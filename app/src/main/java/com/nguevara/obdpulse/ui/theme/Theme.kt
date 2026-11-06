package com.nguevara.obdpulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta tipo "cabina de noche": fondo casi negro, acentos cian/ámbar/verde. */
object Palette {
    val Background = Color(0xFF0A0E13)
    val Surface = Color(0xFF121922)
    val SurfaceHigh = Color(0xFF1A2330)
    val Outline = Color(0xFF263241)
    val Track = Color(0xFF223040)

    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFF59E0B)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
    val Violet = Color(0xFFA78BFA)
    val Pink = Color(0xFFF472B6)

    val TextPrimary = Color(0xFFE6EDF5)
    val TextSecondary = Color(0xFF8A9BB0)
    val TextMuted = Color(0xFF5B6B80)

    /** Colores para series de gráficas (en orden). */
    val Series = listOf(Cyan, Amber, Green, Violet, Pink, Red)
}

private val colors = darkColorScheme(
    primary = Palette.Cyan,
    onPrimary = Color(0xFF00222A),
    primaryContainer = Color(0xFF0E3A45),
    onPrimaryContainer = Color(0xFFB9F3FC),
    secondary = Palette.Amber,
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF3D2A07),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Palette.Green,
    onTertiary = Color(0xFF00281A),
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF223040),
    surfaceContainerLow = Color(0xFF0F151D),
    outline = Palette.Outline,
    outlineVariant = Color(0xFF1E2935),
    error = Palette.Red,
    onError = Color(0xFF3A0808),
)

/** Números con ancho fijo ("tnum") para que no "bailen" al cambiar. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)

private val typography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ObdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
