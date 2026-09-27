package com.alephri.elpuesto.ui.profile

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.alephri.elpuesto.ui.platform.rememberSquareImagePicker
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Area
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.ui.components.rememberOnline
import com.alephri.elpuesto.ui.components.NeedsConnectionNote
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.BloodTypeField
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun EditScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(34.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
        color = TextMut, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, top = 16.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(repo: AppRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf<String?>(null) }
    var area by remember { mutableStateOf<Area?>(null) }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    // Motivo si el servidor rechazó la foto (p. ej. cupo de fotos lleno).
    var uploadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repo.profile(null)?.let {
            name = it.officer.displayName
            area = it.officer.assignedArea
            avatarUrl = it.officer.avatarUrl
        }
    }

    // Elegir imagen + recorte cuadrado → subir (≤1024 px; el backend genera las variantes).
    val pickAvatar = rememberSquareImagePicker(maxSide = 1024) { bytes ->
        if (bytes != null) {
            uploading = true
            scope.launch {
                uploadError = null
                if (repo.uploadAvatar(bytes)) {
                    avatarUrl = repo.profile(null)?.officer?.avatarUrl
                } else {
                    uploadError = repo.takeUploadProblem() ?: "No se pudo subir la foto. Revisa tu conexión e intenta de nuevo."
                }
                uploading = false
            }
        }
    }

    val online = rememberOnline(repo)
    EditScaffold("EDITAR PERFIL", onBack) {
        FieldLabel("Foto de perfil")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Tras subir se repinta sola (la caché de imágenes avisa del cambio).
            RemoteAvatar(repo, avatarUrl, initials(name ?: "?"), size = 64.dp)
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable(enabled = online && !uploading) {
                        pickAvatar()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (uploading) "Subiendo…" else "Cambiar foto",
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (online) Amber else TextFaint,
                )
            }
        }
        // La foto sube directo (no va por la cola); nombre y área sí se guardan sin señal.
        if (!online) NeedsConnectionNote("Cambiar la foto necesita conexión.")
        uploadError?.let { NameProblem(it) }

        FieldLabel("Nombre para mostrar")
        AppTextField(value = name ?: "", onValueChange = { name = it }, placeholder = "Tu nombre", maxLength = com.alephri.elpuesto.model.NameRules.NAME_MAX)
        // Mismas reglas que el servidor (NameRules): se avisa antes de guardar.
        val nameProblem = name?.let { com.alephri.elpuesto.model.NameRules.displayNameProblem(it) }
        if (nameProblem != null && !name.isNullOrEmpty()) NameProblem(nameProblem)

        FieldLabel("Área asignada")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Area.entries.forEach { a ->
                val on = a == area
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (on) Amber else Panel)
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .clickable { area = a }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        a.display(), fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (on) OnAmber else TextPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        if (!online) OfflineSaveNote()
        PrimaryButton(
            text = if (saving) "Guardando…" else "Guardar",
            enabled = !saving && name != null && nameProblem == null,
            onClick = {
                val n = name?.let { com.alephri.elpuesto.model.NameRules.normalize(it) } ?: return@PrimaryButton
                saving = true
                scope.launch { repo.updateProfile(n, area); onBack() }
            },
        )
    }
}

@Composable
fun EditEmergencyScreen(repo: AppRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var contactName by remember { mutableStateOf<String?>(null) }
    var contactPhone by remember { mutableStateOf<String?>(null) }
    var bloodType by remember { mutableStateOf<String?>(null) }
    var allergies by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repo.profile(null)?.emergency?.let {
            contactName = it.contactName; contactPhone = it.contactPhone
            bloodType = it.bloodType; allergies = it.allergies
        }
    }

    val online = rememberOnline(repo)
    // Datos de emergencia en pantalla: sin capturas ni miniatura en "recientes".
    com.alephri.elpuesto.ui.components.SecureWindow()
    EditScaffold("EDITAR EMERGENCIA", onBack) {
        Text(
            "Privada. Solo tu jefe de puesto puede verla durante un evento activo, y cada acceso queda registrado.",
            fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
        )
        FieldLabel("Contacto")
        AppTextField(value = contactName ?: "", onValueChange = { contactName = it }, placeholder = "Nombre del contacto", maxLength = com.alephri.elpuesto.data.TextLimits.EMERGENCY_CONTACT)
        Spacer(Modifier.height(8.dp))
        AppTextField(value = contactPhone ?: "", onValueChange = { contactPhone = it }, placeholder = "Teléfono", keyboardType = KeyboardType.Phone, maxLength = com.alephri.elpuesto.data.TextLimits.PHONE)

        FieldLabel("Tipo de sangre")
        BloodTypeField(value = bloodType ?: "") { bloodType = it }

        FieldLabel("Alergias")
        AppTextField(value = allergies ?: "", onValueChange = { allergies = it }, placeholder = "p. ej. Penicilina", maxLength = com.alephri.elpuesto.data.TextLimits.EMERGENCY_TEXT, showCounter = true)

        Spacer(Modifier.height(28.dp))
        if (!online) OfflineSaveNote()
        PrimaryButton(
            text = if (saving) "Guardando…" else "Guardar",
            enabled = !saving,
            onClick = {
                saving = true
                val info = EmergencyInfo(
                    contactName = contactName?.trim()?.ifBlank { null },
                    contactPhone = contactPhone?.trim()?.ifBlank { null },
                    bloodType = bloodType?.trim()?.ifBlank { null },
                    allergies = allergies?.trim()?.ifBlank { null },
                )
                scope.launch { repo.updateEmergency(info); onBack() }
            },
        )
    }
}

/** Motivo por el que el nombre no sirve (NameRules, igual que en el servidor). */
@Composable
internal fun NameProblem(text: String) {
    Text(
        text, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
        color = com.alephri.elpuesto.ui.theme.Danger, modifier = Modifier.padding(top = 6.dp),
    )
}

/** Sin señal, guardar SÍ funciona: queda en la cola y se envía solo al volver la conexión. */
@Composable
private fun OfflineSaveNote() {
    Text(
        "Sin conexión: se guarda en tu ${com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.deviceNoun} y se envía solo al volver la señal.",
        fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
