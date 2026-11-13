package com.nguevara.obdpulse.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.VehicleMetricAlert
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
    Metrics.OIL_TEMP, Metrics.MAF, Metrics.MAP, Metrics.STFT1, Metrics.LTFT1,
    Metrics.STFT2, Metrics.LTFT2, Metrics.LAMBDA_WIDE, Metrics.LAMBDA_CMD,
)
private val DASH_SLOT_DEFAULTS = listOf(
    Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
    Metrics.ECONOMY, Metrics.VOLTAGE, Metrics.IAT, Metrics.FUEL_LEVEL,
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
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

    val autoTileKeys = tileKeys(vm)
    val savedKeys = settings.dashboardMetricKeys
    val dashboardSlots = remember(connected.info, savedKeys, autoTileKeys) {
        DASH_SLOT_DEFAULTS.indices.map { index ->
            val saved = savedKeys.getOrNull(index)
            val fallback = if (index < 5) DASH_SLOT_DEFAULTS[index] else autoTileKeys.getOrNull(index - 5)
                ?: DASH_SLOT_DEFAULTS[index]
            saved?.takeIf(vm::isAvailable) ?: fallback
        }
    }
    LaunchedEffect(dashboardSlots) { vm.watch(DASH_KEYS + dashboardSlots) }
    val tick by vm.historyTick.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val vehicleAlerts by vm.vehicleAlerts.collectAsStateWithLifecycle()
    val tripMetricKeys by vm.tripMetricKeys.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val tripMetrics = remember(connected.info, activeProfile) { vm.availableMetrics() }
    val context = LocalContext.current
    var recordGps by remember { mutableStateOf(false) }
    val requestTripLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            vm.startTrip(recordGps = true)
        } else {
            vm.notify("No se concedió ubicación. Desactiva GPS para iniciar el registro sin ruta.")
        }
    }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val availableDashboardMetrics = remember(connected.info, activeProfile) { vm.availableMetrics() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vehicleAlerts.isNotEmpty()) {
            SectionCard(title = "Alertas de operación") {
                vehicleAlerts.forEach { alert ->
                    VehicleAlertRow(alert, settings)
                }
                Text(
                    "Umbrales orientativos para el vehículo seleccionado; confirma la especificación del fabricante y no ignores alertas del tablero del auto.",
                    fontSize = 11.sp,
                    color = Palette.TextMuted,
                )
            }
        }

        // ---- Medidores principales ----
        SectionCard(contentPadding = 10) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rpm = values[Metrics.RPM]
                val rpmMax = ceil((settings.redlineRpm + 1500) / 1000.0) * 1000.0
                listOf(dashboardSlots[0], dashboardSlots[1]).forEachIndexed { index, key ->
                    val metric = Metrics[key] ?: return@forEachIndexed
                    val rawValue = values[key]
                    val (rangeMin, rangeMax) = Units.range(metric, settings)
                    val gaugeMin = if (key == Metrics.RPM || key == Metrics.SPEED) 0.0 else rangeMin
                    val gaugeMax = when (key) {
                        Metrics.RPM -> rpmMax
                        Metrics.SPEED -> 200.0
                        else -> rangeMax.takeIf { it > gaugeMin } ?: metric.max
                    }
                    ArcGauge(
                        value = rawValue,
                        min = gaugeMin,
                        max = gaugeMax,
                        title = if (key == Metrics.RPM) "rpm ×1000" else metric.short,
                        unit = Units.unit(metric, settings),
                        valueText = Units.display(metric, rawValue, settings),
                        color = if (index == 0) Palette.Cyan else Palette.Amber,
                        redFrom = if (key == Metrics.RPM) settings.redlineRpm.toDouble() else null,
                        majorTicks = 5,
                        tickLabel = {
                            if (key == Metrics.RPM) (it / 1000).roundToInt().toString()
                            else Units.format(it, 0)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(onClick = {}, onLongClick = { editingSlot = index }),
                    )
                }
            }
        }

        // ---- Anillos ----
        SectionCard(contentPadding = 12) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (2..4).forEach { slot ->
                    Ring(
                        vm,
                        settings,
                        values,
                        dashboardSlots[slot],
                        listOf(Palette.Green, Palette.Violet, Palette.Pink)[slot - 2],
                        modifier = Modifier.weight(1f),
                        warnC = when (dashboardSlots[slot]) {
                            Metrics.COOLANT -> 105.0
                            Metrics.OIL_TEMP -> 130.0
                            else -> null
                        },
                        dangerC = when (dashboardSlots[slot]) {
                            Metrics.COOLANT -> 112.0
                            Metrics.OIL_TEMP -> 140.0
                            else -> null
                        },
                        onLongClick = { editingSlot = slot },
                    )
                }
            }
        }

        // ---- Tarjetas ----
        val tiles = (5 until 5 + autoTileKeys.size).toList()
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { slot ->
                    val key = dashboardSlots[slot]
                    DashTile(
                        vm,
                        settings,
                        values,
                        key,
                        tick,
                        Modifier.weight(1f),
                        onLongClick = { editingSlot = slot },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Mantén presionado cualquier medidor para cambiar la variable que muestra.",
            fontSize = 11.sp,
            color = Palette.TextMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ---- Viaje ----
        if (vm.isAvailable(Metrics.SPEED)) {
            TripCard(
                trip = trip,
                settings = settings,
                metrics = tripMetrics,
                selectedMetricKeys = tripMetricKeys,
                recordGps = recordGps,
                onRecordGpsChange = { recordGps = it },
                onMetricToggle = vm::setTripMetric,
                onStart = {
                    if (recordGps) {
                        val fineGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        val coarseGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fineGranted || coarseGranted) vm.startTrip(recordGps = true)
                        else requestTripLocation.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    } else {
                        vm.startTrip()
                    }
                },
                onStop = { vm.stopTrip() },
            )
        }
    }

    editingSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { editingSlot = null },
            title = { Text("Cambiar variable") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    availableDashboardMetrics.forEach { metric ->
                        TextButton(
                            onClick = {
                                val next = dashboardSlots.toMutableList()
                                val existingIndex = next.indexOf(metric.key)
                                if (existingIndex >= 0 && existingIndex != slot) {
                                    next[existingIndex] = next[slot]
                                }
                                next[slot] = metric.key
                                vm.updateSettings { it.copy(dashboardMetricKeys = next) }
                                editingSlot = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(metric.name, color = Palette.TextPrimary)
                                Text("${metric.short} · ${metric.unit}", color = Palette.TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingSlot = null }) { Text("Cancelar") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

@Composable
private fun VehicleAlertRow(alert: VehicleMetricAlert, settings: AppSettings) {
    val metric = Metrics[alert.metricKey]
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Palette.Amber)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "${metric?.name ?: alert.metricKey} · ${metric?.let { Units.display(it, alert.value, settings) } ?: alert.value} ${metric?.let { Units.unit(it, settings) }.orEmpty()}",
                color = Palette.Amber,
                fontSize = 13.sp,
            )
            Text(alert.message, color = Palette.TextSecondary, fontSize = 12.sp)
            Text(alert.vehicleName, color = Palette.TextMuted, fontSize = 10.sp)
        }
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
@OptIn(ExperimentalFoundationApi::class)
private fun Ring(
    vm: MainViewModel,
    settings: AppSettings,
    values: Map<String, Double>,
    key: String,
    color: Color,
    modifier: Modifier,
    warnC: Double? = null,
    dangerC: Double? = null,
    onLongClick: () -> Unit = {},
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
        modifier = modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DashTile(
    vm: MainViewModel,
    settings: AppSettings,
    values: Map<String, Double>,
    key: String,
    tick: Long,
    modifier: Modifier,
    onLongClick: () -> Unit,
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
            modifier = modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
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
        modifier = modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
    )
}

@Composable
private fun TripCard(
    trip: TripState,
    settings: AppSettings,
    metrics: List<Metric>,
    selectedMetricKeys: Set<String>,
    recordGps: Boolean,
    onRecordGpsChange: (Boolean) -> Unit,
    onMetricToggle: (String, Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var showMetricPicker by remember { mutableStateOf(false) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = recordGps,
                    onCheckedChange = onRecordGpsChange,
                )
                Column {
                    Text("Guardar ruta GPS del celular", color = Palette.TextPrimary, fontSize = 13.sp)
                    Text(
                        "Añade latitud, longitud y precisión al CSV. Se solicitará permiso al iniciar; mantén la app visible y la ubicación activa.",
                        color = Palette.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showMetricPicker = true }, modifier = Modifier.weight(1f)) {
                    Text("Elegir variables para guardar")
                }
                Text("${metrics.count { it.key in selectedMetricKeys }} seleccionadas", color = Palette.TextMuted, fontSize = 11.sp)
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar registro de viaje")
            }
        }
        Text(
            when {
                trip.active && trip.gpsEnabled ->
                    "El GPS añade ubicaciones al CSV mientras la app registra el viaje. La ruta es información sensible y se guarda localmente."
                trip.active ->
                    "Guarda las variables seleccionadas en CSV. Velocidad y consumo también se usan para calcular estadísticas."
                else ->
                    "Guarda las variables elegidas en CSV desde Más → Viajes. Velocidad y consumo también se usan para calcular estadísticas."
            },
            fontSize = 12.sp,
            color = Palette.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (showMetricPicker) {
        AlertDialog(
            onDismissRequest = { showMetricPicker = false },
            title = { Text("Variables del registro") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Selecciona las columnas que se escribirán en el CSV. Algunas lecturas internas pueden usarse para las estadísticas sin agregarse como columnas.",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    metrics.forEach { metric ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = metric.key in selectedMetricKeys,
                                onCheckedChange = { onMetricToggle(metric.key, it) },
                            )
                            Column {
                                Text(metric.name, color = Palette.TextPrimary, fontSize = 13.sp)
                                Text("${metric.short} (${metric.unit})", color = Palette.TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMetricPicker = false }) { Text("Listo") } },
            containerColor = Palette.Surface,
        )
    }
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
