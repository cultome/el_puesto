package com.alephri.elpuesto.ui.convocatorias

import com.alephri.elpuesto.ui.platform.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import com.alephri.elpuesto.ui.components.MarkdownText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.ConvocatoriaStatus
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

// ————————————————————— Lista / Historial (CV-1 / CV-3) —————————————————————

@Composable
fun ConvocatoriaListScreen(
    repo: AppRepository,
    past: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenHistorial: (() -> Unit)? = null,
) {
    var list by remember(past) { mutableStateOf<List<Convocatoria>?>(null) }
    var unavailable by remember(past) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    LaunchedEffect(past, reloader.key) {
        val t = reloader.track { repo.convocatorias(past) }
        list = t.value
        unavailable = t.value.isEmpty() && t.missed
    }
    if (unavailable) {
        UnavailableScreen(repo, if (past) "Historial" else "Convocatorias", onBack, reloader::retry)
        return
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                if (past) "HISTORIAL" else "CONVOCATORIAS",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = TextHi,
                letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            if (!past && onOpenHistorial != null) {
                Text(
                    "Historial", color = Amber, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenHistorial() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.size(34.dp))
            }
        }

        Refreshable(onRefresh = { list = repo.convocatorias(past) }, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            val data = list
            if (data != null) {
                Text(
                    if (past) "Cerradas y de fechas anteriores" else "${data.size} abiertas · re-publicadas del sistema externo",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                )
                Spacer(Modifier.height(14.dp))
                if (data.isEmpty()) {
                    if (past) EmptyState(LineIcon.MEGAPHONE, "Sin convocatorias pasadas", "Aquí quedan las convocatorias cerradas y las de fechas anteriores.")
                    else EmptyState(LineIcon.MEGAPHONE, "No hay convocatorias abiertas", "Cuando se publique una nueva, aparecerá aquí.")
                }
                data.forEach { ConvocatoriaRow(it, past) { onOpen(it.id) } }
            } else {
                SkeletonRows(3, 104.dp, corner = 16.dp)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

@Composable
private fun ConvocatoriaRow(c: Convocatoria, past: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(Panel).border(1.dp, Border, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.eventName, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi)
            Spacer(Modifier.height(3.dp))
            Text("${c.location} · ${c.eventDate}", fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (past) {
                    Tag("Cerrada")
                    if (c.participated) Tag("Participaste", container = Live.copy(alpha = 0.14f), contentColor = Live, border = Live.copy(alpha = 0.3f))
                } else {
                    val d = daysLabel(c.registrationCloseAt)
                    Text(d, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (isUrgent(c.registrationCloseAt)) Danger else Amber)
                    Text("· Cupo ${c.cupo}", fontFamily = PlexMonoFamily, fontSize = 11.5.sp, color = TextSub)
                }
            }
        }
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
}

// ————————————————————— Detalle (CV-2) —————————————————————

@Composable
fun ConvocatoriaDetailScreen(
    repo: AppRepository,
    convocatoriaId: String,
    onBack: () -> Unit,
    onOpenCircuit: (String) -> Unit = {},
) {
    val openUrl = com.alephri.elpuesto.ui.platform.rememberUrlOpener()
    val reminders = com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.reminders
    var c by remember(convocatoriaId) { mutableStateOf<Convocatoria?>(null) }
    // Nombre del circuito del catálogo (si la convocatoria está ligada y sigue vivo).
    var circuitName by remember(convocatoriaId) { mutableStateOf<String?>(null) }
    var unavailable by remember(convocatoriaId) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    LaunchedEffect(convocatoriaId, reloader.key) {
        val t = reloader.track {
            val conv = repo.convocatoria(convocatoriaId)
            conv to conv?.circuitId?.let { id -> repo.circuits().firstOrNull { it.id == id }?.name }
        }
        val conv = t.value.first
        c = conv
        unavailable = conv == null && t.missed
        circuitName = t.value.second
    }
    if (unavailable) {
        UnavailableScreen(repo, "Convocatoria", onBack, reloader::retry)
        return
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text("CONVOCATORIA", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(34.dp))
        }
        val conv = c ?: return@Column
        val open = conv.status == ConvocatoriaStatus.OPEN
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Tag(if (open) "Abierta" else "Cerrada", container = (if (open) Live else TextMut).copy(alpha = 0.14f), contentColor = if (open) Live else TextMut, border = (if (open) Live else TextMut).copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))
            Text(conv.eventName, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi)
            Spacer(Modifier.height(4.dp))
            Text(conv.location, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextSub)
            Spacer(Modifier.height(4.dp))
            Text("Re-publicada desde el sistema externo", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)

            SectionHeader("Datos del evento")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(16.dp)).padding(16.dp),
            ) {
                DataRow("Fecha del evento", conv.eventDate)
                DataRow("Lugar", conv.location)
                // Navegación conectada: la sede ligada al catálogo abre el circuito.
                val circuitId = conv.circuitId
                if (circuitId != null && circuitName != null) {
                    CircuitRow(circuitName!!) { onOpenCircuit(circuitId) }
                }
                DataRow("Fin de inscripciones", "${closeDateLabel(conv.registrationCloseAt)}  ·  ${daysLabel(conv.registrationCloseAt).lowercase()}")
                DataRow("Cupo", "${conv.cupo} oficiales", last = true)
            }

            SectionHeader("Indicaciones generales")
            if (conv.indicacionesMarkdown.isBlank()) {
                Text("Sin indicaciones.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
            } else {
                MarkdownText(conv.indicacionesMarkdown)
            }

            Spacer(Modifier.height(24.dp))
            // Solo enlaces web (http/https): el enlace viene de un sistema externo y otro
            // esquema (intent:, file:, elpuesto:, javascript:…) no debe abrirse desde aquí.
            val applyUri = conv.externalApplyUrl?.let(::webUriOrNull)
            if (open && applyUri != null) {
                PrimaryButton("Postularme ↗", onClick = {
                    openUrl(applyUri)
                })
                Spacer(Modifier.height(10.dp))
            }
            // Recordatorio local: se programa un día antes del cierre (o de inmediato si
            // falta menos); tocar la notificación regresa a este detalle.
            if (open && reminders.supported) {
                var reminded by remember(conv.id) {
                    mutableStateOf(reminders.isConvocatoriaSet(conv.id))
                }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (reminded) Amber else BorderStrong, RoundedCornerShape(14.dp))
                        .clickable {
                            if (reminded) {
                                reminders.cancelConvocatoria(conv.id)
                            } else {
                                reminders.setConvocatoria(conv)
                            }
                            reminded = reminders.isConvocatoriaSet(conv.id)
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (reminded) "✓ Te recordaremos antes del cierre" else "Recordarme antes del cierre",
                        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
                        color = if (reminded) Amber else TextSub,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "La postulación se realiza en el sistema externo de convocatorias, fuera de esta app.",
                fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Fila del circuito ligado: como DataRow pero clickeable, con chevron (abre el detalle). */
@Composable
private fun CircuitRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Circuito", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.weight(1f))
        Text(name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber, modifier = Modifier.weight(1.3f))
        Text("›", color = TextFaint, fontSize = 18.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun DataRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.weight(1f))
        Text(value, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1.3f))
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

// ————————————————————— Fechas —————————————————————

private val MX = TimeZone.of("America/Mexico_City")
private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

private fun daysDiff(closeAt: Instant): Int =
    Clock.System.now().toLocalDateTime(MX).date.daysUntil(closeAt.toLocalDateTime(MX).date)

private fun daysLabel(closeAt: Instant): String = when (val d = daysDiff(closeAt)) {
    in Int.MIN_VALUE..-1 -> "Inscripciones cerradas"
    0 -> "Cierran hoy"
    1 -> "Cierran mañana"
    else -> "Inscripciones cierran en $d días"
}

private fun isUrgent(closeAt: Instant): Boolean = daysDiff(closeAt) in 0..2

private fun closeDateLabel(closeAt: Instant): String {
    val dt = closeAt.toLocalDateTime(MX)
    val h = dt.hour.toString().padStart(2, '0')
    val m = dt.minute.toString().padStart(2, '0')
    return "${dt.dayOfMonth} ${MESES[dt.monthNumber - 1]}, $h:$m"
}

/** El enlace como Uri solo si es web (http/https con host); cualquier otro esquema = null. */
internal fun webUriOrNull(raw: String): String? {
    val t = raw.trim()
    val lower = t.lowercase()
    val rest = when {
        lower.startsWith("https://") -> t.substring(8)
        lower.startsWith("http://") -> t.substring(7)
        else -> return null
    }
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    if (host.isBlank() || t.any { it.isWhitespace() || it.code < 0x20 }) return null
    return t
}
