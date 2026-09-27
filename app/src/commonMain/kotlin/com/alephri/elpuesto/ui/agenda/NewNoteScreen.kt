package com.alephri.elpuesto.ui.agenda

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** Nota rápida de bitácora: un momento con la hora actual, ligado al evento activo. */
@Composable
fun NewNoteScreen(
    repo: AppRepository,
    eventId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    /** Carrera del calendario vinculada (alternativa a [eventId]). */
    roundId: String? = null,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                "NOTA A LA BITÁCORA",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))
            Text(
                "¿QUÉ PASÓ?", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
            AppTextField(
                value = text, onValueChange = { text = it },
                placeholder = "p. ej. Bandera amarilla en la práctica 2…",
                maxLength = com.alephri.elpuesto.data.TextLimits.TRIP_TITLE, showCounter = true, // la nota viaja como título
            )
            Text(
                "Queda en la línea de tiempo con la hora actual.",
                fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (saving) "Guardando…" else "Guardar en la bitácora",
                enabled = text.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val item = TripItem(
                            id = "", eventId = eventId, roundId = roundId, kind = TripItemKind.NOTE,
                            title = text.trim(), at = Clock.System.now(), personal = true,
                        )
                        repo.takeUploadProblem() // un motivo viejo no es de esta nota
                        val ok = repo.createTripItem(item) != null
                        saving = false
                        if (ok) onDone() else error = repo.takeUploadProblem() ?: "No se pudo guardar. Revisa tu conexión e intenta de nuevo."
                    }
                },
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
