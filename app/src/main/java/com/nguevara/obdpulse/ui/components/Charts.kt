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
