package com.nguevara.obdpulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import com.nguevara.obdpulse.ui.components.EmptyState
import com.nguevara.obdpulse.ui.components.LineChart
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette

private val WINDOWS = listOf(30, 60, 120, 300) // segundos
private const val MAX_SERIES = 4

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
            message = "Conecta el adaptador para graficar cualquier sensor de tu carro.",
            primaryLabel = "Ir a conexión",
            onPrimary = onGoConnect,
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }

    val available = remember(connected.info) { vm.availableMetrics() }
    // Selección guardada como texto "010C,010D" para sobrevivir a la rotación.
    var selectedCsv by rememberSaveable { mutableStateOf("") }
    val selected: List<String> = remember(selectedCsv, available) {
        if (selectedCsv.isEmpty()) {
            // Primera vez: selección por defecto
            listOf(Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.ECONOMY)
                .filter { k -> available.any { it.key == k } }
        } else {
            // "-" representa una selección vacía elegida por el usuario
            selectedCsv.split(',').filter { k -> k.isNotBlank() && available.any { it.key == k } }
        }
    }
    var windowS by rememberSaveable { mutableStateOf(60) }
    var pausedAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(selected) { vm.watch(selected.toSet()) }
    val tick by vm.historyTick.collectAsStateWithLifecycle()
    val now = remember(tick) { System.currentTimeMillis() }
    val endTime = pausedAt ?: now

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Ventana de tiempo + pausa ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WINDOWS.forEach { w ->
                        FilterChip(
                            selected = windowS == w,
                            onClick = { windowS = w },
                            label = { Text(if (w < 60) "${w}s" else "${w / 60} min") },
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

        // ---- Selector de sensores ----
        item {
            Column {
            Text(
                "Elige hasta $MAX_SERIES sensores",
                fontSize = 12.sp,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(available, key = { it.key }) { m ->
                    val isOn = m.key in selected
                    FilterChip(
                        selected = isOn,
                        onClick = {
                            val next = if (isOn) {
                                selected - m.key
                            } else {
                                (selected + m.key).takeLast(MAX_SERIES)
                            }
                            selectedCsv = next.joinToString(",").ifEmpty { "-" }
                        },
                        label = { Text(m.short) },
                        colors = chipColors(),
                    )
                }
            }
            }
        }

        if (selected.isEmpty()) {
            item {
                Text(
                    "Selecciona un sensor para graficarlo.",
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        // ---- Una tarjeta por serie ----
        items(selected, key = { it }) { key ->
            val metric = Metrics[key] ?: return@items
            val index = selected.indexOf(key)
            val samples = remember(tick, key) { vm.history(key) }
            ChartCard(
                metric = metric,
                samples = samples,
                current = values[key],
                color = Palette.Series[index % Palette.Series.size],
                windowMs = windowS * 1000L,
                endTime = endTime,
                settings = settings,
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
            Text("-${windowMs / 1000}s", fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.weight(1f))
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
