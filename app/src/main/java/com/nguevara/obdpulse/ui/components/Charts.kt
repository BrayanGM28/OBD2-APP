package com.nguevara.obdpulse.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.nguevara.obdpulse.Sample
import com.nguevara.obdpulse.ui.theme.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Gráfica de línea en tiempo real con relleno degradado, cuadrícula y escala automática.
 *
 * @param samples   historial (unidades base)
 * @param windowMs  ventana visible (p. ej. 60 s)
 * @param endTime   instante del borde derecho (ahora, o el momento de la pausa)
 * @param transform conversión a unidades de visualización (°F, km/gal…)
 */
@Composable
fun LineChart(
    samples: List<Sample>,
    windowMs: Long,
    endTime: Long,
    color: Color,
    decimals: Int,
    modifier: Modifier = Modifier,
    transform: (Double) -> Double = { it },
) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            this.color = Palette.TextMuted.toArgb()
        }
    }

    Canvas(modifier) {
        val startTime = endTime - windowMs
        val visible = samples.filter { it.t in startTime..endTime }
        labelPaint.textSize = 10.dp.toPx()

        // Escala vertical automática con 12 % de margen
        var lo = visible.minOfOrNull { transform(it.v) } ?: 0.0
        var hi = visible.maxOfOrNull { transform(it.v) } ?: 1.0
        if (abs(hi - lo) < 1e-6) {
            val pad = max(abs(hi) * 0.1, 1.0)
            lo -= pad
            hi += pad
        } else {
            val pad = (hi - lo) * 0.12
            lo -= pad
            hi += pad
        }

        val w = size.width
        val h = size.height
        fun x(t: Long) = ((t - startTime).toFloat() / windowMs.toFloat()) * w
        fun y(v: Double) = (h - ((v - lo) / (hi - lo)) * h).toFloat()

        // Cuadrícula horizontal con etiquetas
        val gridLines = 4
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..gridLines) {
            val gy = h * i / gridLines
            drawLine(Palette.Outline, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f, pathEffect = dash)
            val value = hi - (hi - lo) * i / gridLines
            if (i in 1 until gridLines) {
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.getDefault(), "%.${decimals.coerceAtMost(2)}f", value),
                    4.dp.toPx(),
                    gy - 4.dp.toPx(),
                    labelPaint,
                )
            }
        }

        if (visible.size < 2) return@Canvas

        val line = Path()
        val fill = Path()
        visible.forEachIndexed { index, s ->
            val px = x(s.t)
            val py = y(transform(s.v))
            if (index == 0) {
                line.moveTo(px, py)
                fill.moveTo(px, h)
                fill.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        val last = visible.last()
        fill.lineTo(x(last.t), h)
        fill.close()

        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.0f))))
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val lp = Offset(x(last.t), y(transform(last.v)))
        drawCircle(color.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = lp)
        drawCircle(color, radius = 3.5.dp.toPx(), center = lp)
    }
}

/** Mini gráfica sin ejes para tarjetas y listas. */
@Composable
fun Sparkline(
    samples: List<Sample>,
    color: Color,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000,
) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val end = samples.last().t
        val start = end - windowMs
        val visible = samples.filter { it.t >= start }
        if (visible.size < 2) return@Canvas
        var lo = visible.minOf { it.v }
        var hi = visible.maxOf { it.v }
        if (hi - lo < 1e-6) {
            lo -= 1.0
            hi += 1.0
        }
        val path = Path()
        visible.forEachIndexed { i, s ->
            val px = ((s.t - start).toFloat() / windowMs) * size.width
            val py = (size.height - ((s.v - lo) / (hi - lo)) * size.height).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ======================================================================
// Gráficas adicionales: superpuesta, dispersión X-Y e histograma
// ======================================================================

/** Una serie para la gráfica superpuesta, ya en unidades de visualización. */
data class ChartSeries(
    val label: String,
    val samples: List<Sample>,
    val color: Color,
    val transform: (Double) -> Double = { it },
)

/** Media móvil simple de [window] muestras (para suavizar el ruido de los sensores). */
fun smooth(samples: List<Sample>, window: Int): List<Sample> {
    if (window <= 1 || samples.size < 3) return samples
    val out = ArrayList<Sample>(samples.size)
    var sum = 0.0
    for (i in samples.indices) {
        sum += samples[i].v
        if (i >= window) sum -= samples[i - window].v
        val n = minOf(i + 1, window)
        out += Sample(samples[i].t, sum / n)
    }
    return out
}

/**
 * Varias magnitudes en la misma gráfica. Como tienen unidades distintas, cada una se normaliza
 * a 0–100 % de su propio rango visible: se comparan formas y momentos (p. ej. pedal → RPM → turbo).
 */
@Composable
fun OverlayChart(
    series: List<ChartSeries>,
    windowMs: Long,
    endTime: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val startTime = endTime - windowMs
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..4) {
            val gy = h * i / 4
            drawLine(Palette.Outline, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f, pathEffect = dash)
        }
        for (s in series) {
            val visible = s.samples.filter { it.t in startTime..endTime }
            if (visible.size < 2) continue
            val vals = visible.map { s.transform(it.v) }
            var lo = vals.min()
            var hi = vals.max()
            if (hi - lo < 1e-6) {
                lo -= 1.0
                hi += 1.0
            }
            val path = Path()
            visible.forEachIndexed { i, sample ->
                val px = ((sample.t - startTime).toFloat() / windowMs) * w
                val py = (h - ((vals[i] - lo) / (hi - lo)) * h * 0.9 - h * 0.05).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Dispersión X-Y: cada punto es un instante (X, Y). Los puntos recientes son más brillantes.
 * Ejemplos útiles: RPM vs velocidad (se ven las marchas como rectas), MAP vs RPM (mapa del motor),
 * turbo vs RPM (curva del turbo), consumo vs velocidad.
 */
@Composable
fun ScatterChart(
    points: List<Pair<Double, Double>>,
    color: Color,
    xDecimals: Int,
    yDecimals: Int,
    modifier: Modifier = Modifier,
) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            this.color = Palette.TextMuted.toArgb()
        }
    }
    Canvas(modifier) {
        labelPaint.textSize = 10.dp.toPx()
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..4) {
            drawLine(Palette.Outline, Offset(0f, h * i / 4), Offset(w, h * i / 4), strokeWidth = 1f, pathEffect = dash)
            drawLine(Palette.Outline, Offset(w * i / 4, 0f), Offset(w * i / 4, h), strokeWidth = 1f, pathEffect = dash)
        }
        if (points.isEmpty()) return@Canvas
        var x0 = points.minOf { it.first }
        var x1 = points.maxOf { it.first }
        var y0 = points.minOf { it.second }
        var y1 = points.maxOf { it.second }
        if (x1 - x0 < 1e-6) { x0 -= 1; x1 += 1 }
        if (y1 - y0 < 1e-6) { y0 -= 1; y1 += 1 }
        val padX = (x1 - x0) * 0.06
        val padY = (y1 - y0) * 0.08
        x0 -= padX; x1 += padX; y0 -= padY; y1 += padY
        val n = points.size
        points.forEachIndexed { i, (x, y) ->
            val px = ((x - x0) / (x1 - x0) * w).toFloat()
            val py = (h - (y - y0) / (y1 - y0) * h).toFloat()
            val age = (i + 1).toFloat() / n
            drawCircle(color.copy(alpha = 0.15f + 0.75f * age), radius = (2.2f + 1.6f * age).dp.toPx(), center = Offset(px, py))
        }
        // Etiquetas de los extremos de cada eje
        val nc = drawContext.canvas.nativeCanvas
        val yf = "%.${yDecimals.coerceAtMost(2)}f"
        val xf = "%.${xDecimals.coerceAtMost(2)}f"
        nc.drawText(String.format(Locale.getDefault(), yf, y1 - padY), 4.dp.toPx(), 12.dp.toPx(), labelPaint)
        nc.drawText(String.format(Locale.getDefault(), yf, y0 + padY), 4.dp.toPx(), h - 4.dp.toPx(), labelPaint)
        val right = String.format(Locale.getDefault(), xf, x1 - padX)
        nc.drawText(right, w - labelPaint.measureText(right) - 4.dp.toPx(), h - 4.dp.toPx(), labelPaint)
    }
}

/** Histograma: cuánto tiempo pasó la magnitud en cada rango de valores. */
@Composable
fun Histogram(
    values: List<Double>,
    color: Color,
    decimals: Int,
    modifier: Modifier = Modifier,
    bins: Int = 12,
) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            this.color = Palette.TextMuted.toArgb()
        }
    }
    Canvas(modifier) {
        labelPaint.textSize = 10.dp.toPx()
        val w = size.width
        val h = size.height - 16.dp.toPx() // espacio para etiquetas
        drawLine(Palette.Outline, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        if (values.size < 2) return@Canvas
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val counts = IntArray(bins)
        for (v in values) {
            val b = (((v - lo) / (hi - lo)) * bins).toInt().coerceIn(0, bins - 1)
            counts[b]++
        }
        val maxCount = counts.max().coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val bw = w / bins
        for (i in 0 until bins) {
            val bh = counts[i].toFloat() / maxCount * h * 0.95f
            val left = i * bw + gap / 2
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.35f)), startY = h - bh, endY = h),
                topLeft = Offset(left, h - bh),
                size = androidx.compose.ui.geometry.Size(bw - gap, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
        val nc = drawContext.canvas.nativeCanvas
        val f = "%.${decimals.coerceAtMost(2)}f"
        nc.drawText(String.format(Locale.getDefault(), f, lo), 0f, size.height - 2.dp.toPx(), labelPaint)
        val mid = String.format(Locale.getDefault(), f, (lo + hi) / 2)
        nc.drawText(mid, w / 2 - labelPaint.measureText(mid) / 2, size.height - 2.dp.toPx(), labelPaint)
        val top = String.format(Locale.getDefault(), f, hi)
        nc.drawText(top, w - labelPaint.measureText(top), size.height - 2.dp.toPx(), labelPaint)
    }
}

/** Empareja dos historiales por tiempo (muestra Y más cercana a cada X, máximo 1,5 s de diferencia). */
fun pairByTime(xs: List<Sample>, ys: List<Sample>, maxGapMs: Long = 1_500): List<Pair<Sample, Sample>> {
    if (xs.isEmpty() || ys.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Sample, Sample>>(xs.size)
    var j = 0
    for (x in xs) {
        while (j + 1 < ys.size && abs(ys[j + 1].t - x.t) <= abs(ys[j].t - x.t)) j++
        if (abs(ys[j].t - x.t) <= maxGapMs) out += x to ys[j]
    }
    return out
}
