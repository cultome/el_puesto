package com.alephri.elpuesto.data

import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.NotificationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/** Formato de almacenamiento local = protobuf (mismos contratos de :shared). */
val Proto = ProtoBuf { encodeDefaults = false }

inline fun <reified T> encode(value: T): ByteArray = Proto.encodeToByteArray(value)
inline fun <reified T> decode(bytes: ByteArray): T = Proto.decodeFromByteArray(bytes)

/**
 * Decodifica un blob de la CACHÉ local; null si no decodifica — típicamente porque quedó
 * escrito con un esquema anterior tras un cambio de wire. Se trata como cache miss (la UI
 * cae a semilla/carga y el siguiente sync reescribe el blob) en vez de tronar la app.
 */
inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
    try { Proto.decodeFromByteArray<T>(bytes) } catch (_: Throwable) { null }

/** Una orden de la cola de envíos (outbox). */
data class OutboxRow(val seq: Long, val kind: String, val refId: String, val payload: String, val createdAt: Long)

/** Un renglón del checklist local (el `done` en columna para poder alternarlo). */
data class ChecklistRow(val id: String, val text: String, val done: Long)

/**
 * Caché local de la app (fuente de verdad de la UI) + cola de envíos. Mismas operaciones
 * que `ElPuesto.sq`: en Android la implementa SQLDelight (con migraciones), en la web un
 * almacén en memoria respaldado por el navegador. Los blobs son protobuf ([encode]).
 *
 * Los `*Flow` emiten el valor actual y de nuevo tras cada cambio de su tabla; dentro de
 * [transaction] los cambios se avisan al terminar (una sola emisión por tabla).
 */
interface LocalDb {
    fun <T> transaction(block: () -> T): T

    // officer (SOLO el oficial autenticado)
    fun selectOfficer(): ByteArray?
    fun officerFlow(): Flow<ByteArray?>
    fun upsertOfficer(id: String, data: ByteArray)
    fun clearOfficers()

    // event
    fun clearActiveEvent()
    fun upsertEvent(id: String, data: ByteArray, active: Long)
    fun selectActiveEvent(): ByteArray?
    fun activeEventFlow(): Flow<ByteArray?>

    // assignment
    fun upsertAssignment(eventId: String, data: ByteArray)
    fun selectAssignment(eventId: String): ByteArray?
    fun assignmentFlow(eventId: String): Flow<ByteArray?>

    // mate
    fun deleteMates(eventId: String)
    fun insertMate(eventId: String, officerId: String, ord: Long, data: ByteArray)
    fun matesFlow(eventId: String): Flow<List<ByteArray>>

    // session
    fun deleteSessions(eventId: String)
    fun insertSession(id: String, eventId: String, ord: Long, data: ByteArray)
    fun selectSessions(eventId: String): List<ByteArray>
    fun sessionsFlow(eventId: String): Flow<List<ByteArray>>

    // agenda
    fun deleteAgenda()
    fun insertAgenda(id: String, ord: Long, data: ByteArray)
    fun selectAgenda(): List<ByteArray>
    fun agendaFlow(): Flow<List<ByteArray>>

    // circuit
    fun upsertCircuit(id: String, name: String)
    fun circuitNameFlow(id: String): Flow<String?>

    // checklist
    fun deleteChecklist(eventId: String)
    fun insertChecklist(id: String, eventId: String, ord: Long, text: String, done: Long)
    fun selectChecklist(eventId: String): List<ChecklistRow>
    fun checklistFlow(eventId: String): Flow<List<ChecklistRow>>
    fun setChecklistDone(done: Long, id: String)

    // outbox
    fun enqueue(kind: String, refId: String, payload: String, createdAt: Long)
    fun selectOutbox(): List<OutboxRow>
    fun outboxFlow(): Flow<List<OutboxRow>>
    fun countOutboxFlow(): Flow<Long>
    fun deleteOutbox(seq: Long)

    // catalog (caché genérica: protobuf por llave)
    fun putCatalog(key: String, data: ByteArray)
    fun getCatalog(key: String): ByteArray?
    /** Llaves que empiezan con [prefix]. */
    fun catalogKeys(prefix: String): List<String>
    fun deleteCatalog(key: String)

    /** Cerrar sesión: nada del usuario que se va se queda (ni su cola de envíos). */
    fun wipeAll()
}

/**
 * Imágenes del backend guardadas en el dispositivo, por ruta ("/images/avatar/{id}/thumb"):
 * bytes + ETag (para revalidar con 304) + marca "se sabe que no existe".
 */
interface ImageCache {
    fun read(path: String): ByteArray?
    fun etag(path: String): String?
    fun isMissing(path: String): Boolean
    /** ¿Ya se sabe algo de esta imagen (bytes o "no existe")? */
    fun known(path: String): Boolean
    fun write(path: String, bytes: ByteArray, etag: String?)
    fun markMissing(path: String)
    fun delete(path: String)
    fun clear()
    /** Deja el total bajo [maxBytes] borrando primero lo usado hace más tiempo. */
    fun trim(maxBytes: Long)
}

/**
 * Preferencias locales del oficial (no secretas): onboarding, avisos, pausa de ubicación,
 * chats silenciados y el mapa de ids temporales del outbox.
 */
interface LocalPrefs {
    var onboardingDone: Boolean
    fun notificationPrefs(default: NotificationPrefs): NotificationPrefs
    fun saveNotificationPrefs(p: NotificationPrefs)
    fun locationPausedEvent(): String?
    fun setLocationPausedEvent(eventId: String?)
    fun mutedChatIds(): Set<String>
    fun setChatMuted(chatId: String, muted: Boolean)
    /** Fin de sesión: avisos, pausa y silenciados son del oficial y no pasan al siguiente. */
    fun clearUserData()

    /** Id real que dio el servidor a uno temporal ("local-…") del outbox. */
    fun outboxRealId(tempId: String): String?
    fun mapOutboxId(tempId: String, real: String)
    fun clearOutboxIds()
}

/**
 * Lo que el repositorio necesita de la plataforma y no es almacenamiento: si hay red en el
 * dispositivo y los efectos del sistema (recordatorios, avisos). En la web, sin efectos.
 */
interface RepoPlatform {
    /** ¿Hay red en el dispositivo? (ConnectivityManager / navigator.onLine). */
    val hasNetwork: StateFlow<Boolean>
    /** La agenda quedó sincronizada (Android programa los recordatorios locales). */
    fun onAgendaSynced(agenda: List<AgendaEntry>) {}
    /** Una foto encolada que el servidor no aceptó: avisar al oficial con el motivo. */
    fun onUploadRejected(message: String) {}
    /** Las imágenes vivían como blobs en `catalog` ("img:<ruta>"): migración de legado. */
    val migratesLegacyImages: Boolean get() = false
}
