package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel

/**
 * Imagen remota del pipeline de imágenes (elige variante `thumb`/`full` por tamaño) con
 * un fallback componible si no hay imagen. Base de RemoteAvatar y de los logos. Mientras
 * carga del teléfono (o la red) muestra un hueco neutro del mismo tamaño, no el fallback:
 * así no "salta" de iniciales a foto. Lo ya visto sale de memoria al primer cuadro.
 * [onOpen] (opcional) hace tocable la imagen cuando SÍ existe y recibe la ruta de su
 * variante `full` (para el visor); el fallback nunca es tocable.
 */
@Composable
fun RemoteImageBox(
    repo: AppRepository,
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shape: Shape = CircleShape,
    onOpen: ((fullPath: String) -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val path = remember(url, size) {
        url?.let { it.substringBeforeLast('/') + "/" + if (size >= 60.dp) "full" else "thumb" }
    }
    when (val s = rememberRemoteImage(repo, path)) {
        is ImageLoad.Ready -> Image(
            bitmap = s.bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).let { m ->
                if (onOpen == null || path == null) m
                else m.clickable(onClickLabel = "Ver en grande") { onOpen(path.substringBeforeLast('/') + "/full") }
            },
        )
        ImageLoad.Loading -> Box(modifier.size(size).clip(shape).background(Panel))
        else -> fallback()
    }
}

/**
 * Avatar con foto remota (variante `thumb` para tamaños chicos, `full` para grandes) y
 * fallback a iniciales mientras carga o si el oficial no tiene foto. [onOpen]: ver
 * [RemoteImageBox] (tocar la foto para verla en grande).
 */
@Composable
fun RemoteAvatar(
    repo: AppRepository,
    avatarUrl: String?,
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    bg: Color = Amber,
    textColor: Color = OnAmber,
    onOpen: ((fullPath: String) -> Unit)? = null,
) {
    RemoteImageBox(repo, avatarUrl, modifier = modifier, size = size, shape = CircleShape, onOpen = onOpen) {
        Avatar(initials, modifier = modifier, size = size, bg = bg, textColor = textColor)
    }
}
