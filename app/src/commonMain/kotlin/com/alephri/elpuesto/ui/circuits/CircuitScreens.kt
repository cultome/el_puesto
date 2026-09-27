package com.alephri.elpuesto.ui.circuits

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextOverflow
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.TrackSilhouette
import com.alephri.elpuesto.ui.theme.Live
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alephri.elpuesto.ui.format.initials
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AssetType
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.RemoteImageBox
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.UnavailableInline
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.SurfaceTop
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import com.alephri.elpuesto.ui.theme.screenBackground
import com.alephri.elpuesto.ui.theme.MapPuesto
import com.alephri.elpuesto.ui.map.Emphasis
import com.alephri.elpuesto.ui.map.LayerBar
import com.alephri.elpuesto.ui.map.MapItem
import com.alephri.elpuesto.ui.map.MapLayer
import com.alephri.elpuesto.ui.map.MapMarkerDot
import com.alephri.elpuesto.ui.map.MapMemory
import com.alephri.elpuesto.ui.map.TrackMap
import com.alephri.elpuesto.ui.map.TrackMapFullscreen
import com.alephri.elpuesto.ui.map.trackMapItems
import com.alephri.elpuesto.ui.map.visibleWith

// ————————————————————— Catálogo (CI-1) —————————————————————

/** Regiones del filtro del catálogo (por país del circuito). */
private enum class Region(val label: String) { TODOS("Todos"), MEXICO("México"), NORTE("EE. UU. y Canadá"), EUROPA("Europa"), RESTO("Resto") }

private val EUROPA = setOf(
    "España", "Italia", "Reino Unido", "Bélgica", "Países Bajos", "Austria", "Hungría", "Francia",
    "Alemania", "Mónaco", "Azerbaiyán", "Portugal",
)

private fun regionOf(c: Circuit): Region = when (c.country) {
    "México" -> Region.MEXICO
    "Estados Unidos", "Canadá" -> Region.NORTE
    in EUROPA -> Region.EUROPA
    else -> Region.RESTO
}

/** Carrera próxima de un circuito según la agenda (≤40 días): el chip verde de la tarjeta. */
private data class CircuitStatus(val text: String)

/**
 * Catálogo = GALERÍA de siluetas (opción A de Claude Design): tarjetas en 2 columnas con
 * el trazado principal como protagonista, el logo en la esquina, longitud/curvas y si
 * tiene carrera pronto. El sello ✓ ámbar marca dónde ya trabajaste (logros, 2026-09-25:
 * reemplaza al chip "TRABAJAS" — solo sí/no). Filtro por región y buscador; México primero.
 */
@Composable
fun CircuitCatalogScreen(repo: AppRepository, onBack: () -> Unit, onOpenCircuit: (String) -> Unit) {
    var circuits by remember { mutableStateOf<List<Circuit>?>(null) }
    // Circuitos donde trabajaste (sellos del pasaporte): la marca ✓ de la tarjeta.
    var worked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(Region.TODOS) }
    val agenda by repo.agenda().collectAsState(initial = emptyList())
    var unavailable by remember { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    // El trazado principal (silueta, km, curvas) viene en el propio circuito: una sola petición.
    val load: suspend () -> Unit = {
        val t = reloader.track { repo.circuits() to repo.achievements(null) }
        circuits = t.value.first
        worked = t.value.second?.stamps?.map { it.circuitId }?.toSet().orEmpty()
        unavailable = t.value.first.isEmpty() && t.missed
    }
    LaunchedEffect(reloader.key) { load() }

    val today = remember { kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.of("America/Mexico_City")).date }
    val status: Map<String, CircuitStatus> = remember(agenda) {
        agenda.filter { it.circuitId != null && it.kind == com.alephri.elpuesto.model.AgendaKind.EVENT }
            .mapNotNull { e ->
                val start = e.startsOn ?: return@mapNotNull null
                val end = e.endsOn ?: start
                if (end < today) null else Triple(e.circuitId!!, start, e)
            }
            .groupBy { it.first }
            .mapValues { (_, list) ->
                val next = list.minBy { it.second }
                if (today.daysUntil(next.second) <= 40) {
                    CircuitStatus(listOfNotNull(next.third.championshipName, shortDate(next.second)).joinToString(" · "))
                } else null
            }
            .filterValues { it != null }.mapValues { it.value!! }
    }

    if (unavailable) {
        UnavailableScreen(repo, "Circuitos", onBack, reloader::retry)
        return
    }
    val all = circuits
    val matches = all?.filter {
        query.isBlank() || it.name.contains(query, true) || it.location.contains(query, true) || it.country.orEmpty().contains(query, true)
    }
    val filtered = matches?.filter { region == Region.TODOS || regionOf(it) == region }?.sortedBy { it.name }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CIRCUITOS", onBack)
        Refreshable(onRefresh = load, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 22.dp)) {
                AppTextField(value = query, onValueChange = { query = it }, placeholder = "Buscar circuito, ciudad o país…")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Region.entries.forEach { r ->
                    val n = matches?.count { r == Region.TODOS || regionOf(it) == r }
                    RegionChip(r.label, n, selected = r == region) { region = r }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (filtered == null) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) { SkeletonRows(3, 210.dp, corner = 18.dp) }
            } else if (all.isNullOrEmpty()) {
                EmptyState(LineIcon.PIN, "Aún no hay circuitos", "Cuando se carguen circuitos al catálogo, aparecerán aquí.", Modifier.padding(horizontal = 22.dp))
            } else if (filtered.isEmpty()) {
                EmptyState(
                    LineIcon.SEARCH, "Sin resultados",
                    if (query.isNotBlank()) "Ningún circuito coincide con “${query.trim()}”. Prueba con otro nombre, ciudad o país."
                    else "No hay circuitos en esta región.",
                    Modifier.padding(horizontal = 22.dp), compact = true,
                )
            } else if (region == Region.TODOS) {
                // México primero (donde trabajan los oficiales), luego el resto del mundo.
                val (mx, mundo) = filtered.partition { it.country == "México" }
                if (mx.isNotEmpty()) GallerySection("MÉXICO · ${mx.size}", mx, repo, status, worked, onOpenCircuit)
                if (mundo.isNotEmpty()) GallerySection("RESTO DEL MUNDO · ${mundo.size}", mundo, repo, status, worked, onOpenCircuit)
            } else {
                GallerySection("${region.label.uppercase()} · ${filtered.size}", filtered, repo, status, worked, onOpenCircuit)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

private fun shortDate(d: kotlinx.datetime.LocalDate): String =
    "${d.dayOfMonth} ${listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")[d.monthNumber - 1]}"

@Composable
private fun RegionChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(100.dp)
    Row(
        Modifier.height(36.dp).clip(shape).background(if (selected) Amber else Panel)
            .border(1.dp, if (selected) Amber else BorderStrong, shape).clickable { onClick() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (selected) OnAmber else TextPrimary)
        count?.let { Text("$it", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = if (selected) OnAmber else TextFaint) }
    }
}

@Composable
private fun GallerySection(
    title: String,
    list: List<Circuit>,
    repo: AppRepository,
    status: Map<String, CircuitStatus>,
    worked: Set<String>,
    onOpenCircuit: (String) -> Unit,
) {
    val workedHere = list.count { it.id in worked }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Amber, letterSpacing = 1.4.sp)
        if (workedHere > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.alephri.elpuesto.ui.achievements.WorkedMark(14.dp, ring = androidx.compose.ui.graphics.Color.Transparent)
                Text("TRABAJASTE EN $workedHere", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = TextSub, letterSpacing = 1.sp)
            }
        }
    }
    Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.chunked(2).forEach { pair ->
            // Misma altura por fila aunque un nombre ocupe dos renglones.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { c ->
                    CircuitCard(repo, c, status[c.id], c.id in worked, Modifier.weight(1f).fillMaxHeight()) { onOpenCircuit(c.id) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CircuitCard(repo: AppRepository, c: Circuit, status: CircuitStatus?, worked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val main = c.mainTrazado
    val drawn = (main?.path?.size ?: 0) >= 3
    val logoUrl = "/images/circuit/${c.id}/full"
    Column(
        modifier.clip(shape).background(Panel).border(1.dp, Border, shape).clickable { onClick() }
            .semantics(mergeDescendants = true) { if (worked) contentDescription = "${c.name}. Trabajaste aquí" },
    ) {
        Box(Modifier.fillMaxWidth().height(118.dp).background(PanelAlt), contentAlignment = Alignment.Center) {
            if (drawn) {
                TrackSilhouette(
                    main!!.path, Modifier.size(104.dp), color = Amber, width = 2.dp,
                    under = BorderStrong, underWidth = 6.dp,
                )
                // Logo chico en la esquina (sin logo, la silueta basta).
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    RemoteImageBox(repo, logoUrl, size = 30.dp, shape = RoundedCornerShape(8.dp)) {}
                }
            } else {
                // Sin dibujo todavía (callejeros por trazar): el logo al centro, o una bandera.
                RemoteImageBox(repo, logoUrl, size = 64.dp, shape = RoundedCornerShape(14.dp)) {
                    LineIconView(LineIcon.FLAG, TextFaint, size = 34.dp)
                }
            }
            if (c.trazadoCount > 1) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { SmallTag("${c.trazadoCount} TRAZADOS", TextSub) }
            }
            if (worked) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { com.alephri.elpuesto.ui.achievements.WorkedMark(24.dp) }
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.location, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            main?.let {
                Text("${fmtKm(it.lengthM)} km · ${it.curves} curvas", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextSub)
            }
            status?.let {
                Spacer(Modifier.height(3.dp))
                SmallTag(it.text, Live)
            }
        }
    }
}

@Composable
private fun SmallTag(text: String, color: Color) {
    val shape = RoundedCornerShape(100.dp)
    Text(
        text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.6.sp, color = color,
        maxLines = 1,
        modifier = Modifier.clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ————————————————————— Detalle (CI-2 / CI-3) —————————————————————

@Composable
fun CircuitDetailScreen(repo: AppRepository, circuitId: String, onBack: () -> Unit, onOpenPassport: () -> Unit = {}) {
    val online by repo.online.collectAsState()
    var circuit by remember(circuitId) { mutableStateOf<Circuit?>(null) }
    var trazados by remember(circuitId) { mutableStateOf<List<Trazado>>(emptyList()) }
    var trazado by remember(circuitId) { mutableStateOf<Trazado?>(null) }
    var puestos by remember { mutableStateOf<List<Puesto>>(emptyList()) }
    var assets by remember { mutableStateOf<List<TrackAsset>>(emptyList()) }
    // Capas visibles: compartidas con la pantalla completa (y entre circuitos) mientras la app vive.
    val layers = MapMemory.layers(LAYERS_KEY)
    var selectedId by remember { mutableStateOf<String?>(null) }
    // "Ubicar" en la lista: acerca el mapa a ese marcador (cada toque vuelve a volar). Tocarlo
    // en el mapa solo lo elige: no mueve el mapa.
    var flyTo by remember { mutableStateOf<String?>(null) }
    var flyKey by remember { mutableStateOf(0) }
    var fullscreen by remember(circuitId) { mutableStateOf(false) }
    var trazadosLoaded by remember(circuitId) { mutableStateOf(false) }
    // Sello del pasaporte de este circuito (null = no has trabajado aquí): "Tu historia aquí".
    var stamp by remember(circuitId) { mutableStateOf<com.alephri.elpuesto.model.PassportStamp?>(null) }
    var contentReady by remember { mutableStateOf(false) }
    // Sin red ni caché: todo el circuito (pantalla) o solo el trazado elegido (en línea).
    var unavailable by remember(circuitId) { mutableStateOf(false) }
    var contentMissing by remember { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }

    LaunchedEffect(circuitId, reloader.key) {
        unavailable = false
        val t = reloader.track {
            Triple(repo.circuits().firstOrNull { it.id == circuitId }, repo.trazados(circuitId), repo.achievements(null))
        }
        circuit = t.value.first
        trazados = t.value.second
        stamp = t.value.third?.stamps?.firstOrNull { it.circuitId == circuitId }
        // El mismo trazado, pero la versión recién leída (una recarga puede traer cambios).
        trazado = trazados.firstOrNull { it.id == trazado?.id } ?: trazados.firstOrNull()
        trazadosLoaded = true
        unavailable = t.missed && (circuit == null || trazados.isEmpty())
    }
    // Mapa oficial del trazado (mapUrl, kind `trazado`): fallback cuando NO hay silueta
    // dibujada (path). Con dibujo, la silueta manda: el path y los pins comparten la
    // referencia OSM y una foto arbitraria no alinearía.
    var mapImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var contentFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trazado?.id, reloader.key) {
        val t = trazado
        // Solo al CAMBIAR de trazado se vuelve a "cargando" y se suelta la selección; una
        // recarga del mismo (volvió la señal, el servidor trajo cambios) se pinta encima.
        val switched = t?.id != contentFor
        if (switched) { contentReady = false; selectedId = null; flyTo = null }
        contentMissing = false
        if (t == null) { puestos = emptyList(); assets = emptyList() } else {
            val r = reloader.track { repo.puestos(t.id) to repo.assets(t.id) }
            puestos = r.value.first; assets = r.value.second
            if (selectedId != null && (puestos.none { it.id == selectedId } && assets.none { it.id == selectedId })) selectedId = null
            contentMissing = r.missed && puestos.isEmpty() && assets.isEmpty()
        }
        contentFor = t?.id
        mapImage = t?.takeIf { it.path.size < 3 }?.mapUrl?.let { url ->
            com.alephri.elpuesto.ui.components.loadImageBitmap(repo, url)
        }
        contentReady = true
    }

    // "Asignado antes" (derivado de tu historial) va relleno: lo especial de este mapa.
    val items = remember(puestos, assets) {
        trackMapItems(puestos, assets) { _, before -> if (before) Emphasis.BEFORE else Emphasis.NONE }
    }
    val visible = items.filter { it.visibleWith(layers) }
    fun locate(id: String) { selectedId = id; flyTo = id; flyKey++ }

    if (unavailable) {
        UnavailableScreen(repo, "Circuito", onBack, reloader::retry)
        return
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CIRCUITO", onBack)
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Logo del circuito si existe; sin logo no ocupa espacio.
                RemoteImageBox(repo, "/images/circuit/$circuitId/full", size = 44.dp, shape = RoundedCornerShape(12.dp)) {}
                Column {
                    Text(circuit?.name ?: "—", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextHi)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        circuit?.location.orEmpty() + if (!online) " · Sin conexión" else "",
                        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (trazadosLoaded && trazados.isEmpty()) {
                // Sedes de calendarios internacionales: aún sin trazados ni puestos capturados.
                EmptyState(
                    LineIcon.PIN, "Aún no hay trazados",
                    "Este circuito todavía no tiene trazados ni puestos capturados.", compact = true,
                )
                return@Column
            }
            if (trazadosLoaded) {
                TrazadoSelector(trazado, trazados, trazadosLoaded, puestos.size) { trazado = it }
            } else {
                SkeletonBox(Modifier.fillMaxWidth().height(76.dp), corner = 14.dp)
            }
            Spacer(Modifier.height(14.dp))
            // contentReady llega antes que los trazados cuando aún no hay trazado elegido:
            // sin trazadosLoaded se vería "Sin mapa" un instante.
            if (trazadosLoaded && contentReady && contentMissing) {
                UnavailableInline(repo, reloader::retry)
            } else if (trazadosLoaded && contentReady) {
                TrackMap(
                    trazado?.path ?: emptyList(), mapImage, items, layers, selectedId, onSelectItem = { selectedId = it },
                    flyToId = flyTo, flyKey = flyKey,
                    onFullscreen = { fullscreen = true },
                )
                Spacer(Modifier.height(10.dp))
                LayerBar(items, layers, { MapMemory.toggle(LAYERS_KEY, it) })
            } else {
                SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.25f), corner = 18.dp)
                Spacer(Modifier.height(10.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(52.dp), corner = 12.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Refreshable(
            onRefresh = {
                circuit = repo.circuits().firstOrNull { it.id == circuitId }
                trazados = repo.trazados(circuitId)
                stamp = repo.achievements(null)?.stamps?.firstOrNull { it.circuitId == circuitId }
                // El mismo trazado, pero la versión recién leída (una recarga puede traer cambios).
        trazado = trazados.firstOrNull { it.id == trazado?.id } ?: trazados.firstOrNull()
                val t = trazado
                if (t == null) { puestos = emptyList(); assets = emptyList() } else {
                    puestos = repo.puestos(t.id); assets = repo.assets(t.id)
                }
            },
            modifier = Modifier.weight(1f),
        ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (trazadosLoaded && trazados.isEmpty()) {
                // Sin trazados: el aviso de arriba basta.
            } else if (trazadosLoaded && contentReady && contentMissing) {
                // Sin red ni caché del trazado: el aviso de arriba basta.
            } else if (trazadosLoaded && contentReady) {
                stamp?.let {
                    // "Asignado antes" ya viene derivado por puesto: aquí solo se cuenta.
                    com.alephri.elpuesto.ui.achievements.CircuitHistoryCard(
                        it, known = puestos.count { p -> p.assignedBefore }, total = puestos.size,
                        trazadoName = trazado?.name, onOpenPassport = onOpenPassport,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (items.isEmpty()) {
                    EmptyState(LineIcon.PIN, "Sin puestos ni activos", "Este trazado todavía no tiene posiciones capturadas.", compact = true)
                } else if (visible.isEmpty()) {
                    Text("Todas las capas están apagadas: prende una arriba para verlas.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.padding(top = 8.dp))
                } else {
                    visible.forEach { ItemRow(it, selected = it.id == selectedId) { locate(it.id) } }
                }
            } else {
                SkeletonRows(4, 48.dp)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if (fullscreen) {
        TrackMapFullscreen(
            kicker = trazado?.name.orEmpty(), title = circuit?.name.orEmpty(),
            trazadoPath = trazado?.path ?: emptyList(), mapImage = mapImage, items = items,
            layers = layers, onToggleLayer = { MapMemory.toggle(LAYERS_KEY, it) },
            selectedId = selectedId, onSelectItem = { selectedId = it },
            // Al volver, la tarjeta se centra en lo elegido en la pantalla completa.
            onClose = { fullscreen = false; selectedId?.let { flyTo = it; flyKey++ } },
        )
    }
    }
}

private const val LAYERS_KEY = "circuitos"

@Composable
internal fun TrazadoSelector(trazado: Trazado?, trazados: List<Trazado>, loaded: Boolean, puestoCount: Int, onSelect: (Trazado) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelElevA)
                .border(1.dp, BorderStrong, RoundedCornerShape(14.dp)).clickable(enabled = trazados.size > 1) { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("TRAZADO", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = Amber, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Text(trazado?.name ?: if (loaded) "Sin configuraciones" else "…", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi)
                trazado?.let {
                    Text(
                        "${fmtKm(it.lengthM)} km · ${it.curves} curvas · $puestoCount puestos",
                        fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextSub,
                    )
                }
            }
            if (trazados.size > 1) Text(if (expanded) "▲" else "▼", color = Amber, fontSize = 12.sp)
        }
        if (expanded && trazados.size > 1) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(14.dp)),
            ) {
                Text(
                    "Elegir trazado · ${trazados.size} configuraciones",
                    fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = TextMut, letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
                trazados.forEachIndexed { i, t ->
                    val on = t.id == trazado?.id
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(t); expanded = false }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Amber else TextPrimary)
                            Text("${fmtKm(t.lengthM)} km · ${t.curves} curvas", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
                        }
                        if (on) Text("✓", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (i < trazados.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: MapItem, selected: Boolean, onLocate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onLocate() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MapMarkerDot(item)
        Column(Modifier.weight(1f)) {
            Text(
                if (item.layer == MapLayer.PUESTOS) "Puesto ${item.label}" else item.label,
                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary,
            )
            when {
                item.emphasis == Emphasis.BEFORE -> Text("Asignado antes", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = MapPuesto)
                item.layer != MapLayer.PUESTOS -> Text("${item.typeName} · ${item.layer.label}", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
            }
        }
        Text(
            if (selected) "Ubicado ✓" else "Ubicar", color = if (selected) Amber else TextSub,
            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Text(
            title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
            color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(34.dp))
    }
}

// La longitud viaja en metros (entero); en la UI se muestra como km ("4.304").
private fun fmtKm(meters: Int): String =
    "${meters / 1000}.${(meters % 1000).toString().padStart(3, '0')}"
