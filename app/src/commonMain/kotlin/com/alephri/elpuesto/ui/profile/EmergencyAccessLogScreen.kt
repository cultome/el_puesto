package com.alephri.elpuesto.ui.profile

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.EmergencyAccess
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Registro (auditable) de accesos a la info de emergencia propia. Cada acceso queda
 * registrado y es visible para el titular — principio privacy-first de El Puesto.
 */
@Composable
fun EmergencyAccessLogScreen(repo: AppRepository, onBack: () -> Unit) {
    var accesses by remember { mutableStateOf<List<EmergencyAccess>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    val reloader = com.alephri.elpuesto.ui.components.rememberReloader(repo)
    LaunchedEffect(reloader.key) {
        val t = reloader.track { repo.emergencyAccesses() }
        accesses = t.value
        unavailable = t.value.isEmpty() && t.missed
    }
    if (unavailable) {
        com.alephri.elpuesto.ui.components.UnavailableScreen(repo, "Registro de accesos", onBack, reloader::retry)
        return
    }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                "REGISTRO DE ACCESOS",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(34.dp))
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Quién consultó tu información de emergencia. Cada acceso queda registrado tanto para quien consulta como para ti.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            Spacer(Modifier.height(18.dp))
            val list = accesses
            when {
                list == null -> SkeletonRows(3, 56.dp)
                list.isEmpty() -> EmptyState(
                    LineIcon.SHIELD, "Nadie ha consultado tu información",
                    "Si tu jefe de puesto la consulta durante un evento, aquí verás quién fue y cuándo.",
                )
                else -> list.forEach { AccessRow(repo, it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccessRow(repo: AppRepository, a: EmergencyAccess) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(repo, "/images/avatar/${a.viewerId}/full", initials(a.viewerName), size = 40.dp, bg = Panel, textColor = TextPrimary)
        Column(Modifier.weight(1f)) {
            Text(
                if (a.kind == "export") "Descargaste tus datos" else a.viewerName,
                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary,
            )
            Text(whenLabel(a), fontFamily = PlexMonoFamily, fontSize = 11.5.sp, color = TextSub)
        }
        if (a.kind == "export") Tag("Descarga") else if (a.eventId != null) Tag("En evento")
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

private val MX = TimeZone.of("America/Mexico_City")
private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private fun whenLabel(a: EmergencyAccess): String {
    val dt = a.at.toLocalDateTime(MX)
    val h = dt.hour.toString().padStart(2, '0')
    val m = dt.minute.toString().padStart(2, '0')
    return "${dt.dayOfMonth} ${MESES[dt.monthNumber - 1]} ${dt.year} · $h:$m"
}
