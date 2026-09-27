package com.alephri.elpuesto.data

import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.Area
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.Category
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.Chat
import com.alephri.elpuesto.model.ChatMember
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.EmergencyAccess
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.Message
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.PuestoMate
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.model.TripItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam de datos de la app. Funciones `suspend` porque los datos vienen por red.
 * Implementación: [HttpRepository] (backend Ktor). Lecturas ESTRICTAS: lanzan sin conexión
 * y la caché ([OfflineRepository]) decide qué mostrar — nunca hay datos inventados.
 */
/**
 * Respuestas 429 (límite de uso) o 5xx (servidor con problemas) vistas: [count] sube con
 * cada una. No son un rechazo del cambio: el outbox compara antes/después de su petición
 * y reintenta más tarde en vez de descartar.
 */
data class BusySignal(val count: Long = 0, val status: Int = 0, val retryAfterSec: Long? = null)

interface ElPuestoRepository {
    /**
     * Motivo con el que el servidor rechazó la última foto (409/413: p. ej. "Llegaste al
     * límite de fotos…"), si lo hubo. Se consume al leerlo.
     */
    fun takeUploadProblem(): String? = null
    /** Último 429/5xx visto (ver [BusySignal]); default = nunca. */
    fun busySignal(): BusySignal = BusySignal()
    /** Cambios en vivo del evento (SSE); default = sin stream (semilla/offline). */
    fun eventChanges(eventId: String): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** Cambios en vivo del chat (WebSocket); default = sin stream. */
    fun chatChanges(): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** Stream GENERAL de cambios (WS /stream): chats + mutaciones admin; default = sin stream. */
    fun changes(): kotlinx.coroutines.flow.Flow<RemoteChange> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun invite(email: String): String? = "sin conexión"
    suspend fun myInvitations(): List<com.alephri.elpuesto.model.Invitation> = emptyList()
    /** "Descargar mis datos": ¿se puede hoy? null = sin conexión / error. */
    suspend fun exportStatus(): com.alephri.elpuesto.model.ExportStatus? = null
    /** Descarga el ZIP de "mis datos" directo a [out] (streaming). Solo con red. */
    suspend fun exportMyData(out: ExportSink): ExportResult = ExportResult.Failed("Sin conexión")
    /** ¿La cuenta ya pasó por la bienvenida (en cualquier dispositivo)? null = no se pudo saber. */
    suspend fun onboardingDone(): Boolean? = null
    /** Registra en el servidor que la bienvenida se terminó u omitió; true si lo aceptó. */
    suspend fun markOnboarded(): Boolean = false
    val online: StateFlow<Boolean>

    suspend fun me(): Officer
    suspend fun activeEvent(): Event?
    suspend fun circuitName(circuitId: String): String
    /** Mi asignación en el evento; null si no tengo (404). Lanza sin conexión. */
    suspend fun assignment(eventId: String): Assignment?
    suspend fun mates(eventId: String): List<PuestoMate>
    suspend fun schedule(eventId: String): List<Session>
    suspend fun checklist(eventId: String): List<ChecklistItem>
    suspend fun agenda(): List<AgendaEntry>
    /** Sondeo ligero de conectividad (GET /health); actualiza [online]. */
    suspend fun ping()
    /** Envía al backend un cambio de checklist (drenado del outbox). true si se aceptó. */
    suspend fun ackChecklist(itemId: String, done: Boolean): Boolean
    /**
     * Pase de lista de HOY (el backend personaliza: jefe = su posición; resto = solo su
     * marca). Estricto en HTTP (lanza al fallar); default = sin datos (semilla/offline).
     */
    suspend fun attendance(eventId: String): List<com.alephri.elpuesto.model.AttendanceEntry> = emptyList()
    /**
     * Envía una marca del pase de lista (drenado del outbox). present null = desmarcar.
     * true si el servidor la aceptó; false = rechazo real (no eres jefe, etc.).
     */
    suspend fun ackAttendance(eventId: String, officerId: String, present: Boolean?): Boolean = false

    // —— Registro por honor ("yo trabajé este evento"; nunca da permisos) ——
    /** Estado del autoregistro y la participación propia. Estricto: lanza sin conexión. */
    suspend fun registration(eventId: String): com.alephri.elpuesto.model.EventRegistration = error("sin backend")
    /** Alta o edición de la participación propia. Lanza sin conexión (el outbox reintenta). */
    suspend fun setParticipation(eventId: String, req: com.alephri.elpuesto.model.SetParticipationRequest): ParticipationWrite =
        ParticipationWrite.Rejected("sin backend")
    /** Borra la participación propia (idempotente). true = aceptado. Lanza sin conexión. */
    suspend fun deleteParticipation(eventId: String): Boolean = false

    // —— Compartir ubicación. Las escrituras de la allowlist lanzan sin conexión (el
    // outbox reintenta) y devuelven null ante un rechazo real del servidor. ——
    suspend fun locationSharing(): com.alephri.elpuesto.model.LocationSharing = com.alephri.elpuesto.model.LocationSharing()
    suspend fun setLocationEnabled(enabled: Boolean): com.alephri.elpuesto.model.LocationSharing? = null
    suspend fun addLocationShare(officerId: String): com.alephri.elpuesto.model.LocationSharing? = null
    suspend fun removeLocationShare(officerId: String): com.alephri.elpuesto.model.LocationSharing? = null
    suspend fun setLocationHidden(officerId: String, hidden: Boolean): com.alephri.elpuesto.model.LocationSharing? = null
    /**
     * Sube la posición viva. true = aceptada; false = el servidor dice que ya no toca
     * transmitir (apagado / sin evento activo); null = sin conexión (se descarta: una
     * posición vieja no sirve, no se encola).
     */
    suspend fun sendLocation(update: com.alephri.elpuesto.model.LocationUpdate): Boolean? = null
    /** Retira mi última posición del servidor (pausar / dejar de compartir). Best-effort. */
    suspend fun clearLocation() {}
    /** Posiciones vivas que puedo ver en el evento (foto inicial del mapa). Lanza sin red. */
    suspend fun eventLocations(eventId: String): List<com.alephri.elpuesto.model.LivePosition> = emptyList()

    // —— Catálogos (solo lectura) ——
    suspend fun circuits(): List<Circuit>
    suspend fun trazados(circuitId: String): List<Trazado>
    suspend fun puestos(trazadoId: String): List<Puesto>
    suspend fun assets(trazadoId: String): List<TrackAsset>
    suspend fun series(): List<com.alephri.elpuesto.model.Series>
    suspend fun championships(): List<Championship>
    suspend fun categories(championshipId: String): List<Category>
    suspend fun standings(categoryId: String): List<Standing>
    suspend fun rounds(categoryId: String): List<Round>
    suspend fun drivers(categoryId: String): List<Driver>
    suspend fun convocatorias(past: Boolean): List<Convocatoria>

    // —— Perfil / emergencia / viajes / mensajes (fase 2) ——
    suspend fun officer(officerId: String): Officer?
    /**
     * Búsqueda de oficiales (nombre u OMDAI ID; ver [OfficerSearchRules]) para compartir
     * ubicación o invitar. Solo red — sin conexión devuelve vacía (default para semilla/offline).
     */
    suspend fun searchOfficers(q: String): List<Officer> = emptyList()
    /** Historial PROPIO (derivado de asignaciones). El backend lo niega para otro oficial. */
    suspend fun officerHistory(officerId: String): List<OfficerHistoryEntry>
    /** Eventos terminados donde yo y [officerId] participamos (nunca su historial completo). */
    suspend fun commonEvents(officerId: String): List<OfficerHistoryEntry> = emptyList()
    /** Logros (derivados). De otro oficial el backend solo manda los públicos. */
    suspend fun achievements(officerId: String): com.alephri.elpuesto.model.Achievements =
        com.alephri.elpuesto.model.Achievements()
    suspend fun myEmergency(): EmergencyInfo
    suspend fun myEmergencyAccesses(): List<EmergencyAccess>
    /** PUT /me; devuelve el oficial actualizado o null si falló. */
    suspend fun updateMe(displayName: String, area: Area?): Officer?
    /** PUT /me/emergency; true si el servidor aceptó. */
    suspend fun updateEmergency(info: EmergencyInfo): Boolean
    suspend fun tripItems(eventId: String): List<TripItem>
    // —— Planeación personal (agenda editable) ——
    /** Todos los ítems personales del oficial (con o sin evento). */
    suspend fun myTripItems(): List<TripItem>
    /** Crea un ítem personal; devuelve el creado (con id del servidor) o null si falló. */
    suspend fun createTripItem(item: TripItem): TripItem?
    /** Actualiza un ítem personal propio; null si falló. */
    suspend fun updateTripItem(item: TripItem): TripItem?
    /** Elimina un ítem personal propio. */
    suspend fun deleteTripItem(id: String): Boolean
    suspend fun chats(): List<Chat>
    /**
     * Mensajes de un chat en orden ascendente. Sin parámetros, los últimos 200; [limit]
     * acota; [before]/[after] = ids de mensaje (UUIDv7, ordenados por tiempo) para paginar
     * hacia atrás o traer solo lo nuevo. Estricto: lanza sin conexión.
     */
    suspend fun messages(chatId: String, limit: Int? = null, before: String? = null, after: String? = null): List<Message>
    /** Crea un chat (ver [com.alephri.elpuesto.model.CreateChatRequest]); null si falló. */
    suspend fun createChat(req: com.alephri.elpuesto.model.CreateChatRequest): Chat?
    /** Unirse/salir de un público; en un privado: aceptar/rechazar la invitación o salir. */
    suspend fun setChatJoined(chatId: String, joined: Boolean): Boolean
    /** Liga (o desliga con null) un chat propio público/privado a un evento que trabajas. */
    suspend fun setChatEvent(chatId: String, eventId: String?): Boolean = false
    /** Envía un mensaje de texto; null si el servidor lo rechazó (sin membresía, archivado…). */
    suspend fun sendMessage(chatId: String, text: String): Message?
    /** Envía un mensaje con imagen (JPEG ya escalado) y pie opcional. */
    suspend fun sendMediaMessage(chatId: String, caption: String, jpegBytes: ByteArray): Message?
    /** Marca el chat como leído hasta el último mensaje (el unread queda en 0). */
    suspend fun markChatRead(chatId: String): Boolean
    /** Participantes del chat (públicos por membresía; evento/puesto = compañeros). */
    suspend fun chatMembers(chatId: String): List<ChatMember>
    /**
     * Invita a un oficial (público o privado: le llega una invitación que acepta). null =
     * invitado; texto = motivo del rechazo. Solo red; default = sin soporte.
     */
    suspend fun addChatMember(chatId: String, officerId: String): String? = "sin conexión"
    /** Reporta un mensaje (moderación). null = reportado; texto = motivo del rechazo. Solo red. */
    suspend fun reportMessage(chatId: String, messageId: String, reason: String?): String? = "sin conexión"
    /** Oficiales que bloqueé (GET /me/blocks). Estricto: lanza sin conexión. */
    suspend fun blocks(): List<Officer> = emptyList()
    /** Bloquea/desbloquea a un oficial. null = hecho; texto = motivo del rechazo. Solo red. */
    suspend fun setBlocked(officerId: String, blocked: Boolean): String? = "sin conexión"
    /** Reporta un chat (nombre, imagen o descripción). Mismo contrato que [reportMessage]. */
    suspend fun reportChat(chatId: String, reason: String?): String? = "sin conexión"
    /** Reporta el perfil de un oficial (nombre o foto). Mismo contrato que [reportMessage]. */
    suspend fun reportOfficer(officerId: String, reason: String?): String? = "sin conexión"
    /** Archiva un chat público propio. */
    suspend fun archiveChat(chatId: String): Boolean
    /** Imagen del chat (solo el creador de un público). */
    suspend fun uploadChatImage(chatId: String, jpegBytes: ByteArray): Boolean
    /** Sube el avatar recortado; el backend genera variantes. Devuelve el oficial actualizado. */
    suspend fun uploadAvatar(jpegBytes: ByteArray): Officer?
    /** Sube la foto de un ítem PHOTO de la bitácora (proporción original). */
    suspend fun uploadTripPhoto(tripItemId: String, jpegBytes: ByteArray): Boolean
    /** Bytes de una imagen servida por el backend (p. ej. "/images/avatar/{id}/thumb"). */
    suspend fun image(path: String): ByteArray?
    /**
     * Imagen con revalidación: con [etag] (la huella de la copia local) el servidor
     * responde 304 si no cambió. Nunca lanza: sin red = [RemoteImage.Failed].
     */
    suspend fun fetchImage(path: String, etag: String?): RemoteImage
    /** Emergencia de OTRO oficial: solo el jefe de su puesto en evento activo (acceso auditado). */
    suspend fun officerEmergency(officerId: String): EmergencyView
}

/** Resultado de [ElPuestoRepository.setParticipation]. */
sealed interface ParticipationWrite {
    data class Ok(val participation: com.alephri.elpuesto.model.Participation) : ParticipationWrite
    /** Rechazo real del servidor (evento cerrado, ya estás en el roster…), con el motivo. */
    data class Rejected(val message: String) : ParticipationWrite
}

/** Resultado de [ElPuestoRepository.fetchImage]. */
sealed interface RemoteImage {
    class Fetched(val bytes: ByteArray, val etag: String?) : RemoteImage
    data object NotModified : RemoteImage
    /** 404: la imagen no existe (p. ej. oficial sin foto) o no es visible para ti. */
    data object NotFound : RemoteImage
    /** Sin red o error del servidor: no se sabe nada nuevo. */
    data object Failed : RemoteImage
}

/**
 * Cambio recibido por el stream general (`WS /stream`). kind: "chat" (id = chatId),
 * `admin:<entidad>` (id = entityId, action/detail/label del audit) o cambios acotados a
 * un evento (checklist/sessions/…, id = eventId).
 */
data class RemoteChange(
    val kind: String,
    val id: String? = null,
    val action: String? = null,
    val detail: String? = null,
    val label: String? = null,
    // kind "location" (action updated): posición del oficial [id] (nombre en [label]).
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val at: String? = null,
)

/**
 * Resultado de consultar la emergencia de otro oficial. Nunca se cachea localmente:
 * cada consulta va al backend y queda registrada (principio privacy-first).
 */
sealed interface EmergencyView {
    data class Granted(val info: EmergencyInfo) : EmergencyView
    /** El backend negó el acceso (no eres jefe del puesto del oficial en un evento activo). */
    data object Forbidden : EmergencyView
    /** Sin conexión o error del servidor. */
    data object Unavailable : EmergencyView
}
