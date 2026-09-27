package com.alephri.elpuesto.ui.achievements

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Achievement
import com.alephri.elpuesto.model.AchievementRules
import com.alephri.elpuesto.model.Achievements
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.PassportStamp
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.TrackSilhouette
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.Surface
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground

// —— Datos comunes ——

/** Circuitos de México del catálogo (los del pasaporte): trabajados primero, luego por nombre. */
internal fun mxCircuits(circuits: List<Circuit>, stamps: List<PassportStamp>): List<Circuit> {
    val first = stamps.associate { it.circuitId to it.firstOn }
    return circuits.filter { it.country == AchievementRules.HOME_COUNTRY }
        .sortedWith(compareBy<Circuit> { first[it.id] == null }.thenBy { first[it.id] }.thenBy { it.name })
}

private fun Achievements.item(key: String) = items.firstOrNull { it.key == key }

/** Fecha más reciente en que se ganó algo de este logro (para "recientes"). */
private fun Achievement.lastReached() = reachedOn.lastOrNull()

@Composable
private fun MonoLabel(text: String, color: Color = Amber, modifier: Modifier = Modifier) {
    Text(text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.4.sp, color = color, modifier = modifier)
}

@Composable
private fun VisibilityNote(public: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        LineIconView(if (public) LineIcon.EYE else LineIcon.LOCK, TextMut, size = 14.dp)
        Text(if (public) "Visible en tu perfil" else "Solo tú", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
    }
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
        Spacer(Modifier.size(42.dp))
    }
}

/** Barra segmentada (un segmento por circuito de México) o continua si no se sabe el total. */
@Composable
private fun SegmentBar(value: Int, total: Int?, modifier: Modifier = Modifier) {
    if (total != null && total in 1..20) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < value) Amber else Border))
            }
        }
    } else ProgressBar(0f, modifier)
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 5.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(3.dp)).background(Border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(3.dp)).background(Amber))
    }
}

// —— Sección "Logros" del perfil ——

/**
 * Logros en el perfil: pasaporte (→ Pasaporte), campeonatos y los recientes (propio) o la
 * carrera en pista (otro oficial; el backend ya recortó a lo público).
 */
@Composable
fun ProfileAchievementsSection(
    repo: AppRepository,
    achievements: Achievements?,
    circuits: List<Circuit>,
    isSelf: Boolean,
    onOpenPassport: () -> Unit,
    onOpenAll: () -> Unit,
) {
    val a = achievements
    if (a == null) {
        // Se cargan en la misma pasada que el perfil: null = sin red ni caché (no "cargando").
        SectionHeader(if (isSelf) "Logros" else "Logros de pista")
        Text(
            "Sin conexión: los logros aparecerán al volver la señal.",
            fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    val earned = a.items.count { it.tier > 0 }
    if (!isSelf && earned == 0 && a.stamps.isEmpty()) {
        SectionHeader("Logros de pista")
        Text("Aún sin logros de pista.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    SectionHeader(
        if (isSelf) "Logros" else "Logros de pista",
        trailing = if (isSelf) "Ver todos ($earned de ${a.items.size})" else "Ver todos",
        onTrailingClick = onOpenAll,
    )
    PassportCard(a, mxCircuits(circuits, a.stamps), isSelf, onOpenPassport)
    if (a.patches.isNotEmpty()) {
        MonoLabel("CAMPEONATOS", TextMut, Modifier.padding(top = 18.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            a.patches.forEach { SeriesPatchView(repo, it) }
        }
    }
    val shown = if (isSelf) {
        // Lo de pista primero (lo que el oficial presume); las primeras veces rellenan.
        a.items.filter { it.tier > 0 && it.key != AchievementRules.PASSPORT_MX }
            .sortedWith(compareBy<Achievement> { it.key in AchievementRules.FIRSTS }.thenByDescending { it.lastReached() })
            .take(4)
    } else {
        a.items.filter { it.tier > 0 && it.key != AchievementRules.PASSPORT_MX }
    }
    if (shown.isNotEmpty()) {
        MonoLabel(if (isSelf) "RECIENTES" else "CARRERA EN PISTA", TextMut, Modifier.padding(top = 18.dp, bottom = 10.dp))
        val cols = if (isSelf) 4 else 3
        shown.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onOpenAll() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AchievementBadge(item, 56.dp)
                        Text(
                            infoOf(item.key).title, fontFamily = PlexSansFamily, fontSize = 11.5.sp, lineHeight = 14.sp,
                            color = TextPrimary, textAlign = TextAlign.Center, maxLines = 2,
                        )
                        if (!isSelf) {
                            summaryShort(item)?.let {
                                Text(it, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (isSelf) {
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            LineIconView(LineIcon.EYE, TextMut, size = 15.dp, modifier = Modifier.padding(top = 1.dp))
            Text(
                "Otros oficiales ven tu pasaporte, campeonatos y logros de pista. Los de uso de la app son solo tuyos.",
                fontFamily = PlexSansFamily, fontSize = 12.sp, lineHeight = 17.sp, color = TextMut,
            )
        }
    }
}

/** Nivel corto para la cuadrícula del perfil ajeno ("Oro · 148", "×9"). */
private fun summaryShort(a: Achievement): String? {
    val metal = metalOf(a.tier)?.name
    return when {
        a.key in AchievementRules.THRESHOLDS && metal != null -> "$metal · ${a.value}"
        a.key == AchievementRules.GRAND_PRIX -> "${a.value} ${if (a.value == 1) "evento" else "eventos"}"
        a.key == AchievementRules.PROMOTION -> a.detail
        else -> null
    }
}

@Composable
private fun PassportCard(a: Achievements, mx: List<Circuit>, self: Boolean, onOpen: () -> Unit) {
    val p = a.item(AchievementRules.PASSPORT_MX) ?: Achievement(AchievementRules.PASSPORT_MX)
    val total = mx.size.takeIf { it > 0 }
    val worked = a.stamps.map { it.circuitId }.toSet()
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape).clickable { onOpen() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Medal(p.tier, 72.dp, number = "${p.value}")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoLabel("PASAPORTE MÉXICO")
                Text(summaryOf(p, total, self), fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi)
                SegmentBar(p.value, total, Modifier.padding(top = 3.dp))
                nextOf(p, self)?.let { Text(it.first, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub) }
            }
            Text("›", color = TextFaint, fontSize = 22.sp)
        }
        if (mx.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                mx.take(12).forEach { c ->
                    val on = c.id in worked
                    val path = c.mainTrazado?.path.orEmpty()
                    if (path.size >= 3) {
                        TrackSilhouette(path, Modifier.size(28.dp), color = if (on) Amber else Color(0xFF4A5263), width = if (on) 1.8.dp else 1.2.dp, padFraction = 0.08f)
                    } else {
                        LineIconView(LineIcon.FLAG, if (on) Amber else Color(0xFF4A5263), size = 28.dp, strokeWidth = 1.6f)
                    }
                }
            }
        }
    }
}

// —— Todos los logros ——

/**
 * Todos los logros de un oficial ([officerId] null = propios), por sección y con quién ve
 * cada una. Tocar uno abre su detalle (el pasaporte abre su pantalla).
 */
@Composable
fun AchievementsScreen(repo: AppRepository, officerId: String?, onBack: () -> Unit, onOpenPassport: () -> Unit) {
    var data by remember(officerId) { mutableStateOf<Achievements?>(null) }
    var circuits by remember { mutableStateOf<List<Circuit>>(emptyList()) }
    var self by remember(officerId) { mutableStateOf(officerId == null) }
    var unavailable by remember(officerId) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Achievement?>(null) }
    val reloader = rememberReloader(repo)
    BackHandler { if (selected != null) selected = null else onBack() }
    suspend fun load() {
        val t = reloader.track { Triple(repo.achievements(officerId), repo.circuits(), repo.myOfficerId()) }
        data = t.value.first
        circuits = t.value.second
        self = officerId == null || officerId == t.value.third
        unavailable = t.value.first == null && t.missed
    }
    LaunchedEffect(officerId, reloader.key) { load() }
    if (unavailable) {
        UnavailableScreen(repo, "Logros", onBack, reloader::retry)
        return
    }
    val a = data
    val mxTotal = mxCircuits(circuits, a?.stamps.orEmpty()).size.takeIf { it > 0 }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(screenBackground())) {
            TopBar(if (self) "LOGROS" else "LOGROS DE PISTA", onBack)
            if (a == null) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth().height(60.dp), corner = 12.dp)
                    Spacer(Modifier.height(20.dp))
                    SkeletonRows(6, 64.dp)
                }
            } else {
                Refreshable(onRefresh = { load() }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                        if (self) {
                            val earned = a.items.count { it.tier > 0 }
                            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("$earned", fontFamily = ArchivoFamily, fontWeight = FontWeight.Black, fontSize = 34.sp, color = TextHi)
                                Text("de ${a.items.size} logros", fontFamily = PlexSansFamily, fontSize = 15.sp, color = TextSub, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            ProgressBar(if (a.items.isEmpty()) 0f else earned.toFloat() / a.items.size, Modifier.padding(top = 6.dp), height = 6.dp)
                        }
                        Section("PASAPORTE", public = true, self) {
                            AchievementRules.PASSPORT.mapNotNull { a.item(it) }.forEach { item ->
                                AchievementRow(item, mxTotal, self) { if (item.key == AchievementRules.PASSPORT_MX) onOpenPassport() else selected = item }
                            }
                        }
                        Section("CAMPEONATOS · ${a.patches.size}", public = true, self) {
                            if (a.patches.isEmpty()) {
                                Text(
                                    "Aparecen al trabajar un evento de un campeonato.",
                                    fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.padding(vertical = 8.dp),
                                )
                            } else {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    a.patches.forEach { SeriesPatchView(repo, it) }
                                }
                                Text(
                                    "El número son temporadas en las que trabajaste un evento.",
                                    fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut, modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                        }
                        Section("CARRERA EN PISTA", public = true, self) {
                            AchievementRules.CAREER.mapNotNull { a.item(it) }.forEach { item -> AchievementRow(item, mxTotal, self) { selected = item } }
                        }
                        if (self) {
                            Section("OPERACIÓN", public = false, self) {
                                AchievementRules.OPERATION.mapNotNull { a.item(it) }.forEach { item -> AchievementRow(item, mxTotal, self) { selected = item } }
                            }
                            val firsts = AchievementRules.FIRSTS.mapNotNull { a.item(it) }
                            Section("PRIMERAS VECES · ${firsts.count { it.tier > 0 }} DE ${firsts.size}", public = false, self) {
                                firsts.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { item ->
                                            Column(
                                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { selected = item }.padding(vertical = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                AchievementBadge(item, 46.dp)
                                                Text(
                                                    infoOf(item.key).title, fontFamily = PlexSansFamily, fontSize = 12.sp, lineHeight = 15.sp,
                                                    color = if (item.tier > 0) TextPrimary else TextMut, textAlign = TextAlign.Center,
                                                )
                                                Text(summaryOf(item, mxTotal, self), fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = TextMut)
                                            }
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
        selected?.let { AchievementDetailSheet(it, mxTotal, self) { selected = null } }
    }
}

@Composable
private fun Section(title: String, public: Boolean, self: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(title)
        if (self) VisibilityNote(public)
    }
    Column(content = content)
}

@Composable
private fun AchievementRow(a: Achievement, mxTotal: Int?, self: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AchievementBadge(a, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(infoOf(a.key).title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (a.tier > 0) TextHi else TextSub)
            Text(summaryOf(a, mxTotal, self), fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 17.sp, color = TextSub)
            nextOf(a, self)?.takeIf { a.tier < 3 }?.let { (text, frac) ->
                ProgressBar(frac, Modifier.padding(top = 4.dp))
                Text(text, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

// —— Detalle de un logro (hoja inferior) ——

@Composable
private fun AchievementDetailSheet(a: Achievement, mxTotal: Int?, self: Boolean, onDismiss: () -> Unit) {
    val info = infoOf(a.key)
    val section = when (a.key) {
        in AchievementRules.PASSPORT -> "PASAPORTE"
        in AchievementRules.CAREER -> "CARRERA EN PISTA"
        in AchievementRules.OPERATION -> "OPERACIÓN"
        else -> "PRIMERA VEZ"
    }
    Box(Modifier.fillMaxSize().background(Color(0xA00A0C10)).clickable { onDismiss() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Panel)
                .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(BorderStrong))
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AchievementBadge(a, 92.dp)
                MonoLabel(section, modifier = Modifier.padding(top = 8.dp))
                Text(info.title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi, textAlign = TextAlign.Center)
                Text(summaryOf(a, mxTotal, self), fontFamily = PlexSansFamily, fontSize = 14.sp, color = TextSub, textAlign = TextAlign.Center)
            }
            AchievementRules.THRESHOLDS[a.key]?.let { th ->
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    th.forEachIndexed { i, n -> TierCell(Modifier.weight(1f), a, i, n, self) }
                }
            }
            Text("Cómo se cuenta", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(top = 18.dp))
            Text(info.how, fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 18.sp, color = TextSub, modifier = Modifier.padding(top = 4.dp))
            if (a.byYear.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp)) {
                    a.byYear.forEachIndexed { i, y ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${y.year}", fontFamily = PlexMonoFamily, fontSize = 13.sp, color = TextSub)
                            Text("${y.value} ${if (y.value == 1) "día" else "días"}", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                        }
                        if (i < a.byYear.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                    }
                }
            }
            if (self) {
                Box(Modifier.padding(top = 14.dp)) { VisibilityNote(AchievementRules.isPublic(a.key)) }
            }
            Box(
                Modifier.padding(top = 18.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, BorderStrong, RoundedCornerShape(14.dp)).clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) { Text("Listo", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary) }
        }
    }
}

@Composable
private fun TierCell(modifier: Modifier, a: Achievement, index: Int, threshold: Int, self: Boolean) {
    val metal = metalOf(index + 1)!!
    val reached = a.tier > index
    val isNext = a.tier == index
    val unit = when (a.key) {
        AchievementRules.PASSPORT_MX -> "circuitos"
        AchievementRules.TRACK_DAYS -> "días"
        AchievementRules.VERSATILITY -> "tipos"
        else -> "años"
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(PanelAlt)
            .border(1.dp, if (isNext) metal.mid else Border, RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TierDot(metal, reached, 30.dp)
        Text(metal.name.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp, color = metal.ink)
        Text("$threshold $unit", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
        val state = when {
            reached -> a.reachedOn.getOrNull(index)?.let { reachedLabel(a, it, self) } ?: "Ganada"
            a.key == AchievementRules.SENIORITY -> a.detail?.toIntOrNull()?.let { "en ${it + threshold}" } ?: "—"
            else -> "faltan ${threshold - a.value}"
        }
        Text(state, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = if (isNext) TextPrimary else TextMut)
    }
}

/** Punto de nivel: metal con ✓ si se alcanzó; aro punteado si no. */
@Composable
private fun TierDot(metal: Metal, reached: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f - 1.dp.toPx()
        if (reached) {
            drawCircle(Brush.linearGradient(listOf(metal.hi, metal.mid, metal.lo), Offset.Zero, Offset(this.size.width, this.size.height)), radius = r)
            val k = this.size.minDimension / 28f
            val p = Path().apply { moveTo(8.5f * k, 14.2f * k); lineTo(12f * k, 17.7f * k); lineTo(19f * k, 10.3f * k) }
            drawPath(p, metal.onMetal, style = Stroke(width = 2.4f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        } else {
            drawCircle(Surface, radius = r)
            drawCircle(metal.mid, radius = r, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }
    }
}

// —— Pasaporte ——

/**
 * Pasaporte de circuitos ([officerId] null = el propio): la medalla de México, los sellos
 * de sus circuitos con el año del primer evento y los de fuera de México. Los sellos son
 * públicos (se ven desde el perfil de otro oficial).
 */
@Composable
fun PassportScreen(repo: AppRepository, officerId: String?, onBack: () -> Unit, onOpenCircuit: (String) -> Unit) {
    var data by remember(officerId) { mutableStateOf<Achievements?>(null) }
    var circuits by remember { mutableStateOf<List<Circuit>>(emptyList()) }
    var self by remember(officerId) { mutableStateOf(officerId == null) }
    var unavailable by remember(officerId) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    suspend fun load() {
        val t = reloader.track { Triple(repo.achievements(officerId), repo.circuits(), repo.myOfficerId()) }
        data = t.value.first
        circuits = t.value.second
        self = officerId == null || officerId == t.value.third
        unavailable = t.value.first == null && t.missed
    }
    LaunchedEffect(officerId, reloader.key) { load() }
    if (unavailable) {
        UnavailableScreen(repo, "Pasaporte", onBack, reloader::retry)
        return
    }
    val a = data
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("PASAPORTE", onBack)
        if (a == null) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                SkeletonBox(Modifier.fillMaxWidth().height(220.dp), corner = 18.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonRows(3, 110.dp, corner = 18.dp)
            }
            return@Column
        }
        val mx = mxCircuits(circuits, a.stamps)
        val stamps = a.stamps.associateBy { it.circuitId }
        val p = a.item(AchievementRules.PASSPORT_MX) ?: Achievement(AchievementRules.PASSPORT_MX)
        val abroad = a.stamps.filter { it.country != AchievementRules.HOME_COUNTRY }
        val byId = circuits.associateBy { it.id }
        Refreshable(onRefresh = { load() }, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RibbonMedal(p.tier, "${p.value}", if (mx.isNotEmpty()) "DE ${mx.size}" else null)
                    MonoLabel("PASAPORTE MÉXICO", modifier = Modifier.padding(top = 8.dp))
                    Text(metalOf(p.tier)?.name ?: "Sin medalla aún", fontFamily = ArchivoFamily, fontWeight = FontWeight.Black, fontSize = 30.sp, color = TextHi)
                    Text(
                        if (mx.isNotEmpty()) "${p.value} de ${mx.size} circuitos de México" else "${p.value} circuitos de México",
                        fontFamily = PlexSansFamily, fontSize = 14.sp, color = TextSub,
                    )
                }
                TierTrack(p, Modifier.padding(top = 22.dp, start = 8.dp, end = 8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoLabel("MÉXICO · ${p.value} DE ${mx.size}")
                    if (self) VisibilityNote(public = true)
                }
                mx.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> StampCell(c, stamps[c.id], Modifier.weight(1f)) { onOpenCircuit(c.id) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                MonoLabel("FUERA DE MÉXICO", modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
                if (abroad.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .border(1.dp, BorderStrong, RoundedCornerShape(16.dp)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LineIconView(LineIcon.GLOBE, TextMut, size = 26.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (self) "Aún no trabajas fuera de México" else "Aún no trabaja fuera de México",
                                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary,
                            )
                            if (self) {
                                Text(
                                    "Cuando trabajes un evento en otro país, su sello aparecerá aquí y ganarás Internacional.",
                                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 18.sp, color = TextMut,
                                )
                            }
                        }
                    }
                } else {
                    abroad.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { s ->
                                val c = byId[s.circuitId]
                                if (c != null) StampCell(c, s, Modifier.weight(1f)) { onOpenCircuit(c.id) } else Spacer(Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Text("Cómo se cuenta", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(top = 24.dp))
                Text(
                    infoOf(AchievementRules.PASSPORT_MX).how,
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 18.sp, color = TextMut, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                )
            }
        }
    }
}

/** Sello de un circuito: aro de tinta con la silueta y el año; sin trabajar, en gris punteado. */
@Composable
private fun StampCell(c: Circuit, stamp: PassportStamp?, modifier: Modifier, onClick: () -> Unit) {
    val on = stamp != null
    val ink = if (on) Amber else Color(0xFF4A5263)
    val ring = if (on) Amber else BorderStrong
    val tilt = if (on) (c.id.hashCode().mod(11) - 5).toFloat() else 0f
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(96.dp).rotate(tilt), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(96.dp)) {
                val r = this.size.minDimension / 2f
                if (on) drawCircle(Amber.copy(alpha = 0.08f), radius = r * 0.94f)
                drawCircle(ring, radius = r * 0.94f, style = Stroke(width = 2.dp.toPx()))
                drawCircle(
                    ring, radius = r * 0.82f,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(if (on) floatArrayOf(2.dp.toPx(), 3.dp.toPx()) else floatArrayOf(3.dp.toPx(), 4.dp.toPx()))),
                )
            }
            val path = c.mainTrazado?.path.orEmpty()
            Box(Modifier.size(52.dp).padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                if (path.size >= 3) {
                    TrackSilhouette(path, Modifier.size(50.dp), color = ink, width = if (on) 2.dp else 1.4.dp, padFraction = 0.04f)
                } else {
                    LineIconView(LineIcon.FLAG, ink, size = 34.dp, strokeWidth = 1.6f)
                }
            }
            if (on) {
                Text(
                    "${stamp!!.firstOn.year}", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    letterSpacing = 1.sp, color = Amber, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }
        Text(
            c.name.removePrefix("Autódromo ").removePrefix("Óvalo ").removePrefix("de "), fontFamily = PlexSansFamily, fontSize = 12.sp, lineHeight = 15.sp,
            color = if (on) TextPrimary else TextMut, textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

/** Bronce — Plata — Oro con la línea de avance; el nivel siguiente, punteado. */
@Composable
private fun TierTrack(p: Achievement, modifier: Modifier) {
    val th = AchievementRules.THRESHOLDS.getValue(p.key)
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val w = this.size.width
            val x0 = w / 6f
            val x2 = w * 5f / 6f
            val y = this.size.height / 2f
            drawLine(BorderStrong, Offset(x0, y), Offset(x2, y), strokeWidth = 2.dp.toPx())
            val reachedX = when (p.tier) { 0 -> x0; 1 -> x0; 2 -> w / 2f; else -> x2 }
            if (p.tier > 1) drawLine(metalOf(p.tier)!!.mid, Offset(x0, y), Offset(reachedX, y), strokeWidth = 2.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            th.forEachIndexed { i, n ->
                val metal = metalOf(i + 1)!!
                val reached = p.tier > i
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    TierDot(metal, reached, 28.dp)
                    Text(metal.name.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp, color = metal.ink)
                    Text(
                        when {
                            p.tier == i + 1 -> "$n · tú"
                            reached -> "$n circuitos"
                            p.tier == i -> "$n · ${if (n - p.value == 1) "falta" else "faltan"} ${n - p.value}"
                            else -> "$n circuitos"
                        },
                        fontFamily = PlexSansFamily, fontSize = 12.sp, color = if (p.tier == i + 1) TextPrimary else TextSub,
                    )
                }
            }
        }
    }
}

/** Medalla grande con listón a cuadros (el motivo de la bandera). Sin medalla: solo el aro. */
@Composable
private fun RibbonMedal(tier: Int, number: String, caption: String?) {
    val metal = metalOf(tier)
    Box(Modifier.size(width = 120.dp, height = 150.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(width = 120.dp, height = 150.dp)) {
            val k = this.size.width / 160f
            if (metal != null) {
                fun strap(vararg pts: Float) = Path().apply {
                    moveTo(pts[0] * k, pts[1] * k)
                    for (i in 2 until pts.size step 2) lineTo(pts[i] * k, pts[i + 1] * k)
                    close()
                }
                listOf(strap(50f, 0f, 78f, 0f, 94f, 72f, 68f, 80f), strap(110f, 0f, 82f, 0f, 66f, 72f, 92f, 80f)).forEach { s ->
                    clipPath(s) {
                        drawRect(OnAmber)
                        val cell = 8f * k
                        var y = 0f
                        var row = 0
                        while (y < 90f * k) {
                            var x = if (row % 2 == 0) 0f else cell
                            while (x < this.size.width) {
                                drawRect(Amber, topLeft = Offset(x, y), size = Size(cell, cell))
                                x += cell * 2
                            }
                            y += cell; row++
                        }
                    }
                    drawPath(s, OnAmber, style = Stroke(width = 2f * k))
                }
                drawCircle(Brush.linearGradient(listOf(metal.hi, metal.mid, metal.lo), Offset(16f * k, 66f * k), Offset(144f * k, 194f * k)), radius = 64f * k, center = Offset(80f * k, 130f * k))
                drawCircle(Surface, radius = 50f * k, center = Offset(80f * k, 130f * k))
            } else {
                drawCircle(PanelAlt, radius = 60f * k, center = Offset(80f * k, 130f * k))
                drawCircle(
                    BorderStrong, radius = 60f * k, center = Offset(80f * k, 130f * k),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
        }
        Column(
            Modifier.size(width = 120.dp, height = 105.dp).clipToBounds(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(number, fontFamily = ArchivoFamily, fontWeight = FontWeight.Black, fontSize = 36.sp, lineHeight = 38.sp, color = metal?.ink ?: TextSub)
            caption?.let { Text(it, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 2.sp, color = metal?.mid ?: TextMut) }
        }
    }
}

// —— Detalle de circuito: "Tu historia aquí" ——

/**
 * Tarjeta del detalle de un circuito donde ya trabajaste: cuántos eventos y desde cuándo, y
 * cuántos puestos del trazado elegido ya conoces (el "Asignado antes" que ya existe, contado).
 */
@Composable
fun CircuitHistoryCard(stamp: PassportStamp, known: Int, total: Int, trazadoName: String?, onOpenPassport: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Border, shape)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkedMark(30.dp, ring = Panel)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Trabajaste aquí", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi)
                val eventos = if (stamp.events == 1) "1 evento" else "${stamp.events} eventos"
                val last = stamp.lastEventName?.takeIf { stamp.events > 1 }?.let { " · último: $it" }.orEmpty()
                Text("$eventos desde ${stamp.firstOn.year}$last", fontFamily = PlexSansFamily, fontSize = 12.5.sp, lineHeight = 17.sp, color = TextSub)
            }
        }
        if (total > 0) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Conoces $known de $total puestos", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    trazadoName?.let { MonoLabel(it.uppercase(), TextMut) }
                }
                ProgressBar(known.toFloat() / total, height = 6.dp)
                Text(
                    "En ámbar, los puestos donde ya estuviste. Cambia de trazado para ver los demás.",
                    fontFamily = PlexSansFamily, fontSize = 12.sp, lineHeight = 17.sp, color = TextMut,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
        Row(
            Modifier.fillMaxWidth().clickable { onOpenPassport() }.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Ver tu pasaporte", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber)
            Text("›", fontFamily = PlexSansFamily, fontSize = 15.sp, color = Amber)
        }
    }
}
