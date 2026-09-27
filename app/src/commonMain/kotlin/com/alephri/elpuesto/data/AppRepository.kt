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
import com.alephri.elpuesto.model.LocationSharing
import com.alephri.elpuesto.model.Message
import com.alephri.elpuesto.model.NotificationPrefs
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Destino de "Descargar mis datos" (archivo elegido en Android; descarga en la web). */
interface ExportSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
}

/** Resultado de "Descargar mis datos". */
sealed interface ExportResult {
    data class Ok(val bytes: Long) : ExportResult
    /** Ya descargó hoy (una vez cada 24 h): el mensaje dice cuándo podrá de nuevo. */
    data class TooSoon(val message: String) : ExportResult
    data class Failed(val message: String) : ExportResult
}

/** Estado agregado para el Home. */
data class HomeUi(
    val me: Officer,
    val event: Event?,
    val live: Session?,
    val assignment: Assignment?,
    val circuit: String,
    val agenda: List<AgendaEntry>,
)

/** Estado agregado para Modo evento. */
data class EventUi(
    val event: Event,
    val circuit: String,
    val assignment: Assignment,
    val mates: List<PuestoMate>,
    val checklist: List<ChecklistItem>,
    val schedule: List<Session>,
)

/**
 * Estado agregado para la pantalla de Perfil de un oficial.
 * La emergencia solo llega poblada si el visor está autorizado (por ahora: perfil propio).
 */
data class ProfileUi(
    val officer: Officer,
    val isSelf: Boolean,
    val emergency: EmergencyInfo?,
    val history: List<OfficerHistoryEntry>,
    /** Logros (del otro oficial, solo los públicos). null = sin red ni caché. */
    val achievements: com.alephri.elpuesto.model.Achievements?,
)

/**
 * Repositorio de la UI, **offline-first**: la fuente de verdad para la UI es la caché
 * local (SQLDelight); [refresh] sincroniza desde el backend; las escrituras se aplican
 * localmente y se encolan en el outbox para enviarse cuando haya conexión.
 */
interface AppRepository {
    val online: StateFlow<Boolean>
    suspend fun refresh()
    /** Sondeo ligero de conectividad; refresca [online] sin sincronizar datos. */
    suspend fun checkConnectivity()
    fun home(): Flow<HomeUi?>
    fun event(): Flow<EventUi?>
    /**
     * Cambios en vivo del evento (SSE del backend): emite el `kind` de lo que cambió
     * (checklist/sessions/assignments/event/map). Reconecta solo; default = sin stream.
     */
    fun eventChanges(eventId: String): Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** Cambios en vivo del chat (WebSocket): emite el chatId de cada mensaje nuevo visible. */
    fun chatChanges(): Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** Stream GENERAL de cambios (chats + mutaciones admin); un solo socket multiplexado. */
    fun changes(): Flow<RemoteChange> = kotlinx.coroutines.flow.emptyFlow()
    /**
     * Llaves de caché que la revalidación en segundo plano encontró distintas (y ya
     * reescribió): quien las mostró desde la caché debe releerlas. Lo consume `Reloader`.
     */
    fun catalogChanges(): Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** Invita a otro oficial por correo. null = enviada; texto = motivo del rechazo. */
    suspend fun invite(email: String): String? = "sin conexión"
    /** Mis invitaciones, con el estatus actual de cada una. */
    suspend fun myInvitations(): List<com.alephri.elpuesto.model.Invitation> = emptyList()
    /** "Descargar mis datos": ¿se puede hoy? null = sin conexión / error. */
    suspend fun exportStatus(): com.alephri.elpuesto.model.ExportStatus? = null
    /** Descarga el ZIP de "mis datos" directo a [out] (streaming). Solo con red. */
    suspend fun exportMyData(out: ExportSink): ExportResult = ExportResult.Failed("Sin conexión")
    /** Agenda completa (eventos + convocatorias + viajes/recordatorios). */
    fun agenda(): Flow<List<AgendaEntry>>
    /** Ítems de planeación de viaje ligados a un evento (transporte/hospedaje/recordatorios). */
    suspend fun tripItems(eventId: String): List<TripItem>
    /** Todos los ítems personales del oficial (agenda editable). */
    suspend fun myTripItems(): List<TripItem>
    /** Crea un ítem personal; devuelve el creado (con id del servidor) o null si falló. */
    suspend fun createTripItem(item: TripItem): TripItem?
    /** Actualiza/elimina un ítem personal; true si el backend lo aceptó. */
    suspend fun updateTripItem(item: TripItem): Boolean
    suspend fun deleteTripItem(item: TripItem): Boolean
    /** Sube la foto de un ítem PHOTO de la bitácora; true si el servidor la procesó. */
    suspend fun uploadTripPhoto(item: TripItem, jpegBytes: ByteArray): Boolean
    /** Envía un mensaje de texto (persistente); null si el servidor lo rechazó. */
    suspend fun sendMessage(chatId: String, text: String): Message?
    /** Envía un mensaje con imagen (JPEG escalado) y pie opcional. */
    suspend fun sendMediaMessage(chatId: String, caption: String, jpegBytes: ByteArray): Message?
    /** Marca el chat como leído (quita el punto de no leídos); refresca la lista local. */
    suspend fun markChatRead(chatId: String): Boolean
    /** Participantes del chat (nombre, avatar y contexto puesto/rol del evento del chat). */
    suspend fun chatMembers(chatId: String): List<ChatMember>
    /**
     * Invita a un oficial a un chat público o privado: le llega una invitación que acepta o
     * rechaza (nadie entra a un chat sin aceptarlo). null = invitado; texto = motivo del
     * rechazo (p. ej. ya la rechazó antes). Solo red.
     */
    suspend fun addChatMember(chatId: String, officerId: String): String? = "sin conexión"
    /** Reporta un mensaje para moderación. null = reportado; texto = motivo del rechazo. Solo red. */
    suspend fun reportMessage(chatId: String, messageId: String, reason: String?): String? = "sin conexión"
    /**
     * Oficiales que bloqueé (caché primero, llave `blocks:me`): no pueden invitarme a chats
     * ni agregarme a su lista de ubicación, y sus mensajes se ven ocultos. Vacía sin datos.
     */
    suspend fun blocks(): List<Officer> = emptyList()
    /** Bloquea/desbloquea (acción administrativa: solo red). null = hecho; texto = motivo. */
    suspend fun setBlocked(officerId: String, blocked: Boolean): String? = "sin conexión"
    /** Reporta un chat (nombre, imagen o descripción). null = reportado; texto = motivo. Solo red. */
    suspend fun reportChat(chatId: String, reason: String?): String? = "sin conexión"
    /** Reporta el perfil de otro oficial (nombre o foto). null = reportado; texto = motivo. Solo red. */
    suspend fun reportOfficer(officerId: String, reason: String?): String? = "sin conexión"
    /** Chats silenciados localmente: sus mensajes no disparan notificación del sistema. */
    suspend fun mutedChatIds(): Set<String> = emptySet()
    suspend fun setChatMuted(chatId: String, muted: Boolean) {}
    /** Archiva un chat público propio. */
    suspend fun archiveChat(chatId: String): Boolean
    /** Imagen del chat (creador de un público). */
    suspend fun uploadChatImage(chatId: String, jpegBytes: ByteArray): Boolean
    /**
     * Id del oficial AUTENTICADO (caché → red). null si aún no se conoce — NUNCA cae a
     * la semilla: se usa para decidir identidad (burbujas propias, notificaciones).
     */
    suspend fun myOfficerId(): String? = null
    /** Oficial guardado en la caché del teléfono, sin ir a la red (null = no hay). */
    suspend fun cachedOfficerId(): String? = null
    /** Otra cuenta entró en este teléfono: su Completar perfil vuelve a estar pendiente. */
    suspend fun resetOnboarding() {}
    /** Perfil de un oficial; officerId null = perfil propio. null si no existe. */
    suspend fun profile(officerId: String?): ProfileUi?
    /**
     * Logros de un oficial (officerId null = los propios): caché primero. De otro oficial
     * el backend solo manda los públicos. null = sin red ni caché.
     */
    suspend fun achievements(officerId: String?): com.alephri.elpuesto.model.Achievements? = null
    /** Mi historial de eventos terminados (caché primero); vacío sin datos. */
    suspend fun myHistory(): List<OfficerHistoryEntry> = emptyList()
    /** Una sesión del cronograma por id (detalle de actividad). null si no existe. */
    suspend fun session(sessionId: String): Session?
    /** Accesos registrados a la info de emergencia propia (auditable). */
    suspend fun emergencyAccesses(): List<EmergencyAccess>
    /**
     * Emergencia de OTRO oficial: solo el jefe de su puesto durante un evento activo.
     * Siempre va a la red y el acceso queda registrado; nunca se cachea (privacy-first).
     */
    suspend fun officerEmergency(officerId: String): EmergencyView
    /** Ediciones de perfil/emergencia aún sin enviar (se envían solas al volver la señal). */
    fun pendingProfileEdits(): Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)
    /** Cerrar sesión: borra la caché local y la cola de envíos del usuario que se va. */
    suspend fun clearLocalData() {}
    /** Onboarding (Completar perfil): pendiente al primer login activo. */
    suspend fun onboardingPending(): Boolean
    suspend fun finishOnboarding()
    /** Edita el perfil propio (no requiere aprobación). Provisional: override local. */
    suspend fun updateProfile(displayName: String, area: Area?)
    /** Edita la info de emergencia propia. Provisional: override local. */
    suspend fun updateEmergency(info: EmergencyInfo)
    /**
     * Motivo con el que el servidor rechazó la última foto (cupo de fotos lleno, demasiado
     * grande…), para mostrarlo donde se subió. Se consume al leerlo; null = sin motivo.
     */
    fun takeUploadProblem(): String? = null
    /** Sube el avatar recortado (JPEG); true si el servidor lo procesó. */
    suspend fun uploadAvatar(jpegBytes: ByteArray): Boolean
    /** Bytes de una imagen del backend (ver [imageResult]); null si no hay. */
    suspend fun image(path: String): ByteArray?
    /**
     * Imagen del backend: la guardada en el teléfono SIN esperar la red (se revalida en
     * segundo plano y, si cambió, se avisa por [imageChanges]); si no está, se descarga.
     */
    suspend fun imageResult(path: String): ImageResult
    /** Revalida en segundo plano si toca (la UI ya la tenía en memoria). */
    fun revalidateImage(path: String)
    /**
     * El servidor avisó que esta imagen cambió (stream): revalida YA sus variantes que
     * estén guardadas en el teléfono (las demás se bajarán cuando se muestren).
     */
    fun refreshImage(path: String) {}
    /** Rutas de imagen cuyo contenido cambió (revalidación o subida propia): repintarlas. */
    fun imageChanges(): Flow<String>
    /** Catálogo de circuitos (independiente de eventos, offline). */
    suspend fun circuits(): List<Circuit>
    suspend fun trazados(circuitId: String): List<Trazado>
    suspend fun puestos(trazadoId: String): List<Puesto>
    suspend fun assets(trazadoId: String): List<TrackAsset>
    /** Campeonatos (series: Fórmula 1, NASCAR…), con su logo. */
    suspend fun series(): List<com.alephri.elpuesto.model.Series>
    /** TEMPORADAS de campeonato (solo lectura). Categoría → posiciones/calendario/pilotos. */
    suspend fun championships(): List<Championship>
    suspend fun categories(championshipId: String): List<Category>
    suspend fun standings(categoryId: String): List<Standing>
    suspend fun rounds(categoryId: String): List<Round>
    suspend fun drivers(categoryId: String): List<Driver>
    /** Convocatorias (solo consulta; postulación fuera de la app). past=historial. */
    suspend fun convocatorias(past: Boolean): List<Convocatoria>
    suspend fun convocatoria(id: String): Convocatoria?
    /**
     * Compartir ubicación: allowlist en el backend (red → caché), escrituras con outbox.
     * Las órdenes pendientes se superponen al LEER (como el pase de lista).
     */
    suspend fun locationSharing(): LocationSharing
    suspend fun setLocationSharingEnabled(enabled: Boolean)
    suspend fun removeLocationShare(officerId: String)
    /** Agrega un oficial a la allowlist de compartir ubicación (elegido por búsqueda). */
    suspend fun addLocationShare(officer: Officer)
    /** Oculta/muestra en MI mapa a alguien que me comparte (no revoca su decisión). */
    suspend fun setLocationHidden(officerId: String, hidden: Boolean) {}
    /** Posiciones vivas visibles en el evento (solo red; sin conexión = vacía). */
    suspend fun eventLocations(eventId: String): List<com.alephri.elpuesto.model.LivePosition> = emptyList()
    /** Sube mi posición (ver [ElPuestoRepository.sendLocation]). */
    suspend fun sendLocation(update: com.alephri.elpuesto.model.LocationUpdate): Boolean? = null
    suspend fun clearLocation() {}
    /** Pausa local de compartir en un evento (desde la notificación): no auto-reanuda. */
    fun locationPausedFor(eventId: String): Boolean = false
    fun setLocationPaused(eventId: String?, paused: Boolean) {}
    /**
     * Búsqueda de oficiales por nombre u OMDAI ID (ver [OfficerSearchRules]; ya no por
     * correo). Solo red: sin conexión devuelve lista vacía.
     */
    suspend fun searchOfficers(q: String): List<Officer>
    suspend fun notificationPrefs(): NotificationPrefs
    suspend fun setNotificationPrefs(prefs: NotificationPrefs)
    /** Chats (tiempo real pendiente; hoy mensajes desde semilla). */
    suspend fun chats(): List<Chat>
    /**
     * Los últimos mensajes del chat (caché primero; la caché guarda solo los últimos 200) con
     * los propios encolados sin señal al final.
     */
    suspend fun messages(chatId: String): List<Message>
    /** La página anterior a [beforeId] (100, ascendente). Solo red: null = no se pudo. */
    suspend fun olderMessages(chatId: String, beforeId: String): List<Message>? = null
    /**
     * Trae SOLO lo nuevo (después del último guardado), lo suma a la caché y lo devuelve —
     * para avisos en vivo, sin volver a bajar todo el historial. Sin red: vacía.
     */
    suspend fun newerMessages(chatId: String): List<Message> = emptyList()
    /**
     * Crea un chat público o privado (en los privados [inviteeIds] reciben invitación),
     * opcionalmente ligado a un evento que trabajas. El chat creado (ya unido) o null.
     */
    suspend fun createChat(
        name: String,
        description: String?,
        isPrivate: Boolean = false,
        eventId: String? = null,
        inviteeIds: List<String> = emptyList(),
    ): Chat?
    /** Unirse/salir de un público; en un privado: aceptar/rechazar la invitación o salir. */
    suspend fun setChatJoined(chatId: String, joined: Boolean): Boolean
    /** Liga (o desliga con null) un chat propio público/privado a un evento que trabajas. */
    suspend fun setChatEvent(chatId: String, eventId: String?): Boolean = false
    suspend fun setChecklistDone(itemId: String, done: Boolean)
    /**
     * Pase de lista de HOY del evento (registro histórico en el backend, a diferencia
     * del checklist): el jefe ve su posición; cualquier otro oficial solo su marca.
     */
    suspend fun attendance(eventId: String): List<com.alephri.elpuesto.model.AttendanceEntry> = emptyList()
    /** Marca/desmarca (null) la asistencia de HOY de un compañero (solo jefe). Local + outbox. */
    suspend fun setAttendance(eventId: String, officerId: String, present: Boolean?) {}
    /**
     * Registro por honor de un evento: estado (¿puedo registrarme?) y mi participación
     * declarada, con lo pendiente de enviar superpuesto. Caché primero; null = sin red ni caché.
     */
    suspend fun registration(eventId: String): com.alephri.elpuesto.model.EventRegistration? = null
    /**
     * Registra o edita mi participación ("yo trabajé este evento"). null = guardada (sin
     * señal queda encolada y se envía sola); texto = el servidor la rechazó (motivo).
     * [positionLabel] (y [proposalPoint], si propone su puesto) se muestran mientras la
     * orden está pendiente.
     */
    suspend fun setParticipation(
        eventId: String,
        req: com.alephri.elpuesto.model.SetParticipationRequest,
        positionLabel: String,
        proposalPoint: com.alephri.elpuesto.model.MapPoint? = null,
    ): String? = "sin conexión"
    /** Quita mi registro (mismo contrato que [setParticipation]). */
    suspend fun deleteParticipation(eventId: String): String? = "sin conexión"
    /** ¿Mi registro de este evento tiene cambios sin enviar? */
    suspend fun participationPending(eventId: String): Boolean = false
    /** Nº de mutaciones locales pendientes de enviar (outbox). */
    fun pendingSync(): Flow<Long>
}

/**
 * Reglas de la búsqueda de oficiales, las mismas que aplica el servidor: al menos 3 letras
 * (del nombre) o números (del OMDAI ID) y hasta 10 resultados. Sin correos.
 */
object OfficerSearchRules {
    const val MIN_CHARS = 3
    const val MAX_RESULTS = 10
    const val HINT = "Escribe al menos 3 letras o números del OMDAI ID"
    const val PLACEHOLDER = "Nombre u OMDAI ID"

    /** ¿Ya se puede buscar con lo escrito? */
    fun ready(q: String): Boolean = q.count { it.isLetterOrDigit() } >= MIN_CHARS
}

/** Resultado de [AppRepository.imageResult]. */
sealed interface ImageResult {
    class Bytes(val bytes: ByteArray) : ImageResult
    /** Se sabe que no existe (p. ej. oficial sin foto). */
    data object NotFound : ImageResult
    /** No está en el teléfono y no hay conexión para bajarla. */
    data object Unavailable : ImageResult
}
