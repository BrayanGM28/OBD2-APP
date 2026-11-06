package com.nguevara.obdpulse.ui.screens

import android.content.Context
import android.content.Intent
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

private enum class ToolSection(val label: String) {
    SENSORS("Sensores"),
    TERMINAL("Terminal"),
    TRIPS("Viajes"),
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
        if (section == ToolSection.SENSORS && connected != null && connected.info.ecuOk) {
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
            ToolSection.TERMINAL -> TerminalSection(vm, connected != null)
            ToolSection.TRIPS -> TripsSection(vm)
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
    if (connected == null || !connected.info.ecuOk) {
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
    val tick by vm.historyTick.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${metrics.size} magnitudes disponibles · se actualizan en ciclo, las lentas cada 4 ciclos",
                fontSize = 12.sp,
                color = Palette.TextMuted,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(metrics, key = { _, m -> m.key }) { index, m ->
            val samples = remember(tick, m.key) { vm.history(m.key) }
            val color = Palette.Series[index % Palette.Series.size]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontSize = 14.sp, color = Palette.TextPrimary, maxLines = 1)
                    Text(
                        if (m.pid != null) String.format(Locale.US, "PID %02X", m.pid) else "Calculado",
                        fontSize = 11.sp,
                        color = Palette.TextMuted,
                    )
                }
                Sparkline(
                    samples = samples,
                    color = color,
                    modifier = Modifier
                        .width(64.dp)
                        .height(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
                    Text(
                        Units.display(m, values[m.key], settings),
                        style = NumberStyle,
                        fontSize = 18.sp,
                        color = Palette.TextPrimary,
                        maxLines = 1,
                    )
                    Text(Units.unit(m, settings), fontSize = 11.sp, color = color, maxLines = 1)
                }
            }
        }
    }
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

        item {
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
                        Text("Protocolo OBD", fontSize = 14.sp, color = Palette.TextPrimary)
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
            SectionCard(title = "Acerca de") {
                Text("OBD Pulse 1.0", fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
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
