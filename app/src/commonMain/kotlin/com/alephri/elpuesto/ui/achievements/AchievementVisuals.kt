package com.alephri.elpuesto.ui.achievements

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Achievement
import com.alephri.elpuesto.model.AchievementRules
import com.alephri.elpuesto.model.SeriesPatch
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.RemoteImageBox
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.Surface
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextSub
import kotlinx.datetime.LocalDate

// —— Lenguaje visual de los logros (docs/design/Logros/, lámina "Insignias") ——
// La forma dice qué es: MEDALLA = tiene niveles; HEXÁGONO = hito o contador;
// PARCHE = campeonato; SELLO = primera vez. El metal dice cuánto llevas.

/** Metal de un nivel: brillo, medio y sombra del degradado + tinta para glifos/números. */
internal data class Metal(val hi: Color, val mid: Color, val lo: Color, val ink: Color, val onMetal: Color, val name: String)

private val METALS = listOf(
    Metal(Color(0xFFEDB48A), Color(0xFFC27A4A), Color(0xFF7A4526), Color(0xFFEDB48A), Color(0xFF2A1609), "Bronce"),
    Metal(Color(0xFFF4F7FB), Color(0xFFBAC3D0), Color(0xFF6E7888), Color(0xFFE6EBF2), Color(0xFF1A1F29), "Plata"),
    Metal(Color(0xFFFFE08A), Amber, Color(0xFFA8700F), Color(0xFFFFD36B), OnAmber, "Oro"),
)

/** Metal del nivel 1..3 (null = sin ganar). */
internal fun metalOf(tier: Int): Metal? = METALS.getOrNull(tier - 1)

internal enum class BadgeShape { MEDAL, HEX, FIRST }

/** Presentación de cada logro: nombre, forma, glifo y cómo se cuenta. */
internal data class AchievementInfo(val title: String, val shape: BadgeShape, val icon: LineIcon, val how: String)

internal fun infoOf(key: String): AchievementInfo = when (key) {
    AchievementRules.PASSPORT_MX -> AchievementInfo(
        "Pasaporte México", BadgeShape.MEDAL, LineIcon.PIN,
        "Cada circuito de México donde trabajaste al menos un día de un evento terminado. Si tu jefe de puesto te marcó ausente todos los días, ese evento no cuenta.",
    )
    AchievementRules.INTERNATIONAL -> AchievementInfo("Internacional", BadgeShape.HEX, LineIcon.GLOBE, "Trabaja un evento en un circuito fuera de México.")
    AchievementRules.TRACK_DAYS -> AchievementInfo(
        "Días en pista", BadgeShape.MEDAL, LineIcon.CALENDAR,
        "Cada día de tus eventos terminados. Si tu jefe de puesto te marcó ausente en el pase de lista, ese día no cuenta.",
    )
    AchievementRules.VERSATILITY -> AchievementInfo(
        "Versatilidad", BadgeShape.MEDAL, LineIcon.LAYERS,
        "Tipos de rol que has cubierto: " + AchievementRules.ROLE_FAMILIES.joinToString(", ") { it.lowercase() } + ".",
    )
    AchievementRules.PROMOTION -> AchievementInfo(
        "Ascenso", BadgeShape.HEX, LineIcon.RANK,
        "Tu primera vez como jefe de posición: Chief Post Marshal, Jefe Telehandler o Jefe IFRT.",
    )
    AchievementRules.SENIORITY -> AchievementInfo("Antigüedad", BadgeShape.MEDAL, LineIcon.STAR, "Años en pista desde tu «Activo desde».")
    AchievementRules.GRAND_PRIX -> AchievementInfo("Grandes Premios", BadgeShape.HEX, LineIcon.TROPHY, "Eventos de Fórmula 1 que trabajaste.")
    AchievementRules.READY -> AchievementInfo(
        "Listo para pista", BadgeShape.HEX, LineIcon.SHIELD_PLUS,
        "Foto de perfil y datos de emergencia completos: contacto, teléfono y tipo de sangre. Le sirven a tu jefe de puesto.",
    )
    AchievementRules.CLEAN_POST -> AchievementInfo(
        "Puesto impecable", BadgeShape.HEX, LineIcon.CLIPBOARD_CHECK,
        "Tu posición completó el checklist todos los días de un evento. Lo gana todo el puesto.",
    )
    AchievementRules.RECRUITER -> AchievementInfo("Reclutador", BadgeShape.HEX, LineIcon.USER_PLUS, "Oficiales que invitaste y ya tienen su cuenta activa.")
    AchievementRules.FIRST_MESSAGE -> AchievementInfo("Primer mensaje", BadgeShape.FIRST, LineIcon.CHAT, "Envía un mensaje en cualquier chat.")
    AchievementRules.FIRST_CHAT_PHOTO -> AchievementInfo("Primera foto en un chat", BadgeShape.FIRST, LineIcon.CAMERA, "Comparte una foto en un chat.")
    AchievementRules.FIRST_LOG_PHOTO -> AchievementInfo("Primera foto en tu bitácora", BadgeShape.FIRST, LineIcon.IMAGE, "Toma una foto para la bitácora de un evento.")
    AchievementRules.FIRST_LOG_NOTE -> AchievementInfo("Primera nota en tu bitácora", BadgeShape.FIRST, LineIcon.NOTE, "Escribe una nota rápida en la bitácora de un evento.")
    AchievementRules.FIRST_TRIP -> AchievementInfo("Primer viaje planeado", BadgeShape.FIRST, LineIcon.SUITCASE, "Agrega un transporte o un hospedaje a tu agenda.")
    AchievementRules.FIRST_REMINDER -> AchievementInfo("Primer recordatorio", BadgeShape.FIRST, LineIcon.BELL, "Crea un recordatorio en tu agenda.")
    else -> AchievementInfo(key, BadgeShape.HEX, LineIcon.STAR, "")
}

private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

internal fun monthYear(d: LocalDate) = "${MESES[d.monthNumber - 1]} ${d.year}"
internal fun dayMonthYear(d: LocalDate) = "${d.dayOfMonth} ${MESES[d.monthNumber - 1]} ${d.year}"

/**
 * Cuándo se alcanzó un logro. Del pasaporte de OTRO oficial el servidor solo manda el año
 * (fecha = 1 de enero): se muestra solo el año, nunca un "ene" inventado.
 */
internal fun reachedLabel(a: Achievement, d: LocalDate, self: Boolean): String =
    if (!self && a.key in AchievementRules.PASSPORT) "${d.year}" else monthYear(d)

private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

/** Línea bajo el título: dónde vas ("Plata · 5 de 9 circuitos") o qué hace falta. */
internal fun summaryOf(a: Achievement, mxTotal: Int?, self: Boolean = true): String {
    val metal = metalOf(a.tier)?.name
    fun withMetal(s: String) = if (metal != null && a.key in AchievementRules.THRESHOLDS) "$metal · $s" else s
    val on = a.reachedOn.firstOrNull()
    return when (a.key) {
        AchievementRules.PASSPORT_MX -> withMetal(if (mxTotal != null) "${a.value} de $mxTotal circuitos" else "${a.value} ${plural(a.value, "circuito", "circuitos")} de México")
        AchievementRules.TRACK_DAYS -> withMetal("${a.value} ${plural(a.value, "día", "días")}")
        AchievementRules.VERSATILITY -> withMetal("${a.value} de ${AchievementRules.ROLE_FAMILIES.size} tipos de rol")
        AchievementRules.SENIORITY ->
            if (a.detail == null) "Sin «Activo desde» en tu perfil" else withMetal("${a.value} ${plural(a.value, "año", "años")} en pista")
        AchievementRules.PROMOTION ->
            if (a.tier > 0) "Primera vez como ${a.detail ?: "jefe"}" + (on?.let { " · ${monthYear(it)}" } ?: "") else "Sé jefe de posición en un evento"
        AchievementRules.INTERNATIONAL ->
            if (a.tier > 0) (if (self) "Trabajaste fuera de México" else "Trabajó fuera de México") + (on?.let { " · ${reachedLabel(a, it, self)}" } ?: "")
            else if (self) "Trabaja un evento fuera de México" else "Aún no trabaja fuera de México"
        AchievementRules.GRAND_PRIX ->
            if (a.tier > 0) "${a.value} ${plural(a.value, "evento", "eventos")} de Fórmula 1" else "Trabaja un evento de Fórmula 1"
        AchievementRules.READY -> if (a.tier > 0) "Foto y datos de emergencia completos" else "Completa tu foto y tus datos de emergencia"
        AchievementRules.CLEAN_POST ->
            if (a.tier > 0) "Tu puesto completó el checklist todos los días de ${a.value} ${plural(a.value, "evento", "eventos")}"
            else "Completa el checklist con tu puesto todos los días de un evento"
        AchievementRules.RECRUITER ->
            if (a.tier > 0) "${a.value} ${plural(a.value, "oficial que invitaste ya está activo", "oficiales que invitaste ya están activos")}"
            else "Invita a un oficial desde Configuración"
        else -> on?.let { dayMonthYear(it) } ?: "Pendiente"
    }
}

/**
 * Siguiente nivel de un logro con niveles: (texto, avance 0..1) o null si no aplica.
 * [self] = hablarle al titular ("te faltan") o de otro oficial ("le faltan").
 */
internal fun nextOf(a: Achievement, self: Boolean = true): Pair<String, Float>? {
    val th = AchievementRules.THRESHOLDS[a.key] ?: return null
    if (a.key == AchievementRules.SENIORITY && a.detail == null) return null
    val next = th.getOrNull(a.tier) ?: return "Nivel máximo" to 1f
    val name = METALS[a.tier].name
    val text = if (a.key == AchievementRules.SENIORITY) {
        val year = a.detail?.toIntOrNull()?.plus(next)
        "$name a los $next años" + (year?.let { " · en $it" } ?: "")
    } else "$name a los $next · ${if (self) "te" else "le"} ${plural(next - a.value, "falta", "faltan")} ${next - a.value}"
    return text to (a.value.toFloat() / next).coerceIn(0f, 1f)
}

/** Medalla (logros con niveles). [tier] 0 = sin ganar (aro punteado). Número o glifo al centro. */
@Composable
internal fun Medal(tier: Int, size: Dp, number: String? = null, icon: LineIcon? = null) {
    val metal = metalOf(tier)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            if (metal != null) {
                drawCircle(Brush.linearGradient(listOf(metal.hi, metal.mid, metal.lo), Offset.Zero, Offset(this.size.width, this.size.height)), radius = r)
                drawCircle(Surface, radius = r * 24f / 32f)
            } else {
                drawCircle(PanelAlt, radius = r * 30f / 32f)
                drawCircle(
                    BorderStrong, radius = r * 30f / 32f,
                    style = Stroke(width = r / 16f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 5f / 32f, r * 4f / 32f))),
                )
            }
        }
        val ink = metal?.ink ?: TextSub
        when {
            number != null -> Text(
                number, fontFamily = ArchivoFamily, fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.34f).sp, color = ink, textAlign = TextAlign.Center,
            )
            icon != null -> LineIconView(icon, ink, size = size * (24f / 64f), strokeWidth = 2f)
        }
    }
}

/** Hexágono (hito o contador). [count] = "×4" en la esquina. */
@Composable
internal fun HexBadge(earned: Boolean, icon: LineIcon, size: Dp, count: String? = null) {
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val k = this.size.minDimension / 64f
            val p = Path().apply {
                moveTo(32f * k, 3f * k); lineTo(57f * k, 17.5f * k); lineTo(57f * k, 46.5f * k)
                lineTo(32f * k, 61f * k); lineTo(7f * k, 46.5f * k); lineTo(7f * k, 17.5f * k); close()
            }
            if (earned) {
                drawPath(p, Amber.copy(alpha = 0.12f))
                drawPath(p, Amber, style = Stroke(width = 2f * k, join = StrokeJoin.Round))
            } else {
                drawPath(p, PanelAlt)
                drawPath(p, BorderStrong, style = Stroke(width = 2f * k, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * k, 4f * k))))
            }
        }
        LineIconView(icon, if (earned) Amber else TextMut, size = size * (24f / 64f), modifier = Modifier.align(Alignment.Center))
        if (count != null) {
            Text(
                count, fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OnAmber,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp)
                    .clip(RoundedCornerShape(100.dp)).background(Amber).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Sello de primera vez: círculo con el glifo (punteado = pendiente). */
@Composable
internal fun FirstStamp(earned: Boolean, icon: LineIcon, size: Dp = 46.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f - 1.dp.toPx()
            if (earned) {
                drawCircle(Amber.copy(alpha = 0.10f), radius = r)
                drawCircle(Amber, radius = r, style = Stroke(width = 1.5.dp.toPx()))
            } else {
                drawCircle(PanelAlt, radius = r)
                drawCircle(BorderStrong, radius = r, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
        }
        LineIconView(icon, if (earned) Amber else TextMut, size = size * 0.48f)
    }
}

/** La insignia que corresponde a un logro según su forma. */
@Composable
internal fun AchievementBadge(a: Achievement, size: Dp) {
    val info = infoOf(a.key)
    when (info.shape) {
        BadgeShape.MEDAL -> if (a.key == AchievementRules.PASSPORT_MX) Medal(a.tier, size, number = "${a.value}") else Medal(a.tier, size, icon = info.icon)
        BadgeShape.HEX -> HexBadge(
            a.tier > 0, info.icon, size,
            count = a.value.takeIf { a.tier > 0 && a.key in COUNTERS && it > 0 }?.let { "×$it" },
        )
        BadgeShape.FIRST -> FirstStamp(a.tier > 0, info.icon, size)
    }
}

/** Hexágonos que suman veces (llevan contador en la esquina). */
private val COUNTERS = setOf(AchievementRules.GRAND_PRIX, AchievementRules.CLEAN_POST, AchievementRules.RECRUITER)

/** Marca "trabajaste aquí" del catálogo: sello ámbar con ✓ (solo sí/no). */
@Composable
internal fun WorkedMark(size: Dp = 24.dp, ring: Color = PanelAlt) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(ring, radius = r)
        drawCircle(Amber, radius = r - 2.dp.toPx())
        val k = this.size.minDimension / 22f
        val p = Path().apply { moveTo(6.5f * k, 11.2f * k); lineTo(9.5f * k, 14.2f * k); lineTo(15.5f * k, 7.8f * k) }
        drawPath(p, OnAmber, style = Stroke(width = 2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Texto del parche cuando la serie no tiene logo: "F1", "NASCAR"+"MÉXICO", "MRC". */
private fun patchText(name: String): Pair<String, String> {
    val words = name.trim().split(Regex("\\s+"))
    return when {
        name.length <= 6 -> name to ""
        words.size > 1 && words[0].all { !it.isLetter() || it.isUpperCase() } -> words[0] to words.drop(1).joinToString(" ").uppercase()
        words.size > 1 -> words.joinToString("") { it.take(1).uppercase() }.take(4) to ""
        else -> name.take(6) to ""
    }
}

/** Parche de campeonato: tela con costura punteada; el logo real si lo hay. */
@Composable
internal fun SeriesPatchView(repo: AppRepository, patch: SeriesPatch, width: Dp = 78.dp) {
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(width, 54.dp).clip(RoundedCornerShape(12.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width, 54.dp)) {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    Amber.copy(alpha = 0.55f), topLeft = Offset(inset, inset),
                    size = Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
                    cornerRadius = CornerRadius(9.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            }
            val (main, sub) = remember(patch.name) { patchText(patch.name) }
            RemoteImageBox(repo, patch.emblemUrl, size = 34.dp, shape = RoundedCornerShape(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        main, fontFamily = ArchivoFamily, fontWeight = FontWeight.Black, color = TextHi,
                        fontSize = if (main.length <= 3) 19.sp else 13.sp, maxLines = 1,
                    )
                    if (sub.isNotEmpty()) {
                        Text(sub, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 8.5.sp, color = Amber, letterSpacing = 1.sp, maxLines = 1)
                    }
                }
            }
        }
        Text(
            "${patch.seasons} ${plural(patch.seasons, "temporada", "temp.")}",
            fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextSub, maxLines = 1,
        )
    }
}
