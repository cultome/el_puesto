package com.alephri.elpuesto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.platform.BackHandler
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.SurfaceTop
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub

/** Alto que ocupan arriba la barra y la barra de capas en pantalla completa. */
val FullscreenTopInset: Dp = 132.dp

/**
 * Barra superior de un mapa a pantalla completa, igual en todos: atrás a la izquierda, el
 * título en medio y "salir de pantalla completa" a la derecha (donde estaba el botón de
 * entrar). Ambos botones cierran la pantalla completa.
 */
@Composable
fun FullscreenMapTopBar(onClose: () -> Unit, modifier: Modifier = Modifier, title: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MapButtonBox("Regresar", onClick = onClose) { Text("‹", color = TextPrimary, fontSize = 24.sp) }
        Row(
            Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceTop.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = title,
        )
        MapButton(LineIcon.FULLSCREEN_EXIT, "Salir de pantalla completa", onClick = onClose)
    }
}

/** Título de dos renglones de la barra de pantalla completa ("GRAN PREMIO" / circuito). */
@Composable
fun RowScope.FullscreenTitle(kicker: String, title: String) {
    Column(Modifier.weight(1f)) {
        // Alto de línea fijo: dos renglones caben en la barra de 42 dp en Android y en la web.
        Text(kicker.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 1.sp, color = Amber)
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, color = TextHi)
    }
}

/** Alto de la barra de navegación del sistema (los controles de abajo van por encima). */
@Composable
fun navigationBarBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * El mapa de un trazado a pantalla completa (Circuito y Registro por honor; el del evento es
 * el Mapa en vivo): se dibuja ENCIMA de su pantalla, que sigue viva debajo, así que las capas,
 * lo elegido y lo capturado se conservan al salir. Atrás / Escape / salir la cierran.
 * [bottom] = lo propio de la pantalla abajo (p. ej. "Listo" del registro).
 */
@Composable
fun TrackMapFullscreen(
    kicker: String,
    title: String,
    trazadoPath: List<MapPoint>,
    mapImage: ImageBitmap?,
    items: List<MapItem>,
    layers: Set<MapLayer>,
    onToggleLayer: (MapLayer) -> Unit,
    selectedId: String?,
    onSelectItem: ((String?) -> Unit)?,
    onClose: () -> Unit,
    hint: String? = null,
    onTapMap: ((MapPoint) -> Unit)? = null,
    bottomInset: Dp = 0.dp,
    bottom: (@Composable BoxScope.() -> Unit)? = null,
) {
    BackHandler { onClose() }
    DisposableEffect(Unit) {
        MapMemory.fullscreenOpen = true
        onDispose { MapMemory.fullscreenOpen = false }
    }
    val nav = navigationBarBottom()
    Box(Modifier.fillMaxSize().background(PanelAlt)) {
        TrackMap(
            trazadoPath, mapImage, items, layers, selectedId, onSelectItem,
            modifier = Modifier.fillMaxSize(), corner = 0.dp,
            controlsBottom = 24.dp + nav + bottomInset,
            fitInsetTop = FullscreenTopInset, fitInsetBottom = bottomInset,
            onTapMap = onTapMap, wheelZoom = true,
        )
        FullscreenMapTopBar(onClose) { FullscreenTitle(kicker, title) }
        LayerBar(items, layers, onToggleLayer, Modifier.padding(start = 16.dp, end = 16.dp, top = 66.dp), overlay = true)
        hint?.let {
            Text(
                it, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = FullscreenTopInset + 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(SurfaceTop.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        bottom?.invoke(this)
    }
}
