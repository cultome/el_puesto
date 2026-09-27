package com.alephri.elpuesto.ui.event

import com.alephri.elpuesto.data.freshReads
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.EventUi
import com.alephri.elpuesto.model.AttendanceEntry
import com.alephri.elpuesto.model.Chat
import com.alephri.elpuesto.model.ChatType
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.PuestoMate
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.ui.agenda.BitacoraTimeline
import com.alephri.elpuesto.ui.chats.ChatTypeTag
import com.alephri.elpuesto.ui.chats.chatAvatarLabel
import com.alephri.elpuesto.ui.chats.chatImageUrl
import com.alephri.elpuesto.ui.chats.eventChats
import com.alephri.elpuesto.ui.chats.isGroup
import com.alephri.elpuesto.ui.circuits.TrazadoSelector
import com.alephri.elpuesto.ui.map.Emphasis
import com.alephri.elpuesto.ui.map.LayerBar
import com.alephri.elpuesto.ui.map.MapMemory
import com.alephri.elpuesto.ui.map.TrackMap
import com.alephri.elpuesto.ui.map.trackMapItems
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.AmberTag
import com.alephri.elpuesto.ui.components.BitacoraFab
import com.alephri.elpuesto.ui.components.CameraGlyph
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.Segmented
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.format.stateLabel
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.datetime.todayIn

@Composable
fun EventScreen(
    repo: AppRepository,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenActivity: (String) -> Unit,
    onOpenChat: (Chat) -> Unit,
    onOpenCircuito: (String) -> Unit = {},
    onOpenTripEditor: (tripItemId: String, eventId: String?) -> Unit = { _, _ -> },
    onAddToBitacora: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onOpenLiveMap: () -> Unit = {},
    onNewChat: (eventId: String) -> Unit = {},
) {
    val ui by repo.event().collectAsState(initial = null)
    val pending by repo.pendingSync().collectAsState(initial = 0L)
    var chats by remember { mutableStateOf<List<Chat>?>(null) }
    var bitacora by remember { mutableStateOf<List<TripItem>?>(null) }
    var attendance by remember { mutableStateOf<List<AttendanceEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    // MbM: al entrar al tab, la actividad EN CURSO se centra sola en pantalla.
    val mbmScroll = rememberScrollState()
    var mbmLiveCenterY by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pagerState.currentPage, mbmLiveCenterY != null) {
        if (pagerState.currentPage == 1) mbmLiveCenterY?.let { y ->
            mbmScroll.animateScrollTo((y - mbmScroll.viewportSize / 2f).toInt().coerceAtLeast(0))
        }
    }
    BackHandler { onBack() }
    // Abierto desde la barra fija o el aviso de cambio del MbM: ir directo a esa pestaña.
    LaunchedEffect(Unit) {
        com.alephri.elpuesto.ui.platform.AppIntents.pendingEventPage.collect { p ->
            if (p == null) return@collect
            pagerState.scrollToPage(p)
            com.alephri.elpuesto.ui.platform.AppIntents.pendingEventPage.value = null
        }
    }
    // Caché primero (Reloader.track): lo guardado sale al instante y la red lo revalida.
    val reloader = rememberReloader(repo)
    LaunchedEffect(reloader.key) { chats = reloader.track { repo.chats() }.value.filter { !it.archived } }
    LaunchedEffect(Unit) { repo.refresh() }
    // Se recarga al volver de un editor (la pantalla re-entra a composición) y al conocer el evento.
    val evId = ui?.event?.id
    LaunchedEffect(evId, reloader.key) {
        if (evId != null) {
            bitacora = reloader.track { repo.tripItems(evId) }.value
            attendance = reloader.track { repo.attendance(evId) }.value
        }
    }
    // Tiempo real: mientras el Modo evento está abierto, el backend avisa (SSE) cuando
    // cambia el MbM/checklist/asignaciones/asistencia y se resincroniza; conflate()
    // colapsa ráfagas (por eso la asistencia se recarga en cada aviso, no solo en el suyo).
    LaunchedEffect(evId) {
        if (evId != null) repo.eventChanges(evId).conflate().collect {
            repo.refresh()
            attendance = freshReads { repo.attendance(evId) }
        }
    }

    val s = ui

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            // "Modo evento" no le dice nada al oficial: el encabezado dice que está EN VIVO.
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(Live)
                Spacer(Modifier.size(10.dp))
                Text(
                    "EVENTO ACTIVO",
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = Amber,
                    letterSpacing = 2.sp,
                )
            }
            Spacer(Modifier.size(42.dp))
        }

        if (s == null) {
            // Cargando: skeleton estructural (título, tabs, tarjeta de asignación, filas).
            Column(Modifier.weight(1f).padding(horizontal = 22.dp).padding(top = 14.dp)) {
                SkeletonBox(Modifier.fillMaxWidth().height(22.dp))
                Spacer(Modifier.height(16.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(40.dp), corner = 12.dp)
                Spacer(Modifier.height(18.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(140.dp), corner = 20.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonRows(4, 56.dp)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 22.dp).padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Imagen del evento por convención (/images/event/{id}); sin foto → iniciales.
                RemoteAvatar(
                    repo, "/images/event/${s.event.id}/full", initials(s.event.name),
                    size = 34.dp, bg = Panel, textColor = Amber,
                )
                Column {
                    Text(s.event.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextHi)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Navegación conectada: el circuito lleva a su detalle.
                        Text(
                            "${s.circuit} ›", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenCircuito(s.event.circuitId) }.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Segmented(
                // "MbM" (minute by minute): el nombre corto del cronograma entre oficiales.
                listOf("Puesto", "MbM", "Chat", "Bitácora"),
                pagerState.currentPage,
                { scope.launch { pagerState.animateScrollToPage(it) } },
                Modifier.padding(horizontal = 22.dp),
            )
            Refreshable(
                onRefresh = {
                    repo.refresh()
                    chats = repo.chats().filter { !it.archived }
                    ui?.event?.id?.let {
                        bitacora = repo.tripItems(it)
                        attendance = repo.attendance(it)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val pageScroll = if (page == 1) mbmScroll else rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 22.dp)) {
                        when (page) {
                            0 -> PuestoTab(
                                repo, s, pending, attendance, onOpenProfile, onOpenLocationSettings, onOpenLiveMap,
                                puestoChat = chats?.firstOrNull { it.type == ChatType.PUESTO && (it.eventId == null || it.eventId == s.event.id) },
                                onOpenChat = onOpenChat,
                                onSetAttendance = { off, pres ->
                                    scope.launch {
                                        repo.setAttendance(s.event.id, off, pres)
                                        attendance = repo.attendance(s.event.id)
                                    }
                                },
                            ) { id, done -> scope.launch { repo.setChecklistDone(id, done) } }
                            1 -> CronogramaTab(s, onOpenActivity) { y ->
                                if (mbmLiveCenterY?.let { kotlin.math.abs(it - y) > 1f } != false) mbmLiveCenterY = y
                            }
                            2 -> ChatTab(
                                repo,
                                // Solo los de ESTE evento: el del evento, el de tu puesto y los
                                // grupos (privados/públicos) que alguien ligó a él.
                                chats?.let { eventChats(it, s.event.id) },
                                onOpenChat,
                                onNewChat = { onNewChat(s.event.id) },
                            )
                            else -> BitacoraTab(repo, bitacora, onOpenTripEditor)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                if (pagerState.currentPage == 3) {
                    BitacoraFab(
                        onClick = onAddToBitacora,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 22.dp),
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun PuestoTab(
    repo: AppRepository,
    s: EventUi,
    pending: Long,
    attendance: List<AttendanceEntry>?,
    onOpenProfile: (String) -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenLiveMap: () -> Unit,
    puestoChat: Chat?,
    onOpenChat: (Chat) -> Unit,
    onSetAttendance: (String, Boolean?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val a = s.assignment
    // Compartir ubicación: quienes me comparten (vivo) + mi propio pin mientras transmito.
    val live by rememberLivePositions(repo, s.event.id)
    val now = rememberNow()
    val own by com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.location.ownPosition.collectAsState()
    var selectedPerson by remember(s.event.id) { mutableStateOf<String?>(null) }
    val positions = visiblePositions(live, now)
    // Capas y trazado elegido: los comparte con el Mapa en vivo (su pantalla completa).
    val mapKey = eventMapKey(s.event.id)
    val layers = MapMemory.layers(mapKey)
    var selectedItem by remember(s.event.id) { mutableStateOf<String?>(null) }
    // Trazados del EVENTO (Event.trazadoIds; el primero es el principal). Selector solo
    // cuando hay más de uno; el mapa y sus puestos/activos siguen al trazado elegido.
    var trazados by remember(s.event.id) { mutableStateOf<List<Trazado>?>(null) }
    var trazado by remember(s.event.id) { mutableStateOf<Trazado?>(null) }
    var puestos by remember(s.event.id) { mutableStateOf<List<Puesto>>(emptyList()) }
    var assets by remember(s.event.id) { mutableStateOf<List<TrackAsset>>(emptyList()) }
    var mapImage by remember(s.event.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var mapReady by remember(s.event.id) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(s.event.id, s.event.trazadoIds, reloader.key) {
        val ids = s.event.trazadoIds.ifEmpty { listOf(s.event.trazadoId) }
        val all = reloader.track { repo.trazados(s.event.circuitId) }.value
        val evTrazados = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
        trazados = evTrazados
        // El mismo trazado, en su versión recién leída (una recarga puede traer cambios).
        trazado = evTrazados.firstOrNull { it.id == trazado?.id }
            ?: evTrazados.firstOrNull { it.id == MapMemory.trazado(mapKey) } ?: evTrazados.firstOrNull()
    }
    var mapFor by remember(s.event.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(trazado?.id, reloader.key) {
        val t = trazado ?: return@LaunchedEffect
        // "Cargando" solo al cambiar de trazado; una recarga del mismo se pinta encima.
        if (t.id != mapFor) mapReady = false
        val r = reloader.track { repo.puestos(t.id) to repo.assets(t.id) }.value
        puestos = r.first
        assets = r.second
        mapFor = t.id
        // El mapa oficial (mapUrl) solo aplica sin silueta dibujada (misma regla que Circuitos).
        mapImage = t.takeIf { it.path.size < 3 }?.mapUrl?.let { url ->
            com.alephri.elpuesto.ui.components.loadImageBitmap(repo, url)
        }
        mapReady = true
    }

    Spacer(Modifier.height(14.dp))
    // "Tu asignación" compacta (el mapa sube, diseño "Ubicaciones en vivo" A); lleva al
    // chat de tu puesto.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelElevA)
            .border(1.dp, BorderStrong, RoundedCornerShape(16.dp))
            .clickable(enabled = puestoChat != null) { puestoChat?.let(onOpenChat) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("TU ASIGNACIÓN", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = Amber, letterSpacing = 1.sp)
            Text(a.puestoLabel.ifBlank { "P${a.puestoNumber}" }, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi)
        }
        Column(Modifier.weight(1f)) {
            Text(a.role, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = Amber)
            a.shift?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut) }
        }
        if (puestoChat != null) {
            Text("Chat ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber)
        }
    }

    val evTrazados = trazados
    if (evTrazados == null) {
        Spacer(Modifier.height(16.dp))
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.25f), corner = 18.dp)
    } else if (evTrazados.isNotEmpty()) {
        SectionHeader(
            if (evTrazados.size > 1) "Trazados del evento" else "Mapa del trazado",
            trailing = if (evTrazados.size > 1) "${evTrazados.size}" else trazado?.name.orEmpty(),
        )
        if (evTrazados.size > 1) {
            TrazadoSelector(trazado, evTrazados, loaded = true, puestoCount = puestos.size) {
                trazado = it; selectedItem = null
                MapMemory.setTrazado(mapKey, it.id)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (mapReady) {
            // Tu posición va rellena (con halo) y siempre visible: un puesto O un activo
            // tripulado (TH/IFRT/HIAB). El mapa se abre acercado a ella.
            val items = trackMapItems(puestos, assets) { id, _ -> if (id == a.puestoId) Emphasis.MINE else Emphasis.NONE }
            var fitKey by remember { mutableStateOf(0) }
            val pins = personPins(positions, own, trazado, now, myId = a.officerId)
            Box {
                TrackMap(
                    trazado?.path ?: emptyList(), mapImage, items, layers, selectedItem, onSelectItem = { selectedItem = it },
                    people = pins, selectedPersonId = selectedPerson,
                    avatarRepo = repo, onSelectPerson = { selectedPerson = it }, fitKey = fitKey,
                    flyToId = a.puestoId, onFullscreen = onOpenLiveMap,
                )
                if (positions.isNotEmpty()) {
                    LiveChip(repo, positions, Modifier.align(Alignment.TopStart).padding(10.dp)) { fitKey++ }
                }
            }
            Spacer(Modifier.height(10.dp))
            LayerBar(items, layers, { MapMemory.toggle(mapKey, it) })
        } else {
            SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.25f), corner = 18.dp)
        }
        Spacer(Modifier.height(10.dp))
        LiveStrip(repo, positions, now, trazado, selectedPerson) { selectedPerson = it }
        if (positions.isNotEmpty()) Spacer(Modifier.height(10.dp))
        OwnSharingRow(repo, s.event.id, s.event.name, onOpenLocationSettings)
    }

    SectionHeader("Compañeros de puesto", trailing = "${s.mates.size}")
    s.mates.forEach { MateRow(repo, it, onOpenProfile) }

    // Sin plantilla de checklist para el puesto: la sección no se muestra.
    if (s.checklist.isNotEmpty()) {
        val done = s.checklist.count { it.done }
        val trailing = if (pending > 0) "$done/${s.checklist.size} · $pending sin enviar" else "$done/${s.checklist.size}"
        // "HOY <fecha>" deja claro que el checklist se reinicia a diario (medianoche CDMX,
        // la zona del cronograma — el server purga las marcas de días anteriores).
        SectionHeader("Checklist de hoy · ${fechaHoyCdmx()}", trailing = trailing)
        s.checklist.forEach { ChecklistRow(it, onToggle) }
    }

    // Al fondo: la asistencia (pase de lista del jefe o tu marca de hoy). Las ubicaciones
    // viven bajo el mapa (diseño "Ubicaciones en vivo" A).
    // —— Pase de lista de HOY: el jefe marca a su tripulación; los demás ven su marca.
    // A diferencia del checklist, la asistencia queda REGISTRADA por día en el backend.
    val isChief = s.mates.firstOrNull { it.officerId == a.officerId }?.isChief == true
    if (isChief) {
        val marked = attendance?.associate { it.officerId to it.present }
        SectionHeader(
            "Pase de lista · HOY ${fechaHoyCdmx()}",
            trailing = attendance?.let { att -> "${att.count { it.present }}/${s.mates.size} presentes" },
        )
        if (marked == null) {
            SkeletonRows(s.mates.size.coerceAtLeast(2), 56.dp)
        } else {
            s.mates.forEach { m -> AttendanceRow(repo, m, marked[m.officerId], onSetAttendance) }
            Text(
                "La asistencia de cada día queda registrada.",
                fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    } else {
        SectionHeader("Asistencia de hoy", trailing = fechaHoyCdmx())
        if (attendance == null) {
            SkeletonBox(Modifier.fillMaxWidth().height(44.dp), corner = 14.dp)
        } else {
            val mine = attendance.firstOrNull { it.officerId == a.officerId }?.present
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "La registra tu jefe de puesto",
                    fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut,
                )
                when (mine) {
                    true -> Tag("Presente", container = Live.copy(alpha = 0.14f), contentColor = Live, border = Live.copy(alpha = 0.3f))
                    false -> Tag("Ausente", container = Danger.copy(alpha = 0.14f), contentColor = Danger, border = Danger.copy(alpha = 0.3f))
                    null -> Tag("Sin marcar")
                }
            }
        }
    }
}

private val MESES_CORTO = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

private fun fechaHoyCdmx(): String {
    val hoy = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.of("America/Mexico_City"))
    return "${hoy.dayOfMonth} ${MESES_CORTO[hoy.monthNumber - 1]}"
}

@Composable
private fun MateRow(repo: AppRepository, m: PuestoMate, onOpenProfile: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenProfile(m.officerId) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Foto por convención (/images/avatar/{id}); si el oficial no tiene, caen las iniciales.
        RemoteAvatar(
            repo, "/images/avatar/${m.officerId}/full", initials(m.displayName), size = 42.dp,
            bg = if (m.isChief) Amber else Panel, textColor = if (m.isChief) OnAmber else TextPrimary,
        )
        Column(Modifier.weight(1f)) {
            Text(m.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            // El rol de SU asignación en este evento; el área del perfil solo con caché vieja.
            Text(m.role.ifBlank { if (m.isChief) "Jefe de puesto" else m.area.display() }, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        }
        if (m.isChief) AmberTag("CMP")
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

/** Fila del pase de lista (solo el jefe): ✓ presente / ✕ ausente; re-tocar desmarca. */
@Composable
private fun AttendanceRow(repo: AppRepository, m: PuestoMate, present: Boolean?, onSet: (String, Boolean?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(
            repo, "/images/avatar/${m.officerId}/full", initials(m.displayName), size = 42.dp,
            bg = if (m.isChief) Amber else Panel, textColor = if (m.isChief) OnAmber else TextPrimary,
        )
        Column(Modifier.weight(1f)) {
            Text(m.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            Text(
                when (present) {
                    true -> "Presente"
                    false -> "Ausente"
                    null -> "Sin marcar"
                },
                fontFamily = PlexSansFamily, fontSize = 12.5.sp,
                color = when (present) {
                    true -> Live
                    false -> Danger
                    null -> TextFaint
                },
            )
        }
        AttendanceChip("✓", active = present == true, color = Live) {
            onSet(m.officerId, if (present == true) null else true)
        }
        AttendanceChip("✕", active = present == false, color = Danger) {
            onSet(m.officerId, if (present == false) null else false)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun AttendanceChip(glyph: String, active: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) color else Panel)
            .border(if (active) 0.dp else 2.dp, BorderStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (active) OnAmber else TextFaint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, onToggle: (String, Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(item.id, !item.done) }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                .background(if (item.done) Amber else Panel)
                .border(if (item.done) 0.dp else 2.dp, BorderStrong, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) Text("✓", color = OnAmber, fontSize = 14.sp)
        }
        Text(
            item.text,
            fontFamily = PlexSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = if (item.done) TextFaint else TextPrimary,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun CronogramaTab(s: EventUi, onOpenActivity: (String) -> Unit, onLiveCenter: (Float) -> Unit = {}) {
    if (s.schedule.isEmpty()) { MbmEmptyState(); return }
    Spacer(Modifier.height(8.dp))
    // Lo ya ocurrido estorba la lectura: se colapsa dejando visible solo la actividad
    // previa; un toque despliega (u oculta) el resto.
    var showPast by remember { mutableStateOf(false) }
    val pastPrefix = s.schedule.takeWhile { it.status == EventStatus.FINISHED }
    val collapsed = if (pastPrefix.size > 1) pastPrefix.dropLast(1) else emptyList()
    if (collapsed.isNotEmpty()) {
        PastToggleRow(collapsed.size, showPast) { showPast = !showPast }
        if (showPast) collapsed.forEach { SessionRow(it, onOpenActivity, onLiveCenter) }
    }
    s.schedule.drop(collapsed.size).forEach { SessionRow(it, onOpenActivity, onLiveCenter) }
}

/** MbM aún sin publicar: lo captura Control y llega solo (SSE), sin que el oficial haga nada. */
@Composable
private fun MbmEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(48.dp))
                .background(Amber.copy(alpha = 0.10f).compositeOver(Panel))
                .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(48.dp)),
            contentAlignment = Alignment.Center,
        ) { LineIconView(LineIcon.CLOCK, Amber, size = 40.dp, strokeWidth = 1.8f) }
        Spacer(Modifier.height(22.dp))
        Text("Aún no hay MbM", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextHi)
        Spacer(Modifier.height(8.dp))
        Text(
            "Control publica el minuto a minuto del evento.\nAparecerá aquí en cuanto esté listo.",
            fontFamily = PlexSansFamily, fontSize = 13.5.sp, color = TextMut,
            textAlign = TextAlign.Center, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "SE ACTUALIZA EN VIVO",
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
            color = Live, letterSpacing = 1.sp,
            modifier = Modifier.clip(RoundedCornerShape(100.dp))
                .background(Live.copy(alpha = 0.10f))
                .border(1.dp, Live.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PastToggleRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Divider))
        Text(
            if (expanded) "Ocultar actividades anteriores"
            else if (count == 1) "1 actividad anterior" else "$count actividades anteriores",
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
            color = TextFaint, letterSpacing = 0.5.sp,
        )
        Text(if (expanded) "▴" else "▾", color = TextFaint, fontSize = 11.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Divider))
    }
}

@Composable
private fun SessionRow(s: Session, onOpenActivity: (String) -> Unit, onLiveCenter: (Float) -> Unit = {}) {
    val live = s.status == EventStatus.LIVE
    val done = s.status == EventStatus.FINISHED
    val liveMod = if (live) {
        // Reporta el centro de la fila EN CURSO (coords del contenido del scroll) para autocentrarla.
        Modifier.onGloballyPositioned { onLiveCenter(it.positionInParent().y + it.size.height / 2f) }
    } else Modifier
    // Lo pasado se atenúa completo para que el ojo caiga en lo que sigue.
    val dimMod = if (done) Modifier.alpha(0.55f) else Modifier
    Row(Modifier.fillMaxWidth().then(liveMod).then(dimMod).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            "${s.time}", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
            color = if (live) Live else TextSub, modifier = Modifier.padding(top = 14.dp),
        )
        Column(
            // Mismo resaltado que el admin: la actividad EN CURSO va en verde (fondo,
            // borde y nombre) para distinguirse de un vistazo.
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (live) Live.copy(alpha = 0.10f).compositeOver(Panel) else Panel)
                .border(1.dp, if (live) Live.copy(alpha = 0.55f) else Border, RoundedCornerShape(14.dp))
                .clickable { onOpenActivity(s.id) }
                .padding(13.dp),
        ) {
            Text(s.category.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = Amber, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (live) "${s.name} · EN CURSO" else s.name,
                fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = if (live) Live else if (done) TextMut else TextHi,
            )
            Spacer(Modifier.height(6.dp))
            Text(s.stateLabel(), fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (live) Live else TextMut)
        }
    }
}

@Composable
private fun BitacoraTab(
    repo: AppRepository,
    items: List<TripItem>?,
    onOpenTripEditor: (String, String?) -> Unit,
) {
    if (items == null) {
        Spacer(Modifier.height(28.dp))
        SkeletonRows(4, 56.dp)
        return
    }
    if (items.isEmpty()) {
        // Empty state protagónico: invita a estrenar la bitácora desde el FAB de cámara.
        Column(
            Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(52.dp))
                    .background(Amber.copy(alpha = 0.10f).compositeOver(Panel))
                    .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(52.dp)),
                contentAlignment = Alignment.Center,
            ) { CameraGlyph(Amber, Modifier.scale(1.7f)) }
            Spacer(Modifier.height(22.dp))
            Text("Tu bitácora está vacía", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextHi)
            Spacer(Modifier.height(8.dp))
            Text(
                "Fotos, notas y tu planeación del viaje,\nen un solo hilo cronológico.",
                fontFamily = PlexSansFamily, fontSize = 13.5.sp, color = TextMut,
                textAlign = TextAlign.Center, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "TOCA LA CÁMARA PARA EMPEZAR",
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                color = Amber, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(100.dp))
                    .background(Amber.copy(alpha = 0.10f))
                    .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Solo tú puedes verla.", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)
        }
        return
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Tu bitácora de este evento. Solo tú la ves.",
        fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
        modifier = Modifier.padding(top = 8.dp),
    )
    BitacoraTimeline(repo, items) { id, evId -> onOpenTripEditor(id, evId) }
    Spacer(Modifier.height(72.dp)) // aire para que el FAB no tape el último ítem
}

@Composable
private fun ChatTab(repo: AppRepository, chats: List<Chat>?, onOpenChat: (Chat) -> Unit, onNewChat: () -> Unit) {
    Spacer(Modifier.height(14.dp))
    if (chats == null) { SkeletonRows(2, 64.dp); return } // cargando
    if (chats.isEmpty()) {
        EmptyState(LineIcon.CHAT, "Sin chats de este evento", "El chat del evento y el de tu puesto aparecen aquí en cuanto Control publica las asignaciones.", compact = true)
    }
    chats.forEach { c ->
        Row(
            Modifier.fillMaxWidth().clickable { onOpenChat(c) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Imagen del chat por convención (los de evento usan la del EVENTO); sin foto → iniciales.
            RemoteAvatar(
                repo, chatImageUrl(c), chatAvatarLabel(c), size = 42.dp,
                bg = if (c.type == ChatType.PUESTO) Amber else Panel,
                textColor = if (c.type == ChatType.PUESTO) OnAmber else TextPrimary,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f, fill = false))
                    // Los grupos que alguien ligó al evento se distinguen de los operativos.
                    if (c.isGroup()) ChatTypeTag(c.type)
                }
                Text(
                    c.lastPreview ?: when (c.type) {
                        ChatType.PUESTO -> "Chat de tu puesto"
                        ChatType.EVENT -> "Chat del evento"
                        else -> "Aún no hay mensajes"
                    },
                    fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut, maxLines = 1,
                )
            }
            // Punto de no leídos (sin contador: con la indicación basta).
            if (c.unread > 0) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Amber))
            }
            Text("›", color = TextFaint, fontSize = 22.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
    }
    // Un chat propio para este evento (p. ej. los de la grúa, o con quienes viajas): nace
    // ligado a él, privado por defecto.
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, BorderStrong, RoundedCornerShape(14.dp))
            .clickable { onNewChat() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Nuevo chat para este evento", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Amber)
    }
    Spacer(Modifier.height(12.dp))
}

/** Punto "en vivo": núcleo fijo y un halo que se expande y se desvanece en bucle. */
@Composable
internal fun PulsingDot(color: androidx.compose.ui.graphics.Color) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulso")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween<Float>(1400, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        ),
        label = "halo",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(8.dp).scale(1f + p * 1.25f).alpha((1f - p) * 0.55f)
                .clip(RoundedCornerShape(50)).background(color),
        )
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Chip sobre el mapa: fotos apiladas + "N en vivo · Encuadrar" (ajusta el mapa a todos). */
@Composable
internal fun LiveChip(repo: AppRepository, positions: List<com.alephri.elpuesto.model.LivePosition>, modifier: Modifier = Modifier, onFit: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(17.dp)).background(com.alephri.elpuesto.ui.theme.SurfaceTop.copy(alpha = 0.94f))
            .border(1.dp, BorderStrong, RoundedCornerShape(17.dp)).clickable { onFit() }
            .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            positions.take(3).forEachIndexed { i, p ->
                Box(
                    Modifier.padding(start = (i * 14).dp).size(24.dp).clip(RoundedCornerShape(50))
                        .background(com.alephri.elpuesto.ui.theme.SurfaceTop).padding(2.dp),
                ) {
                    RemoteAvatar(
                        repo, (p.avatarUrl ?: "/images/avatar/${p.officerId}/full").replace("/full", "/thumb"),
                        initials(p.displayName), size = 20.dp, bg = Panel, textColor = com.alephri.elpuesto.ui.theme.Travel,
                    )
                }
            }
        }
        Text("${positions.size} en vivo · Encuadrar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextHi)
    }
}

/** Llave de las capas y el trazado elegido del mapa de un evento (tab Puesto ↔ Mapa en vivo). */
internal fun eventMapKey(eventId: String) = "evento:$eventId"
