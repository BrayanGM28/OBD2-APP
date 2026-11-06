package com.nguevara.obdpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.Sample
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.data.Units
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.ui.components.ChartSeries
import com.nguevara.obdpulse.ui.components.EmptyState
import com.nguevara.obdpulse.ui.components.Histogram
import com.nguevara.obdpulse.ui.components.LineChart
import com.nguevara.obdpulse.ui.components.OverlayChart
import com.nguevara.obdpulse.ui.components.RingGauge
import com.nguevara.obdpulse.ui.components.ScatterChart
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.components.pairByTime
import com.nguevara.obdpulse.ui.components.smooth
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import kotlin.math.sqrt

private val WINDOWS = listOf(30, 60, 120, 300, 600) // segundos
private const val MAX_SERIES = 6

private enum class ChartMode(val label: String) {
    LINES("Líneas"),
    OVERLAY("Superpuestas"),
    XY("X-Y"),
    HISTOGRAM("Histograma"),
    GAUGES("Medidores"),
}

/** Combinaciones listas para usar. Solo se muestran las que tu vehículo soporta. */
private val PRESETS = listOf(
    "Conducción" to listOf(Metrics.SPEED, Metrics.RPM, Metrics.THROTTLE, Metrics.LOAD, Metrics.ACCEL),
    "Temperaturas" to listOf(Metrics.COOLANT, Metrics.IAT, Metrics.OIL_TEMP, Metrics.AMBIENT, Metrics.CAT_TEMP, Metrics.EGT),
    "Mezcla" to listOf(Metrics.STFT1, Metrics.LTFT1, Metrics.O2_B1S1, Metrics.LAMBDA_WIDE, Metrics.AFR),
    "Turbo / diésel" to listOf(Metrics.BOOST, Metrics.MAP, Metrics.RAIL_PRESSURE, Metrics.EGR_CMD, Metrics.EGT, Metrics.DPF_TEMP),
    "Consumo" to listOf(Metrics.FUEL_RATE, Metrics.ECONOMY, Metrics.MAF, Metrics.SPEED),
    "Potencia" to listOf(Metrics.POWER, Metrics.TORQUE, Metrics.RPM, Metrics.BOOST),
)

/** Pares sugeridos para la dispersión X-Y: (nombre, X, Y). */
private val XY_PRESETS = listOf(
    Triple("Marchas (RPM vs velocidad)", Metrics.SPEED, Metrics.RPM),
    Triple("Mapa del motor (MAP vs RPM)", Metrics.RPM, Metrics.MAP),
    Triple("Curva del turbo", Metrics.RPM, Metrics.BOOST),
    Triple("Consumo vs velocidad", Metrics.SPEED, Metrics.FUEL_RATE),
    Triple("Avance vs carga", Metrics.LOAD, Metrics.TIMING),
    Triple("Potencia vs RPM", Metrics.RPM, Metrics.POWER),
)

@Composable
fun ChartsScreen(
    vm: MainViewModel,
    conn: ConnState,
    settings: AppSettings,
    values: Map<String, Double>,
    onGoConnect: () -> Unit,
) {
    val connected = conn as? ConnState.Connected
    if (connected == null || !connected.info.ecuOk) {
        EmptyState(
            icon = Icons.AutoMirrored.Rounded.ShowChart,
            title = "Gráficas en tiempo real",
            message = "Conecta el adaptador para graficar cualquier sensor de tu carro: líneas, superpuestas, X-Y, histogramas y medidores.",
            primaryLabel = "Ir a conexión",
            onPrimary = onGoConnect,
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }

    val available = remember(connected.info) { vm.availableMetrics() }
    val availableKeys = remember(available) { available.map { it.key }.toSet() }

    var modeName by rememberSaveable { mutableStateOf(ChartMode.LINES.name) }
    val mode = ChartMode.valueOf(modeName)

    // Selección guardada como texto "010C,010D" para sobrevivir a la rotación.
    var selectedCsv by rememberSaveable { mutableStateOf("") }
    val selected: List<String> = remember(selectedCsv, available) {
        if (selectedCsv.isEmpty()) {
            listOf(Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.ECONOMY).filter { it in availableKeys }
        } else {
            // "-" representa una selección vacía elegida por el usuario
            selectedCsv.split(',').filter { it.isNotBlank() && it in availableKeys }
        }
    }
    var xKey by rememberSaveable { mutableStateOf(Metrics.SPEED) }
    var yKey by rememberSaveable { mutableStateOf(Metrics.RPM) }
    var windowS by rememberSaveable { mutableStateOf(60) }
    var smoothOn by rememberSaveable { mutableStateOf(false) }
    var pausedAt by remember { mutableStateOf<Long?>(null) }

    val watchKeys = if (mode == ChartMode.XY) setOf(xKey, yKey) else selected.toSet()
    LaunchedEffect(watchKeys) { vm.watch(watchKeys) }

    val tick by vm.historyTick.collectAsStateWithLifecycle()
    val now = remember(tick) { System.currentTimeMillis() }
    val endTime = pausedAt ?: now
    val windowMs = windowS * 1000L
    fun hist(key: String): List<Sample> = vm.history(key).let { if (smoothOn) smooth(it, 5) else it }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Tipo de gráfica ----
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChartMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { modeName = m.name },
                        label = { Text(m.label) },
                        colors = chipColors(),
                    )
                }
            }
        }

        // ---- Ventana de tiempo, suavizado y pausa ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WINDOWS.forEach { w ->
                        FilterChip(
                            selected = windowS == w,
                            onClick = { windowS = w },
                            label = { Text(if (w < 60) "${w}s" else "${w / 60} min") },
                            colors = chipColors(),
                        )
                    }
                    if (mode != ChartMode.GAUGES) {
                        FilterChip(
                            selected = smoothOn,
                            onClick = { smoothOn = !smoothOn },
                            label = { Text("Suavizar") },
                            colors = chipColors(),
                        )
                    }
                }
                FilledTonalIconButton(onClick = { pausedAt = if (pausedAt == null) System.currentTimeMillis() else null }) {
                    Icon(
                        if (pausedAt == null) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (pausedAt == null) "Pausar" else "Reanudar",
                    )
                }
            }
        }

        if (mode == ChartMode.XY) {
            // ---- Selector X-Y ----
            item {
                Column {
                    Label("Combinaciones sugeridas")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        XY_PRESETS.filter { it.second in availableKeys && it.third in availableKeys }.forEach { (name, x, y) ->
                            SuggestionChip(
                                onClick = { xKey = x; yKey = y },
                                label = { Text(name) },
                                colors = SuggestionChipDefaults.suggestionChipColors(labelColor = Palette.TextSecondary),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Eje X (horizontal)", Modifier.weight(1f))
                        IconButton(onClick = { val t = xKey; xKey = yKey; yKey = t }) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Intercambiar ejes", tint = Palette.Cyan)
                        }
                    }
                    MetricPicker(available, setOf(xKey)) { xKey = it }
                    Spacer(Modifier.height(8.dp))
                    Label("Eje Y (vertical)")
                    MetricPicker(available, setOf(yKey)) { yKey = it }
                }
            }
            item {
                val xm = Metrics[xKey]
                val ym = Metrics[yKey]
                if (xm != null && ym != null && xKey in availableKeys && yKey in availableKeys) {
                    val start = endTime - windowMs
                    val pairs = remember(tick, xKey, yKey, windowS, smoothOn, pausedAt) {
                        pairByTime(
                            hist(xKey).filter { it.t in start..endTime },
                            hist(yKey).filter { it.t in start..endTime },
                        ).map { (x, y) -> Units.convert(xm, x.v, settings) to Units.convert(ym, y.v, settings) }
                    }
                    SectionCard(title = "${ym.short} vs ${xm.short}") {
                        Text(
                            "${pairs.size} puntos · los más recientes se ven más brillantes",
                            fontSize = 12.sp,
                            color = Palette.TextMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                        ScatterChart(
                            points = pairs,
                            color = Palette.Cyan,
                            xDecimals = xm.decimals,
                            yDecimals = ym.decimals,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text("↑ ${ym.short} (${Units.unit(ym, settings)})", fontSize = 12.sp, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
                            Text("${xm.short} (${Units.unit(xm, settings)}) →", fontSize = 12.sp, color = Palette.TextSecondary)
                        }
                    }
                }
            }
            return@LazyColumn
        }

        // ---- Combinaciones rápidas y selector de sensores ----
        item {
            Column {
                Label("Combinaciones rápidas")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { (name, keys) ->
                        val keysOk = keys.filter { it in availableKeys }
                        if (keysOk.size >= 2) {
                            SuggestionChip(
                                onClick = { selectedCsv = keysOk.take(MAX_SERIES).joinToString(",") },
                                label = { Text(name) },
                                colors = SuggestionChipDefaults.suggestionChipColors(labelColor = Palette.TextSecondary),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Label("Sensores (hasta $MAX_SERIES) · ${selected.size} elegidos")
                MetricPicker(available, selected.toSet()) { key ->
                    val next = if (key in selected) selected - key else (selected + key).takeLast(MAX_SERIES)
                    selectedCsv = next.joinToString(",").ifEmpty { "-" }
                }
            }
        }

        if (selected.isEmpty()) {
            item {
                Text("Selecciona un sensor para graficarlo.", color = Palette.TextSecondary, modifier = Modifier.padding(24.dp))
            }
            return@LazyColumn
        }

        when (mode) {
            ChartMode.LINES -> items(selected, key = { it }) { key ->
                val metric = Metrics[key] ?: return@items
                val samples = remember(tick, key, smoothOn) { hist(key) }
                ChartCard(
                    metric = metric,
                    samples = samples,
                    current = values[key],
                    color = Palette.Series[selected.indexOf(key) % Palette.Series.size],
                    windowMs = windowMs,
                    endTime = endTime,
                    settings = settings,
                )
            }

            ChartMode.OVERLAY -> item {
                val series = remember(tick, selected, smoothOn) {
                    selected.mapIndexedNotNull { i, key ->
                        val m = Metrics[key] ?: return@mapIndexedNotNull null
                        ChartSeries(m.short, hist(key), Palette.Series[i % Palette.Series.size]) { Units.convert(m, it, settings) }
                    }
                }
                SectionCard(title = "Superpuestas") {
                    Text(
                        "Cada línea usa su propia escala (0–100 % de su rango visible) para comparar cuándo sube o baja cada sensor.",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OverlayChart(
                        series = series,
                        windowMs = windowMs,
                        endTime = endTime,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    selected.forEachIndexed { i, key ->
                        val m = Metrics[key] ?: return@forEachIndexed
                        LegendRow(Palette.Series[i % Palette.Series.size], m.name, Units.display(m, values[key], settings), Units.unit(m, settings))
                    }
                }
            }

            ChartMode.HISTOGRAM -> items(selected, key = { it }) { key ->
                val metric = Metrics[key] ?: return@items
                val color = Palette.Series[selected.indexOf(key) % Palette.Series.size]
                val inWindow = remember(tick, key, windowS, smoothOn, pausedAt) {
                    hist(key).filter { it.t in (endTime - windowMs)..endTime }.map { Units.convert(metric, it.v, settings) }
                }
                HistogramCard(metric, inWindow, color, settings)
            }

            ChartMode.GAUGES -> item {
                SectionCard(title = "Medidores") {
                    selected.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { key ->
                                val m = Metrics[key]
                                if (m != null) {
                                    val (lo, hi) = Units.range(m, settings)
                                    val v = values[key]?.let { Units.convert(m, it, settings) }
                                    RingGauge(
                                        value = v,
                                        min = lo,
                                        max = hi,
                                        title = m.short,
                                        valueText = Units.format(v, m.decimals),
                                        unit = Units.unit(m, settings),
                                        color = Palette.Series[selected.indexOf(key) % Palette.Series.size],
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            ChartMode.XY -> Unit
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        color = Palette.TextSecondary,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** Fila de chips con todos los sensores disponibles, agrupados por categoría. */
@Composable
private fun MetricPicker(available: List<Metric>, selected: Set<String>, onToggle: (String) -> Unit) {
    val ordered = remember(available) { available.sortedBy { it.category.ordinal } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ordered.forEach { m ->
            FilterChip(
                selected = m.key in selected,
                onClick = { onToggle(m.key) },
                label = { Text(m.short) },
                colors = chipColors(),
            )
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Palette.Cyan.copy(alpha = 0.18f),
    selectedLabelColor = Palette.Cyan,
    labelColor = Palette.TextSecondary,
)

@Composable
private fun LegendRow(color: Color, name: String, value: String, unit: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, style = NumberStyle, fontSize = 14.sp, color = Palette.TextPrimary)
        Spacer(Modifier.width(4.dp))
        Text(unit, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun HistogramCard(metric: Metric, values: List<Double>, color: Color, settings: AppSettings) {
    val avg = if (values.isEmpty()) null else values.average()
    val sd = if (values.size < 2 || avg == null) null else sqrt(values.sumOf { (it - avg) * (it - avg) } / (values.size - 1))
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(metric.name, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1)
                Text("${values.size} muestras", fontSize = 12.sp, color = Palette.TextMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                MiniStat("prom", Units.format(avg, metric.decimals))
                MiniStat("desv.", Units.format(sd, metric.decimals))
            }
        }
        Spacer(Modifier.height(10.dp))
        Histogram(
            values = values,
            color = color,
            decimals = metric.decimals,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        )
        Text(
            "Unidad: ${Units.unit(metric, settings)} · altura = tiempo en ese rango",
            fontSize = 11.sp,
            color = Palette.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChartCard(
    metric: Metric,
    samples: List<Sample>,
    current: Double?,
    color: Color,
    windowMs: Long,
    endTime: Long,
    settings: AppSettings,
) {
    val unit = Units.unit(metric, settings)
    val inWindow = samples.filter { it.t in (endTime - windowMs)..endTime }
        .map { Units.convert(metric, it.v, settings) }
    val minV = inWindow.minOrNull()
    val maxV = inWindow.maxOrNull()
    val avgV = if (inWindow.isEmpty()) null else inWindow.average()

    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(metric.name, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Units.display(metric, current, settings),
                        style = NumberStyle,
                        fontSize = 30.sp,
                        color = Palette.TextPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = color, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MiniStat("mín", Units.format(minV, metric.decimals))
                MiniStat("prom", Units.format(avgV, metric.decimals))
                MiniStat("máx", Units.format(maxV, metric.decimals))
            }
        }
        Spacer(Modifier.height(10.dp))
        LineChart(
            samples = samples,
            windowMs = windowMs,
            endTime = endTime,
            color = color,
            decimals = metric.decimals,
            transform = { Units.convert(metric, it, settings) },
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                if (windowMs >= 60_000) "-${windowMs / 60_000} min" else "-${windowMs / 1000}s",
                fontSize = 11.sp,
                color = Palette.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text("ahora", fontSize = 11.sp, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = Palette.TextMuted)
        Spacer(Modifier.width(6.dp))
        Text(value, style = NumberStyle, fontSize = 13.sp, color = Palette.TextPrimary)
    }
}
