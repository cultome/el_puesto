package com.alephri.elpuesto.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alephri.elpuesto.ui.theme.PanelElevA

/** N filas skeleton de altura fija — para listas cargando (usar dentro de un Column). */
@Composable
fun SkeletonRows(count: Int, rowHeight: Dp, corner: Dp = 14.dp, spacing: Dp = 10.dp) {
    repeat(count) {
        SkeletonBox(Modifier.fillMaxWidth().height(rowHeight), corner = corner)
        Spacer(Modifier.height(spacing))
    }
}

/** Bloque placeholder con un shimmer sutil (pulso de opacidad) para estados de carga. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(animation = tween(850), repeatMode = RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.clip(RoundedCornerShape(corner)).background(PanelElevA.copy(alpha = alpha)))
}
