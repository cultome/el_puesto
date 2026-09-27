package com.alephri.elpuesto.ui.agenda

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.alephri.elpuesto.ui.platform.decodeImage
import com.alephri.elpuesto.ui.platform.rememberCameraCapture
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Foto de bitácora: abre la cámara del sistema al entrar; al capturar muestra la vista
 * previa con pie de foto y guarda (crea el ítem PHOTO + sube la imagen). Cancelar la
 * cámara regresa a la pantalla anterior.
 */
@Composable
fun NewPhotoScreen(
    repo: AppRepository,
    eventId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    /** Carrera del calendario vinculada (alternativa a [eventId]). */
    roundId: String? = null,
) {
    val scope = rememberCoroutineScope()
    // La foto ya lista para subir (girada según su EXIF, lado mayor ≤ 1600 px).
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var captured by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var caption by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    val camera = rememberCameraCapture(maxSide = 1600) { bytes ->
        if (bytes != null) { photo = bytes; captured = true } else onBack()
    }
    LaunchedEffect(Unit) { if (!captured) camera() }
    LaunchedEffect(photo) {
        val bytes = photo
        if (bytes != null && preview == null) preview = decodeImage(bytes)
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                "FOTO A LA BITÁCORA",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))
            val bmp = preview
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Border, RoundedCornerShape(16.dp)),
                )
            } else {
                Text(
                    if (captured) "Procesando la foto…" else "Abriendo la cámara…",
                    fontFamily = PlexMonoFamily, fontSize = 12.sp, color = TextMut,
                    modifier = Modifier.padding(vertical = 40.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "PIE DE FOTO (OPCIONAL)", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
            AppTextField(value = caption, onValueChange = { caption = it }, placeholder = "p. ej. Amanecer desde el puesto 7", maxLength = com.alephri.elpuesto.data.TextLimits.TRIP_TITLE, showCounter = true)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (saving) "Guardando…" else "Guardar en la bitácora",
                enabled = captured && preview != null && !saving,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val bytes = photo
                        if (bytes == null) {
                            saving = false
                            error = "No se pudo leer la foto."
                            return@launch
                        }
                        val item = TripItem(
                            id = "", eventId = eventId, roundId = roundId, kind = TripItemKind.PHOTO,
                            title = caption.trim().ifBlank { "Foto" },
                            at = Clock.System.now(), personal = true,
                        )
                        val created = repo.createTripItem(item)
                        val ok = created != null && repo.uploadTripPhoto(created, bytes)
                        saving = false
                        if (ok) {
                            onDone()
                        } else {
                            error = repo.takeUploadProblem() ?: "No se pudo guardar. Revisa tu conexión e intenta de nuevo."
                        }
                    }
                },
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
