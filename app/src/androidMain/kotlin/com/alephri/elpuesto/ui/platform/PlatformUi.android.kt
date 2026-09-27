package com.alephri.elpuesto.ui.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.alephri.elpuesto.data.ExportResult
import com.alephri.elpuesto.data.ExportSink
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

// —— Fotos ——

/** JPEG con el lado mayor ≤ [maxSide]. */
private fun Bitmap.toJpeg(maxSide: Int, quality: Int): ByteArray {
    val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
    val w = maxOf(1, (width * scale).toInt())
    val h = maxOf(1, (height * scale).toInt())
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(this, w, h, true) else this
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

/** Muestreo para no cargar la foto completa en memoria (potencia de 2 ≥ lo necesario). */
private fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * Foto de un archivo (cámara): con muestreo y ya girada según su EXIF (la cámara la guarda
 * acostada; sin esto la vista previa y la foto subida salían giradas 90°).
 */
private fun jpegFromFile(file: File, maxSide: Int): ByteArray? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide) }
    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)?.oriented(exifOrientation(file)) ?: return null
    return bmp.toJpeg(maxSide, 88)
}

/** Foto de la galería: las de la galería también traen EXIF. */
private fun jpegFromUri(context: Context, uri: Uri, maxSide: Int): ByteArray? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide) }
    val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?.oriented(exifOrientation(context, uri)) ?: return null
    return bmp.toJpeg(maxSide, 88)
}

@Composable
actual fun rememberCameraCapture(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val file = remember {
        val dir = File(context.cacheDir, "bitacora").apply { mkdirs() }
        File(dir, "captura-${System.currentTimeMillis()}.jpg")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) {
            latest(null)
        } else {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { jpegFromFile(file, maxSide) }.getOrNull() }
                file.delete()
                latest(bytes)
            }
        }
    }
    return remember(camera) {
        { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)) }
    }
}

@Composable
actual fun rememberGalleryPicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    // Photo Picker del sistema: sin permisos de almacenamiento.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            latest(null)
        } else {
            scope.launch {
                latest(withContext(Dispatchers.IO) { runCatching { jpegFromUri(context, uri, maxSide) }.getOrNull() })
            }
        }
    }
    return remember(gallery) {
        { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

@Suppress("DEPRECATION") // CropImageContract: sigue funcionando; reemplazarlo es aparte.
@Composable
actual fun rememberSquareImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val crop = rememberLauncherForActivityResult(CropImageContract()) { result ->
        val uri = result.uriContent
        if (!result.isSuccessful || uri == null) {
            latest(null)
        } else {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bmp = BitmapFactory.decodeStream(input) ?: return@use null
                            val side = minOf(maxSide, minOf(bmp.width, bmp.height))
                            val scaled = Bitmap.createScaledBitmap(bmp, side, side, true)
                            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        }
                    }.getOrNull()
                }
                latest(bytes)
            }
        }
    }
    return remember(crop) {
        {
            crop.launch(
                CropImageContractOptions(
                    null,
                    CropImageOptions(fixAspectRatio = true, aspectRatioX = 1, aspectRatioY = 1, imageSourceIncludeCamera = false),
                ),
            )
        }
    }
}

actual suspend fun decodeImage(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { it.prepareToDraw() }?.asImageBitmap()
    }.getOrNull()
}

// 1/8 del heap de la app.
actual val imageMemoryBudgetKb: Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()

// —— Fuera de la app ——

@Composable
actual fun rememberUrlOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        }
    }
}

@Composable
actual fun rememberMapsOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { q -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))) } }
    }
}

@Composable
actual fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { msg -> Toast.makeText(context, msg, if (msg.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}

@Composable
actual fun rememberExportSaver(
    onStart: () -> Unit,
    onResult: (ExportResult?) -> Unit,
    write: suspend (ExportSink) -> ExportResult,
): (suggestedName: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onStart)
    val done by rememberUpdatedState(onResult)
    val writer by rememberUpdatedState(write)
    // El oficial elige DÓNDE guardar (nunca a Descargas en automático: lleva su
    // información de emergencia) y el ZIP se escribe directo ahí en streaming.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            done(null)
        } else {
            start()
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            writer(object : ExportSink {
                                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                            })
                        }
                    }.getOrNull() ?: ExportResult.Failed("No se pudo escribir en el archivo elegido.")
                }
                if (result !is ExportResult.Ok) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                done(result)
            }
        }
    }
    return remember(launcher) { { name -> launcher.launch(name) } }
}

// —— Ubicación ——

@Composable
actual fun rememberLocationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    // "Mientras se usa la app" (nunca en segundo plano).
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        latest(granted.values.any { it })
    }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
}

@Composable
actual fun rememberBatteryOptimization(refreshKey: Int): BatteryOptimization? {
    val context = LocalContext.current
    return remember(refreshKey) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        BatteryOptimization(
            restricted = !pm.isIgnoringBatteryOptimizations(context.packageName),
            openSettings = {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }
}

// —— Fecha y hora (diálogos nativos) ——

@Composable
actual fun rememberDatePicker(onPicked: (kotlinx.datetime.LocalDate) -> Unit): (initial: kotlinx.datetime.LocalDate) -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    return remember(context) {
        { base ->
            android.app.DatePickerDialog(
                context,
                { _, y, m, d -> latest(kotlinx.datetime.LocalDate(y, m + 1, d)) },
                base.year, base.monthNumber - 1, base.dayOfMonth,
            ).show()
        }
    }
}

@Composable
actual fun rememberTimePicker(onPicked: (kotlinx.datetime.LocalTime) -> Unit): (initial: kotlinx.datetime.LocalTime) -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    return remember(context) {
        { base ->
            android.app.TimePickerDialog(
                context,
                { _, h, min -> latest(kotlinx.datetime.LocalTime(h, min)) },
                base.hour, base.minute, true,
            ).show()
        }
    }
}
