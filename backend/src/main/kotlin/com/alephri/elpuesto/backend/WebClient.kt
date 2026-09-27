package com.alephri.elpuesto.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.header
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * La app web de los oficiales (Compose/Wasm en el navegador): lo que cambia respecto a la
 * app Android está aquí.
 *
 * - **Sesión**: el access token vive solo en memoria del navegador; el refresh token, en la
 *   cookie [COOKIE] (HttpOnly: ningún script, ni uno inyectado, la puede leer). La cookie
 *   solo se usa en `/auth/callback|refresh|logout` y solo si viene la cabecera [HEADER]
 *   (anti-CSRF: un formulario ajeno no puede mandarla y un fetch ajeno necesita CORS); además
 *   un `Origin` ajeno se rechaza ([originAllowed]).
 * - **WebSocket**: el del navegador no puede mandar `Authorization`, así que la web pide un
 *   boleto de un solo uso ([StreamTickets]) y abre `/stream/web?ticket=…`.
 */
object WebClient {
    /** Cabecera con que la app web se identifica (`web`). */
    const val HEADER = "X-El-Puesto-Client"
    const val WEB = "web"

    /** Cookie del refresh token de la web. */
    const val COOKIE = "ep_rt"

    /** ¿La petición viene de la app web? (Sin la cabecera = la app Android, tal cual.) */
    fun isWeb(call: ApplicationCall): Boolean = call.request.header(HEADER) == WEB

    /** Refresh token de la cookie (solo tiene sentido con [isWeb]). */
    fun refreshFromCookie(call: ApplicationCall): String? =
        call.request.cookies.rawCookies[COOKIE]?.takeIf { it.isNotEmpty() && it.length <= 200 }

    /**
     * Pone la cookie del refresh token: `HttpOnly; Secure; SameSite=Strict; Path=/auth` y
     * `Max-Age` = vida del refresh. Sin `Secure` SOLO en desarrollo (sin PUBLIC_BASE_URL),
     * para que funcione en http://localhost. Respuesta con tokens: nunca en caché.
     */
    fun setRefreshCookie(call: ApplicationCall, refresh: String) {
        call.response.headers.append(HttpHeaders.SetCookie, cookie(refresh, Config.refreshTtlDays * 86_400))
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    }

    /** Borra la cookie (Max-Age=0): sesión rechazada o cerrada. */
    fun clearRefreshCookie(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.SetCookie, cookie("", 0))
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    }

    private fun cookie(value: String, maxAge: Long): String = buildString {
        append("$COOKIE=$value; Max-Age=$maxAge; Path=/auth; HttpOnly")
        if (Config.publicBaseUrl != null) append("; Secure")
        append("; SameSite=Strict")
    }

    /**
     * `Origin` permitido: el mismo host que atiende la petición o uno de `CORS_ORIGINS`.
     * Sin `Origin` (la app Android, curl) se acepta aquí; [requireOrigin] = true lo exige
     * (el WebSocket del navegador siempre lo manda: sin él no es un navegador).
     */
    fun originAllowed(call: ApplicationCall, requireOrigin: Boolean = false): Boolean {
        val origin = call.request.header(HttpHeaders.Origin) ?: return !requireOrigin
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false // "null" (sandbox, file://) = ajeno
        val authority = uri.rawAuthority?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return false
        val host = call.request.header(HttpHeaders.Host)?.trim()?.lowercase()
        if (host != null && authority == host) return true
        return "$scheme://$authority" in allowedOrigins
    }

    /**
     * Orígenes de ESTE servidor tal como los ve el navegador (PUBLIC_BASE_URL y los hosts del
     * admin y de la app web). Solo para el plugin CORS: [originAllowed] ya los acepta por ser el mismo host.
     */
    fun ownOrigins(): List<String> = listOfNotNull(
        Config.publicBaseUrl?.let { runCatching { URI(it) }.getOrNull() }?.let { u -> u.scheme?.let { "$it://${u.rawAuthority}" } },
        Config.adminHost?.let { "https://$it" },
        Config.appHost?.let { "https://$it" },
    )

    /** `CORS_ORIGINS` normalizados a `esquema://host[:puerto]` (sin esquema = https, como el plugin CORS). */
    private val allowedOrigins: Set<String> by lazy {
        Config.corsOrigins.map { o -> (if ("://" in o) o else "https://$o").lowercase().trimEnd('/') }.toSet()
    }

    /** 403 si el `Origin` es ajeno (el handler no sigue). */
    fun checkOrigin(call: ApplicationCall) {
        if (!originAllowed(call)) throw Rejected(HttpStatusCode.Forbidden, "origen no permitido")
    }
}

/**
 * Boletos del WebSocket de la web: 32 bytes aleatorios (base64url), **un solo uso**, vencen
 * en 60 s, en memoria y ligados a la sesión (JWT) de quien los pidió. Tope por oficial y
 * total: nadie llena la memoria pidiendo boletos que no usa.
 */
object StreamTickets {
    private const val TTL_MS = 60_000L
    private const val MAX_POR_OFICIAL = 5
    private const val MAX_TOTAL = 10_000

    /** 32 bytes en base64url sin relleno = 43 caracteres. */
    private val FORMA = Regex("^[A-Za-z0-9_-]{43}$")

    private class Entry(val principal: JWTPrincipal, val expiresAt: Long)

    private val random = SecureRandom()
    private val lock = Any()
    private val tickets = HashMap<String, Entry>()

    /** Boletos por oficial en orden de emisión (los ya usados se limpian al emitir). */
    private val porOficial = HashMap<String, ArrayDeque<String>>()

    /** Emite un boleto para [principal]; null = el servidor está lleno (503). */
    fun issue(principal: JWTPrincipal, now: Long = System.currentTimeMillis()): String? {
        val subject = principal.subject ?: return null
        val bytes = ByteArray(32).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        synchronized(lock) {
            if (tickets.size >= MAX_TOTAL / 2) purge(now)
            if (tickets.size >= MAX_TOTAL) return null
            val mine = porOficial.getOrPut(subject) { ArrayDeque() }
            mine.removeAll { it !in tickets }
            // Solo los últimos MAX_POR_OFICIAL valen: pedir más invalida los más viejos.
            while (mine.size >= MAX_POR_OFICIAL) tickets.remove(mine.removeFirst())
            tickets[ticket] = Entry(principal, now + TTL_MS)
            mine.addLast(ticket)
        }
        return ticket
    }

    /** Canjea (y borra) el boleto: la sesión de quien lo pidió, o null si no existe, ya se usó o venció. */
    fun consume(ticket: String?, now: Long = System.currentTimeMillis()): JWTPrincipal? {
        if (ticket == null || !FORMA.matches(ticket)) return null
        val entry = synchronized(lock) { tickets.remove(ticket) } ?: return null
        return entry.principal.takeIf { entry.expiresAt > now }
    }

    private fun purge(now: Long) {
        tickets.entries.removeIf { it.value.expiresAt <= now }
        porOficial.values.forEach { q -> q.removeAll { it !in tickets } }
        porOficial.entries.removeIf { it.value.isEmpty() }
    }
}
