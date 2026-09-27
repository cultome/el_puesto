package com.alephri.elpuesto.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * IP del cliente. Detrás del túnel (ngrok llega desde loopback) se toma el ÚLTIMO valor
 * de X-Forwarded-For — el que agregó el proxy de confianza; los anteriores los puede
 * inventar el cliente. Una conexión directa (no loopback) ignora el header.
 */
fun ApplicationCall.clientIp(): String {
    val remote = request.origin.remoteHost
    val viaLocalProxy = remote == "127.0.0.1" || remote == "localhost" || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
    if (viaLocalProxy) {
        request.header(HttpHeaders.XForwardedFor)?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return remote
}

/**
 * Límite de intentos en memoria (ventana fija + bloqueo). Frena la fuerza bruta contra
 * la clave admin y el abuso del envío de enlaces mágicos (spam de correo, enumeración).
 * Por instancia: con varias instancias haría falta un almacén compartido.
 */
object Throttle {
    private val log = LoggerFactory.getLogger("Throttle")

    private class Bucket(var windowStart: Long, var count: Int, var blockedUntil: Long = 0)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Segundos que faltan de bloqueo para [key]; null = libre. */
    fun blockedFor(key: String, now: Long = System.currentTimeMillis()): Long? {
        val b = buckets[key] ?: return null
        return if (b.blockedUntil > now) (b.blockedUntil - now + 999) / 1000 else null
    }

    /**
     * Cuenta un evento de [key]; si en la ventana pasa de [limit], bloquea por [blockMs].
     * Devuelve true si quedó (o ya estaba) bloqueado.
     */
    fun hit(key: String, limit: Int, windowMs: Long, blockMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val b = buckets.compute(key) { _, old ->
            when {
                old == null || now - old.windowStart > windowMs -> Bucket(now, 1, old?.blockedUntil ?: 0)
                else -> old.also { it.count++ }
            }
        }!!
        if (b.count > limit && b.blockedUntil <= now) {
            b.blockedUntil = now + blockMs
            log.warn("bloqueado {} por {} s ({} eventos en la ventana)", key, blockMs / 1000, b.count)
        }
        if (buckets.size > 50_000) purge(now) // cota de memoria ante barridos de IPs
        return b.blockedUntil > now
    }

    fun reset(key: String) { buckets.remove(key) }

    private fun purge(now: Long) {
        buckets.entries.removeIf { (_, b) -> b.blockedUntil <= now && now - b.windowStart > 60 * 60_000 }
    }

    /** Solo pruebas: limpia todo. */
    internal fun clear() = buckets.clear()
}

private const val MIN = 60_000L

/** Clave admin: 10 fallos en 15 min por IP bloquean esa IP 15 min. */
private const val ADMIN_FAIL_LIMIT = 10

/**
 * Resuelve la X-Admin-Key con freno de fuerza bruta: una IP bloqueada recibe 429 (con
 * Retry-After) aunque traiga la clave correcta; cada clave inválida suma un fallo.
 * Responde el error y devuelve null si no hay actor.
 */
suspend fun ApplicationCall.adminActorOrFail(): AdminActor? {
    val key = "admin:${clientIp()}"
    Throttle.blockedFor(key)?.let { secs ->
        response.header(HttpHeaders.RetryAfter, secs.toString())
        respond(
            HttpStatusCode.TooManyRequests,
            AdminError("demasiados intentos con clave inválida desde esta IP", hint = "espera $secs s y reintenta con una clave vigente"),
        )
        return null
    }
    val actor = AdminAuth.resolve(request.header("X-Admin-Key"))
    if (actor == null) {
        // Una IP ya bloqueada no llega aquí: si este fallo la bloquea, es el momento de avisar
        // (una vez por bloqueo; SecurityMonitor además agrupa a una por hora).
        if (Throttle.hit(key, ADMIN_FAIL_LIMIT, 15 * MIN, 15 * MIN)) {
            SecurityMonitor.alert(
                "admin-clave", "Fuerza bruta contra la clave de admin",
                listOf(
                    "La IP ${clientIp()} falló la clave de admin más de $ADMIN_FAIL_LIMIT veces en 15 minutos " +
                        "y quedó bloqueada 15 minutos (host ${request.host()}, ${request.httpMethod.value} ${request.path()}).",
                    "Si no fuiste tú: revisa en el admin web Claves y Auditoría, y rota la clave maestra " +
                        "(ADMIN_API_KEY en Parameter Store) si pudo filtrarse.",
                ),
            )
        }
        respond(
            HttpStatusCode.Unauthorized,
            AdminError("clave de admin inválida o ausente", hint = "manda el header X-Admin-Key con una clave vigente"),
        )
        return null
    }
    return actor
}

/**
 * Enlace mágico: máx. 10 solicitudes por IP y 5 por correo cada 15 min, y 12 por correo al
 * día aunque cambien las IPs (frena el spam de correos desde nuestro dominio y la
 * enumeración de cuentas invitadas). true = se respondió 429.
 */
suspend fun ApplicationCall.magicLinkThrottled(email: String): Boolean {
    val byIp = "magic-ip:${clientIp()}"
    val byEmail = "magic-email:$email"
    val byEmailDay = "magic-email-dia:$email"
    val keys = listOf(byIp, byEmail, byEmailDay)
    val blocked = keys.firstNotNullOfOrNull { Throttle.blockedFor(it) }
        ?: run {
            val ipOver = Throttle.hit(byIp, 10, 15 * MIN, 15 * MIN)
            val emailOver = Throttle.hit(byEmail, 5, 15 * MIN, 15 * MIN)
            val dayOver = Throttle.hit(byEmailDay, 12, 24 * 60 * MIN, 24 * 60 * MIN)
            if (ipOver || emailOver || dayOver) keys.firstNotNullOfOrNull { Throttle.blockedFor(it) } else null
        }
    if (blocked == null) return false
    response.header(HttpHeaders.RetryAfter, blocked.toString())
    respond(HttpStatusCode.TooManyRequests, ErrorBody("demasiadas solicitudes; intenta en unos minutos"))
    return true
}

/**
 * Correos que aceptamos (invitaciones, enlace mágico, cuentas del admin): UNA dirección
 * simple en minúsculas. Nada de nombres visibles, comillas, comas ni espacios — con ellos
 * alguien podía hacer que el correo saliera con texto suyo o a varias personas a la vez.
 */
object EmailRules {
    private val FORMA = Regex(
        "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
    )

    fun normalize(raw: String): String = raw.trim().lowercase()

    fun isValid(email: String): Boolean = email.length <= 254 && FORMA.matches(email)
}

/**
 * Secretos: expuesto a internet (hay PUBLIC_BASE_URL) no se arranca con los defaults de
 * desarrollo ni con claves cortas — con la llave de firma por default cualquiera fabrica
 * sesiones, y una clave admin corta se adivina. En local solo se advierte.
 */
object SecretsCheck {
    private val log = LoggerFactory.getLogger("SecretsCheck")
    private const val DEFAULT_JWT = "dev-secret-el-puesto-change-me"
    private const val DEFAULT_ADMIN = "dev-admin-key-change-me"

    fun problems(): List<String> = buildList {
        if (Config.jwtSecret == DEFAULT_JWT || Config.jwtSecret.length < 32) {
            add("JWT_SECRET es el default de desarrollo o tiene menos de 32 caracteres")
        }
        if (Config.adminApiKey == DEFAULT_ADMIN || Config.adminApiKey.length < 32) {
            add("ADMIN_API_KEY es el default de desarrollo o tiene menos de 32 caracteres")
        }
    }

    fun enforce() {
        val p = problems()
        if (p.isEmpty()) return
        val how = "genera uno con: openssl rand -hex 32"
        if (Config.publicBaseUrl != null) {
            error("Backend público (PUBLIC_BASE_URL=${Config.publicBaseUrl}) con secretos inseguros: ${p.joinToString("; ")} — $how")
        }
        p.forEach { log.warn("secreto inseguro (aceptado solo en local): {} — {}", it, how) }
    }
}
