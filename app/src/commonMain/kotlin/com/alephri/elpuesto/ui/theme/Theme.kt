package com.alephri.elpuesto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush

/**
 * Colores que no encajan en el ColorScheme de Material 3 (semánticos de bandera, tonos de
 * panel/borde/texto). Se exponen vía CompositionLocal y se acceden con `ElPuestoTheme.colors`.
 */
@Immutable
data class ElPuestoColors(
    val panel: androidx.compose.ui.graphics.Color = Panel,
    val panelAlt: androidx.compose.ui.graphics.Color = PanelAlt,
    val panelElevA: androidx.compose.ui.graphics.Color = PanelElevA,
    val panelElevB: androidx.compose.ui.graphics.Color = PanelElevB,
    val border: androidx.compose.ui.graphics.Color = Border,
    val borderStrong: androidx.compose.ui.graphics.Color = BorderStrong,
    val divider: androidx.compose.ui.graphics.Color = Divider,
    val amberHi: androidx.compose.ui.graphics.Color = AmberHi,
    val amberLo: androidx.compose.ui.graphics.Color = AmberLo,
    val textSub: androidx.compose.ui.graphics.Color = TextSub,
    val textMut: androidx.compose.ui.graphics.Color = TextMut,
    val textFaint: androidx.compose.ui.graphics.Color = TextFaint,
    val placeholder: androidx.compose.ui.graphics.Color = Placeholder,
    val live: androidx.compose.ui.graphics.Color = Live,
    val danger: androidx.compose.ui.graphics.Color = Danger,
    val dangerHi: androidx.compose.ui.graphics.Color = DangerHi,
    val travel: androidx.compose.ui.graphics.Color = Travel,
    val flagYellow: androidx.compose.ui.graphics.Color = FlagYellow,
)

val LocalElPuestoColors = staticCompositionLocalOf { ElPuestoColors() }

/** ColorScheme de Material 3 (siempre oscuro). */
private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    secondary = Amber,
    onSecondary = OnAmber,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Panel,
    onSurfaceVariant = TextSub,
    outline = Border,
    error = Danger,
    onError = OnAmber,
)

@Composable
fun ElPuestoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalElPuestoColors provides ElPuestoColors()) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = ElPuestoTypography,
            shapes = ElPuestoShapes,
            content = content,
        )
    }
}

/** Accesor a los colores de extensión: `ElPuestoTheme.colors.live`, etc. */
object ElPuestoTheme {
    val colors: ElPuestoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalElPuestoColors.current
}

/**
 * Fondo estándar de pantalla: gradiente carbón de arriba (SurfaceTop) hacia abajo (Bg).
 * Aproxima el radial de los mockups.
 */
fun screenBackground(): Brush = Brush.verticalGradient(listOf(SurfaceTop, Surface, Bg))
