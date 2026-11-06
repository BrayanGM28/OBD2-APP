package com.nguevara.obdpulse.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Enlace por Bluetooth Low Energy (GATT).
 *
 * Los adaptadores ELM327 BLE exponen un "puerto serie" sobre dos características:
 *  - una con NOTIFY/INDICATE por donde llegan las respuestas,
 *  - otra con WRITE/WRITE_NO_RESPONSE por donde se envían los comandos.
 * Primero se prueban los pares conocidos de los fabricantes más comunes; si ninguno
 * coincide, se busca de forma genérica cualquier servicio que tenga ambas.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    override val device: ObdDevice,
) : ObdTransport {

    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var open = false

    override val isOpen: Boolean
        get() = open

    // Señales de los callbacks GATT (que llegan en hilos del sistema) hacia las corrutinas.
    private val connected = CompletableDeferred<Unit>()
    private val servicesReady = CompletableDeferred<Unit>()

    @Volatile
    private var descriptorWritten = CompletableDeferred<Unit>()

    @Volatile
    private var chunkWritten: CompletableDeferred<Unit>? = null

    private val writeMutex = Mutex()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = TransportException("El adaptador BLE se desconectó (código $status)")
                connected.completeExceptionally(error)
                servicesReady.completeExceptionally(error)
                descriptorWritten.completeExceptionally(error)
                chunkWritten?.completeExceptionally(error)
                if (open) {
                    open = false
                    incoming.close(error)
                }
                try {
                    g.close()
                } catch (_: Exception) {
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesReady.complete(Unit)
            } else {
                servicesReady.completeExceptionally(TransportException("Falló el descubrimiento de servicios BLE ($status)"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Aunque falle, intentamos continuar: algunos clones notifican sin CCCD válido.
            descriptorWritten.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            chunkWritten?.complete(Unit)
        }

        // Android 13 o superior
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.trySend(value.copyOf())
        }

        // Android 12 o inferior
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = legacyValue(c) ?: return
                incoming.trySend(value.copyOf())
            }
        }
    }

    override suspend fun open() {
        val remote: BluetoothDevice = adapter.getRemoteDevice(device.address)
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        val g = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("No se pudo iniciar la conexión BLE")
        gatt = g

        try {
            withTimeout(15_000) { connected.await() }
            delay(300) // pequeño respiro recomendado antes de descubrir servicios
            if (!g.discoverServices()) throw TransportException("No se pudo iniciar el descubrimiento de servicios")
            withTimeout(15_000) { servicesReady.await() }

            selectCharacteristics(g)
            val notify = notifyChar
                ?: throw TransportException("Este dispositivo BLE no expone un puerto serie compatible con ELM327")
            if (writeChar == null) throw TransportException("No se encontró la característica de escritura")

            enableNotifications(g, notify)
            open = true
        } catch (e: TimeoutCancellationException) {
            close()
            throw TransportException("Tiempo de espera agotado al conectar por BLE", e)
        } catch (e: TransportException) {
            close()
            throw e
        } catch (e: SecurityException) {
            close()
            throw TransportException("Falta el permiso de Bluetooth", e)
        }
    }

    private fun selectCharacteristics(g: BluetoothGatt) {
        // 1) Pares conocidos (servicio, notificación, escritura)
        for ((service, notify, write) in KNOWN_PAIRS) {
            val s = g.getService(service) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            notifyChar = n
            writeChar = w
            return
        }
        // 2) Búsqueda genérica en servicios no estándar
        for (s in g.services) {
            if (s.uuid in IGNORED_SERVICES) continue
            val n = s.characteristics.firstOrNull {
                (it.properties and (PROP_NOTIFY or PROP_INDICATE)) != 0
            }
            val w = s.characteristics.firstOrNull {
                (it.properties and (PROP_WRITE or PROP_WRITE_NR)) != 0
            }
            if (n != null && w != null) {
                notifyChar = n
                writeChar = w
                return
            }
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD_UUID) ?: return
        val value = if ((c.properties and PROP_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descriptorWritten = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            legacyWriteDescriptor(g, cccd, value)
        }
        if (started) withTimeoutOrNull(5_000) { descriptorWritten.await() }
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw TransportException("Sin conexión BLE")
        val c = writeChar ?: throw TransportException("Sin característica de escritura")
        val type = if ((c.properties and PROP_WRITE_NR) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        writeMutex.withLock {
            // BLE "clásico" admite 20 bytes por escritura sin negociar MTU: troceamos.
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 20, data.size)
                val chunk = data.copyOfRange(offset, end)
                val done = CompletableDeferred<Unit>()
                chunkWritten = done
                var started = writeChunk(g, c, chunk, type)
                if (!started) {
                    delay(40) // la pila BLE puede estar ocupada; reintento corto
                    started = writeChunk(g, c, chunk, type)
                    if (!started) throw TransportException("No se pudo escribir en el adaptador BLE")
                }
                withTimeoutOrNull(300) { done.await() }
                offset = end
            }
        }
    }

    private fun writeChunk(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray, type: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            legacyWriteCharacteristic(g, c, chunk, type)
        }
    }

    override fun close() {
        open = false
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
        } catch (_: Exception) {
        }
        try {
            g?.close()
        } catch (_: Exception) {
        }
        incoming.close()
    }

    // ---- APIs antiguas (Android 12 o inferior), aisladas para suprimir avisos de deprecación ----

    @Suppress("DEPRECATION")
    private fun legacyValue(c: BluetoothGattCharacteristic): ByteArray? = c.value

    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.value = value
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteCharacteristic(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        chunk: ByteArray,
        type: Int,
    ): Boolean {
        c.writeType = type
        c.value = chunk
        return g.writeCharacteristic(c)
    }

    companion object {
        private const val PROP_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private const val PROP_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val PROP_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        private const val PROP_WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        val CCCD_UUID: UUID = uuid16("2902")

        /** (servicio, característica de notificación, característica de escritura) */
        private val KNOWN_PAIRS: List<Triple<UUID, UUID, UUID>> = listOf(
            Triple(uuid16("fff0"), uuid16("fff1"), uuid16("fff2")), // la mayoría de clones BLE
            Triple(uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1")), // módulos HM-10 / CC2541
            Triple(uuid16("18f0"), uuid16("2af0"), uuid16("2af1")), // Vgate / Vlink
            Triple(
                UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            ), // OBDLink / Veepeak
        )

        /** Servicios estándar que nunca son el puerto serie. */
        private val IGNORED_SERVICES: Set<UUID> = setOf(
            uuid16("1800"), // Generic Access
            uuid16("1801"), // Generic Attribute
            uuid16("180a"), // Device Information
            uuid16("180f"), // Battery
        )
    }
}
