package com.alephri.elpuesto.ui.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Orientación EXIF de una foto. La cámara guarda la imagen "acostada" (como la lee el
 * sensor) más una etiqueta que dice cómo girarla; BitmapFactory ignora esa etiqueta y
 * al re-comprimir a JPEG se pierde → la foto quedaba girada 90°. Hay que aplicarla antes.
 */
internal fun exifOrientation(file: File): Int =
    runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

internal fun exifOrientation(context: Context, uri: Uri): Int =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

/** El bitmap ya girado/volteado según [orientation] (EXIF); el mismo si no hace falta. */
internal fun Bitmap.oriented(orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
