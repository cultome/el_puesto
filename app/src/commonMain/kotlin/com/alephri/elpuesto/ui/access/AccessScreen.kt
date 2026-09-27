package com.alephri.elpuesto.ui.access

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.resources.Res
import com.alephri.elpuesto.resources.logo_mark
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground

@Composable
fun AccessScreen(sending: Boolean = false, error: String? = null, onSend: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(screenBackground())) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 64.dp, bottom = 34.dp)) {
            // Marca + wordmark
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Image(painterResource(Res.drawable.logo_mark), contentDescription = null, modifier = Modifier.height(50.dp))
                Column {
                    Text("EL PUESTO", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, color = androidx.compose.ui.graphics.Color.White, letterSpacing = 1.5.sp)
                    Text("OFICIALES DE PISTA", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = Amber, letterSpacing = 3.sp)
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "Tu puesto, listo antes de la bandera verde.",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = androidx.compose.ui.graphics.Color.White,
                lineHeight = 32.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Asignación, compañeros y cronograma en un solo lugar, antes y durante cada evento.",
                fontFamily = PlexSansFamily,
                fontSize = 15.5.sp,
                color = TextSub,
                lineHeight = 23.sp,
            )

            Spacer(Modifier.weight(1f))

            Text("CORREO ELECTRÓNICO", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = TextMut, letterSpacing = 0.4.sp)
            Spacer(Modifier.height(8.dp))
            AppTextField(email, { email = it }, "nombre@correo.com", keyboardType = KeyboardType.Email, maxLength = com.alephri.elpuesto.data.TextLimits.EMAIL)
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                if (sending) "Enviando…" else "Enviar enlace de acceso",
                onClick = { onSend(email.trim()) },
                enabled = !sending && email.isNotBlank(),
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Danger.copy(alpha = 0.10f))
                        .border(1.dp, Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        error,
                        fontFamily = PlexSansFamily, fontSize = 12.8.sp, color = DangerHi, lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Te enviamos un enlace para entrar. Sin contraseñas.",
                fontFamily = PlexSansFamily,
                fontSize = 12.5.sp,
                color = TextFaint,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Amber.copy(alpha = 0.09f))
                    .border(1.dp, Amber.copy(alpha = 0.22f), RoundedCornerShape(13.dp))
                    .padding(13.dp),
            ) {
                Text(
                    "El acceso es solo por invitación de otro oficial y aprobación del administrador.",
                    fontFamily = PlexSansFamily,
                    fontSize = 12.8.sp,
                    color = TextSub,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
