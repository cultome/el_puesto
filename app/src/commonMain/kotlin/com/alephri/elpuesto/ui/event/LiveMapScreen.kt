package com.alephri.elpuesto.ui.event

import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.EventUi
import com.alephri.elpuesto.model.LivePosition
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.ui.map.Emphasis
import com.alephri.elpuesto.ui.map.FullscreenMapTopBar
import com.alephri.elpuesto.ui.map.FullscreenTopInset
import com.alephri.elpuesto.ui.map.LayerBar
import com.alephri.elpuesto.ui.map.MapMemory
import com.alephri.elpuesto.ui.map.TrackMap
import com.alephri.elpuesto.ui.map.trackMapItems
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import kotlinx.coroutines.flow.map

/** Envoltura para distinguir "cargando" (null) de "no hay evento activo". */
private class Loaded(val ui: EventUi?)

/**
 * "Mapa en vivo" (diseño "Ubicaciones en vivo" B): el mapa del trazado a pantalla completa
 * con quienes te comparten su ubicación, y una hoja inferior — cerrada, un vistazo con sus
 * fotos; abierta (tocar o arrastrar hacia arriba), la lista con rol y posición. Se abre
 * encuadrando a todos. Entradas: ⤢ del mapa del tab Puesto, la tarjeta del evento en el
 * Home y el aviso "X te comparte su ubicación".
 */
@Composable
fun LiveMapScreen(repo: AppRepository, onBack: () -> Unit, onOpenLocationSettings: () -> Unit) {
    val loaded by remember { repo.event().map { Loaded(it) } }.collectAsState(initial = null)
    var expanded by remember { mutableStateOf(false) }
    BackHandler { if (expanded) expanded = false else onBack() }
    val s = loaded?.ui
    var trazadoName by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(PanelAlt)) {
        when {
            loaded == null -> Unit // cargando (instantáneo: sale de la caché local)
            s == null -> Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(64.dp))
                EmptyState(LineIcon.PIN, "No hay un evento activo", "El mapa en vivo funciona durante un evento al que estés asignado.")
            }
            else -> LiveMapContent(repo, s, expanded, { expanded = it }, onOpenLocationSettings) { trazadoName = it }
        }
        // La barra de toda pantalla completa: atrás, título y salir (aquí, ambos regresan).
        FullscreenMapTopBar(onBack) {
            PulsingDot(Live)
            Text("MAPA EN VIVO", maxLines = 1, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 2.sp, color = Amber)
            Spacer(Modifier.weight(1f))
            trazadoName?.let { Text(it, maxLines = 1, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut) }
        }
    }
}

@Composable
private fun LiveMapContent(
    repo: AppRepository,
    s: EventUi,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onOpenLocationSettings: () -> Unit,
    onTrazado: (String?) -> Unit,
) {
    val a = s.assignment
    val live by rememberLivePositions(repo, s.event.id)
    val now = rememberNow()
    val own by com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.location.ownPosition.collectAsState()
    val positions = visiblePositions(live, now)
    var selected by remember { mutableStateOf<String?>(null) }
    var fitKey by remember { mutableStateOf(0) }
    // Capas y trazado: los mismos que dejaste en el tab Puesto.
    val mapKey = eventMapKey(s.event.id)
    val layers = MapMemory.layers(mapKey)
    var selectedItem by remember(s.event.id) { mutableStateOf<String?>(null) }

    // El trazado elegido en el tab Puesto (o el principal: el primero de trazadoIds) con sus
    // puestos y activos.
    var trazado by remember(s.event.id) { mutableStateOf<Trazado?>(null) }
    var puestos by remember(s.event.id) { mutableStateOf<List<Puesto>>(emptyList()) }
    var assets by remember(s.event.id) { mutableStateOf<List<TrackAsset>>(emptyList()) }
    var mapImage by remember(s.event.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var ready by remember(s.event.id) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(s.event.id, reloader.key) {
        val first = MapMemory.trazado(mapKey) ?: s.event.trazadoIds.firstOrNull() ?: s.event.trazadoId
        val t = reloader.track { repo.trazados(s.event.circuitId) }.value
            .let { all -> all.firstOrNull { it.id == first } ?: all.firstOrNull() }
        trazado = t
        onTrazado(t?.name)
        if (t != null) {
            val r = reloader.track { repo.puestos(t.id) to repo.assets(t.id) }.value
            puestos = r.first
            assets = r.second
            mapImage = t.takeIf { it.path.size < 3 }?.mapUrl?.let { url ->
                com.alephri.elpuesto.ui.components.loadImageBitmap(repo, url)
            }
        }
        ready = true
    }
    // Se abre encuadrando a todos (en cuanto hay mapa y alguien en él).
    LaunchedEffect(ready, positions.isNotEmpty()) {
        if (ready && positions.isNotEmpty() && fitKey == 0) fitKey = 1
    }

    // Tu posición va rellena y siempre visible (aunque apagues su capa).
    val items = trackMapItems(puestos, assets) { id, _ -> if (id == a.puestoId) Emphasis.MINE else Emphasis.NONE }
    val onMap = positions.filter { !liveState(it, now, trazado).outside }
    val outside = positions - onMap.toSet()

    Box(Modifier.fillMaxSize()) {
        if (ready) {
            TrackMap(
                trazado?.path ?: emptyList(), mapImage, items, layers, selectedItem, onSelectItem = { selectedItem = it },
                people = personPins(positions, own, trazado, now, myId = a.officerId),
                selectedPersonId = selected,
                modifier = Modifier.fillMaxSize(), corner = 0.dp,
                avatarRepo = repo, onSelectPerson = { selected = it }, fitKey = fitKey,
                controlsBottom = 236.dp,
                fitInsetTop = FullscreenTopInset + 44.dp, fitInsetBottom = 250.dp,
                wheelZoom = true,
            )
        }
        // Capas arriba, bajo la barra (las mismas que en la tarjeta), y el encuadre de personas.
        LayerBar(items, layers, { MapMemory.toggle(mapKey, it) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 66.dp), overlay = true)
        if (positions.isNotEmpty()) {
            LiveChip(repo, positions, Modifier.padding(start = 16.dp, top = FullscreenTopInset + 10.dp)) { fitKey++ }
        }

        // —— Hoja inferior ——
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Panel)
                .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .navigationBarsPadding()
                .animateContentSize()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            // Asa + encabezado: tocar o arrastrar abre/cierra la lista.
            Column(
                Modifier.fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy ->
                            if (dy < -6f) onExpand(true) else if (dy > 6f) onExpand(false)
                        }
                    }
                    .clickable { onExpand(!expanded) }
                    .padding(top = 10.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(BorderStrong))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        when {
                            positions.isEmpty() -> "Nadie en el mapa"
                            expanded -> "Te comparten su ubicación"
                            else -> "${onMap.size} en el mapa"
                        },
                        fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextHi,
                        modifier = Modifier.weight(1f),
                    )
                    if (expanded) {
                        Text("${positions.size}", fontFamily = PlexMonoFamily, fontSize = 12.sp, color = Amber)
                    } else if (outside.isNotEmpty()) {
                        Text("${outside.size} fuera del circuito", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
                    }
                }
            }
            when {
                positions.isEmpty() -> Text(
                    "Nadie te está compartiendo su ubicación ahora. Aparecen aquí cuando un compañero te incluye y tiene su app abierta.",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut, lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                !expanded -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    positions.forEach { p ->
                        val st = liveState(p, now, trazado)
                        val sel = p.officerId == selected
                        Column(
                            Modifier.width(70.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { selected = if (sel) null else p.officerId }.alpha(if (st.stale) 0.6f else 1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.size(50.dp).clip(CircleShape).background(if (st.outside) BorderStrong else Travel).padding(if (sel) 3.dp else 2.dp)) {
                                RemoteAvatar(
                                    repo, (p.avatarUrl ?: "/images/avatar/${p.officerId}/full").replace("/full", "/thumb"),
                                    initials(p.displayName), size = if (sel) 44.dp else 46.dp, bg = PanelAlt, textColor = Travel,
                                )
                            }
                            Text(firstAndLast(p.displayName).substringBefore(' '), maxLines = 1, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                            Text(
                                if (st.outside) "fuera" else st.ago.removePrefix("hace "), maxLines = 1,
                                fontFamily = PlexMonoFamily, fontSize = 9.5.sp, color = if (st.stale || st.outside) TextMut else Live,
                            )
                        }
                    }
                }
                else -> Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 10.dp),
                ) {
                    onMap.forEach { p -> PersonRow(repo, p, now, trazado) { selected = p.officerId; onExpand(false) } }
                    if (outside.isNotEmpty()) {
                        Text(
                            "FUERA DEL CIRCUITO", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                            letterSpacing = 1.sp, color = TextMut, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                        outside.forEach { p -> PersonRow(repo, p, now, trazado, onLocate = null) }
                    }
                    Text(
                        "Solo ves a quien decide compartirte su ubicación.",
                        fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            OwnSharingRow(repo, s.event.id, s.event.name, onOpenLocationSettings)
        }
    }
}

/** Fila de la lista abierta: foto, nombre, rol · posición, antigüedad y "Ubicar". */
@Composable
private fun PersonRow(repo: AppRepository, p: LivePosition, now: kotlinx.datetime.Instant, trazado: Trazado?, onLocate: (() -> Unit)?) {
    val st = liveState(p, now, trazado)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).alpha(if (st.stale) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (st.outside) BorderStrong else Travel).padding(2.dp)) {
            RemoteAvatar(
                repo, (p.avatarUrl ?: "/images/avatar/${p.officerId}/full").replace("/full", "/thumb"),
                initials(p.displayName), size = 40.dp, bg = PanelAlt, textColor = Travel,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(p.displayName, maxLines = 1, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            listOfNotNull(p.role, p.position).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub)
            }
            Text(
                listOfNotNull(if (st.stale) "sin señal ${st.ago}" else st.ago, st.accuracy).joinToString(" · "),
                fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = if (st.stale) TextMut else Live,
            )
        }
        if (onLocate != null) {
            Text(
                "Ubicar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Amber,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, BorderStrong, RoundedCornerShape(10.dp))
                    .clickable { onLocate() }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}
