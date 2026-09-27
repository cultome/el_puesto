@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.AuthResult
import com.alephri.elpuesto.model.CallbackRequest
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.model.UpdateProfileRequest
import com.alephri.elpuesto.model.MagicLinkRequest
import com.alephri.elpuesto.model.MagicLinkResponse
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.OfficerStats
import com.alephri.elpuesto.model.RefreshRequest
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.queryString
import io.ktor.server.request.uri
import io.ktor.server.request.receive
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

@Serializable
data class Health(val status: String, val service: String)

@Serializable
data class Ack(val ok: Boolean = true, val message: String? = null)

@Serializable
data class ErrorBody(val error: String)

/** Mensaje del stream general de cambios (`WS /stream`): qué cambió y con qué contexto. */
@Serializable
data class StreamChange(
    val kind: String,
    val id: String? = null,
    val action: String? = null,
    val detail: String? = null,
    val label: String? = null,
    // kind "location" (action updated): la posición viaja en el frame (sin refetch).
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val at: String? = null,
)

// —— Auth (persistido en Postgres; envío de correo real pendiente) ——
private const val JWT_ISSUER = "el-puesto"

/** Sesión emitida: access token corto + refresh token largo. */
private data class Session(val access: String, val refresh: String, val accessExpiresInSec: Int)

private fun makeAccessToken(officerId: String, email: String, status: AccountStatus): String =
    JWT.create()
        .withIssuer(JWT_ISSUER)
        .withSubject(officerId)
        .withClaim("email", email)
        .withClaim("status", status.name)
        // iat: un corte de sesiones (AccountGate) invalida lo emitido antes de él.
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + Config.accessTtlMinutes * 60_000))
        .sign(Algorithm.HMAC256(Config.jwtSecret))

/** Emite una sesión: access token corto + refresh token largo (persistido, rotable). */
private fun issueSession(officerId: String, email: String, status: AccountStatus): Session {
    val refresh = UUID.randomUUID().toString()
    saveRefreshToken(refresh, email, officerId, Instant.now().plus(Config.refreshTtlDays, ChronoUnit.DAYS))
    return Session(makeAccessToken(officerId, email, status), refresh, (Config.accessTtlMinutes * 60).toInt())
}

fun main() {
    // Expuesto a internet no se arranca con secretos de desarrollo (Security.kt).
    SecretsCheck.enforce()
    initDatabase()
    DomainRepository.init()
    AdminAuth.init()
    DataExport.init()
    FeatureSwitches.init()
    embeddedServer(Netty, port = Config.port, host = Config.host, module = Application::module).start(wait = true)
}

/**
 * Backend Ktor — ESQUELETO. Reutiliza los contratos de `shared`, responde protobuf/JSON,
 * y ahora exige **JWT** en las rutas de datos, emitido por un flujo de **magic link**
 * (dev: el enlace se devuelve en la respuesta). Cuentas y tokens en memoria por ahora.
 */
fun Application.module() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true })
        protobuf(ProtoBuf { encodeDefaults = false })
    }
    install(StatusPages) {
        // Rechazos de los guardias (cuenta no activa, límite de uso, cuerpo o texto muy
        // grandes): se lanzan antes o en vez del handler para que no corra.
        exception<Rejected> { call, e ->
            e.retryAfterSec?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(e.status, ErrorBody(e.message ?: "rechazado"))
        }
        // Cuerpo/formato inválido (deserialización, tipos, enums, fechas): 400 con el
        // motivo real — un agente se autocorrige con esto.
        exception<BadRequestException> { call, cause ->
            val root = generateSequence<Throwable>(cause) { it.cause }.last()
            call.respond(
                HttpStatusCode.BadRequest,
                AdminError(
                    "cuerpo inválido: ${root.message ?: "no se pudo leer"}",
                    hint = "revisa tipos, valores de enums y fechas/horas en ISO-8601",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("error no manejado en ${call.request.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("error interno"))
        }
    }
    // CORS: solo para orígenes declarados (CORS_ORIGINS). El admin web se sirve del mismo
    // origen y la app Android no lo usa, así que por default no se instala: ningún sitio
    // ajeno puede llamar a la API desde el navegador de alguien.
    if (Config.corsOrigins.isNotEmpty()) {
        install(CORS) {
            // Más nuestros propios orígenes: detrás de Caddy el backend ve http y el plugin
            // tomaría por ajeno (403) al admin web y a la app web de este mismo host.
            (Config.corsOrigins + WebClient.ownOrigins()).distinct().forEach { o ->
                val (scheme, host) = o.split("://", limit = 2).let { if (it.size == 2) it[0] to it[1] else "https" to it[0] }
                allowHost(host, schemes = listOf(scheme))
            }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("X-Admin-Key")
            // App web servida desde un origen declarado: su cabecera y la cookie de sesión
            // (`ep_rt`, solo en /auth/*; ver WebClient).
            allowHeader(WebClient.HEADER)
            allowCredentials = true
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
        }
    }
    // Cabeceras defensivas (CSP por ruta, sin iframes, sin Referer) y el admin en su
    // propio host (WebSecurity.kt).
    WebSecurity.install(this)
    // Una línea por petición + detección de picos (Abuse.kt) y tope del cuerpo por ruta.
    install(RegistroDePeticiones)
    BodyLimits.install(this)
    // WebSockets: canal de tiempo real del chat (notificar-y-refetch, mismo ChangeBus).
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(25)
        timeout = java.time.Duration.ofSeconds(60)
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "el-puesto"
            verifier(JWT.require(Algorithm.HMAC256(Config.jwtSecret)).withIssuer(JWT_ISSUER).build())
            // Firma y vencimiento no bastan: la cuenta debe existir, seguir ligada al mismo
            // oficial y no haber cerrado sesiones después de emitir el token (AccountGate).
            validate { cred ->
                val sub = cred.payload.subject ?: return@validate null
                val email = cred.payload.getClaim("email").asString() ?: return@validate null
                if (AccountGate.tokenValid(email, sub, cred.payload.issuedAt?.toInstant())) JWTPrincipal(cred.payload) else null
            }
        }
    }

    // Compartir ubicación: purga de posiciones vencidas / de eventos inactivos.
    LocationHub.start(this)
    // Posiciones de campeonatos: al terminar cada fecha, pide la tabla a su fuente.
    StandingsIngest.start(this)

    routing {
        get("/health") { call.respond(Health("ok", "el-puesto-backend")) }

        // —— Auth (público) ——
        post("/auth/magic-link") {
            // Freno por IP ANTES de leer el cuerpo (nadie sin sesión nos hace leer de más).
            RateLimits.check("ip:${call.clientIp()}", RateLimits.Clase.AUTH)
            WebClient.checkOrigin(call)
            FeatureSwitches.check(Pausa.ENLACES)
            val req = call.receive<MagicLinkRequest>()
            limite("correo", req.email, TextLimits.CORREO)
            // Quién lo pide: la app web (el enlace abre el navegador) o la app Android (null).
            val web = when (req.client?.takeIf { it.isNotEmpty() }) {
                null -> false
                WebClient.WEB -> true
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("cliente inválido"))
                    return@post
                }
            }
            // Con correo pero sin dirección pública de la app web no hay enlace que mandar
            // (sin SMTP, en desarrollo, el devLink sale relativo).
            if (web && Config.webAppUrl == null && Config.emailEnabled) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("la app web no está disponible en este servidor"))
                return@post
            }
            val email = EmailRules.normalize(req.email)
            if (!EmailRules.isValid(email)) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("correo inválido"))
                return@post
            }
            // Reto PKCE (apps nuevas): si viene, con la forma exacta.
            val challenge = req.challenge?.takeIf { it.isNotEmpty() }
            if (challenge != null && !Pkce.validFormat(challenge)) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("reto inválido"))
                return@post
            }
            if (call.magicLinkThrottled(email)) return@post
            val acc = findAccount(email)
            fun newLink(): Pair<String, String> {
                val token = UUID.randomUUID().toString()
                saveMagicToken(token, email, Instant.now().plus(15, ChronoUnit.MINUTES), challenge)
                // Web: la app web con el token en el FRAGMENTO (el navegador no lo manda a
                // ningún servidor: ni a nosotros, ni a los logs, ni en el Referer). En
                // desarrollo, relativo (`/app/#auth=…`).
                if (web) {
                    val webLink = "${Config.webAppUrl ?: "/app/"}#auth=$token"
                    return webLink to webLink
                }
                // El botón del correo apunta a la página puente https cuando hay
                // PUBLIC_BASE_URL (Gmail y similares quitan los links de esquema propio).
                val link = "elpuesto://auth?token=$token"
                return link to (Config.publicBaseUrl?.let { "$it/auth/open?token=$token" } ?: link)
            }
            // Desarrollo local (sin SMTP ni URL pública): el enlace va en la respuesta
            // (devLink, para curl) y se dice si el correo no está invitado.
            if (!Config.emailEnabled && Config.publicBaseUrl == null) {
                if (acc == null) return@post call.respond(MagicLinkResponse(sent = false, reason = "not_invited"))
                return@post call.respond(MagicLinkResponse(sent = true, devLink = newLink().first))
            }
            // Expuesto: la respuesta es IDÉNTICA exista o no la cuenta, y el correo sale en
            // segundo plano (mismo tiempo de respuesta): nadie puede averiguar por aquí si
            // un correo es de un oficial. El enlace SOLO viaja por correo.
            if (acc != null) {
                call.application.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val (link, buttonLink) = newLink()
                    if (!EmailSender.sendMagicLink(email, link, buttonLink, web = web)) {
                        call.application.log.error("no se pudo enviar el enlace mágico (SMTP configurado: ${Config.emailEnabled})")
                    }
                }
            }
            call.respond(MagicLinkResponse(sent = true))
        }

        // Página puente del correo: un link http(s) SIEMPRE clickeable que abre la app
        // (el esquema elpuesto:// sí funciona desde un navegador con tap del usuario).
        get("/auth/open") {
            RateLimits.check("ip:${call.clientIp()}", RateLimits.Clase.AUTH)
            val token = call.request.queryParameters["token"]
            // Solo la forma exacta de un token (UUID): nada más del URL llega a la página.
            if (!WebSecurity.isMagicToken(token)) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("enlace inválido"))
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(WebSecurity.authOpenPage(token!!), ContentType.parse("text/html; charset=utf-8"))
        }

        post("/auth/callback") {
            RateLimits.check("ip:${call.clientIp()}", RateLimits.Clase.AUTH)
            WebClient.checkOrigin(call)
            val web = WebClient.isWeb(call)
            val req = call.receive<CallbackRequest>()
            if (!WebSecurity.isMagicToken(req.token)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("token inválido o expirado"))
                return@post
            }
            val verifier = req.verifier?.takeIf { Pkce.validFormat(it) }
            val acc = when (val r = consumeMagicToken(req.token, verifier, Config.authRequirePkce)) {
                is MagicRedeem.Ok -> findAccount(r.email)
                MagicRedeem.WrongDevice -> {
                    val donde = if (web) "el navegador" else "el teléfono"
                    call.respond(HttpStatusCode.Unauthorized, ErrorBody("Abre el enlace en $donde donde lo pediste o pide uno nuevo."))
                    return@post
                }
                MagicRedeem.Invalid -> null
            }
            if (acc == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("token inválido o expirado"))
                return@post
            }
            // Aceptar la invitación (primer login) = pasa a esperar aprobación del admin.
            val status = if (acc.status == AccountStatus.INVITED) {
                setAccountStatus(acc.email, AccountStatus.PENDING_APPROVAL)
                AccountStatus.PENDING_APPROVAL
            } else acc.status
            val s = issueSession(AccountGate.subjectOf(acc.email, acc.officerId), acc.email, status)
            if (web) {
                // Web: el refresh va en la cookie HttpOnly, nunca en el cuerpo. Si el
                // navegador ya traía una sesión (otra cuenta o un login repetido), se revoca:
                // su cookie se reemplaza y el token quedaría vivo sin dueño.
                WebClient.refreshFromCookie(call)?.let { revokeRefreshToken(it) }
                WebClient.setRefreshCookie(call, s.refresh)
                call.respond(AuthResult(s.access, status, null, s.accessExpiresInSec))
            } else {
                call.respond(AuthResult(s.access, status, s.refresh, s.accessExpiresInSec))
            }
        }

        // Renueva la sesión con un refresh token (rotación de un solo uso). La cuenta se
        // re-lee de la DB: un cambio de estado o de oficial ligado se refleja al renovar.
        // Web (`X-El-Puesto-Client: web`): el token viene de la cookie `ep_rt` (el del cuerpo
        // se ignora), el rotado vuelve en la cookie y un rechazo la borra.
        post("/auth/refresh") {
            RateLimits.check("ip:${call.clientIp()}", RateLimits.Clase.AUTH)
            WebClient.checkOrigin(call)
            val web = WebClient.isWeb(call)
            val token = if (web) {
                WebClient.refreshFromCookie(call) ?: run {
                    WebClient.clearRefreshCookie(call)
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("refresh token inválido o expirado"))
                }
            } else {
                call.receive<RefreshRequest>().refreshToken
            }
            val r = rotateRefreshToken(token)
            if (web && r !is RefreshResult.Ok) WebClient.clearRefreshCookie(call)
            when (r) {
                is RefreshResult.Ok -> {
                    val acc = findAccount(r.owner.email) ?: run {
                        if (web) WebClient.clearRefreshCookie(call)
                        return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("refresh token inválido o expirado"))
                    }
                    val s = issueSession(AccountGate.subjectOf(acc.email, acc.officerId), acc.email, acc.status)
                    if (web) {
                        WebClient.setRefreshCookie(call, s.refresh)
                        call.respond(AuthResult(s.access, acc.status, null, s.accessExpiresInSec))
                    } else {
                        call.respond(AuthResult(s.access, acc.status, s.refresh, s.accessExpiresInSec))
                    }
                }
                // Un token ya canjeado volvió a llegar: alguien más lo tiene. Ya se cortaron
                // TODAS las sesiones de la cuenta; se avisa al titular y al administrador.
                is RefreshResult.Reused -> {
                    AccountGate.invalidate(r.email)
                    SecurityMonitor.alert(
                        "reuso-${r.email}", "Reuso de una sesión",
                        listOf(
                            "Un refresh token ya canjeado volvió a llegar para ${r.email} (IP ${call.clientIp()}).",
                            "Se cerraron todas sus sesiones y se le avisó por correo.",
                        ),
                    )
                    // Un aviso por hora como mucho: repetir el token no bombardea al titular.
                    if (!Throttle.hit("aviso-sesiones:${r.email}", 1, 60 * 60_000L, 60 * 60_000L)) {
                        call.application.launch(kotlinx.coroutines.Dispatchers.IO) { EmailSender.sendSessionsClosedNotice(r.email) }
                    }
                    call.respond(HttpStatusCode.Unauthorized, ErrorBody("sesión cerrada por seguridad; vuelve a entrar"))
                }
                RefreshResult.Invalid -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("refresh token inválido o expirado"))
            }
        }

        // Cerrar sesión en este teléfono: revoca su refresh token (idempotente). Web: el de
        // la cookie, y la cookie se borra.
        post("/auth/logout") {
            RateLimits.check("ip:${call.clientIp()}", RateLimits.Clase.AUTH)
            WebClient.checkOrigin(call)
            if (WebClient.isWeb(call)) {
                WebClient.refreshFromCookie(call)?.let { revokeRefreshToken(it) }
                WebClient.clearRefreshCookie(call)
            } else {
                revokeRefreshToken(call.receive<RefreshRequest>().refreshToken.take(200))
            }
            call.respond(Ack(message = "sesión cerrada"))
        }

        // —— Admin (X-Admin-Key: master key de env o key nombrada con scopes) ——
        // La consumen agentes de AI (vía MCP), el admin web y curl. Ver AdminRoutes.kt.
        adminRoutes()
        // Descarga pública del APK más reciente (pruebas en teléfonos sin adb).
        downloadRoutes()
        // Admin web (humanos): SPA estática servida por el mismo backend (sin CORS);
        // los assets son públicos, los datos siguen detrás de X-Admin-Key.
        staticResources("/admin/ui", "adminweb")
        // App web de los oficiales (Compose/Wasm) en /app/, si hay WEB_APP_DIR (WebApp.kt).
        webAppRoutes()

        // Tiempo real GENERAL para la app web: el WebSocket del navegador no puede mandar
        // Authorization, así que entra con un boleto de un solo uso (POST /stream/ticket) y
        // corre EXACTAMENTE la misma sesión que `/stream` (generalStream).
        route("/stream/web") {
            // Antes del upgrade: solo navegadores de nuestro origen (sin Origin no es un
            // navegador) y una IP que prueba boletos falsos queda frenada.
            intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                Throttle.blockedFor("boleto-ip:${call.clientIp()}")?.let {
                    throw Rejected(HttpStatusCode.TooManyRequests, "demasiados boletos inválidos; intenta en unos minutos", it)
                }
                if (!WebClient.originAllowed(call, requireOrigin = true)) {
                    throw Rejected(HttpStatusCode.Forbidden, "origen no permitido")
                }
            }
            webSocket {
                // Boleto inválido, vencido o ya usado (o la sesión que lo pidió se cerró) →
                // VIOLATED_POLICY: la web pide otro boleto (y si falla, renueva la sesión).
                val principal = StreamTickets.consume(call.request.queryParameters["ticket"])?.takeIf { AccountGate.usable(it) }
                if (principal == null) {
                    Throttle.hit("boleto-ip:${call.clientIp()}", 30, 15 * 60_000L, 15 * 60_000L)
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "boleto inválido o vencido"))
                    return@webSocket
                }
                // El registro de peticiones atribuye la conexión al oficial (no a la IP).
                call.authentication.principal(principal)
                generalStream(principal)
            }
        }

        // —— Rutas de datos (requieren JWT; servidas desde Postgres/DomainRepository) ——
        authenticate("auth-jwt") {
            // Cuenta ACTIVE para todo salvo GET /me + límite de uso por oficial (Sessions.kt).
            install(CuentaActiva)
            get("/me") {
                val p = call.principal<JWTPrincipal>()!!
                // El estado vivo de la cuenta (no el del token): una aprobación o suspensión
                // se ve al momento, sin esperar a que se renueve la sesión.
                val status = p.email()?.let { AccountGate.info(it)?.status }
                    ?: AccountStatus.valueOf(p.payload.getClaim("status").asString())
                val officer = p.subject?.let { DomainRepository.officer(it) }
                call.respond(
                    officer?.copy(status = status)
                        ?: Officer(id = p.subject ?: "pending", omdaiId = 0, displayName = "Pendiente", status = status, stats = OfficerStats(0, 0)),
                )
            }

            // Perfil propio: edición (sin aprobación) + emergencia + registro de accesos.
            put("/me") {
                val officerId = call.principal<JWTPrincipal>()!!.subject ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorBody("sin sujeto"))
                val req = call.receive<UpdateProfileRequest>()
                limite("nombre", req.displayName, TextLimits.NOMBRE)
                // Mismas reglas que la app (NameRules): ni "Tú" ni "Control" ni invisibles.
                com.alephri.elpuesto.model.NameRules.displayNameProblem(req.displayName)?.let {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody(it))
                    return@put
                }
                val updated = DomainRepository.updateOfficer(officerId, com.alephri.elpuesto.model.NameRules.normalize(req.displayName), req.area)
                if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorBody("oficial no encontrado")) else call.respond(updated)
            }
            get("/me/emergency") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                call.respond(DomainRepository.emergencyInfo(officerId))
            }
            put("/me/emergency") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val info = call.receive<EmergencyInfo>()
                limite("contacto", info.contactName, TextLimits.NOMBRE)
                limite("teléfono", info.contactPhone, TextLimits.TELEFONO)
                limite("tipo de sangre", info.bloodType, TextLimits.ETIQUETA)
                limite("alergias", info.allergies, TextLimits.EMERGENCIA)
                DomainRepository.upsertEmergency(officerId, info)
                call.respond(Ack(message = "emergencia actualizada"))
            }
            // Bienvenida (Completar perfil): UNA vez por cuenta, no por dispositivo. La cuenta
            // sale del JWT (solo la propia).
            get("/me/onboarding") {
                val email = call.principal<JWTPrincipal>()!!.payload.getClaim("email").asString()
                call.respond(com.alephri.elpuesto.model.OnboardingState(done = accountOnboarded(email)))
            }
            post("/me/onboarding") {
                val email = call.principal<JWTPrincipal>()!!.payload.getClaim("email").asString()
                markAccountOnboarded(email)
                call.respond(com.alephri.elpuesto.model.OnboardingState(done = true))
            }
            // "Descargar mis datos": ZIP con todo lo propio (DataExport). Una vez al día;
            // se registra solo si la descarga se completó y avisa por correo al titular.
            get("/me/export/status") {
                val next = DataExport.nextAllowedAt(call.principal<JWTPrincipal>()!!.subject!!)
                call.respond(
                    com.alephri.elpuesto.model.ExportStatus(
                        available = next == null,
                        nextAt = next?.let { kotlinx.datetime.Instant.parse(it.toString()) },
                    ),
                )
            }
            get("/me/export") {
                val principal = call.principal<JWTPrincipal>()!!
                val officerId = principal.subject!!
                DataExport.nextAllowedAt(officerId)?.let { next ->
                    val secs = java.time.Duration.between(Instant.now(), next).seconds.coerceAtLeast(1)
                    call.response.header(HttpHeaders.RetryAfter, secs.toString())
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorBody("Ya descargaste tus datos hoy. Podrás volver a hacerlo después del ${DataExport.humanDate(next)}."),
                    )
                    return@get
                }
                val now = Instant.now()
                // Una a la vez y apartada desde que empieza (ver DataExport.begin).
                val recordId = DataExport.begin(officerId, now) ?: run {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorBody("Ya hay una descarga de tus datos en curso."))
                    return@get
                }
                var completed = false
                try {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, DataExport.fileName(now)).toString(),
                    )
                    call.respondOutputStream(ContentType.Application.Zip) { DataExport.write(officerId, this, now) }
                    completed = true
                } finally {
                    DataExport.finish(officerId, recordId, completed)
                }
                principal.payload.getClaim("email").asString()?.let { email ->
                    call.application.launch(kotlinx.coroutines.Dispatchers.IO) {
                        EmailSender.sendExportNotice(email, DataExport.humanDate(now))
                    }
                }
            }
            // —— Bloqueos: quién ya no puede invitarte ni compartirte su ubicación ——
            get("/me/blocks") {
                call.respond(DomainRepository.blockedOfficers(call.principal<JWTPrincipal>()!!.subject!!))
            }
            post("/me/blocks/{officerId}") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val problem = DomainRepository.block(officerId, call.parameters["officerId"]!!)
                call.respond(Ack(ok = problem == null, message = problem ?: "bloqueado"))
            }
            delete("/me/blocks/{officerId}") {
                DomainRepository.unblock(call.principal<JWTPrincipal>()!!.subject!!, call.parameters["officerId"]!!)
                call.respond(Ack(message = "desbloqueado"))
            }
            get("/me/emergency/accesses") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                call.respond(DomainRepository.emergencyAccesses(officerId))
            }

            // Avatar: recibe la imagen recortada, genera variantes (thumb/full) y guarda.
            post("/me/avatar") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val bytes = call.receive<ByteArray>()
                if (!ImageService.processAndStore("avatar", officerId, bytes)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("imagen inválida"))
                    return@post
                }
                val updated = DomainRepository.setOfficerAvatar(officerId, "/images/avatar/$officerId/full")
                if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorBody("oficial no encontrado"))
                else {
                    // Las apps conectadas revalidan su copia guardada (la URL no cambia).
                    ChangeBus.emit(null, "image", id = "/images/avatar/$officerId/full")
                    call.respond(updated)
                }
            }

            // Sirve variantes de imagen con ETag (la app las guarda y revalida: 304 si no
            // cambiaron). Las privadas (bitácora, fotos e imagen de chat) solo a quien puede
            // verlas; si no, 404 — igual que si no existiera (no se confirma su existencia).
            get("/images/{kind}/{ownerId}/{variant}") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val kind = call.parameters["kind"]!!
                val ownerId = call.parameters["ownerId"]!!
                val bytes = if (!DomainRepository.canViewImage(viewerId, kind, ownerId)) null
                    else ImageService.get(kind, ownerId, call.parameters["variant"]!!)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorBody("imagen no encontrada"))
                    return@get
                }
                val etag = ImageService.etag(bytes)
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, "private, no-cache")
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim().removePrefix("W/") == etag }) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(bytes, ContentType.parse(ImageService.CONTENT_TYPE))
                }
            }

            // —— Compartir ubicación (opt-in; allowlist que controla el oficial) ——
            // Éxito = LocationSharing actualizado; rechazo = 4xx (el outbox lo descarta).
            get("/me/location-sharing") {
                call.respond(LocationRepository.sharing(call.principal<JWTPrincipal>()!!.subject!!))
            }
            put("/me/location-sharing") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<com.alephri.elpuesto.model.SetLocationEnabledRequest>()
                call.respond(LocationRepository.setEnabled(officerId, req.enabled))
            }
            post("/me/location-shares/{officerId}") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val problem = LocationRepository.addShare(officerId, call.parameters["officerId"]!!)
                if (problem != null) call.respond(HttpStatusCode.BadRequest, ErrorBody(problem))
                else call.respond(LocationRepository.sharing(officerId))
            }
            delete("/me/location-shares/{officerId}") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                LocationRepository.removeShare(officerId, call.parameters["officerId"]!!)
                call.respond(LocationRepository.sharing(officerId))
            }
            post("/me/location-hidden/{officerId}") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                LocationRepository.setHidden(officerId, call.parameters["officerId"]!!, hidden = true)
                call.respond(LocationRepository.sharing(officerId))
            }
            delete("/me/location-hidden/{officerId}") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                LocationRepository.setHidden(officerId, call.parameters["officerId"]!!, hidden = false)
                call.respond(LocationRepository.sharing(officerId))
            }
            // Posición viva (la sube el servicio de la app ~cada 15 s). Solo con el
            // interruptor encendido y asignado al evento activo; si no, Ack(ok=false) y la
            // app detiene el servicio. Solo se guarda la ÚLTIMA, en memoria. Fuera de la
            // zona del circuito ([LocationRepository.fenceFor]) se descarta y se retira la
            // anterior: nadie te ve lejos del circuito (el teléfono ya no debería mandarla;
            // esto es el segundo candado). Ack ok=true: el servicio sigue para cuando vuelvas.
            post("/me/location") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val u = call.receive<com.alephri.elpuesto.model.LocationUpdate>()
                if (u.lat !in -90.0..90.0 || u.lon !in -180.0..180.0) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("coordenadas inválidas"))
                    return@post
                }
                val eventId = LocationRepository.sharingEvent(officerId)
                if (eventId == null) {
                    LocationHub.remove(officerId)
                    call.respond(Ack(ok = false, message = "sin evento activo o compartir apagado"))
                } else if (LocationRepository.fenceFor(eventId)?.contains(u.lat, u.lon) != true) {
                    LocationHub.remove(officerId)
                    call.respond(Ack(message = "fuera del circuito: no se comparte"))
                } else {
                    LocationHub.update(officerId, eventId, u)
                    call.respond(Ack(message = "ok"))
                }
            }
            // Pausar / dejar de compartir: el pin desaparece para quien lo veía.
            delete("/me/location") {
                LocationHub.remove(call.principal<JWTPrincipal>()!!.subject!!)
                call.respond(Ack(message = "ubicación retirada"))
            }

            // Búsqueda de oficiales — SOLO para elegir a quién compartir ubicación /
            // invitar (privacy-first: no es red social): nombre (3+ letras) u OMDAI ID
            // (3+ dígitos), nunca correo, máx. 10 resultados; el solicitante queda excluido.
            get("/officers/search") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                limite("búsqueda", q, TextLimits.BUSQUEDA)
                call.respond(DomainRepository.searchOfficers(q, viewerId))
            }
            // Perfil de otros oficiales (contexto de trabajo; sin funciones sociales).
            get("/officers/{id}") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val o = call.parameters["id"]?.let { DomainRepository.officer(it, viewerId) }
                if (o == null) call.respond(HttpStatusCode.NotFound, ErrorBody("oficial no encontrado")) else call.respond(o)
            }
            // Historial completo: SOLO el propio. De otro oficial solo se ven los eventos en
            // los que ambos participaron (privacy-first: nunca su historial completo).
            get("/officers/{id}/history") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val id = call.parameters["id"]!!
                if (id != viewerId) {
                    call.respond(HttpStatusCode.Forbidden, ErrorBody("solo tu propio historial; de otro oficial usa /officers/{id}/common-events"))
                } else {
                    call.respond(DomainRepository.officerHistory(id))
                }
            }
            // Reportar el PERFIL de un oficial (nombre o foto): lo revisa el admin.
            post("/officers/{id}/report") {
                val officerId = call.principal<JWTPrincipal>()!!.subject!!
                val req = call.receive<com.alephri.elpuesto.model.ReportMessageRequest>()
                limite("motivo", req.reason, TextLimits.MOTIVO)
                val problem = DomainRepository.reportContent(officerId, "officer", call.parameters["id"]!!, req.reason?.trim()?.ifBlank { null })
                call.respond(Ack(ok = problem == null, message = problem ?: "reportado"))
            }
            get("/officers/{id}/common-events") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                call.respond(DomainRepository.commonEvents(viewerId, call.parameters["id"]!!))
            }
            // Logros (derivados al leer). De otro oficial el servidor recorta a los públicos
            // (pasaporte, campeonatos, carrera en pista): la app no decide la privacidad.
            get("/officers/{id}/achievements") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val a = AchievementsRepository.forOfficer(call.parameters["id"]!!, viewerId)
                if (a == null) call.respond(HttpStatusCode.NotFound, ErrorBody("oficial no encontrado")) else call.respond(a)
            }
            // Emergencia de OTRO oficial: SOLO el jefe de su puesto durante un evento activo,
            // y cada acceso queda registrado (auditable, visible para el titular).
            get("/officers/{id}/emergency") {
                val viewerId = call.principal<JWTPrincipal>()!!.subject!!
                val targetId = call.parameters["id"]!!
                val eventId = DomainRepository.chiefViewContext(viewerId, targetId)
                if (eventId == null) {
                    call.respond(HttpStatusCode.Forbidden, ErrorBody("solo el jefe de puesto durante un evento activo"))
                } else {
                    DomainRepository.recordEmergencyAccess(targetId, viewerId, eventId)
                    call.respond(DomainRepository.emergencyInfo(targetId))
                }
            }

            route("/events") {
                get { call.respond(DomainRepository.activeEvents()) }
                route("/{id}") {
                    get {
                        val ev = call.parameters["id"]?.let { DomainRepository.event(it) }
                        if (ev == null) call.respond(HttpStatusCode.NotFound, ErrorBody("evento no encontrado")) else call.respond(ev)
                    }
                    get("/assignment/me") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject ?: ""
                        val a = call.parameters["id"]?.let { DomainRepository.assignmentFor(it, officerId) }
                        if (a == null) call.respond(HttpStatusCode.NotFound, ErrorBody("sin asignación")) else call.respond(a)
                    }
                    get("/mates") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        call.respond(DomainRepository.mates(call.parameters["id"]!!, officerId))
                    }
                    // Tiempo real (SSE, mismo bus que el admin): la app se suscribe con su
                    // Bearer normal y recibe QUÉ cambió para resincronizar esa parte.
                    get("/stream") {
                        val eventId = call.parameters["id"]!!
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        // Solo quien trabaja el evento (roster): nadie más necesita saber
                        // cuándo cambia su MbM, su checklist o su pase de lista.
                        if (DomainRepository.assignmentFor(eventId, officerId) == null) {
                            call.respond(HttpStatusCode.Forbidden, ErrorBody("no estás asignado a este evento"))
                            return@get
                        }
                        if (!WsLimits.open(officerId)) {
                            call.respond(HttpStatusCode.TooManyRequests, ErrorBody("demasiadas conexiones abiertas"))
                            return@get
                        }
                        call.response.headers.append("Cache-Control", "no-cache")
                        call.respondTextWriter(io.ktor.http.ContentType.parse("text/event-stream")) {
                            try {
                                write(": conectado\n\n"); flush()
                                val changes = ChangeBus.flow
                                    .filter { StreamPolicy.eventStreamVisible(it, eventId) }
                                    .map { "event: change\ndata: {\"kind\":\"${it.kind}\"}\n\n" }
                                // El ping además revisa la sesión: suspender o cerrar sesiones corta el stream.
                                val principal = call.principal<JWTPrincipal>()!!
                                val pings = kotlinx.coroutines.flow.flow {
                                    while (true) {
                                        kotlinx.coroutines.delay(25_000)
                                        if (!AccountGate.usable(principal)) throw java.util.concurrent.CancellationException("sesión cerrada")
                                        emit(": ping\n\n")
                                    }
                                }
                                kotlinx.coroutines.flow.merge(changes, pings).collect { write(it); flush() }
                            } catch (_: Exception) {
                                // Cliente desconectado: fin del stream.
                            } finally {
                                WsLimits.close(officerId)
                            }
                        }
                    }
                    // Posiciones vivas que el viewer puede ver en este evento (foto inicial;
                    // luego llegan por el WS /stream como kind "location").
                    get("/locations") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        call.respond(LocationRepository.livePositions(officerId, call.parameters["id"]!!))
                    }
                    get("/schedule") { call.respond(DomainRepository.schedule(call.parameters["id"]!!)) }
                    get("/checklist") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        call.respond(DomainRepository.checklist(call.parameters["id"]!!, officerId))
                    }
                    get("/trip") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        call.respond(DomainRepository.tripItems(call.parameters["id"]!!, officerId))
                    }
                    // Pase de lista: GET personalizado (jefe ve su posición, el resto solo
                    // su propia marca); POST solo del jefe y siempre sobre HOY (CDMX).
                    get("/attendance") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        val day = call.request.queryParameters["day"]
                        if (day != null && runCatching { java.time.LocalDate.parse(day) }.isFailure) {
                            call.respond(HttpStatusCode.BadRequest, ErrorBody("day inválido (ISO yyyy-mm-dd)"))
                            return@get
                        }
                        call.respond(DomainRepository.attendance(call.parameters["id"]!!, officerId, day))
                    }
                    post("/attendance") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        val req = call.receive<com.alephri.elpuesto.model.SetAttendanceRequest>()
                        val reason = DomainRepository.setAttendance(call.parameters["id"]!!, req.officerId, req.present, officerId)
                        call.respond(Ack(ok = reason == null, message = reason ?: "asistencia actualizada"))
                    }
                    // Registro por HONOR ("yo trabajé este evento"): estado + la propia.
                    // Éxito = Participation; rechazo = 4xx con el motivo (el outbox lo descarta).
                    // Nunca da permisos: vive aparte de las asignaciones del roster.
                    get("/registration") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        val r = ParticipationRepository.registration(call.parameters["id"]!!, officerId)
                        if (r == null) call.respond(HttpStatusCode.NotFound, ErrorBody("evento no encontrado")) else call.respond(r)
                    }
                    put("/participation") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        val req = call.receive<com.alephri.elpuesto.model.SetParticipationRequest>()
                        limite("rol", req.role, TextLimits.NOMBRE)
                        limite("puesto propuesto", req.proposal?.label, TextLimits.ETIQUETA)
                        // La etiqueta propuesta termina en el mapa de todos y en el CSV
                        // versionado: solo letras, números y . - / # (nada de "=FORMULA(…)").
                        req.proposal?.label?.trim()?.let { l ->
                            if (!PUESTO_PROPUESTO.matches(l)) {
                                throw Rejected(HttpStatusCode.BadRequest, "puesto propuesto: usa letras, números, espacios y . - / #")
                            }
                        }
                        if (req.days.size > TextLimits.MAX_DIAS) throw Rejected(HttpStatusCode.BadRequest, "días: máximo ${TextLimits.MAX_DIAS}")
                        when (val r = ParticipationRepository.set(call.parameters["id"]!!, officerId, req)) {
                            is ParticipationRepository.SetResult.Ok -> call.respond(r.participation)
                            is ParticipationRepository.SetResult.Rejected -> call.respond(
                                if (r.notFound) HttpStatusCode.NotFound else HttpStatusCode.Conflict, ErrorBody(r.reason),
                            )
                        }
                    }
                    delete("/participation") {
                        val officerId = call.principal<JWTPrincipal>()!!.subject!!
                        ParticipationRepository.delete(call.parameters["id"]!!, officerId)
                        // Idempotente: borrar lo que ya no existe también es éxito (outbox).
                        call.respond(Ack(message = "registro eliminado"))
                    }
                }
                post("/checklist/{id}") {
                    val done = call.request.queryString().contains("done=true")
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val ok = DomainRepository.setChecklistDone(call.parameters["id"]!!, done, officerId)
                    call.respond(Ack(ok = ok, message = if (ok) "checklist actualizado" else "ítem inexistente o sin asignación a puesto"))
                }
            }

            // —— Planeación personal (agenda editable): CRUD de trip items propios ——
            route("/trip") {
                get {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    call.respond(DomainRepository.myTripItems(officerId))
                }
                post {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val item = call.receive<com.alephri.elpuesto.model.TripItem>()
                    if (item.title.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("título requerido"))
                        return@post
                    }
                    tripItemProblem(item)?.let {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody(it))
                        return@post
                    }
                    Quotas.checkTripItems(officerId)
                    call.respond(DomainRepository.createTripItem(officerId, item))
                }
                put("/{id}") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val item = call.receive<com.alephri.elpuesto.model.TripItem>()
                    tripItemProblem(item)?.let {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody(it))
                        return@put
                    }
                    val updated = DomainRepository.updateTripItem(officerId, call.parameters["id"]!!, item)
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorBody("ítem no encontrado o no es tuyo"))
                    } else {
                        call.respond(updated)
                    }
                }
                delete("/{id}") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val ok = DomainRepository.deleteTripItem(officerId, call.parameters["id"]!!)
                    if (ok) call.respond(Ack(message = "eliminado")) else call.respond(HttpStatusCode.NotFound, ErrorBody("ítem no encontrado o no es tuyo"))
                }
                // Foto de bitácora: la imagen del ítem PHOTO, con la proporción original.
                post("/{id}/photo") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val id = call.parameters["id"]!!
                    if (!DomainRepository.ownsTripItem(officerId, id)) {
                        call.respond(HttpStatusCode.NotFound, ErrorBody("ítem no encontrado o no es tuyo"))
                        return@post
                    }
                    Quotas.checkPhotos(officerId)
                    val bytes = call.receive<ByteArray>()
                    if (!ImageService.processAndStorePhoto("trip", id, bytes)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("imagen inválida"))
                        return@post
                    }
                    call.respond(Ack(message = "foto guardada"))
                }
            }

            get("/circuits") { call.respond(DomainRepository.circuits(withMain = true)) }
            get("/circuits/{id}/trazados") { call.respond(DomainRepository.trazados(call.parameters["id"]!!)) }
            // "Asignado antes" es de QUIEN consulta (su historial): se deriva con el JWT.
            get("/trazados/{id}/puestos") {
                call.respond(DomainRepository.puestos(call.parameters["id"]!!, call.principal<JWTPrincipal>()?.subject))
            }
            get("/trazados/{id}/assets") {
                call.respond(DomainRepository.assets(call.parameters["id"]!!, call.principal<JWTPrincipal>()?.subject))
            }

            get("/series") { call.respond(DomainRepository.series()) }
            get("/championships") { call.respond(DomainRepository.championships()) }
            get("/championships/{id}/categories") { call.respond(DomainRepository.categories(call.parameters["id"]!!)) }
            get("/categories/{id}/standings") { call.respond(DomainRepository.standings(call.parameters["id"]!!)) }
            get("/categories/{id}/rounds") { call.respond(DomainRepository.rounds(call.parameters["id"]!!)) }
            get("/categories/{id}/drivers") { call.respond(DomainRepository.drivers(call.parameters["id"]!!)) }

            get("/convocatorias") {
                val past = call.request.queryString().contains("status=past")
                call.respond(DomainRepository.convocatorias(past))
            }

            get("/agenda") {
                val officerId = call.principal<JWTPrincipal>()!!.subject ?: ""
                call.respond(DomainRepository.agenda(officerId))
            }
            // —— Invitaciones entre pares: quién invita a quién ——
            route("/invitations") {
                get {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    call.respond(invitationsBy(officerId))
                }
                post {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val req = call.receive<com.alephri.elpuesto.model.InviteRequest>()
                    limite("correo", req.email, TextLimits.CORREO)
                    val email = EmailRules.normalize(req.email)
                    if (!EmailRules.isValid(email)) {
                        return@post call.respond(Ack(ok = false, message = "correo inválido"))
                    }
                    // Misma respuesta exista o no la cuenta (ver createInvitation).
                    call.respond(createInvitation(officerId, email))
                }
            }

            // Tiempo real GENERAL (WebSocket, Bearer): ver generalStream (GeneralStream.kt).
            // La web corre la misma sesión por `/stream/web` con boleto (fuera de authenticate).
            webSocket("/stream") {
                generalStream(call.principal<JWTPrincipal>()!!)
            }
            // Boleto para `/stream/web` (la app web): un solo uso, 60 s, ligado a esta sesión.
            post("/stream/ticket") {
                val ticket = StreamTickets.issue(call.principal<JWTPrincipal>()!!)
                    ?: throw Rejected(HttpStatusCode.ServiceUnavailable, "demasiadas conexiones pendientes; intenta en un momento", 5)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(com.alephri.elpuesto.model.StreamTicket(ticket))
            }

            route("/chats") {
                // Tiempo real del chat (WebSocket, Bearer normal): cada mensaje nuevo
                // notifica {"chatId"} — SOLO de chats que el oficial puede ver — y el
                // cliente refetchea esa conversación (notificar-y-refetch).
                webSocket("/stream") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val officerId = principal.subject!!
                    if (!WsLimits.open(officerId)) {
                        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "demasiadas conexiones"))
                        return@webSocket
                    }
                    val guard = closeWhenRevoked(principal)
                    val notifier = launch {
                        ChangeBus.flow
                            .filter { it.kind == "chat" && it.chatId != null }
                            .filter { DomainRepository.canSeeChat(officerId, it.chatId!!) }
                            .collect { send(Frame.Text("{\"chatId\":\"${it.chatId}\"}")) }
                    }
                    try {
                        for (frame in incoming) { /* canal solo de salida; leer detecta el cierre */ }
                    } finally {
                        notifier.cancel()
                        guard.cancel()
                        WsLimits.close(officerId)
                    }
                }
                get {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    call.respond(DomainRepository.chats(officerId))
                }
                // Crear chat público o privado (cualquiera crea; el creador queda unido; en
                // los privados los invitados reciben invitación). Opcional: ligado a un evento.
                post {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val req = call.receive<com.alephri.elpuesto.model.CreateChatRequest>()
                    limite("nombre", req.name, TextLimits.NOMBRE)
                    limite("descripción", req.description, TextLimits.DESCRIPCION)
                    com.alephri.elpuesto.model.NameRules.chatNameProblem(req.name)?.let {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody(it))
                        return@post
                    }
                    if (com.alephri.elpuesto.model.NameRules.hasHiddenChars(req.description.orEmpty())) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("La descripción tiene caracteres no permitidos."))
                        return@post
                    }
                    if (req.inviteeIds.size > TextLimits.MAX_INVITADOS) {
                        throw Rejected(HttpStatusCode.BadRequest, "invitados: máximo ${TextLimits.MAX_INVITADOS}")
                    }
                    if (req.name.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("nombre requerido"))
                        return@post
                    }
                    Quotas.checkOpenChats(officerId)
                    val (chat, problem) = DomainRepository.createChat(
                        officerId, com.alephri.elpuesto.model.NameRules.normalize(req.name), req.description?.trim()?.ifBlank { null },
                        isPrivate = req.isPrivate, eventId = req.eventId?.ifBlank { null }, inviteeIds = req.inviteeIds,
                    )
                    if (chat == null) call.respond(HttpStatusCode.BadRequest, ErrorBody(problem ?: "no se pudo crear el chat"))
                    else call.respond(chat)
                }
                // Ligar (o desligar) un chat público/privado a un evento que trabajas: aparece
                // en el Modo evento de ese evento. Solo quien lo creó.
                post("/{id}/event") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val req = call.receive<com.alephri.elpuesto.model.SetChatEventRequest>()
                    val problem = DomainRepository.setChatEvent(officerId, call.parameters["id"]!!, req.eventId?.ifBlank { null })
                    if (problem == null) call.respond(Ack(message = if (req.eventId == null) "chat desligado" else "chat ligado al evento"))
                    else call.respond(HttpStatusCode.Forbidden, ErrorBody(problem))
                }
                get("/{id}/messages") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    if (!DomainRepository.canReadChat(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes ver este chat"))
                        return@get
                    }
                    // Por páginas: ?limit= (máx. 200), ?before=<id> (anteriores), ?after=<id> (nuevos).
                    val q = call.request.queryParameters
                    call.respond(
                        DomainRepository.messages(
                            chatId,
                            limit = q["limit"]?.toIntOrNull() ?: DomainRepository.MESSAGES_PAGE_MAX,
                            beforeId = q["before"]?.take(64),
                            afterId = q["after"]?.take(64),
                        ),
                    )
                }
                // Reportar un CHAT (nombre, imagen o descripción). También quien solo tiene la
                // invitación: un nombre ofensivo se reporta sin tener que entrar.
                post("/{id}/report") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    val req = call.receive<com.alephri.elpuesto.model.ReportMessageRequest>()
                    limite("motivo", req.reason, TextLimits.MOTIVO)
                    if (!DomainRepository.canListMembers(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes ver este chat"))
                        return@post
                    }
                    val problem = DomainRepository.reportContent(officerId, "chat", chatId, req.reason?.trim()?.ifBlank { null })
                    call.respond(Ack(ok = problem == null, message = problem ?: "reportado"))
                }
                // Reportar un mensaje (moderación básica): queda registrado y lo revisa el admin.
                post("/{id}/messages/{messageId}/report") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    val req = call.receive<com.alephri.elpuesto.model.ReportMessageRequest>()
                    limite("motivo", req.reason, TextLimits.MOTIVO)
                    if (!DomainRepository.canReadChat(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes ver este chat"))
                        return@post
                    }
                    val problem = DomainRepository.reportMessage(
                        officerId, chatId, call.parameters["messageId"]!!,
                        req.reason?.trim()?.ifBlank { null },
                    )
                    // Rechazo = Ack(ok=false, message) — decodificable por la app (como /invitations).
                    call.respond(Ack(ok = problem == null, message = problem ?: "reportado"))
                }
                // Enviar mensaje de texto (persistente; requiere membresía en públicos).
                post("/{id}/messages") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val req = call.receive<com.alephri.elpuesto.model.SendMessageRequest>()
                    limite("mensaje", req.text, TextLimits.MENSAJE)
                    if (req.text.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("mensaje vacío"))
                        return@post
                    }
                    val msg = DomainRepository.sendMessage(officerId, call.parameters["id"]!!, req.text.trim())
                    if (msg == null) call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes escribir en este chat"))
                    else call.respond(msg)
                }
                // Mensaje con imagen: bytes JPEG en el body, pie opcional en ?text=.
                post("/{id}/media") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    val caption = call.request.queryParameters["text"]?.trim().orEmpty()
                    limite("pie de foto", caption, TextLimits.PIE)
                    // Antes de leer la foto: ¿puede escribir aquí y le queda cupo?
                    if (!DomainRepository.canWriteChat(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes escribir en este chat"))
                        return@post
                    }
                    Quotas.checkPhotos(officerId)
                    val bytes = call.receive<ByteArray>()
                    // La imagen se valida y procesa ANTES de crear el mensaje (antes quedaban
                    // mensajes "📷 Foto" sin foto si la imagen era inválida).
                    val processed = ImageService.preparePhoto(bytes)
                    if (processed == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("imagen inválida"))
                        return@post
                    }
                    val msg = DomainRepository.sendMessage(
                        officerId, chatId, caption,
                        mediaType = com.alephri.elpuesto.model.MessageMediaType.IMAGE,
                    )
                    if (msg == null) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes escribir en este chat"))
                        return@post
                    }
                    ImageService.storePrepared("chatmedia", msg.id, processed)
                    call.respond(msg)
                }
                // Participantes: quien puede leer el chat (y el invitado a un privado, para
                // decidir si acepta). Los de un privado solo sus miembros.
                get("/{id}/members") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    if (!DomainRepository.canListMembers(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes ver este chat"))
                        return@get
                    }
                    call.respond(DomainRepository.chatMembers(chatId, officerId))
                }
                // Invitar (público o privado): al invitado le llega una invitación que acepta
                // o rechaza; nadie entra a un grupo sin su consentimiento.
                post("/{id}/members") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val req = call.receive<com.alephri.elpuesto.model.AddChatMemberRequest>()
                    val problem = DomainRepository.addChatMember(officerId, call.parameters["id"]!!, req.officerId)
                    if (problem == null) call.respond(Ack(message = "invitado"))
                    else call.respond(HttpStatusCode.Forbidden, ErrorBody(problem))
                }
                // Marca de lectura: el indicador de no leídos queda en 0 para este oficial.
                post("/{id}/read") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    if (!DomainRepository.canReadChat(officerId, call.parameters["id"]!!)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("no puedes ver este chat"))
                        return@post
                    }
                    val ok = DomainRepository.markChatRead(officerId, call.parameters["id"]!!)
                    call.respond(Ack(ok = ok, message = if (ok) "leído" else "chat inexistente"))
                }
                // Imagen del chat (avatar del grupo): cualquier miembro de un chat público o
                // cualquiera de la posición en un chat de puesto.
                post("/{id}/image") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val chatId = call.parameters["id"]!!
                    if (!DomainRepository.canSetChatImage(officerId, chatId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorBody("solo los miembros del chat pueden cambiar la imagen"))
                        return@post
                    }
                    val bytes = call.receive<ByteArray>()
                    if (!ImageService.processAndStore("chatimg", chatId, bytes)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("imagen inválida"))
                        return@post
                    }
                    // Los miembros conectados revalidan su copia guardada (la URL no cambia).
                    ChangeBus.emit(null, "image", chatId = chatId, id = "/images/chatimg/$chatId/full")
                    call.respond(Ack(message = "imagen actualizada"))
                }
                post("/{id}/archive") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val ok = DomainRepository.archiveChat(officerId, call.parameters["id"]!!)
                    if (ok) call.respond(Ack(message = "chat archivado"))
                    else call.respond(HttpStatusCode.Forbidden, ErrorBody("solo quien creó el chat puede archivarlo"))
                }
                // Unirse a un público, o ACEPTAR la invitación a un privado.
                post("/{id}/join") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val ok = DomainRepository.setChatJoined(officerId, call.parameters["id"]!!, join = true)
                    if (ok) call.respond(Ack(message = "unido"))
                    else call.respond(HttpStatusCode.NotFound, ErrorBody("chat no encontrado, no es de grupo o no tienes invitación"))
                }
                // Salir de un público/privado, o RECHAZAR la invitación a un privado.
                post("/{id}/leave") {
                    val officerId = call.principal<JWTPrincipal>()!!.subject!!
                    val ok = DomainRepository.setChatJoined(officerId, call.parameters["id"]!!, join = false)
                    if (ok) call.respond(Ack(message = "saliste del chat"))
                    else call.respond(HttpStatusCode.NotFound, ErrorBody("chat no encontrado o no es de grupo"))
                }
            }
        }
    }
}

/** Etiqueta de un puesto propuesto por un oficial: empieza con letra o número. */
private val PUESTO_PROPUESTO = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} .\\-/#]{0,39}$")

/**
 * Reglas de la planeación personal: un solo vínculo (evento, carrera del calendario o
 * convocatoria), la carrera debe existir y el fin (llegada/salida) no va antes del inicio.
 */
private fun tripItemProblem(item: com.alephri.elpuesto.model.TripItem): String? {
    if (item.title.length > TextLimits.TITULO) return "título: máximo ${TextLimits.TITULO} caracteres"
    if ((item.detail?.length ?: 0) > TextLimits.NOTA) return "detalle: máximo ${TextLimits.NOTA} caracteres"
    if (listOfNotNull(item.eventId, item.roundId, item.convocatoriaId).size > 1)
        return "vincula la planeación a un solo evento, carrera o convocatoria"
    if (item.roundId != null && !DomainRepository.roundExists(item.roundId!!))
        return "la carrera vinculada ya no existe en el calendario"
    val end = item.endsAt ?: return null
    val start = item.at ?: return "la hora de fin requiere fecha de inicio"
    return if (end < start) "el fin no puede ser antes del inicio" else null
}
