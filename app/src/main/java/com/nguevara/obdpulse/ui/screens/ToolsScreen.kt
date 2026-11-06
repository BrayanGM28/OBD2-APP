package com.nguevara.obdpulse.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.data.EconUnit
import com.nguevara.obdpulse.data.Units
import com.nguevara.obdpulse.obd.TermLine
import com.nguevara.obdpulse.ui.components.EmptyState
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.components.Sparkline
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import com.nguevara.obdpulse.obd.EcuModule
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.obd.PidNames
import com.nguevara.obdpulse.ui.components.InfoRow
import com.nguevara.obdpulse.vehicle.VehicleProfile
import kotlinx.coroutines.launch

private enum class ToolSection(val label: String) {
    SENSORS("Sensores"),
    MODULES("Módulos"),
    TERMINAL("Terminal"),
    TRIPS("Viajes"),
    VEHICLES("Vehículos"),
    SETTINGS("Ajustes"),
}

/** Protocolos del comando ATSPx del ELM327. */
private val PROTOCOLS = listOf(
    "0" to "Automático (recomendado)",
    "1" to "SAE J1850 PWM (Ford antiguos)",
    "2" to "SAE J1850 VPW (GM antiguos)",
    "3" to "ISO 9141-2 (K-Line)",
    "4" to "ISO 14230-4 KWP, inicio lento",
    "5" to "ISO 14230-4 KWP, inicio rápido",
    "6" to "ISO 15765-4 CAN 11 bit, 500 kbps",
    "7" to "ISO 15765-4 CAN 29 bit, 500 kbps",
    "8" to "ISO 15765-4 CAN 11 bit, 250 kbps",
    "9" to "ISO 15765-4 CAN 29 bit, 250 kbps",
    "A" to "SAE J1939 CAN (camiones)",
)

/** Comandos rápidos de la terminal: (texto del botón, comando). */
private val QUICK_COMMANDS = listOf(
    "ATI" to "ATI",
    "Voltaje" to "ATRV",
    "Protocolo" to "ATDP",
    "PIDs 01-20" to "0100",
    "RPM" to "010C",
    "Velocidad" to "010D",
    "Estado" to "0101",
    "Códigos" to "03",
    "VIN" to "0902",
    "Reiniciar" to "ATZ",
)

@Composable
fun ToolsScreen(
    vm: MainViewModel,
    conn: ConnState,
    settings: AppSettings,
    values: Map<String, Double>,
) {
    var section by rememberSaveable { mutableStateOf(ToolSection.SENSORS) }
    val connected = conn as? ConnState.Connected

    // Solo la sección de sensores necesita sondear datos en vivo; las demás liberan el bus.
    LaunchedEffect(section, connected?.info) {
        if (section == ToolSection.SENSORS && connected != null) {
            vm.watch(vm.availableMetrics().map { it.key }.toSet())
        } else {
            vm.watch(emptySet())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolSection.entries.forEach { s ->
                FilterChip(
                    selected = section == s,
                    onClick = { section = s },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Cyan.copy(alpha = 0.18f),
                        selectedLabelColor = Palette.Cyan,
                        labelColor = Palette.TextSecondary,
                    ),
                )
            }
        }

        when (section) {
            ToolSection.SENSORS -> SensorsSection(vm, connected, settings, values)
            ToolSection.MODULES -> ModulesSection(vm, connected)
            ToolSection.TERMINAL -> TerminalSection(vm, connected != null)
            ToolSection.TRIPS -> TripsSection(vm)
            ToolSection.VEHICLES -> VehiclesSection(vm)
            ToolSection.SETTINGS -> SettingsSection(vm, settings)
        }
    }
}

// ======================================================================
// Sensores: todas las magnitudes que soporta el vehículo
// ======================================================================

@Composable
private fun SensorsSection(
    vm: MainViewModel,
    connected: ConnState.Connected?,
    settings: AppSettings,
    values: Map<String, Double>,
) {
    if (connected == null) {
        EmptyState(
            icon = Icons.Rounded.Sensors,
            title = "Todos los sensores",
            message = "Al conectar verás aquí la lista completa de sensores que reporta tu vehículo, con su valor en vivo.",
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }
    val metrics = remember(connected.info) { vm.availableMetrics() }
    val groups = remember(metrics) { metrics.groupBy { it.category }.toSortedMap(compareBy<Metric.Category> { it.ordinal }) }
    val undecoded = remember(connected.info) { vm.undecodedPids() }
    val tick by vm.historyTick.collectAsStateWithLifecycle()
    val rawValues = remember { mutableStateMapOf<Int, String>() }
    val scope = rememberCoroutineScope()
    var selectedMetric by remember { mutableStateOf<Metric?>(null) }
    val alerts = metrics.mapNotNull { metric ->
        val value = values[metric.key]
        if (value != null && value.isFinite() && (value < metric.min || value > metric.max)) metric to value else null
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${metrics.size} magnitudes disponibles · ${connected.info.supportedPids.count { it % 0x20 != 0 }} PIDs reportados por la ECU",
                fontSize = 12.sp,
                color = Palette.TextMuted,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        if (!connected.info.ecuOk) {
            item {
                Notice(
                    connected.info.ecuMessage ?: "La ECU no responde. Las lecturas IMU, si están disponibles, provienen del teléfono.",
                    Palette.Amber,
                )
            }
        }
        if (alerts.isNotEmpty()) {
            item {
                SectionCard(title = "Alertas de rango · ${alerts.size}") {
                    alerts.forEach { (metric, value) ->
                        Text(
                            "${metric.name}: ${Units.display(metric, value, settings)} ${Units.unit(metric, settings)} " +
                                "(referencia ${metric.min}–${metric.max} ${metric.unit})",
                            fontSize = 13.sp,
                            color = Palette.Red,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "El rango es orientativo, no confirma una avería ni falta de calibración. En sensores del vehículo, " +
                            "la app genérica no puede verificar calibraciones del fabricante; confirma con la ficha técnica y diagnóstico del módulo.",
                        fontSize = 12.sp,
                        color = Palette.TextSecondary,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
        var colorIndex = 0
        groups.forEach { (category, list) ->
            item(key = "h-" + category.name) {
                Text(
                    category.label.uppercase(),
                    fontSize = 12.sp,
                    letterSpacing = 1.4.sp,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            list.forEach { m ->
                val color = Palette.Series[colorIndex++ % Palette.Series.size]
                item(key = m.key) {
                    val samples = remember(tick, m.key) { vm.history(m.key) }
                    SensorRow(m, samples, values[m.key], color, settings, onClick = { selectedMetric = m })
                }
            }
        }
        if (undecoded.isNotEmpty()) {
            item(key = "h-raw") {
                Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                    Text("OTROS SENSORES DE TU VEHÍCULO", fontSize = 12.sp, letterSpacing = 1.4.sp, color = Palette.TextSecondary)
                    Text(
                        "La ECU dice que los tiene, pero la app aún no sabe convertirlos a unidades. Puedes leer sus bytes crudos.",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                        lineHeight = 16.sp,
                    )
                }
            }
            items(undecoded, key = { "raw-$it" }) { pid ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.Surface)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(PidNames.name(pid), fontSize = 14.sp, color = Palette.TextPrimary)
                        Text(
                            String.format(Locale.US, "PID %02X", pid) + (rawValues[pid]?.let { " · $it" } ?: ""),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (rawValues[pid] != null) Palette.Cyan else Palette.TextMuted,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch { rawValues[pid] = vm.readRawPid(pid) ?: "sin respuesta" }
                    }) { Text("Leer") }
                }
            }
        }
        item {
            SteeringSensorCard(vm)
        }
    }

    selectedMetric?.let { metric ->
        SensorDetailsDialog(
            vm = vm,
            metric = metric,
            value = values[metric.key],
            settings = settings,
            onDismiss = { selectedMetric = null },
        )
    }
}

@Composable
private fun SensorRow(
    m: Metric,
    samples: List<com.nguevara.obdpulse.Sample>,
    value: Double?,
    color: Color,
    settings: AppSettings,
    onClick: () -> Unit,
) {
    val outOfRange = value != null && value.isFinite() && (value < m.min || value > m.max)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = 14.sp, color = Palette.TextPrimary, maxLines = 2, lineHeight = 17.sp)
            Text(
                when {
                    m.pid != null -> String.format(Locale.US, "PID %02X", m.pid)
                    Metrics.isPhoneSensor(m.key) -> "IMU del teléfono"
                    m.calculation != null -> "Calculado"
                    else -> "Adaptador"
                },
                fontSize = 11.sp,
                color = Palette.TextMuted,
            )
        }
        Sparkline(
            samples = samples,
            color = color,
            modifier = Modifier
                .width(56.dp)
                .height(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
            Text(
                Units.display(m, value, settings),
                style = NumberStyle,
                fontSize = 18.sp,
                color = if (outOfRange) Palette.Red else Palette.TextPrimary,
                maxLines = 1,
            )
            Text(Units.unit(m, settings), fontSize = 11.sp, color = color, maxLines = 1)
        }
        if (outOfRange) {
            Spacer(Modifier.width(6.dp))
            Pill("!", Palette.Red)
        }
    }
}

@Composable
private fun SensorDetailsDialog(
    vm: MainViewModel,
    metric: Metric,
    value: Double?,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val calibration by vm.imuCalibration.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(metric.name) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Metrics.explanation(metric), fontSize = 14.sp, color = Palette.TextSecondary, lineHeight = 19.sp)
                Text(
                    "Lectura actual: ${Units.display(metric, value, settings)} ${Units.unit(metric, settings)}",
                    fontSize = 14.sp,
                    color = if (value != null && (value < metric.min || value > metric.max)) Palette.Red else Palette.Cyan,
                )
                Text(
                    "Rango orientativo de alerta: ${metric.min}–${metric.max} ${metric.unit}. No es un límite oficial del fabricante.",
                    fontSize = 12.sp,
                    color = Palette.TextMuted,
                )
                metric.calculation?.let {
                    Text("Cómo se obtiene: $it", fontSize = 13.sp, color = Palette.TextPrimary, lineHeight = 18.sp)
                }
                if (value != null && (value < metric.min || value > metric.max)) {
                    Notice(
                        if (Metrics.isPhoneSensor(metric.key)) {
                            "La lectura puede deberse a movimiento del teléfono, montaje u offset del IMU. Detén el vehículo antes de calibrar."
                        } else {
                            "Puede ser una lectura real, un sensor/cableado defectuoso, protocolo/unidad incorrectos o una calibración requerida. " +
                                "OBD genérico no permite confirmar la calibración del módulo."
                        },
                        Palette.Amber,
                    )
                }
                if (Metrics.isPhoneSensor(metric.key)) {
                    Button(
                        onClick = { vm.calibratePhoneImu() },
                        enabled = !calibration.calibrating,
                    ) {
                        Text(if (calibration.calibrating) "Calibrando…" else "Calibrar IMU del teléfono")
                    }
                    calibration.message?.let {
                        Text(it, fontSize = 12.sp, color = if (calibration.calibrated) Palette.Green else Palette.TextSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        containerColor = Palette.SurfaceHigh,
    )
}

@Composable
private fun SteeringSensorCard(vm: MainViewModel) {
    val context = LocalContext.current
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val phoneSensors = vm.availableMetrics().filter { Metrics.isPhoneSensor(it.key) }
    SectionCard(title = "Ángulo del timón y sensores de dirección") {
        Text(
            "El ángulo de dirección suele estar en el módulo ABS/ESP o dirección eléctrica, no en los PIDs OBD-II genéricos del motor. " +
                "Este ELM327 solo lo podrá leer si el vehículo expone datos mejorados del fabricante y el adaptador accede a ese módulo.",
            fontSize = 13.sp,
            color = Palette.TextSecondary,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (profile == null) {
                "Para buscar el procedimiento específico se necesita como mínimo marca, modelo y año, además del módulo ABS/EPS."
            } else {
                "Perfil: ${profile.make} ${profile.model} ${profile.year}. Si no aparece, se necesita diagnóstico mejorado del ABS/EPS y confirmar compatibilidad del adaptador."
            },
            fontSize = 12.sp,
            color = Palette.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                try {
                    val url = profile?.let { OnlineService.steeringSearchUrl(it) }
                        ?: "https://www.google.com/search?q=OBD2+steering+angle+sensor+ABS+EPS+manufacturer+diagnostics"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    vm.notify("No se pudo abrir la búsqueda web.")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Buscar acceso para este vehículo") }
        Text(
            if (phoneSensors.isEmpty()) {
                "Este teléfono no expone acelerómetro lineal, giroscopio u orientación compatibles. Se necesita un teléfono con esos sensores."
            } else {
                "IMU disponible en este teléfono: ${phoneSensors.joinToString { it.short }}. Mide el teléfono, no el vehículo; fíjalo firmemente y calibra inmóvil."
            },
            fontSize = 12.sp,
            color = Palette.TextMuted,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ======================================================================
// Módulos: verificación SOLO LECTURA
// ======================================================================

@Composable
private fun ModulesSection(vm: MainViewModel, connected: ConnState.Connected?) {
    if (connected == null || !connected.info.ecuOk) {
        EmptyState(
            icon = Icons.Rounded.Memory,
            title = "Módulos del vehículo",
            message = "Al conectar podrás ver qué computadoras (ECU) responden, su identificación de software y si es reprogramable.",
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }
    val state by vm.modules.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Verificación de módulos") {
                Text(
                    "Consulta, sin modificar nada, qué módulos responden por OBD-II y si tienen software con " +
                        "identificador de calibración (CALID) y número de verificación (CVN).",
                    fontSize = 13.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(state.step, fontSize = 14.sp, color = Palette.TextSecondary)
                    }
                } else {
                    Button(onClick = { vm.scanModules() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.readAt == null) "Verificar módulos" else "Volver a verificar")
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Notice(it, Palette.Amber)
                }
            }
        }

        items(state.modules, key = { it.address }) { m -> ModuleCard(m) }

        if (state.modules.isNotEmpty()) {
            item {
                SectionCard(title = "¿Se pueden reprogramar los sensores?") {
                    val points = listOf(
                        "Los sensores (temperatura, MAF, oxígeno, presión…) son piezas físicas: no tienen software. Si fallan, se limpian o se reemplazan.",
                        "Lo reprogramable es el software de cada módulo. Su versión es el CALID y el CVN permite comprobar que no fue modificado.",
                        "Reprogramar, codificar o adaptar requiere el protocolo propio de la marca, acceso de seguridad y una interfaz estable (J2534). " +
                            "Con un ELM327, un corte a mitad de escritura puede dejar la ECU inservible, por eso esta app solo lee.",
                        "Lo que sí puedes hacer aquí: borrar códigos en Diagnóstico (la ECU reinicia sus ajustes de combustible) y revisar los monitores.",
                        "Guarda el CALID y el CVN: si un taller actualiza el software podrás comprobar que cambió.",
                    )
                    points.forEach {
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("•", color = Palette.Cyan, modifier = Modifier.padding(end = 8.dp))
                            Text(it, fontSize = 13.sp, color = Palette.TextSecondary, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(m: EcuModule) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = Palette.Cyan)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name ?: "Módulo ${m.address}", fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                Text("Dirección ${m.address}", fontSize = 12.sp, color = Palette.TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (m.hasCalibration) {
            Pill("Software reprogramable por el fabricante", Palette.Amber)
        } else {
            Pill("Sin identificador de calibración", Palette.TextSecondary)
        }
        Spacer(Modifier.height(8.dp))
        if (m.calibrationIds.isEmpty()) {
            InfoRow("CALID", "No informado")
        } else {
            m.calibrationIds.forEachIndexed { i, c -> InfoRow(if (i == 0) "CALID" else "", c) }
        }
        if (m.cvns.isNotEmpty()) m.cvns.forEachIndexed { i, c -> InfoRow(if (i == 0) "CVN" else "", c) }
        if (m.pidCount > 0) InfoRow("PIDs 01–20", m.pidCount.toString())
        InfoRow("Resultados de pruebas (modo 06)", if (m.supportsMode06) "Sí" else "No")
        InfoRow("Pruebas activas (modo 08)", if (m.supportsMode08) "Sí (no se ejecutan)" else "No")
        m.vin?.let { InfoRow("VIN", it) }
    }
}

// ======================================================================
// Vehículos
// ======================================================================

@Composable
private fun VehiclesSection(vm: MainViewModel) {
    var editing by remember { mutableStateOf<Pair<VehicleProfile, Boolean>?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        GarageContent(vm, onEdit = { p, isNew -> editing = p to isNew })
    }
    editing?.let { (p, isNew) -> VehicleEditorDialog(vm, p, isNew) { editing = null } }
}

// ======================================================================
// Terminal: comandos AT / OBD manuales con la respuesta cruda
// ======================================================================

@Composable
private fun TerminalSection(vm: MainViewModel, isConnected: Boolean) {
    val lines by vm.terminal.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun send(cmd: String) {
        vm.sendTerminal(cmd)
        input = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Consola
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF05080C))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        if (isConnected) {
                            "Escribe un comando (p. ej. ATRV o 010C) o usa los botones rápidos."
                        } else {
                            "Conecta el adaptador para enviar comandos."
                        },
                        color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
            items(lines) { line ->
                val (prefix, color) = when (line.dir) {
                    TermLine.Dir.TX -> "> " to Palette.Cyan
                    TermLine.Dir.RX -> "< " to Palette.TextPrimary
                    TermLine.Dir.INFO -> "· " to Palette.Amber
                }
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        timeFormat.format(Date(line.time)),
                        color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                    )
                    Text(
                        prefix + line.text.replace("\r", "\n").trim(),
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        // Comandos rápidos
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_COMMANDS.forEach { (label, cmd) ->
                FilterChip(
                    selected = false,
                    enabled = isConnected,
                    onClick = { send(cmd) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }

        // Entrada
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.uppercase(Locale.US) },
                placeholder = { Text("Comando AT u OBD", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                enabled = isConnected,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) send(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Cyan,
                    unfocusedBorderColor = Palette.Outline,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (input.isNotBlank()) send(input) },
                enabled = isConnected && input.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Enviar")
            }
            IconButton(onClick = { vm.clearTerminal() }) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Limpiar", tint = Palette.TextSecondary)
            }
        }
    }
}

// ======================================================================
// Viajes: archivos CSV grabados
// ======================================================================

@Composable
private fun TripsSection(vm: MainViewModel) {
    val context = LocalContext.current
    val files by vm.tripFiles.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) { vm.refreshTripFiles() }

    if (files.isEmpty() && !trip.active) {
        EmptyState(
            icon = Icons.Rounded.Description,
            title = "Sin viajes guardados",
            message = "Inicia un registro desde el Tablero. Cada viaje se guarda como un archivo CSV que puedes abrir en Excel, Python o MATLAB.",
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (trip.active) {
            item {
                Notice(
                    "Hay un viaje grabándose (${formatDuration(trip.elapsedS)}, ${Units.format(trip.distanceKm, 2)} km). " +
                        "Detenlo desde el Tablero para que aparezca aquí.",
                    Palette.Red,
                )
            }
        }
        items(files, key = { it.name }) { f ->
            TripFileRow(
                file = f,
                onShare = { shareCsv(context, f) },
                onDelete = { toDelete = f },
            )
        }
        item {
            Text(
                "Columnas: fecha y hora, segundos desde el inicio y una columna por sensor (punto decimal, separado por comas).",
                fontSize = 12.sp,
                color = Palette.TextMuted,
                modifier = Modifier.padding(4.dp),
            )
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar viaje?") },
            text = { Text("Se borrará ${f.name} del teléfono.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrip(f)
                    toDelete = null
                }) { Text("Eliminar", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

@Composable
private fun TripFileRow(file: File, onShare: () -> Unit, onDelete: () -> Unit) {
    val date = remember(file) {
        SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }
    SectionCard(contentPadding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = Palette.Green)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(date, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                Text("${file.name} · ${formatSize(file.length())}", fontSize = 12.sp, color = Palette.TextMuted, maxLines = 1)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = "Compartir", tint = Palette.Cyan)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Palette.TextSecondary)
            }
        }
    }
}

private fun shareCsv(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Viaje OBD Pulse · ${file.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir viaje"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ======================================================================
// Ajustes
// ======================================================================

@Composable
private fun SettingsSection(vm: MainViewModel, settings: AppSettings) {
    var showProtocols by remember { mutableStateOf(false) }
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val db by vm.vehicleDb.collectAsStateWithLifecycle()
    val dbStatus by vm.dbStatus.collectAsStateWithLifecycle()
    val dbUpdating by vm.dbUpdating.collectAsStateWithLifecycle()
    var urlDraft by remember(settings.dbUrl) { mutableStateOf(settings.dbUrl) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Unidades") {
                Text("Rendimiento de combustible", fontSize = 14.sp, color = Palette.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EconUnit.entries.forEach { u ->
                        FilterChip(
                            selected = settings.econUnit == u,
                            onClick = { vm.updateSettings { it.copy(econUnit = u) } },
                            label = { Text(u.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Cyan.copy(alpha = 0.18f),
                                selectedLabelColor = Palette.Cyan,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "Temperatura en °F",
                    subtitle = "Por defecto se muestra en °C",
                    checked = settings.fahrenheit,
                    onChange = { v -> vm.updateSettings { it.copy(fahrenheit = v) } },
                )
            }
        }

        if (profile != null) {
            item {
                SectionCard(title = "Mi vehículo") {
                    Text(
                        "Combustible, cilindrada y zona roja se toman de ${profile?.title} ${profile?.year}. Cámbialos en Más → Vehículos.",
                        fontSize = 13.sp,
                        color = Palette.TextSecondary,
                        lineHeight = 18.sp,
                    )
                }
            }
        } else item {
            SectionCard(title = "Mi vehículo") {
                SwitchRow(
                    title = "Motor diésel",
                    subtitle = "Ajusta la relación aire/combustible y la densidad para el cálculo de consumo",
                    checked = settings.diesel,
                    onChange = { v -> vm.updateSettings { it.copy(diesel = v) } },
                )
                SliderRow(
                    title = "Cilindrada",
                    valueLabel = { String.format(Locale.getDefault(), "%.1f L", it) },
                    value = settings.displacementL.toFloat(),
                    range = 0.8f..6.0f,
                    steps = 51,
                    help = "Solo se usa si el carro no tiene sensor MAF (estimación de consumo por MAP).",
                    onCommit = { v -> vm.updateSettings { it.copy(displacementL = (v * 10).roundToInt() / 10.0) } },
                )
                SliderRow(
                    title = "Zona roja del tacómetro",
                    valueLabel = { "${(it / 100).roundToInt() * 100} rpm" },
                    value = settings.redlineRpm.toFloat(),
                    range = 3000f..9000f,
                    steps = 11,
                    onCommit = { v -> vm.updateSettings { it.copy(redlineRpm = (v / 500).roundToInt() * 500) } },
                )
            }
        }

        item {
            SectionCard(title = "Conexión") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showProtocols = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (profile == null) "Protocolo OBD" else "Protocolo OBD (sin vehículo elegido)",
                            fontSize = 14.sp,
                            color = Palette.TextPrimary,
                        )
                        Text(
                            PROTOCOLS.firstOrNull { it.first == settings.protocol }?.second ?: "Automático",
                            fontSize = 12.sp,
                            color = Palette.TextSecondary,
                        )
                    }
                    Pill("ATSP${settings.protocol}", Palette.Cyan)
                }
                SliderRow(
                    title = "Pausa entre ciclos de lectura",
                    valueLabel = { v -> if (v < 1f) "Sin pausa" else "${v.roundToInt()} ms" },
                    value = settings.pollDelayMs.toFloat(),
                    range = 0f..1000f,
                    steps = 19,
                    help = "Súbela si el adaptador se desconecta o se cuelga con frecuencia (clones baratos).",
                    onCommit = { v -> vm.updateSettings { it.copy(pollDelayMs = (v / 50).roundToInt() * 50) } },
                )
                SwitchRow(
                    title = "Mantener la pantalla encendida",
                    subtitle = "Útil cuando el teléfono va en el soporte del carro",
                    checked = settings.keepScreenOn,
                    onChange = { v -> vm.updateSettings { it.copy(keepScreenOn = v) } },
                )
            }
        }

        item {
            SectionCard(title = "Base de vehículos") {
                InfoRow("Versión", "v${db.version} · ${db.updated}")
                InfoRow("Origen", vm.dbOrigin)
                InfoRow("Contenido", "${db.makes.size} marcas · ${db.rules.size} reglas")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Descarga desde internet la última versión de la base de protocolos (archivo vehicles.json del repositorio de la app).",
                    fontSize = 12.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it.trim() },
                    label = { Text("Dirección (URL)") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Cyan, unfocusedBorderColor = Palette.Outline),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (urlDraft != settings.dbUrl) vm.updateSettings { it.copy(dbUrl = urlDraft.ifBlank { com.nguevara.obdpulse.vehicle.OnlineService.DEFAULT_DB_URL }) }
                            vm.updateVehicleDb()
                        },
                        enabled = !dbUpdating,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (dbUpdating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Background)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Buscar actualización")
                    }
                    if (urlDraft != com.nguevara.obdpulse.vehicle.OnlineService.DEFAULT_DB_URL) {
                        OutlinedButton(onClick = {
                            urlDraft = com.nguevara.obdpulse.vehicle.OnlineService.DEFAULT_DB_URL
                            vm.updateSettings { it.copy(dbUrl = com.nguevara.obdpulse.vehicle.OnlineService.DEFAULT_DB_URL) }
                        }) { Text("Restablecer") }
                    }
                }
                dbStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Palette.TextSecondary, lineHeight = 16.sp)
                }
            }
        }

        item {
            SectionCard(title = "Acerca de") {
                Text("OBD Pulse 1.1", fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lector OBD-II para adaptadores ELM327 por Bluetooth Clásico o BLE. Los datos de consumo son " +
                        "estimaciones a partir de los sensores del motor. Borrar códigos no repara la falla: " +
                        "si el Check Engine parpadea, detén el vehículo y consulta a un mecánico.",
                    fontSize = 13.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 18.sp,
                )
            }
        }
    }

    if (showProtocols) {
        AlertDialog(
            onDismissRequest = { showProtocols = false },
            title = { Text("Protocolo OBD") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Se aplica en la próxima conexión. Usa Automático salvo que tu carro no responda.",
                        fontSize = 12.sp,
                        color = Palette.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(360.dp)) {
                        items(PROTOCOLS, key = { it.first }) { (code, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        vm.updateSettings { it.copy(protocol = code) }
                                        showProtocols = false
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = settings.protocol == code,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Palette.Cyan),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp, color = Palette.TextPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProtocols = false }) { Text("Cerrar") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Palette.TextPrimary)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Palette.TextSecondary, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.Cyan,
                uncheckedTrackColor = Palette.Track,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

/** Deslizador que guarda el valor solo al soltarlo (evita escribir los ajustes en cada movimiento). */
@Composable
private fun SliderRow(
    title: String,
    valueLabel: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    help: String? = null,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Text(valueLabel(local), style = NumberStyle, fontSize = 14.sp, color = Palette.Cyan)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Palette.Cyan,
                activeTrackColor = Palette.Cyan,
                inactiveTrackColor = Palette.Track,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        if (help != null) Text(help, fontSize = 12.sp, color = Palette.TextMuted, lineHeight = 16.sp)
    }
}
