package com.alephri.elpuesto.ui.agenda

import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AgendaAssignment
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.AgendaRace
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.components.TopBarIconButton
import com.alephri.elpuesto.ui.format.categoryShort
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.format.isRace
import com.alephri.elpuesto.ui.format.seriesTag
import com.alephri.elpuesto.ui.format.typeTitle
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Vínculo de la planeación: a un evento que trabajas o a una carrera del calendario. */
data class TripLink(val eventId: String? = null, val roundId: String? = null)

/**
 * Detalle de una entrada de agenda — diseño C "Tu fin de semana" (Claude Design):
 * - Evento que trabajas / carrera del calendario: encabezado con la serie y la fecha N de
 *   M, tira de días, tu asignación y la línea de tiempo por día con las carreras y TU
 *   planeación, notas y fotos (lo ligado al evento o a sus carreras).
 * - Planeación personal (transporte/hospedaje/recordatorio): la misma línea de tiempo del
 *   viaje con el elemento desplegado (detalle, llegada/salida, editar/eliminar).
 * La entrada se relee de la agenda viva: tras editar, la pantalla se actualiza sola.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaEntryDetailScreen(
    repo: AppRepository,
    entry: AgendaEntry,
    onBack: () -> Unit,
    onOpenEditor: (tripItemId: String?, link: TripLink, kind: TripItemKind?) -> Unit = { _, _, _ -> },
    onAddNote: (TripLink) -> Unit = {},
    onAddPhoto: (TripLink) -> Unit = {},
    onOpenEntry: (AgendaEntry) -> Unit = {},
    onOpenConvocatoria: (String) -> Unit = {},
    onOpenCircuit: (String) -> Unit = {},
    onOpenChampionship: (String) -> Unit = {},
    onOpenEvent: () -> Unit = {},
    onOpenChat: (com.alephri.elpuesto.model.Chat) -> Unit = {},
    onRegister: (eventId: String) -> Unit = {},
) {
    BackHandler { onBack() }
    val agenda by repo.agenda().collectAsState(initial = null)
    // Registrarse (o quitar el registro) cambia la entrada: el fin de semana `rnd-…` se
    // funde en `evt-…` (y al revés). Se sigue al MISMO evento por su id de registro.
    val regEvent = entry.registrationEventId ?: entry.eventId
    val current = agenda?.firstOrNull { it.id == entry.id }
        ?: regEvent?.let { id -> agenda?.firstOrNull { it.kind == AgendaKind.EVENT && (it.registrationEventId == id || it.eventId == id) } }
        ?: entry
    var mine by remember { mutableStateOf<List<TripItem>?>(null) }
    // La planeación cambia → la agenda se resincroniza → se releen los ítems (caché
    // primero: lo guardado sale al instante y la red lo revalida).
    val reloader = rememberReloader(repo)
    LaunchedEffect(agenda, reloader.key) { mine = reloader.track { repo.myTripItems() }.value }
    // Una planeación eliminada (aquí o en el editor) ya no está en la agenda: cerrar.
    LaunchedEffect(agenda) {
        val a = agenda
        if (entry.personal && a != null && a.none { it.id == entry.id }) onBack()
    }

    val ctx: AgendaEntry? = when {
        current.kind == AgendaKind.EVENT -> current
        current.eventId != null -> agenda?.firstOrNull { it.kind == AgendaKind.EVENT && it.eventId == current.eventId }
        current.roundId != null -> agenda?.firstOrNull { e ->
            e.kind == AgendaKind.EVENT && (e.roundId == current.roundId || e.races.any { it.roundId == current.roundId })
        }
        else -> null
    }
    val focused = if (current.personal) mine?.firstOrNull { it.id == current.id } else null

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                current.typeTitle(), fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            if (focused != null) {
                TopBarIconButton("✎") { onOpenEditor(focused.id, TripLink(focused.eventId, focused.roundId), null) }
            } else {
                Spacer(Modifier.size(42.dp))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            when {
                current.kind == AgendaKind.CONVOCATORIA -> ConvocatoriaBody(current, onOpenConvocatoria)
                current.kind == AgendaKind.EVENT -> EventBody(
                    repo, current, mine?.let { linkedTo(current, it) }, agenda.orEmpty(),
                    onOpenEditor, onAddNote, onAddPhoto, onOpenEntry, onOpenCircuit, onOpenChampionship,
                    onOpenEvent, onOpenChat, onRegister,
                )
                else -> PersonalBody(
                    repo, current, focused, mine, ctx, agenda.orEmpty(),
                    onBack, onOpenEditor, onOpenEntry, onOpenConvocatoria,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ——— Evento que trabajas / carrera del calendario ———

@Composable
private fun EventBody(
    repo: AppRepository,
    ctx: AgendaEntry,
    items: List<TripItem>?,
    agenda: List<AgendaEntry>,
    onOpenEditor: (String?, TripLink, TripItemKind?) -> Unit,
    onAddNote: (TripLink) -> Unit,
    onAddPhoto: (TripLink) -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
    onOpenCircuit: (String) -> Unit,
    onOpenChampionship: (String) -> Unit,
    onOpenEvent: () -> Unit,
    onOpenChat: (com.alephri.elpuesto.model.Chat) -> Unit,
    onRegister: (String) -> Unit,
) {
    val link = linkOf(ctx)
    // Registro por honor: la participación que DECLARÉ (no la del roster) es mía para
    // editar; se lee aparte (caché primero, con lo pendiente de enviar superpuesto) para
    // verla aunque la agenda aún no se resincronice (sin señal).
    val regId = ctx.registrationEventId
    val declared = ctx.assignment?.declared == true
    var registration by remember(regId) { mutableStateOf<com.alephri.elpuesto.model.EventRegistration?>(null) }
    var regPending by remember(regId) { mutableStateOf(false) }
    val regReloader = rememberReloader(repo)
    LaunchedEffect(regId, ctx.assigned, regReloader.key) {
        if (regId == null || (ctx.assigned && !declared)) return@LaunchedEffect
        registration = regReloader.track { repo.registration(regId) }.value
        regPending = repo.participationPending(regId)
    }
    // Evento que TRABAJAS: el título lleva al Modo evento y "Tu asignación" al chat de tu
    // puesto. Ambos existen solo mientras el evento está EN CURSO (activo); antes, un
    // aviso explica cuándo se habilitan.
    val toast = com.alephri.elpuesto.ui.platform.rememberToast()
    val activeUi by repo.event().collectAsState(initial = null)
    val isActive = ctx.assigned && ctx.eventId != null && activeUi?.event?.id == ctx.eventId
    var puestoChat by remember(ctx.eventId) { mutableStateOf<com.alephri.elpuesto.model.Chat?>(null) }
    val chatsReloader = rememberReloader(repo)
    LaunchedEffect(ctx.eventId, isActive, chatsReloader.key) {
        puestoChat = if (!isActive) null else runCatching { chatsReloader.track { repo.chats() }.value }.getOrNull()
            ?.firstOrNull { it.type == com.alephri.elpuesto.model.ChatType.PUESTO && it.eventId == ctx.eventId && !it.archived }
    }
    fun notYet(what: String) =
        toast("$what se abre cuando el evento esté en curso.")
    // El Modo evento y el chat de puesto salen del ROSTER: un registro por honor no los abre.
    val onTitle: (() -> Unit)? = if (!ctx.assigned || ctx.eventId == null || declared) null
    else ({ if (isActive) onOpenEvent() else notYet("El Modo evento") })
    HeaderCard(repo, ctx, onOpenCircuit, onTitle, titleLinked = isActive)
    DayStrip(ctx, items.orEmpty(), focusDay = null)
    val mineDeclared = registration?.mine
    when {
        regId != null && (declared || (!ctx.assigned && mineDeclared != null)) -> DeclaredCard(
            // Puesto propuesto (en revisión): su etiqueta, marcada.
            position = mineDeclared?.proposal?.label ?: mineDeclared?.positionLabel ?: ctx.assignment?.position.orEmpty(),
            role = mineDeclared?.role ?: ctx.assignment?.role.orEmpty(),
            mates = ctx.assignment?.mates ?: 0,
            pending = regPending,
            positionPending = mineDeclared?.proposal != null || (mineDeclared == null && ctx.assignment?.positionPending == true),
        ) { onRegister(regId) }
        ctx.assignment != null -> AssignmentCard(ctx.assignment!!, chatLinked = puestoChat != null) {
            puestoChat?.let(onOpenChat) ?: notYet("El chat de tu puesto")
        }
        regId != null -> RegistrationCard(registration?.state ?: ctx.registration) { onRegister(regId) }
    }
    SectionHeader(if (ctx.assigned || !items.isNullOrEmpty()) "Tu fin de semana" else "El fin de semana")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip("Nota", LineIcon.NOTE, Amber, Modifier.weight(1f)) { onAddNote(link) }
        ActionChip("Foto", LineIcon.CAMERA, Amber, Modifier.weight(1f)) { onAddPhoto(link) }
        ActionChip("Planear", LineIcon.PLUS, Travel, Modifier.weight(1f)) { onOpenEditor(null, link, null) }
    }
    Spacer(Modifier.height(14.dp))
    if (items == null) {
        SkeletonRows(3, 56.dp)
    } else {
        Timeline(repo, ctx, items, focus = null, agenda, onOpenEditor, onOpenEntry, onDeleted = {})
    }
    if (ctx.isRace && ctx.circuitId != null) {
        Spacer(Modifier.height(8.dp))
        CircuitCard(ctx) { onOpenCircuit(ctx.circuitId!!) }
    }
    ctx.championshipId?.let { id ->
        Spacer(Modifier.height(10.dp))
        ChampionshipCard(ctx) { onOpenChampionship(id) }
    }
    Privacy("Solo tú ves tu planeación, notas y fotos.")
}

// ——— Planeación personal ———

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonalBody(
    repo: AppRepository,
    current: AgendaEntry,
    focused: TripItem?,
    mine: List<TripItem>?,
    ctx: AgendaEntry?,
    agenda: List<AgendaEntry>,
    onBack: () -> Unit,
    onOpenEditor: (String?, TripLink, TripItemKind?) -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
    onOpenConvocatoria: (String) -> Unit,
) {
    if (mine == null || focused == null) {
        SkeletonBox(Modifier.fillMaxWidth().height(84.dp), corner = 18.dp)
        Spacer(Modifier.height(14.dp))
        SkeletonRows(3, 56.dp)
        return
    }
    when {
        ctx != null -> {
            ContextLinkCard(ctx) { onOpenEntry(ctx) }
            DayStrip(ctx, linkedTo(ctx, mine), focusDay = focused.at?.let(::dayOf))
            SectionHeader("Tu fin de semana")
            Timeline(repo, ctx, linkedTo(ctx, mine), focus = focused.id, agenda, onOpenEditor, onOpenEntry, onDeleted = onBack)
        }
        else -> {
            current.convocatoriaId?.let { id ->
                LinkCard("CONVOCATORIA", Live, "Ver convocatoria", "Detalles y postulación", LineIcon.FLAG) { onOpenConvocatoria(id) }
                Spacer(Modifier.height(16.dp))
            }
            FocusCard(repo, focused, onOpenEditor, onDeleted = onBack)
        }
    }
    Privacy("Solo tú ves tu planeación.")
}

@Composable
private fun ConvocatoriaBody(entry: AgendaEntry, onOpenConvocatoria: (String) -> Unit) {
    Kicker("CONVOCATORIA", Live)
    Spacer(Modifier.height(6.dp))
    Text(entry.title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 27.sp, color = TextHi)
    entry.location?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextSub, modifier = Modifier.padding(top = 4.dp)) }
    entry.at?.let { Text(longDate(dayOf(it)), fontFamily = PlexMonoFamily, fontSize = 12.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp)) }
    Spacer(Modifier.height(18.dp))
    entry.convocatoriaId?.let { id ->
        LinkCard("CONVOCATORIA", Live, entry.title, "Detalles y postulación", LineIcon.FLAG) { onOpenConvocatoria(id) }
    } ?: Text(
        "Consulta los detalles y postulación en la sección de Convocatorias.",
        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
    )
}

// ——— Encabezado ———

@Composable
private fun HeaderCard(
    repo: AppRepository,
    ctx: AgendaEntry,
    onOpenCircuit: (String) -> Unit,
    onTitle: (() -> Unit)? = null,
    titleLinked: Boolean = false,
) {
    // Silueta del trazado (si el circuito tiene uno dibujado) como motivo del encabezado.
    var path by remember(ctx.circuitId) { mutableStateOf<List<MapPoint>?>(null) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(ctx.circuitId, reloader.key) {
        path = ctx.circuitId?.let { id -> reloader.track { repo.trazados(id) }.value.firstOrNull { it.path.size >= 3 }?.path }
    }
    val main = ctx.races.firstOrNull { it.main }
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape)) {
        path?.let { pts ->
            Canvas(Modifier.align(Alignment.TopEnd).offset(x = 22.dp, y = (-4).dp).size(150.dp)) {
                val p = Path()
                pts.forEachIndexed { i, pt ->
                    val o = Offset(pt.x * size.width, pt.y * size.height)
                    if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
                }
                p.close()
                drawPath(p, PanelElevA, style = Stroke(width = 12f, join = StrokeJoin.Round))
                drawPath(p, Amber.copy(alpha = 0.55f), style = Stroke(width = 3f, join = StrokeJoin.Round))
            }
        }
        Column(Modifier.padding(18.dp).padding(end = if (path != null) 96.dp else 0.dp)) {
            Kicker(headerKicker(ctx, main), Amber)
            Spacer(Modifier.height(6.dp))
            Text(
                (if (ctx.isRace) main?.name ?: ctx.title else ctx.title) + if (titleLinked) " ›" else "",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 27.sp, color = TextHi,
                modifier = onTitle?.let { Modifier.clickable { it() } } ?: Modifier,
            )
            ctx.location?.let { loc ->
                val m = ctx.circuitId?.let { id -> Modifier.clickable { onOpenCircuit(id) } } ?: Modifier
                Text(
                    if (ctx.circuitId != null) "$loc ›" else loc,
                    fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextSub, modifier = m.padding(top = 6.dp),
                )
            }
            relativeLabel(ctx)?.let { (text, color) ->
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ctx.assigned) Tag("Trabajas este evento", container = Amber.copy(alpha = 0.13f), contentColor = Amber, border = Amber.copy(alpha = 0.3f))
                    Tag(text, container = color.copy(alpha = 0.13f), contentColor = color, border = color.copy(alpha = 0.3f))
                }
            }
        }
    }
}

private fun headerKicker(ctx: AgendaEntry, main: AgendaRace?): String {
    if (main == null) return if (ctx.assigned) "TRABAJAS ESTE EVENTO" else "EVENTO"
    val series = ctx.championshipName.orEmpty()
    val cat = if (main.categoryName == ctx.championshipName || categoryShort(main.categoryName).equals(series, true)) null else main.categoryName
    return listOfNotNull(series.ifBlank { null }, cat, "FECHA ${main.number} DE ${main.total}").joinToString(" · ").uppercase()
}

/** "En 36 días" / "Mañana" / "En curso" / "Terminó" para el encabezado. */
private fun relativeLabel(ctx: AgendaEntry): Pair<String, Color>? {
    val start = ctx.startsOn ?: ctx.at?.let(::dayOf) ?: return null
    val end = ctx.endsOn ?: start
    val today = today()
    val days = today.daysUntil(start)
    return when {
        today in start..end -> "En curso" to Live
        days == 1 -> "Mañana" to Live
        days > 1 -> "En $days días" to Live
        else -> "Terminó" to TextMut
    }
}

// ——— Tira de días ———

@Composable
private fun DayStrip(ctx: AgendaEntry, items: List<TripItem>, focusDay: LocalDate?) {
    val (start, end) = range(ctx) ?: return
    // Días de viaje pegados al evento (±3) amplían la tira; lo lejano solo va en la línea de tiempo.
    val itemDays = items.flatMap { listOfNotNull(it.at?.let(::dayOf), it.endsAt?.let(::dayOf)) }
        .filter { near(it, start, end) }
    val (from, to) = (listOf(start, end) + itemDays).let { it.min() to it.max() }
    val days = generateSequence(from) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= to }.toList()
    val main = ctx.races.firstOrNull { it.main }?.date
    val today = today()
    val hl = focusDay?.takeIf { it in days } ?: today.takeIf { it in days } ?: main?.takeIf { it in days } ?: start
    val singleCategory = ctx.races.map { it.categoryName }.distinct().size <= 1
    val scroll = days.size > 6
    Spacer(Modifier.height(12.dp))
    Row(
        (if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { d ->
            val races = ctx.races.filter { it.date == d }
            val tag = when {
                d < start -> "Viaje"
                d > end -> "Regreso"
                races.isNotEmpty() -> if (singleCategory) "Carrera" else races.map { categoryShort(it.categoryName) }.distinct()
                    .let { if (it.size == 1) it[0] else "${it.size} carreras" }
                d == start -> "Inicio"
                else -> ""
            }
            val on = d == hl
            val cellShape = RoundedCornerShape(14.dp)
            Column(
                (if (scroll) Modifier.width(56.dp) else Modifier.weight(1f))
                    .clip(cellShape)
                    .background(if (on) Amber else Panel)
                    .border(1.dp, if (on) Amber else if (d == today) Live.copy(alpha = 0.6f) else Border, cellShape)
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(DIAS_CORTOS[d.dayOfWeek.isoDayNumber - 1], fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = if (on) OnAmber else TextMut)
                Text("${d.dayOfMonth}", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = if (on) OnAmber else TextPrimary)
                Text(
                    tag.ifBlank { " " }, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1,
                    color = when {
                        on -> OnAmber.copy(alpha = 0.8f)
                        d < start || d > end -> Travel
                        else -> TextFaint
                    },
                )
            }
        }
    }
}

@Composable
private fun AssignmentCard(a: AgendaAssignment, chatLinked: Boolean = false, onClick: () -> Unit = {}) {
    Spacer(Modifier.height(12.dp))
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PanelElevA).border(1.dp, BorderStrong, shape)
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Kicker("TU ASIGNACIÓN", Amber)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(a.position, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = TextHi)
            Text(a.role, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Amber, modifier = Modifier.padding(bottom = 5.dp))
        }
        val mates = when (a.mates) { 0 -> null; 1 -> "1 compañero de puesto"; else -> "${a.mates} compañeros de puesto" }
        Text(listOfNotNull(a.shift.ifBlank { null }, mates).joinToString(" · "), fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub)
        if (chatLinked) {
            Text("Chat de tu puesto ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Mi participación declarada por honor: se edita (o se quita) desde aquí. */
@Composable
private fun DeclaredCard(position: String, role: String, mates: Int, pending: Boolean, positionPending: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PanelElevA).border(1.dp, BorderStrong, shape)
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Kicker("TU PARTICIPACIÓN · REGISTRO POR HONOR", Amber)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                position.ifBlank { "Sin puesto" }, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = if (position.isBlank()) 22.sp else 28.sp, color = TextHi,
            )
            Text(role, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Amber, modifier = Modifier.padding(bottom = 5.dp))
        }
        val mateLine = when (mates) { 0 -> null; 1 -> "1 compañero en tu puesto"; else -> "$mates compañeros en tu puesto" }
        listOfNotNull(
            if (pending) "Sin enviar: se enviará solo al volver la señal" else null,
            if (positionPending) "Puesto propuesto: en revisión (solo tú lo ves)" else null,
            mateLine,
        ).forEach {
            Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub)
        }
        Text("Editar mi registro ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Evento con registro por honor donde aún no me registro (o que la organización registra). */
@Composable
private fun RegistrationCard(state: com.alephri.elpuesto.model.RegistrationState?, onRegister: () -> Unit) {
    val open = state == com.alephri.elpuesto.model.RegistrationState.OPEN
    val (title, body) = when (state) {
        com.alephri.elpuesto.model.RegistrationState.OPEN ->
            "¿Trabajaste este evento?" to "Regístralo para tu historial, tu pasaporte y tus logros."
        com.alephri.elpuesto.model.RegistrationState.NOT_YET ->
            "Registro por honor" to "Si trabajas este evento, podrás registrar tu participación desde su primer día."
        com.alephri.elpuesto.model.RegistrationState.CLOSED ->
            "Lo registra la organización" to "La participación de este evento sale del roster de la organización."
        else -> return
    }
    Spacer(Modifier.height(12.dp))
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (open) PanelElevA else Panel)
            .border(1.dp, if (open) Amber.copy(alpha = 0.45f) else Border, shape)
            .then(if (open) Modifier.clickable { onRegister() } else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(if (state == com.alephri.elpuesto.model.RegistrationState.CLOSED) LineIcon.LOCK else LineIcon.SHIELD, if (open) Amber else TextMut, 40)
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextHi)
            Text(body, fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp, color = TextSub, modifier = Modifier.padding(top = 2.dp))
            if (open) {
                Text("Registrar mi participación ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// ——— Línea de tiempo por día ———

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Timeline(
    repo: AppRepository,
    ctx: AgendaEntry,
    items: List<TripItem>,
    focus: String?,
    agenda: List<AgendaEntry>,
    onOpenEditor: (String?, TripLink, TripItemKind?) -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
    onDeleted: () -> Unit,
) {
    val (start, end) = range(ctx) ?: (null to null)
    val today = today()
    val dated = items.filter { it.at != null }.sortedBy { it.at }
    val byDay = dated.groupBy { dayOf(it.at!!) }
    val baseDays = if (start != null && end != null) {
        generateSequence(start) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= end }.toList()
    } else emptyList()
    // Noches cubiertas por un hospedaje: la línea de ese día se pinta como estancia.
    val lodgings = items.filter { it.kind == TripItemKind.LODGING && it.at != null && it.endsAt != null }
    val stays = lodgings.map { dayOf(it.at!!) to dayOf(it.endsAt!!) }
    // La salida del hospedaje se marca en SU día (fila tenue, no es otro elemento).
    val checkouts = lodgings.filter { dayOf(it.endsAt!!) != dayOf(it.at!!) }.groupBy { dayOf(it.endsAt!!) }
    val days = (baseDays + byDay.keys + checkouts.keys).distinct().sorted()
    val mainDay = ctx.races.firstOrNull { it.main }?.date
    val blocks = days.mapNotNull { d ->
        val races = ctx.races.filter { it.date == d }
        val dayItems = byDay[d].orEmpty()
        val inBase = start != null && end != null && d in start..end
        val empty = races.isEmpty() && dayItems.isEmpty() && checkouts[d].isNullOrEmpty()
        // Carrera que no trabajas: los días sin nada se omiten (salvo el inicio del fin de semana).
        if (empty && !ctx.assigned && d != start) return@mapNotNull null
        Triple(d, races, dayItems).takeIf { inBase || !empty }
    }
    if (blocks.isEmpty() && dated.size == items.size) {
        Text("Aún no hay nada aquí.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.padding(vertical = 8.dp))
    }
    blocks.forEachIndexed { i, (d, races, dayItems) ->
        val last = i == blocks.lastIndex && items.none { it.at == null }
        val close = start != null && end != null && near(d, start, end)
        val suffix = when {
            d == today -> "HOY"
            close && d < start!! -> "Viaje"
            close && d > end!! -> "Regreso"
            d == mainDay -> "Carrera"
            d == start && races.isEmpty() -> "Inicio"
            else -> null
        }
        val dot = when {
            dayItems.any { it.id == focus } -> kindColor(dayItems.first { it.id == focus }.kind)
            d == today -> Live
            close && (d < start!! || d > end!!) -> Travel
            d == mainDay -> Amber
            else -> BorderStrong
        }
        val stay = stays.any { (a, b) -> d >= a && d < b }
        // El mes solo cuando el día cae en otro mes que el evento ("Jueves 24 sep").
        val month = if (start == null || d.monthNumber != start.monthNumber) " ${MESES[d.monthNumber - 1]}" else ""
        DayBlock("${DIAS_LARGOS[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth}$month" + (suffix?.let { " · $it" } ?: ""), dot, last, stay) {
            races.forEach { RaceRow(it) }
            checkouts[d].orEmpty().forEach { CheckoutRow(it) }
            dayItems.forEach { item ->
                if (item.id == focus) FocusCard(repo, item, onOpenEditor, onDeleted)
                else ItemRow(repo, item, onOpen = { openItem(item, agenda, onOpenEditor, onOpenEntry) })
            }
            if (races.isEmpty() && dayItems.isEmpty() && checkouts[d].isNullOrEmpty()) {
                Text(
                    if (ctx.assigned) "Aquí aparecerán tus notas y fotos del día" else "Inicio del fin de semana",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                )
            }
        }
    }
    val undated = items.filter { it.at == null }
    if (undated.isNotEmpty()) {
        DayBlock("Sin fecha", BorderStrong, last = true, stay = false) {
            undated.forEach { item ->
                if (item.id == focus) FocusCard(repo, item, onOpenEditor, onDeleted)
                else ItemRow(repo, item, onOpen = { openItem(item, agenda, onOpenEditor, onOpenEntry) })
            }
        }
    }
}

/** Planeación → su detalle (entrada de agenda); fotos y notas → su editor. */
private fun openItem(
    item: TripItem,
    agenda: List<AgendaEntry>,
    onOpenEditor: (String?, TripLink, TripItemKind?) -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
) {
    val entry = agenda.firstOrNull { it.id == item.id }
    if (entry != null && item.kind != TripItemKind.PHOTO && item.kind != TripItemKind.NOTE) onOpenEntry(entry)
    else if (item.personal) onOpenEditor(item.id, TripLink(item.eventId, item.roundId), null)
}

@Composable
private fun DayBlock(title: String, dot: Color, last: Boolean, stay: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(14.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(dot))
            if (!last) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.width(if (stay) 4.dp else 2.dp).weight(1f).clip(RoundedCornerShape(2.dp))
                        .background(if (stay) Live.copy(alpha = 0.55f) else Border),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            content()
        }
    }
}

@Composable
private fun RaceRow(r: AgendaRace) {
    val color = if (r.main) Amber else TextSub
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(LineIcon.FLAG, color, 30)
        Column(Modifier.weight(1f)) {
            Text(r.categoryName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
            Text(
                listOfNotNull("FECHA ${r.number} DE ${r.total}", r.name).joinToString(" · "),
                fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextMut,
            )
        }
    }
}

/** Salida de un hospedaje (en el día de check-out): fila tenue que cierra la estancia. */
@Composable
private fun CheckoutRow(item: TripItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LineIconView(LineIcon.BED, Live.copy(alpha = 0.7f), 15.dp)
        Text(
            "Salida · ${item.title}" + (item.endsAt?.let { " · ${hhmm(it)}" } ?: ""),
            fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub,
        )
    }
}

@Composable
private fun ItemRow(repo: AppRepository, item: TripItem, onOpen: () -> Unit) {
    when (item.kind) {
        TripItemKind.PHOTO -> Box(Modifier.clickable { onOpen() }) { PhotoCard(repo, item) }
        TripItemKind.NOTE -> Box(Modifier.clickable { onOpen() }) { NoteCard(item) }
        else -> {
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape).clickable { onOpen() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconTile(kindIcon(item.kind), kindColor(item.kind), 30)
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
                    Text(timeLine(item), fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextMut)
                }
                LineIconView(LineIcon.CHEVRON, TextFaint, 16.dp, 2.2f)
            }
        }
    }
}

/** El elemento seleccionado, desplegado: detalle, llegada/salida y acciones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusCard(
    repo: AppRepository,
    item: TripItem,
    onOpenEditor: (String?, TripLink, TripItemKind?) -> Unit,
    onDeleted: () -> Unit,
) {
    val color = kindColor(item.kind)
    val openMaps = com.alephri.elpuesto.ui.platform.rememberMapsOpener()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(item.id) { delay(250); bring.bringIntoView() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(shape).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(kindIcon(item.kind), color, 36)
            Column(Modifier.weight(1f)) {
                Text(item.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi)
                Text(item.at?.let { "${hhmm(it)} · ${shortDay(dayOf(it))}" } ?: "Sin fecha", fontFamily = PlexMonoFamily, fontSize = 11.5.sp, color = color)
            }
        }
        endLine(item)?.let { Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary) }
        if (item.kind == TripItemKind.REMINDER && item.at != null) {
            Text("Te avisamos con una notificación a esa hora.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub)
        }
        item.detail?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 13.sp, lineHeight = 19.sp, color = TextSub) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.kind == TripItemKind.LODGING) {
                SmallAction("Mapas", LineIcon.EXTERNAL, Live) {
                    val q = item.detail?.ifBlank { null } ?: item.title
                    openMaps(q)
                }
            }
            SmallAction("Editar", LineIcon.PENCIL, Amber) { onOpenEditor(item.id, TripLink(item.eventId, item.roundId), null) }
            SmallAction("Eliminar", LineIcon.TRASH, Danger, textColor = Danger) { confirm = true }
        }
        error?.let { Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Panel,
            title = { Text("¿Eliminar “${item.title}”?", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
            text = { Text("Se quita de tu agenda y de la línea de tiempo del viaje.", fontFamily = PlexSansFamily, color = TextSub) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        if (repo.deleteTripItem(item)) onDeleted()
                        else error = "No se pudo eliminar. Revisa tu conexión e intenta de nuevo."
                    }
                }) { Text("Eliminar", color = Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar", color = TextSub) } },
        )
    }
}

// ——— Tarjetas de navegación ———

@Composable
private fun ContextLinkCard(ctx: AgendaEntry, onClick: () -> Unit) {
    val range = range(ctx)?.let { (a, b) -> rangeLabel(a, b) }
    val sub = listOfNotNull(range, ctx.assignment?.let { "tu puesto ${it.position}" } ?: ctx.location).joinToString(" · ")
    LinkCard(
        // Solo dice a qué está ligado (un recordatorio no es necesariamente un viaje).
        if (ctx.isRace) "VINCULADO A LA CARRERA" else "VINCULADO AL EVENTO", Amber,
        ctx.title, sub, LineIcon.FLAG, badge = ctx.championshipName?.let(::seriesTag), onClick = onClick,
    )
}

@Composable
private fun CircuitCard(ctx: AgendaEntry, onClick: () -> Unit) =
    LinkCard("CIRCUITO", TextMut, ctx.location ?: "Circuito", "Trazados, puestos y activos", LineIcon.PIN, onClick = onClick)

@Composable
private fun ChampionshipCard(ctx: AgendaEntry, onClick: () -> Unit) =
    LinkCard(
        "CAMPEONATO", TextMut, "Ver ${ctx.championshipName ?: "campeonato"}", "Calendario y posiciones",
        LineIcon.FLAG, badge = ctx.championshipName?.let(::seriesTag), onClick = onClick,
    )

@Composable
private fun LinkCard(
    kicker: String,
    kickerColor: Color,
    title: String,
    sub: String,
    icon: LineIcon,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape).clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (badge != null) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val b = if (badge.length <= 3) badge else badge.uppercase().take(3)
                Text(b, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Amber, maxLines = 1)
            }
        } else {
            IconTile(icon, kickerColor, 34)
        }
        Column(Modifier.weight(1f)) {
            Kicker(kicker, kickerColor)
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            if (sub.isNotBlank()) Text(sub, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        }
        LineIconView(LineIcon.CHEVRON, TextFaint, 16.dp, 2.2f)
    }
}

// ——— Piezas chicas ———

@Composable
private fun Kicker(text: String, color: Color) =
    Text(text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.1.sp, color = color)

@Composable
private fun IconTile(icon: LineIcon, color: Color, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        LineIconView(icon, color, (size / 2).dp, 2f)
    }
}

@Composable
private fun ActionChip(label: String, icon: LineIcon, iconColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(44.dp).clip(shape).background(Panel).border(1.dp, BorderStrong, shape).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        LineIconView(icon, iconColor, 16.dp)
        Spacer(Modifier.width(7.dp))
        Text(label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
    }
}

@Composable
private fun SmallAction(label: String, icon: LineIcon, iconColor: Color, textColor: Color = TextPrimary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(36.dp).clip(shape).background(PanelAlt).border(1.dp, BorderStrong, shape).clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIconView(icon, iconColor, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = textColor)
    }
}

@Composable
private fun Privacy(text: String) {
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LineIconView(LineIcon.LOCK, TextFaint, 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)
    }
}

// ——— Lógica ———

/** Lo ligado a la entrada: por su evento o por cualquiera de sus carreras. */
private fun linkedTo(ctx: AgendaEntry, items: List<TripItem>): List<TripItem> {
    val rounds = ctx.races.map { it.roundId }.toSet() + listOfNotNull(ctx.roundId)
    return items.filter { (ctx.eventId != null && it.eventId == ctx.eventId) || (it.roundId != null && it.roundId in rounds) }
}

/** Vínculo para lo que se agregue desde la entrada: el evento si lo trabajas; si no, la carrera. */
private fun linkOf(ctx: AgendaEntry) = TripLink(eventId = ctx.eventId, roundId = if (ctx.eventId == null) ctx.roundId else null)

/** ¿El día está pegado al evento (hasta 3 días antes o después)? = día de viaje/regreso. */
private fun near(d: LocalDate, start: LocalDate, end: LocalDate): Boolean =
    d.daysUntil(start) <= 3 && end.daysUntil(d) <= 3

private fun range(ctx: AgendaEntry): Pair<LocalDate, LocalDate>? {
    val start = ctx.startsOn ?: ctx.at?.let(::dayOf) ?: return null
    return start to (ctx.endsOn ?: start)
}

private fun kindColor(kind: TripItemKind): Color = when (kind) {
    TripItemKind.TRANSPORT -> Travel
    TripItemKind.LODGING -> Live
    TripItemKind.REMINDER -> Amber
    else -> TextSub
}

private fun kindIcon(kind: TripItemKind): LineIcon = when (kind) {
    TripItemKind.TRANSPORT -> LineIcon.PLANE
    TripItemKind.LODGING -> LineIcon.BED
    TripItemKind.REMINDER -> LineIcon.BELL
    TripItemKind.PHOTO -> LineIcon.CAMERA
    TripItemKind.NOTE -> LineIcon.NOTE
}

/** "18:40 → 20:05" (transporte), "21:00 · 4 noches" (hospedaje) o la hora sola. */
private fun timeLine(item: TripItem): String {
    val at = item.at ?: return item.kind.display()
    val end = item.endsAt
    return when {
        end != null && item.kind == TripItemKind.LODGING -> "${hhmm(at)} · ${nights(at, end)}"
        end != null && dayOf(end) == dayOf(at) -> "${hhmm(at)} → ${hhmm(end)}"
        end != null -> "${hhmm(at)} → ${shortDay(dayOf(end))} ${hhmm(end)}"
        else -> hhmm(at)
    }
}

/** Línea de fin para el elemento desplegado (llegada / salida). */
private fun endLine(item: TripItem): String? {
    val end = item.endsAt ?: return null
    val at = item.at
    return when (item.kind) {
        TripItemKind.LODGING -> "Salida ${shortDay(dayOf(end))} · ${hhmm(end)}" + (at?.let { " · ${nights(it, end)}" } ?: "")
        TripItemKind.TRANSPORT -> "Llega " + (if (at != null && dayOf(at) == dayOf(end)) hhmm(end) else "${shortDay(dayOf(end))} · ${hhmm(end)}")
        else -> null
    }
}

private fun nights(at: Instant, end: Instant): String {
    val n = dayOf(at).daysUntil(dayOf(end)).coerceAtLeast(0)
    return if (n == 1) "1 noche" else "$n noches"
}

private val MX = TimeZone.of("America/Mexico_City")
private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private val DIAS_CORTOS = listOf("LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM")
private val DIAS_LARGOS = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
private val DIAS_ABBR = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

private fun today(): LocalDate = Clock.System.now().toLocalDateTime(MX).date
private fun dayOf(at: Instant): LocalDate = at.toLocalDateTime(MX).date
private fun shortDay(d: LocalDate) = "${DIAS_ABBR[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth} ${MESES[d.monthNumber - 1]}"
private fun longDate(d: LocalDate) = "${DIAS_LARGOS[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth} ${MESES[d.monthNumber - 1]} ${d.year}"
private fun rangeLabel(a: LocalDate, b: LocalDate) = when {
    a == b -> "${a.dayOfMonth} ${MESES[a.monthNumber - 1]}"
    a.monthNumber == b.monthNumber -> "${a.dayOfMonth} – ${b.dayOfMonth} ${MESES[b.monthNumber - 1]}"
    else -> "${a.dayOfMonth} ${MESES[a.monthNumber - 1]} – ${b.dayOfMonth} ${MESES[b.monthNumber - 1]}"
}

private fun hhmm(at: Instant): String {
    val dt = at.toLocalDateTime(MX)
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
