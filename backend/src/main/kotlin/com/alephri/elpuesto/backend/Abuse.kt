package com.alephri.elpuesto.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Límite de uso por oficial (cubeta de fichas: `capacidad` = ráfaga, `porSegundo` =
 * ritmo sostenido). Holgado a propósito: frena scripts, no a un oficial. Las ráfagas
 * grandes existen de verdad — al arrancar la app precarga cientos de imágenes y al volver
 * la señal el outbox se vacía de golpe — y la v1.1.0 de la app DESCARTA lo que el
 * servidor rechaza con 429, así que ningún uso normal debe tocar estos topes.
 * `RATE_LIMIT_SCALE` los multiplica (0 = apagados; 0.01 para probarlos).
 */
object RateLimits {
    private const val DIA = 86_400.0

    enum class Clase(val capacidad: Double, val porSegundo: Double, val etiqueta: String) {
        LECTURA(600.0, 20.0, "lecturas"),
        IMAGEN(1500.0, 50.0, "imágenes"),
        ESCRITURA(300.0, 2.0, "cambios"),
        MENSAJE(60.0, 0.5, "mensajes"),
        SUBIDA(60.0, 1.0 / 60, "fotos"),
        REPORTE(20.0, 20 / DIA, "reportes"),
        INVITACION(20.0, 20 / DIA, "invitaciones"),
        CHAT_NUEVO(10.0, 10 / DIA, "chats nuevos"),
        MIEMBRO(100.0, 100 / DIA, "invitaciones a chats"),
        REGISTRO(30.0, 30 / DIA, "registros por honor"),
        BUSQUEDA(60.0, 400 / DIA, "búsquedas"),
        UBICACION(10.0, 0.1, "ubicación"),

        // —— Topes diarios y clases propias (2026-09-26): frenan la acumulación y el
        // barrido, no el uso normal ——
        /** Mensajes al día (además del ritmo de [MENSAJE]). */
        MENSAJE_DIA(1_500.0, 1_500 / DIA, "mensajes del día"),
        /** Fotos al día (además del ritmo de [SUBIDA]). */
        SUBIDA_DIA(150.0, 150 / DIA, "fotos del día"),
        /** Perfiles, logros y eventos en común de OTROS oficiales: no para recorrer el directorio. */
        PERFIL(200.0, 1_000 / DIA, "perfiles"),
        /** Interruptor, allowlist y ocultos de la ubicación. */
        UBICACION_CONFIG(60.0, 200 / DIA, "cambios de ubicación"),
        BLOQUEO(20.0, 20 / DIA, "bloqueos"),
        EXPORTACION(3.0, 3 / DIA, "descargas de datos"),
        /** Conexiones en vivo (SSE/WebSocket): reconectar con mala señal sí, en bucle no. */
        STREAM(120.0, 1.0 / 30, "conexiones en vivo"),

        /** Rutas públicas de sesión (canje del enlace, renovar, salir), por IP. */
        AUTH(300.0, 2.0, "inicios de sesión"),
    }

    private val subida = Regex("^/(me/avatar|trip/[^/]+/photo|chats/[^/]+/(media|image))$")
    private val mensaje = Regex("^/chats/[^/]+/(messages|media)$")
    private val reporte = Regex("^/(chats/[^/]+/messages/[^/]+|chats/[^/]+|officers/[^/]+)/report$")
    private val miembro = Regex("^/chats/[^/]+/members$")
    private val registro = Regex("^/events/[^/]+/participation$")
    private val perfil = Regex("^/officers/[^/]+(/achievements|/common-events)?$")
    private val stream = Regex("^/(stream|chats/stream|events/[^/]+/stream)$")
    private val ubicacionConfig = Regex("^/me/(location-sharing|location-shares/[^/]+|location-hidden/[^/]+)$")
    private val bloqueo = Regex("^/me/blocks/[^/]+$")

    /** Clases que consume una petición (la de ritmo y, si aplica, su tope diario). */
    fun classifyAll(method: HttpMethod, path: String): List<Clase> {
        val base = classify(method, path)
        return when (base) {
            Clase.MENSAJE -> if (path.endsWith("/media")) listOf(base, Clase.SUBIDA, Clase.SUBIDA_DIA, Clase.MENSAJE_DIA) else listOf(base, Clase.MENSAJE_DIA)
            Clase.SUBIDA -> listOf(base, Clase.SUBIDA_DIA)
            else -> listOf(base)
        }
    }

    fun classify(method: HttpMethod, path: String): Clase = when {
        method == HttpMethod.Get -> when {
            path.startsWith("/images/") -> Clase.IMAGEN
            path == "/officers/search" -> Clase.BUSQUEDA
            path == "/me/export" -> Clase.EXPORTACION
            stream.matches(path) -> Clase.STREAM
            perfil.matches(path) -> Clase.PERFIL
            else -> Clase.LECTURA
        }
        // Boleto del WebSocket de la web: cuenta como abrir una conexión en vivo (como el
        // GET /stream de la app Android).
        method == HttpMethod.Post && path == "/stream/ticket" -> Clase.STREAM
        method == HttpMethod.Post && mensaje.matches(path) -> Clase.MENSAJE
        method == HttpMethod.Post && subida.matches(path) -> Clase.SUBIDA
        method == HttpMethod.Post && reporte.matches(path) -> Clase.REPORTE
        method == HttpMethod.Post && miembro.matches(path) -> Clase.MIEMBRO
        method == HttpMethod.Post && path == "/chats" -> Clase.CHAT_NUEVO
        method == HttpMethod.Post && path == "/invitations" -> Clase.INVITACION
        method == HttpMethod.Post && path == "/me/location" -> Clase.UBICACION
        ubicacionConfig.matches(path) -> Clase.UBICACION_CONFIG
        bloqueo.matches(path) -> Clase.BLOQUEO
        registro.matches(path) -> Clase.REGISTRO
        else -> Clase.ESCRITURA
    }

    private class Cubeta(var fichas: Double, var ultima: Long)

    private val cubetas = ConcurrentHashMap<String, Cubeta>()

    /** Consume una ficha de [quien] en [clase]; si no hay, lanza 429 con Retry-After. */
    fun check(quien: String, clase: Clase, now: Long = System.currentTimeMillis()) {
        // Congelado por chocar una y otra vez con los límites (Operations.kt).
        Freezer.check(quien, now)
        val escala = Config.rateLimitScale
        if (escala <= 0.0) return
        val capacidad = maxOf(1.0, clase.capacidad * escala)
        val ritmo = clase.porSegundo * escala
        val c = cubetas.computeIfAbsent("$quien|${clase.name}") { Cubeta(capacidad, now) }
        val espera: Long? = synchronized(c) {
            c.fichas = min(capacidad, c.fichas + (now - c.ultima) / 1000.0 * ritmo)
            c.ultima = now
            if (c.fichas >= 1.0) {
                c.fichas -= 1.0
                null
            } else {
                ceil((1.0 - c.fichas) / ritmo).toLong().coerceAtLeast(1)
            }
        }
        if (cubetas.size > 100_000) purge(now)
        if (espera != null) {
            SecurityMonitor.limited(quien, clase)
            Freezer.strike(quien, now)
            throw Rejected(HttpStatusCode.TooManyRequests, "demasiadas solicitudes (${clase.etiqueta}); intenta en $espera s", espera)
        }
    }

    /**
     * Cota de memoria: suelta las cubetas que ya se habrían rellenado solas (soltar antes
     * regalaría fichas: un tope DIARIO se saltaría esperando una hora).
     */
    private fun purge(now: Long) {
        cubetas.entries.removeIf { (key, c) ->
            val clase = runCatching { Clase.valueOf(key.substringAfterLast('|')) }.getOrNull() ?: return@removeIf true
            // Tiempo en rellenarse de vacía a llena (la escala multiplica ambos: no cambia).
            val llenaEnMs = (clase.capacidad / clase.porSegundo * 1000).toLong()
            now - c.ultima > maxOf(llenaEnMs, 60 * 60_000L)
        }
    }

    /** Solo pruebas. */
    internal fun clear() = cubetas.clear()
}

/**
 * Tope del cuerpo de cada petición según la ruta, revisado ANTES de leerlo (por
 * Content-Length): el backend lee los cuerpos completos en memoria. Caddy además corta
 * todo lo que pase de 20 MB (incluso sin Content-Length).
 */
object BodyLimits {
    const val SUBIDA = 15L * 1024 * 1024
    const val ADMIN = 4L * 1024 * 1024
    const val NORMAL = 256L * 1024

    private val subidas = Regex("^/(me/avatar|trip/[^/]+/photo|chats/[^/]+/(media|image)|admin/images/.+)$")

    fun maxFor(path: String): Long = when {
        subidas.matches(path) -> SUBIDA
        path.startsWith("/admin/") -> ADMIN
        else -> NORMAL
    }

    fun install(app: io.ktor.server.application.Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val len = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (len == null) {
                // Cuerpo "chunked" sin tamaño declarado: se saltaría este tope (el backend
                // lee el cuerpo completo en memoria). Nuestros clientes siempre declaran el
                // tamaño; quien no lo haga recibe 411.
                if (call.request.header(HttpHeaders.TransferEncoding) != null) {
                    throw Rejected(HttpStatusCode.LengthRequired, "declara el tamaño del cuerpo (Content-Length)")
                }
                return@intercept
            }
            val max = maxFor(call.request.path())
            if (len > max) {
                throw Rejected(HttpStatusCode.PayloadTooLarge, "el cuerpo pesa más de ${max / 1024} KB")
            }
        }
    }
}

/** Topes de texto de lo que los oficiales escriben (holgados: frenan abuso, no el uso normal). */
object TextLimits {
    // Nunca más que la columna donde se guardan (antes algunos pasaban y tronaban con 500).
    const val NOMBRE = 80
    const val MENSAJE = 2000
    const val NOTA = 500
    const val TITULO = 200
    const val PIE = 1000
    const val MOTIVO = 300
    const val DESCRIPCION = 300
    const val CORREO = 254
    const val BUSQUEDA = 80
    const val EMERGENCIA = 300
    const val TELEFONO = 40
    const val ETIQUETA = 40
    const val MAX_INVITADOS = 50
    const val MAX_DIAS = 31
}

/** 400 si [valor] pasa de [max] caracteres (el handler no sigue). */
fun limite(campo: String, valor: String?, max: Int) {
    if (valor != null && valor.length > max) {
        throw Rejected(HttpStatusCode.BadRequest, "$campo: máximo $max caracteres")
    }
}

/**
 * Detecta picos (429, 401, 403, 5xx) y eventos de seguridad (reuso de refresh token) y
 * avisa por correo a `ALERT_EMAIL` — como mucho una vez por hora por tipo. Todo en
 * memoria: sin costo de CloudWatch. Sin ALERT_EMAIL solo queda en el log.
 */
object SecurityMonitor {
    private val log = LoggerFactory.getLogger("Seguridad")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val VENTANA_MS = 5 * 60_000L
    private const val UNA_HORA_MS = 60 * 60_000L

    /** Respuestas por ventana de 5 min que disparan el aviso. */
    private val umbrales = mapOf("429" to 50, "401" to 500, "403" to 100, "5xx" to 20)

    private var inicio = System.currentTimeMillis()
    private val conteos = HashMap<String, Int>()
    private val quienes = HashMap<String, HashMap<String, Int>>()
    private val ultimaAlerta = ConcurrentHashMap<String, Long>()

    fun record(status: Int, quien: String) {
        val tipo = when {
            status == 429 -> "429"
            status == 401 -> "401"
            status == 403 -> "403"
            status >= 500 -> "5xx"
            else -> return
        }
        val top: List<String>? = synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - inicio > VENTANA_MS) {
                inicio = now; conteos.clear(); quienes.clear()
            }
            val n = (conteos[tipo] ?: 0) + 1
            conteos[tipo] = n
            val q = quienes.getOrPut(tipo) { HashMap() }
            if (q.size < 1000 || quien in q) q[quien] = (q[quien] ?: 0) + 1
            if (n == umbrales.getValue(tipo)) q.entries.sortedByDescending { it.value }.take(5).map { "${it.key}: ${it.value}" } else null
        }
        if (top != null) {
            alert(
                "pico-$tipo", "Pico de respuestas $tipo",
                listOf("${umbrales[tipo]} respuestas $tipo en menos de 5 minutos.", "Quién generó más:") + top,
            )
        }
    }

    /** Un oficial (o IP) chocó con un límite: solo se registra; el pico de 429 avisa. */
    fun limited(quien: String, clase: RateLimits.Clase) {
        log.info("límite {} alcanzado por {}", clase.name, quien)
    }

    fun alert(tipo: String, titulo: String, lineas: List<String>) {
        val now = System.currentTimeMillis()
        val previa = ultimaAlerta[tipo]
        if (previa != null && now - previa < UNA_HORA_MS) {
            log.warn("ALERTA {} (sin correo: ya se avisó hace menos de 1 h): {}", tipo, lineas.joinToString(" | "))
            return
        }
        ultimaAlerta[tipo] = now
        log.warn("ALERTA {}: {} — {}", tipo, titulo, lineas.joinToString(" | "))
        val to = Config.alertEmail ?: return
        scope.launch {
            val legibles = lineas.map(::describir)
            if (!EmailSender.sendSecurityAlert(to, titulo, legibles)) log.error("no se pudo enviar la alerta {} a {}", tipo, to)
        }
    }

    /** "oficial:<id>: 12" → "Nombre (oficial <id>): 12" para el correo. */
    private fun describir(linea: String): String {
        if (!linea.startsWith("oficial:")) return linea
        val id = linea.removePrefix("oficial:").substringBefore(':')
        val nombre = runCatching { DomainRepository.officer(id)?.displayName }.getOrNull() ?: return linea
        return "$nombre (oficial $id)" + linea.removePrefix("oficial:$id")
    }
}

private val InicioKey = AttributeKey<Long>("inicio-peticion")

/**
 * Una línea por petición (método, ruta sin query, status, latencia y quién): el oficial
 * si hay sesión; la IP solo en rutas sin sesión. La query nunca se registra (la página
 * puente y el stream del admin llevan tokens ahí). Alimenta a [SecurityMonitor].
 */
val RegistroDePeticiones = createApplicationPlugin("RegistroDePeticiones") {
    val log = LoggerFactory.getLogger("Peticiones")
    on(CallSetup) { call -> call.attributes.put(InicioKey, System.nanoTime()) }
    on(ResponseSent) { call ->
        val path = call.request.path()
        if (path == "/health") return@on
        val status = call.response.status()?.value ?: 0
        val ms = call.attributes.getOrNull(InicioKey)?.let { (System.nanoTime() - it) / 1_000_000 } ?: -1
        val officer = call.principal<JWTPrincipal>()?.subject
        val quien = if (officer != null) "oficial:$officer" else "ip:${call.clientIp()}"
        log.info("{} {} {} {}ms {}", call.request.httpMethod.value, path, status, ms, quien)
        SecurityMonitor.record(status, quien)
        Usage.record(quien, status)
    }
}
