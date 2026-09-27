package com.alephri.elpuesto.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.MapPuestoFill
import com.alephri.elpuesto.ui.theme.OnMapFill
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.SurfaceTop
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.Travel
import kotlinx.coroutines.launch

/**
 * Persona sobre el mapa (compartir ubicación): círculo con su foto, distinto de las
 * píldoras de posiciones. [stale] = señal vieja (atenuado); [self] = "Tú".
 */
data class PersonPin(
    val id: String,
    val initials: String,
    val point: MapPoint,
    val stale: Boolean,
    val self: Boolean = false,
    /** Miniatura de su foto de perfil (null = iniciales). */
    val avatarUrl: String? = null,
    /** Etiqueta al seleccionarlo ("Ana Ruiz · hace 20 s"). */
    val label: String? = null,
)

/**
 * El mapa de un trazado, IGUAL en toda la app (Circuito, tab Puesto, Mapa en vivo, Registro
 * por honor; decisión 2026-09-27): silueta (o el mapa oficial), marcadores de las [layers]
 * visibles, personas que comparten su ubicación y los mismos controles en las mismas
 * esquinas — pantalla completa arriba a la derecha ([onFullscreen]), zoom abajo a la derecha
 * (ver todo, acercar, alejar) y el crédito de OpenStreetMap abajo a la izquierda.
 *
 * Marcadores: el color de su capa en el borde y el texto; relleno SOLO lo especial (tu
 * puesto, "asignado antes", lo que tocas). Tocar uno lo elige y dice qué es.
 */
@Composable
fun TrackMap(
    trazadoPath: List<MapPoint>,
    mapImage: ImageBitmap?,
    items: List<MapItem>,
    layers: Set<MapLayer>,
    selectedId: String?,
    /** Tocar un marcador lo elige (o lo suelta si ya estaba elegido); null = no se tocan. */
    onSelectItem: ((String?) -> Unit)?,
    /** Tamaño/forma del mapa (default: tarjeta 1.25:1; en pantalla completa lo llena todo). */
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1.25f),
    corner: Dp = 18.dp,
    /** Indicación de una ACCIÓN (p. ej. "Toca en el mapa dónde estaba tu puesto"). */
    hint: String? = null,
    people: List<PersonPin> = emptyList(),
    selectedPersonId: String? = null,
    /** Para pintar la FOTO de cada persona (sin él: iniciales). */
    avatarRepo: AppRepository? = null,
    onSelectPerson: ((String?) -> Unit)? = null,
    /** Cada cambio (> 0) encuadra a todas las personas visibles. */
    fitKey: Int = 0,
    /** Acerca y centra este marcador (al abrir y cada vez que cambia [flyKey]). */
    flyToId: String? = null,
    flyKey: Int = 0,
    controlsBottom: Dp = 10.dp,
    /** Zonas tapadas (barra arriba, hoja abajo): el encuadre usa solo lo visible. */
    fitInsetTop: Dp = 0.dp,
    fitInsetBottom: Dp = 0.dp,
    /** Tocar el MAPA (fuera de los pins): el punto normalizado 0..1 (p. ej. proponer un puesto). */
    onTapMap: ((MapPoint) -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    /**
     * La rueda del mouse acerca/aleja (web). Solo en pantalla completa: en una tarjeta la
     * rueda debe seguir desplazando la página.
     */
    wheelZoom: Boolean = false,
) {
    val tapMap by rememberUpdatedState(onTapMap)
    // Zoom del mapa (con muchos puestos/activos todo se encima): pellizcar acerca/aleja,
    // con zoom un dedo arrastra, doble toque acerca ahí o regresa a la vista completa.
    // Sin zoom un dedo NO se consume (sigue el scroll/swipe de la pantalla). Los pins
    // conservan su tamaño: al acercar se separan en vez de crecer.
    val scope = rememberCoroutineScope()
    val zoomAnim = remember { Animatable(1f) }
    val panAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoom = zoomAnim.value
    val pan = panAnim.value
    var boxPx by remember { mutableStateOf(Size.Zero) }
    fun clampPan(p: Offset, z: Float) = Offset(
        p.x.coerceIn(boxPx.width * (1 - z), 0f),
        p.y.coerceIn(boxPx.height * (1 - z), 0f),
    )
    // Lee el zoom/desplazamiento ACTUALES (no los de la composición): se llama desde gestos.
    fun zoomTo(z: Float, focus: Offset, animated: Boolean = true) {
        val z0 = zoomAnim.value
        val nz = z.coerceIn(1f, MAX_ZOOM)
        // Mantiene fijo el punto del contenido que está bajo [focus].
        val np = clampPan(focus - (focus - panAnim.value) * (nz / z0), nz)
        scope.launch {
            if (animated) {
                launch { zoomAnim.animateTo(nz) }
                panAnim.animateTo(np)
            } else { zoomAnim.snapTo(nz); panAnim.snapTo(np) }
        }
    }
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelectItem)
    val visible = items.filter { it.visibleWith(layers) }

    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(corner))
            .background(PanelAlt).border(1.dp, Border, RoundedCornerShape(corner))
            .onSizeChanged { boxPx = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        // Solo con movimiento real: un toque quieto (p. ej. en los botones de
                        // zoom) NO debe hacer snap, porque cancelaría la animación del botón.
                        val moved = zoomChange != 1f || panChange != Offset.Zero
                        if (moved && (fingers >= 2 || zoomAnim.value > 1f)) {
                            val z0 = zoomAnim.value
                            val nz = (z0 * zoomChange).coerceIn(1f, MAX_ZOOM)
                            val c = event.calculateCentroid(useCurrent = true)
                            val base = if (c == Offset.Unspecified) panAnim.value else c - (c - panAnim.value) * (nz / z0)
                            val np = clampPan(base + panChange, nz)
                            scope.launch { zoomAnim.snapTo(nz); panAnim.snapTo(np) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(wheelZoom) {
                if (!wheelZoom) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val ch = e.changes.firstOrNull() ?: continue
                        val dy = ch.scrollDelta.y
                        if (dy == 0f) continue
                        zoomTo(zoomAnim.value * if (dy < 0f) 1.25f else 0.8f, ch.position, animated = false)
                        ch.consume()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { c -> if (zoomAnim.value > 1.05f) zoomTo(1f, c) else zoomTo(2.5f, c) },
                    onTap = { c ->
                        // Pantalla → punto normalizado: inversa de screenX/screenY (área
                        // cuadrada centrada, con el zoom y el desplazamiento actuales).
                        val cb = tapMap
                        if (cb == null) {
                            // Tocar fuera de los marcadores suelta el elegido.
                            select?.invoke(null)
                            return@detectTapGestures
                        }
                        val side = kotlin.math.min(boxPx.width, boxPx.height)
                        if (side <= 0f) return@detectTapGestures
                        val ox = (boxPx.width - side) / 2f
                        val oy = (boxPx.height - side) / 2f
                        val z = zoomAnim.value
                        val pan0 = panAnim.value
                        val x = ((c.x - pan0.x) / z - ox) / side
                        val y = ((c.y - pan0.y) / z - oy) / side
                        if (x in 0f..1f && y in 0f..1f) cb(MapPoint(x, y))
                    },
                )
            },
    ) {
        val w = maxWidth
        val h = maxHeight
        // Las coordenadas normalizadas (silueta y pins) son respecto a un ÁREA CUADRADA
        // (mismo criterio que el admin web): escala uniforme = lado menor, centrada en el
        // mapa. Silueta y pins comparten esta transformación (más el zoom) para quedar coherentes.
        val side = min(w, h)
        val offX = (w - side) / 2
        val offY = (h - side) / 2
        val panDp = with(density) { DpOffset(pan.x.toDp(), pan.y.toDp()) }
        fun screenX(p: MapPoint) = (offX + side * p.x) * zoom + panDp.x
        fun screenY(p: MapPoint) = (offY + side * p.y) * zoom + panDp.y
        val trackPoints = items.filter { it.layer == MapLayer.PUESTOS }.map { it.point }
        Canvas(Modifier.fillMaxSize()) {
            val sidePx = kotlin.math.min(size.width, size.height)
            val ox = (size.width - sidePx) / 2f
            val oy = (size.height - sidePx) / 2f
            fun toPx(p: MapPoint) = Offset((ox + p.x * sidePx) * zoom + pan.x, (oy + p.y * sidePx) * zoom + pan.y)
            // La cinta engorda un poco al acercar (se lee como pista), sin crecer al ritmo del zoom.
            val grow = 1f + (zoom - 1f) * 0.35f
            fun ribbon(path: Path) {
                drawPath(path, Color(0xFF3A4152), style = Stroke(width = 18.dp.toPx() * grow, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Color(0xFF20242E), style = Stroke(width = 11.dp.toPx() * grow, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (trazadoPath.size >= 3) {
                // Silueta REAL del trazado (dibujada en el admin; el polígono se cierra
                // implícitamente último → primero).
                val pts = trazadoPath.map { toPx(it) }
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
                ribbon(path)
            } else if (mapImage != null) {
                // Mapa oficial (mapUrl) contenido en la misma área cuadrada de referencia.
                val imgW = mapImage.width.toFloat()
                val imgH = mapImage.height.toFloat()
                val scale = kotlin.math.min(sidePx / imgW, sidePx / imgH) * zoom
                val dw = (imgW * scale).toInt()
                val dh = (imgH * scale).toInt()
                drawImage(
                    mapImage,
                    dstOffset = IntOffset(
                        ((ox + (sidePx - imgW * scale / zoom) / 2f) * zoom + pan.x).toInt(),
                        ((oy + (sidePx - imgH * scale / zoom) / 2f) * zoom + pan.y).toInt(),
                    ),
                    dstSize = IntSize(dw, dh),
                )
            } else if (trackPoints.size >= 3) {
                // Fallback provisional (sin dibujo capturado): cinta cerrada y suave que
                // pasa por los puestos (hollow = pista).
                val pts = trackPoints.map { toPx(it) }
                val n = pts.size
                fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val path = Path()
                val startM = mid(pts[n - 1], pts[0])
                path.moveTo(startM.x, startM.y)
                for (i in 0 until n) {
                    val curr = pts[i]
                    val m = mid(curr, pts[(i + 1) % n])
                    path.quadraticBezierTo(curr.x, curr.y, m.x, m.y)
                }
                path.close()
                ribbon(path)
            }
        }
        val hasMap = items.isNotEmpty() || mapImage != null || trazadoPath.size >= 3
        if (!hasMap) {
            Text("Sin mapa para este trazado.", fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextFaint, modifier = Modifier.align(Alignment.Center))
        } else {
            val box = 46.dp
            // Lo relleno y lo elegido, encima de lo demás.
            visible.sortedBy { (if (it.id == selectedId) 2 else 0) + (if (it.emphasis != Emphasis.NONE) 1 else 0) }.forEach { item ->
                val sel = item.id == selectedId
                Box(
                    Modifier.offset(x = screenX(item.point) - box / 2, y = screenY(item.point) - box / 2).size(box)
                        .then(
                            if (onSelectItem == null) Modifier
                            else Modifier.clip(CircleShape).clickable { onSelectItem(if (sel) null else item.id) },
                        )
                        .semantics { contentDescription = item.callout },
                    contentAlignment = Alignment.Center,
                ) { MapMarker(item, sel) }
            }
            // Globo del elegido: qué es ("IFRT4 · Rescate").
            visible.firstOrNull { it.id == selectedId }?.let { item ->
                val ax = with(density) { screenX(item.point).roundToPx() }
                val ay = with(density) { (screenY(item.point) - 17.dp).roundToPx() }
                Text(
                    item.callout, maxLines = 1, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = TextHi,
                    modifier = Modifier
                        .layout { m, c ->
                            val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                            layout(0, 0) { pl.place(ax - pl.width / 2, ay - pl.height) }
                        }
                        .clip(RoundedCornerShape(10.dp)).background(SurfaceTop.copy(alpha = 0.96f))
                        .border(1.dp, BorderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            if (zoom <= 1.01f) hint?.let {
                Text(it, fontFamily = PlexSansFamily, fontSize = 10.5.sp, color = TextFaint, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
        // Personas encima de las posiciones (misma transformación cuadrada). Solo las que
        // caen dentro del marco; las de fuera se listan como "fuera del circuito".
        // Cada pin se ancla por la PUNTA (el lugar exacto) y se dibuja hacia arriba.
        people.filter { it.point.x in 0f..1f && it.point.y in 0f..1f }
            .sortedBy { it.id == selectedPersonId } // el seleccionado, encima
            .forEach { p ->
                val sel = p.id == selectedPersonId
                val ax = with(density) { screenX(p.point).roundToPx() }
                val ay = with(density) { screenY(p.point).roundToPx() }
                Box(
                    Modifier.layout { m, c ->
                        val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) { pl.place(ax - pl.width / 2, ay - pl.height) }
                    },
                ) {
                    PersonPinView(p, sel, avatarRepo) { onSelectPerson?.invoke(if (sel) null else p.id) }
                }
            }
        // Las siluetas salen de OpenStreetMap (o se dibujan sobre él): su licencia (ODbL)
        // pide el crédito visible donde se muestran — a la altura de los controles, para que
        // no lo tape lo de abajo (la hoja del Mapa en vivo, "Listo" del registro).
        if (trazadoPath.size >= 3) {
            Text(
                "© OpenStreetMap", fontFamily = PlexSansFamily, fontSize = 9.sp, color = TextFaint,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = (controlsBottom - 2.dp).coerceAtLeast(8.dp)),
            )
        }
        if (hasMap) {
            onFullscreen?.let {
                MapButton(LineIcon.FULLSCREEN, "Mapa a pantalla completa", Modifier.align(Alignment.TopEnd).padding(10.dp), onClick = it)
            }
            // Controles de zoom (accesibles, además de los gestos): siempre los tres, en el
            // mismo lugar, para que no salten.
            val center = Offset(boxPx.width / 2f, boxPx.height / 2f)
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = controlsBottom),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapButton(LineIcon.FIT_TRACK, "Ver todo el trazado") { zoomTo(1f, center) }
                MapButton(LineIcon.PLUS, "Acercar") { zoomTo(zoomAnim.value * 1.6f, center) }
                MapButton(LineIcon.MINUS, "Alejar") { zoomTo(zoomAnim.value / 1.6f, center) }
            }
        }
    }
    // Encuadrar a todas las personas visibles (chip "N en vivo · Encuadrar").
    LaunchedEffect(fitKey) {
        if (fitKey == 0 || boxPx.width == 0f) return@LaunchedEffect
        val pts = people.map { it.point }.filter { it.x in 0f..1f && it.y in 0f..1f }
        if (pts.isEmpty()) return@LaunchedEffect
        val side = kotlin.math.min(boxPx.width, boxPx.height)
        val ox = (boxPx.width - side) / 2f
        val oy = (boxPx.height - side) / 2f
        val xs = pts.map { ox + side * it.x }
        val ys = pts.map { oy + side * it.y }
        val pad = with(density) { 48.dp.toPx() } // el pin mide ~40 dp hacia arriba de su punta
        val top = with(density) { fitInsetTop.toPx() }
        val usableH = (boxPx.height - top - with(density) { fitInsetBottom.toPx() }).coerceAtLeast(boxPx.height / 3f)
        val bw = (xs.max() - xs.min()) + pad * 2
        val bh = (ys.max() - ys.min()) + pad * 2
        val nz = kotlin.math.min(boxPx.width / bw, usableH / bh).coerceIn(1f, MAX_ZOOM)
        val c = Offset((xs.max() + xs.min()) / 2f, (ys.max() + ys.min()) / 2f - pad / 3f)
        val np = clampPan(Offset(boxPx.width / 2f, top + usableH / 2f) - c * nz, nz)
        launch { zoomAnim.animateTo(nz) }
        panAnim.animateTo(np)
    }
    // Persona elegida (en la tira o en la lista): centrarla sin alejar.
    LaunchedEffect(selectedPersonId) {
        val p = people.firstOrNull { it.id == selectedPersonId } ?: return@LaunchedEffect
        if (boxPx.width == 0f || p.point.x !in 0f..1f || p.point.y !in 0f..1f) return@LaunchedEffect
        val side = kotlin.math.min(boxPx.width, boxPx.height)
        val base = Offset((boxPx.width - side) / 2f + side * p.point.x, (boxPx.height - side) / 2f + side * p.point.y)
        val nz = kotlin.math.max(zoomAnim.value, 2f)
        val np = clampPan(Offset(boxPx.width / 2f, boxPx.height / 2f) - base * nz, nz)
        launch { zoomAnim.animateTo(nz) }
        panAnim.animateTo(np)
    }
    // Ubicar un marcador ("Ubicar" en la lista, tu puesto al abrir): acerca y lo centra.
    LaunchedEffect(flyToId, flyKey, boxPx.width > 0f) {
        val item = items.firstOrNull { it.id == flyToId } ?: return@LaunchedEffect
        if (boxPx.width == 0f) return@LaunchedEffect
        val side = kotlin.math.min(boxPx.width, boxPx.height)
        val base = Offset((boxPx.width - side) / 2f + side * item.point.x, (boxPx.height - side) / 2f + side * item.point.y)
        val nz = kotlin.math.max(zoomAnim.value, 2.5f)
        val np = clampPan(Offset(boxPx.width / 2f, boxPx.height / 2f) - base * nz, nz)
        launch { zoomAnim.animateTo(nz) }
        panAnim.animateTo(np)
    }
}

private const val MAX_ZOOM = 6f

/**
 * Marcador de una posición: píldora con su label ("MP 1", "0.2", "TH1"…). Normal = el color
 * de su capa en el borde y el texto, sobre un tinte leve; relleno = lo especial (tu puesto
 * con halo, "asignado antes") o lo elegido (además con borde oscuro y aro blanco).
 */
@Composable
fun MapMarker(item: MapItem, selected: Boolean) {
    val shape = RoundedCornerShape(50)
    val color = item.layer.color
    val filled = selected || item.emphasis != Emphasis.NONE
    val halo = when {
        selected -> Modifier.border(2.dp, TextHi, shape).padding(2.dp)
        item.emphasis == Emphasis.MINE -> Modifier.border(6.dp, item.layer.fill.copy(alpha = 0.32f), shape).padding(6.dp)
        else -> Modifier
    }
    Box(
        Modifier.wrapContentSize(unbounded = true)
            .then(halo)
            .height(if (selected) 24.dp else 22.dp)
            .clip(shape)
            .background(if (filled) item.layer.fill else color.copy(alpha = 0.14f).compositeOver(PanelAlt))
            .border(if (selected) 2.dp else if (filled) 1.dp else 1.5.dp, if (selected) OnMapFill else if (filled) item.layer.fill else color, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            item.label, maxLines = 1,
            fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = if (selected) 11.sp else 10.sp,
            color = if (filled) OnMapFill else color,
            modifier = Modifier.padding(horizontal = 7.dp),
        )
    }
}

/** El marcador en miniatura (listas): mismo estilo que en el mapa, sin texto. */
@Composable
fun MapMarkerDot(item: MapItem, modifier: Modifier = Modifier) {
    val filled = item.emphasis != Emphasis.NONE
    Box(
        modifier.size(14.dp).clip(CircleShape)
            .background(if (filled) item.layer.fill else item.layer.color.copy(alpha = 0.16f).compositeOver(PanelAlt))
            .border(1.5.dp, if (filled) item.layer.fill else item.layer.color, CircleShape),
    )
}

/** Botón sobre el mapa (zoom, pantalla completa, atrás en pantalla completa): 42 dp. */
@Composable
fun MapButton(icon: LineIcon, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MapButtonBox(description, modifier, onClick) { LineIconView(icon, TextHi, size = 20.dp) }
}

@Composable
internal fun MapButtonBox(description: String, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Panel.copy(alpha = 0.94f))
            .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Persona sobre el mapa: miniatura de su foto (iniciales sin foto) con aro — azul si te
 * comparte, naranja de tu puesto si eres tú ("TÚ") — y una punta que marca el lugar exacto.
 * Señal vieja (>2 min) = atenuada; seleccionada = más grande y con su nombre y antigüedad encima.
 */
@Composable
fun PersonPinView(p: PersonPin, selected: Boolean, repo: AppRepository?, onClick: () -> Unit) {
    val ring = if (p.self) MapPuestoFill else Travel
    val d = if (selected) 44.dp else 34.dp
    Column(
        Modifier.alpha(if (p.stale && !selected) 0.5f else 1f).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selected && p.label != null) {
            Text(
                p.label, maxLines = 1, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = TextHi,
                modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceTop.copy(alpha = 0.96f))
                    .border(1.dp, BorderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box {
            Box(Modifier.size(d).clip(CircleShape).background(ring).padding(if (selected) 3.dp else 2.dp)) {
                if (repo != null) {
                    com.alephri.elpuesto.ui.components.RemoteAvatar(
                        repo, p.avatarUrl, p.initials, size = d - (if (selected) 6.dp else 4.dp),
                        bg = SurfaceTop, textColor = ring,
                    )
                } else {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(SurfaceTop), contentAlignment = Alignment.Center) {
                        Text(p.initials, maxLines = 1, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = ring)
                    }
                }
            }
            if (p.self) {
                Text(
                    "TÚ", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, color = OnMapFill,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(MapPuestoFill).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        // Punta: el lugar exacto está en su vértice.
        Canvas(Modifier.size(width = 10.dp, height = 6.dp)) {
            drawPath(
                Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() },
                ring,
            )
        }
    }
}
