package com.nguevara.obdpulse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.data.Units
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import java.util.Locale

@Composable
fun SensorDetailDialog(metric: Metric, currentValue: Double?, settings: AppSettings, onDismiss: () -> Unit) {
    val displayVal = Units.display(metric, currentValue, settings)
    val unitStr = Units.unit(metric, settings)
    val outOfRange = currentValue != null &&
        (currentValue < metric.min || currentValue > metric.max)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Palette.Cyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Palette.Cyan, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(metric.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, lineHeight = 20.sp)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        Text(metric.category.label, fontSize = 12.sp, color = Palette.TextSecondary)
                        if (metric.pid != null) {
                            Text("· PID ${String.format(Locale.US, "%02X", metric.pid)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Palette.Cyan)
                        } else {
                            Text("· Magnitud calculada", fontSize = 12.sp, color = Palette.Violet)
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Palette.SurfaceHigh,
                    border = BorderStroke(1.dp, Palette.Outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("LECTURA ACTUAL", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Palette.TextSecondary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (metric.fast) "Actualización continua" else "Lectura periódica (cada 4 ciclos)",
                                fontSize = 11.sp,
                                color = Palette.TextMuted,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(displayVal, style = NumberStyle, fontSize = 26.sp, color = Palette.Cyan)
                            if (unitStr.isNotBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Text(unitStr, fontSize = 14.sp, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("DESCRIPCIÓN Y SIGNIFICADO TÉCNICO", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Palette.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Palette.SurfaceHigh,
                    border = BorderStroke(1.dp, Palette.Outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        metric.description.ifBlank { "Magnitud estándar SAE J1979 reportada por la unidad de control del motor (ECU)." },
                        fontSize = 13.sp,
                        color = Palette.TextPrimary,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                    val (minV, maxV) = Units.range(metric, settings)
                    Text("Escala típica: ${Units.format(minV, 0)} a ${Units.format(maxV, 0)} $unitStr", fontSize = 11.sp, color = Palette.TextMuted)
                }
                if (outOfRange) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Palette.Red.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Palette.Red.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "ALERTA: lectura fuera del rango orientativo. Puede ser una condición real, ruido o falta de calibración; esta app no puede confirmar la calibración de sensores de la ECU. Para el IMU del teléfono, calibra solo con el equipo quieto.",
                            fontSize = 12.sp,
                            color = Palette.TextPrimary,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        containerColor = Palette.Surface,
    )
}
