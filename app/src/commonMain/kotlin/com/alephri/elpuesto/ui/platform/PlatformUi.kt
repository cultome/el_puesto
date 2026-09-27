package com.alephri.elpuesto.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.alephri.elpuesto.data.ExportResult
import com.alephri.elpuesto.data.ExportSink
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// Piezas de UI que dependen de la plataforma pero solo necesitan su SDK (no los servicios
// de :androidApp): botón/gesto de "atrás", cámara/galería/recorte, abrir enlaces, avisos
// breves, guardar la exportación y el permiso de ubicación.

/** "Atrás" del sistema (gesto de Android; botón del navegador y Escape en la web). */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * Cámara: toma una foto y la entrega como JPEG listo para subir (ya girado según su EXIF y
 * con el lado mayor ≤ [maxSide]). null = se canceló o no se pudo leer. Devuelve el lanzador.
 */
@Composable
expect fun rememberCameraCapture(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit

/** Galería: igual que [rememberCameraCapture] pero eligiendo una foto existente. */
@Composable
expect fun rememberGalleryPicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit

/**
 * Foto CUADRADA (avatar, imagen de chat): el oficial la elige y la recorta (en la web se
 * recorta al centro), lado ≤ [maxSide].
 */
@Composable
expect fun rememberSquareImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit

/** Decodifica una imagen (JPEG/PNG) fuera del hilo principal; null si no se puede. */
expect suspend fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Memoria para imágenes ya decodificadas (KB). */
expect val imageMemoryBudgetKb: Int

/** Abre un enlace http(s) fuera de la app (navegador / pestaña nueva). */
@Composable
expect fun rememberUrlOpener(): (String) -> Unit

/** Abre la dirección [query] en la app de mapas (Google Maps en la web). */
@Composable
expect fun rememberMapsOpener(): (String) -> Unit

/** Aviso breve (Toast en Android; una tira sobre la app en la web). */
@Composable
expect fun rememberToast(): (String) -> Unit

/**
 * "Descargar mis datos": el oficial elige dónde guardar (Android) o el navegador lo descarga
 * al terminar (web); [write] vuelca el ZIP al destino. [onResult] null = canceló. Si falla,
 * lo escrito a medias se borra. El lanzador recibe el nombre sugerido del archivo.
 */
@Composable
expect fun rememberExportSaver(
    onStart: () -> Unit,
    onResult: (ExportResult?) -> Unit,
    write: suspend (ExportSink) -> ExportResult,
): (suggestedName: String) -> Unit

/** Pide el permiso de ubicación "mientras se usa la app"; [onResult] = concedido. */
@Composable
expect fun rememberLocationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit

/**
 * Ahorro de batería de la app (algunos fabricantes detienen el servicio de ubicación):
 * [restricted] = conviene excluirla; [openSettings] lleva a la lista de Ajustes.
 */
class BatteryOptimization(val restricted: Boolean, val openSettings: () -> Unit)

/** null = no aplica (web). [refreshKey] re-evalúa (p. ej. al volver de Ajustes). */
@Composable
expect fun rememberBatteryOptimization(refreshKey: Int): BatteryOptimization?

/** Selector de fecha (diálogo nativo en Android). El lanzador recibe la fecha inicial. */
@Composable
expect fun rememberDatePicker(onPicked: (LocalDate) -> Unit): (initial: LocalDate) -> Unit

/** Selector de hora, 24 h (diálogo nativo en Android). El lanzador recibe la hora inicial. */
@Composable
expect fun rememberTimePicker(onPicked: (LocalTime) -> Unit): (initial: LocalTime) -> Unit
