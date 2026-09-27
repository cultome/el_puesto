package com.alephri.elpuesto.ui.components

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.alephri.elpuesto.ui.theme.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.TextFaint

/** Visor de imagen a pantalla completa (fotos de chat, etc.). */
@Composable
fun ImageViewerScreen(repo: AppRepository, path: String, onBack: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(path) { mutableStateOf(false) }
    var unavailable by remember(path) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    LaunchedEffect(path, reloader.key) {
        val t = com.alephri.elpuesto.data.trackMiss { loadImageBitmap(repo, path) }
        bitmap = t.value
        unavailable = t.value == null && t.missed
        loaded = true
    }
    if (unavailable) {
        UnavailableScreen(repo, "Imagen", onBack, reloader::retry)
        return
    }
    // Las fotos de piloto vienen recortadas sobre el carbón de la app: mismo fondo, sin rectángulo.
    val bg = if (path.startsWith("/images/driver/")) Surface else Color(0xFF0A0C10)
    Box(Modifier.fillMaxSize().background(bg)) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            loaded -> Text(
                "No se pudo cargar la imagen",
                fontFamily = PlexMonoFamily, fontSize = 12.sp, color = TextFaint,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Text(
                "Cargando…",
                fontFamily = PlexMonoFamily, fontSize = 12.sp, color = TextFaint,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { BackButton(onBack) }
    }
}
