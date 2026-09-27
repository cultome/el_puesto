package com.alephri.elpuesto.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.http.toHttpDate
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.combineSafe
import io.ktor.util.date.GMTDate
import java.io.File

/**
 * La app web de los oficiales (Compose Multiplatform / Kotlin-Wasm, compilada aparte a
 * archivos estáticos) servida en `/app/` desde [Config.webAppDir]. Sin esa carpeta no hay
 * rutas (`/app` da 404). Su CSP y su Permissions-Policy (ubicación solo para sí misma) las
 * pone [WebSecurity]; el host del admin no la sirve.
 *
 * - `/app` → redirige a `/app/` (las rutas relativas del index dependen de la barra).
 * - `/app/` → `index.html`; `/app/<archivo>` → el archivo, sin salir de la carpeta
 *   ([combineSafe] + ruta canónica: ni `..` ni enlaces simbólicos que apunten fuera) y sin
 *   archivos ocultos (`.algo`).
 * - `Cache-Control: no-cache` + ETag: el navegador revalida cada vez (una versión nueva se
 *   ve al recargar) pero no vuelve a bajar el `.wasm` si no cambió (304).
 */
object WebApp {
    private val WASM = ContentType.parse("application/wasm")
    private val JS = ContentType.parse("text/javascript")
    private val HTML = ContentType.parse("text/html; charset=utf-8")

    /** `.wasm` DEBE ser application/wasm (lo exige `WebAssembly.instantiateStreaming`; además va `nosniff`). */
    fun contentTypeFor(file: File): ContentType = when (file.extension.lowercase()) {
        "wasm" -> WASM
        "mjs", "js" -> JS
        "html" -> HTML
        else -> ContentType.defaultForFilePath(file.name)
    }

    /** El archivo pedido dentro de [root] (canónica), o null si no existe, es oculto o saldría de ella. */
    fun resolve(root: File, segments: List<String>): File? {
        val parts = segments.filter { it.isNotEmpty() }
        if (parts.any { it.startsWith('.') || '\\' in it || '\u0000' in it }) return null
        val relative = parts.joinToString("/").ifEmpty { "index.html" }
        val file = runCatching { root.combineSafe(relative).canonicalFile }.getOrNull() ?: return null
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file
    }

    fun etagOf(file: File): String = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
}

/** Rutas de la app web (ver [WebApp]); sin `WEB_APP_DIR` no registra ninguna. */
fun Route.webAppRoutes() {
    val root = Config.webAppDir?.let { File(it).canonicalFile } ?: return
    get("/app") { call.respondRedirect("/app/") }
    get("/app/{path...}") {
        val file = WebApp.resolve(root, call.parameters.getAll("path").orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("no encontrado"))
        val etag = WebApp.etagOf(file)
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.ETag, etag)
        call.response.headers.append(HttpHeaders.LastModified, GMTDate(file.lastModified()).toHttpDate())
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim().removePrefix("W/") == etag }) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respond(LocalFileContent(file, WebApp.contentTypeFor(file)))
        }
    }
}
