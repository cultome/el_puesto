package com.alephri.elpuesto.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.ImageResult
import kotlinx.coroutines.launch

/** Estado de una imagen del backend en pantalla. */
sealed interface ImageLoad {
    data object Loading : ImageLoad
    class Ready(val bitmap: ImageBitmap) : ImageLoad
    /** No existe (p. ej. oficial sin foto). */
    data object Missing : ImageLoad
    /** No está en el teléfono y no hay conexión para bajarla (se reintenta sola al volver). */
    data object Unavailable : ImageLoad
}

/**
 * Imágenes ya decodificadas, en memoria: al volver a una pantalla o hacer scroll se pintan
 * en el PRIMER cuadro (sin pasar por iniciales/skeleton). Las ya vistas que no existen
 * también se recuerdan, para no pintar un placeholder cada vez.
 */
object ImageMemory {
    private val bitmaps = LruCache<String, ImageBitmap>(
        com.alephri.elpuesto.ui.platform.imageMemoryBudgetKb,
    ) { _, value -> maxOf(1, value.width * value.height * 4 / 1024) }
    private val missing = LruCache<String, Boolean>(4000)

    fun peek(path: String): ImageLoad? =
        bitmaps.get(path)?.let { ImageLoad.Ready(it) } ?: missing.get(path)?.let { ImageLoad.Missing }

    fun put(path: String, bitmap: ImageBitmap) { missing.remove(path); bitmaps.put(path, bitmap) }
    fun putMissing(path: String) { bitmaps.remove(path); missing.put(path, true) }
    fun invalidate(path: String) { bitmaps.remove(path); missing.remove(path) }

    /** Al cerrar sesión: nada del usuario que se va se queda ni en memoria. */
    fun clear() { bitmaps.evictAll(); missing.evictAll() }
}

/** Decodifica fuera del hilo principal (y deja el bitmap listo para dibujar). */
suspend fun decodeImage(bytes: ByteArray): ImageBitmap? = com.alephri.elpuesto.ui.platform.decodeImage(bytes)

/**
 * Carga única (para pantallas que la piden dentro de otra carga, p. ej. el mapa de un
 * trazado): memoria → teléfono → red. Null = no existe o no disponible.
 */
suspend fun loadImageBitmap(repo: AppRepository, path: String): ImageBitmap? {
    (ImageMemory.peek(path) as? ImageLoad.Ready)?.let { repo.revalidateImage(path); return it.bitmap }
    val bytes = (repo.imageResult(path) as? ImageResult.Bytes)?.bytes ?: return null
    return decodeImage(bytes)?.also { ImageMemory.put(path, it) }
}

/**
 * Imagen del backend para pintar: sale de memoria al instante si ya se vio, si no del
 * teléfono (ms) o de la red. Se repinta sola si el servidor la cambia (revalidación) y
 * reintenta al volver la señal si no estaba disponible.
 */
@Composable
fun rememberRemoteImage(repo: AppRepository, path: String?): ImageLoad {
    var state by remember(path) {
        mutableStateOf(if (path == null) ImageLoad.Missing else ImageMemory.peek(path) ?: ImageLoad.Loading)
    }
    LaunchedEffect(repo, path) {
        if (path == null) return@LaunchedEffect
        suspend fun load() {
            state = when (val r = repo.imageResult(path)) {
                is ImageResult.Bytes -> decodeImage(r.bytes)?.let { ImageMemory.put(path, it); ImageLoad.Ready(it) }
                    ?: ImageLoad.Missing
                ImageResult.NotFound -> { ImageMemory.putMissing(path); ImageLoad.Missing }
                ImageResult.Unavailable -> (state as? ImageLoad.Ready) ?: ImageLoad.Unavailable
            }
        }
        if (state === ImageLoad.Loading) load() else repo.revalidateImage(path)
        launch { repo.online.collect { up -> if (up && state === ImageLoad.Unavailable) load() } }
        repo.imageChanges().collect { changed ->
            if (changed == path) { ImageMemory.invalidate(path); load() }
        }
    }
    return state
}
