package com.alephri.elpuesto.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Avisos breves dentro de la app (donde la plataforma no tiene los suyos, como el Toast de
 * Android): los pinta [ToastHost] en la raíz y se retiran solos.
 */
object AppToasts {
    class Toast(val message: String)
    val current = MutableStateFlow<Toast?>(null)
    fun show(message: String) { current.value = Toast(message) }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val toast by AppToasts.current.collectAsState()
    val t = toast ?: return
    LaunchedEffect(t) {
        delay(if (t.message.length > 60) 4_000 else 2_500)
        if (AppToasts.current.value === t) AppToasts.current.value = null
    }
    Box(
        modifier.padding(horizontal = 24.dp, vertical = 96.dp).widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp)).background(PanelElevA)
            .border(1.dp, BorderStrong, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(t.message, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextPrimary, textAlign = TextAlign.Center)
    }
}
