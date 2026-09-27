package com.alephri.elpuesto.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import java.security.MessageDigest
import java.util.Base64

/**
 * Cabeceras de seguridad y aislamiento de orígenes.
 *
 * - **CSP en toda respuesta**: el API responde `default-src 'none'`; el admin web solo
 *   carga sus propios archivos (más los mosaicos y la búsqueda de OpenStreetMap); la página
 *   puente del correo solo corre SU script (por hash); la app web (`/app/`) solo sus propios
 *   archivos (más `'wasm-unsafe-eval'` para compilar su WebAssembly) y solo habla con este
 *   mismo origen. Un XSS que se colara no podría cargar ni mandar nada a otro sitio.
 * - **Permissions-Policy**: nada de cámara, micrófono, sensores ni pagos; la ubicación solo
 *   para la app web y solo en su propio origen (compartir ubicación en un evento).
 * - **Host del admin** ([Config.adminHost]): el admin web guarda su clave en el navegador,
 *   así que vive en su propio origen y ninguna otra página nuestra lo comparte.
 */
object WebSecurity {
    /** Script ÚNICO de la página puente: abre la app con el enlace del botón. */
    private const val AUTH_OPEN_SCRIPT = "location.href = document.getElementById('abrir').href;"
    private val authOpenScriptHash: String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(AUTH_OPEN_SCRIPT.toByteArray()),
    )

    private const val API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

    private const val ADMIN_CSP =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' blob: data: https://tile.openstreetmap.org; " +
            "connect-src 'self' https://nominatim.openstreetmap.org; font-src 'self'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private val authOpenCsp =
        "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-$authOpenScriptHash'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /** Página de descargas de desarrollo (estilos en línea, sin scripts). */
    private const val DOWNLOADS_CSP =
        "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /**
     * App web de los oficiales (Compose/Wasm): sus scripts y su `.wasm` (compilarlo exige
     * `'wasm-unsafe-eval'`, que NO habilita `eval` de JavaScript), estilos en línea del
     * lienzo, imágenes/fuentes propias o generadas (blob:/data:), workers propios y la API y
     * el WebSocket de este mismo origen.
     */
    private const val APP_CSP =
        "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' blob: data:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private const val PERMISSIONS =
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"

    /** Igual que [PERMISSIONS] pero la app web puede pedir la ubicación (solo para sí misma). */
    private const val APP_PERMISSIONS =
        "accelerometer=(), camera=(), geolocation=(self), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"

    private fun isWebApp(path: String): Boolean = path == "/app" || path.startsWith("/app/")

    fun cspFor(path: String): String = when {
        path == "/admin/ui" || path.startsWith("/admin/ui/") -> ADMIN_CSP
        path == "/auth/open" -> authOpenCsp
        path == "/descargas" || path.startsWith("/descargas/") -> DOWNLOADS_CSP
        isWebApp(path) -> APP_CSP
        else -> API_CSP
    }

    fun permissionsFor(path: String): String = if (isWebApp(path)) APP_PERMISSIONS else PERMISSIONS

    fun install(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            // Ya respondida por un plugin anterior (el preflight o el rechazo de CORS): sus
            // cabeceras ya salieron y agregar más truena.
            if (call.response.isCommitted) return@intercept
            val path = call.request.path()
            val headers = call.response.headers
            headers.append("X-Frame-Options", "DENY")
            headers.append("X-Content-Type-Options", "nosniff")
            // Sin Referer: la página puente lleva el token en la URL.
            headers.append("Referrer-Policy", "no-referrer")
            headers.append("Content-Security-Policy", cspFor(path))
            headers.append("Cross-Origin-Opener-Policy", "same-origin")
            headers.append("Cross-Origin-Resource-Policy", "same-origin")
            headers.append("Permissions-Policy", permissionsFor(path))

            val host = call.request.host().lowercase()
            // Host propio de la app web: ahí vive `/app/` (la raíz lleva a ella) y en cualquier
            // otro host `/app` se muda. El admin sigue su propia regla (abajo).
            Config.appHost?.let { appHost ->
                if (host == appHost && path == "/") {
                    call.respondRedirect("/app/")
                    finish()
                    return@intercept
                }
                if (host != appHost && isWebApp(path)) {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respondRedirect("https://$appHost/app/")
                    finish()
                    return@intercept
                }
            }

            val adminHost = Config.adminHost ?: return@intercept
            val isAdminPath = path == "/admin" || path.startsWith("/admin/")
            if (host == adminHost) {
                // El host del admin no sirve nada más (ni la API de la app, ni la app web
                // `/app`, ni páginas): la clave del admin no comparte origen con nadie.
                if (!isAdminPath && path != "/health") {
                    if (path == "/") call.respondRedirect("/admin/ui/") else call.respondNotFound()
                    finish()
                }
            } else if (isAdminPath) {
                // Fuera del host del admin: el admin web se muda (redirige); su API no existe.
                if (path == "/admin/ui" || path.startsWith("/admin/ui/")) {
                    call.respondRedirect("https://$adminHost/admin/ui/")
                } else {
                    call.respondNotFound()
                }
                finish()
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondNotFound() {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        respond(HttpStatusCode.NotFound, ErrorBody("no encontrado"))
    }

    /** Forma exacta de un token de enlace mágico (UUID en minúsculas). */
    private val MAGIC_TOKEN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun isMagicToken(token: String?): Boolean = token != null && MAGIC_TOKEN.matches(token)

    /**
     * Página puente del enlace mágico (tema Paddock nocturno): abre la app sola y deja un
     * botón por si el navegador bloquea la redirección. El token ya se validó con
     * [isMagicToken] y además se escapa: nada del URL llega crudo al HTML.
     */
    fun authOpenPage(token: String): String {
        val href = htmlEscape("elpuesto://auth?token=$token")
        return """
            <!doctype html>
            <html lang="es">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="referrer" content="no-referrer">
              <title>El Puesto — acceso</title>
              <style>
                body { margin:0; background:#12151C; color:#E8EAF0; font-family:system-ui,Arial,sans-serif;
                       display:flex; min-height:100vh; align-items:center; justify-content:center; }
                .card { background:#1E222B; border:1px solid #333949; border-radius:18px; max-width:420px;
                        margin:20px; padding:30px; text-align:center; }
                .brand { font-weight:800; letter-spacing:2px; font-size:20px; }
                .sub { font-family:monospace; letter-spacing:4px; font-size:11px; color:#F2B134; margin-top:4px; }
                p { color:#9AA3B5; font-size:14px; line-height:1.5; }
                b { color:#E8EAF0; }
                .btn { display:block; background:#F2B134; color:#1E222B; font-weight:800; font-size:16px;
                       text-decoration:none; padding:15px 24px; border-radius:14px; margin-top:22px; }
                .hint { font-size:11px; color:#6B7385; margin-top:16px; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="brand">EL PUESTO</div>
                <div class="sub">OFICIALES DE PISTA</div>
                <p>Estás a un toque de entrar. Este enlace solo funciona <b>en el teléfono donde lo pediste</b>, con la app instalada.</p>
                <a id="abrir" class="btn" href="$href">Abrir El Puesto&nbsp;&nbsp;→</a>
                <div class="hint">Si nada pasa, abre el correo en ese teléfono o pide un enlace nuevo (vencen en 15 minutos).</div>
              </div>
              <script>$AUTH_OPEN_SCRIPT</script>
            </body>
            </html>
        """.trimIndent()
    }

    fun htmlEscape(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
