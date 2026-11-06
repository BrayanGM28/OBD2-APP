package com.nguevara.obdpulse.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

private fun fractionOf(value: Double?, min: Double, max: Double): Float {
    if (value == null || max <= min) return 0f
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun DrawScope.pointOnArc(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + (cos(rad) * radius).toFloat(), center.y + (sin(rad) * radius).toFloat())
}

/**
 * Medidor grande de aguja (RPM, velocidad). Arco de 270°, marcas con números, zona roja opcional
 * y aguja animada. El valor numérico va en la parte inferior, dentro del hueco del arco.
 */
@Composable
fun ArcGauge(
    value: Double?,
    min: Double,
    max: Double,
    title: String,
    unit: String,
    valueText: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Cyan,
    redFrom: Double? = null,
    majorTicks: Int = 8,
    tickLabel: (Double) -> String = { it.roundToInt().toString() },
) {
    val target = fractionOf(value, min, max)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "arcGauge",
    )
    val inRed = redFrom != null && value != null && value >= redFrom
    val activeColor by animateColorAsState(if (inRed) Palette.Red else color, label = "arcGaugeColor")

    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            this.color = Palette.TextSecondary.toArgb()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val side = maxWidth.value

        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.065f
            val pad = stroke * 0.8f
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val topLeft = Offset(pad, pad)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = arcSize.width / 2f

            // Pista
            drawArc(Palette.Track, ARC_START, ARC_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            // Zona roja
            if (redFrom != null) {
                val rf = fractionOf(redFrom, min, max)
                drawArc(
                    Palette.Red.copy(alpha = 0.35f),
                    ARC_START + ARC_SWEEP * rf,
                    ARC_SWEEP * (1f - rf),
                    false,
                    topLeft,
                    arcSize,
                    style = Stroke(stroke),
                )
            }

            // Progreso con "brillo"
            if (animated > 0.002f) {
                drawArc(
                    activeColor.copy(alpha = 0.18f), ARC_START, ARC_SWEEP * animated, false, topLeft, arcSize,
                    style = Stroke(stroke * 2.1f, cap = StrokeCap.Round),
                )
                drawArc(
                    activeColor, ARC_START, ARC_SWEEP * animated, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            // Marcas menores y mayores con números
            labelPaint.textSize = size.minDimension * 0.062f
            val minorPerMajor = 4
            val totalMinor = majorTicks * minorPerMajor
            for (i in 0..totalMinor) {
                val f = i.toFloat() / totalMinor
                val deg = ARC_START + ARC_SWEEP * f
                val isMajor = i % minorPerMajor == 0
                val outer = radius - stroke * 1.0f
                val inner = outer - if (isMajor) stroke * 0.85f else stroke * 0.4f
                drawLine(
                    color = if (isMajor) Palette.TextSecondary else Palette.TextMuted,
                    start = pointOnArc(center, outer, deg),
                    end = pointOnArc(center, inner, deg),
                    strokeWidth = if (isMajor) 2.2.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (isMajor) {
                    val p = pointOnArc(center, inner - labelPaint.textSize * 1.0f, deg)
                    drawContext.canvas.nativeCanvas.drawText(
                        tickLabel(min + (max - min) * f),
                        p.x,
                        p.y + labelPaint.textSize / 3f,
                        labelPaint,
                    )
                }
            }

            // Aguja
            val needleDeg = ARC_START + ARC_SWEEP * animated
            val tip = pointOnArc(center, radius - stroke * 1.3f, needleDeg)
            val tail = pointOnArc(center, stroke * 0.9f, needleDeg + 180f)
            drawLine(activeColor.copy(alpha = 0.35f), tail, tip, strokeWidth = stroke * 0.55f, cap = StrokeCap.Round)
            drawLine(Color.White, tail, tip, strokeWidth = stroke * 0.22f, cap = StrokeCap.Round)
            drawCircle(activeColor, radius = stroke * 0.62f, center = center)
            drawCircle(Palette.Background, radius = stroke * 0.28f, center = center)
        }

        // Título (debajo del eje de la aguja)
        Text(
            text = title.uppercase(),
            color = Palette.TextSecondary,
            fontSize = (side * 0.055f).sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.offset(y = (side * 0.16f).dp),
        )

        // Valor (en el hueco inferior del arco)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-side * 0.04f).dp),
        ) {
            Text(
                text = valueText,
                style = NumberStyle,
                color = Palette.TextPrimary,
                fontSize = (side * 0.15f).sp,
                lineHeight = (side * 0.16f).sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = unit,
                color = activeColor,
                fontSize = (side * 0.058f).sp,
                lineHeight = (side * 0.07f).sp,
            )
        }
    }
}

/**
 * Medidor compacto tipo anillo (temperatura, carga, acelerador).
 * [warnFrom] cambia el color a ámbar y [dangerFrom] a rojo.
 */
@Composable
fun RingGauge(
    value: Double?,
    min: Double,
    max: Double,
    title: String,
    valueText: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Cyan,
    warnFrom: Double? = null,
    dangerFrom: Double? = null,
) {
    val target = fractionOf(value, min, max)
    val animated by animateFloatAsState(target, tween(420, easing = FastOutSlowInEasing), label = "ring")
    val state = when {
        value != null && dangerFrom != null && value >= dangerFrom -> Palette.Red
        value != null && warnFrom != null && value >= warnFrom -> Palette.Amber
        else -> color
    }
    val activeColor by animateColorAsState(state, label = "ringColor")

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val side = maxWidth.value
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val pad = stroke / 2f + 2f
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            val topLeft = Offset(pad, pad)
            drawArc(Palette.Track, ARC_START, ARC_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0.002f) {
                drawArc(activeColor, ARC_START, ARC_SWEEP * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = valueText,
                style = NumberStyle,
                color = Palette.TextPrimary,
                fontSize = (side * 0.22f).sp,
                lineHeight = (side * 0.24f).sp,
            )
            Text(text = unit, color = activeColor, fontSize = (side * 0.1f).sp, lineHeight = (side * 0.12f).sp)
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-side * 0.01f).dp),
        ) {
            Text(
                text = title,
                color = Palette.TextSecondary,
                fontSize = (side * 0.095f).sp,
                maxLines = 1,
            )
        }
    }
}
