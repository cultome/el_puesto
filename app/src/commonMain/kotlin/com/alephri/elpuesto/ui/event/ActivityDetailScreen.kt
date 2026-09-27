package com.alephri.elpuesto.ui.event

import com.alephri.elpuesto.ui.components.rememberReloader
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.LoadingBox
import com.alephri.elpuesto.ui.format.stateLabel
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

/**
 * Detalle extendido de una actividad del cronograma. Por ahora muestra los campos
 * disponibles de [Session]; participantes/notas de dirección quedan pendientes de
 * definir el modelo (ver memoria del proyecto).
 */
@Composable
fun ActivityDetailScreen(
    repo: AppRepository,
    sessionId: String,
    onBack: () -> Unit,
    onOpenChampionship: (String) -> Unit = {},
) {
    var session by remember(sessionId) { mutableStateOf<Session?>(null) }
    var championship by remember(sessionId) { mutableStateOf<Championship?>(null) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(sessionId, reloader.key) {
        session = repo.session(sessionId)
        // Navegación conectada: la categoría de la actividad lleva a su campeonato.
        // La sesión guarda solo el NOMBRE de la categoría; se busca en el catálogo.
        val cat = session?.category?.trim()
        if (!cat.isNullOrEmpty()) {
            // Varias temporadas pueden tener la categoría (Fórmula E 2025-26 y 2026-27): se
            // prefiere la que cubre el día de la actividad.
            val day = session?.day
            val cands = reloader.track {
                repo.championships().filter { ch -> repo.categories(ch.id).any { it.name.equals(cat, ignoreCase = true) } }
            }.value
            championship = cands.firstOrNull { c ->
                day != null && c.startsOn != null && c.endsOn != null && day in c.startsOn!!..c.endsOn!!
            } ?: cands.firstOrNull()
        }
    }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                "ACTIVIDAD",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(34.dp))
        }

        val s = session
        if (s == null) {
            LoadingBox(Modifier.weight(1f).fillMaxWidth())
        } else {
            val live = s.status == EventStatus.LIVE
            Column(Modifier.padding(horizontal = 22.dp).padding(top = 18.dp)) {
                Text(s.category.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Amber, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(s.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background((if (live) Live else TextMut).copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(s.stateLabel(), fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (live) Live else TextSub)
                }

                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PanelElevA)
                        .border(1.dp, BorderStrong, RoundedCornerShape(18.dp)).padding(18.dp),
                ) {
                    InfoRow("Fecha", longDate(s.day))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                    InfoRow("Hora", hhmm(s.time))
                    championship?.let { ch ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenChampionship(ch.id) }.padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Campeonato", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
                            Text("${ch.name} ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Amber)
                        }
                    }
                    if (live && s.endsInMin != null) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                        InfoRow("Termina en", "${s.endsInMin} min")
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "Más detalles de la actividad (participantes, notas de dirección) llegan en un incremento posterior.",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
        Text(value, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
    }
}

private val DIAS = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
private val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
private fun longDate(d: LocalDate): String = "${DIAS[d.dayOfWeek.isoDayNumber - 1]} ${d.dayOfMonth} de ${MESES[d.monthNumber - 1]}"
private fun hhmm(t: LocalTime): String = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
