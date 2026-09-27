package com.alephri.elpuesto.ui.components

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.Travel

/**
 * Botón flotante de la bitácora: visible mientras hay un evento activo. El punto verde
 * lo liga visualmente al "EN CURSO". Abre [AddToBitacoraSheet].
 */
@Composable
fun BitacoraFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(60.dp)) {
        Box(
            Modifier.size(56.dp).align(Alignment.BottomStart)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF6BB45), Color(0xFFE79A1F))))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { CameraGlyph(OnAmber) }
        Box(
            Modifier.size(16.dp).align(Alignment.TopEnd)
                .clip(CircleShape).background(Color(0xFF191C24)),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(Live)) }
    }
}

/** Glyph de cámara hecho con cajas (sin dependencias de íconos). */
@Composable
fun CameraGlyph(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 26.dp, height = 24.dp)) {
        // Visor superior
        Box(
            Modifier.align(Alignment.TopStart).offset(x = 4.dp)
                .size(width = 8.dp, height = 6.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(color),
        )
        // Cuerpo
        Box(
            Modifier.align(Alignment.BottomCenter)
                .size(width = 26.dp, height = 20.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(2.dp, color, RoundedCornerShape(5.dp)),
        )
        // Lente
        Box(
            Modifier.align(Alignment.BottomCenter).offset(y = (-5).dp)
                .size(10.dp).clip(CircleShape).border(2.dp, color, CircleShape),
        )
    }
}

/** Glyph de nota (hoja con líneas). */
@Composable
private fun NoteGlyph(color: Color) {
    Box(Modifier.size(width = 15.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).border(2.dp, color, RoundedCornerShape(3.dp))) {
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(width = 7.dp, height = 2.dp).background(color))
            Box(Modifier.size(width = 7.dp, height = 2.dp).background(color))
        }
    }
}

/**
 * Sheet "Agregar a la bitácora": foto y nota rápida arriba, la planeación clásica debajo.
 * Todo cae en la línea de tiempo del viaje del evento activo.
 */
@Composable
fun AddToBitacoraSheet(
    onFoto: () -> Unit,
    onNota: () -> Unit,
    onPlan: (TripItemKind) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xA00A0C10)).clickable { onDismiss() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF20242E))
                .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3F4756)))
            Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                Text("Agregar a la bitácora", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = TextHi)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Todo queda en la línea de tiempo del viaje, con la hora en que lo agregas.",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut, lineHeight = 17.sp,
                )
            }
            SheetOption(hero = true, tint = Amber, title = "Foto", subtitle = "Tomar con la cámara, sin salir de la app", onClick = onFoto) {
                CameraGlyph(Amber, Modifier.size(width = 22.dp, height = 20.dp))
            }
            SheetOption(tint = Color(0xFFD7DAE1), title = "Nota", subtitle = "Momento o apunte rápido", onClick = onNota) { NoteGlyph(Color(0xFFD7DAE1)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) {
                Text("PLANEACIÓN", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp, color = TextFaint, letterSpacing = 1.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Border))
            }
            SheetOption(tint = Travel, title = "Transporte", subtitle = "Vuelo, auto o autobús", onClick = { onPlan(TripItemKind.TRANSPORT) }) { LetterGlyph("T", Travel) }
            SheetOption(tint = Live, title = "Hospedaje", subtitle = "Hotel o alojamiento", onClick = { onPlan(TripItemKind.LODGING) }) { LetterGlyph("H", Live) }
            SheetOption(tint = Amber, title = "Recordatorio", subtitle = "Nota o pendiente con hora", onClick = { onPlan(TripItemKind.REMINDER) }) { LetterGlyph("R", Amber) }
        }
    }
}

@Composable
private fun LetterGlyph(letter: String, color: Color) {
    Text(letter, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = color)
}

@Composable
private fun SheetOption(
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    hero: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (hero) Modifier.background(Amber.copy(alpha = 0.08f)).border(1.dp, Amber.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = TextHi)
            Text(subtitle, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
        }
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
}
