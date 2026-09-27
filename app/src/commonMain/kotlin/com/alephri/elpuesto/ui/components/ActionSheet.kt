package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.TextLimits
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import kotlinx.coroutines.launch

/**
 * Hoja inferior sobre un velo (mismo estilo que las del chat): tocar fuera la cierra
 * ([dismissable] = false mientras algo se envía). Va dentro de un Box de pantalla completa.
 */
@Composable
fun BottomSheet(onDismiss: () -> Unit, dismissable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xA00A0C10)).clickable(enabled = dismissable) { onDismiss() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF20242E))
                .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .imePadding().navigationBarsPadding().padding(bottom = 18.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Color(0xFF3F4756)),
            )
            content()
        }
    }
}

/** Título de una [BottomSheet]. */
@Composable
fun SheetTitle(text: String) {
    Text(text, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
}

/** Opción de una [BottomSheet] (fila tocable con "›"). */
@Composable
fun SheetOption(label: String, color: Color = TextHi, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp)).alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = color, modifier = Modifier.weight(1f))
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
}

/**
 * Hoja de reporte (moderación básica: lo revisa el administrador): motivo opcional y
 * "Enviar reporte". [submit] devuelve null si quedó reportado o el motivo del rechazo;
 * [onResult] recibe lo mismo al terminar (la pantalla muestra la confirmación verde o el
 * error). Exige conexión: no va por la cola.
 */
@Composable
fun ReportSheet(
    repo: AppRepository,
    title: String,
    subject: String?,
    note: String,
    onDismiss: () -> Unit,
    submit: suspend (reason: String?) -> String?,
    onResult: (problem: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val online = rememberOnline(repo)
    BottomSheet(onDismiss = onDismiss, dismissable = !sending) {
        SheetTitle(title)
        subject?.let {
            Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut, modifier = Modifier.padding(horizontal = 22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 22.dp)) {
            AppTextField(
                value = reason, onValueChange = { reason = it }, placeholder = "Motivo (opcional)",
                maxLength = TextLimits.REASON, showCounter = true,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(note, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint, modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(12.dp))
        if (!online) NeedsConnectionNote("Reportar necesita conexión.", Modifier.padding(horizontal = 22.dp))
        Box(Modifier.padding(horizontal = 22.dp)) {
            PrimaryButton(if (sending) "Reportando…" else "Enviar reporte", enabled = online && !sending, onClick = {
                sending = true
                scope.launch {
                    val problem = submit(reason.trim().ifBlank { null })
                    sending = false
                    onResult(problem)
                }
            })
        }
    }
}
