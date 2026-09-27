@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
import com.alephri.elpuesto.model.CreateChatRequest
import com.alephri.elpuesto.model.SendMessageRequest
import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.EmergencyAccess
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.Message
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.UpdateProfileRequest
import com.alephri.elpuesto.model.AuthResult
import com.alephri.elpuesto.model.CallbackRequest
import com.alephri.elpuesto.model.MagicLinkRequest
import com.alephri.elpuesto.model.MagicLinkResponse
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.PuestoMate
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.readText
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn

/**
 * Repositorio conectado al backend Ktor por HTTP + **protobuf** (contratos de `shared`).
 *
 * Estricto: si el backend no responde, lanza y marca `online=false` (banner offline); la
 * caché SQLDelight + outbox ([OfflineRepository]) decide qué mostrar sin conexión.
 *
 * baseUrl por defecto = `10.0.2.2` (loopback del host desde el emulador de Android).
 */
@OptIn(ExperimentalAtomicApi::class)
class HttpRepository(
    private val baseUrl: String,
    private val accessToken: () -> String? = { null },
    private val refreshToken: () -> String? = { null },
    private val onRefreshed: (access: String, refresh: String?) -> Unit = { _, _ -> },
    /**
     * Cliente WEB (navegador): el refresh token vive en una cookie HttpOnly que la app no
     * ve (se manda la cabecera [WEB_CLIENT_HEADER] y el servidor la usa), y el WebSocket se
     * abre con un boleto de un solo uso (el navegador no puede mandar `Authorization`).
     */
    private val webClient: Boolean = false,
) : ElPuestoRepository {

    private val _online = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    // Protobuf manual (encode/decode desde bytes crudos). NO usamos el ContentNegotiation
    // del cliente: con `Content-Type: application/protobuf; charset=UTF-8` leía el body
    // binario como texto UTF-8 y lo corrompía (0xD5 → U+FFFD), rompiendo la deserialización.
    private val proto = ProtoBuf { encodeDefaults = false }
    private val client = platformHttpClient(streaming = false)

    private val busy = AtomicReference(BusySignal())
    override fun busySignal(): BusySignal = busy.load()

    @Volatile private var uploadProblem: String? = null
    override fun takeUploadProblem(): String? = uploadProblem.also { uploadProblem = null }

    /**
     * Foto rechazada por el servidor: 409 = cupo de fotos lleno, 413 = demasiado grande
     * (su ErrorBody explica). Se guarda el motivo para que quien la subió lo muestre.
     */
    private suspend fun noteUploadRejection(resp: HttpResponse) {
        val s = resp.status.value
        if (s != 409 && s != 413) return
        uploadProblem = runCatching { errorText(resp.readRawBytes()) }.getOrNull()
            ?: if (s == 413) "La foto es demasiado grande." else "No se pudo subir la foto."
    }

    /**
     * Alta rechazada por el servidor con un motivo para el oficial (409 = cupo lleno: 2000
     * entradas de bitácora, 30 chats abiertos; 400 = dato inválido, p. ej. un nombre
     * reservado). Se guarda como [uploadProblem] para que la pantalla muestre el motivo
     * real en vez de "revisa tu conexión".
     */
    private suspend fun noteCreateRejection(resp: HttpResponse) {
        val s = resp.status.value
        if (s != 400 && s != 409) return
        uploadProblem = runCatching { errorText(resp.readRawBytes()) }.getOrNull()
    }

    init {
        // Toda respuesta 429/5xx queda registrada (con su Retry-After): el outbox y los
        // envíos "red primero" la distinguen de un rechazo real.
        client.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            val s = call.response.status.value
            if (s == 429 || s >= 500) {
                val retry = call.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                while (true) {
                    val cur = busy.load()
                    if (busy.compareAndSet(cur, BusySignal(cur.count + 1, s, retry))) break
                }
            }
            call
        }
    }

    // Cliente aparte para el stream SSE: necesita read timeout largo (los keepalive del
    // backend llegan cada 25s; el default de OkHttp los mataría a los 10s).
    private val sseClient = platformHttpClient(streaming = true) {
        install(io.ktor.client.plugins.websocket.WebSockets)
    }

    /** Cabecera del cliente web en las rutas de sesión (modo cookie; ver [webClient]). */
    private fun HttpRequestBuilder.clientKind() {
        if (webClient) header(WEB_CLIENT_HEADER, "web")
    }

    // Pide protobuf explícitamente (sin ContentNegotiation, Ktor ya no lo hace solo; sin
    // este Accept el backend responde JSON) y adjunta el Bearer si hay sesión.
    private fun HttpRequestBuilder.bearer() {
        accept(ContentType.Application.ProtoBuf)
        accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }


    /** Renueva la sesión con el refresh token. */
    private suspend fun refreshSession(): Refresh {
        val rt = refreshToken() ?: return Refresh.REJECTED
        return try {
            val resp = client.post("$baseUrl/auth/refresh") {
                clientKind()
                accept(ContentType.Application.ProtoBuf)
                contentType(ContentType.Application.ProtoBuf)
                setBody(proto.encodeToByteArray(RefreshRequest(rt)))
            }
            when {
                resp.status == HttpStatusCode.OK -> {
                    val r: AuthResult = proto.decodeFromByteArray(resp.readRawBytes())
                    onRefreshed(r.jwt, r.refreshToken)
                    Refresh.OK
                }
                // 429/5xx: el servidor no pudo contestar ahora; la sesión puede seguir viva.
                resp.status.value == 429 || resp.status.value >= 500 -> Refresh.UNAVAILABLE
                else -> Refresh.REJECTED
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            Refresh.UNAVAILABLE
        }
    }

    /** Renueva la sesión con el refresh token; true si obtuvo tokens nuevos. */
    private suspend fun tryRefresh(): Boolean = refreshSession() == Refresh.OK

    /** Cierra la sesión en el servidor (revoca el refresh de este teléfono). Mejor esfuerzo: sin señal no espera. */
    suspend fun revokeSession() {
        val rt = refreshToken() ?: return
        try {
            kotlinx.coroutines.withTimeoutOrNull(3_000) {
                client.post("$baseUrl/auth/logout") {
                    clientKind()
                    accept(ContentType.Application.ProtoBuf)
                    contentType(ContentType.Application.ProtoBuf)
                    setBody(proto.encodeToByteArray(RefreshRequest(rt)))
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    /**
     * GET con Bearer; ante 401 renueva (una vez) y reintenta — transparente al usuario.
     * Actualiza [online] en éxito/fallo (cualquier tráfico refresca el banner de conexión).
     */
    private suspend inline fun <reified T> authedGet(path: String): T {
        val bytes = try {
            val t0 = nowMs()
            var resp: HttpResponse = client.get("$baseUrl$path") { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.get("$baseUrl$path") { bearer() }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "GET ${com.alephri.elpuesto.pathForLog(path)} -> ${resp.status.value} (${nowMs() - t0}ms)" }
            resp.readRawBytes()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            _online.value = false
            throw e
        }
        // Hubo respuesta: estamos en línea aunque el cuerpo no decodifique (403/404/…).
        // Antes un error de decodificación también encendía el banner offline.
        _online.value = true
        return proto.decodeFromByteArray(bytes)
    }

    /**
     * Cambios en vivo del evento (SSE): emite el `kind` de cada cambio. Reconecta solo
     * (con pausa) y renueva el access ante 401; se cierra al cancelar la colección
     * (p. ej. al salir del Modo evento).
     */
    override fun eventChanges(eventId: String): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        while (true) {
            try {
                sseClient.prepareGet("$baseUrl/events/$eventId/stream") {
                    accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    accept(ContentType.parse("text/event-stream"))
                }.execute { resp ->
                    if (resp.status == HttpStatusCode.Unauthorized) { tryRefresh(); return@execute }
                    if (resp.status != HttpStatusCode.OK) return@execute
                    _online.value = true
                    val ch = resp.bodyAsChannel()
                    while (true) {
                        val line = ch.readUTF8Line() ?: break
                        if (line.startsWith("data:")) {
                            val kind = line.substringAfter("\"kind\":\"", "").substringBefore("\"")
                            if (kind.isNotEmpty()) {
                                com.alephri.elpuesto.logd("ElPuestoHttp") { "SSE change: $kind" }
                                emit(kind)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
            }
            kotlinx.coroutines.delay(3_000)
        }
    }

    /**
     * Cambios en vivo del chat (WebSocket): emite el chatId de cada mensaje nuevo que el
     * oficial puede ver. Reconecta solo; renueva el access tras una caída (posible 401).
     */
    // —— Stream general de cambios: UN solo WebSocket (/stream) compartido por todos los
    // colectores (shareIn); chatChanges() es una vista filtrada. La conexión además
    // actualiza [online] (conectado = en línea, caída = sin conexión) en tiempo real.

    private val streamScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + ioDispatcher,
    )
    private val streamJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Boleto de un solo uso para abrir el WebSocket desde el navegador (no puede mandar
     * `Authorization`). null = no se pudo (sin sesión o sin red).
     */
    private suspend fun streamTicket(): String? {
        suspend fun ask() = client.post("$baseUrl/stream/ticket") { bearer() }
        var resp = ask()
        if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) resp = ask()
        if (resp.status != HttpStatusCode.OK) return null
        return proto.decodeFromByteArray<com.alephri.elpuesto.model.StreamTicket>(resp.readRawBytes()).ticket
    }

    private fun rawChanges(): kotlinx.coroutines.flow.Flow<RemoteChange> = kotlinx.coroutines.flow.flow {
        val wsBase = baseUrl.replaceFirst("http", "ws")
        while (true) {
            try {
                val wsUrl = if (webClient) {
                    val ticket = streamTicket() ?: error("sin boleto")
                    "$wsBase/stream/web?ticket=" + ticket.encodeURLQueryComponent(encodeFull = true)
                } else "$wsBase/stream"
                sseClient.webSocket(wsUrl, request = {
                    if (!webClient) accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }) {
                    liveSocket = this
                    _online.value = true
                    for (frame in incoming) {
                        if (frame is io.ktor.websocket.Frame.Text) {
                            val dto = try {
                                streamJson.decodeFromString(StreamChangeDto.serializer(), frame.readText())
                            } catch (e: Exception) {
                                null
                            }
                            if (dto != null) {
                                if (dto.kind != "location") {
                                    com.alephri.elpuesto.logd("ElPuestoHttp") { "WS change: ${dto.kind} ${dto.id ?: ""} ${dto.action ?: ""}" }
                                }
                                emit(RemoteChange(dto.kind, dto.id, dto.action, dto.detail, dto.label, dto.lat, dto.lon, dto.accuracyM, dto.at))
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // Solo la cancelación de ESTE colector termina el flujo; la del socket
                // (closeStreams) es un corte más y se reconecta.
                if (e is CancellationException && kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw e
                if (!socketRestart) _online.value = false // el socket es el latido: caída = sin conexión
            }
            liveSocket = null
            if (socketRestart) {
                // Corte a propósito (cambió la sesión): reconectar ya, con el token nuevo.
                socketRestart = false
                continue
            }
            tryRefresh() // por si la caída fue un access vencido
            kotlinx.coroutines.delay(3_000)
        }
    }

    /** Socket en vivo abierto ahora (se abrió con el token de la sesión de ese momento). */
    @Volatile private var liveSocket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    @Volatile private var socketRestart = false

    /**
     * Corta el socket en vivo: tras cerrar sesión o cambiar de cuenta no debe seguir
     * llegando lo de la sesión anterior (mensajes, ubicaciones). Si hay quien escuche,
     * se reconecta solo con la sesión nueva.
     */
    fun closeStreams() {
        val ws = liveSocket ?: return
        socketRestart = true
        ws.cancel()
    }

    private val sharedChanges by lazy {
        rawChanges().shareIn(
            streamScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            replay = 0,
        )
    }

    override fun changes(): kotlinx.coroutines.flow.Flow<RemoteChange> = sharedChanges

    override fun chatChanges(): kotlinx.coroutines.flow.Flow<String> =
        sharedChanges.mapNotNull { if (it.kind == "chat") it.id else null }

    override suspend fun invite(email: String): String? {
        return try {
            val resp = client.post("$baseUrl/invitations") {
                bearer()
                contentType(ContentType.Application.ProtoBuf)
                setBody(proto.encodeToByteArray(com.alephri.elpuesto.model.InviteRequest(email)))
            }
            val bytes = resp.readRawBytes()
            _online.value = true
            // Éxito = Invitation; rechazo = Ack(ok=false, message). Distinguimos probando.
            try {
                proto.decodeFromByteArray<com.alephri.elpuesto.model.Invitation>(bytes)
                null
            } catch (_: Throwable) {
                val ack = proto.decodeFromByteArray<com.alephri.elpuesto.model.Ack>(bytes)
                ack.message ?: "no se pudo enviar"
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            "sin conexión"
        }
    }

    // Lanza sin red: la caché (OfflineRepository) decide qué mostrar.
    override suspend fun myInvitations(): List<com.alephri.elpuesto.model.Invitation> = authedGet("/invitations")

    override suspend fun exportStatus(): com.alephri.elpuesto.model.ExportStatus? =
        // Tipo explícito NO nulo: ProtoBuf truena al decodificar un tipo nullable en la raíz.
        try { authedGet<com.alephri.elpuesto.model.ExportStatus>("/me/export/status") } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    /**
     * "Descargar mis datos": streaming del ZIP a [out] (puede pesar varios MB por las fotos;
     * cliente con timeout largo). 429 = ya descargó hoy (mensaje del servidor); ante 401
     * renueva la sesión una vez y reintenta.
     */
    override suspend fun exportMyData(out: ExportSink): ExportResult {
        suspend fun attempt(): ExportResult? = sseClient.prepareGet("$baseUrl/me/export") {
            accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, "application/zip, application/json")
        }.execute { resp ->
            when (resp.status) {
                HttpStatusCode.OK -> {
                    val ch = resp.bodyAsChannel()
                    val buf = ByteArray(64 * 1024)
                    var n = 0L
                    while (true) {
                        val read = ch.readAvailable(buf, 0, buf.size)
                        if (read < 0) break
                        if (read > 0) { out.write(buf, 0, read); n += read }
                    }
                    ExportResult.Ok(n)
                }
                HttpStatusCode.Unauthorized -> null
                HttpStatusCode.TooManyRequests -> ExportResult.TooSoon(
                    runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(resp.readRawBytes().decodeToString())
                            .let { it as kotlinx.serialization.json.JsonObject }["error"]
                            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    }.getOrNull() ?: "Ya descargaste tus datos hoy.",
                )
                else -> ExportResult.Failed("El servidor no pudo generar tu copia (${resp.status.value}).")
            }
        }
        return try {
            attempt() ?: if (tryRefresh()) attempt() ?: ExportResult.Failed("Tu sesión venció; vuelve a entrar.") else ExportResult.Failed("Tu sesión venció; vuelve a entrar.")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            ExportResult.Failed("Se perdió la conexión durante la descarga. Intenta de nuevo.")
        }
    }

    override suspend fun onboardingDone(): Boolean? =
        try {
            suspend fun ask() = client.get("$baseUrl/me/onboarding") { bearer() }
            var resp = ask()
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) resp = ask()
            _online.value = true
            // Solo un 200 cuenta: un servidor anterior (404) o con problemas NO significa
            // "no la ha hecho" (se le mostraría otra vez la bienvenida a todo mundo).
            if (resp.status != HttpStatusCode.OK) null
            else proto.decodeFromByteArray<com.alephri.elpuesto.model.OnboardingState>(resp.readRawBytes()).done
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun markOnboarded(): Boolean =
        try {
            authedPost("/me/onboarding", ByteArray(0)).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    /** Sondeo ligero de conectividad: GET /health (sin auth); solo actualiza [online]. */
    override suspend fun ping() {
        try {
            client.get("$baseUrl/health")
            _online.value = true
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            _online.value = false
        }
    }

    /** No-op: se conserva para compatibilidad (ya no hay cache de tokens del plugin). */
    fun clearAuthCache() {}

    // —— Auth ——
    /** Pide el enlace; [challenge] = huella del verificador que guarda este teléfono. */
    suspend fun requestMagicLink(email: String, challenge: String?): MagicLinkResponse {
        val resp = client.post("$baseUrl/auth/magic-link") {
            accept(ContentType.Application.ProtoBuf)
            contentType(ContentType.Application.ProtoBuf)
            setBody(proto.encodeToByteArray(MagicLinkRequest(email, challenge, client = if (webClient) "web" else null)))
        }
        return proto.decodeFromByteArray(resp.readRawBytes())
    }

    /**
     * Canjea el token del enlace presentando el [verifier] guardado al pedirlo. 4xx =
     * rechazo con el motivo del servidor (p. ej. abierto en otro teléfono); 429/5xx o sin
     * red = no se pudo preguntar (el enlace sigue sirviendo).
     */
    suspend fun exchange(token: String, verifier: String?): ExchangeResult =
        try {
            val resp = client.post("$baseUrl/auth/callback") {
                clientKind()
                accept(ContentType.Application.ProtoBuf)
                contentType(ContentType.Application.ProtoBuf)
                setBody(proto.encodeToByteArray(CallbackRequest(token, verifier)))
            }
            val bytes = resp.readRawBytes()
            when {
                resp.status == HttpStatusCode.OK -> ExchangeResult.Ok(proto.decodeFromByteArray<AuthResult>(bytes))
                resp.status.value == 429 || resp.status.value >= 500 -> ExchangeResult.Unreachable
                else -> ExchangeResult.Rejected(errorText(bytes))
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            ExchangeResult.Unreachable
        }

    /** Motivo de un error del backend (`ErrorBody`, en protobuf o JSON); null si no trae. */
    private fun errorText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = if (bytes[0] == '{'.code.toByte()) {
            runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject)["error"]
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            }.getOrNull()
        } else {
            runCatching { proto.decodeFromByteArray<RemoteError>(bytes).error }.getOrNull()
        }
        return text?.trim()?.ifBlank { null }
    }

    /** /me estricto: null si no hay sesión válida (no cae a semilla). Renueva en 401. */
    /**
     * Estado de la sesión al arrancar. Distingue "el servidor dice que no" (sesión
     * inválida) de "no hubo con quién hablar" (sin red): lo segundo NO debe sacarte de la
     * app — en pista se abre sin señal todo el tiempo.
     */
    suspend fun checkSession(): SessionCheck {
        return try {
            var resp: HttpResponse = client.get("$baseUrl/me") { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized) {
                when (refreshSession()) {
                    Refresh.OK -> resp = client.get("$baseUrl/me") { bearer() }
                    // No se pudo renovar por un problema del servidor o de red: no es una
                    // sesión inválida (sacarte de la app aquí te dejaría fuera sin motivo).
                    Refresh.UNAVAILABLE -> return SessionCheck.Unreachable
                    Refresh.REJECTED -> {}
                }
            }
            _online.value = true
            when (resp.status) {
                HttpStatusCode.OK -> SessionCheck.Valid(proto.decodeFromByteArray<Officer>(resp.readRawBytes()).status)
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> SessionCheck.Invalid
                else -> SessionCheck.Unreachable // backend con problemas ≠ sesión inválida
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            _online.value = false
            SessionCheck.Unreachable
        }
    }

    override suspend fun me(): Officer = authedGet("/me")

    override suspend fun activeEvent(): Event? = authedGet<List<Event>>("/events").firstOrNull()

    override suspend fun circuitName(circuitId: String): String =
        authedGet<List<Circuit>>("/circuits").firstOrNull { it.id == circuitId }?.name ?: "—"

    override suspend fun assignment(eventId: String): Assignment? {
        // 404 = sin asignación en el evento (no es error de red).
        val path = "/events/$eventId/assignment/me"
        var resp: HttpResponse = client.get("$baseUrl$path") { bearer() }
        if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) resp = client.get("$baseUrl$path") { bearer() }
        _online.value = true
        if (resp.status == HttpStatusCode.NotFound) return null
        // Tipo explícito NO nulo: con el `Assignment?` del retorno, ProtoBuf intenta
        // decodificar un tipo nullable en la raíz y truena ("Error while decoding
        // Assignment?") — eso tumbaba el refresh entero y el Home nunca veía el evento activo.
        return proto.decodeFromByteArray<Assignment>(resp.readRawBytes())
    }

    override suspend fun mates(eventId: String): List<PuestoMate> = authedGet("/events/$eventId/mates")

    override suspend fun schedule(eventId: String): List<Session> = authedGet("/events/$eventId/schedule")

    override suspend fun checklist(eventId: String): List<ChecklistItem> = authedGet("/events/$eventId/checklist")

    override suspend fun agenda(): List<AgendaEntry> = authedGet("/agenda")

    // —— Catálogos: estrictos (lanzan al fallar). OfflineRepository maneja caché + semilla. ——
    override suspend fun circuits(): List<Circuit> = authedGet("/circuits")
    override suspend fun trazados(circuitId: String): List<Trazado> = authedGet("/circuits/$circuitId/trazados")
    override suspend fun puestos(trazadoId: String): List<Puesto> = authedGet("/trazados/$trazadoId/puestos")
    override suspend fun assets(trazadoId: String): List<TrackAsset> = authedGet("/trazados/$trazadoId/assets")
    override suspend fun series(): List<com.alephri.elpuesto.model.Series> = authedGet("/series")
    override suspend fun championships(): List<Championship> = authedGet("/championships")
    override suspend fun categories(championshipId: String): List<Category> = authedGet("/championships/$championshipId/categories")
    override suspend fun standings(categoryId: String): List<Standing> = authedGet("/categories/$categoryId/standings")
    override suspend fun rounds(categoryId: String): List<Round> = authedGet("/categories/$categoryId/rounds")
    override suspend fun drivers(categoryId: String): List<Driver> = authedGet("/categories/$categoryId/drivers")
    override suspend fun convocatorias(past: Boolean): List<Convocatoria> =
        authedGet("/convocatorias" + if (past) "?status=past" else "")

    // —— Perfil / emergencia / viajes / mensajes (fase 2) ——

    /** PUT con Bearer + protobuf; renueva en 401 y reintenta. */
    private suspend fun authedPut(path: String, body: ByteArray): HttpResponse {
        try {
            var resp = client.put("$baseUrl$path") {
                bearer(); contentType(ContentType.Application.ProtoBuf); setBody(body)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.put("$baseUrl$path") {
                    bearer(); contentType(ContentType.Application.ProtoBuf); setBody(body)
                }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "PUT ${com.alephri.elpuesto.pathForLog(path)} -> ${resp.status.value}" }
            _online.value = true
            return resp
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            throw e
        }
    }

    /** POST con Bearer + protobuf; renueva en 401 y reintenta. */
    private suspend fun authedPost(path: String, body: ByteArray): HttpResponse {
        try {
            var resp = client.post("$baseUrl$path") {
                bearer(); contentType(ContentType.Application.ProtoBuf); setBody(body)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post("$baseUrl$path") {
                    bearer(); contentType(ContentType.Application.ProtoBuf); setBody(body)
                }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "POST ${com.alephri.elpuesto.pathForLog(path)} -> ${resp.status.value}" }
            _online.value = true
            return resp
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            throw e
        }
    }

    /** DELETE con Bearer; renueva en 401 y reintenta. */
    private suspend fun authedDelete(path: String): HttpResponse {
        try {
            var resp = client.delete("$baseUrl$path") { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.delete("$baseUrl$path") { bearer() }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "DELETE ${com.alephri.elpuesto.pathForLog(path)} -> ${resp.status.value}" }
            _online.value = true
            return resp
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            throw e
        }
    }

    override suspend fun officer(officerId: String): Officer? =
        try { authedGet<Officer>("/officers/$officerId") } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun searchOfficers(q: String): List<Officer> =
        try {
            authedGet("/officers/search?q=" + q.encodeURLQueryComponent(encodeFull = true))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            emptyList()
        }

    override suspend fun officerHistory(officerId: String): List<OfficerHistoryEntry> =
        authedGet("/officers/$officerId/history")

    override suspend fun commonEvents(officerId: String): List<OfficerHistoryEntry> =
        authedGet("/officers/$officerId/common-events")

    override suspend fun achievements(officerId: String): com.alephri.elpuesto.model.Achievements =
        authedGet("/officers/$officerId/achievements")

    override suspend fun myEmergency(): EmergencyInfo = authedGet("/me/emergency")

    override suspend fun myEmergencyAccesses(): List<EmergencyAccess> = authedGet("/me/emergency/accesses")

    override suspend fun updateMe(displayName: String, area: Area?): Officer? =
        try {
            val resp = authedPut("/me", proto.encodeToByteArray(UpdateProfileRequest(displayName, area)))
            if (resp.status != HttpStatusCode.OK) null else proto.decodeFromByteArray<Officer>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun updateEmergency(info: EmergencyInfo): Boolean =
        try {
            authedPut("/me/emergency", proto.encodeToByteArray(info)).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun tripItems(eventId: String): List<TripItem> = authedGet("/events/$eventId/trip")

    override suspend fun myTripItems(): List<TripItem> = authedGet("/trip")

    override suspend fun createTripItem(item: TripItem): TripItem? =
        try {
            val resp = authedPost("/trip", proto.encodeToByteArray(item))
            if (resp.status != HttpStatusCode.OK) { noteCreateRejection(resp); null } else proto.decodeFromByteArray<TripItem>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun updateTripItem(item: TripItem): TripItem? =
        try {
            val resp = authedPut("/trip/${item.id}", proto.encodeToByteArray(item))
            if (resp.status != HttpStatusCode.OK) null else proto.decodeFromByteArray<TripItem>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun deleteTripItem(id: String): Boolean =
        try {
            authedDelete("/trip/$id").status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    /** Emergencia de otro oficial. Distingue 403 (no autorizado) de error de red. */
    override suspend fun officerEmergency(officerId: String): EmergencyView =
        try {
            var resp = client.get("$baseUrl/officers/$officerId/emergency") { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.get("$baseUrl/officers/$officerId/emergency") { bearer() }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "GET /officers/$officerId/emergency -> ${resp.status.value}" }
            _online.value = true
            when (resp.status) {
                HttpStatusCode.OK -> EmergencyView.Granted(proto.decodeFromByteArray<EmergencyInfo>(resp.readRawBytes()))
                HttpStatusCode.Forbidden -> EmergencyView.Forbidden
                else -> EmergencyView.Unavailable
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _online.value = false
            EmergencyView.Unavailable
        }

    override suspend fun chats(): List<Chat> = authedGet("/chats")

    override suspend fun messages(chatId: String, limit: Int?, before: String?, after: String?): List<Message> {
        val params = listOfNotNull(
            limit?.let { "limit=$it" },
            before?.let { "before=" + it.encodeURLQueryComponent(encodeFull = true) },
            after?.let { "after=" + it.encodeURLQueryComponent(encodeFull = true) },
        )
        return authedGet("/chats/$chatId/messages" + if (params.isEmpty()) "" else "?" + params.joinToString("&"))
    }

    override suspend fun createChat(req: CreateChatRequest): Chat? =
        try {
            val resp = authedPost("/chats", proto.encodeToByteArray(req))
            if (resp.status != HttpStatusCode.OK) { noteCreateRejection(resp); null } else proto.decodeFromByteArray<Chat>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun setChatJoined(chatId: String, joined: Boolean): Boolean =
        try {
            authedPost("/chats/$chatId/" + if (joined) "join" else "leave", ByteArray(0)).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun setChatEvent(chatId: String, eventId: String?): Boolean =
        try {
            authedPost(
                "/chats/$chatId/event",
                proto.encodeToByteArray(com.alephri.elpuesto.model.SetChatEventRequest(eventId)),
            ).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun sendMessage(chatId: String, text: String): Message? =
        try {
            val resp = authedPost("/chats/$chatId/messages", proto.encodeToByteArray(SendMessageRequest(text)))
            if (resp.status != HttpStatusCode.OK) null else proto.decodeFromByteArray<Message>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun sendMediaMessage(chatId: String, caption: String, jpegBytes: ByteArray): Message? =
        try {
            var resp = client.post("$baseUrl/chats/$chatId/media") {
                parameter("text", caption)
                bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post("$baseUrl/chats/$chatId/media") {
                    parameter("text", caption)
                    bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
                }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "POST /chats/$chatId/media -> ${resp.status.value} (${jpegBytes.size} bytes)" }
            if (resp.status != HttpStatusCode.OK) { noteUploadRejection(resp); null } else proto.decodeFromByteArray<Message>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun markChatRead(chatId: String): Boolean =
        try {
            authedPost("/chats/$chatId/read", ByteArray(0)).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun chatMembers(chatId: String): List<ChatMember> = authedGet("/chats/$chatId/members")

    override suspend fun addChatMember(chatId: String, officerId: String): String? =
        try {
            val resp = authedPost(
                "/chats/$chatId/members",
                proto.encodeToByteArray(com.alephri.elpuesto.model.AddChatMemberRequest(officerId)),
            )
            val bytes = resp.readRawBytes()
            if (resp.status.value in 200..299) null else errorText(bytes) ?: "no se pudo invitar"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "sin conexión"
        }

    override suspend fun reportMessage(chatId: String, messageId: String, reason: String?): String? =
        postReport("/chats/$chatId/messages/$messageId/report", reason)

    override suspend fun blocks(): List<Officer> = authedGet("/me/blocks")

    override suspend fun setBlocked(officerId: String, blocked: Boolean): String? =
        try {
            val resp = if (blocked) authedPost("/me/blocks/$officerId", ByteArray(0)) else authedDelete("/me/blocks/$officerId")
            val bytes = resp.readRawBytes()
            if (resp.status.value !in 200..299) {
                errorText(bytes) ?: "no se pudo"
            } else {
                val ack = runCatching { proto.decodeFromByteArray<com.alephri.elpuesto.model.Ack>(bytes) }.getOrNull()
                if (ack == null || ack.ok) null else ack.message ?: "no se pudo"
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "sin conexión"
        }

    override suspend fun reportChat(chatId: String, reason: String?): String? =
        postReport("/chats/$chatId/report", reason)

    override suspend fun reportOfficer(officerId: String, reason: String?): String? =
        postReport("/officers/$officerId/report", reason)

    /**
     * Reporte (mensaje, chat o perfil; mismo cuerpo). null = reportado; texto = motivo del
     * rechazo: Ack(ok=false, message) — p. ej. duplicado o propio — o el ErrorBody de un 4xx.
     */
    private suspend fun postReport(path: String, reason: String?): String? =
        try {
            val resp = authedPost(path, proto.encodeToByteArray(com.alephri.elpuesto.model.ReportMessageRequest(reason)))
            val bytes = resp.readRawBytes()
            if (resp.status != HttpStatusCode.OK) {
                errorText(bytes) ?: "no se pudo reportar"
            } else {
                val ack = proto.decodeFromByteArray<com.alephri.elpuesto.model.Ack>(bytes)
                if (ack.ok) null else ack.message ?: "no se pudo reportar"
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "sin conexión"
        }

    override suspend fun archiveChat(chatId: String): Boolean =
        try {
            authedPost("/chats/$chatId/archive", ByteArray(0)).status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun uploadChatImage(chatId: String, jpegBytes: ByteArray): Boolean =
        try {
            var resp = client.post("$baseUrl/chats/$chatId/image") {
                bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post("$baseUrl/chats/$chatId/image") {
                    bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
                }
            }
            (resp.status.value in 200..299).also { if (!it) noteUploadRejection(resp) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun uploadAvatar(jpegBytes: ByteArray): Officer? =
        try {
            var resp = client.post("$baseUrl/me/avatar") {
                bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post("$baseUrl/me/avatar") {
                    bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
                }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "POST /me/avatar -> ${resp.status.value} (${jpegBytes.size} bytes)" }
            if (resp.status != HttpStatusCode.OK) { noteUploadRejection(resp); null } else proto.decodeFromByteArray<Officer>(resp.readRawBytes())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun uploadTripPhoto(tripItemId: String, jpegBytes: ByteArray): Boolean =
        try {
            var resp = client.post("$baseUrl/trip/$tripItemId/photo") {
                bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
            }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post("$baseUrl/trip/$tripItemId/photo") {
                    bearer(); contentType(ContentType.Image.JPEG); setBody(jpegBytes)
                }
            }
            com.alephri.elpuesto.logd("ElPuestoHttp") { "POST /trip/$tripItemId/photo -> ${resp.status.value} (${jpegBytes.size} bytes)" }
            (resp.status.value in 200..299).also { if (!it) noteUploadRejection(resp) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }

    override suspend fun image(path: String): ByteArray? =
        try {
            var resp = client.get("$baseUrl$path") { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.get("$baseUrl$path") { bearer() }
            }
            if (resp.status != HttpStatusCode.OK) null else resp.readRawBytes()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun fetchImage(path: String, etag: String?): RemoteImage =
        try {
            suspend fun get() = client.get("$baseUrl$path") {
                bearer()
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            var resp = get()
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) resp = get()
            when (resp.status) {
                HttpStatusCode.OK -> RemoteImage.Fetched(resp.readRawBytes(), resp.headers[HttpHeaders.ETag])
                HttpStatusCode.NotModified -> RemoteImage.NotModified
                HttpStatusCode.NotFound -> RemoteImage.NotFound
                else -> RemoteImage.Failed
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            RemoteImage.Failed
        }

    override suspend fun ackChecklist(itemId: String, done: Boolean): Boolean =
        try {
            val path = "$baseUrl/events/checklist/$itemId?done=$done"
            var resp = client.post(path) { bearer() }
            if (resp.status == HttpStatusCode.Unauthorized && tryRefresh()) {
                resp = client.post(path) { bearer() }
            }
            _online.value = true
            resp.status.value in 200..299
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // cancelación por navegación ≠ sin conexión
            _online.value = false
            false
        }

    // —— Pase de lista (asistencia por día) ——
    override suspend fun attendance(eventId: String): List<com.alephri.elpuesto.model.AttendanceEntry> =
        authedGet("/events/$eventId/attendance")

    override suspend fun ackAttendance(eventId: String, officerId: String, present: Boolean?): Boolean {
        // Lanza sin conexión (el outbox lo reintenta); Ack(ok=false) = rechazo real.
        val resp = authedPost(
            "/events/$eventId/attendance",
            proto.encodeToByteArray(com.alephri.elpuesto.model.SetAttendanceRequest(officerId, present)),
        )
        if (resp.status != HttpStatusCode.OK) return false
        return proto.decodeFromByteArray<com.alephri.elpuesto.model.Ack>(resp.readRawBytes()).ok
    }

    // —— Registro por honor ——
    override suspend fun registration(eventId: String): com.alephri.elpuesto.model.EventRegistration =
        authedGet("/events/$eventId/registration")

    override suspend fun setParticipation(
        eventId: String,
        req: com.alephri.elpuesto.model.SetParticipationRequest,
    ): ParticipationWrite {
        // Lanza sin conexión; 4xx = rechazo real con el motivo del servidor.
        val resp = authedPut("/events/$eventId/participation", proto.encodeToByteArray(req))
        val bytes = resp.readRawBytes()
        return if (resp.status == HttpStatusCode.OK) {
            ParticipationWrite.Ok(proto.decodeFromByteArray(bytes))
        } else {
            ParticipationWrite.Rejected(
                runCatching { proto.decodeFromByteArray<RemoteError>(bytes).error }.getOrNull()?.ifBlank { null }
                    ?: "No se pudo guardar tu registro",
            )
        }
    }

    override suspend fun deleteParticipation(eventId: String): Boolean =
        authedDelete("/events/$eventId/participation").status.value in 200..299

    // —— Compartir ubicación ——
    override suspend fun locationSharing(): com.alephri.elpuesto.model.LocationSharing = authedGet("/me/location-sharing")

    /** 200 → la allowlist actualizada; 4xx → rechazo real (null). Lanza sin conexión. */
    private fun sharingOrNull(resp: HttpResponse, bytes: ByteArray): com.alephri.elpuesto.model.LocationSharing? =
        if (resp.status == HttpStatusCode.OK) proto.decodeFromByteArray<com.alephri.elpuesto.model.LocationSharing>(bytes) else null

    override suspend fun setLocationEnabled(enabled: Boolean): com.alephri.elpuesto.model.LocationSharing? {
        val resp = authedPut(
            "/me/location-sharing",
            proto.encodeToByteArray(com.alephri.elpuesto.model.SetLocationEnabledRequest(enabled)),
        )
        return sharingOrNull(resp, resp.readRawBytes())
    }

    override suspend fun addLocationShare(officerId: String): com.alephri.elpuesto.model.LocationSharing? {
        val resp = authedPost("/me/location-shares/$officerId", ByteArray(0))
        return sharingOrNull(resp, resp.readRawBytes())
    }

    override suspend fun removeLocationShare(officerId: String): com.alephri.elpuesto.model.LocationSharing? {
        val resp = authedDelete("/me/location-shares/$officerId")
        return sharingOrNull(resp, resp.readRawBytes())
    }

    override suspend fun setLocationHidden(officerId: String, hidden: Boolean): com.alephri.elpuesto.model.LocationSharing? {
        val resp = if (hidden) authedPost("/me/location-hidden/$officerId", ByteArray(0))
        else authedDelete("/me/location-hidden/$officerId")
        return sharingOrNull(resp, resp.readRawBytes())
    }

    override suspend fun sendLocation(update: com.alephri.elpuesto.model.LocationUpdate): Boolean? =
        try {
            val resp = authedPost("/me/location", proto.encodeToByteArray(update))
            if (resp.status != HttpStatusCode.OK) false
            else proto.decodeFromByteArray<com.alephri.elpuesto.model.Ack>(resp.readRawBytes()).ok
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    override suspend fun clearLocation() {
        try {
            authedDelete("/me/location")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    override suspend fun eventLocations(eventId: String): List<com.alephri.elpuesto.model.LivePosition> =
        authedGet("/events/$eventId/locations")
}

/** Resultado de renovar: el servidor la negó (sesión muerta) ≠ no se pudo preguntar. */
private enum class Refresh { OK, REJECTED, UNAVAILABLE }

/** Un aviso del stream general (`/stream`), como lo manda el servidor (JSON). */
@kotlinx.serialization.Serializable
internal data class StreamChangeDto(
    val kind: String,
    val id: String? = null,
    val action: String? = null,
    val detail: String? = null,
    val label: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val at: String? = null,
)

/** Cabecera con la que el cliente web pide el modo cookie en las rutas de sesión. */
const val WEB_CLIENT_HEADER = "X-El-Puesto-Client"

/**
 * Cliente HTTP de la plataforma (OkHttp en Android, fetch en la web). [streaming] = para
 * streams largos (SSE/WebSocket/descargas): sin timeout de lectura corto.
 */
expect fun platformHttpClient(
    streaming: Boolean,
    config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
): HttpClient

/** Cuerpo de error del backend (`ErrorBody`: un solo campo de texto). */
@kotlinx.serialization.Serializable
internal data class RemoteError(@kotlinx.serialization.protobuf.ProtoNumber(1) val error: String = "")
