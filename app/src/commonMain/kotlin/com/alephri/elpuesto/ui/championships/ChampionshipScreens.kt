package com.alephri.elpuesto.ui.championships

import com.alephri.elpuesto.ui.platform.BackHandler
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.TrackSilhouette
import kotlinx.datetime.daysUntil
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import com.alephri.elpuesto.ui.components.Avatar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Category
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Series
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.RemoteImageBox
import com.alephri.elpuesto.ui.components.Segmented
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.UnavailableInline
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.format.championshipBadge
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.PanelAlt
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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ————————————————————— Catálogo (CH-1) —————————————————————

/**
 * Catálogo = TARJETAS DE TEMPORADA (opción A de Claude Design): una tarjeta por campeonato
 * con su temporada vigente (en curso → próxima → última): estado, avance fecha a fecha,
 * la siguiente fecha con la silueta de su circuito y las categorías. Los que están en curso
 * van primero, luego los que arrancan y al final los terminados.
 */
@Composable
fun ChampionshipCatalogScreen(repo: AppRepository, onBack: () -> Unit, onOpenChampionship: (String) -> Unit) {
    var series by remember { mutableStateOf<List<Series>?>(null) }
    var seasons by remember { mutableStateOf<List<Championship>>(emptyList()) }
    var paths by remember { mutableStateOf<Map<String, List<com.alephri.elpuesto.model.MapPoint>>>(emptyMap()) }
    var unavailable by remember { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    val load: suspend () -> Unit = {
        val t = reloader.track {
            seasons = repo.championships() // antes que la lista: la tarjeta se pinta completa
            repo.series()
        }
        series = t.value
        unavailable = t.value.isEmpty() && t.missed
        // Silueta del circuito de la siguiente fecha (catálogo de circuitos, en caché).
        paths = reloader.track { repo.circuits() }.value
            .mapNotNull { c -> c.mainTrazado?.path?.takeIf { it.size >= 3 }?.let { c.id to it } }.toMap()
    }
    LaunchedEffect(reloader.key) { load() }

    if (unavailable) {
        UnavailableScreen(repo, "Campeonatos", onBack, reloader::retry)
        return
    }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CAMPEONATOS", onBack)
        Refreshable(onRefresh = load, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            val list = series
            if (list == null) {
                SkeletonRows(3, 220.dp, corner = 20.dp)
            } else if (list.isEmpty()) {
                EmptyState(LineIcon.FLAG, "Aún no hay campeonatos", "Cuando se carguen campeonatos y sus calendarios, aparecerán aquí.")
            } else {
                val cards = list.map { s ->
                    val own = seasons.filter { it.seriesId == s.id }
                    val current = defaultSeason(own)
                    Triple(s, own, current)
                }.sortedBy { (_, _, c) -> c?.let(::stateRank) ?: 3 }
                cards.forEach { (s, own, current) ->
                    SeasonCard(repo, s, own.size, current, paths) { onOpenChampionship(s.id) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

private val MESES_CORTOS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

private fun shortDay(d: kotlinx.datetime.LocalDate) = "${d.dayOfMonth} ${MESES_CORTOS[d.monthNumber - 1]}"

/** 0 = en curso, 1 = arranca, 2 = terminó (orden del catálogo). */
private fun stateRank(c: Championship): Int {
    val start = c.startsOn ?: return 3
    val end = c.endsOn ?: start
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Mexico_City")).date
    return when {
        today in start..end -> 0
        today < start -> 1
        else -> 2
    }
}

@Composable
private fun SeasonCard(
    repo: AppRepository,
    s: Series,
    seasonCount: Int,
    c: Championship?,
    paths: Map<String, List<com.alephri.elpuesto.model.MapPoint>>,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val rank = c?.let(::stateRank) ?: 3
    val stateColor = when (rank) { 0 -> Live; 1 -> Amber; else -> TextFaint }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape).background(Panel).border(1.dp, Border, shape)
            .clickable { onClick() }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeriesBadge(repo, s.emblemUrl, s.name, 52)
            Column(Modifier.weight(1f)) {
                Text(s.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi)
                c?.let {
                    Text(
                        "Temporada ${it.seasonLabel}" + if (seasonCount > 1) " · $seasonCount temporadas" else "",
                        fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
                    )
                }
            }
            c?.let(::seasonState)?.let { (txt, _) -> StateTag(txt, stateColor) }
        }
        if (c == null || c.roundsTotal == 0) return@Column
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${c.roundsDone} de ${c.roundsTotal} fechas", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = TextSub)
            if (c.startsOn != null && c.endsOn != null) {
                Text("${shortDay(c.startsOn!!)} – ${shortDay(c.endsOn!!)}", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = TextSub)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Avance fecha a fecha: un segmento por fecha de la categoría principal.
        val done = if (rank == 2) BorderStrong else stateColor
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(c.roundsTotal) { i ->
                Box(
                    Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < c.roundsDone) done else PanelElevA),
                )
            }
        }
        c.nextRound?.let { r -> NextRoundBox(r, paths[r.circuitId]) }
        if (c.categoryNames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.categoryNames.forEach { name ->
                    Text(
                        name, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextSub,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PanelElevA).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NextRoundBox(r: Round, path: List<com.alephri.elpuesto.model.MapPoint>?) {
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Mexico_City")).date
    val days = today.daysUntil(r.date)
    val whenTxt = when {
        r.status == EventStatus.LIVE -> "EN CURSO"
        days <= 0 -> "HOY"
        days == 1 -> "MAÑANA"
        else -> "EN $days DÍAS"
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelAlt).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (path != null) {
                TrackSilhouette(path, Modifier.size(46.dp), color = TextSub, width = 1.6.dp)
            } else {
                // Rallies (sin circuito) o circuito sin dibujo: un pin.
                LineIconView(LineIcon.PIN, TextSub, size = 22.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "PRÓXIMA · FECHA ${r.number} · $whenTxt", fontFamily = PlexMonoFamily, fontSize = 10.sp,
                letterSpacing = 1.sp, color = if (r.status == EventStatus.LIVE) Live else Amber,
            )
            Text(r.name ?: "Fecha ${r.number}", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
            Text(
                listOfNotNull(r.circuitName.ifBlank { null } ?: r.location, shortDay(r.date)).joinToString(" · "),
                fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut,
            )
        }
    }
}

@Composable
private fun StateTag(text: String, color: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(100.dp)
    Text(
        text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.6.sp, color = color,
        maxLines = 1,
        modifier = Modifier.clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SeriesBadge(repo: AppRepository, emblemUrl: String?, name: String, size: Int) {
    RemoteImageBox(repo, emblemUrl, size = size.dp, shape = RoundedCornerShape(12.dp)) {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(championshipBadge(name), fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = (size / 3).sp, color = Amber) }
    }
}

/** Temporada por defecto: la que está en curso; si no, la próxima; si no, la más reciente. */
internal fun defaultSeason(seasons: List<Championship>): Championship? {
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Mexico_City")).date
    return seasons.firstOrNull { s -> s.startsOn != null && s.endsOn != null && today in s.startsOn!!..s.endsOn!! }
        ?: seasons.filter { it.startsOn != null && it.startsOn!! > today }.minByOrNull { it.startsOn!! }
        ?: seasons.maxByOrNull { it.season }
}

/** "En curso" / "Arranca 18 dic" / "Terminó" según el rango de la temporada. */
private fun seasonState(c: Championship): Pair<String, Boolean>? {
    val start = c.startsOn ?: return null
    val end = c.endsOn ?: return null
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/Mexico_City")).date
    return when {
        today in start..end -> "EN CURSO" to true
        today < start -> "ARRANCA ${start.dayOfMonth} ${MESES[start.monthNumber - 1].uppercase()}" to false
        else -> "TERMINÓ" to false
    }
}

// ————————————————————— Detalle (CH-2 / CH-3 / CH-4) —————————————————————

/**
 * Detalle de un campeonato: selector de TEMPORADA (si tiene más de una) → categoría →
 * Calendario / Posiciones. Se abre por campeonato ([seriesId], desde el catálogo) o por
 * temporada ([seasonId], desde la agenda o el MbM: esa temporada queda seleccionada).
 */
@Composable
fun ChampionshipDetailScreen(
    repo: AppRepository,
    seriesId: String?,
    seasonId: String?,
    onBack: () -> Unit,
    onOpenCircuito: (String) -> Unit,
    onOpenImage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var series by remember(seriesId, seasonId) { mutableStateOf<Series?>(null) }
    var seasons by remember(seriesId, seasonId) { mutableStateOf<List<Championship>>(emptyList()) }
    var season by remember(seriesId, seasonId) { mutableStateOf<Championship?>(null) }
    // null = aún no se sabe (cargando): nunca se confunde con "sin categorías".
    var categories by remember(seriesId, seasonId) { mutableStateOf<List<Category>?>(null) }
    var category by remember(seriesId, seasonId) { mutableStateOf<Category?>(null) }
    // Lo elegido en los chips (null = la temporada/categoría por defecto). Saveable, igual que la
    // pestaña y el scroll: abrir la foto de un piloto saca esta pantalla de la composición y al
    // volver debe quedar como estaba (misma categoría, en Posiciones), no en la por defecto.
    var pickedSeason by rememberSaveable(seriesId, seasonId) { mutableStateOf(seasonId) }
    var pickedCategory by rememberSaveable(seriesId, seasonId) { mutableStateOf<String?>(null) }
    var standings by remember { mutableStateOf<List<Standing>?>(null) }
    var rounds by remember { mutableStateOf<List<Round>?>(null) }
    // Posiciones solo si la categoría tiene tabla (decisión 2026-09-25: las fuentes
    // automáticas son un nice-to-have; sin datos, la categoría muestra su calendario y ya).
    var hasStandings by rememberSaveable { mutableStateOf(false) } // si no, al volver solo hay 1 pestaña
    val pager = rememberPagerState(pageCount = { if (hasStandings) 2 else 1 })
    // Calendario: al abrir (y al cambiar de categoría) se desplaza a la fecha en curso o
    // la próxima; con la temporada avanzada, las terminadas quedan arriba.
    val calScroll = rememberScrollState()
    // A este nivel (no dentro de la página): así se conserva al volver de la foto de un piloto.
    val standingsScroll = rememberScrollState()
    // Al volver, la lista pasa un cuadro como esqueleto (más corto) y el ScrollState restaurado se
    // recorta a esa altura: se anota el scroll mientras hay tabla y se re-aplica cuando ya creció.
    var standingsOffset by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val target = standingsOffset
        if (target > 0) {
            withTimeoutOrNull(3_000) { snapshotFlow { standingsScroll.maxValue }.first { it >= target } }
            standingsScroll.scrollTo(target)
        }
        snapshotFlow { standingsScroll.value }.collect { if (standings != null) standingsOffset = it }
    }
    var calTargetY by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(calTargetY) {
        calTargetY?.let { calScroll.animateScrollTo((it - 12f).toInt().coerceAtLeast(0)) }
    }
    // Sin red ni caché: el campeonato completo (pantalla) o sus pestañas (en línea).
    var unavailable by remember(seriesId, seasonId) { mutableStateOf(false) }
    var catsMissing by remember(seriesId, seasonId) { mutableStateOf(false) }
    var roundsMissing by remember { mutableStateOf(false) }
    var standingsMissing by remember { mutableStateOf(false) }
    var roundsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }

    // Toda la pantalla en UNA pasada (temporada → categorías → calendario/posiciones de la
    // categoría elegida) y el cuerpo se asigna junto: con la caché sale completa de una
    // vez, sin la cascada de pasos que dejaba ver un "Aún no hay calendario" falso.
    suspend fun load() {
        val t = reloader.track {
            val all = repo.championships()
            val sid = seriesId ?: all.firstOrNull { it.id == seasonId }?.seriesId
            val ser = repo.series().firstOrNull { it.id == sid }
            // Cronológicas en el selector (2025-26 · 2026-27).
            val seas = all.filter { it.seriesId == sid && !sid.isNullOrEmpty() }.sortedBy { it.season }
                .ifEmpty { all.filter { it.id == seasonId } } // caché vieja sin campeonato
            val sea = seas.firstOrNull { it.id == pickedSeason } ?: defaultSeason(seas)
            // El encabezado ya puede pintarse (sin red, la primera vez, llega antes).
            series = ser; seasons = seas; season = sea
            val cats = sea?.let { s -> reloader.track { repo.categories(s.id) } }
            val cat = cats?.value?.let { list -> list.firstOrNull { it.id == pickedCategory } ?: list.firstOrNull() }
            val st = cat?.let { c -> reloader.track { repo.standings(c.id) } }
            val rd = cat?.let { c -> reloader.track { repo.rounds(c.id) } }
            Triple(cats, cat, st to rd)
        }
        unavailable = t.missed && series == null && seasons.isEmpty()
        val (cats, cat, lists) = t.value
        val (st, rd) = lists
        val stList = st?.value.orEmpty()
        categories = cats?.value.orEmpty()
        catsMissing = cats != null && cats.missed && cats.value.isEmpty()
        category = cat
        standings = stList
        rounds = rd?.value.orEmpty()
        standingsMissing = st != null && st.missed && stList.isEmpty()
        roundsMissing = rd != null && rd.missed && rd.value.isEmpty()
        hasStandings = stList.isNotEmpty() || (standingsMissing && cat?.standingsCredit != null)
        // Solo al CAMBIAR de categoría el calendario vuelve arriba (y se re-centra en la
        // fecha en curso); una recarga de la misma — volvió la señal, el servidor trajo
        // cambios — se pinta encima sin mover el scroll.
        if (cat?.id != roundsFor) {
            roundsFor = cat?.id
            calTargetY = null
            calScroll.scrollTo(0)
            standingsScroll.scrollTo(0)
        }
    }
    LaunchedEffect(seriesId, seasonId, pickedSeason, pickedCategory, reloader.key) { load() }
    if (unavailable) {
        UnavailableScreen(repo, "Campeonato", onBack, reloader::retry)
        return
    }
    val liveRound = rounds?.firstOrNull { it.status == EventStatus.LIVE }
    val name = series?.name ?: season?.name ?: "—"

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CAMPEONATO", onBack)
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SeriesBadge(repo, series?.emblemUrl ?: season?.emblemUrl, name, 40)
                Column(Modifier.weight(1f)) {
                    Text(name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi)
                    val fecha = liveRound?.let { "Fecha ${it.number} de ${rounds?.size ?: 0}" } ?: season?.let { "Temporada ${it.seasonLabel}" } ?: ""
                    Text(fecha, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub)
                }
            }
            if (seasons.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text("TEMPORADA", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = Amber, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                CategoryChips(seasons.map { it.seasonLabel }, seasons.indexOf(season).coerceAtLeast(0)) {
                    pickedSeason = seasons[it].id
                    pickedCategory = null
                }
            }
            val cats = categories.orEmpty()
            if (cats.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("CATEGORÍA", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = Amber, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                CategoryChips(cats.map { it.name }, cats.indexOfFirst { it.id == category?.id }.coerceAtLeast(0)) {
                    pickedCategory = cats[it].id
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (hasStandings) {
            Segmented(listOf("Calendario", "Posiciones"), pager.currentPage, { scope.launch { pager.animateScrollToPage(it) } }, Modifier.padding(horizontal = 22.dp))
        }
        Refreshable(
            onRefresh = { load() }, // Refreshable lo corre con freshReads: todo de la red
            modifier = Modifier.weight(1f),
        ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val pageScroll = if (page == 0) calScroll else standingsScroll
            Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(14.dp))
                when {
                    catsMissing || (if (page == 0) roundsMissing else standingsMissing) ->
                        UnavailableInline(repo, reloader::retry)
                    page == 0 -> RoundsTab(rounds, onOpenCircuito) { y -> if (calTargetY == null) calTargetY = y }
                    else -> StandingsTab(repo, standings, category, rounds?.size, onOpenImage)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }
}

@Composable
private fun StandingsTab(
    repo: AppRepository,
    standings: List<Standing>?,
    category: Category?,
    totalRounds: Int?,
    onOpenImage: (String) -> Unit,
) {
    if (standings == null) { SkeletonRows(6, 48.dp); return } // cargando
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Posiciones oficiales", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        // Con ingesta automática se sabe hasta qué fecha llega la tabla.
        category?.standingsThroughRound?.takeIf { standings.isNotEmpty() }?.let { n ->
            Text(
                if (totalRounds != null && totalRounds > 0) "TRAS LA FECHA $n DE $totalRounds" else "TRAS LA FECHA $n",
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = Amber, letterSpacing = 1.sp,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    if (standings.isEmpty()) {
        EmptyState(LineIcon.PODIUM, "Sin posiciones", "La tabla de esta categoría aparecerá aquí cuando se publique.", compact = true)
        return
    }
    standings.forEach { s ->
        // Con foto, tocar la fila la abre en grande (más adelante: el detalle del piloto).
        val photo = s.photoUrl
        Row(
            Modifier.fillMaxWidth()
                .then(if (photo != null) Modifier.clickable { onOpenImage(photo) } else Modifier)
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${s.pos}", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (s.pos <= 3) Amber else TextMut, modifier = Modifier.size(width = 22.dp, height = 22.dp), textAlign = TextAlign.Center)
            RemoteImageBox(repo, photo, size = 40.dp, shape = CircleShape) {
                Avatar(driverInitials(s.driverName), size = 40.dp, bg = PanelElevA, textColor = TextSub)
            }
            Column(Modifier.weight(1f)) {
                Text(s.driverName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    // El número va como texto ("007", "00"); el entero es legado de cachés viejas.
                    (s.numberText ?: s.driverNumber.takeIf { it > 0 }?.toString())?.let {
                        NumberChip(it)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(s.team, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${s.points} pts", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextHi)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
    }
    // Atribución que piden las licencias de las fuentes (p. ej. CC BY-NC-SA de Jolpica).
    category?.standingsCredit?.let {
        Text("Fuente: $it", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun RoundsTab(rounds: List<Round>?, onOpenCircuito: (String) -> Unit, onTargetY: (Float) -> Unit = {}) {
    if (rounds == null) { SkeletonRows(5, 72.dp); return } // cargando
    if (rounds.isEmpty()) {
        EmptyState(LineIcon.CALENDAR, "Aún no hay calendario", "Las fechas de esta categoría aparecerán aquí en cuanto se publiquen.", compact = true)
        return
    }
    // "Próxima" solo para la primera por venir (las demás muestran su fecha y ya).
    val nextNumber = rounds.firstOrNull { it.status == EventStatus.UPCOMING }?.number
    // Destino del auto-desplazamiento: la fecha en curso o, si no hay, la próxima (nada si
    // la temporada ya terminó o todavía no empieza: ahí conviene ver desde la primera).
    val target = (rounds.firstOrNull { it.status == EventStatus.LIVE }?.number ?: nextNumber)
        ?.takeIf { rounds.any { r -> r.status == EventStatus.FINISHED } }
    rounds.forEach { r ->
        val live = r.status == EventStatus.LIVE
        val done = r.status == EventStatus.FINISHED
        val circuitId = r.circuitId
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .let { m -> if (r.number == target) m.onGloballyPositioned { onTargetY(it.positionInParent().y) } else m },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.size(width = 54.dp, height = 44.dp), verticalArrangement = Arrangement.Center) {
                Text("FECHA", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 8.sp, color = TextFaint, letterSpacing = 0.5.sp)
                Text("${r.number}", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = if (live) Live else if (done) TextMut else Amber)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Panel)
                    .border(1.dp, if (live) Live.copy(alpha = 0.4f) else Border, RoundedCornerShape(14.dp))
                    .let { m -> if (circuitId != null) m.clickable { onOpenCircuito(circuitId) } else m }
                    .padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // Título = nombre del evento; sin él (dato legado), el circuito.
                    Text(r.name ?: r.circuitName, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = if (done) TextMut else TextHi)
                    Spacer(Modifier.height(4.dp))
                    val sede = listOfNotNull(r.circuitName.takeIf { r.name != null && it.isNotBlank() }, r.location)
                        .joinToString(" · ")
                    if (sede.isNotBlank()) Text(sede, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
                    Spacer(Modifier.height(4.dp))
                    val estado = when {
                        live -> "Este fin de semana"
                        done -> "Terminada"
                        r.number == nextNumber -> "Próxima"
                        else -> null
                    }
                    Text(
                        listOfNotNull(fmtRange(r), estado).joinToString(" · "),
                        fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        color = if (live) Live else if (estado == "Próxima") Amber else TextSub,
                    )
                }
                if (circuitId != null) Text("›", color = TextFaint, fontSize = 20.sp)
            }
        }
    }
}

/** Selector de categoría: chips con scroll horizontal (nombres largos como "O'Reilly Auto Parts Series"). */
@Composable
private fun CategoryChips(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { i, n ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Amber else PanelAlt)
                    .border(1.dp, if (sel) Amber else Border, RoundedCornerShape(50))
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(n, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (sel) OnAmber else TextMut, maxLines = 1)
            }
        }
    }
}

/** Número del coche junto al equipo ("#33"): chico pero con contraste, es como lo reconocen en pista. */
@Composable
private fun NumberChip(number: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("#$number", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, color = TextHi, maxLines = 1)
    }
}

/** "Brendon Hartley / Ryō Hirakawa" → "BH" (de una tripulación, el primero). */
private fun driverInitials(name: String): String {
    val words = name.substringBefore(" / ").split(' ').filter { it.isNotBlank() }
    return listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull()).joinToString("") { it.take(1).uppercase() }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(34.dp))
    }
}

private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
/** "5–7 JUN" / "30 MAY–1 JUN" / "8 MAR" (un solo día o sin inicio conocido). */
private fun fmtRange(r: Round): String {
    val end = r.date
    val start = r.startDate?.takeIf { it < end }
        ?: return "${end.dayOfMonth} ${MESES[end.monthNumber - 1]}".uppercase()
    val txt = if (start.monthNumber == end.monthNumber) {
        "${start.dayOfMonth}–${end.dayOfMonth} ${MESES[end.monthNumber - 1]}"
    } else {
        "${start.dayOfMonth} ${MESES[start.monthNumber - 1]}–${end.dayOfMonth} ${MESES[end.monthNumber - 1]}"
    }
    return txt.uppercase()
}
