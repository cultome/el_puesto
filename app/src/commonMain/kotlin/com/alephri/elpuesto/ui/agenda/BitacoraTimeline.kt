package com.alephri.elpuesto.ui.agenda

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.components.ImageLoad
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.rememberRemoteImage
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * Bitácora de viaje: los ítems (planeación + fotos + notas) en un solo hilo cronológico
 * agrupado por día. Durante el día actual se muestra el marcador "AHORA" y lo futuro se
 * atenúa. Los ítems personales se editan al tocarlos.
 */
@Composable
fun BitacoraTimeline(
    repo: AppRepository,
    items: List<TripItem>,
    onOpenEditor: (tripItemId: String, presetEventId: String?) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "Aún no hay nada en la bitácora.",
            fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    val now = remember { Clock.System.now() }
    val today = now.toLocalDateTime(MX).date
    val dated = items.filter { it.at != null }.sortedBy { it.at }
    val undated = items.filter { it.at == null }
    val byDay = dated.groupBy { it.at!!.toLocalDateTime(MX).date }

    Column(Modifier.fillMaxWidth()) {
        byDay.forEach { (day, dayItems) ->
            val isToday = day == today
            DayHeader(day, isToday)
            val (past, future) = if (isToday) dayItems.partition { it.at!! <= now } else {
                if (day < today) dayItems to emptyList() else emptyList<TripItem>() to dayItems
            }
            past.forEachIndexed { i, item ->
                TimelineRow(repo, item, dim = false, last = future.isEmpty() && i == past.lastIndex, onOpenEditor)
            }
            if (isToday) NowMarker(now)
            future.forEachIndexed { i, item ->
                TimelineRow(repo, item, dim = day >= today, last = i == future.lastIndex, onOpenEditor)
            }
        }
        if (undated.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp)) {
                Text(
                    "SIN FECHA", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, color = Amber, letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Border))
            }
            undated.forEachIndexed { i, item ->
                TimelineRow(repo, item, dim = false, last = i == undated.lastIndex, onOpenEditor)
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, isToday: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp)) {
        Text(
            dayLabel(day) + if (isToday) " · HOY" else "",
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            color = if (isToday) Live else Amber, letterSpacing = 1.sp,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Border))
    }
}

@Composable
private fun NowMarker(now: Instant) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.clip(RoundedCornerShape(100.dp))
                .background(Live.copy(alpha = 0.12f))
                .border(1.dp, Live.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Live))
            Text(
                "AHORA · ${hhmmInstant(now)}", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold,
                fontSize = 10.sp, color = Live, letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(2.dp)).background(Live.copy(alpha = 0.35f)))
    }
}

@Composable
private fun TimelineRow(
    repo: AppRepository,
    item: TripItem,
    dim: Boolean,
    last: Boolean,
    onOpenEditor: (String, String?) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            item.at?.let { hhmmInstant(it) } ?: "—",
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 11.5.sp,
            color = if (dim) TextFaint else TextSub,
            modifier = Modifier.width(44.dp).padding(top = 1.dp),
        )
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            val dotColor = dotColorFor(item).let { if (dim) it.copy(alpha = 0.4f) else it }
            val filled = !item.personal
            Box(
                Modifier.padding(top = 1.dp).size(13.dp).clip(CircleShape)
                    .background(if (filled) dotColor else Color.Transparent)
                    .border(2.5.dp, dotColor, CircleShape),
            )
            if (!last) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(2.dp).weight(1f).clip(RoundedCornerShape(2.dp)).background(Border))
            }
        }
        Column(
            Modifier.weight(1f).padding(start = 12.dp, bottom = 18.dp)
                .clickable(enabled = item.personal) { onOpenEditor(item.id, item.eventId) },
        ) {
            when (item.kind) {
                TripItemKind.PHOTO -> PhotoCard(repo, item)
                TripItemKind.NOTE -> NoteCard(item)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = if (dim) TextMut else TextPrimary,
                        )
                        if (!item.personal) SysChip()
                    }
                    item.detail?.let {
                        Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = if (dim) TextFaint else TextMut)
                    }
                    KindChipSmall(item.kind, dim)
                }
            }
        }
    }
}

@Composable
internal fun PhotoCard(repo: AppRepository, item: TripItem) {
    val img = rememberRemoteImage(repo, "/images/trip/${item.id}/full")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(Panel).border(1.dp, Border, RoundedCornerShape(13.dp)),
    ) {
        when (img) {
            is ImageLoad.Ready -> Image(
                bitmap = img.bitmap, contentDescription = item.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            ImageLoad.Loading -> SkeletonBox(Modifier.fillMaxWidth().height(160.dp), corner = 0.dp)
            else -> Box(
                Modifier.fillMaxWidth().height(90.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Foto sin conexión", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextFaint)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp, color = TextPrimary, modifier = Modifier.weight(1f),
            )
            Text(
                "FOTO", fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium, fontSize = 9.sp,
                color = TextSub, letterSpacing = 0.4.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF2B3040)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
internal fun NoteCard(item: TripItem) {
    Column {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Panel).border(1.dp, Border, RoundedCornerShape(13.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
        ) {
            Text(
                "“${item.title}”", fontFamily = PlexSansFamily, fontStyle = FontStyle.Italic,
                fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "NOTA", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.sp,
            color = TextSub, letterSpacing = 0.4.sp,
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF2B3040)).padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SysChip() {
    Text(
        "DEL EVENTO", fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium, fontSize = 8.5.sp,
        color = TextFaint, letterSpacing = 0.3.sp,
        modifier = Modifier.border(1.dp, Border, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun KindChipSmall(kind: TripItemKind, dim: Boolean) {
    val color = when (kind) {
        TripItemKind.TRANSPORT -> Travel
        TripItemKind.LODGING -> Live
        else -> Amber
    }
    Spacer(Modifier.height(6.dp))
    Text(
        kind.display().uppercase(),
        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.sp,
        color = if (dim) color.copy(alpha = 0.5f) else color, letterSpacing = 0.4.sp,
        modifier = Modifier.clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = if (dim) 0.08f else 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

private fun dotColorFor(item: TripItem): Color = when {
    !item.personal -> Amber
    item.kind == TripItemKind.TRANSPORT -> Travel
    item.kind == TripItemKind.LODGING -> Live
    item.kind == TripItemKind.REMINDER -> Amber
    else -> TextSub // fotos y notas: neutral
}

private val MX = TimeZone.of("America/Mexico_City")
private val MESES_B = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private val DIAS_B = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")

private fun dayLabel(d: LocalDate) =
    "${DIAS_B[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth} ${MESES_B[d.monthNumber - 1]}".uppercase()

private fun hhmmInstant(at: Instant): String {
    val dt = at.toLocalDateTime(MX)
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
