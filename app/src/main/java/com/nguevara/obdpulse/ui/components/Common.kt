package com.nguevara.obdpulse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nguevara.obdpulse.Sample
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette

val CardShape = RoundedCornerShape(20.dp)

/** Tarjeta base de la app: superficie oscura, borde sutil, título opcional. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
    ) {
        Column(Modifier.padding(contentPadding.dp)) {
            if (title != null || trailing != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Text(
                            title.uppercase(),
                            color = Palette.TextSecondary,
                            fontSize = 12.sp,
                            letterSpacing = 1.4.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** Valor numérico con unidad, ícono y mini-gráfica. */
@Composable
fun MetricTile(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    samples: List<Sample> = emptyList(),
    subtitle: String? = null,
) {
    SectionCard(modifier = modifier, contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle, fontSize = 28.sp, color = Palette.TextPrimary, lineHeight = 30.sp)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = color, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (subtitle != null) {
            Text(subtitle, color = Palette.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Sparkline(
            samples = samples,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
        )
    }
}

/** Fila etiqueta–valor para listas de información. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Palette.TextPrimary) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.TextSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** Etiqueta pequeña con fondo de color. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Pantalla vacía con ícono, texto y acciones. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Palette.Cyan, modifier = Modifier.size(36.dp))
            }
            Text(title, fontSize = 20.sp, color = Palette.TextPrimary, textAlign = TextAlign.Center)
            Text(message, fontSize = 14.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            if (primaryLabel != null) {
                Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth(0.8f)) { Text(primaryLabel) }
            }
            if (secondaryLabel != null) {
                OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth(0.8f)) { Text(secondaryLabel) }
            }
        }
    }
}
