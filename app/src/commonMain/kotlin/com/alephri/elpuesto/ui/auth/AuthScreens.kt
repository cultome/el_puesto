package com.alephri.elpuesto.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(screenBackground()), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Amber)
    }
}

/**
 * Bandera ondeando: la marca de las pantallas de mensaje habla el idioma de la pista —
 * roja = pista cerrada ("no puedes pasar"), verde = todo bien, adelante; amarilla =
 * precaución / espera. Dibujada en Canvas (sin assets): asta + paño cuya ondulación
 * crece hacia el borde libre, con sombreado de pliegues en degradado, animada en bucle.
 */
@Composable
fun WavingFlag(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val phase by androidx.compose.animation.core.rememberInfiniteTransition(label = "bandera").animateFloat(
        initialValue = 0f,
        targetValue = (2 * kotlin.math.PI).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "fase",
    )
    androidx.compose.foundation.Canvas(modifier.size(width = 92.dp, height = 84.dp)) {
        val poleX = size.width * 0.12f
        val poleW = 4.dp.toPx()
        // Asta con remate.
        drawLine(
            androidx.compose.ui.graphics.Color(0xFFAAB0BD),
            start = androidx.compose.ui.geometry.Offset(poleX, 5.dp.toPx()),
            end = androidx.compose.ui.geometry.Offset(poleX, size.height),
            strokeWidth = poleW,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(androidx.compose.ui.graphics.Color(0xFFECEEF2), radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(poleX, 4.dp.toPx()))

        // Paño: bordes superior e inferior ondulados; la amplitud crece hacia la punta.
        val left = poleX + poleW / 2
        val right = size.width - 2.dp.toPx()
        val top = 8.dp.toPx()
        val h = size.height * 0.5f
        val w = right - left
        val k = (2.2 * kotlin.math.PI / w).toFloat()
        fun wave(x: Float): Float {
            val t = (x - left) / w
            return (5.5.dp.toPx() * t) * kotlin.math.sin(k * (x - left) - phase)
        }
        val steps = 28
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(left, top)
        for (i in 0..steps) {
            val x = left + w * i / steps
            path.lineTo(x, top + wave(x))
        }
        for (i in steps downTo 0) {
            val x = left + w * i / steps
            path.lineTo(x, top + h + wave(x))
        }
        path.close()
        drawPath(path, color)
        // Sombreado de pliegues: degradado horizontal continuo (claro donde el paño sube
        // hacia la luz, oscuro en los valles), más marcado hacia el borde libre.
        val stops = (0..steps).map { i ->
            val f = i.toFloat() / steps
            val slope = kotlin.math.cos(k * (w * f) - phase) * f
            f to if (slope > 0) {
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f * slope)
            } else {
                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f * -slope)
            }
        }.toTypedArray()
        drawPath(path, androidx.compose.ui.graphics.Brush.horizontalGradient(*stops, startX = left, endX = right))
    }
}

@Composable
private fun MessageScaffold(
    badge: @Composable () -> Unit,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Box(Modifier.fillMaxSize().background(screenBackground()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            badge()
            Spacer(Modifier.height(22.dp))
            Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = TextHi, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, fontFamily = PlexSansFamily, fontSize = 14.sp, color = TextSub, textAlign = TextAlign.Center, lineHeight = 21.sp)
            if (primary != null) {
                Spacer(Modifier.height(28.dp))
                PrimaryButton(primary.first, onClick = primary.second)
            }
            if (secondary != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    secondary.first,
                    fontFamily = PlexSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    color = Amber,
                    modifier = Modifier.clickable(onClick = secondary.second).padding(6.dp),
                )
            }
        }
    }
}

@Composable
fun SentScreen(onOther: () -> Unit) {
    MessageScaffold(
        badge = { WavingFlag(com.alephri.elpuesto.ui.theme.Live) }, // verde: todo bien, adelante
        title = "Revisa tu correo",
        // Neutral a propósito: el servidor responde igual exista o no la invitación (nadie
        // puede averiguar desde aquí si un correo es de un oficial).
        body = "Si tu correo tiene invitación, te llegó un enlace de acceso. Ábrelo desde este ${com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.deviceNoun} para entrar.\n\nEl enlace vence en 15 minutos; si no llega, revisa el spam. El acceso es solo por invitación de otro oficial ya registrado.",
        secondary = "Usar otro correo" to onOther,
    )
}

@Composable
fun PendingScreen(onLogout: () -> Unit) {
    MessageScaffold(
        badge = { WavingFlag(com.alephri.elpuesto.ui.theme.FlagYellow) }, // amarilla: precaución, espera
        title = "Tu solicitud está en revisión",
        body = "Un administrador revisará tu acceso. Te avisaremos por correo en cuanto quede aprobado.",
        secondary = "Cerrar sesión" to onLogout,
    )
}

@Composable
fun SuspendedScreen(onLogout: () -> Unit) {
    MessageScaffold(
        badge = { WavingFlag(com.alephri.elpuesto.ui.theme.Danger) }, // roja: pista cerrada
        title = "Tu cuenta está suspendida",
        body = "Un administrador suspendió tu acceso a El Puesto. Si crees que es un error, habla con la organización.",
        secondary = "Cerrar sesión" to onLogout,
    )
}

@Composable
fun NotInvitedScreen(onBack: () -> Unit) {
    MessageScaffold(
        badge = { WavingFlag(com.alephri.elpuesto.ui.theme.Danger) }, // roja: pista cerrada
        title = "Necesitas una invitación",
        body = "No encontramos una invitación para ese correo. El acceso es solo por invitación de otro oficial ya registrado.",
        secondary = "Usar otro correo" to onBack,
    )
}
