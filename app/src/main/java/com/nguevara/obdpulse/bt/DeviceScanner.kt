package com.nguevara.obdpulse.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Busca adaptadores por Bluetooth Clásico (descubrimiento + dispositivos emparejados)
 * y por BLE al mismo tiempo, y los combina en una sola lista.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? = manager?.adapter

    private val _devices = MutableStateFlow<List<ObdDevice>>(emptyList())
    val devices: StateFlow<List<ObdDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiverRegistered = false
    private var bleScanning = false

    val isBluetoothAvailable: Boolean
        get() = adapter != null

    val isBluetoothOn: Boolean
        get() = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = deviceExtra(intent) ?: return
                    val name = safeName(dev) ?: return
                    // Los dispositivos solo-BLE los reporta el escáner BLE.
                    if (safeType(dev) == BluetoothDevice.DEVICE_TYPE_LE) return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    upsert(
                        ObdDevice(
                            name = name,
                            address = dev.address,
                            type = LinkType.CLASSIC,
                            bonded = safeBonded(dev),
                            rssi = if (rssi == Short.MIN_VALUE.toInt()) null else rssi,
                        ),
                    )
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!bleScanning) _scanning.value = false
                }
            }
        }
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: safeName(dev) ?: return
            upsert(
                ObdDevice(
                    name = name,
                    address = dev.address,
                    type = LinkType.BLE,
                    bonded = safeBonded(dev),
                    rssi = result.rssi,
                ),
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
    }

    /** Agrega los dispositivos ya emparejados en Ajustes de Bluetooth (muy común con ELM327 Clásico). */
    fun loadBonded() {
        val a = adapter ?: return
        val bonded = try {
            a.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        for (dev in bonded) {
            val name = safeName(dev) ?: continue
            val type = if (safeType(dev) == BluetoothDevice.DEVICE_TYPE_LE) LinkType.BLE else LinkType.CLASSIC
            upsert(ObdDevice(name, dev.address, type, bonded = true))
        }
    }

    fun startScan() {
        val a = adapter ?: return
        if (!isBluetoothOn) return
        loadBonded()
        _scanning.value = true

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, classicReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (_: SecurityException) {
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            a.bluetoothLeScanner?.startScan(null, settings, leCallback)
            bleScanning = true
        } catch (_: Exception) {
            bleScanning = false
        }
    }

    fun stopScan() {
        val a = adapter
        try {
            a?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        if (bleScanning) {
            try {
                a?.bluetoothLeScanner?.stopScan(leCallback)
            } catch (_: Exception) {
            }
            bleScanning = false
        }
        _scanning.value = false
    }

    fun release() {
        stopScan()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(classicReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    @Synchronized
    private fun upsert(device: ObdDevice) {
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.address == device.address && it.type == device.type }
        if (index >= 0) {
            val old = current[index]
            current[index] = device.copy(
                bonded = device.bonded || old.bonded,
                rssi = device.rssi ?: old.rssi,
            )
        } else {
            current += device
        }
        // Orden: primero los que parecen OBD, luego emparejados, luego por intensidad de señal.
        _devices.value = current.sortedWith(
            compareByDescending<ObdDevice> { it.looksLikeObd }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi ?: -200 },
        )
    }

    private fun safeName(dev: BluetoothDevice): String? = try {
        dev.name?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    private fun safeType(dev: BluetoothDevice): Int = try {
        dev.type
    } catch (_: SecurityException) {
        BluetoothDevice.DEVICE_TYPE_UNKNOWN
    }

    private fun safeBonded(dev: BluetoothDevice): Boolean = try {
        dev.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: SecurityException) {
        false
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            legacyDeviceExtra(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDeviceExtra(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
