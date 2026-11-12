package com.nguevara.obdpulse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nguevara.obdpulse.ui.theme.Palette

private enum class GuideTopic(val title: String) {
    STFT("Ajuste Corto (STFT)"),
    LTFT("Ajuste Largo (LTFT)"),
    LAMBDA("Lambda (λ) Real"),
    LAMBDA_CMD("Lambda Comandada"),
}

/**
 * Diálogo interactivo didáctico sobre la mezcla de combustible:
 * Ajustes de combustible (STFT / LTFT), Sondas Lambda y Lambda Comandada.
 */
@Composable
fun FuelTrimLambdaGuideDialog(onDismiss: () -> Unit) {
    var topic by remember { mutableStateOf(GuideTopic.STFT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Palette.Cyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.HelpOutline, contentDescription = null, tint = Palette.Cyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Guía de Mezcla y Combustible", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                    Text("STFT, LTFT, Lambda y Lambda Comandada", fontSize = 12.sp, color = Palette.TextSecondary)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Selector de pestañas
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GuideTopic.entries.forEach { t ->
                        FilterChip(
                            selected = topic == t,
                            onClick = { topic = t },
                            label = { Text(t.name, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Cyan.copy(alpha = 0.2f),
                                selectedLabelColor = Palette.Cyan,
                                labelColor = Palette.TextSecondary,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                LazyColumn(Modifier.height(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (topic) {
                        GuideTopic.STFT -> {
                            item { TopicHeader("Ajuste Corto de Combustible (STFT B1 / PID 0106)", Palette.Cyan) }
                            item {
                                CardInfo(
                                    "¿Qué es?",
                                    "Es la corrección inmediata en tiempo real que la ECU aplica al ancho de pulso de inyección en respuesta instantánea al sensor de oxígeno pre-catalizador.",
                                )
                            }
                            item {
                                RangeRow("Rango Normal Óptimo", "-5% a +5%", Palette.Green, "Oscila constantemente arriba y abajo en lazo cerrado.")
                            }
                            item {
                                RangeRow("Valores Positivos (+)", "Añade combustible", Palette.Amber, "La ECU detecta exceso de oxígeno (mezcla pobre) y alarga el pulso de los inyectores.")
                            }
                            item {
                                RangeRow("Valores Negativos (-)", "Resta combustible", Palette.Violet, "La ECU detecta falta de oxígeno (mezcla rica) y acorta el pulso de los inyectores.")
                            }
                            item {
                                CardInfo(
                                    "Causas de Alarma (> ±10%)",
                                    "• Fugas de vacío en mangueras de admisión o juntas.\n• Presión de gasolina baja o regulador trabado.\n• Inyectores sucios o sensor MAF descalibrado.\n• Entrada de aire no medido tras el sensor de flujo.",
                                    Palette.Red,
                                )
                            }
                        }

                        GuideTopic.LTFT -> {
                            item { TopicHeader("Ajuste Largo de Combustible (LTFT B1 / PID 0107)", Palette.Amber) }
                            item {
                                CardInfo(
                                    "¿Qué es?",
                                    "Es la corrección permanente memorizada por la ECU. Si el ajuste corto (STFT) permanece desviado por varios minutos, la ECU lo traslada al LTFT para compensar desgaste del motor o altitud.",
                                )
                            }
                            item {
                                RangeRow("Rango Saludable", "-8% a +8%", Palette.Green, "Cercano a 0% indica motor en perfecto balance estequiométrico.")
                            }
                            item {
                                RangeRow("Falla P0171: Mezcla Pobre", "> +15% a +25%", Palette.Red, "La ECU está forzada a inyectar mucho más combustible del mapa de fábrica.")
                            }
                            item {
                                RangeRow("Falla P0172: Mezcla Rica", "< -15% a -25%", Palette.Red, "La ECU está quitando combustible al límite para evitar ahogo.")
                            }
                            item {
                                CardInfo(
                                    "¿Cómo diagnosticar?",
                                    "Si el LTFT es muy positivo en ralentí pero mejora al acelerar a 2500 rpm: casi seguro es una FUGA DE VACÍO.\nSi el LTFT es positivo en todo momento o empeora bajo carga: sospecha de sensor MAF sucio o bomba de gasolina baja de presión.",
                                )
                            }
                        }

                        GuideTopic.LAMBDA -> {
                            item { TopicHeader("Lambda (λ) Real (PID 0124 Banda Ancha)", Palette.Green) }
                            item {
                                CardInfo(
                                    "¿Qué es Lambda (λ)?",
                                    "Es la relación matemática entre el aire admitido y el aire necesario para combustión completa perfecta (estequiométrica = 14.7 kg de aire por 1 kg de gasolina).",
                                )
                            }
                            item {
                                RangeRow("λ = 1.000 (Estequiométrica)", "AFR = 14.7 : 1", Palette.Green, "Equilibrio perfecto donde el catalizador destruye el 99% de gases nocivos.")
                            }
                            item {
                                RangeRow("λ < 1.000 (Mezcla Rica)", "Ej. λ = 0.85 (AFR 12.5)", Palette.Amber, "Más combustible. Produce MÁXIMA POTENCIA y enfría las válvulas y pistones a fondo.")
                            }
                            item {
                                RangeRow("λ > 1.000 (Mezcla Pobre)", "Ej. λ = 1.10 (AFR 16.2)", Palette.Cyan, "Más aire. Máxima economía en velocidad crucero; si pasa de 1.15 puede causar tirones o sobrecalentar válvulas.")
                            }
                        }

                        GuideTopic.LAMBDA_CMD -> {
                            item { TopicHeader("Lambda Comandada (PID 0144)", Palette.Pink) }
                            item {
                                CardInfo(
                                    "¿Qué representa?",
                                    "Es el objetivo que la computadora exige al sistema en este milisegundo según su mapa de inyección, la temperatura del motor y el pedal.",
                                )
                            }
                            item {
                                CardInfo(
                                    "Comportamiento normal",
                                    "• Ralentí y crucero: Comandada = 1.000.\n• Aceleración a fondo (WOT): Comandada baja a 0.85 - 0.90 para proteger el motor y dar potencia.\n• Freno motor en bajada (Cut-off): Sube a 1.99+ (corte total de inyección).",
                                )
                            }
                            item {
                                CardInfo(
                                    "Comparación Crítica (Real vs Comandada)",
                                    "Si la ECU comanda λ = 0.88 pero la Lambda Real se queda en 1.05: el motor está sufriendo falta de combustible peligrosa (peligro de picado/pistoneo).",
                                    Palette.Red,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Entendido") } },
        containerColor = Palette.Surface,
    )
}

@Composable
private fun TopicHeader(title: String, color: Color) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
}

@Composable
private fun CardInfo(title: String, content: String, borderColor: Color = Palette.Outline) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Palette.SurfaceHigh,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(content, fontSize = 12.sp, color = Palette.TextSecondary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun RangeRow(label: String, value: String, color: Color, note: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Palette.SurfaceHigh,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                Pill(value, color)
            }
            Spacer(Modifier.height(4.dp))
            Text(note, fontSize = 11.sp, color = Palette.TextMuted, lineHeight = 15.sp)
        }
    }
}
