package com.nguevara.obdpulse.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.bt.LinkType
import com.nguevara.obdpulse.bt.ObdDevice
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.ui.components.InfoRow
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.theme.Palette
import com.nguevara.obdpulse.vehicle.OnlineService

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun ConnectScreen(vm: MainViewModel, conn: ConnState, settings: AppSettings) {
    val context = LocalContext.current
    val devices by vm.scanner.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val protoScan by vm.protoScan.collectAsStateWithLifecycle()
    var showGarage by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.scanner.isBluetoothOn) pendingAction?.invoke()
        pendingAction = null
    }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            permissionDenied = false
            vm.loadBonded()
            if (vm.scanner.isBluetoothOn) {
                pendingAction?.invoke()
                pendingAction = null
            } else {
                try {
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {
                    pendingAction = null
                }
            }
        } else {
            permissionDenied = true
            pendingAction = null
        }
    }

    /** Asegura permisos y Bluetooth encendido antes de ejecutar [action]. */
    fun withBluetooth(action: () -> Unit) {
        if (!vm.scanner.isBluetoothAvailable) {
            Toast.makeText(context, "Este teléfono no tiene Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasPermissions(context)) {
            pendingAction = action
            requestPermissions.launch(requiredPermissions())
            return
        }
        if (!vm.scanner.isBluetoothOn) {
            pendingAction = action
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                pendingAction = null
                Toast.makeText(context, "Activa el Bluetooth desde los ajustes", Toast.LENGTH_LONG).show()
            }
            return
        }
        action()
    }

    LaunchedEffect(Unit) {
        vm.watch(emptySet())
        if (hasPermissions(context)) vm.loadBonded()
    }

    val busy = conn is ConnState.Connecting

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                conn = conn,
                onDisconnect = { vm.disconnect() },
                onRetry = { device ->
                    if (device.type == LinkType.DEMO) vm.connect(device) else withBluetooth { vm.connect(device) }
                },
                onDismiss = { vm.dismissError() },
            )
        }

        if (conn !is ConnState.Connecting) {
            item { ActiveVehicleCard(vm, profile) { showGarage = true } }
        }

        // La ECU no respondió con el orden recomendado: ofrecer el escaneo completo y la búsqueda en internet.
        val connectedNoEcu = conn is ConnState.Connected && !conn.info.ecuOk
        if (connectedNoEcu || protoScan.loading || protoScan.results.isNotEmpty()) {
            item {
                SectionCard(title = "Encontrar el protocolo") {
                    if (protoScan.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(protoScan.step, fontSize = 14.sp, color = Palette.TextSecondary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Cada protocolo K-Line tarda hasta 12 s. Mantén el motor encendido.", fontSize = 12.sp, color = Palette.TextMuted)
                    } else {
                        Text(
                            "Prueba los 10 protocolos del ELM327 uno por uno (1–2 minutos) para saber con cuál responde tu vehículo.",
                            fontSize = 13.sp,
                            color = Palette.TextSecondary,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.scanAllProtocols() },
                                enabled = conn is ConnState.Connected,
                                modifier = Modifier.weight(1f),
                            ) { Text("Probar todos") }
                            val prof = profile
                            if (prof != null) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OnlineService.protocolSearchUrl(prof))))
                                        } catch (_: Exception) {
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Buscar en internet") }
                            }
                        }
                    }
                    protoScan.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Notice(it, Palette.Red)
                    }
                    if (protoScan.results.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        protoScan.results.forEach { r ->
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, fontSize = 14.sp, color = if (r.ok) Palette.Green else Palette.TextPrimary)
                                    Text("ATSP${r.code} · ${r.detail}", fontSize = 12.sp, color = Palette.TextMuted)
                                }
                                if (r.ok) Button(onClick = { vm.useProtocol(r.code) }) { Text("Usar") }
                            }
                        }
                        if (protoScan.results.none { it.ok }) {
                            Spacer(Modifier.height(8.dp))
                            Notice(
                                "Ningún protocolo respondió. Con el motor encendido y un adaptador que funciona en otros carros, " +
                                    "lo más probable es que la ECU no hable OBD-II genérico (solo el protocolo propio de la marca) " +
                                    "o que el circuito K-Line del adaptador no funcione.",
                                Palette.Amber,
                            )
                        }
                    }
                }
            }
        }

        if (conn !is ConnState.Connected) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (scanning) vm.stopScan() else withBluetooth { vm.startScan() } },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(if (scanning) Icons.Rounded.Stop else Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (scanning) "Detener" else "Buscar adaptador")
                    }
                    OutlinedButton(
                        onClick = { vm.connectDemo() },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Modo demo")
                    }
                }
            }

            val last = settings.lastDevice
            if (last != null && conn !is ConnState.Connecting) {
                item {
                    SectionCard(title = "Último adaptador") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(last.name, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                                Text("${last.type.label} · ${last.address}", fontSize = 12.sp, color = Palette.TextMuted)
                            }
                            Button(onClick = { withBluetooth { vm.connect(last) } }) { Text("Conectar") }
                        }
                    }
                }
            }

            if (permissionDenied) {
                item {
                    Notice(
                        "Sin permiso de Bluetooth no es posible buscar ni conectar el adaptador. " +
                            "Puedes concederlo en Ajustes del sistema → Aplicaciones → OBD Pulse → Permisos.",
                        Palette.Red,
                    )
                }
            }

            if (scanning) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Cyan, trackColor = Palette.Track) }
            }

            if (devices.isNotEmpty()) {
                item {
                    Text(
                        "DISPOSITIVOS",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(devices, key = { "${it.type}-${it.address}" }) { d ->
                    DeviceRow(d, enabled = !busy) { withBluetooth { vm.connect(d) } }
                }
            }

            item {
                Notice(
                    "El ELM327 Mini azul suele ser Bluetooth Clásico: si no aparece, emparéjalo primero en los ajustes " +
                        "de Bluetooth del teléfono (PIN 1234 o 0000) y vuelve a buscar. Los adaptadores BLE aparecen " +
                        "directamente. El conector OBD-II suele estar bajo el volante; pon el encendido en ON.",
                    Palette.Cyan,
                )
            }
        }
    }

    if (showGarage) GarageDialog(vm) { showGarage = false }

}

@Composable
private fun StatusCard(
    conn: ConnState,
    onDisconnect: () -> Unit,
    onRetry: (ObdDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard {
        when (conn) {
            is ConnState.Idle -> {
                StatusHeader(Icons.Rounded.BluetoothDisabled, Palette.TextSecondary, "Sin conexión", "Busca tu adaptador ELM327 o prueba el modo demo.")
            }

            is ConnState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(40.dp), color = Palette.Cyan, strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(conn.device.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                        Text(conn.step, fontSize = 14.sp, color = Palette.TextSecondary)
                    }
                }
            }

            is ConnState.Connected -> {
                val info = conn.info
                StatusHeader(
                    Icons.Rounded.BluetoothConnected,
                    if (info.ecuOk) Palette.Green else Palette.Amber,
                    conn.device.name,
                    if (info.ecuOk) "Conectado y comunicando con el vehículo" else "Adaptador conectado, la ECU no responde",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(conn.device.type.label, Palette.Cyan)
                    if (info.ecuOk) Pill("${info.supportedPids.count { it % 0x20 != 0 }} PIDs", Palette.Green)
                }
                Spacer(Modifier.height(10.dp))
                InfoRow("Adaptador", info.elmVersion)
                InfoRow("Protocolo", info.protocol)
                InfoRow("VIN", info.vin ?: "No disponible")
                if (info.attempts.size > 1 || !info.ecuOk) {
                    Spacer(Modifier.height(6.dp))
                    Text("Protocolos probados", fontSize = 12.sp, color = Palette.TextSecondary)
                    info.attempts.forEach { (code, result) ->
                        InfoRow(shortProtocol(code), result, if (result == "Responde") Palette.Green else Palette.TextMuted)
                    }
                }
                if (!info.ecuOk && info.ecuMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Notice(info.ecuMessage, Palette.Amber)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!info.ecuOk) {
                        Button(onClick = { onRetry(conn.device) }, modifier = Modifier.weight(1f)) { Text("Reintentar") }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Red),
                        border = BorderStroke(1.dp, Palette.Red.copy(alpha = 0.5f)),
                    ) { Text("Desconectar") }
                }
            }

            is ConnState.Failed -> {
                StatusHeader(Icons.Rounded.ErrorOutline, Palette.Red, "No se pudo conectar", conn.message)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val device = conn.device
                    if (device != null) {
                        Button(onClick = { onRetry(device) }, modifier = Modifier.weight(1f)) { Text("Reintentar") }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cerrar") }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            Text(subtitle, fontSize = 14.sp, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun DeviceRow(device: ObdDevice, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Surface,
        border = BorderStroke(1.dp, if (device.looksLikeObd) Palette.Cyan.copy(alpha = 0.45f) else Palette.Outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = if (device.looksLikeObd) Palette.Cyan else Palette.TextSecondary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, maxLines = 1)
                Text(device.address, fontSize = 12.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(device.type.label, if (device.type == LinkType.BLE) Palette.Violet else Palette.Cyan)
                    if (device.bonded) Pill("Emparejado", Palette.Green)
                    if (device.looksLikeObd) Pill("OBD", Palette.Amber)
                }
            }
            device.rssi?.let { Text("$it dBm", fontSize = 12.sp, color = Palette.TextMuted) }
        }
    }
}

@Composable
fun Notice(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(12.dp),
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = Palette.TextSecondary, lineHeight = 18.sp)
    }
}
