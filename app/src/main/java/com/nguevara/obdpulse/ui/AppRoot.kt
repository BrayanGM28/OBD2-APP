package com.nguevara.obdpulse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.bt.LinkType
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.StatusDot
import com.nguevara.obdpulse.ui.screens.ChartsScreen
import com.nguevara.obdpulse.ui.screens.ConnectScreen
import com.nguevara.obdpulse.ui.screens.DashboardScreen
import com.nguevara.obdpulse.ui.screens.DiagnosticsScreen
import com.nguevara.obdpulse.ui.screens.ToolsScreen
import com.nguevara.obdpulse.ui.screens.UnknownVehicleRegistrationDialog
import com.nguevara.obdpulse.ui.theme.Palette
import java.util.Locale
import android.widget.Toast

enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("Conexión", Icons.Rounded.Bluetooth),
    DASHBOARD("Tablero", Icons.Rounded.Speed),
    CHARTS("Gráficas", Icons.AutoMirrored.Rounded.ShowChart),
    DIAGNOSTICS("Diagnóstico", Icons.Rounded.Build),
    TOOLS("Más", Icons.Rounded.Tune),
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CONNECT) }
    var lastAutoSwitch by rememberSaveable { mutableStateOf<String?>(null) }
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val scannedVehiclePending by vm.scannedVehiclePending.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Avisos cortos del ViewModel (protocolo aprendido, resultado de escaneos…)
    LaunchedEffect(Unit) {
        vm.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // Al conectar, ir al tablero (una sola vez por conexión). Si la conexión falla, volver a Conexión.
    val connectedAddress = (conn as? ConnState.Connected)?.device?.address
    LaunchedEffect(connectedAddress) {
        if (connectedAddress != null && lastAutoSwitch != connectedAddress) {
            lastAutoSwitch = connectedAddress
            if (tab == Tab.CONNECT) tab = Tab.DASHBOARD
        }
        if (connectedAddress == null) lastAutoSwitch = null
    }
    LaunchedEffect(conn) {
        if (conn is ConnState.Failed) tab = Tab.CONNECT
    }

    // Mantener la pantalla encendida mientras se usa la app (ajustable).
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = Palette.Background,
        topBar = { Header(conn, values[Metrics.VOLTAGE], profile?.let { "${it.title} ${it.year}" }) },
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Cyan,
                            selectedTextColor = Palette.Cyan,
                            indicatorColor = Palette.Cyan.copy(alpha = 0.16f),
                            unselectedIconColor = Palette.TextMuted,
                            unselectedTextColor = Palette.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val goConnect = { tab = Tab.CONNECT }
            when (tab) {
                Tab.CONNECT -> ConnectScreen(vm, conn, settings)
                Tab.DASHBOARD -> DashboardScreen(vm, conn, settings, values, goConnect)
                Tab.CHARTS -> ChartsScreen(vm, conn, settings, values, goConnect)
                Tab.DIAGNOSTICS -> DiagnosticsScreen(vm, conn, goConnect)
                Tab.TOOLS -> ToolsScreen(vm, conn, settings, values)
            }
        }

        val connected = conn as? ConnState.Connected
        val vehicleMismatch = connected?.info?.vin?.let { vin ->
            profile?.vin?.let { savedVin -> !vin.equals(savedVin, ignoreCase = true) } ?: true
        } == true
        if (connected != null && connected.info.ecuOk && connected.device.type != LinkType.DEMO &&
            !scannedVehiclePending && (profile == null || vehicleMismatch)
        ) {
            UnknownVehicleRegistrationDialog(vm, vehicleMismatch)
        }
    }
}

@Composable
private fun Header(conn: ConnState, voltage: Double?, vehicle: String?) {
    val (dotColor, status) = when (conn) {
        is ConnState.Idle -> Palette.TextMuted to "Sin conexión"
        is ConnState.Connecting -> Palette.Amber to "Conectando a ${conn.device.name}…"
        is ConnState.Connected -> {
            val label = if (conn.device.type == LinkType.DEMO) "Modo demo" else conn.device.name
            (if (conn.info.ecuOk) Palette.Green else Palette.Amber) to label
        }

        is ConnState.Failed -> Palette.Red to "Error de conexión"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("OBD Pulse", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (vehicle != null) "$status · $vehicle" else status,
                    fontSize = 12.sp,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (voltage != null && conn is ConnState.Connected) {
            val color = when {
                voltage < 11.8 -> Palette.Red
                voltage < 12.3 -> Palette.Amber
                else -> Palette.Green
            }
            Pill(String.format(Locale.getDefault(), "%.1f V", voltage), color)
        }
    }
}
