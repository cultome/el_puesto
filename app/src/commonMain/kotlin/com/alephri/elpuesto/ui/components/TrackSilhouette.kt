package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alephri.elpuesto.model.MapPoint

/**
 * Silueta de un trazado (`Trazado.path`, coordenadas normalizadas 0..1 en un área
 * cuadrada) como motivo: un trazo de color sobre una "cinta" opcional más ancha. El lazo
 * se cierra implícitamente (último → primero), como en el mapa.
 */
@Composable
fun TrackSilhouette(
    path: List<MapPoint>,
    modifier: Modifier,
    color: Color,
    width: Dp = 2.dp,
    under: Color? = null,
    underWidth: Dp = 6.dp,
    padFraction: Float = 0.06f,
) {
    if (path.size < 3) return
    Canvas(modifier) {
        val side = kotlin.math.min(size.width, size.height)
        val pad = side * padFraction
        val span = side - 2 * pad
        val ox = (size.width - side) / 2f + pad
        val oy = (size.height - side) / 2f + pad
        val p = Path()
        path.forEachIndexed { i, pt ->
            val o = Offset(ox + pt.x * span, oy + pt.y * span)
            if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
        }
        p.close()
        under?.let { drawPath(p, it, style = Stroke(width = underWidth.toPx(), join = StrokeJoin.Round)) }
        drawPath(p, color, style = Stroke(width = width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
