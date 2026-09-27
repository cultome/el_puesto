package com.alephri.elpuesto.ui.agenda

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AchievementRules
import com.alephri.elpuesto.model.EventRegistration
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.NewPuestoProposal
import com.alephri.elpuesto.model.OperationalRoles
import com.alephri.elpuesto.model.RegistrationState
import com.alephri.elpuesto.model.SetParticipationRequest
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.ui.map.Emphasis
import com.alephri.elpuesto.ui.map.LayerBar
import com.alephri.elpuesto.ui.map.MapItem
import com.alephri.elpuesto.ui.map.MapLayer
import com.alephri.elpuesto.ui.map.MapMemory
import com.alephri.elpuesto.ui.map.TrackMap
import com.alephri.elpuesto.ui.map.TrackMapFullscreen
import com.alephri.elpuesto.ui.map.layer
import com.alephri.elpuesto.ui.circuits.TrazadoSelector
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.loadImageBitmap
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** Posición elegible de un trazado del evento (puesto o activo). */
private data class Position(val id: String, val label: String, val trazadoId: String, val item: MapItem?)

private const val NO_POSITION = ""

/** positionId especial: "mi puesto no aparece" → lo propone (en revisión). */
private const val PROPOSE = "__propose__"

/** Id del pin del puesto propuesto en el mapa. */
private const val NEW_PIN = "__new__"

/**
 * Registro por HONOR ("yo trabajé este evento"; decisiones 2026-09-25): el oficial
 * declara su participación en un evento con autoregistro permitido. Primero el aviso del
 * sistema de honor (solo al registrarse por primera vez) y luego tres preguntas: posición
 * (mapa del trazado o lista; "no aparece / sin puesto fijo"), rol (catálogo por familia,
 * incluidos los de jefe) y días. Funciona sin señal: se encola y se envía sola. Nunca da
 * permisos: Modo evento, chats y emergencia salen solo del roster de la organización.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegisterParticipationScreen(repo: AppRepository, eventId: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val toast = com.alephri.elpuesto.ui.platform.rememberToast()
    val scope = rememberCoroutineScope()
    val reloader = rememberReloader(repo)
    var reg by remember(eventId) { mutableStateOf<EventRegistration?>(null) }
    var missed by remember(eventId) { mutableStateOf(false) }
    var pending by remember(eventId) { mutableStateOf(false) }
    // Trazados del evento con sus posiciones (null = cargando).
    var trazados by remember(eventId) { mutableStateOf<List<Trazado>?>(null) }
    var positions by remember(eventId) { mutableStateOf<Map<String, List<Position>>>(emptyMap()) }
    var mapImages by remember(eventId) { mutableStateOf<Map<String, ImageBitmap?>>(emptyMap()) }
    LaunchedEffect(eventId, reloader.key) {
        val r = reloader.track { repo.registration(eventId) }
        missed = r.missed
        val loaded = r.value ?: return@LaunchedEffect
        reg = loaded
        pending = repo.participationPending(eventId)
        val tz = reloader.track { repo.trazados(loaded.circuitId) }.value
            .filter { it.id in loaded.trazadoIds }
            .sortedBy { loaded.trazadoIds.indexOf(it.id) }
        positions = tz.associate { t ->
            val (ps, assets) = reloader.track { repo.puestos(t.id) to repo.assets(t.id) }.value
            t.id to (
                ps.map { p ->
                    val label = p.label ?: "Puesto ${p.number}"
                    Position(p.id, label, t.id, if (p.onMap) MapItem(p.id, p.label ?: "${p.number}", p.point, MapLayer.PUESTOS, "Puesto") else null)
                } + assets.map { a ->
                    Position(a.id, a.label, t.id, MapItem(a.id, a.label, a.point, a.type.layer(), a.type.display()))
                }
                )
        }
        // El mapa oficial (mapUrl) solo aplica sin silueta dibujada (misma regla que Circuitos).
        mapImages = tz.associate { t -> t.id to t.takeIf { it.path.size < 3 }?.mapUrl?.let { loadImageBitmap(repo, it) } }
        trazados = tz
    }

    // —— Formulario (se llena UNA vez desde el registro existente; una recarga no lo pisa).
    // Como los demás editores: `remember`, no saveable — el SaveableStateHolder del shell
    // conserva el estado de las pantallas cerradas y al reabrir revivirían cambios sin guardar. ——
    var filledFor by remember(eventId) { mutableStateOf<String?>(null) }
    var accepted by remember(eventId) { mutableStateOf(false) }
    var positionId by remember(eventId) { mutableStateOf<String?>(null) } // NO_POSITION = sin puesto
    var role by remember(eventId) { mutableStateOf<String?>(null) }
    var days by remember(eventId) { mutableStateOf<List<String>>(emptyList()) }
    var trazadoId by remember(eventId) { mutableStateOf<String?>(null) }
    var query by remember(eventId) { mutableStateOf("") }
    // Puesto propuesto ("no aparece"): número/nombre y dónde estaba (punto del trazado actual).
    var proposalLabel by remember(eventId) { mutableStateOf("") }
    var proposalPoint by remember(eventId) { mutableStateOf<MapPoint?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Mapa: capas (compartidas con la pantalla completa), pantalla completa y "volar" a la
    // posición elegida con los chips (tocarla en el mapa no lo mueve).
    val layers = MapMemory.layers(LAYERS_KEY)
    var mapFullscreen by remember(eventId) { mutableStateOf(false) }
    var flyTo by remember(eventId) { mutableStateOf<String?>(null) }
    var flyKey by remember(eventId) { mutableStateOf(0) }

    val r = reg
    val allDays = r?.let { eventDays(it.opensOn, it.endsOn ?: it.opensOn) }.orEmpty()
    LaunchedEffect(r?.eventId, trazados) {
        val reg0 = r ?: return@LaunchedEffect
        val tz = trazados ?: return@LaunchedEffect
        if (filledFor == reg0.eventId) return@LaunchedEffect
        val mine = reg0.mine
        accepted = mine != null
        role = mine?.role
        val proposal = mine?.proposal
        positionId = when {
            mine == null -> null
            proposal != null -> PROPOSE
            else -> mine.positionId ?: NO_POSITION
        }
        proposalLabel = proposal?.label.orEmpty()
        proposalPoint = proposal?.point
        days = (mine?.days?.takeIf { it.isNotEmpty() } ?: allDays).map { it.toString() }
        flyTo = mine?.positionId
        trazadoId = proposal?.trazadoId?.takeIf { id -> tz.any { it.id == id } }
            ?: mine?.positionId?.let { pid -> positions.entries.firstOrNull { e -> e.value.any { it.id == pid } }?.key }
            ?: tz.firstOrNull()?.id
        filledFor = reg0.eventId
    }

    val blocked = r != null && r.state != RegistrationState.OPEN
    val current = trazados?.let { tz -> tz.firstOrNull { it.id == trazadoId } ?: tz.firstOrNull() }
    val list = current?.let { positions[it.id] }.orEmpty()
    val proposing = positionId == PROPOSE
    // Ubicar el puesto propuesto exige el marco geográfico del trazado (dibujo).
    val canLocate = current?.geoFrame != null
    // La posición elegida va rellena (es "tu puesto" de este registro) y siempre visible.
    val onMap = list.mapNotNull { p -> p.item?.copy(emphasis = if (p.id == positionId) Emphasis.MINE else Emphasis.NONE) } +
        listOfNotNull(
            proposalPoint?.takeIf { proposing }?.let { pt ->
                MapItem(NEW_PIN, proposalLabel.trim().ifBlank { "Tu puesto" }, pt, MapLayer.PUESTOS, "Puesto", Emphasis.MINE)
            },
        )
    val mapSelected = if (proposing) NEW_PIN else positionId
    val mapHint = when {
        blocked -> null
        proposing && canLocate -> "Toca en el mapa dónde estaba tu puesto"
        proposing -> null
        else -> "Toca tu puesto en el mapa"
    }
    val onSelectMapItem: ((String?) -> Unit)? =
        if (blocked) null else ({ id -> if (id != null && id != NEW_PIN) { positionId = id; error = null } })
    val onTapMap: ((MapPoint) -> Unit)? =
        if (!blocked && proposing && canLocate) ({ pt -> proposalPoint = pt; error = null }) else null
    val chosen = list.firstOrNull { it.id == positionId } ?: positions.values.flatten().firstOrNull { it.id == positionId }
    val chosenLabel = when {
        positionId == NO_POSITION -> "Sin puesto fijo"
        proposing -> proposalLabel.trim().ifBlank { null }?.let { "$it · en revisión" } ?: "Tu puesto nuevo"
        chosen != null -> chosen.label
        positionId != null -> r?.mine?.positionLabel?.ifBlank { null } ?: "Puesto elegido"
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                if (r?.mine != null) "TU PARTICIPACIÓN" else "REGISTRO POR HONOR",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }
        if (r == null) {
            if (missed) {
                UnavailableScreen(repo, "Registro por honor", onBack = null) { reloader.retry() }
            } else {
                Column(Modifier.padding(22.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth().height(70.dp), corner = 18.dp)
                    Spacer(Modifier.height(16.dp))
                    SkeletonRows(4, 56.dp)
                }
            }
            return@Column
        }
        // Scroll NO saveable: al reabrir el formulario empieza arriba (ver nota del formulario).
        val scroll = remember(eventId) { ScrollState(0) }
        Column(Modifier.verticalScroll(scroll).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(r.eventName, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 26.sp, color = TextHi)
            Text(rangeLabel(r.opensOn, r.endsOn), fontFamily = PlexMonoFamily, fontSize = 12.sp, color = TextMut, modifier = Modifier.padding(top = 4.dp))

            when {
                blocked && r.mine == null -> {
                    BlockedState(r)
                    return@Column
                }
                !accepted -> {
                    HonorPledge(onAccept = { accepted = true }, onCancel = onBack)
                    return@Column
                }
            }

            if (blocked) {
                // Ya registrado, pero el evento se cerró (o llegó el roster): solo quitar.
                Note(
                    if (r.state == RegistrationState.ROSTERED) "La organización ya te incluyó en su roster: tu asignación manda y este registro no cuenta."
                    else "La organización cerró el autoregistro de este evento: tu registro se conserva, pero ya no se puede editar.",
                )
            }

            // —— 1. Posición ——
            SectionHeader("¿En qué puesto estuviste?")
            val tz = trazados
            if (tz == null) {
                SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.25f), corner = 18.dp)
            } else {
                if (tz.size > 1) {
                    TrazadoSelector(current, tz, loaded = true, puestoCount = list.size) {
                        trazadoId = it.id; query = ""; proposalPoint = null
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (current != null && (onMap.isNotEmpty() || current.path.size >= 3 || mapImages[current.id] != null)) {
                    TrackMap(
                        trazadoPath = current.path, mapImage = mapImages[current.id], items = onMap, layers = layers,
                        selectedId = mapSelected, onSelectItem = onSelectMapItem, hint = mapHint, onTapMap = onTapMap,
                        flyToId = flyTo, flyKey = flyKey, onFullscreen = { mapFullscreen = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    LayerBar(onMap, layers, { MapMemory.toggle(LAYERS_KEY, it) })
                    Spacer(Modifier.height(12.dp))
                }
                Selected(chosenLabel)
                if (list.isEmpty()) {
                    Note("Este trazado aún no tiene puestos cargados: elige \"Mi puesto no aparece\" para agregar el tuyo, o regístrate sin puesto fijo.")
                } else if (!blocked && !proposing) {
                    Spacer(Modifier.height(10.dp))
                    AppTextField(query, { query = it }, "Buscar puesto (p. ej. 7 o TH3)")
                }
                if (!blocked) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Mi puesto no aparece", proposing) { positionId = PROPOSE; error = null }
                        Choice("Sin puesto fijo", positionId == NO_POSITION) { positionId = NO_POSITION; error = null }
                    }
                }
                if (!blocked && proposing) {
                    Spacer(Modifier.height(12.dp))
                    AppTextField(proposalLabel, { proposalLabel = it.take(20); error = null }, "Número o nombre de tu puesto (p. ej. 7)")
                    Note(
                        when {
                            !canLocate -> "Este trazado aún no tiene mapa dibujado: tu puesto se propone solo con su número."
                            proposalPoint == null -> "Toca en el mapa dónde estaba. Si no lo recuerdas, basta con el número."
                            else -> "Ubicado en el mapa. Toca otro lugar para moverlo."
                        } + " Queda en revisión: solo tú lo ves hasta que se verifique y se publique en el mapa para todos.",
                    )
                }
                if (!blocked && !proposing && list.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val q = query.trim().lowercase()
                    val shown = list.filter { q.isEmpty() || it.label.lowercase().contains(q) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.take(MAX_CHIPS).forEach { p ->
                            Choice(p.label, p.id == positionId) { positionId = p.id; error = null; flyTo = p.id; flyKey++ }
                        }
                    }
                    if (shown.size > MAX_CHIPS) {
                        Text(
                            "${shown.size - MAX_CHIPS} más: busca por su nombre o tócalo en el mapa.",
                            fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // —— 2. Rol ——
            SectionHeader("¿Qué rol desempeñaste?")
            val byFamily = OperationalRoles.ALL.groupBy { AchievementRules.roleFamily(it) ?: "Otros" }
            (AchievementRules.ROLE_FAMILIES + "Otros").filter { it in byFamily }.forEach { fam ->
                Text(
                    fam.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp,
                    letterSpacing = 1.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    byFamily.getValue(fam).forEach { rl ->
                        Choice(rl, rl == role, enabled = !blocked) { role = rl; error = null }
                    }
                }
            }

            // —— 3. Días ——
            if (allDays.size > 1) {
                SectionHeader("¿Qué días estuviste?", trailing = "${days.size} de ${allDays.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allDays.forEach { d ->
                        val on = d.toString() in days
                        DayChip(d, on, Modifier.weight(1f), enabled = !blocked) {
                            days = if (on) days - d.toString() else (days + d.toString()).sorted()
                            error = null
                        }
                    }
                }
            }

            if (pending) {
                Note("Sin enviar: tu registro se enviará solo al volver la señal.")
            }
            error?.let {
                Text(it, fontFamily = PlexSansFamily, fontSize = 13.sp, color = DangerHi, modifier = Modifier.padding(top = 14.dp))
            }
            Spacer(Modifier.height(20.dp))
            if (!blocked) {
                val ready = role != null && positionId != null && (allDays.size <= 1 || days.isNotEmpty()) &&
                    (positionId != PROPOSE || proposalLabel.isNotBlank())
                PrimaryButton(
                    when {
                        saving -> "Guardando…"
                        r.mine != null -> "Guardar cambios"
                        else -> "Registrar mi participación"
                    },
                    enabled = ready && !saving,
                    onClick = {
                        val chosenRole = role ?: return@PrimaryButton
                        val sendDays = if (days.size == allDays.size) emptyList() else days.map(LocalDate::parse)
                        // Propuesta: el punto del mapa se guarda como lat/lon absolutas (el
                        // marco del trazado es la misma proyección que usa el servidor).
                        val tzNow = trazados?.let { list -> list.firstOrNull { it.id == trazadoId } ?: list.firstOrNull() }
                        val proposal = if (positionId == PROPOSE && tzNow != null) {
                            val ll = proposalPoint?.let { pt -> tzNow.geoFrame?.unproject(pt) }
                            NewPuestoProposal(tzNow.id, proposalLabel.trim(), ll?.first, ll?.second)
                        } else null
                        val pid = positionId?.takeIf { it != NO_POSITION && it != PROPOSE }
                        val label = proposal?.label ?: positions.values.flatten().firstOrNull { it.id == pid }?.label.orEmpty()
                        saving = true
                        scope.launch {
                            val rejected = repo.setParticipation(
                                eventId, SetParticipationRequest(chosenRole, pid, sendDays, proposal), label,
                                proposalPoint = proposalPoint.takeIf { proposal?.lat != null },
                            )
                            saving = false
                            if (rejected != null) {
                                error = rejected.replaceFirstChar { it.uppercase() }
                            } else {
                                val queued = repo.participationPending(eventId)
                                toast(if (queued) "Sin señal: tu registro se enviará solo." else "Participación registrada")
                                onBack()
                            }
                        }
                    },
                )
                if (!ready) {
                    Text(
                        if (positionId == PROPOSE && proposalLabel.isBlank()) "Escribe el número o nombre de tu puesto."
                        else "Elige tu puesto (o \"sin puesto fijo\") y tu rol.",
                        fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (r.mine != null) {
                Text(
                    "Quitar mi registro",
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DangerHi,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !saving) { confirmDelete = true }.padding(10.dp),
                )
            }
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
                LineIconView(LineIcon.SHIELD, TextFaint, 12.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Registrarte no da acceso al Modo evento, a los chats del evento ni a datos de emergencia: eso sale del roster de la organización.",
                    fontFamily = PlexSansFamily, fontSize = 11.5.sp, lineHeight = 15.sp, color = TextFaint,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    // El mapa a pantalla completa, ENCIMA del formulario (que sigue vivo debajo): elegir o
    // colocar tu puesto con más espacio; "Listo" regresa.
    // Al volver, la tarjeta se centra en lo elegido en la pantalla completa.
    val closeFullscreen: () -> Unit = {
        mapFullscreen = false
        if (mapSelected != null) { flyTo = mapSelected; flyKey++ }
    }
    if (mapFullscreen && r != null && current != null) {
        TrackMapFullscreen(
            kicker = current.name, title = r.eventName,
            trazadoPath = current.path, mapImage = mapImages[current.id], items = onMap,
            layers = layers, onToggleLayer = { MapMemory.toggle(LAYERS_KEY, it) },
            selectedId = mapSelected, onSelectItem = onSelectMapItem, onClose = closeFullscreen,
            hint = mapHint, onTapMap = onTapMap, bottomInset = 76.dp,
        ) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp, BorderStrong, RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chosenLabel ?: "Toca tu puesto", maxLines = 1, modifier = Modifier.weight(1f),
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (chosenLabel != null) TextHi else TextMut,
                )
                Text(
                    "Listo", fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnAmber,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Amber).clickable { closeFullscreen() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Panel,
            title = { Text("¿Quitar tu registro?", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
            text = {
                Text(
                    "Este evento dejará de contar en tu historial, tu pasaporte y tus logros. Puedes volver a registrarte después.",
                    fontFamily = PlexSansFamily, color = TextSub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    scope.launch {
                        val rejected = repo.deleteParticipation(eventId)
                        saving = false
                        if (rejected != null) error = rejected else onBack()
                    }
                }) { Text("Quitar", color = DangerHi, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar", color = TextSub, fontFamily = PlexSansFamily) }
            },
        )
    }
}

private const val MAX_CHIPS = 36
private const val LAYERS_KEY = "registro"

/** Aviso del sistema de honor: se acepta una vez, antes de la primera captura. */
@Composable
private fun HonorPledge(onAccept: () -> Unit, onCancel: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PanelElevA).border(1.dp, Amber.copy(alpha = 0.35f), shape).padding(20.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Amber.copy(alpha = 0.12f)).border(1.dp, Amber.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { LineIconView(LineIcon.SHIELD, Amber, 24.dp) }
        Spacer(Modifier.height(14.dp))
        Text("SISTEMA DE HONOR", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.1.sp, color = Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Confiamos en tu palabra",
            fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi,
        )
        Spacer(Modifier.height(10.dp))
        Bullet("Tú registras que trabajaste este evento; nadie lo revisa antes de que cuente.")
        Bullet("Cuenta en tu historial, tu pasaporte y tus logros, y tus compañeros lo ven cuando coinciden contigo.")
        Bullet("Registra solo lo que trabajaste, con el puesto y el rol que de verdad desempeñaste.")
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Trabajé este evento · Continuar", onClick = onAccept)
    Text(
        "Ahora no",
        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextSub, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { onCancel() }.padding(10.dp),
    )
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        LineIconView(LineIcon.CHECK, Amber, 14.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = PlexSansFamily, fontSize = 13.5.sp, lineHeight = 18.sp, color = TextPrimary)
    }
}

/** El registro no está disponible para este oficial (roster / cerrado / aún no abre). */
@Composable
private fun BlockedState(r: EventRegistration) {
    when (r.state) {
        RegistrationState.ROSTERED -> EmptyState(
            LineIcon.CLIPBOARD_CHECK, "Ya estás registrado",
            "La organización te incluyó en el roster de este evento: tu participación ya cuenta en tu historial.",
        )
        RegistrationState.CLOSED -> EmptyState(
            LineIcon.LOCK, "Lo registra la organización",
            "En este evento la participación sale del roster de la organización; no hay autoregistro.",
        )
        else -> EmptyState(
            LineIcon.CALENDAR, "Aún no abre",
            "Podrás registrar tu participación desde el ${longDay(r.opensOn)}, primer día del evento.",
        )
    }
}

@Composable
private fun Selected(label: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("TU PUESTO", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, letterSpacing = 1.sp, color = TextMut)
        Spacer(Modifier.width(10.dp))
        Text(
            label ?: "Sin elegir", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
            color = if (label != null) Amber else TextFaint,
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(100.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Amber else Panel)
            .border(1.dp, if (selected) Amber else BorderStrong, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = if (selected) OnAmber else if (enabled) TextPrimary else TextMut,
        )
    }
}

@Composable
private fun DayChip(d: LocalDate, on: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(if (on) Amber else Panel).border(1.dp, if (on) Amber else Border, shape)
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(DIAS[d.dayOfWeek.isoDayNumber - 1], fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = if (on) OnAmber else TextMut)
        Text("${d.dayOfMonth}", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = if (on) OnAmber else TextPrimary)
        Text(MESES[d.monthNumber - 1], fontFamily = PlexSansFamily, fontSize = 10.sp, color = if (on) OnAmber.copy(alpha = 0.8f) else TextFaint)
    }
}

@Composable
private fun Note(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LineIconView(LineIcon.PIN, TextMut, 14.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 17.sp, color = TextSub)
    }
}

private fun eventDays(start: LocalDate, end: LocalDate): List<LocalDate> =
    generateSequence(start) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= end }.toList()

private val DIAS = listOf("LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM")
private val DIAS_LARGOS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

private fun longDay(d: LocalDate) = "${DIAS_LARGOS[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth} ${MESES[d.monthNumber - 1]}"

private fun rangeLabel(start: LocalDate, end: LocalDate?): String {
    val e = end ?: start
    return when {
        e == start -> "${start.dayOfMonth} ${MESES[start.monthNumber - 1]} ${start.year}"
        e.monthNumber == start.monthNumber -> "${start.dayOfMonth}–${e.dayOfMonth} ${MESES[e.monthNumber - 1]} ${e.year}"
        else -> "${start.dayOfMonth} ${MESES[start.monthNumber - 1]} – ${e.dayOfMonth} ${MESES[e.monthNumber - 1]} ${e.year}"
    }
}
