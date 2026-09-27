package com.alephri.elpuesto.backend

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Herramientas de operación ante un abuso o un incidente, sin redesplegar:
 * - [FeatureSwitches]: pausar una función completa (subidas, mensajes, chats…).
 * - [Freezer]: quien choca una y otra vez con los límites queda congelado 30 min.
 * - [Usage]: quién hace más peticiones y quién choca con los límites (últimas 24 h).
 * Se operan desde la API admin (`/admin/security/…`, scope `keys`) y el admin web.
 */

// —— Pausas por función ——

/** Funciones que el admin puede pausar (la app recibe 429 con el motivo y reintenta después). */
enum class Pausa(val descripcion: String) {
    ENLACES("Pedir enlaces de acceso (nadie nuevo puede entrar)"),
    SUBIDAS("Subir fotos (perfil, bitácora, chat e imagen de chat)"),
    MENSAJES("Mandar mensajes de chat"),
    CHATS("Crear chats"),
    INVITACIONES("Invitar oficiales (a la app y a chats)"),
    UBICACION("Compartir ubicación"),
    REGISTROS("Registros por honor y puestos propuestos"),
    EXPORTACIONES("Descargar mis datos"),
}

object FeatureSwitchesT : Table("feature_switches") {
    val key = varchar("key", 32)
    val reason = varchar("reason", 300).nullable()
    val since = varchar("since", 40) // ISO Instant
    val by = varchar("by_actor", 120)
    override val primaryKey = PrimaryKey(key)
}

@Serializable
data class FeatureSwitchInfo(val key: String, val description: String, val paused: Boolean, val reason: String? = null, val since: String? = null, val by: String? = null)

@Serializable
data class SetSwitchRequest(val paused: Boolean, val reason: String? = null)

object FeatureSwitches {
    /** Pausadas ahora (fila presente = pausada). Caché corta: se consulta en cada petición. */
    @Volatile private var cache: Map<Pausa, ResultRowInfo> = emptyMap()
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 10_000L

    data class ResultRowInfo(val reason: String?, val since: String, val by: String)

    fun init() = transaction { SchemaUtils.createMissingTablesAndColumns(FeatureSwitchesT) }

    private fun paused(): Map<Pausa, ResultRowInfo> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TTL_MS) return cache
        cache = transaction {
            FeatureSwitchesT.selectAll().mapNotNull { r ->
                runCatching { Pausa.valueOf(r[FeatureSwitchesT.key]) }.getOrNull()
                    ?.let { it to ResultRowInfo(r[FeatureSwitchesT.reason], r[FeatureSwitchesT.since], r[FeatureSwitchesT.by]) }
            }.toMap()
        }
        cachedAt = now
        return cache
    }

    fun list(): List<FeatureSwitchInfo> {
        val p = paused()
        return Pausa.entries.map { k -> p[k].let { FeatureSwitchInfo(k.name, k.descripcion, it != null, it?.reason, it?.since, it?.by) } }
    }

    fun set(key: Pausa, paused: Boolean, reason: String?, actor: String) {
        transaction {
            FeatureSwitchesT.deleteWhere { FeatureSwitchesT.key eq key.name }
            if (paused) {
                FeatureSwitchesT.insert {
                    it[FeatureSwitchesT.key] = key.name; it[FeatureSwitchesT.reason] = reason?.take(300)
                    it[since] = Instant.now().toString(); it[by] = actor.take(120)
                }
            }
        }
        cachedAt = 0 // se aplica al momento en este proceso
    }

    /** Si [key] está pausada, corta la petición (429: la app conserva lo que tenga en cola). */
    fun check(key: Pausa) {
        val p = paused()[key] ?: return
        val motivo = p.reason?.let { ": $it" } ?: ""
        throw Rejected(HttpStatusCode.TooManyRequests, "Pausado por el administrador ($key)$motivo. Intenta más tarde.", 600)
    }

    private val subida = Regex("^/(me/avatar|trip/[^/]+/photo|chats/[^/]+/(media|image))$")
    private val mensaje = Regex("^/chats/[^/]+/(messages|media)$")
    private val miembro = Regex("^/chats/[^/]+/members$")
    private val registro = Regex("^/events/[^/]+/participation$")
    private val ubicacion = Regex("^/me/(location|location-shares/[^/]+)$")

    /** Pausas que aplican a una petición de la app (una foto de chat es subida Y mensaje). */
    fun keysFor(method: HttpMethod, path: String): List<Pausa> = buildList {
        if (method == HttpMethod.Get) {
            if (path == "/me/export") add(Pausa.EXPORTACIONES)
            return@buildList
        }
        if (method == HttpMethod.Post && subida.matches(path)) add(Pausa.SUBIDAS)
        if (method == HttpMethod.Post && mensaje.matches(path)) add(Pausa.MENSAJES)
        if (method == HttpMethod.Post && path == "/chats") add(Pausa.CHATS)
        if (method == HttpMethod.Post && (path == "/invitations" || miembro.matches(path))) add(Pausa.INVITACIONES)
        if (method == HttpMethod.Post && ubicacion.matches(path)) add(Pausa.UBICACION)
        if (method == HttpMethod.Put && registro.matches(path)) add(Pausa.REGISTROS)
    }
}

// —— Congelamiento automático ——

/**
 * Quien choca con los límites de uso una y otra vez (ignora el Retry-After: un script, no
 * un oficial) queda congelado [FREEZE_MS]: todas sus peticiones reciben 429. Se avisa por
 * correo al admin ([SecurityMonitor]) y el admin puede descongelar antes.
 */
object Freezer {
    private const val VENTANA_MS = 10 * 60_000L
    private const val STRIKES = 60
    private const val FREEZE_MS = 30 * 60_000L

    private class Strikes(var start: Long, var count: Int)

    private val strikes = ConcurrentHashMap<String, Strikes>()
    private val frozen = ConcurrentHashMap<String, Long>()

    /** Lanza 429 si [quien] está congelado. */
    fun check(quien: String, now: Long = System.currentTimeMillis()) {
        val until = frozen[quien] ?: return
        if (until <= now) {
            frozen.remove(quien); return
        }
        throw Rejected(
            HttpStatusCode.TooManyRequests,
            "demasiadas solicitudes: acceso pausado unos minutos",
            ((until - now) / 1000).coerceAtLeast(1),
        )
    }

    /** Un rechazo por límite de [quien]; al llegar a [STRIKES] en la ventana, lo congela. */
    fun strike(quien: String, now: Long = System.currentTimeMillis()) {
        val s = strikes.compute(quien) { _, old ->
            if (old == null || now - old.start > VENTANA_MS) Strikes(now, 1) else old.also { it.count++ }
        }!!
        if (s.count >= STRIKES && frozen.putIfAbsent(quien, now + FREEZE_MS) == null) {
            strikes.remove(quien)
            SecurityMonitor.alert(
                "congelado-$quien", "Acceso congelado 30 min",
                listOf("$quien chocó $STRIKES veces con los límites de uso en menos de 10 minutos.",
                    "Quedó congelado 30 min (todas sus peticiones reciben 429). Descongelar: admin web → Seguridad."),
            )
        }
        if (strikes.size > 50_000) strikes.entries.removeIf { now - it.value.start > VENTANA_MS }
    }

    fun list(now: Long = System.currentTimeMillis()): Map<String, Long> = frozen.filterValues { it > now }

    fun unfreeze(quien: String): Boolean = frozen.remove(quien) != null
}

// —— Uso ——

/** Peticiones y rechazos (429) por quien, en casillas por hora de las últimas 24 h (memoria). */
object Usage {
    class Counts { @Volatile var requests = 0; @Volatile var rejected = 0 }

    private class Hour(val epochHour: Long, val counts: ConcurrentHashMap<String, Counts> = ConcurrentHashMap())

    private val hours = arrayOfNulls<Hour>(24)

    fun record(quien: String, status: Int, now: Long = System.currentTimeMillis()) {
        val eh = now / 3_600_000L
        val idx = (eh % 24).toInt()
        val h = hours[idx]?.takeIf { it.epochHour == eh } ?: synchronized(hours) {
            hours[idx]?.takeIf { it.epochHour == eh } ?: Hour(eh).also { hours[idx] = it }
        }
        if (h.counts.size > 20_000 && !h.counts.containsKey(quien)) return // cota de memoria (barridos de IPs)
        val c = h.counts.computeIfAbsent(quien) { Counts() }
        synchronized(c) {
            c.requests++
            if (status == 429) c.rejected++
        }
    }

    /** Totales de las últimas [horas] horas por quien. */
    fun totals(horas: Int, now: Long = System.currentTimeMillis()): Map<String, Pair<Int, Int>> {
        val eh = now / 3_600_000L
        val out = HashMap<String, Pair<Int, Int>>()
        hours.filterNotNull().filter { eh - it.epochHour < horas.coerceIn(1, 24) }.forEach { h ->
            h.counts.forEach { (q, c) ->
                val (r, x) = out[q] ?: (0 to 0)
                out[q] = (r + c.requests) to (x + c.rejected)
            }
        }
        return out
    }
}

@Serializable
data class UsageRow(val who: String, val name: String? = null, val requests: Int, val rejected: Int, val frozenUntil: String? = null)

@Serializable
data class FrozenRow(val who: String, val name: String? = null, val until: String)

/** "oficial:<id>" → nombre del oficial (para leer la tabla sin buscar ids). */
private fun nameOf(who: String): String? =
    if (!who.startsWith("oficial:")) null else runCatching { DomainRepository.officer(who.removePrefix("oficial:"))?.displayName }.getOrNull()

/** Rutas `/admin/security/…` (scope `keys`: operación de seguridad, no para agentes). */
fun Route.adminSecurityRoutes() = route("/security") {
    get("/switches") {
        call.requireAdmin("keys") ?: return@get
        call.respond(FeatureSwitches.list())
    }
    put("/switches/{key}") {
        val actor = call.requireAdmin("keys") ?: return@put
        val key = runCatching { Pausa.valueOf(call.parameters["key"]!!.uppercase()) }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, AdminError("pausa desconocida", field = "key", hint = Pausa.entries.joinToString { it.name }))
        val req = call.receive<SetSwitchRequest>()
        if (call.isDryRun) return@put call.finishDryRun("switch", key.name, if (req.paused) "se pausaría" else "se reanudaría")
        FeatureSwitches.set(key, req.paused, req.reason?.trim()?.ifBlank { null }, actor.name)
        AdminAudit.record(actor, if (req.paused) "pause" else "resume", "switch", key.name, dryRun = false, detail = req.reason)
        call.respond(ChangeSummary("updated", "switch", key.name, detail = if (req.paused) "pausada" else "reanudada"))
    }
    get("/usage") {
        call.requireAdmin("keys") ?: return@get
        val horas = call.request.queryParameters["hours"]?.toIntOrNull() ?: 24
        val frozen = Freezer.list()
        call.respond(
            Usage.totals(horas).entries
                .sortedWith(compareByDescending<Map.Entry<String, Pair<Int, Int>>> { it.value.second }.thenByDescending { it.value.first })
                .take(50)
                .map { (who, c) -> UsageRow(who, nameOf(who), c.first, c.second, frozen[who]?.let { Instant.ofEpochMilli(it).toString() }) },
        )
    }
    get("/frozen") {
        call.requireAdmin("keys") ?: return@get
        call.respond(Freezer.list().map { (who, until) -> FrozenRow(who, nameOf(who), Instant.ofEpochMilli(until).toString()) })
    }
    delete("/frozen/{who}") {
        val actor = call.requireAdmin("keys") ?: return@delete
        val who = call.parameters["who"]!!
        if (!Freezer.unfreeze(who)) return@delete call.respond(HttpStatusCode.NotFound, AdminError("'$who' no está congelado"))
        AdminAudit.record(actor, "unfreeze", "security", who, dryRun = false)
        call.respond(ChangeSummary("updated", "security", who, detail = "descongelado"))
    }
}
