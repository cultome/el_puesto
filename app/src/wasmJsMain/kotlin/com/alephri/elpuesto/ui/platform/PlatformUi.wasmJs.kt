@file:OptIn(ExperimentalWasmJsInterop::class)

package com.alephri.elpuesto.ui.platform

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.alephri.elpuesto.data.ExportResult
import com.alephri.elpuesto.data.ExportSink
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.TextSub
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.js.Promise

// ————————————————— "Atrás": historial del navegador + Escape —————————————————

/**
 * El botón "atrás" del navegador (y Escape) ejecutan el BackHandler más reciente, como el
 * gesto de Android. Mientras haya alguno activo se deja UNA entrada extra en el historial
 * (la "guarda"): el navegador la consume al ir atrás y la app decide qué cerrar. Sin
 * handlers se retira la guarda, así el siguiente "atrás" sale de la app normalmente.
 */
private object WebBack {
    private class Entry(val onBack: () -> Unit)

    private val stack = mutableListOf<Entry>()
    private var guarded = false
    private var ignoreNextPop = false
    private var installed = false

    fun register(onBack: () -> Unit): Any {
        if (!installed) {
            installed = true
            jsOnPopState { onPop() }
            jsOnEscape { stack.lastOrNull()?.onBack?.invoke() }
        }
        val e = Entry(onBack)
        stack.add(e)
        sync()
        return e
    }

    fun unregister(token: Any) {
        stack.remove(token)
        // Después de que la UI reaccione (otra pantalla puede registrar el suyo).
        jsSetTimeout { sync() }
    }

    private fun onPop() {
        if (ignoreNextPop) { ignoreNextPop = false; return }
        guarded = false // el navegador consumió la guarda
        stack.lastOrNull()?.onBack?.invoke()
        jsSetTimeout { sync() }
    }

    private fun sync() {
        if (stack.isNotEmpty() && !guarded) {
            jsPushGuard()
            guarded = true
        } else if (stack.isEmpty() && guarded) {
            guarded = false
            ignoreNextPop = true
            jsHistoryBack()
        }
    }
}

private fun jsPushGuard(): Unit = js("{ history.pushState({ elPuesto: true }, ''); }")
private fun jsHistoryBack(): Unit = js("{ history.back(); }")
private fun jsOnPopState(cb: () -> Unit): Unit = js("{ window.addEventListener('popstate', () => cb()); }")
private fun jsOnEscape(cb: () -> Unit): Unit =
    js("{ window.addEventListener('keydown', (e) => { if (e.key === 'Escape') cb(); }); }")
private fun jsSetTimeout(cb: () -> Unit): Unit = js("{ setTimeout(() => cb(), 0); }")

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val token = WebBack.register { current() }
        onDispose { WebBack.unregister(token) }
    }
}

// ————————————————— Fotos: <input type=file> + canvas —————————————————

/**
 * Elige (o toma, con [capture]) una foto y la entrega como JPEG: `createImageBitmap` ya
 * aplica la orientación EXIF, el canvas la escala (lado mayor ≤ [maxSide]) y, si [square],
 * la recorta al centro. Resuelve null si se canceló o no se pudo leer.
 */
private fun jsPickImage(capture: Boolean, maxSide: Int, square: Boolean, quality: Double): Promise<JsAny?> = js(
    """
    new Promise((resolve) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = 'image/*';
      if (capture) input.setAttribute('capture', 'environment');
      input.addEventListener('change', async () => {
        const f = input.files && input.files[0];
        if (!f) { resolve(null); return; }
        try {
          const bmp = await createImageBitmap(f);
          let sx = 0, sy = 0, sw = bmp.width, sh = bmp.height;
          if (square) { const s = Math.min(sw, sh); sx = (sw - s) / 2; sy = (sh - s) / 2; sw = s; sh = s; }
          const scale = Math.min(1, maxSide / Math.max(sw, sh));
          const w = Math.max(1, Math.round(sw * scale)), h = Math.max(1, Math.round(sh * scale));
          const c = document.createElement('canvas');
          c.width = w; c.height = h;
          c.getContext('2d').drawImage(bmp, sx, sy, sw, sh, 0, 0, w, h);
          c.toBlob(async (b) => {
            if (!b) { resolve(null); return; }
            resolve(new Uint8Array(await b.arrayBuffer()));
          }, 'image/jpeg', quality);
        } catch (e) { resolve(null); }
      });
      input.addEventListener('cancel', () => resolve(null));
      input.click();
    })
    """,
)

private fun jsLength(a: JsAny): Int = js("a.length")
private fun jsByteAt(a: JsAny, i: Int): Byte = js("a[i]")

/** Copia un Uint8Array de JS a un ByteArray de Kotlin. */
internal fun uint8ToBytes(a: JsAny): ByteArray = ByteArray(jsLength(a)) { jsByteAt(a, it) }

@Composable
private fun rememberImagePick(capture: Boolean, square: Boolean, maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    return remember(capture, square, maxSide) {
        {
            scope.launch {
                val r = runCatching { jsPickImage(capture, maxSide, square, if (square) 0.9 else 0.88).await<JsAny?>() }.getOrNull()
                latest(r?.let(::uint8ToBytes))
            }
        }
    }
}

@Composable
actual fun rememberCameraCapture(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit =
    rememberImagePick(capture = true, square = false, maxSide = maxSide, onResult = onResult)

@Composable
actual fun rememberGalleryPicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit =
    rememberImagePick(capture = false, square = false, maxSide = maxSide, onResult = onResult)

@Composable
actual fun rememberSquareImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit =
    rememberImagePick(capture = false, square = true, maxSide = maxSide, onResult = onResult)

actual suspend fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual val imageMemoryBudgetKb: Int = 96 * 1024

// ————————————————— Fuera de la app —————————————————

private fun jsOpen(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")
private fun jsEncodeUri(s: String): String = js("encodeURIComponent(s)")

@Composable
actual fun rememberUrlOpener(): (String) -> Unit = remember { { url -> jsOpen(url) } }

@Composable
actual fun rememberMapsOpener(): (String) -> Unit =
    remember { { q -> jsOpen("https://www.google.com/maps/search/?api=1&query=" + jsEncodeUri(q)) } }

@Composable
actual fun rememberToast(): (String) -> Unit = remember { { msg -> AppToasts.show(msg) } }

// ————————————————— Descargar mis datos —————————————————

private fun jsNewParts(): JsAny = js("[]")
private fun jsNewChunk(n: Int): JsAny = js("new Uint8Array(n)")
private fun jsSetByte(a: JsAny, i: Int, v: Byte): Unit = js("{ a[i] = v; }")
private fun jsPush(parts: JsAny, chunk: JsAny): Unit = js("{ parts.push(chunk); }")
private fun jsDownload(parts: JsAny, name: String): Unit = js(
    """{
      const url = URL.createObjectURL(new Blob(parts, { type: 'application/zip' }));
      const a = document.createElement('a');
      a.href = url; a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    }""",
)

@Composable
actual fun rememberExportSaver(
    onStart: () -> Unit,
    onResult: (ExportResult?) -> Unit,
    write: suspend (ExportSink) -> ExportResult,
): (suggestedName: String) -> Unit {
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onStart)
    val done by rememberUpdatedState(onResult)
    val writer by rememberUpdatedState(write)
    // Sin selector de carpeta: el ZIP se arma en memoria y el navegador lo descarga al
    // terminar (si falla, no queda nada a medias).
    return remember {
        { name ->
            start()
            scope.launch {
                val parts = jsNewParts()
                val result = runCatching {
                    writer(object : ExportSink {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            val chunk = jsNewChunk(length)
                            for (i in 0 until length) jsSetByte(chunk, i, bytes[offset + i])
                            jsPush(parts, chunk)
                        }
                    })
                }.getOrElse { ExportResult.Failed("No se pudo preparar la descarga.") }
                if (result is ExportResult.Ok) jsDownload(parts, name)
                done(result)
            }
        }
    }
}

// ————————————————— Ubicación —————————————————

// La web no transmite la ubicación propia (LocationPlatform.supported = false).
@Composable
actual fun rememberLocationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    return remember { { latest(false) } }
}

@Composable
actual fun rememberBatteryOptimization(refreshKey: Int): BatteryOptimization? = null

// ————————————————— Fecha y hora (Material 3) —————————————————

@Composable
actual fun rememberDatePicker(onPicked: (LocalDate) -> Unit): (initial: LocalDate) -> Unit {
    val latest by rememberUpdatedState(onPicked)
    var initial by remember { mutableStateOf<LocalDate?>(null) }
    initial?.let { base ->
        // El DatePicker de Material trabaja en milisegundos UTC a medianoche.
        val state = rememberDatePickerState(initialSelectedDateMillis = base.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { initial = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        latest(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date)
                    }
                    initial = null
                }) { Text("Aceptar", color = Amber) }
            },
            dismissButton = { TextButton(onClick = { initial = null }) { Text("Cancelar", color = TextSub) } },
        ) { DatePicker(state = state) }
    }
    return remember { { base -> initial = base } }
}

@Composable
actual fun rememberTimePicker(onPicked: (LocalTime) -> Unit): (initial: LocalTime) -> Unit {
    val latest by rememberUpdatedState(onPicked)
    var initial by remember { mutableStateOf<LocalTime?>(null) }
    initial?.let { base ->
        val state = rememberTimePickerState(initialHour = base.hour, initialMinute = base.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { initial = null },
            confirmButton = {
                TextButton(onClick = { latest(LocalTime(state.hour, state.minute)); initial = null }) { Text("Aceptar", color = Amber) }
            },
            dismissButton = { TextButton(onClick = { initial = null }) { Text("Cancelar", color = TextSub) } },
            text = { TimePicker(state = state) },
        )
    }
    return remember { { base -> initial = base } }
}
