package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Íconos de línea (trazos SVG de 24×24, los mismos de los mockups de Claude Design) sin
 * dependencia de librerías de íconos. [fill] = partes rellenas opcionales (cuadros, banderines).
 */
enum class LineIcon(val d: String, val fill: String? = null) {
    PLANE("M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z"),
    BED("M3 19V7M3 15h18v4M21 15v-3a3 3 0 0 0-3-3h-7v6M5.2 11.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"),
    BELL("M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0"),
    FLAG("M5 21V4M5 4h11l-2 4 2 4H5"),
    PIN("M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11zM9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
    NOTE("M6 4h9l3 3v13H6zM9 11h6M9 15h4"),
    CAMERA("M4 8h3l2-2.5h6L17 8h3v11H4zM8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0"),
    PENCIL("M4 20h4L19 9l-4-4L4 16z"),
    TRASH("M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13"),
    EXTERNAL("M14 5h5v5M19 5l-8 8M18 14v5H5V6h5"),
    PLUS("M12 5v14M5 12h14"),
    LOCK("M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2H7a2 2 0 0 1 -2 -2v-5a2 2 0 0 1 2 -2zM8 11V8a4 4 0 0 1 8 0v3"),
    CHEVRON("M9 5l7 7-7 7"),
    CLOSE("M6 6l12 12M18 6L6 18"),
    CLOCK("M12 7v5l3 2M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"),
    FULLSCREEN("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"),
    FULLSCREEN_EXIT("M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"),
    MINUS("M5 12h14"),
    // "Ver todo el trazado" (quita el zoom): la silueta de un circuito.
    FIT_TRACK("M8 5h8.5A2.5 2.5 0 0 1 19 7.5v2c0 .9-.5 1.5-1.3 1.9-.7.4-.8 1.3-.2 1.8.9.7 1.5 1.5 1.5 2.8a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z"),
    SEARCH("M11 18a7 7 0 1 0 0-14a7 7 0 1 0 0 14zM20 20l-4.2-4.2"),
    CHAT("M4 5h16v11H9l-5 4z"),
    CALENDAR("M4 6h16v14H4zM4 10h16M8 3v4M16 3v4"),
    PODIUM("M4 20v-7h5v7M9 20V8h6v12M15 20v-10h5v10M3 20h18"),
    ARCHIVE("M3 5h18v4H3zM5 9v10h14V9M10 13h4"),
    SHIELD("M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6z"),
    MEGAPHONE("M3 10v4h3l7 5V5L6 10zM17 9a4 4 0 0 1 0 6"),
    // Logros (gamificación): glifos de medallas, hitos y primeras veces.
    TROPHY("M8 4h8v5a4 4 0 0 1-8 0zM8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4M12 13v4M8 20h8M9 17h6"),
    LAYERS("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5M3 17.5l9 5 9-5"),
    RANK("M6 14l6-5 6 5M6 19l6-5 6 5M6 9l6-5 6 5"),
    STAR("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"),
    GLOBE("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"),
    SHIELD_PLUS("M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6zM12 9v6M9 12h6"),
    CLIPBOARD_CHECK("M9 4h6v3H9zM7 5H5v16h14V5h-2M9 14l2 2 4-4"),
    USER_PLUS("M6 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M3 21c0-4 3-6 7-6s7 2 7 6M19 8v6M16 11h6"),
    IMAGE("M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M14 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
    SUITCASE("M4 8h16v12H4zM9 8V5h6v3M4 13h16"),
    CHECK("M5 12.5l4.5 4.5L19 7.5"),
    DOWNLOAD("M12 4v11M7 10l5 5 5-5M5 20h14"),
    ALERT("M12 4l9 16H3zM12 10v4M12 17h.01"),
    EYE("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
    // Barra inferior (canvas "Iconos del bottom bar" de Claude Design): box de pits,
    // calendario con la franja a cuadros y radio de mano.
    GARAGE("M3 20V9.5L12 4l9 5.5V20M6.5 20v-8h11v8M6.5 15h11M6.5 17.5h11"),
    CALENDAR_CHECKERED(
        "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v12a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-12A1.5 1.5 0 0 1 5.5 5z" +
            "M4 10h16M8 14h.01M12 14h.01M16 14h.01M8 17h.01M12 17h.01",
        fill = "M5.5 5H8v2.5H4V6.5A1.5 1.5 0 0 1 5.5 5zM12 5h4v2.5h-4zM8 7.5h4V10H8zM16 7.5h4V10h-4z",
    ),
    RADIO(
        "M7.5 8h9A1.5 1.5 0 0 1 18 9.5v10a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 19.5v-10A1.5 1.5 0 0 1 7.5 8z" +
            "M9 8V2.5M9.5 12h5M9.5 14.75h5M9.5 17.5h5",
    ),
    WIFI_OFF(
        "M12 20h.01M8.5 16.43a5 5 0 0 1 7 0M5 12.86a10 10 0 0 1 5.17-2.69M19 12.86a10 10 0 0 0-2-1.52" +
            "M2 8.82a15 15 0 0 1 4.18-2.64M22 8.82a15 15 0 0 0-11.29-3.76M2 2l20 20",
    ),
}

@Composable
fun LineIconView(icon: LineIcon, color: Color, size: Dp = 16.dp, strokeWidth: Float = 2f, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    val fill = remember(icon) { icon.fill?.let { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (fill != null) drawPath(fill, color)
        }
    }
}
