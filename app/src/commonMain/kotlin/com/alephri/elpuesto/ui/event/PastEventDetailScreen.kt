package com.alephri.elpuesto.ui.event

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.AmberTag
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.datetime.LocalDate

/**
 * Detalle read-only de un evento pasado (derivado de las asignaciones), abierto desde el
 * historial propio ([otherName] null) o desde "eventos en común" de otro oficial: ahí
 * muestra la participación de ambos y si fueron compañeros de puesto. El circuito lleva
 * a su detalle (navegación conectada).
 */
@Composable
fun PastEventDetailScreen(
    entry: OfficerHistoryEntry,
    otherName: String?,
    onBack: () -> Unit,
    onOpenCircuit: (String) -> Unit = {},
    /** Solo en el historial propio: abre el registro por honor de este evento. */
    onEditRegistration: (String) -> Unit = {},
) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                "EVENTO PASADO",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(
                dateRange(entry.date, entry.endsOn).uppercase(),
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                color = Amber, letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(entry.eventName, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Finalizado")
                if (otherName != null && entry.samePosition) AmberTag("Mismo puesto")
            }

            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PanelElevA)
                    .border(1.dp, BorderStrong, RoundedCornerShape(20.dp)).padding(18.dp),
            ) {
                Text(
                    if (otherName == null) "TU PARTICIPACIÓN" else "USTEDES DOS",
                    fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                    color = Amber, letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(10.dp))
                if (otherName == null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        entry.positionLabel?.let {
                            Text(it, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = TextHi)
                        }
                        Text(
                            entry.roleLabel, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = Amber, modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    if (entry.positionPending) {
                        Text(
                            "Propusiste este puesto: está en revisión y solo tú lo ves hasta que se verifique.",
                            fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (entry.declared) {
                        // Lo registró el titular por honor: es su bitácora, la puede corregir.
                        Text(
                            "Lo registraste tú (sistema de honor) · Editar ›",
                            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber,
                            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { onEditRegistration(entry.id) }.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    Participant("Tú", entry.positionLabel?.let { if (entry.positionPending) "$it (en revisión)" else it }, entry.roleLabel)
                    Participant(otherName, entry.otherPosition, entry.otherRole)
                    if (entry.samePosition) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Estuvieron en la misma posición: fueron compañeros de puesto.",
                            fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            DetailField("Fechas", dateRange(entry.date, entry.endsOn))
            // Navegación conectada: el circuito abre su detalle (si el evento lo trae).
            val circuitId = entry.circuitId
            Row(
                Modifier.fillMaxWidth()
                    .then(if (circuitId != null) Modifier.clickable { onOpenCircuit(circuitId) } else Modifier)
                    .padding(vertical = 7.dp),
            ) {
                Text("Circuito", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.weight(1f))
                Text(
                    entry.location + if (circuitId != null) "  ›" else "",
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
                    color = if (circuitId != null) Amber else TextPrimary,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Fila "quién · posición · rol" del bloque "Ustedes dos". */
@Composable
private fun Participant(name: String, position: String?, role: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(position, role).joinToString(" · ").ifBlank { "—" },
            fontFamily = PlexMonoFamily, fontSize = 12.sp, color = Amber,
        )
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Text(label, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.weight(1f))
        Text(value, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
}

private val MESES = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

private fun fullDate(d: LocalDate): String = "${d.dayOfMonth} de ${MESES[d.monthNumber - 1]} de ${d.year}"

/** "5 – 7 de junio de 2026" / "30 de junio – 2 de julio de 2026" / un solo día. */
private fun dateRange(from: LocalDate, to: LocalDate?): String = when {
    to == null || to == from -> fullDate(from)
    from.year != to.year -> "${fullDate(from)} – ${fullDate(to)}"
    from.monthNumber != to.monthNumber ->
        "${from.dayOfMonth} de ${MESES[from.monthNumber - 1]} – ${to.dayOfMonth} de ${MESES[to.monthNumber - 1]} de ${to.year}"
    else -> "${from.dayOfMonth} – ${to.dayOfMonth} de ${MESES[to.monthNumber - 1]} de ${to.year}"
}
