package com.alephri.elpuesto.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Area
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.ui.components.rememberOnline
import com.alephri.elpuesto.ui.components.AppTextField
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
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Onboarding tras la primera aprobación: identidad mínima (paso 1) + emergencia opcional (paso 2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletarPerfilScreen(repo: AppRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var omdaiId by remember { mutableStateOf("") }
    var area by remember { mutableStateOf<Area?>(null) }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var bloodType by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    // Motivo si el servidor rechazó la foto (p. ej. cupo de fotos lleno).
    var uploadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repo.profile(null)?.let {
            name = it.officer.displayName
            omdaiId = it.officer.omdaiId.toString()
            area = it.officer.assignedArea
            avatarUrl = it.officer.avatarUrl
        }
    }

    // Mismo flujo que Editar perfil: elegir + recorte cuadrado → subir (≤1024 px; el backend genera las variantes).
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

    fun finish(withEmergency: Boolean) {
        saving = true
        scope.launch {
            repo.updateProfile(com.alephri.elpuesto.model.NameRules.normalize(name), area)
            if (withEmergency) {
                repo.updateEmergency(
                    EmergencyInfo(
                        contactName = contactName.trim().ifBlank { null },
                        contactPhone = contactPhone.trim().ifBlank { null },
                        bloodType = bloodType.trim().ifBlank { null },
                        allergies = allergies.trim().ifBlank { null },
                    ),
                )
            }
            repo.finishOnboarding()
            onDone()
        }
    }

    // Paso 2 (emergencia): sin capturas ni miniatura en "recientes".
    com.alephri.elpuesto.ui.components.SecureWindow(enabled = step == 2)
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("EL PUESTO", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Amber, letterSpacing = 3.sp)
            Spacer(Modifier.height(20.dp))
            Text("PASO $step DE 2 · ${if (step == 1) "IDENTIDAD" else "EMERGENCIA"}", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))

            if (step == 1) {
                Text("Crea tu perfil", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi)
                Spacer(Modifier.height(6.dp))
                Text("Pedimos lo mínimo para identificarte en pista. Tu correo queda privado.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Tras subir se repinta sola (la caché de imágenes avisa del cambio).
                    RemoteAvatar(repo, avatarUrl, initials(name.ifBlank { "?" }), size = 64.dp)
                    val online = rememberOnline(repo)
                    Column {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(12.dp))
                                .clickable(enabled = online && !uploading) {
                                    pickAvatar()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                if (uploading) "Subiendo…" else "Subir foto",
                                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (online) Amber else TextMut,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(if (online) "Opcional" else "Opcional · necesita conexión", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut)
                    }
                }
                uploadError?.let { com.alephri.elpuesto.ui.profile.NameProblem(it) }

                Label("Nombre para mostrar")
                AppTextField(value = name, onValueChange = { name = it }, placeholder = "Tu nombre", maxLength = com.alephri.elpuesto.model.NameRules.NAME_MAX)
                // Mismas reglas que el servidor (NameRules): se avisa antes de seguir.
                val nameProblem = com.alephri.elpuesto.model.NameRules.displayNameProblem(name)
                if (nameProblem != null && name.isNotEmpty()) com.alephri.elpuesto.ui.profile.NameProblem(nameProblem)
                Hint("Así te verán tus compañeros de puesto.")

                Label("OMDAI ID")
                AppTextField(value = omdaiId, onValueChange = { omdaiId = it.filter(Char::isDigit) }, placeholder = "# 0000", keyboardType = KeyboardType.Number)
                Hint("Tu número de oficial (OMDAI ID).")

                Label("Área asignada")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Area.entries.forEach { a ->
                        val on = a == area
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Amber else Panel).border(1.dp, Border, RoundedCornerShape(10.dp)).clickable { area = a }.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text(a.display(), fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (on) OnAmber else TextPrimary) }
                    }
                }
                Hint("Solo tienes un área a la vez.")

                Spacer(Modifier.height(28.dp))
                PrimaryButton("Continuar →", enabled = com.alephri.elpuesto.model.NameRules.displayNameProblem(name) == null, onClick = { step = 2 })
            } else {
                Text("Información de emergencia", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi)
                Spacer(Modifier.height(6.dp))
                Text("Opcional. Puedes agregarla ahora o más tarde desde tu perfil. Es privada: solo tu jefe de puesto la ve durante un evento activo, y cada acceso queda registrado.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)

                Label("Contacto de emergencia")
                AppTextField(value = contactName, onValueChange = { contactName = it }, placeholder = "Nombre", maxLength = com.alephri.elpuesto.data.TextLimits.EMERGENCY_CONTACT)
                Spacer(Modifier.height(8.dp))
                AppTextField(value = contactPhone, onValueChange = { contactPhone = it }, placeholder = "Teléfono", keyboardType = KeyboardType.Phone, maxLength = com.alephri.elpuesto.data.TextLimits.PHONE)

                Label("Tipo de sangre")
                BloodTypeField(value = bloodType) { bloodType = it }

                Label("Alergias (opcional)")
                AppTextField(value = allergies, onValueChange = { allergies = it }, placeholder = "Ej. Penicilina", maxLength = com.alephri.elpuesto.data.TextLimits.EMERGENCY_TEXT, showCounter = true)

                Spacer(Modifier.height(28.dp))
                PrimaryButton(if (saving) "Guardando…" else "Finalizar", onClick = { finish(withEmergency = true) }, enabled = !saving)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !saving) { finish(withEmergency = false) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Omitir por ahora", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextMut)
                }
                Spacer(Modifier.height(8.dp))
                Text("Podrás editar toda tu información cuando quieras desde tu perfil.", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp))
}
