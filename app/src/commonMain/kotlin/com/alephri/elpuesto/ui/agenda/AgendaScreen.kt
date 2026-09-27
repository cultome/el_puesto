package com.alephri.elpuesto.ui.agenda

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.RemoteImageBox
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.format.kindDisplay
import com.alephri.elpuesto.ui.format.seriesTag
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/**
 * Agenda del oficial: calendario mensual + lista con dos estados (diseño en
 * docs/design/Agenda - lo que viene/):
 * - Sin día tocado (al entrar): LO QUE VIENE. En el mes actual, de hoy en adelante (lo
 *   anterior del mes se junta en "N anteriores"); en otro mes, el mes completo. Cada entrada
 *   sale una vez, bajo el día en que empieza (o bajo hoy, si sigue en curso).
 * - Día tocado: SOLO ese día. Lo de varios días (fin de semana, evento, hospedaje) sale en
 *   cada día que abarca ("Día 2 de 3", "Noche 2 de 4").
 * Lo del oficial (trabajas, planeación, convocatorias) va en tarjeta; los fines de semana
 * del calendario que no trabaja, en filas compactas.
 */
@Composable
fun AgendaScreen(repo: AppRepository, onOpenEntry: (AgendaEntry) -> Unit = {}, onAddEntry: (LocalDate?) -> Unit = {}) {
    val entries by repo.agenda().collectAsState(initial = null)
    LaunchedEffect(Unit) { repo.refresh() }

    val dated = remember(entries) { entries.orEmpty().mapNotNull(::spanOf) }
    val undated = remember(entries) { entries.orEmpty().filter { spanOf(it) == null } }
    val marks = remember(dated) { dayMarks(dated) }

    // "Hoy" según el reloj real del dispositivo: mes por defecto.
    val today = remember { kotlinx.datetime.Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    // Día tocado y anteriores abiertas: sobreviven a abrir un detalle y volver (el
    // SaveableStateHolder del shell), no a cambiar de pestaña (entrar = lo que viene).
    var selectedIso by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedIso?.let(LocalDate::parse)
    var showPast by rememberSaveable { mutableStateOf(false) }

    // Meses como páginas de un pager (swipe horizontal ⇄ flechas): la página central es
    // el mes actual; el mes visible se deriva de la página.
    val baseMonth = remember(today) { LocalDate(today.year, today.monthNumber, 1) }
    val centerPage = 600
    val pagerState = rememberPagerState(initialPage = centerPage, pageCount = { centerPage * 2 })
    fun monthFor(page: Int) = baseMonth.plus(DatePeriod(months = page - centerPage))
    val month = monthFor(pagerState.currentPage)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    // Cambiar de mes (swipe, flechas, "Hoy", "Siguiente") regresa a "lo que viene". La
    // primera emisión es la página actual (también al volver de un detalle): no limpia.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect {
            selectedIso = null
            showPast = false
        }
    }
    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, start = 22.dp, end = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Agenda", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi)
            // "+" agrega planeación personal (transporte/hospedaje/recordatorio); con un día
            // tocado, el editor abre con esa fecha.
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Panel)
                    .border(1.dp, Border, RoundedCornerShape(11.dp)).clickable { onAddEntry(selected) }
                    .semantics { contentDescription = "Agregar planeación" },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = Amber, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        }

        Refreshable(onRefresh = { repo.refresh() }, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            if (entries == null) {
                // Cargando: skeleton del calendario + lista (sin estados intermedios).
                SkeletonBox(Modifier.fillMaxWidth().height(20.dp))
                Spacer(Modifier.height(12.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(230.dp), corner = 16.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonRows(2, 64.dp)
            } else {
                MonthHeader(
                    month,
                    showToday = month != baseMonth,
                    onToday = { goToPage(centerPage) },
                    onPrev = { goToPage(pagerState.currentPage - 1) },
                    onNext = { goToPage(pagerState.currentPage + 1) },
                )
                Spacer(Modifier.height(12.dp))
                WeekdayRow()
                Spacer(Modifier.height(4.dp))
                HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top) { page ->
                    MonthGrid(monthFor(page), marks, selected, today) { d ->
                        selectedIso = if (d == selected) null else d.toString()
                    }
                }

                if (selected != null) {
                    DaySection(
                        repo, selected, dated.filter { it.covers(selected) }, today,
                        onClear = { selectedIso = null },
                        onOpenEntry = onOpenEntry,
                        onPlan = { onAddEntry(selected) },
                    )
                } else {
                    val next = month.plus(DatePeriod(months = 1))
                    Overview(
                        repo, month, dated, today,
                        isCurrent = month == baseMonth,
                        undated = if (month == baseMonth) undated else emptyList(),
                        showPast = showPast,
                        onTogglePast = { showPast = !showPast },
                        nextCount = dated.count { it.touches(next) },
                        onNext = {
                            scope.launch { scroll.animateScrollTo(0) }
                            goToPage(pagerState.currentPage + 1)
                        },
                        onOpenEntry = onOpenEntry,
                    )
                }
            }
            // Holgura para que lo último suba por encima del botón flotante de bitácora.
            Spacer(Modifier.height(88.dp))
        }
        }
    }
}

// —— Modelo de la pantalla ——

/** Qué es la entrada para la agenda: define su color, su forma (tarjeta o fila) y su punto. */
private enum class Lane { WORK, PLAN, CONVOCATORIA, RACE, SYSTEM }

private fun laneOf(e: AgendaEntry): Lane = when {
    e.kind == AgendaKind.EVENT && e.assigned -> Lane.WORK
    e.personal -> Lane.PLAN
    e.kind == AgendaKind.CONVOCATORIA -> Lane.CONVOCATORIA
    e.kind == AgendaKind.EVENT -> Lane.RACE
    else -> Lane.SYSTEM
}

private fun accentOf(e: AgendaEntry, lane: Lane): Color = when (lane) {
    Lane.WORK -> Amber
    Lane.PLAN -> Travel
    Lane.CONVOCATORIA -> Live
    Lane.RACE -> TextMut
    Lane.SYSTEM -> if (e.kind == AgendaKind.REMINDER) TextMut else Travel
}

/** Una entrada con los días que abarca (fin de semana/evento por su rango; planeación hasta su fin). */
private class Dated(val entry: AgendaEntry, val start: LocalDate, val end: LocalDate) {
    val days: Int get() = start.daysUntil(end) + 1
    fun covers(d: LocalDate) = d in start..end
    fun touches(month: LocalDate) = start <= lastDayOf(month) && end >= month
}

private fun spanOf(e: AgendaEntry): Dated? {
    val start = e.startsOn ?: e.at?.let(::dayOf) ?: return null
    val end = e.endsOn ?: e.endsAt?.let(::dayOf)?.takeIf { it >= start } ?: start
    return Dated(e, start, end)
}

/** Puntos del calendario por día: qué tipos de entrada lo tocan. */
private class DayMarks {
    var work = false
    var plan = false
    var conv = false
    var other = false
}

private fun dayMarks(dated: List<Dated>): Map<LocalDate, DayMarks> {
    val map = HashMap<LocalDate, DayMarks>()
    dated.forEach { d ->
        val lane = laneOf(d.entry)
        var day = d.start
        var guard = 0
        while (day <= d.end && guard++ < 62) {
            val m = map.getOrPut(day) { DayMarks() }
            when (lane) {
                Lane.WORK -> m.work = true
                Lane.PLAN -> m.plan = true
                Lane.CONVOCATORIA -> m.conv = true
                else -> m.other = true
            }
            day = day.plus(DatePeriod(days = 1))
        }
    }
    return map
}

// —— Calendario ——

@Composable
private fun MonthHeader(month: LocalDate, showToday: Boolean, onToday: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${MESES_LARGO[month.monthNumber - 1]} ${month.year}",
            fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextHi,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showToday) {
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(9.dp)).background(Panel)
                        .border(1.dp, Border, RoundedCornerShape(9.dp)).clickable { onToday() }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("HOY", color = Amber, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
            NavArrow("‹", "Mes anterior", onPrev)
            NavArrow("›", "Mes siguiente", onNext)
        }
    }
}

@Composable
private fun NavArrow(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(9.dp)).clickable { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun WeekdayRow() {
    Row(Modifier.fillMaxWidth()) {
        DIAS_CORTOS.forEach { d ->
            Text(
                d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                fontFamily = PlexMonoFamily, fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MonthGrid(month: LocalDate, marks: Map<LocalDate, DayMarks>, selected: LocalDate?, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val leading = month.dayOfWeek.isoDayNumber % 7 // grid inicia en domingo
    val daysInMonth = lastDayOf(month).dayOfMonth
    val rows = (leading + daysInMonth + 6) / 7
    Column {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - leading + 1
                    Box(Modifier.weight(1f).height(48.dp)) {
                        if (day in 1..daysInMonth) {
                            val date = LocalDate(month.year, month.monthNumber, day)
                            val prev = date.minus(DatePeriod(days = 1))
                            val next = date.plus(DatePeriod(days = 1))
                            val work = marks[date]?.work == true
                            DayCell(
                                date, marks[date], isSelected = date == selected, isToday = date == today, isPast = date < today,
                                // Franja de "Trabajas": une los días del evento; cierra en sus
                                // extremos y al cambiar de renglón o de mes.
                                bandStart = work && (marks[prev]?.work != true || c == 0 || day == 1),
                                bandEnd = work && (marks[next]?.work != true || c == 6 || day == daysInMonth),
                            ) { onSelect(date) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    marks: DayMarks?,
    isSelected: Boolean,
    isToday: Boolean,
    isPast: Boolean,
    bandStart: Boolean,
    bandEnd: Boolean,
    onClick: () -> Unit,
) {
    val count = listOf(marks?.work, marks?.plan, marks?.conv, marks?.other).count { it == true }
    val label = "${DIAS_LARGO[date.dayOfWeek.isoDayNumber - 1]} ${date.dayOfMonth} de ${MESES_LARGO[date.monthNumber - 1].lowercase()}" +
        if (count == 0) ", sin actividades" else ""
    Box(
        // Toda la celda es tocable; el resaltado es circular (no recorta la franja).
        Modifier.fillMaxSize().clickable(interactionSource = null, indication = ripple(bounded = false, radius = 22.dp)) { onClick() }.semantics {
            contentDescription = label
            this.selected = isSelected
        },
    ) {
        if (marks?.work == true) {
            val tint = Amber.copy(alpha = 0.15f)
            Canvas(Modifier.fillMaxSize()) {
                val r = 17.dp.toPx()
                val top = 4.dp.toPx()
                val cx = size.width / 2f
                val left = if (bandStart) cx - r else 0f
                val right = if (bandEnd) cx + r else size.width
                val startR = if (bandStart) CornerRadius(r) else CornerRadius.Zero
                val endR = if (bandEnd) CornerRadius(r) else CornerRadius.Zero
                val shape = RoundRect(left, top, right, top + 2 * r, startR, endR, endR, startR)
                drawPath(Path().apply { addRoundRect(shape) }, tint)
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(34.dp).clip(CircleShape)
                .background(if (isSelected) Amber else Color.Transparent)
                .border(1.5.dp, if (isToday && !isSelected) Amber else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                fontFamily = PlexSansFamily, fontSize = 13.sp,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isSelected -> OnAmber
                    isToday -> Amber
                    isPast -> TextFaint
                    else -> TextPrimary
                },
            )
        }
        // Un punto por tipo (máx. 3): trabajas, planeación, convocatoria, carreras.
        val dots = buildList {
            if (marks?.work == true) add(Amber)
            if (marks?.plan == true) add(Travel)
            if (marks?.conv == true) add(Live)
            if (marks?.other == true) add(RaceDot)
        }.take(3)
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 41.dp).alpha(if (isPast) 0.45f else 1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            dots.forEach { Box(Modifier.size(4.dp).clip(CircleShape).background(it)) }
        }
    }
}

// —— Lista ——

/** Estado A: lo que viene (mes actual) o el mes completo (otro mes). */
@Composable
private fun Overview(
    repo: AppRepository,
    month: LocalDate,
    dated: List<Dated>,
    today: LocalDate,
    isCurrent: Boolean,
    undated: List<AgendaEntry>,
    showPast: Boolean,
    onTogglePast: () -> Unit,
    nextCount: Int,
    onNext: () -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
) {
    val inMonth = dated.filter { it.touches(month) }
    val upcoming = if (isCurrent) inMonth.filter { it.end >= today } else inMonth
    val past = if (isCurrent) inMonth.filter { it.end < today } else emptyList()
    val from = if (isCurrent) today else month
    val monthName = MESES_LARGO[month.monthNumber - 1].lowercase()

    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            if (isCurrent) "Lo que viene" else "Todo $monthName",
            fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextHi,
        )
        Text(plural(upcoming.size, "actividad", "actividades"), fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextMut)
    }
    if (past.isNotEmpty()) PastToggle(past.size, showPast, onTogglePast)
    if (showPast) {
        byAnchor(past) { maxOf(it.start, month) }.forEach { (day, items) ->
            DayGroup(repo, day, items, today, dayMode = false, faded = true, onOpenEntry = onOpenEntry)
        }
    }
    byAnchor(upcoming) { maxOf(it.start, from) }.forEach { (day, items) ->
        DayGroup(repo, day, items, today, dayMode = false, faded = false, onOpenEntry = onOpenEntry)
    }
    if (upcoming.isEmpty()) {
        EmptyState(
            LineIcon.CALENDAR,
            if (past.isNotEmpty()) "Nada más en $monthName" else "Sin actividades este mes",
            "Toca + para planear un viaje o agregar un recordatorio.",
            compact = true,
        )
    }

    // Entradas sin fecha (no caben en el calendario): aparte, solo en el mes actual.
    if (undated.isNotEmpty()) {
        Text(
            "SIN FECHA",
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
            color = TextMut, letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        undated.forEach { e -> EntryCard(repo, e, null, null) { onOpenEntry(e) } }
    }

    if (nextCount > 0) {
        val nextName = MESES_LARGO[month.plus(DatePeriod(months = 1)).monthNumber - 1]
        Row(
            Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                .border(1.dp, Border, RoundedCornerShape(14.dp)).clickable { onNext() }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("SIGUIENTE", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp)
                Text(
                    "$nextName · ${plural(nextCount, "actividad", "actividades")}",
                    fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi,
                )
            }
            LineIconView(LineIcon.CHEVRON, Amber, size = 18.dp, strokeWidth = 2.4f)
        }
    }
}

/** Estado B: solo el día tocado. */
@Composable
private fun DaySection(
    repo: AppRepository,
    day: LocalDate,
    items: List<Dated>,
    today: LocalDate,
    onClear: () -> Unit,
    onOpenEntry: (AgendaEntry) -> Unit,
    onPlan: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (day == today) "HOY · " else "") +
                    if (items.isEmpty()) "SIN ACTIVIDADES" else plural(items.size, "ACTIVIDAD", "ACTIVIDADES"),
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Amber, letterSpacing = 1.sp,
            )
            Text(
                "${DIAS_LARGO[day.dayOfWeek.isoDayNumber - 1]} ${day.dayOfMonth} de ${MESES_LARGO[day.monthNumber - 1].lowercase()}",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = TextHi,
            )
        }
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                .border(1.dp, BorderStrong, RoundedCornerShape(12.dp)).clickable { onClear() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LineIconView(LineIcon.CLOSE, TextSub, size = 14.dp, strokeWidth = 2.4f)
            Text("Todo el mes", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
        }
    }
    if (items.isEmpty()) {
        EmptyState(
            LineIcon.CALENDAR,
            "Nada el ${DIAS_LARGO[day.dayOfWeek.isoDayNumber - 1].lowercase()} ${day.dayOfMonth}",
            "Sin carreras ni planes este día.",
            compact = true,
            actionLabel = "Planear algo este día",
            onAction = onPlan,
        )
    } else {
        DayGroup(repo, day, items, today, dayMode = true, faded = false, onOpenEntry = onOpenEntry)
    }
}

/** Agrupa por el día bajo el que se lista cada entrada, en orden. */
private fun byAnchor(items: List<Dated>, anchor: (Dated) -> LocalDate): List<Pair<LocalDate, List<Dated>>> =
    items.groupBy(anchor).toList().sortedBy { it.first }

/** Un día de la lista: lo del oficial en tarjetas y, abajo, las carreras en filas compactas. */
@Composable
private fun DayGroup(
    repo: AppRepository,
    day: LocalDate,
    items: List<Dated>,
    today: LocalDate,
    dayMode: Boolean,
    faded: Boolean,
    onOpenEntry: (AgendaEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).alpha(if (faded) 0.5f else 1f)) {
        if (!dayMode) {
            val isToday = day == today
            Text(
                (if (isToday) "HOY · " else "") + "${DIAS_LARGO[day.dayOfWeek.isoDayNumber - 1]} ${day.dayOfMonth}".uppercase(),
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                color = if (isToday && !faded) Amber else TextMut, letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items.filter { laneOf(it.entry) != Lane.RACE }
            .sortedBy { sortKey(it, day) }
            .forEach { d -> EntryCard(repo, d.entry, d, if (dayMode) day else null) { onOpenEntry(d.entry) } }
        val races = items.filter { laneOf(it.entry) == Lane.RACE }
            .sortedWith(compareBy<Dated>({ it.start }, { raceTitle(it.entry) }))
        if (races.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelAlt)
                    .border(1.dp, RaceBorder, RoundedCornerShape(14.dp)),
            ) {
                races.forEachIndexed { i, d ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(RaceBorder))
                    RaceRow(repo, d, if (dayMode) day else null) { onOpenEntry(d.entry) }
                }
            }
        }
    }
}

/** Trabajas primero; lo demás por la hora que le toca ESE día (entrada/salida del hospedaje). */
private fun sortKey(d: Dated, day: LocalDate): Int {
    val e = d.entry
    if (laneOf(e) == Lane.WORK) return -1
    if (e.allDay) return 0
    val at = e.at?.toLocalDateTime(MX)
    val end = e.endsAt?.toLocalDateTime(MX)
    return when {
        at != null && at.date == day -> at.hour * 60 + at.minute
        end != null && end.date == day -> end.hour * 60 + end.minute
        else -> 0 // a mitad de una estancia: arriba
    }
}

@Composable
private fun PastToggle(count: Int, open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .drawBehind {
                drawRoundRect(
                    BorderStrong,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
            .clickable { onToggle() }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LineIconView(LineIcon.CHEVRON, TextSub, size = 14.dp, strokeWidth = 2.4f, modifier = Modifier.rotate(if (open) 90f else 0f))
        Text(
            if (open) "OCULTAR ANTERIORES" else plural(count, "ANTERIOR ESTE MES", "ANTERIORES ESTE MES"),
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = TextSub, letterSpacing = 0.8.sp,
        )
    }
}

/**
 * Tarjeta de lo del oficial (trabajas, planeación, convocatoria). [day] = día tocado
 * (estado B: "Día 1 de 3", "Noche 2 de 4") o null (estado A: el rango).
 */
@Composable
private fun EntryCard(repo: AppRepository, e: AgendaEntry, dated: Dated?, day: LocalDate?, onClick: () -> Unit) {
    val lane = laneOf(e)
    val accent = accentOf(e, lane)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(Panel)
            .border(1.dp, if (lane == Lane.WORK) Amber.copy(alpha = 0.45f) else Border, shape)
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryTile(repo, e, lane, accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                kickerOf(e, lane, dated, day),
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = accent, letterSpacing = 0.8.sp,
            )
            Text(e.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, lineHeight = 18.sp)
            subOf(e, lane)?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut) }
            if (lane == Lane.WORK) {
                e.assignment?.let { a ->
                    val text = listOf(a.position, a.role).filter { it.isNotBlank() }.joinToString(" · ") +
                        if (a.positionPending) " (en revisión)" else ""
                    if (text.isNotBlank()) {
                        Box(Modifier.padding(top = 5.dp)) {
                            Tag(text, container = Amber.copy(alpha = 0.14f), contentColor = Amber, border = Amber.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

/** Ícono de la tarjeta: logo del campeonato (trabajas), tipo de planeación o convocatoria. */
@Composable
private fun EntryTile(repo: AppRepository, e: AgendaEntry, lane: Lane, accent: Color) {
    val shape = RoundedCornerShape(10.dp)
    val tile: @Composable () -> Unit = {
        Box(
            Modifier.size(40.dp).clip(shape).background(accent.copy(alpha = 0.14f)).border(1.dp, accent.copy(alpha = 0.3f), shape),
            contentAlignment = Alignment.Center,
        ) {
            val tag = e.championshipName?.let(::shortSeries)
            if (lane == Lane.WORK && tag != null) {
                Text(tag, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = accent)
            } else {
                LineIconView(iconOf(e, lane), accent, size = 18.dp)
            }
        }
    }
    val logo = e.championshipEmblemUrl?.takeIf { lane == Lane.WORK }
    if (logo == null) tile()
    else RemoteImageBox(repo, logo, modifier = Modifier.border(1.dp, Amber.copy(alpha = 0.3f), shape), size = 40.dp, shape = shape, fallback = tile)
}

private fun iconOf(e: AgendaEntry, lane: Lane): LineIcon = when {
    lane == Lane.WORK -> LineIcon.FLAG
    lane == Lane.CONVOCATORIA -> LineIcon.MEGAPHONE
    e.tripKind == TripItemKind.TRANSPORT -> LineIcon.PLANE
    e.tripKind == TripItemKind.LODGING -> LineIcon.BED
    e.tripKind == TripItemKind.REMINDER || e.kind == AgendaKind.REMINDER -> LineIcon.BELL
    e.kind == AgendaKind.TRIP -> LineIcon.SUITCASE
    else -> LineIcon.CALENDAR
}

private fun kickerOf(e: AgendaEntry, lane: Lane, d: Dated?, day: LocalDate?): String {
    val at = e.at?.toLocalDateTime(MX)
    val end = e.endsAt?.toLocalDateTime(MX)
    val time = at?.takeIf { !e.allDay }?.let { " · ${hhmm(it)}" }.orEmpty()
    return when (lane) {
        Lane.WORK -> "TRABAJAS" + when {
            d == null -> ""
            day != null && d.days > 1 -> " · DÍA ${d.start.daysUntil(day) + 1} DE ${d.days}"
            day != null -> ""
            else -> " · " + rangeLabel(d.start, d.end).uppercase()
        }
        Lane.PLAN -> when (e.tripKind) {
            TripItemKind.TRANSPORT -> "TRANSPORTE" + when {
                at == null -> ""
                day != null && end != null && end.date == day && at.date != day -> " · LLEGA ${hhmm(end)}"
                end != null -> " · ${hhmm(at)} → ${hhmm(end)}"
                else -> " · ${hhmm(at)}"
            }
            TripItemKind.LODGING -> "HOSPEDAJE" + when {
                at == null -> ""
                day == null && end != null && end.date > at.date -> " · " + rangeLabel(at.date, end.date).uppercase()
                day == null || day == at.date -> " · ENTRADA ${hhmm(at)}"
                end != null && day == end.date -> " · SALIDA ${hhmm(end)}"
                end != null -> " · NOCHE ${at.date.daysUntil(day) + 1} DE ${at.date.daysUntil(end.date)}"
                else -> ""
            }
            else -> e.kindDisplay().uppercase() + time
        }
        Lane.CONVOCATORIA -> "CONVOCATORIA$time"
        else -> e.kindDisplay().uppercase() + time
    }
}

private fun subOf(e: AgendaEntry, lane: Lane): String? {
    if (lane == Lane.PLAN && e.tripKind == TripItemKind.LODGING) {
        val a = e.at?.let(::dayOf)
        val b = e.endsAt?.let(::dayOf)
        if (a != null && b != null && b > a) {
            return plural(a.daysUntil(b), "noche", "noches") + (e.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
    }
    return e.location?.takeIf { it.isNotBlank() }
}

/** Fila compacta de un fin de semana del calendario (o evento) que el oficial no trabaja. */
@Composable
private fun RaceRow(repo: AppRepository, d: Dated, day: LocalDate?, onClick: () -> Unit) {
    val e = d.entry
    val sub: String?
    val right: String?
    if (day != null) {
        val cats = e.races.filter { it.date == day }.map { it.categoryName }.distinct()
        sub = listOfNotNull(categoriesLabel(cats), e.location).joinToString(" · ").ifBlank { null }
        right = if (d.days > 1) "DÍA ${d.start.daysUntil(day) + 1}/${d.days}" else null
    } else {
        sub = e.location
        right = rangeLabel(d.start, d.end).uppercase()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SeriesTile(repo, e, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(
                raceTitle(e), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = TextPrimary,
            )
            sub?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
            }
        }
        right?.let { Text(it, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 0.5.sp) }
    }
}

/** Logo del campeonato; sin logo, sus siglas; sin campeonato (evento suelto), una bandera. */
@Composable
private fun SeriesTile(repo: AppRepository, e: AgendaEntry, size: Dp) {
    val shape = RoundedCornerShape(8.dp)
    val tile: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(shape).background(PanelElevA).border(1.dp, BorderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            val tag = e.championshipName?.let(::shortSeries)
            if (tag != null) Text(tag, fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = TextSub)
            else LineIconView(LineIcon.FLAG, TextSub, size = 14.dp)
        }
    }
    val logo = e.championshipEmblemUrl
    if (logo == null) tile()
    else RemoteImageBox(repo, logo, modifier = Modifier.border(1.dp, Border, shape), size = size, shape = shape, fallback = tile)
}

/** "F1 · Italian Grand Prix" → "Italian Grand Prix" (el logo ya dice el campeonato). */
private fun raceTitle(e: AgendaEntry): String =
    e.championshipName?.let { e.title.removePrefix("$it · ") } ?: e.title

/** Siglas que caben en un cuadro chico: "F1", "WEC", "NASCAR México" → "NM". */
private fun shortSeries(name: String): String {
    val tag = seriesTag(name)
    if (tag.length <= 4) return tag
    val words = tag.split(' ').filter { it.isNotBlank() }
    return if (words.size > 1) words.take(3).joinToString("") { it.first().uppercase() } else tag.take(4).uppercase()
}

private fun categoriesLabel(cats: List<String>): String? = when (cats.size) {
    0 -> null
    1 -> cats[0]
    2 -> "${cats[0]} y ${cats[1]}"
    else -> "${cats[0]} y ${cats.size - 1} más"
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

private fun lastDayOf(month: LocalDate): LocalDate =
    LocalDate(month.year, month.monthNumber, 1).plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))

private fun rangeLabel(a: LocalDate, b: LocalDate) = when {
    a == b -> "${a.dayOfMonth} ${MESES_CORTO[a.monthNumber - 1]}"
    a.year == b.year && a.monthNumber == b.monthNumber -> "${a.dayOfMonth}–${b.dayOfMonth} ${MESES_CORTO[b.monthNumber - 1]}"
    else -> "${a.dayOfMonth} ${MESES_CORTO[a.monthNumber - 1]} – ${b.dayOfMonth} ${MESES_CORTO[b.monthNumber - 1]}"
}

/**
 * Etiqueta de una entrada en otras listas (Home): el LOGO del campeonato cuando la etiqueta
 * sería su nombre ("F1", "NASCAR") y el campeonato tiene logo; un ÍCONO para la planeación
 * (transporte, hospedaje, recordatorio); si no (o mientras carga el logo), el texto.
 */
@Composable
internal fun AgendaKindTag(repo: AppRepository, entry: AgendaEntry, accent: Color) {
    val text: @Composable () -> Unit = {
        Tag(entry.kindDisplay(), container = accent.copy(alpha = 0.14f), contentColor = accent, border = accent.copy(alpha = 0.28f))
    }
    val icon = when (entry.tripKind) {
        TripItemKind.TRANSPORT -> LineIcon.PLANE
        TripItemKind.LODGING -> LineIcon.BED
        TripItemKind.REMINDER -> LineIcon.BELL
        else -> if (entry.kind == AgendaKind.REMINDER) LineIcon.BELL else null
    }
    if (icon != null) {
        val shape = RoundedCornerShape(9.dp)
        val label = entry.kindDisplay()
        Box(
            Modifier.size(36.dp).clip(shape).background(accent.copy(alpha = 0.14f)).border(1.dp, accent.copy(alpha = 0.28f), shape)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { LineIconView(icon, accent, size = 18.dp) }
        return
    }
    val logo = entry.championshipEmblemUrl?.takeIf { entry.kind == AgendaKind.EVENT && !entry.assigned }
    if (logo == null) return text()
    val shape = RoundedCornerShape(9.dp)
    RemoteImageBox(repo, logo, modifier = Modifier.border(1.dp, Border, shape), size = 36.dp, shape = shape, fallback = text)
}

private val MX = TimeZone.of("America/Mexico_City")
private val RaceDot = Color(0xFF5B6272)
private val RaceBorder = Color(0xFF2B303C)
private val DIAS_CORTOS = listOf("Do", "Lu", "Ma", "Mi", "Ju", "Vi", "Sa")
private val DIAS_LARGO = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
private val MESES_LARGO = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
private val MESES_CORTO = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private fun dayOf(at: kotlinx.datetime.Instant): LocalDate = at.toLocalDateTime(MX).date
private fun hhmm(dt: LocalDateTime): String = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
