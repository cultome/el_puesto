package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AccountStatus
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Corta la llamada con un status: StatusPages la responde y el handler NO corre (lanzarla
 * desde un hook o interceptor garantiza que una escritura rechazada no se ejecute).
 */
class Rejected(val status: HttpStatusCode, message: String, val retryAfterSec: Long? = null) :
    RuntimeException(message, null, false, false)

/**
 * Estado de las cuentas para validar sesiones en cada petición (caché de 30 s; cada cambio
 * hecho en este proceso la invalida al momento).
 *
 * - Un token deja de valer si su cuenta cortó sesiones DESPUÉS de emitirlo
 *   (`sessions_revoked_at`: cerrar sesiones, reuso de refresh token, suspensión) o si la
 *   cuenta quedó ligada a otro oficial.
 * - Las rutas de datos exigen cuenta ACTIVE ([CuentaActiva]): suspender corta la API, no
 *   solo lo que muestra la app.
 */
object AccountGate {
    data class Info(val status: AccountStatus, val officerId: String?, val sessionsRevokedAt: Instant?)

    private class Cached(val info: Info?, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private const val TTL_MS = 30_000L

    fun info(email: String): Info? {
        val now = System.currentTimeMillis()
        cache[email]?.takeIf { now - it.at < TTL_MS }?.let { return it.info }
        val info = accountGateInfo(email)
        cache[email] = Cached(info, now)
        if (cache.size > 20_000) cache.entries.removeIf { now - it.value.at > TTL_MS }
        return info
    }

    fun invalidate(email: String) {
        cache.remove(email)
    }

    fun invalidateAll() = cache.clear()

    /** ¿El token sigue siendo de su cuenta y posterior al último corte de sesiones? */
    fun tokenValid(email: String, subject: String, issuedAt: Instant?): Boolean {
        val i = info(email) ?: return false
        if (subjectOf(email, i.officerId) != subject) return false
        val cut = i.sessionsRevokedAt ?: return true
        return issuedAt != null && !issuedAt.isBefore(cut)
    }

    /** Activa Y con oficial ligado: sin oficial la cuenta no tiene identidad en el sistema. */
    fun isActive(email: String): Boolean = info(email)?.let { it.status == AccountStatus.ACTIVE && it.officerId != null } == true

    /** Sujeto del JWT de una cuenta: su oficial, o un id opaco mientras no tiene (nunca el correo). */
    fun subjectOf(email: String, officerId: String?): String = officerId ?: pendingSubject(email)

    /** Canales largos (WS/SSE): la sesión con que se abrieron sigue viva y la cuenta activa. */
    fun usable(p: JWTPrincipal): Boolean {
        val email = p.email() ?: return false
        return tokenValid(email, p.subject ?: return false, p.payload.issuedAt?.toInstant()) && isActive(email)
    }
}

fun JWTPrincipal.email(): String? = payload.getClaim("email").asString()

/**
 * Guardia de las rutas de datos: cuenta ACTIVE (salvo `GET /me`, que la app necesita para
 * saber en qué estado está la cuenta) y límite de uso por oficial ([RateLimits]).
 */
val CuentaActiva = createRouteScopedPlugin("CuentaActiva") {
    on(AuthenticationChecked) { call ->
        val p = call.principal<JWTPrincipal>() ?: return@on // sin sesión: la auth ya respondió 401
        val email = p.email() ?: return@on
        val method = call.request.httpMethod
        val path = call.request.path()
        val soloEstado = method == HttpMethod.Get && path == "/me"
        if (!soloEstado && !AccountGate.isActive(email)) {
            throw Rejected(HttpStatusCode.Forbidden, "tu cuenta no está activa")
        }
        // Funciones pausadas por el admin (Operations.kt), antes de gastar fichas.
        FeatureSwitches.keysFor(method, path).forEach(FeatureSwitches::check)
        RateLimits.classifyAll(method, path).forEach { RateLimits.check("oficial:${p.subject}", it) }
    }
}

/**
 * WebSockets abiertos por oficial (la app usa uno; con la web y reconexiones a medias
 * puede haber más). Tope para que nadie acapare conexiones.
 */
object WsLimits {
    private const val MAX_POR_OFICIAL = 5
    private val abiertos = ConcurrentHashMap<String, AtomicInteger>()

    /** Registra una conexión; false si ya tiene el máximo (no se registra). */
    fun open(officerId: String): Boolean {
        val n = abiertos.computeIfAbsent(officerId) { AtomicInteger() }.incrementAndGet()
        if (n > MAX_POR_OFICIAL) {
            close(officerId)
            return false
        }
        return true
    }

    fun close(officerId: String) {
        abiertos[officerId]?.let { if (it.decrementAndGet() <= 0) abiertos.remove(officerId, it) }
    }
}

/**
 * Vigila un WebSocket abierto: si la sesión se cerró (suspensión, "cerrar sesiones",
 * reuso de token) lo cierra en ≤ 30 s. Cancelar el Job al terminar el socket.
 */
fun DefaultWebSocketServerSession.closeWhenRevoked(p: JWTPrincipal): Job = launch {
    while (true) {
        delay(30_000)
        if (!AccountGate.usable(p)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "sesión cerrada"))
            break
        }
    }
}
