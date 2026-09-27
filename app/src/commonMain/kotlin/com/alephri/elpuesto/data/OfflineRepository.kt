@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.alephri.elpuesto.data

import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.AttendanceEntry
import com.alephri.elpuesto.model.RegistrationState
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.EmergencyAccess
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Message
import com.alephri.elpuesto.model.MessageMediaType
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.model.PuestoMate
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.TripItemKind
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

private const val TAG = "ElPuestoSync"

/** Intento de red en lecturas cuando ya sabemos que no hay señal (ver fetchBudgeted). */
private const val OFFLINE_FETCH_BUDGET_MS = 1_500L

/** Una llave de `catalog` se revalida con la red a lo más cada tanto (ver revalidate). */
private const val CATALOG_REVALIDATE_MS = 10_000L

/** Cada cuánto, como mucho, se pregunta al servidor si una imagen mutable cambió. */
private const val IMAGE_REVALIDATE_MS = 10 * 60_000L

/** Tope de las imágenes guardadas en el teléfono (se borra primero lo menos usado). */
private const val IMAGE_STORE_MAX_BYTES = 150L * 1024 * 1024

/** Página de mensajes que se pide al servidor (al abrir un chat y hacia atrás). */
private const val MESSAGES_PAGE = 100

/** Mensajes de cada chat que se guardan en el teléfono (los últimos). */
private const val MESSAGES_CACHED = 200

/** Marca interna de "se sabe que no existe" al leer del teléfono. */
private val MISSING = ByteArray(0)

/** Misma imagen en otra variante ("/…/thumb" ↔ "/…/full"). */
private fun variantOf(path: String, variant: String) = path.substringBeforeLast('/') + "/" + variant

/** Mensaje de chat encolado offline (texto o foto): el eco local usa [localId]. */
@Serializable
internal data class QueuedChatMsg(val localId: String, val text: String, val b64: String? = null)

/**
 * Implementación offline-first: lee de la caché SQLDelight (Flows) y sincroniza desde
 * [remote] (backend por protobuf). Las escrituras (checklist) se aplican local e
 * inmediatamente y se encolan en el outbox, que se drena best-effort al backend.
 */
class OfflineRepository(
    private val remote: ElPuestoRepository,
    /** Caché local + cola de envíos (SQLDelight en Android; el navegador en la web). */
    private val db: LocalDb,
    /** Imágenes guardadas en el dispositivo. */
    private val images: ImageCache,
    private val prefs: LocalPrefs,
    private val platform: RepoPlatform,
) : AppRepository {

    private val io = ioDispatcher

    // —— Imágenes guardadas en el teléfono (ver imageResult) ——
    private val imageScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val imageChangesFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Última vez que se preguntó al servidor por cada imagen (en memoria: el proceso). */
    private val imageChecked = SafeMap<String, Long>()
    /** Descargas en curso: varias pantallas pidiendo la misma imagen comparten una. */
    private val imageInflight = SafeMap<String, Deferred<ImageResult>>()
    private val prefetchGate = Semaphore(4)

    init {
        imageScope.launch {
            if (platform.migratesLegacyImages) migrateLegacyImages()
            images.trim(IMAGE_STORE_MAX_BYTES)
        }
    }

    /**
     * ¿Hay red en el teléfono? (ConnectivityManager). Sin red no tiene caso esperar el
     * timeout de una petición: el banner y las lecturas pasan a "sin conexión" al instante,
     * aunque el socket todavía no se haya enterado de la caída.
     */
    private val hasNetwork: StateFlow<Boolean> = platform.hasNetwork

    /** En línea = hay red Y el backend responde (latido del socket / última petición). */
    override val online: StateFlow<Boolean> =
        combine(remote.online, hasNetwork) { up, net -> up && net }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, remote.online.value && hasNetwork.value)

    override suspend fun checkConnectivity() = remote.ping()

    // —— Flows base sobre la caché ——
    private fun officerFlow(): Flow<Officer?> =
        db.officerFlow().map { it?.let { b -> decodeOrNull<Officer>(b) } }

    private fun activeEventFlow(): Flow<Event?> =
        db.activeEventFlow().map { it?.let { b -> decodeOrNull<Event>(b) } }
            .onEach { com.alephri.elpuesto.logd(TAG) { "activeEventFlow emite ${it?.id ?: "null"}" } }

    private fun assignmentFlow(eventId: String): Flow<Assignment?> =
        db.assignmentFlow(eventId).map { it?.let { b -> decodeOrNull<Assignment>(b) } }

    private fun matesFlow(eventId: String): Flow<List<PuestoMate>> =
        db.matesFlow(eventId).map { rows -> rows.mapNotNull { decodeOrNull<PuestoMate>(it) } }

    private fun scheduleFlow(eventId: String): Flow<List<Session>> =
        db.sessionsFlow(eventId).map { rows -> rows.mapNotNull { decodeOrNull<Session>(it) } }

    private fun agendaFlow(): Flow<List<AgendaEntry>> =
        db.agendaFlow().map { rows -> rows.mapNotNull { decodeOrNull<AgendaEntry>(it) } }
            .onEach { com.alephri.elpuesto.logd(TAG) { "agendaFlow emite ${it.size} entradas" } }

    private fun circuitNameFlow(circuitId: String): Flow<String> =
        db.circuitNameFlow(circuitId).map { it ?: "—" }

    private fun checklistFlow(eventId: String): Flow<List<ChecklistItem>> =
        db.checklistFlow(eventId).map { rows ->
            rows.map { ChecklistItem(id = it.id, text = it.text, done = it.done != 0L) }
        }

    // —— Estados agregados para la UI ——
    override fun home(): Flow<HomeUi?> =
        combine(officerFlow(), activeEventFlow(), agendaFlow()) { me, ev, ag -> Triple(me, ev, ag) }
            .flatMapLatest { (me, ev, ag) ->
                when {
                    me == null -> flowOf(null)
                    ev == null -> flowOf(HomeUi(me, null, null, null, "", ag))
                    else -> combine(assignmentFlow(ev.id), scheduleFlow(ev.id), circuitNameFlow(ev.circuitId)) { a, sch, cn ->
                        HomeUi(me, ev, sch.firstOrNull { it.status == EventStatus.LIVE }, a, cn, ag)
                    }
                }
            }

    override fun event(): Flow<EventUi?> =
        activeEventFlow().flatMapLatest { ev ->
            if (ev == null) {
                flowOf(null)
            } else {
                combine(
                    assignmentFlow(ev.id),
                    matesFlow(ev.id),
                    checklistFlow(ev.id),
                    scheduleFlow(ev.id),
                    circuitNameFlow(ev.circuitId),
                ) { a, m, ch, sch, cn ->
                    if (a == null) null else EventUi(ev, cn, a, m, ch, sch)
                }
            }
        }

    /**
     * PUERTA ÚNICA de lectura de la caché genérica `catalog` (objeto suelto; null = no se
     * pudo). Toda lectura cacheable de la app pasa por aquí; la política vive solo aquí:
     *
     * - CACHÉ PRIMERO (siempre, salvo [freshReads]): lo guardado se devuelve al instante y
     *   la red revalida en segundo plano ([revalidate]). Si trae algo distinto, la puerta
     *   de escritura ([storeCatalog]) lo guarda y [catalogChanges] avisa: las pantallas que
     *   leyeron esa llave con `Reloader.track` ([reportRead] se las anota) releen solas.
     * - RED PRIMERO si no hay nada guardado, o dentro de [freshReads] (jalar para
     *   refrescar, recargas por un aviso en vivo, decisiones que exigen lo último — p. ej.
     *   si notificar un mensaje): red → caché → null (y [reportMiss]).
     */
    private suspend inline fun <reified T : Any> cachedValue(key: String, crossinline fetch: suspend () -> T): T? {
        if (reportRead(key)) {
            val bytes = withContext(io) { db.getCatalog(key) }
            val cached = bytes?.let { withContext(io) { decodeOrNull<T>(it) } }
            if (bytes != null && cached != null) {
                revalidate(key, bytes) { encode(fetch()) }
                return cached
            }
        }
        return try {
            val epoch = sessionEpoch.load()
            val v = fetchBudgeted { fetch() }
            // Si la sesión terminó mientras tanto, lo que llegó ya no se guarda.
            if (sessionEpoch.load() == epoch) {
                storeCatalog(key, encode(v))
                catalogChecked[key] = nowMs()
            }
            v
        } catch (e: Throwable) {
            // Cancelación por navegación ≠ error; el timeout del intento corto sí lo es.
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            db.getCatalog(key)?.let { decodeOrNull<T>(it) } ?: run { reportMiss(); null }
        }
    }

    /**
     * PUERTA ÚNICA de escritura de la caché `catalog`: guarda [bytes] y, si cambiaron,
     * avisa por [catalogChanges] — así CUALQUIER escritura (revalidación, una acción del
     * oficial, el drenaje del outbox, un aviso en vivo) llega sola a las pantallas que
     * muestran esa llave. true = cambió. Dentro de `db.transaction` no se usa: el aviso iría
     * antes del commit (ver [applyTripLocally]).
     */
    private fun storeCatalog(key: String, bytes: ByteArray): Boolean {
        val old = db.getCatalog(key)
        if (old != null && old.contentEquals(bytes)) return false
        db.putCatalog(key, bytes)
        notifyChanged(key)
        return true
    }

    /** Lo que se lee de [key] cambió (caché o lo que se le superpone, como el outbox). */
    private fun notifyChanged(key: String) {
        com.alephri.elpuesto.logd(TAG) { "caché: cambió $key" }
        catalogChangesFlow.tryEmit(key)
    }

    /** Tras una escritura en el servidor: trae [key] de nuevo y lo guarda (avisa si cambió). */
    private suspend inline fun <reified T> refetch(key: String, crossinline fetch: suspend () -> T) {
        try {
            val epoch = sessionEpoch.load()
            val bytes = encode(fetch())
            if (sessionEpoch.load() != epoch) return // la sesión terminó mientras tanto
            storeCatalog(key, bytes)
            catalogChecked[key] = nowMs()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.alephri.elpuesto.logd(TAG) { "refetch($key): falló (${e.message}); se revalidará al leer" }
            catalogChecked.remove(key)
        }
    }

    /**
     * Sube con cada [clearLocalData] (fin de sesión / cambio de cuenta): una respuesta que
     * venía en camino con la sesión anterior ya no se escribe en la caché del siguiente.
     */
    private val sessionEpoch = AtomicInt(0)

    // —— Revalidación en segundo plano de la caché `catalog` (ver cachedValue) ——
    private val catalogScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val catalogChangesFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Última vez que cada llave se trajo de la red (en memoria: el proceso). */
    private val catalogChecked = SafeMap<String, Long>()
    private val catalogInflight = SafeSet<String>()

    override fun catalogChanges(): Flow<String> = catalogChangesFlow

    /**
     * Trae [key] de la red sin que nadie espere: sin conexión no se intenta (al volver, la
     * pantalla se recarga y revalida), una sola a la vez por llave y no más de una cada
     * [CATALOG_REVALIDATE_MS] (así la recarga que provoca un cambio no vuelve a pedirlo).
     * Avisa si lo del servidor difiere de lo que se MOSTRÓ ([shown]) — no de la caché
     * actual: si otro camino (refresh, una escritura) ya la reescribió, la pantalla que
     * pintó lo viejo igual tiene que releer.
     */
    private fun revalidate(key: String, shown: ByteArray, fetch: suspend () -> ByteArray) {
        if (!online.value) return
        val last = catalogChecked[key]
        if (last != null && nowMs() - last < CATALOG_REVALIDATE_MS) return
        if (!catalogInflight.add(key)) return
        val epoch = sessionEpoch.load()
        catalogScope.launch {
            try {
                val fresh = fetch()
                if (sessionEpoch.load() != epoch) return@launch // la sesión terminó mientras tanto
                catalogChecked[key] = nowMs()
                // Si otro camino ya dejó esto en la caché, storeCatalog no avisa: igual hay
                // que avisar si difiere de lo que esa pantalla pintó.
                if (!storeCatalog(key, fresh) && !fresh.contentEquals(shown)) notifyChanged(key)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                com.alephri.elpuesto.logd(TAG) { "revalidate($key): falló (${e.message}); se queda lo guardado" }
            } finally {
                catalogInflight.remove(key)
            }
        }
    }

    /**
     * Espera de la red en LECTURAS: con conexión, la normal (timeouts del cliente); sin red
     * en el teléfono, ni se intenta; con red pero el backend caído, solo un intento corto
     * (si ya volvió, responde y [online] se corrige solo). Así abrir algo sin conexión cae
     * a la caché (o a "no disponible") de inmediato, sin colgarse.
     */
    private suspend fun <T> fetchBudgeted(fetch: suspend () -> T): T = when {
        !hasNetwork.value -> throw NoNetworkException()
        online.value -> fetch()
        else -> withTimeout(OFFLINE_FETCH_BUDGET_MS) { fetch() }
    }

    // —— Perfil de oficial: red → caché (sin datos inventados: sin nada = null) ——
    override suspend fun myOfficerId(): String? {
        db.selectOfficer()?.let { decodeOrNull<Officer>(it)?.id }?.let { return it }
        return try {
            val me = remote.me()
            db.transaction { db.clearOfficers(); db.upsertOfficer(me.id, encode(me)) }
            me.id
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            reportMiss()
            null
        }
    }

    override suspend fun profile(officerId: String?): ProfileUi? {
        // myOfficerId: caché → red (y la deja en caché); sin sesión conocida no hay perfil.
        val meId = myOfficerId() ?: return null
        val id = officerId ?: meId
        val isSelf = id == meId
        val officer: Officer = if (isSelf) {
            // El propio ya vive en la caché principal (refresh la mantiene); override local encima.
            overlayPendingProfile(db.selectOfficer()?.let { decodeOrNull<Officer>(it) } ?: return null)
        } else {
            cachedValue("officer:$id") { remote.officer(id) ?: error("404") } ?: return null
        }
        // Propio = historial completo; otro = SOLO los eventos en común (privacy-first).
        // Ambos derivados de asignaciones en el backend; sin red, la última caché.
        val history = if (isSelf) {
            cachedCatalog("history:$id", emptyList()) { remote.officerHistory(id) }
        } else {
            cachedCatalog("common:$id", emptyList()) { remote.commonEvents(id) }
        }
        val emergency = if (isSelf) {
            // Pendiente de enviar = lo que el oficial capturó (un fetch no debe "deshacerlo").
            pendingEmergency() ?: cachedValue("emergency:me") { remote.myEmergency() } ?: com.alephri.elpuesto.model.EmergencyInfo()
        } else null
        return ProfileUi(
            officer = officer,
            isSelf = isSelf,
            emergency = emergency,
            history = history,
            achievements = cachedValue("achievements:$id") { remote.achievements(id) },
        )
    }

    override suspend fun achievements(officerId: String?): com.alephri.elpuesto.model.Achievements? {
        val id = officerId ?: myOfficerId() ?: return null
        return cachedValue("achievements:$id") { remote.achievements(id) }
    }

    override suspend fun myHistory(): List<OfficerHistoryEntry> {
        val id = myOfficerId() ?: return emptyList()
        return cachedCatalog("history:$id", emptyList()) { remote.officerHistory(id) }
    }

    override suspend fun cachedOfficerId(): String? =
        withContext(io) { db.selectOfficer()?.let { decodeOrNull<Officer>(it)?.id } }

    override suspend fun resetOnboarding() { prefs.onboardingDone = false }

    /**
     * La bienvenida es UNA vez por cuenta (el servidor la recuerda), no por dispositivo: en un
     * teléfono o navegador nuevo se le pregunta al servidor. Si no se puede preguntar, no se
     * fuerza (quien ya la hizo y abre sin señal va directo al Inicio).
     */
    override suspend fun onboardingPending(): Boolean {
        if (prefs.onboardingDone) return false
        val done = try {
            fetchBudgeted { remote.onboardingDone() }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        } ?: return false
        if (done) prefs.onboardingDone = true
        return !done
    }

    override suspend fun finishOnboarding() {
        prefs.onboardingDone = true
        // Sin señal se encola: el servidor debe saberlo para no mostrarla en otro dispositivo.
        if (!remote.markOnboarded()) {
            db.selectOutbox().filter { it.kind == "onboarding" }.forEach { db.deleteOutbox(it.seq) }
            db.enqueue("onboarding", "me", "", nowMs())
        }
    }

    // —— Edición de perfil y emergencia propios: OUTBOX (funciona sin señal) ——
    // Se refleja en la caché al instante, se encola (solo la ÚLTIMA edición queda en la
    // cola: gana la última) y se superpone al leer mientras no se envía.

    @Serializable
    internal data class QueuedProfile(val displayName: String, val area: com.alephri.elpuesto.model.Area? = null)

    private fun pendingProfile(): QueuedProfile? =
        db.selectOutbox().lastOrNull { it.kind == "profile" }
            ?.let { runCatching { ojson.decodeFromString<QueuedProfile>(it.payload) }.getOrNull() }

    private fun overlayPendingProfile(o: Officer): Officer =
        pendingProfile()?.let { o.copy(displayName = it.displayName, assignedArea = it.area) } ?: o

    private fun pendingEmergency(): com.alephri.elpuesto.model.EmergencyInfo? =
        db.selectOutbox().lastOrNull { it.kind == "emergency" }
            ?.let { runCatching { ojson.decodeFromString<com.alephri.elpuesto.model.EmergencyInfo>(it.payload) }.getOrNull() }

    /** Deja en la cola solo la última orden de ese tipo (coalescing). */
    private fun enqueueLatest(kind: String, payload: String) {
        db.selectOutbox().filter { it.kind == kind }.forEach { db.deleteOutbox(it.seq) }
        db.enqueue(kind, "me", payload, nowMs())
    }

    override suspend fun updateProfile(displayName: String, area: com.alephri.elpuesto.model.Area?) {
        enqueueLatest("profile", ojson.encodeToString(QueuedProfile.serializer(), QueuedProfile(displayName, area)))
        db.selectOfficer()?.let { blob ->
            decodeOrNull<Officer>(blob)?.let { cached -> db.upsertOfficer(cached.id, encode(overlayPendingProfile(cached))) }
        }
        drainOutbox()
    }

    override suspend fun updateEmergency(info: com.alephri.elpuesto.model.EmergencyInfo) {
        enqueueLatest("emergency", ojson.encodeToString(com.alephri.elpuesto.model.EmergencyInfo.serializer(), info))
        storeCatalog("emergency:me", encode(info))
        drainOutbox()
    }

    override fun pendingProfileEdits(): Flow<Long> =
        db.outboxFlow().map { rows -> rows.count { it.kind == "profile" || it.kind == "emergency" }.toLong() }

    /** Cerrar sesión: nada del usuario que se va se queda en el teléfono ni se envía después. */
    override suspend fun clearLocalData() {
        sessionEpoch.incrementAndFetch()
        db.wipeAll()
        prefs.clearOutboxIds()
        prefs.clearUserData()
        withContext(io) { images.clear() }
        imageChecked.clear()
        catalogChecked.clear()
    }

    override fun takeUploadProblem(): String? = remote.takeUploadProblem()

    override suspend fun uploadAvatar(jpegBytes: ByteArray): Boolean {
        val updated = remote.uploadAvatar(jpegBytes) ?: return false
        // Refleja el avatarUrl nuevo en la caché y reemplaza las variantes guardadas.
        db.upsertOfficer(updated.id, encode(overlayPendingProfile(updated)))
        updated.avatarUrl?.let { url -> storeUploadedImage(url, jpegBytes, withThumb = true) }
        return true
    }

    /**
     * Tras subir una imagen propia: guarda las variantes que generó el servidor (o la
     * original como aproximación si no se pudieron bajar) y avisa a la UI.
     */
    private suspend fun storeUploadedImage(fullPath: String, jpegBytes: ByteArray, withThumb: Boolean) {
        val paths = if (withThumb) listOf(fullPath, variantOf(fullPath, "thumb")) else listOf(fullPath)
        withContext(io) {
            for (p in paths) {
                when (val r = remote.fetchImage(p, null)) {
                    is RemoteImage.Fetched -> images.write(p, r.bytes, r.etag)
                    else -> images.write(p, jpegBytes, null)
                }
                imageChecked[p] = nowMs()
            }
        }
        paths.forEach { imageChangesFlow.emit(it) }
    }

    // —— Imágenes: PRIMERO lo guardado en el teléfono (sin esperar la red: nada de
    // parpadeos ni esperas sin señal), revalidación en segundo plano con ETag (304 si no
    // cambió) y descarga solo si no está. Fotos de chat y de bitácora no cambian nunca
    // (su id es único): no se revalidan. ——

    /** Imagen propia recién capturada (foto de chat/bitácora): guardada y avisada. */
    private suspend fun putLocalImage(path: String, bytes: ByteArray) {
        withContext(io) { images.write(path, bytes, null) }
        imageChangesFlow.emit(path)
    }

    private fun immutableImage(path: String) =
        path.startsWith("/images/chatmedia/") || path.startsWith("/images/trip/")

    override fun imageChanges(): Flow<String> = imageChangesFlow

    override suspend fun image(path: String): ByteArray? = (imageResult(path) as? ImageResult.Bytes)?.bytes

    override suspend fun imageResult(path: String): ImageResult {
        val local = withContext(io) { images.read(path) ?: if (images.isMissing(path)) MISSING else null }
        if (local != null) {
            revalidateImage(path)
            return if (local === MISSING) ImageResult.NotFound else ImageResult.Bytes(local)
        }
        val r = download(path)
        if (r === ImageResult.Unavailable) {
            // Sin red y sin copia: la otra variante sirve (miniatura estirada > nada).
            val other = variantOf(path, if (path.endsWith("/thumb")) "full" else "thumb")
            if (other != path) withContext(io) { images.read(other) }?.let { return ImageResult.Bytes(it) }
            reportMiss()
        }
        return r
    }

    /** Descarga (una sola por ruta aunque la pidan varias pantallas) y la guarda. */
    private suspend fun download(path: String): ImageResult {
        val job = imageInflight.getOrPut(path) {
            // LAZY: arranca ya registrada, para que el remove del final no se adelante al put.
            imageScope.async(start = CoroutineStart.LAZY) {
                try {
                    val r = try {
                        fetchBudgeted { remote.fetchImage(path, null) }
                    } catch (e: Throwable) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        RemoteImage.Failed
                    }
                    when (r) {
                        is RemoteImage.Fetched -> {
                            images.write(path, r.bytes, r.etag)
                            imageChecked[path] = nowMs()
                            ImageResult.Bytes(r.bytes)
                        }
                        RemoteImage.NotFound -> {
                            images.markMissing(path)
                            imageChecked[path] = nowMs()
                            ImageResult.NotFound
                        }
                        else -> ImageResult.Unavailable
                    }
                } finally {
                    imageInflight.remove(path)
                }
            }
        }
        job.start()
        return job.await()
    }

    override fun refreshImage(path: String) {
        for (p in listOf(variantOf(path, "thumb"), variantOf(path, "full"))) {
            imageScope.launch {
                if (!images.known(p)) return@launch
                imageChecked.remove(p)
                revalidateImage(p)
            }
        }
    }

    override fun revalidateImage(path: String) {
        if (immutableImage(path) || !online.value) return
        val now = nowMs()
        val last = imageChecked[path]
        if (last != null && now - last < IMAGE_REVALIDATE_MS) return
        imageChecked[path] = now
        imageScope.launch {
            when (val r = remote.fetchImage(path, images.etag(path))) {
                is RemoteImage.Fetched -> {
                    val old = images.read(path)
                    images.write(path, r.bytes, r.etag)
                    if (old == null || !old.contentEquals(r.bytes)) imageChangesFlow.emit(path)
                }
                RemoteImage.NotFound -> if (!images.isMissing(path)) {
                    images.markMissing(path)
                    imageChangesFlow.emit(path)
                }
                RemoteImage.NotModified -> Unit
                RemoteImage.Failed -> imageChecked.remove(path) // se reintenta en la próxima vista
            }
        }
    }

    /**
     * Precarga en segundo plano lo que aún no está en el teléfono (para verlo después
     * sin señal aunque nunca se haya abierto). No revalida: eso pasa al mostrarlas.
     */
    private fun prefetchImages(paths: Collection<String>) {
        if (paths.isEmpty() || !online.value) return
        imageScope.launch {
            val todo = paths.distinct().filterNot { images.known(it) }
            if (todo.isEmpty()) return@launch
            com.alephri.elpuesto.logd(TAG) { "prefetchImages: ${todo.size} por descargar" }
            todo.map { p -> launch { prefetchGate.withPermit { if (!images.known(p)) download(p) } } }
                .forEach { it.join() }
            images.trim(IMAGE_STORE_MAX_BYTES)
        }
    }

    /**
     * Registro por honor SIN señal (en pista casi nunca hay): de los eventos abiertos que
     * están cerca (desde 2 días antes hasta 2 semanas después) se guardan en segundo plano
     * su estado de registro y sus trazados/puestos/activos, para que la pantalla abra y el
     * registro se encole aunque no haya red.
     */
    private fun prefetchRegistrations(agenda: List<AgendaEntry>) {
        if (!online.value) return
        val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.of("America/Mexico_City"))
        val near = agenda.filter { e ->
            val start = e.startsOn ?: return@filter false
            val end = e.endsOn ?: start
            e.registrationEventId != null && e.registration in setOf(RegistrationState.OPEN, RegistrationState.NOT_YET) &&
                today >= start.minus(kotlinx.datetime.DatePeriod(days = 2)) && today <= end.plus(kotlinx.datetime.DatePeriod(days = 14))
        }.mapNotNull { it.registrationEventId }.distinct()
        if (near.isEmpty()) return
        catalogScope.launch {
            for (eventId in near) {
                refetch(registrationKey(eventId)) { remote.registration(eventId) }
                val reg = db.getCatalog(registrationKey(eventId))
                    ?.let { decodeOrNull<com.alephri.elpuesto.model.EventRegistration>(it) } ?: continue
                if (reg.circuitId.isNotBlank()) refetch("trazados:${reg.circuitId}") { remote.trazados(reg.circuitId) }
                reg.trazadoIds.forEach { tz ->
                    refetch("puestos:$tz") { remote.puestos(tz) }
                    refetch("assets:$tz") { remote.assets(tz) }
                }
            }
        }
    }

    /** Las imágenes vivían como blobs en `catalog` ("img:<ruta>"): pasan a archivos. */
    private fun migrateLegacyImages() {
        val keys = runCatching { db.catalogKeys("img:") }.getOrDefault(emptyList())
        if (keys.isEmpty()) return
        for (key in keys) {
            db.getCatalog(key)?.let { images.write(key.removePrefix("img:"), it, null) }
            db.deleteCatalog(key)
        }
        com.alephri.elpuesto.logd(TAG) { "migrateLegacyImages: ${keys.size} imágenes pasaron a archivos" }
    }

    override fun agenda(): Flow<List<AgendaEntry>> = agendaFlow()

    override suspend fun tripItems(eventId: String) =
        cachedCatalog("trip:$eventId", emptyList()) { remote.tripItems(eventId) }

    override suspend fun myTripItems() = cachedCatalog("trip:mine", emptyList()) { remote.myTripItems() }

    // —— Agenda editable: red primero; sin conexión se aplica local y se encola ——
    // Ids offline = "local-…"; al drenar el create el server asigna el real y el mapa
    // persistido (outboxPrefs) traduce las operaciones posteriores.
    override suspend fun createTripItem(item: TripItem): TripItem? {
        val mark = remote.busySignal().count
        val created = remote.createTripItem(item)
        if (created != null) { syncAfterTripChange(created.eventId); return created }
        if (rejectedByServer(mark)) return null // el servidor lo rechazó: error real
        val local = item.copy(id = "local-" + Uuid.random(), personal = true)
        applyTripLocally(local, delete = false)
        db.enqueue("trip-create", local.id, ojson.encodeToString(local), nowMs())
        return local
    }

    override suspend fun uploadTripPhoto(item: TripItem, jpegBytes: ByteArray): Boolean {
        val pendingCreate = hasPendingCreate(item.id)
        if (!pendingCreate) {
            val mark = remote.busySignal().count
            if (remote.uploadTripPhoto(realId(item.id), jpegBytes)) {
                putLocalImage("/images/trip/${item.id}/full", jpegBytes)
                syncAfterTripChange(item.eventId)
                return true
            }
            if (rejectedByServer(mark)) return false
        }
        // Copia local para la miniatura del timeline; el drain sube la real.
        putLocalImage("/images/trip/${item.id}/full", jpegBytes)
        db.enqueue(
            "trip-photo", item.id,
            Base64.Default.encode(jpegBytes), nowMs(),
        )
        return true
    }

    override suspend fun updateTripItem(item: TripItem): Boolean {
        if (hasPendingCreate(item.id)) {
            // Creado offline y aún no drenado: reescribe el create encolado (conservando
            // sus fotos DESPUÉS, para que el drain corra create → foto en orden).
            val rows = db.selectOutbox().filter { it.refId == item.id }
            rows.forEach { db.deleteOutbox(it.seq) }
            db.enqueue("trip-create", item.id, ojson.encodeToString(item.copy(personal = true)), nowMs())
            rows.filter { it.kind == "trip-photo" }.forEach { db.enqueue(it.kind, it.refId, it.payload, it.createdAt) }
            applyTripLocally(item.copy(personal = true), delete = false)
            return true
        }
        val id = realId(item.id)
        val mark = remote.busySignal().count
        val updated = remote.updateTripItem(item.copy(id = id))
        if (updated != null) { syncAfterTripChange(updated.eventId); return true }
        if (rejectedByServer(mark)) return false
        applyTripLocally(item, delete = false)
        db.enqueue("trip-update", id, ojson.encodeToString(item.copy(id = id, personal = true)), nowMs())
        return true
    }

    override suspend fun deleteTripItem(item: TripItem): Boolean {
        if (hasPendingCreate(item.id)) {
            // Nunca llegó al servidor: basta con tirar sus filas encoladas.
            db.selectOutbox().filter { it.refId == item.id }.forEach { db.deleteOutbox(it.seq) }
            applyTripLocally(item, delete = true)
            return true
        }
        val id = realId(item.id)
        val mark = remote.busySignal().count
        if (remote.deleteTripItem(id)) { syncAfterTripChange(item.eventId); return true }
        if (rejectedByServer(mark)) return false
        // Órdenes pendientes sobre un ítem que va a morir: ya no tienen caso.
        db.selectOutbox()
            .filter { it.refId == id && (it.kind == "trip-update" || it.kind == "trip-photo") }
            .forEach { db.deleteOutbox(it.seq) }
        applyTripLocally(item, delete = true)
        db.enqueue("trip-delete", id, item.eventId ?: "", nowMs())
        return true
    }

    // —— Soporte del outbox de agenda/fotos ——

    private val ojson = Json { ignoreUnknownKeys = true }
    /** Traduce un id temporal ("local-…") al real asignado por el server al drenar. */
    private fun realId(id: String): String = prefs.outboxRealId(id) ?: id

    private fun mapId(tempId: String, real: String) = prefs.mapOutboxId(tempId, real)

    private fun hasPendingCreate(id: String): Boolean =
        id.startsWith("local-") &&
            db.selectOutbox().any { it.kind == "trip-create" && it.refId == id }

    private fun tripToAgenda(item: TripItem): AgendaEntry? =
        if (item.kind == TripItemKind.PHOTO || item.kind == TripItemKind.NOTE) null
        else AgendaEntry(
            id = item.id,
            kind = if (item.kind == TripItemKind.REMINDER) AgendaKind.REMINDER else AgendaKind.TRIP,
            title = item.title, at = item.at, allDay = false, location = item.detail,
            eventId = item.eventId, personal = true, convocatoriaId = item.convocatoriaId,
            tripKind = item.kind, roundId = item.roundId, endsAt = item.endsAt,
        )

    /** Aplica una mutación de planeación a las cachés locales (agenda + bitácoras). */
    private fun applyTripLocally(item: TripItem, delete: Boolean) {
        val touched = mutableListOf<String>()
        db.transaction {
            val rows = db.selectAgenda().mapNotNull { decodeOrNull<AgendaEntry>(it) }
            val next = rows.filterNot { it.id == item.id } +
                (if (delete) emptyList() else listOfNotNull(tripToAgenda(item)))
            db.deleteAgenda()
            next.forEachIndexed { i, a -> db.insertAgenda(a.id, i.toLong(), encode(a)) }

            fun patch(key: String) {
                val cached = db.getCatalog(key)?.let { decodeOrNull<List<TripItem>>(it) }
                if (cached == null && delete) return
                val without = cached.orEmpty().filterNot { it.id == item.id }
                db.putCatalog(key, encode(if (delete) without else without + item))
                touched += key
            }
            patch("trip:mine")
            item.eventId?.let { patch("trip:$it") }
        }
        touched.forEach(::notifyChanged) // ya con el commit hecho
        // Un REMINDER creado/editado offline también programa su alarma local.
        val agendaNow = db.selectAgenda().mapNotNull { decodeOrNull<AgendaEntry>(it) }
        platform.onAgendaSynced(agendaNow)
    }

    /** Tras mutar la planeación, refresca agenda (tabla → Flows) y cachés de trip en UNA transacción. */
    private suspend fun syncAfterTripChange(eventId: String?) {
        try {
            val agenda = remote.agenda()
            val mine = remote.myTripItems()
            val evTrip = eventId?.let { remote.tripItems(it) }
            db.transaction {
                db.deleteAgenda()
                agenda.forEachIndexed { i, a -> db.insertAgenda(a.id, i.toLong(), encode(a)) }
                db.putCatalog("trip:mine", encode(mine))
                if (eventId != null && evTrip != null) db.putCatalog("trip:$eventId", encode(evTrip))
            }
            val now = nowMs()
            catalogChecked["trip:mine"] = now // recién traídos: la recarga no los re-pide
            notifyChanged("trip:mine")
            if (eventId != null && evTrip != null) { catalogChecked["trip:$eventId"] = now; notifyChanged("trip:$eventId") }
            platform.onAgendaSynced(agenda)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.alephri.elpuesto.logd(TAG) { "syncAfterTripChange: falló (${e.message}); la caché se actualizará en el próximo refresh" }
        }
    }

    override suspend fun emergencyAccesses(): List<EmergencyAccess> =
        cachedCatalog("accesses:me", emptyList()) { remote.myEmergencyAccesses() }

    // Emergencia ajena: SIEMPRE directo al backend (queda auditada) y sin caché local.
    override suspend fun officerEmergency(officerId: String): EmergencyView =
        remote.officerEmergency(officerId)

    // —— Catálogos offline-first: caché primero si la pantalla se entera de los cambios;
    // si no, red primero → caché (ver cachedValue). Sin nada = [fallback] (vacío), nunca
    // datos inventados. `fetch` (remote/HttpRepository) es estricto: lanza al fallar. ——
    private suspend inline fun <reified T> cachedCatalog(key: String, fallback: List<T>, crossinline fetch: suspend () -> List<T>): List<T> =
        cachedValue<List<T>>(key) { fetch() } ?: fallback

    override suspend fun circuits() = cachedCatalog("circuits", emptyList()) { remote.circuits() }.also { list ->
        // Logos: miniatura siempre; completo donde el catálogo lo pone al centro (sin silueta).
        prefetchImages(list.flatMap { c ->
            val thumb = "/images/circuit/${c.id}/thumb"
            if ((c.mainTrazado?.path?.size ?: 0) < 3) listOf(thumb, "/images/circuit/${c.id}/full") else listOf(thumb)
        })
    }
    override suspend fun trazados(circuitId: String) =
        cachedCatalog("trazados:$circuitId", emptyList()) { remote.trazados(circuitId) }.also { list ->
            // Mapa oficial: solo se usa sin silueta dibujada (misma regla que la UI).
            prefetchImages(list.filter { it.path.size < 3 }.mapNotNull { it.mapUrl })
        }
    override suspend fun puestos(trazadoId: String) =
        cachedCatalog("puestos:$trazadoId", emptyList()) { remote.puestos(trazadoId) }
    override suspend fun assets(trazadoId: String) =
        cachedCatalog("assets:$trazadoId", emptyList()) { remote.assets(trazadoId) }
    override suspend fun series() = cachedCatalog("series", emptyList()) { remote.series() }
        .also { list -> prefetchImages(list.mapNotNull { it.emblemUrl?.let { u -> variantOf(u, "thumb") } }) }
    override suspend fun championships() = cachedCatalog("championships", emptyList()) { remote.championships() }
    override suspend fun categories(championshipId: String) =
        cachedCatalog("categories:$championshipId", emptyList()) { remote.categories(championshipId) }
    override suspend fun standings(categoryId: String) = cachedCatalog("standings:$categoryId", emptyList()) { remote.standings(categoryId) }
    override suspend fun rounds(categoryId: String) = cachedCatalog("rounds:$categoryId", emptyList()) { remote.rounds(categoryId) }
    override suspend fun drivers(categoryId: String) = cachedCatalog("drivers:$categoryId", emptyList()) { remote.drivers(categoryId) }
    override suspend fun convocatorias(past: Boolean) =
        cachedCatalog(
            if (past) "convocatorias:past" else "convocatorias:open",
            emptyList(),
        ) { remote.convocatorias(past) }
    override suspend fun convocatoria(id: String) =
        (convocatorias(false) + convocatorias(true)).firstOrNull { it.id == id }

    // —— Compartir ubicación: la allowlist vive en el backend (red → caché "locsharing").
    // Escrituras: red primero; sin conexión se encolan (loc-enabled / loc-share /
    // loc-hidden, coalescing por destino) y se SUPERPONEN al leer, así un fetch nunca
    // "deshace" lo pendiente (mismo principio que el pase de lista). ——
    private val locsharingKey = "locsharing"

    /** Payload de loc-share: la operación y el oficial completo (para pintarlo offline). */
    @Serializable
    internal data class QueuedShare(val add: Boolean, val officer: Officer? = null)

    override suspend fun locationSharing(): com.alephri.elpuesto.model.LocationSharing {
        val base = cachedValue(locsharingKey) { remote.locationSharing() } ?: com.alephri.elpuesto.model.LocationSharing()
        return overlayPendingLocation(base)
    }

    private fun overlayPendingLocation(base: com.alephri.elpuesto.model.LocationSharing): com.alephri.elpuesto.model.LocationSharing {
        var out = base
        db.selectOutbox().forEach { o ->
            when (o.kind) {
                "loc-enabled" -> out = out.copy(enabled = o.payload.toBoolean())
                "loc-share" -> {
                    val p = runCatching { ojson.decodeFromString<QueuedShare>(o.payload) }.getOrNull() ?: return@forEach
                    val rest = out.sharesWith.filterNot { it.id == o.refId }
                    out = out.copy(sharesWith = if (p.add && p.officer != null) rest + p.officer else rest)
                }
                "loc-hidden" -> out = out.copy(
                    hiddenIds = out.hiddenIds.filterNot { it == o.refId } + listOfNotNull(o.refId.takeIf { o.payload.toBoolean() }),
                )
            }
        }
        return out
    }

    /** Red primero; offline (o fallo de red) → cola. Un rechazo real no se encola. */
    private suspend fun locationWrite(
        kind: String,
        refId: String,
        payload: String,
        call: suspend () -> com.alephri.elpuesto.model.LocationSharing?,
    ) {
        val updated = try {
            call()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            db.selectOutbox().filter { it.kind == kind && it.refId == refId }.forEach { db.deleteOutbox(it.seq) }
            db.enqueue(kind, refId, payload, nowMs())
            notifyChanged(locsharingKey) // lo encolado se superpone al leer
            return
        }
        updated?.let { storeCatalog(locsharingKey, encode(it)) }
    }

    override suspend fun setLocationSharingEnabled(enabled: Boolean) {
        // Encender de nuevo levanta la pausa manual del evento.
        if (enabled) prefs.setLocationPausedEvent(null)
        locationWrite("loc-enabled", "me", enabled.toString()) { remote.setLocationEnabled(enabled) }
    }

    override suspend fun removeLocationShare(officerId: String) =
        locationWrite("loc-share", officerId, ojson.encodeToString(QueuedShare(add = false))) {
            remote.removeLocationShare(officerId)
        }

    override suspend fun addLocationShare(officer: Officer) =
        locationWrite("loc-share", officer.id, ojson.encodeToString(QueuedShare(add = true, officer = officer))) {
            remote.addLocationShare(officer.id)
        }

    override suspend fun setLocationHidden(officerId: String, hidden: Boolean) =
        locationWrite("loc-hidden", officerId, hidden.toString()) { remote.setLocationHidden(officerId, hidden) }

    override suspend fun eventLocations(eventId: String): List<com.alephri.elpuesto.model.LivePosition> =
        try {
            remote.eventLocations(eventId)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            emptyList() // sin red no hay posiciones vivas (nunca se cachean)
        }

    override suspend fun sendLocation(update: com.alephri.elpuesto.model.LocationUpdate) = remote.sendLocation(update)
    override suspend fun clearLocation() = remote.clearLocation()
    override fun locationPausedFor(eventId: String) = prefs.locationPausedEvent() == eventId
    override fun setLocationPaused(eventId: String?, paused: Boolean) =
        prefs.setLocationPausedEvent(if (paused) eventId else null)

    override suspend fun searchOfficers(q: String): List<Officer> =
        if (!OfficerSearchRules.ready(q)) emptyList()
        else remote.searchOfficers(q.trim()).take(OfficerSearchRules.MAX_RESULTS)
    override suspend fun notificationPrefs() = prefs.notificationPrefs(com.alephri.elpuesto.model.NotificationPrefs())
    override suspend fun setNotificationPrefs(prefs: com.alephri.elpuesto.model.NotificationPrefs) = this.prefs.saveNotificationPrefs(prefs)

    // Chats: lista y mensajes desde el backend (el tiempo real es milestone aparte).
    override suspend fun chats() = cachedCatalog("chats", emptyList()) { remote.chats() }.also(::prefetchChatImages)

    /** Imagen de cada chat en la variante de la lista (la de evento es la del evento). */
    private fun prefetchChatImages(chats: List<com.alephri.elpuesto.model.Chat>) = prefetchImages(
        chats.map { c ->
            if (c.type == com.alephri.elpuesto.model.ChatType.EVENT && c.eventId != null) "/images/event/${c.eventId}/thumb"
            else "/images/chatimg/${c.id}/thumb"
        },
    )

    override suspend fun createChat(
        name: String,
        description: String?,
        isPrivate: Boolean,
        eventId: String?,
        inviteeIds: List<String>,
    ): com.alephri.elpuesto.model.Chat? {
        val created = remote.createChat(
            com.alephri.elpuesto.model.CreateChatRequest(name, description, isPrivate, eventId, inviteeIds),
        ) ?: return null
        refreshChatsCache()
        return created
    }

    override suspend fun setChatEvent(chatId: String, eventId: String?): Boolean {
        if (!remote.setChatEvent(chatId, eventId)) return false
        refreshChatsCache()
        return true
    }

    override suspend fun setChatJoined(chatId: String, joined: Boolean): Boolean {
        if (!remote.setChatJoined(chatId, joined)) return false
        refreshChatsCache()
        return true
    }

    private suspend fun refreshChatsCache() = refetch("chats") { remote.chats().also(::prefetchChatImages) }

    override suspend fun sendMessage(chatId: String, text: String): Message? {
        val mark = remote.busySignal().count
        val sent = remote.sendMessage(chatId, text)
        if (sent != null) { refreshMessagesCache(chatId); return sent }
        if (rejectedByServer(mark)) return null
        return enqueueChatMessage(chatId, text, jpegBytes = null)
    }

    override suspend fun sendMediaMessage(chatId: String, caption: String, jpegBytes: ByteArray): Message? {
        val mark = remote.busySignal().count
        val sent = remote.sendMediaMessage(chatId, caption, jpegBytes)
        if (sent != null) {
            // La foto que acabas de mandar ya está en el teléfono: no se vuelve a bajar.
            putLocalImage("/images/chatmedia/${sent.id}/full", jpegBytes)
            refreshMessagesCache(chatId)
            return sent
        }
        if (rejectedByServer(mark)) return null
        return enqueueChatMessage(chatId, caption, jpegBytes)
    }

    /** Sin conexión: encola el mensaje y devuelve un eco local (aparece en messages()). */
    private suspend fun enqueueChatMessage(chatId: String, text: String, jpegBytes: ByteArray?): Message {
        val localId = "local-" + Uuid.random()
        val payload = QueuedChatMsg(
            localId, text,
            b64 = jpegBytes?.let { Base64.Default.encode(it) },
        )
        if (jpegBytes != null) putLocalImage("/images/chatmedia/$localId/full", jpegBytes)
        val now = nowMs()
        db.enqueue(if (jpegBytes != null) "chat-media" else "chat-text", chatId, ojson.encodeToString(payload), now)
        notifyChanged("messages:$chatId") // el eco local se superpone al leer
        val me = db.selectOfficer()?.let { decodeOrNull<Officer>(it) }
        // El eco es propio por su id "local-…" (la UI no decide por el nombre).
        return Message(
            id = localId, chatId = chatId, senderId = me?.id, senderName = me?.displayName ?: "Yo",
            text = text, at = kotlinx.datetime.Instant.fromEpochMilliseconds(now),
            mediaType = if (jpegBytes != null) MessageMediaType.IMAGE else null,
        )
    }

    /** Mensajes propios aún encolados (offline), como eco al final de la conversación. */
    private fun pendingChatMessages(chatId: String): List<Message> {
        val rows = db.selectOutbox()
            .filter { (it.kind == "chat-text" || it.kind == "chat-media") && it.refId == chatId }
        if (rows.isEmpty()) return emptyList()
        val me = db.selectOfficer()?.let { decodeOrNull<Officer>(it) }
        return rows.mapNotNull { o ->
            val p = runCatching { ojson.decodeFromString<QueuedChatMsg>(o.payload) }.getOrNull()
                ?: return@mapNotNull null
            Message(
                id = p.localId, chatId = chatId, senderId = me?.id, senderName = me?.displayName ?: "Yo",
                text = p.text, at = kotlinx.datetime.Instant.fromEpochMilliseconds(o.createdAt),
                mediaType = if (o.kind == "chat-media") MessageMediaType.IMAGE else null,
            )
        }
    }

    /** Tras enviar: lo nuevo del chat y la lista de chats (lastPreview) a la caché. */
    private suspend fun refreshMessagesCache(chatId: String) {
        runCatching { newerMessages(chatId) }
        refreshChatsCache()
    }

    // —— Mensajes paginados: la caché guarda la COLA del chat (los últimos
    // [MESSAGES_CACHED]); la conversación pide páginas anteriores a la red cuando se
    // quieren ver, y los avisos en vivo traen solo lo posterior al último guardado. ——

    private fun messagesKey(chatId: String) = "messages:$chatId"

    private fun cachedMessages(chatId: String): List<Message>? =
        db.getCatalog(messagesKey(chatId))?.let { decodeOrNull<List<Message>>(it) }

    /**
     * Une lo guardado con [fresh] (más nuevo): sin repetidos, en orden, solo la cola. Si
     * [fresh] es "lo último" (una página llena) que no toca lo guardado, en medio faltan
     * mensajes: lo guardado se descarta para que la caché nunca tenga huecos.
     */
    private fun mergeTail(cached: List<Message>, fresh: List<Message>, latestPage: Boolean): List<Message> {
        if (fresh.isEmpty()) return cached
        val connects = cached.isEmpty() || fresh.size < MESSAGES_PAGE || cached.any { it.id == fresh.first().id }
        val base = if (latestPage && !connects) emptyList() else cached
        return (base + fresh).distinctBy { it.id }
            .sortedWith(compareBy<Message>({ it.at }, { it.id }))
            .takeLast(MESSAGES_CACHED)
    }

    override suspend fun olderMessages(chatId: String, beforeId: String): List<Message>? =
        try {
            fetchBudgeted { remote.messages(chatId, limit = MESSAGES_PAGE, before = beforeId) }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            null
        }

    override suspend fun newerMessages(chatId: String): List<Message> {
        val epoch = sessionEpoch.load()
        val cached = cachedMessages(chatId).orEmpty()
        val known = cached.map { it.id }.toSet()
        var merged = cached
        val fresh = mutableListOf<Message>()
        try {
            var newest = cached.lastOrNull()?.id
            if (newest == null) {
                val page = fetchBudgeted { remote.messages(chatId, limit = MESSAGES_PAGE) }
                merged = mergeTail(merged, page, latestPage = true)
                fresh += page
            } else {
                // Lo posterior al último guardado, por páginas (pocas: es lo de un rato).
                for (i in 0 until 5) {
                    val page = fetchBudgeted { remote.messages(chatId, limit = MESSAGES_PAGE, after = newest) }
                    merged = mergeTail(merged, page, latestPage = false)
                    fresh += page
                    if (page.size < MESSAGES_PAGE) break
                    newest = page.last().id
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            com.alephri.elpuesto.logd(TAG) { "newerMessages($chatId): falló (${e.message})" }
        }
        if (merged !== cached && sessionEpoch.load() == epoch) {
            storeCatalog(messagesKey(chatId), encode(merged))
            catalogChecked[messagesKey(chatId)] = nowMs()
        }
        return fresh.filter { it.id !in known }.distinctBy { it.id }
    }

    override suspend fun markChatRead(chatId: String): Boolean {
        if (!remote.markChatRead(chatId)) return false
        refreshChatsCache() // la lista cacheada pierde el punto de no leídos al instante
        return true
    }

    override suspend fun chatMembers(chatId: String) =
        // Clave "2": el blob viejo era List<Officer>, incompatible con ChatMember.
        cachedCatalog("chatmembers2:$chatId", emptyList<com.alephri.elpuesto.model.ChatMember>()) { remote.chatMembers(chatId) }
            // Los primeros de la lista (el chat de evento puede tener cientos: el resto al verlos).
            .also { list -> prefetchImages(list.take(60).map { "/images/avatar/${it.officer.id}/thumb" }) }

    override suspend fun addChatMember(chatId: String, officerId: String): String? {
        remote.addChatMember(chatId, officerId)?.let { return it }
        // Aparece como invitación pendiente entre los participantes: recachear.
        refetch("chatmembers2:$chatId") { remote.chatMembers(chatId) }
        refreshChatsCache()
        return null
    }

    override suspend fun reportMessage(chatId: String, messageId: String, reason: String?): String? =
        remote.reportMessage(chatId, messageId, reason)

    override suspend fun reportChat(chatId: String, reason: String?): String? = remote.reportChat(chatId, reason)

    override suspend fun blocks(): List<Officer> = cachedCatalog("blocks:me", emptyList()) { remote.blocks() }

    override suspend fun setBlocked(officerId: String, blocked: Boolean): String? {
        remote.setBlocked(officerId, blocked)?.let { return it }
        refetch("blocks:me") { remote.blocks() }
        // Bloquear también lo saca de la lista de ubicación e invitaciones del servidor.
        refetch(locsharingKey) { remote.locationSharing() }
        refreshChatsCache()
        return null
    }

    override suspend fun reportOfficer(officerId: String, reason: String?): String? = remote.reportOfficer(officerId, reason)

    override suspend fun mutedChatIds(): Set<String> = prefs.mutedChatIds()
    override suspend fun setChatMuted(chatId: String, muted: Boolean) = prefs.setChatMuted(chatId, muted)

    override suspend fun archiveChat(chatId: String): Boolean {
        if (!remote.archiveChat(chatId)) return false
        refreshChatsCache()
        return true
    }

    override suspend fun uploadChatImage(chatId: String, jpegBytes: ByteArray): Boolean {
        if (!remote.uploadChatImage(chatId, jpegBytes)) return false
        storeUploadedImage("/images/chatimg/$chatId/full", jpegBytes, withThumb = true)
        return true
    }
    override suspend fun messages(chatId: String) =
        // La página más reciente, unida a lo guardado (la caché solo crece hasta la cola).
        cachedCatalog(messagesKey(chatId), emptyList<Message>()) {
            mergeTail(cachedMessages(chatId).orEmpty(), remote.messages(chatId, limit = MESSAGES_PAGE), latestPage = true)
        }.also { list ->
            // Avatares de quienes escriben y las fotos recientes: la conversación se ve sin señal.
            prefetchImages(
                list.mapNotNull { m -> m.senderId?.let { "/images/avatar/$it/thumb" } }.distinct() +
                    list.filter { it.mediaType != null }.takeLast(30).map { "/images/chatmedia/${it.id}/full" },
            )
        } + pendingChatMessages(chatId)

    override suspend fun session(sessionId: String): Session? {
        val ev = db.selectActiveEvent()?.let { decodeOrNull<Event>(it) }
        val cached = ev?.let { db.selectSessions(it.id).mapNotNull { b -> decodeOrNull<Session>(b) } }.orEmpty()
        return cached.firstOrNull { it.id == sessionId }
    }

    override fun pendingSync(): Flow<Long> = db.countOutboxFlow()

    // —— Escritura del checklist: local + outbox ——
    override suspend fun setChecklistDone(itemId: String, done: Boolean) {
        db.setChecklistDone(if (done) 1L else 0L, itemId)
        db.enqueue("checklist", itemId, done.toString(), nowMs())
        drainOutbox()
    }

    // —— Pase de lista (asistencia por día): caché POR DÍA + outbox ——
    // La clave de caché lleva el día CDMX: la de ayer nunca se muestra como "hoy"
    // (queda huérfana en catalog, como las imágenes viejas — inocuo). La caché guarda
    // la verdad del SERVER; las órdenes encoladas se superponen al LEER, así un fetch
    // nunca "des-marca" lo pendiente (mismo principio que el checklist en refresh()).

    private fun hoyCdmx(): String =
        kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.of("America/Mexico_City")).toString()

    private fun attendanceKey(eventId: String) = "attendance:$eventId:${hoyCdmx()}"

    override suspend fun attendance(eventId: String): List<AttendanceEntry> =
        overlayPendingAttendance(
            eventId,
            cachedCatalog(attendanceKey(eventId), emptyList()) { remote.attendance(eventId) },
        )

    override suspend fun setAttendance(eventId: String, officerId: String, present: Boolean?) {
        // Coalescing: solo la ÚLTIMA orden por oficial+evento queda en la cola.
        val ref = "$eventId|$officerId"
        db.selectOutbox()
            .filter { it.kind == "attendance" && it.refId == ref }
            .forEach { db.deleteOutbox(it.seq) }
        db.enqueue("attendance", ref, present?.toString() ?: "null", nowMs())
        notifyChanged(attendanceKey(eventId)) // la orden encolada se superpone al leer
        drainOutbox()
    }

    private fun overlayPendingAttendance(eventId: String, base: List<AttendanceEntry>): List<AttendanceEntry> {
        val pending = db.selectOutbox()
            .filter { it.kind == "attendance" && it.refId.startsWith("$eventId|") }
        if (pending.isEmpty()) return base
        val myId = db.selectOfficer()?.let { decodeOrNull<Officer>(it)?.id }.orEmpty()
        val myPuesto = db.selectAssignment(eventId)
            ?.let { decodeOrNull<Assignment>(it)?.puestoId }.orEmpty()
        var out = base
        for (o in pending) {
            val off = o.refId.substringAfter("|")
            val pres = o.payload.toBooleanStrictOrNull() // "null" = desmarcar
            out = out.filterNot { it.officerId == off } + listOfNotNull(
                pres?.let {
                    AttendanceEntry(
                        officerId = off, day = kotlinx.datetime.LocalDate.parse(hoyCdmx()),
                        present = it, markedBy = myId,
                        markedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(o.createdAt).toString(),
                        puestoId = myPuesto,
                    )
                },
            )
        }
        return out
    }

    // —— Registro por honor ("yo trabajé este evento"): red primero; sin conexión se
    // encola (una orden por evento: gana la última). La caché guarda la verdad del SERVER
    // y la orden pendiente se superpone al LEER, como el pase de lista. ——

    /** Payload del outbox `participation` (refId = evento): [req] null = quitar mi registro. */
    @Serializable
    internal data class QueuedParticipation(
        val req: com.alephri.elpuesto.model.SetParticipationRequest? = null,
        /** Nombre de la posición para pintarla mientras no se envía. */
        val positionLabel: String = "",
        /** Punto del puesto PROPUESTO (para dibujarlo mientras no se envía). */
        val proposalPoint: com.alephri.elpuesto.model.MapPoint? = null,
    )

    private fun registrationKey(eventId: String) = "registration:$eventId"

    private fun pendingParticipation(eventId: String): QueuedParticipation? =
        db.selectOutbox().lastOrNull { it.kind == "participation" && it.refId == eventId }
            ?.let { runCatching { ojson.decodeFromString<QueuedParticipation>(it.payload) }.getOrNull() }

    override suspend fun registration(eventId: String): com.alephri.elpuesto.model.EventRegistration? {
        val reg = cachedValue(registrationKey(eventId)) { remote.registration(eventId) } ?: return null
        val p = pendingParticipation(eventId) ?: return reg
        return reg.copy(
            mine = p.req?.let { r ->
                com.alephri.elpuesto.model.Participation(
                    id = reg.mine?.id ?: "local-$eventId", eventId = eventId, role = r.role,
                    positionId = r.positionId, positionLabel = if (r.proposal == null) p.positionLabel else "", days = r.days,
                    proposal = r.proposal?.let { np ->
                        com.alephri.elpuesto.model.PuestoProposal(
                            id = reg.mine?.proposal?.id ?: "local-$eventId", trazadoId = np.trazadoId, label = np.label,
                            lat = np.lat, lon = np.lon, point = p.proposalPoint,
                        )
                    },
                )
            },
        )
    }

    override suspend fun participationPending(eventId: String): Boolean = pendingParticipation(eventId) != null

    override suspend fun setParticipation(
        eventId: String,
        req: com.alephri.elpuesto.model.SetParticipationRequest,
        positionLabel: String,
        proposalPoint: com.alephri.elpuesto.model.MapPoint?,
    ): String? = participationWrite(eventId, QueuedParticipation(req, positionLabel, proposalPoint)) {
        when (val r = remote.setParticipation(eventId, req)) {
            is ParticipationWrite.Ok -> null
            is ParticipationWrite.Rejected -> r.message
        }
    }

    override suspend fun deleteParticipation(eventId: String): String? =
        participationWrite(eventId, QueuedParticipation(req = null)) {
            if (remote.deleteParticipation(eventId)) null else "No se pudo quitar tu registro"
        }

    /** null = aceptada (o encolada sin señal); texto = rechazo real del servidor (no se encola). */
    private suspend fun participationWrite(eventId: String, queued: QueuedParticipation, send: suspend () -> String?): String? {
        // Una orden anterior sin enviar queda obsoleta: gana la última.
        db.selectOutbox().filter { it.kind == "participation" && it.refId == eventId }
            .forEach { db.deleteOutbox(it.seq) }
        val rejected = try {
            send()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            db.enqueue("participation", eventId, ojson.encodeToString(queued), nowMs())
            notifyChanged(registrationKey(eventId)) // lo encolado se superpone al leer
            return null
        }
        if (rejected == null) syncAfterParticipation(eventId) else notifyChanged(registrationKey(eventId))
        return rejected
    }

    /**
     * Tras registrar/quitar: el estado del registro, la agenda (el evento aparece o deja de
     * aparecer como "Trabajas"), mi historial y mis logros cambian en el servidor.
     */
    private suspend fun syncAfterParticipation(eventId: String) {
        refetch(registrationKey(eventId)) { remote.registration(eventId) }
        syncAfterTripChange(null) // resincroniza la agenda completa
        myOfficerId()?.let { id ->
            refetch("history:$id") { remote.officerHistory(id) }
            refetch("achievements:$id") { remote.achievements(id) }
        }
    }

    // —— Sincronización desde el backend hacia la caché ——
    override fun eventChanges(eventId: String) = remote.eventChanges(eventId)

    override fun chatChanges() = remote.chatChanges()

    override fun changes() = remote.changes()

    override suspend fun invite(email: String) =
        remote.invite(email).also { err -> if (err == null) refetch("invitations") { remote.myInvitations() } }
    override suspend fun myInvitations() = cachedCatalog("invitations", emptyList()) { remote.myInvitations() }
    // Exportar exige red (acción administrativa, nunca se cachea: lleva la emergencia).
    override suspend fun exportStatus() = remote.exportStatus()
    override suspend fun exportMyData(out: ExportSink) =
        remote.exportMyData(out).also { r ->
            // La descarga queda en el Registro de accesos.
            if (r is ExportResult.Ok) refetch("accesses:me") { remote.myEmergencyAccesses() }
        }

    override suspend fun refresh() {
        try {
            val t0 = nowMs()
            val epoch = sessionEpoch.load()
            com.alephri.elpuesto.logd(TAG) { "refresh: descargando datos remotos…" }
            // 1) Red PRIMERO, sin tocar la caché: la UI no debe ver estados intermedios.
            val me = overlayPendingProfile(remote.me())
            val ev = remote.activeEvent()
            val assignment = ev?.let { remote.assignment(it.id) }
            val mates = ev?.let { remote.mates(it.id) }.orEmpty()
            val schedule = ev?.let { remote.schedule(it.id) }.orEmpty()
            val circuitName = ev?.let { remote.circuitName(it.circuitId) }
            val checklist = ev?.let { remote.checklist(it.id) }.orEmpty()
            val agenda = remote.agenda()
            com.alephri.elpuesto.logd(TAG) { "refresh: remoto listo en ${nowMs() - t0}ms; escribiendo caché en 1 transacción" }
            // La sesión terminó (o cambió de cuenta) mientras se descargaba: no se guarda.
            if (sessionEpoch.load() != epoch) return

            // 2) TODAS las escrituras en UNA transacción: SQLDelight difiere las
            //    notificaciones a los Flows hasta el commit → una sola emisión por tabla,
            //    sin estados intermedios (antes deleteAgenda→insert emitía una lista vacía
            //    y la UI parpadeaba skeleton↔contenido).
            db.transaction {
                // Solo el oficial autenticado vive en la caché (ver clearOfficers en .sq).
                db.clearOfficers()
                db.upsertOfficer(me.id, encode(me))
                // SIEMPRE (aun con ev == null): si el admin desactivó el evento, el
                // cacheado deja de ser "en curso" — antes se quedaba pegado para siempre.
                db.clearActiveEvent()
                if (ev != null) {
                    db.upsertEvent(ev.id, encode(ev), 1L)
                    assignment?.let { db.upsertAssignment(ev.id, encode(it)) }

                    db.deleteMates(ev.id)
                    mates.forEachIndexed { i, m -> db.insertMate(ev.id, m.officerId, i.toLong(), encode(m)) }

                    db.deleteSessions(ev.id)
                    schedule.forEachIndexed { i, s -> db.insertSession(s.id, ev.id, i.toLong(), encode(s)) }

                    circuitName?.let { db.upsertCircuit(ev.circuitId, it) }

                    // Checklist: preservar cambios locales que aún están en el outbox.
                    val pending = db.selectOutbox().filter { it.kind == "checklist" }.map { it.refId }.toSet()
                    val localDone = db.selectChecklist(ev.id).associate { it.id to (it.done != 0L) }
                    db.deleteChecklist(ev.id)
                    checklist.forEachIndexed { i, c ->
                        val done = if (c.id in pending) (localDone[c.id] ?: c.done) else c.done
                        db.insertChecklist(c.id, ev.id, i.toLong(), c.text, if (done) 1L else 0L)
                    }
                }
                db.deleteAgenda()
                agenda.forEachIndexed { i, a -> db.insertAgenda(a.id, i.toLong(), encode(a)) }
            }
            platform.onAgendaSynced(agenda)
            com.alephri.elpuesto.logd(TAG) { "refresh: caché actualizada (${nowMs() - t0}ms total)" }
            // La lista de chats depende del evento ACTIVO (evento/puesto): se resincroniza
            // en cada refresh para que p. ej. desactivar un evento la corrija aunque el
            // hub de chats no esté abierto.
            refreshChatsCache()
            // Refrescar = también volver a preguntar por las imágenes y los catálogos al
            // mostrarlos (un cambio del admin dispara refresh: la siguiente lectura revalida).
            imageChecked.clear()
            catalogChecked.clear()
            prefetchImages(
                buildList {
                    add("/images/avatar/${me.id}/thumb"); add("/images/avatar/${me.id}/full")
                    mates.forEach { add("/images/avatar/${it.officerId}/thumb") }
                    ev?.let { add("/images/event/${it.id}/full"); add("/images/event/${it.id}/thumb") }
                    agenda.mapNotNull { it.championshipEmblemUrl }.distinct().forEach { add(variantOf(it, "thumb")) }
                },
            )
            prefetchRegistrations(agenda)
            drainOutbox()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ error
            // Offline: se conserva la caché y el banner refleja online=false (desde remote).
            com.alephri.elpuesto.logd(TAG) { "refresh: falló (${e.message}); se conserva la caché" }
        }
    }

    /**
     * ¿El servidor rechazó de verdad lo que se envió? Solo si respondió (online) y no fue
     * un 429/5xx desde [mark]: con eso (o sin red) el cambio se encola y se reintenta.
     */
    private fun rejectedByServer(mark: Long): Boolean = remote.online.value && remote.busySignal().count == mark

    /** 5xx seguidos por fila del outbox (en memoria: al reiniciar la app se vuelve a intentar). */
    private val busyStrikes = SafeMap<Long, Int>()
    private val drainRetryScheduled = AtomicBoolean(false)

    /** Un solo reintento programado del drenaje (Retry-After del servidor, entre 5 s y 10 min). */
    private fun scheduleDrainRetry(retryAfterSec: Long?) {
        if (!drainRetryScheduled.compareAndSet(false, true)) return
        catalogScope.launch {
            kotlinx.coroutines.delay((retryAfterSec ?: 30L).coerceIn(5L, 600L) * 1000)
            drainRetryScheduled.store(false)
            drainOutbox()
        }
    }

    private suspend fun drainOutbox() {
        val items = db.selectOutbox()
        if (items.isEmpty()) return
        var tripsTouched = false
        val tripEventIds = mutableSetOf<String?>()
        val chatsTouched = mutableSetOf<String>()
        val attendanceTouched = mutableSetOf<String>()
        val participationTouched = mutableSetOf<String>()
        var profileTouched = false
        for (o in items) {
            val mark = remote.busySignal().count
            remote.takeUploadProblem() // limpia un motivo viejo: el de ESTA fila se lee abajo
            val ok = runCatching {
                when (o.kind) {
                    "checklist" -> remote.ackChecklist(o.refId, o.payload.toBoolean())
                    "onboarding" -> remote.markOnboarded()
                    "loc-enabled", "loc-share", "loc-hidden" -> {
                        val updated = when (o.kind) {
                            "loc-enabled" -> remote.setLocationEnabled(o.payload.toBoolean())
                            "loc-hidden" -> remote.setLocationHidden(o.refId, o.payload.toBoolean())
                            else -> {
                                val p = ojson.decodeFromString<QueuedShare>(o.payload)
                                if (p.add) remote.addLocationShare(o.refId) else remote.removeLocationShare(o.refId)
                            }
                        }
                        updated?.let { storeCatalog(locsharingKey, encode(it)) }
                        updated != null
                    }
                    "attendance" -> remote.ackAttendance(
                        o.refId.substringBefore("|"), o.refId.substringAfter("|"),
                        o.payload.toBooleanStrictOrNull(), // "null" = desmarcar
                    ).also { if (it) attendanceTouched += o.refId.substringBefore("|") }
                    "participation" -> {
                        val p = ojson.decodeFromString<QueuedParticipation>(o.payload)
                        participationTouched += o.refId // aceptada o rechazada: releer la verdad
                        if (p.req == null) remote.deleteParticipation(o.refId)
                        else remote.setParticipation(o.refId, p.req) is ParticipationWrite.Ok
                    }
                    "trip-create" -> {
                        val item = ojson.decodeFromString<TripItem>(o.payload)
                        val created = remote.createTripItem(item.copy(id = ""))
                        if (created != null) {
                            mapId(o.refId, created.id)
                            tripsTouched = true; tripEventIds += item.eventId
                        }
                        created != null
                    }
                    "trip-update" -> {
                        val item = ojson.decodeFromString<TripItem>(o.payload)
                        val updated = remote.updateTripItem(item.copy(id = realId(item.id)))
                        if (updated != null) { tripsTouched = true; tripEventIds += item.eventId }
                        updated != null
                    }
                    "trip-delete" -> remote.deleteTripItem(realId(o.refId))
                        .also { if (it) { tripsTouched = true; tripEventIds += o.payload.ifBlank { null } } }
                    "trip-photo" -> {
                        val id = realId(o.refId)
                        // Si sigue "local-", su create falló antes: no hay a dónde subirla.
                        if (id.startsWith("local-")) false
                        else {
                            val bytes = Base64.Default.decode(o.payload)
                            remote.uploadTripPhoto(id, bytes).also {
                                if (it) {
                                    tripsTouched = true
                                    // Con su id real ya no se vuelve a bajar.
                                    if (id != o.refId) putLocalImage("/images/trip/$id/full", bytes)
                                }
                            }
                        }
                    }
                    "profile" -> {
                        profileTouched = true // aceptado o rechazado: releer la verdad
                        val p = ojson.decodeFromString<QueuedProfile>(o.payload)
                        // La cola ya no la tiene cuando se escriba: la caché queda con lo del server.
                        remote.updateMe(p.displayName, p.area)
                            ?.let { server -> db.upsertOfficer(server.id, encode(server)); true } ?: false
                    }
                    "emergency" -> {
                        val info = ojson.decodeFromString<com.alephri.elpuesto.model.EmergencyInfo>(o.payload)
                        remote.updateEmergency(info).also { if (it) storeCatalog("emergency:me", encode(info)) }
                    }
                    "chat-text" -> {
                        val p = ojson.decodeFromString<QueuedChatMsg>(o.payload)
                        (remote.sendMessage(o.refId, p.text) != null).also { if (it) chatsTouched += o.refId }
                    }
                    "chat-media" -> {
                        val p = ojson.decodeFromString<QueuedChatMsg>(o.payload)
                        val bytes = p.b64?.let { Base64.Default.decode(it) }
                        if (bytes == null) true // payload corrupto: descartar
                        else remote.sendMediaMessage(o.refId, p.text, bytes)?.let { sent ->
                            chatsTouched += o.refId
                            putLocalImage("/images/chatmedia/${sent.id}/full", bytes)
                            withContext(io) { images.delete("/images/chatmedia/${p.localId}/full") }
                            true
                        } ?: false
                    }
                    else -> true // kind desconocido (versión vieja): descartar
                }
            }.getOrDefault(false)
            if (ok) {
                db.deleteOutbox(o.seq)
                busyStrikes.remove(o.seq)
            } else if (remote.busySignal().count != mark) {
                // 429 (límite de uso) o 5xx (servidor con problemas): NO es un rechazo del
                // cambio. Se conserva y se reintenta después, respetando Retry-After. Un 5xx
                // que se repite con el MISMO cambio 5 veces sí se descarta: no atorar la cola.
                val signal = remote.busySignal()
                val strikes = if (signal.status >= 500) busyStrikes.merge(o.seq, 1, Int::plus) else 0
                if (strikes >= 5) {
                    com.alephri.elpuesto.logd(TAG) { "drainOutbox: ${o.kind}/${o.refId} falla con ${signal.status} 5 veces; se descarta" }
                    db.deleteOutbox(o.seq)
                    busyStrikes.remove(o.seq)
                    continue
                }
                com.alephri.elpuesto.logd(TAG) { "drainOutbox: servidor ocupado (${signal.status}); se reintenta en ${signal.retryAfterSec ?: 30} s" }
                scheduleDrainRetry(signal.retryAfterSec)
                break
            } else {
                runCatching { remote.ping() } // actualiza online con un sondeo real
                if (remote.online.value) {
                    // El servidor es alcanzable y aun así falló: rechazo real. Se descarta
                    // para no atorar la fila detrás (best-effort, como el resto del outbox).
                    com.alephri.elpuesto.logd(TAG) { "drainOutbox: ${o.kind}/${o.refId} rechazado; se descarta" }
                    db.deleteOutbox(o.seq)
                    // Una foto encolada que el servidor no aceptó (cupo lleno…): el oficial ya
                    // no está en la pantalla donde la tomó; se le avisa con su motivo.
                    if (o.kind == "trip-photo" || o.kind == "chat-media") {
                        remote.takeUploadProblem()?.let { platform.onUploadRejected(it) }
                    }
                } else {
                    break // sin conexión: se reintenta en el próximo drenaje
                }
            }
        }
        if (tripsTouched) {
            val events = tripEventIds.filterNotNull().distinct()
            if (events.isEmpty()) syncAfterTripChange(null) else events.forEach { syncAfterTripChange(it) }
        }
        chatsTouched.forEach { refreshMessagesCache(it) }
        // Ya no se superpone lo encolado: la caché debe traer la verdad del servidor.
        attendanceTouched.forEach { ev -> refetch(attendanceKey(ev)) { remote.attendance(ev) } }
        participationTouched.forEach { syncAfterParticipation(it) }
        // El perfil se pintó con lo capturado (overlay): si el servidor lo rechazó (p. ej. un
        // nombre que no pasa NameRules) la caché no debe quedarse con ese nombre.
        if (profileTouched && pendingProfile() == null) {
            runCatching { remote.me() }.getOrNull()?.let { me ->
                db.transaction { db.clearOfficers(); db.upsertOfficer(me.id, encode(me)) }
            }
        }
        // Cola vacía: los mapas temp→real ya no tienen referencias pendientes.
        if (db.selectOutbox().isEmpty()) prefs.clearOutboxIds()
    }
}
