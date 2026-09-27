package com.alephri.elpuesto.ui.profile

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.screenBackground

/**
 * "Ver todo" del perfil: historial propio completo ([otherName] null) o todos los eventos
 * en común con otro oficial, agrupados por año (más reciente primero).
 */
@Composable
fun HistoryListScreen(
    repo: AppRepository,
    officerId: String?,
    otherName: String?,
    onBack: () -> Unit,
    onOpenHistory: (OfficerHistoryEntry, String?) -> Unit,
) {
    var history by remember(officerId) { mutableStateOf<List<OfficerHistoryEntry>?>(null) }
    var unavailable by remember(officerId) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(officerId, reloader.key) {
        unavailable = false
        val t = reloader.track { repo.profile(officerId)?.history.orEmpty() }
        history = t.value
        unavailable = t.value.isEmpty() && t.missed
    }
    if (unavailable) {
        UnavailableScreen(repo, if (otherName == null) "Historial" else "En común", onBack, reloader::retry)
        return
    }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                if (otherName == null) "HISTORIAL" else "EN COMÚN",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (otherName != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Eventos en los que tú y $otherName participaron.",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                )
            }
            val list = history
            if (list == null) {
                Spacer(Modifier.height(18.dp))
                SkeletonRows(6, 56.dp)
            } else {
                list.groupBy { it.date.year }.entries.sortedByDescending { it.key }.forEach { (year, entries) ->
                    SectionHeader("$year", trailing = if (entries.size == 1) "1 evento" else "${entries.size} eventos")
                    entries.forEach { h -> HistoryRow(h, otherName) { onOpenHistory(h, otherName) } }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
