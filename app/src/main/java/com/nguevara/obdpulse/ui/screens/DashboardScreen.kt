package com.nguevara.obdpulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.data.EconUnit
import com.nguevara.obdpulse.data.TripState
import com.nguevara.obdpulse.data.Units
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.ui.components.ArcGauge
import com.nguevara.obdpulse.ui.components.EmptyState
import com.nguevara.obdpulse.ui.components.MetricTile
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.RingGauge
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val DASH_KEYS = setOf(
    Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
    Metrics.ECONOMY, Metrics.FUEL_RATE, Metrics.VOLTAGE, Metrics.IAT, Metrics.FUEL_LEVEL,
    Metrics.OIL_TEMP, Metrics.MAF, Metrics.MAP,
)

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    conn: ConnState,
    settings: AppSettings,
    values: Map<String, Double>,
    onGoConnect: () -> Unit,
) {
    val connected = conn as? ConnState.Connected
    if (connected == null) {
        EmptyState(
            icon = Icons.Rounded.DirectionsCar,
            title = "Conecta tu vehículo",
            message = "Conecta el adaptador ELM327 para ver revoluciones, velocidad, temperatura, consumo y más en tiempo real.",
            primaryLabel = "Ir a conexión",
            onPrimary = onGoConnect,
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }
    if (!connected.info.ecuOk) {
        EmptyState(
            icon = Icons.Rounded.Warning,
            title = "La ECU no responde",
            message = connected.info.ecuMessage ?: "Pon el encendido en ON o arranca el motor.",
            primaryLabel = "Reintentar",
            onPrimary = { vm.connect(connected.device) },
        )
        return
    }

    LaunchedEffect(Unit) { vm.watch(DASH_KEYS) }
    val tick by vm.historyTick.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    var showTripSetup by rememberSaveable { mutableStateOf(false) }
    var tripKeysCsv by rememberSaveable { mutableStateOf("") }
    val availableTripMetrics = remember(connected.info) { vm.availableMetrics() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Medidores principales ----
        SectionCard(contentPadding = 10) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rpm = values[Metrics.RPM]
                val rpmMax = ceil((settings.redlineRpm + 1500) / 1000.0) * 1000.0
                ArcGauge(
                    value = rpm,
                    min = 0.0,
                    max = rpmMax,
                    title = "rpm ×1000",
                    unit = "rpm",
                    valueText = Units.format(rpm, 0),
                    color = Palette.Cyan,
                    redFrom = settings.redlineRpm.toDouble(),
                    majorTicks = (rpmMax / 1000).toInt(),
                    tickLabel = { (it / 1000).roundToInt().toString() },
                    modifier = Modifier.weight(1f),
                )
                val speed = values[Metrics.SPEED]
                ArcGauge(
                    value = speed,
                    min = 0.0,
                    max = 200.0,
                    title = "velocidad",
                    unit = "km/h",
                    valueText = Units.format(speed, 0),
                    color = Palette.Amber,
                    majorTicks = 5,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- Anillos ----
        SectionCard(contentPadding = 12) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ring(vm, settings, values, Metrics.COOLANT, Palette.Green, warnC = 105.0, dangerC = 112.0, modifier = Modifier.weight(1f))
                Ring(vm, settings, values, Metrics.LOAD, Palette.Violet, modifier = Modifier.weight(1f))
                Ring(vm, settings, values, Metrics.THROTTLE, Palette.Pink, modifier = Modifier.weight(1f))
            }
        }

        // ---- Tarjetas ----
        val tiles = remember(connected.info) { tileKeys(vm) }
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { key ->
                    DashTile(vm, settings, values, key, tick, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ---- Viaje ----
        if (vm.isAvailable(Metrics.SPEED)) {
            TripCard(
                trip,
                settings,
                onStart = {
                    if (tripKeysCsv.isBlank()) {
                        tripKeysCsv = MainViewModel.TRIP_KEYS
                            .filter { key -> availableTripMetrics.any { it.key == key } }
                            .joinToString(",")
                    }
                    showTripSetup = true
                },
                onStop = { vm.stopTrip() },
            )
        }
    }

    if (showTripSetup) {
        TripSelectionDialog(
            metrics = availableTripMetrics,
            selectedCsv = tripKeysCsv,
            onSelectionChange = { tripKeysCsv = it },
            onDismiss = { showTripSetup = false },
            onStart = {
                vm.startTrip(tripKeysCsv.split(',').filter(String::isNotBlank).toSet())
                showTripSetup = false
            },
        )
    }
}

/** Elige hasta 4 tarjetas según lo que soporte el carro. */
private fun tileKeys(vm: MainViewModel): List<String> {
    val candidates = listOf(
        Metrics.ECONOMY, Metrics.VOLTAGE, Metrics.IAT, Metrics.FUEL_LEVEL,
        Metrics.OIL_TEMP, Metrics.MAF, Metrics.MAP,
    )
    return candidates.filter { vm.isAvailable(it) }.take(4)
}

private fun iconFor(key: String): ImageVector = when (key) {
    Metrics.ECONOMY, Metrics.FUEL_RATE, Metrics.FUEL_LEVEL -> Icons.Rounded.LocalGasStation
    Metrics.VOLTAGE -> Icons.Rounded.BatteryChargingFull
    Metrics.IAT, Metrics.COOLANT -> Icons.Rounded.Whatshot
    Metrics.OIL_TEMP -> Icons.Rounded.Opacity
    else -> Icons.Rounded.Speed
}

private fun colorFor(key: String): Color = when (key) {
    Metrics.ECONOMY -> Palette.Green
    Metrics.VOLTAGE -> Palette.Amber
    Metrics.IAT -> Palette.Cyan
    Metrics.FUEL_LEVEL -> Palette.Violet
    Metrics.OIL_TEMP -> Palette.Pink
    else -> Palette.Cyan
}

@Composable
private fun Ring(
    vm: MainViewModel,
    settings: AppSettings,
    values: Map<String, Double>,
    key: String,
    color: Color,
    modifier: Modifier,
    warnC: Double? = null,
    dangerC: Double? = null,
) {
    val metric = Metrics[key] ?: return
    if (!vm.isAvailable(key)) {
        Spacer(modifier)
        return
    }
    val raw = values[key]
    val shown = raw?.let { Units.convert(metric, it, settings) }
    val (lo, hi) = Units.range(metric, settings)
    // Para temperatura usamos una escala útil (≈ 40–130 °C) en lugar del rango completo del PID.
    val (min, max) = if (metric.kind == Metric.Kind.TEMPERATURE) {
        Units.convert(metric, 40.0, settings) to Units.convert(metric, 130.0, settings)
    } else {
        lo to hi
    }
    RingGauge(
        value = shown,
        min = min,
        max = max,
        title = metric.short,
        valueText = Units.format(shown, metric.decimals),
        unit = Units.unit(metric, settings),
        color = color,
        warnFrom = warnC?.let { Units.convert(metric, it, settings) },
        dangerFrom = dangerC?.let { Units.convert(metric, it, settings) },
        modifier = modifier,
    )
}

@Composable
private fun DashTile(
    vm: MainViewModel,
    settings: AppSettings,
    values: Map<String, Double>,
    key: String,
    tick: Long,
    modifier: Modifier,
) {
    val metric = Metrics[key] ?: return
    val samples = remember(tick, key) { vm.history(key) }
    val raw = values[key]

    if (key == Metrics.ECONOMY) {
        val speed = values[Metrics.SPEED] ?: 0.0
        val rate = values[Metrics.FUEL_RATE]
        val idle = speed < 3.0
        val text = if (idle) "Ralentí" else Units.display(metric, raw, settings)
        val sub = rate?.let { String.format(Locale.getDefault(), "Consumo: %.2f L/h", it) }
        MetricTile(
            title = metric.short,
            value = text,
            unit = if (idle) "" else Units.unit(metric, settings),
            icon = iconFor(key),
            color = colorFor(key),
            samples = samples,
            subtitle = sub,
            modifier = modifier,
        )
        return
    }

    MetricTile(
        title = metric.short,
        value = Units.display(metric, raw, settings),
        unit = Units.unit(metric, settings),
        icon = iconFor(key),
        color = colorFor(key),
        samples = samples,
        modifier = modifier,
    )
}

@Composable
private fun TripCard(trip: TripState, settings: AppSettings, onStart: () -> Unit, onStop: () -> Unit) {
    SectionCard(
        title = "Viaje",
        trailing = { if (trip.active) Pill("● Grabando", Palette.Red) },
    ) {
        val avg = trip.avgKmPerL?.let { Units.econ(it, settings.econUnit) }
        val econLabel = settings.econUnit.label.substringBefore(" (")
        Row(Modifier.fillMaxWidth()) {
            TripStat("Tiempo", formatDuration(trip.elapsedS), "", Modifier.weight(1f))
            TripStat("Distancia", Units.format(trip.distanceKm, 2), "km", Modifier.weight(1f))
            TripStat(
                if (settings.econUnit == EconUnit.L_PER_100KM) "Consumo" else "Rendimiento",
                Units.format(avg, 1),
                econLabel,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            TripStat("Vel. promedio", Units.format(trip.avgSpeed, 0), "km/h", Modifier.weight(1f))
            TripStat("Vel. máxima", Units.format(trip.maxSpeed, 0), "km/h", Modifier.weight(1f))
            TripStat("Combustible", Units.format(trip.fuelL, 2), "L", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (trip.active) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Detener y guardar CSV")
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar registro de viaje")
            }
            Text(
                "Elige qué variables guardar en el CSV. Puedes compartir el archivo desde Más → Viajes.",
                fontSize = 12.sp,
                color = Palette.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TripSelectionDialog(
    metrics: List<Metric>,
    selectedCsv: String,
    onSelectionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    val selected = selectedCsv.split(',').filter(String::isNotBlank).toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Variables del registro") },
        text = {
            Column {
                Text(
                    "${selected.size} seleccionadas. Velocidad y consumo también se usan internamente para calcular las estadísticas del viaje.",
                    fontSize = 12.sp,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(metrics, key = { it.key }) { metric ->
                        val checked = metric.key in selected
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    val next = if (checked) selected - metric.key else selected + metric.key
                                    onSelectionChange(metrics.filter { it.key in next }.joinToString(",") { it.key })
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(metric.name, fontSize = 14.sp, color = Palette.TextPrimary)
                                Text(metric.unit, fontSize = 11.sp, color = Palette.TextMuted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onStart, enabled = selected.isNotEmpty()) { Text("Iniciar registro") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = Palette.SurfaceHigh,
    )
}

@Composable
private fun TripStat(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 12.sp, color = Palette.TextSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle, fontSize = 20.sp, color = Palette.TextPrimary)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
