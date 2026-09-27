package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.Category
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * API administrativa (JSON): la consumen agentes de AI (vía el módulo MCP), el admin web
 * y curl. Diseño pensado para agentes: los IDs los asigna SIEMPRE el servidor (UUIDv7;
 * crear = POST a la colección sin id, la respuesta trae el id; PUT /{id} solo actualiza),
 * bulk para datos que llegan en tabla (standings, puestos…), `?dryRun=true` para validar
 * sin escribir, errores accionables y auditoría de toda mutación con el nombre del actor.
 */

@Serializable
data class AdminAccountInfo(
    val email: String,
    val officerId: String? = null,
    val status: String,
    val invitedBy: String? = null,
    /** Sesiones vivas (refresh tokens sin revocar ni vencer): teléfonos con la sesión abierta. */
    val activeSessions: Int = 0,
)

/** Evento como lo ve el admin: incluye el flag `active` (fuera del modelo compartido). */
@Serializable
data class AdminEventInfo(
    val id: String,
    val name: String,
    val startsOn: String,
    val endsOn: String,
    val circuitId: String,
    val trazadoId: String,
    val trazadoIds: List<String> = emptyList(),
    val championshipIds: List<String> = emptyList(),
    val status: String,
    val active: Boolean,
    /** Autoregistro por honor permitido (default true). */
    val selfRegistration: Boolean = true,
    /** Participaciones declaradas por los oficiales (registro por honor). */
    val declaredCount: Int = 0,
)

@Serializable
data class AccountUpsertRequest(val officerId: String? = null, val status: AccountStatus)

/**
 * Tokens efímeros de un solo uso para el stream SSE: EventSource no puede mandar el
 * header X-Admin-Key, así que la página pide un token por POST (autenticado) y lo pasa
 * como query param al conectar. 60s de vida, se consume al usarse.
 */
object StreamTokens {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun mint(): String {
        tokens.entries.removeIf { it.value < System.currentTimeMillis() }
        val t = uuidv7() + uuidv7()
        tokens[t] = System.currentTimeMillis() + 60_000
        return t
    }
    fun consume(t: String): Boolean {
        val exp = tokens.remove(t) ?: return false
        return exp >= System.currentTimeMillis()
    }
}

@Serializable
data class StreamTokenResponse(val token: String)

/** Chat del evento como lo ve el admin. */
@Serializable
data class AdminEventChat(val chatId: String? = null, val messages: List<com.alephri.elpuesto.model.Message> = emptyList())

@Serializable
data class ControlMessageRequest(val text: String)

/** Estado de un ítem del checklist en UN puesto (visor del admin: quién y cuándo). */
@Serializable
data class ChecklistPuestoState(
    val itemId: String,
    val puestoId: String,
    val done: Boolean,
    val markedBy: String? = null,
    val markedAt: String? = null,
)

private suspend fun ApplicationCall.fail(error: String, field: String? = null, hint: String? = null) =
    respond(HttpStatusCode.BadRequest, AdminError(error, field, hint))

private val adminJson = Json { ignoreUnknownKeys = true }

/**
 * Recibe la entidad del body tomando el `id` del path (el body puede omitirlo; si trae
 * otro distinto es error). null = ya se respondió el 400.
 */
private suspend inline fun <reified T> ApplicationCall.receiveEntity(): T? {
    val id = parameters["id"] ?: parameters["email"]!!
    val obj = receive<JsonObject>()
    val bodyId = obj["id"]?.jsonPrimitive?.contentOrNull
    if (!bodyId.isNullOrBlank() && bodyId != id) {
        fail(
            "el id del body ('$bodyId') no coincide con el del path ('$id')",
            field = "id", hint = "omite el id en el body o usa el mismo del path",
        )
        return null
    }
    return try {
        adminJson.decodeFromJsonElement<T>(JsonObject(obj + ("id" to JsonPrimitive(id))))
    } catch (e: SerializationException) {
        fail("cuerpo inválido: ${e.message}", hint = "revisa tipos, valores de enums y fechas/horas en ISO-8601")
        null
    }
}

/**
 * Recibe la entidad de un POST de creación: el body NO debe traer id (lo asigna el
 * servidor, UUIDv7) y se devuelve ya inyectado. null = ya se respondió el 400.
 */
private suspend inline fun <reified T> ApplicationCall.receiveCreate(): T? {
    val obj = receive<JsonObject>()
    val bodyId = obj["id"]?.jsonPrimitive?.contentOrNull
    if (!bodyId.isNullOrBlank()) {
        fail(
            "el id lo asigna el servidor; no lo mandes al crear",
            field = "id", hint = "haz el POST sin id y toma el id (UUID) de la respuesta",
        )
        return null
    }
    return try {
        adminJson.decodeFromJsonElement<T>(JsonObject(obj + ("id" to JsonPrimitive(uuidv7()))))
    } catch (e: SerializationException) {
        fail("cuerpo inválido: ${e.message}", hint = "revisa tipos, valores de enums y fechas/horas en ISO-8601")
        null
    }
}

/** Los PUT solo actualizan: si la entidad no existe responde 404 apuntando al POST. */
private suspend fun ApplicationCall.requireExisting(entity: String, id: String, collection: String): Boolean {
    if (AdminRepository.entityExists(entity, id)) return true
    respond(
        HttpStatusCode.NotFound,
        AdminError(
            "$entity '$id' no existe", field = "id",
            hint = "los ids los asigna el servidor; para crear usa POST /admin/$collection (sin id)",
        ),
    )
    return false
}

/**
 * Recibe una lista bulk rellenando en cada elemento los campos que el path ya define
 * (p. ej. `eventId`) y los generables (`id`: "" = generar). El body puede overridearlos.
 */
private suspend inline fun <reified T> ApplicationCall.receiveList(defaults: Map<String, String>): List<T>? {
    val arr = receive<JsonArray>()
    return try {
        val filled = JsonArray(arr.map { el ->
            JsonObject(defaults.mapValues { JsonPrimitive(it.value) } + el.jsonObject)
        })
        adminJson.decodeFromJsonElement<List<T>>(filled)
    } catch (e: SerializationException) {
        fail("cuerpo inválido: ${e.message}", hint = "revisa tipos, valores de enums y fechas/horas en ISO-8601")
        null
    }
}

/**
 * Cierra una mutación: si el repo la rechazó (action="none") responde 400 con el motivo;
 * si aplicó, la audita con el nombre del actor y responde el resumen.
 */
private suspend fun ApplicationCall.finish(actor: AdminActor, s: ChangeSummary) {
    if (s.action == "none") {
        fail(s.detail ?: "operación no aplicada", hint = "corrige la referencia o el id y reintenta")
    } else {
        AdminAudit.record(actor, s.action, s.entity, s.id, dryRun = false, detail = s.detail)
        respond(s)
    }
}

/** Respuesta de un dryRun: validado, sin tocar la base. */
internal suspend fun ApplicationCall.finishDryRun(entity: String, id: String?, detail: String? = null) =
    respond(ChangeSummary("validated", entity, id, dryRun = true, detail = detail ?: "válido; sin cambios aplicados"))

// —— Validaciones por entidad (compartidas por el POST de crear y el PUT de actualizar) ——

private suspend fun ApplicationCall.validOfficer(o: Officer): Boolean {
    if (o.displayName.isBlank()) { fail("displayName requerido", field = "displayName"); return false }
    if (o.omdaiId <= 0) { fail("omdaiId debe ser positivo", field = "omdaiId"); return false }
    return true
}

private suspend fun ApplicationCall.validEvent(e: Event): Boolean {
    if (e.name.isBlank()) { fail("name requerido", field = "name"); return false }
    if (e.trazadoIds.isEmpty() && e.trazadoId.isBlank()) {
        fail(
            "trazadoIds requerido (al menos un trazado)", field = "trazadoIds",
            hint = "lista de ids de trazados del circuito; el primero es el principal",
        )
        return false
    }
    if (e.endsOn < e.startsOn) {
        fail("endsOn (${e.endsOn}) es anterior a startsOn (${e.startsOn})", field = "endsOn")
        return false
    }
    return true
}

private suspend fun ApplicationCall.validCircuit(c: Circuit): Boolean {
    if (c.name.isBlank()) { fail("name requerido", field = "name"); return false }
    return true
}

private val validDirections = setOf("Horario", "Antihorario")

private suspend fun ApplicationCall.validTrazado(t: Trazado): Boolean {
    if (t.name.isBlank()) { fail("name requerido", field = "name"); return false }
    if (t.lengthM <= 0) { fail("lengthM debe ser positivo (metros, entero)", field = "lengthM"); return false }
    if (t.curves <= 0) { fail("curves debe ser un entero positivo", field = "curves"); return false }
    if (t.direction !in validDirections) {
        fail("direction '${t.direction}' inválido", field = "direction", hint = "opciones: ${validDirections.joinToString()}")
        return false
    }
    return true
}

private suspend fun ApplicationCall.validSeries(s: com.alephri.elpuesto.model.Series, id: String?): Boolean {
    if (s.name.isBlank()) { fail("name requerido", field = "name"); return false }
    if (AdminRepository.seriesNameTaken(s.name, id)) {
        fail("ya existe un campeonato '${s.name.trim()}'", field = "name", hint = "cada campeonato se registra una vez; sus años van como temporadas")
        return false
    }
    return true
}

/** Temporada: de un campeonato existente, con etiqueta única dentro de él y año derivable. */
private suspend fun ApplicationCall.validChampionship(c: Championship, id: String?): Boolean {
    if (c.seriesId.isBlank() || !AdminRepository.entityExists("series", c.seriesId)) {
        fail("seriesId requerido: el campeonato de la temporada", field = "seriesId", hint = "GET /admin/series lista los campeonatos; créalo con POST /admin/series")
        return false
    }
    if (c.seasonLabel.isBlank()) {
        fail("seasonLabel requerido", field = "seasonLabel", hint = "etiqueta de la temporada: \"2026\" o, si cruza el año, \"2025-26\"")
        return false
    }
    if (c.season <= 0 && AdminRepository.seasonYear(c.seasonLabel) == null) {
        fail("no se puede derivar el año de '${c.seasonLabel}'", field = "season", hint = "usa \"2026\"/\"2025-26\" o manda season (año para ordenar)")
        return false
    }
    if (AdminRepository.seasonLabelTaken(c.seriesId, c.seasonLabel, id)) {
        fail("el campeonato ya tiene la temporada '${c.seasonLabel.trim()}'", field = "seasonLabel")
        return false
    }
    return true
}

private suspend fun ApplicationCall.validCategory(c: Category): Boolean {
    if (c.name.isBlank()) { fail("name requerido", field = "name"); return false }
    return true
}

private suspend fun ApplicationCall.validConvocatoria(c: Convocatoria): Boolean {
    if (c.eventName.isBlank()) { fail("eventName requerido", field = "eventName"); return false }
    if (c.cupo < 0) { fail("cupo no puede ser negativo", field = "cupo"); return false }
    val circuit = c.circuitId
    if (circuit != null && !AdminRepository.entityExists("circuit", circuit)) {
        fail(
            "circuitId no existe en el catálogo", field = "circuitId",
            hint = "usa un id de GET /admin/circuits, u omite el campo (sede solo como texto)",
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.validAgenda(e: AgendaEntry): Boolean {
    if (e.title.isBlank()) { fail("title requerido", field = "title"); return false }
    val conv = e.convocatoriaId
    if (conv != null && !AdminRepository.entityExists("convocatoria", conv)) {
        fail(
            "convocatoriaId no existe", field = "convocatoriaId",
            hint = "usa un id de GET /admin/convocatorias, u omite el campo",
        )
        return false
    }
    return true
}

fun Route.adminRoutes() = route("/admin") {

    // —— Operación de seguridad: pausas por función, uso y congelados (Operations.kt) ——
    adminSecurityRoutes()

    // —— Descubrimiento ——
    get("/docs") {
        call.adminActorOrFail() ?: return@get
        val md = object {}.javaClass.getResource("/admin-api.md")?.readText()
            ?: return@get call.respond(HttpStatusCode.NotFound, AdminError("documentación no empaquetada"))
        call.respondText(md, ContentType.parse("text/markdown; charset=utf-8"))
    }
    // Spec OpenAPI 3 formal (para clientes/tooling; /docs sigue siendo la guía narrativa).
    get("/openapi.json") {
        call.adminActorOrFail() ?: return@get
        val spec = object {}.javaClass.getResource("/admin-openapi.json")?.readText()
            ?: return@get call.respond(HttpStatusCode.NotFound, AdminError("spec no empaquetado"))
        call.respondText(spec, ContentType.Application.Json)
    }
    get("/whoami") {
        val actor = call.adminActorOrFail() ?: return@get
        call.respond(WhoAmI(actor.name, actor.scopes.toList()))
    }

    // —— Gestión de claves (scope: keys) ——
    route("/keys") {
        get {
            call.requireAdmin("keys") ?: return@get
            call.respond(AdminAuth.listKeys())
        }
        post {
            val actor = call.requireAdmin("keys") ?: return@post
            val req = call.receive<CreateKeyRequest>()
            if (req.name.isBlank()) return@post call.fail("nombre requerido", field = "name")
            val bad = req.scopes.filter { it != AdminScopes.ALL && it !in AdminScopes.KNOWN }
            if (bad.isNotEmpty()) {
                return@post call.fail(
                    "scopes desconocidos: ${bad.joinToString()}", field = "scopes",
                    hint = "válidos: ${AdminScopes.KNOWN.sorted().joinToString()} o *",
                )
            }
            if (req.scopes.isEmpty()) return@post call.fail("al menos un scope", field = "scopes")
            val created = AdminAuth.createKey(req.name.trim(), req.scopes.toSet())
            AdminAudit.record(actor, "create-key", "admin-key", created.id, dryRun = false, detail = "scopes: ${created.scopes.joinToString()}")
            call.respond(created)
        }
        delete("/{id}") {
            val actor = call.requireAdmin("keys") ?: return@delete
            val id = call.parameters["id"]!!
            if (AdminAuth.revokeKey(id)) {
                AdminAudit.record(actor, "revoke-key", "admin-key", id, dryRun = false)
                call.respond(ChangeSummary("deleted", "admin-key", id))
            } else call.fail("clave '$id' no existe", hint = "GET /admin/keys lista las claves")
        }
    }

    // —— Auditoría (scope: keys) ——
    get("/audit") {
        call.requireAdmin("keys") ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        call.respond(AdminAudit.list(limit, call.request.queryParameters["entity"]))
    }

    // —— Botón de emergencia (scope: keys): cierra las sesiones de TODAS las cuentas ——
    // Para una fuga (p. ej. se filtró JWT_SECRET o una base de tokens): nadie usa la API
    // hasta volver a entrar con su correo. Los access tokens emitidos dejan de valer al
    // momento; los sockets se cierran en ≤ 30 s.
    post("/sessions/revoke-all") {
        val actor = call.requireAdmin("keys") ?: return@post
        val vivas = activeSessionCounts().values.sum()
        if (call.isDryRun) return@post call.finishDryRun("sessions", null, "cerraría $vivas sesiones de todas las cuentas")
        val cuentas = revokeAllSessions()
        AdminAudit.record(actor, "revoke-all", "sessions", null, dryRun = false, detail = "$vivas sesiones de $cuentas cuentas")
        call.respond(ChangeSummary("updated", "sessions", null, detail = "$vivas sesiones cerradas en $cuentas cuentas; todos deben volver a entrar con su correo"))
    }

    // —— Cuentas (scope: accounts) ——
    route("/accounts") {
        get {
            call.requireAdmin("accounts") ?: return@get
            val status = call.request.queryParameters["status"]?.uppercase()
            val inviters = invitersByEmail()
            val sessions = activeSessionCounts()
            call.respond(AdminRepository.listAccounts(status).map { acc ->
                val inviterName = inviters[acc.email]?.let { DomainRepository.officer(it)?.displayName }
                AdminAccountInfo(acc.email, acc.officerId, acc.status.name, inviterName, sessions[acc.email] ?: 0)
            })
        }
        put("/{email}") {
            val actor = call.requireAdmin("accounts") ?: return@put
            val email = EmailRules.normalize(call.parameters["email"]!!)
            if (!EmailRules.isValid(email)) {
                return@put call.fail("email inválido: '$email'", field = "email", hint = "una sola dirección simple, sin nombre ni comas")
            }
            val req = call.receive<AccountUpsertRequest>()
            if (req.status == AccountStatus.ACTIVE && req.officerId == null) {
                return@put call.fail(
                    "una cuenta ACTIVE necesita un oficial ligado", field = "officerId",
                    hint = "crea el oficial (POST /admin/officers) y manda su id en officerId",
                )
            }
            if (call.isDryRun) return@put call.finishDryRun("account", email)
            val before = findAccount(email)
            val result = AdminRepository.upsertAccount(email, req.officerId, req.status)
            AccountGate.invalidate(email)
            // Suspender o ligar la cuenta a OTRO oficial cierra sus sesiones: los tokens ya
            // emitidos dejan de valer al momento (no a los 15 min) y sus sockets se cierran.
            val suspended = req.status == AccountStatus.SUSPENDED && before?.status != AccountStatus.SUSPENDED
            val relinked = before?.officerId != null && before.officerId != req.officerId
            if (result.action != "none" && (suspended || relinked)) {
                val n = revokeSessions(email, if (suspended) "suspended" else "admin")
                AdminAudit.record(actor, "revoke-sessions", "account", email, dryRun = false, detail = if (suspended) "suspendida ($n sesiones)" else "ligada a otro oficial ($n sesiones)")
            }
            call.finish(actor, result)
        }
        // Cerrar TODAS las sesiones de una cuenta (teléfono perdido, sospecha de robo).
        post("/{email}/revoke-sessions") {
            val actor = call.requireAdmin("accounts") ?: return@post
            val email = call.parameters["email"]!!.trim().lowercase()
            if (findAccount(email) == null) return@post call.fail("cuenta '$email' no existe", field = "email", hint = "GET /admin/accounts lista las cuentas")
            val vivas = activeSessionCounts()[email] ?: 0
            if (call.isDryRun) return@post call.finishDryRun("account", email, "cerraría $vivas sesiones")
            revokeSessions(email, "admin")
            AdminAudit.record(actor, "revoke-sessions", "account", email, dryRun = false, detail = "$vivas sesiones")
            call.respond(ChangeSummary("updated", "account", email, detail = "$vivas sesiones cerradas; debe volver a entrar con su correo"))
        }
        post("/{email}/approve") {
            val actor = call.requireAdmin("accounts") ?: return@post
            val email = EmailRules.normalize(call.parameters["email"]!!)
            approveAccount(email)?.let { problem ->
                return@post call.fail(problem, field = "officerId", hint = "PUT /admin/accounts/{email} con officerId y status ACTIVE")
            }
            AdminAudit.record(actor, "approve", "account", email, dryRun = false)
            call.respond(ChangeSummary("updated", "account", email, detail = "aprobada (ACTIVE)"))
        }
    }
    // Compat con el flujo original de aprobación.
    get("/pending") {
        call.requireAdmin("accounts") ?: return@get
        call.respond(pendingAccounts().map { AdminAccountInfo(it.email, it.officerId, it.status.name) })
    }
    post("/approve") {
        val actor = call.requireAdmin("accounts") ?: return@post
        val req = call.receive<com.alephri.elpuesto.model.MagicLinkRequest>()
        val email = EmailRules.normalize(req.email)
        approveAccount(email)?.let { problem -> return@post call.fail(problem, field = "officerId") }
        AdminAudit.record(actor, "approve", "account", email, dryRun = false)
        call.respond(Ack(message = "aprobado"))
    }

    // —— Oficiales (scope: officers) ——
    route("/officers") {
        get {
            call.requireAdmin("officers") ?: return@get
            call.respond(AdminRepository.listOfficers())
        }
        get("/{id}") {
            call.requireAdmin("officers") ?: return@get
            val o = DomainRepository.officer(call.parameters["id"]!!)
            if (o == null) call.respond(HttpStatusCode.NotFound, AdminError("oficial no encontrado")) else call.respond(o)
        }
        post {
            val actor = call.requireAdmin("officers") ?: return@post
            val body = call.receiveCreate<Officer>() ?: return@post
            if (!call.validOfficer(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("officer", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertOfficer(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("officers") ?: return@put
            val body = call.receiveEntity<Officer>() ?: return@put
            if (!call.requireExisting("officer", body.id, "officers")) return@put
            if (!call.validOfficer(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("officer", body.id)
            call.finish(actor, AdminRepository.upsertOfficer(body))
        }
    }

    // —— Eventos (scope: events) ——
    route("/events") {
        get {
            call.requireAdmin("events") ?: return@get
            call.respond(AdminRepository.allEvents())
        }
        post {
            val actor = call.requireAdmin("events") ?: return@post
            val body = call.receiveCreate<Event>() ?: return@post
            if (!call.validEvent(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("event", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertEvent(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("events") ?: return@put
            val body = call.receiveEntity<Event>() ?: return@put
            if (!call.requireExisting("event", body.id, "events")) return@put
            if (!call.validEvent(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("event", body.id)
            call.finish(actor, AdminRepository.upsertEvent(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("events") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("event", id, "se borraría con sus asignaciones/sesiones/checklist")
            call.finish(actor, AdminRepository.deleteEvent(id))
        }
        // Marca qué evento está activo ("en curso" para la app). ?value=true|false
        post("/{id}/active") {
            val actor = call.requireAdmin("events") ?: return@post
            val id = call.parameters["id"]!!
            val value = when (call.request.queryParameters["value"]) {
                "true" -> true; "false" -> false
                else -> return@post call.fail("query param value requerido", field = "value", hint = "?value=true o ?value=false")
            }
            if (!AdminRepository.setEventActive(id, value)) return@post call.fail("evento '$id' no existe")
            AdminAudit.record(actor, "set-active", "event", id, dryRun = false, detail = "active=$value")
            call.respond(ChangeSummary("updated", "event", id, detail = "active=$value"))
        }
        // Lecturas de las sub-listas (los editores las cargan antes de reemplazar).
        get("/{id}/sessions") {
            call.requireAdmin("events") ?: return@get
            call.respond(DomainRepository.schedule(call.parameters["id"]!!))
        }
        get("/{id}/checklist") {
            call.requireAdmin("events") ?: return@get
            call.respond(DomainRepository.checklist(call.parameters["id"]!!))
        }
        // Avance del checklist POR PUESTO (quién marcó cada ítem y cuándo).
        get("/{id}/checklist-state") {
            call.requireAdmin("events") ?: return@get
            call.respond(DomainRepository.checklistState(call.parameters["id"]!!))
        }
        // Registro de asistencia (pase de lista de los jefes): HISTÓRICO completo por
        // día y posición (nunca se purga); ?day=ISO filtra a un día.
        get("/{id}/attendance") {
            call.requireAdmin("events") ?: return@get
            val day = call.request.queryParameters["day"]
            if (day != null && runCatching { java.time.LocalDate.parse(day) }.isFailure) {
                return@get call.fail("day inválido", field = "day", hint = "fecha ISO yyyy-mm-dd")
            }
            call.respond(DomainRepository.attendanceAll(call.parameters["id"]!!, day))
        }
        // —— Chat del evento: el admin lee y escribe como "Control" ——
        get("/{id}/chat") {
            call.requireAdmin("events") ?: return@get
            val chatId = DomainRepository.eventChatId(call.parameters["id"]!!)
            call.respond(AdminEventChat(chatId, chatId?.let { DomainRepository.messages(it) } ?: emptyList()))
        }
        post("/{id}/chat/messages") {
            val actor = call.requireAdmin("events") ?: return@post
            val chatId = DomainRepository.eventChatId(call.parameters["id"]!!)
                ?: return@post call.fail("el evento no tiene chat", hint = "guarda el evento para crear su chat")
            val req = call.receive<ControlMessageRequest>()
            if (req.text.isBlank()) return@post call.fail("text requerido", field = "text")
            val sender = if (actor.name == "master") "Control" else "Control · ${actor.name}"
            val msg = DomainRepository.sendControlMessage(chatId, req.text.trim(), sender)
                ?: return@post call.fail("no se pudo enviar (chat archivado)")
            AdminAudit.record(actor, "send", "chat", chatId, dryRun = false, detail = "mensaje de Control")
            call.respond(msg)
        }
        // —— Tiempo real (SSE): el detalle del evento se suscribe y recibe QUÉ cambió ——
        post("/stream-token") {
            call.requireAdmin("events") ?: return@post
            call.respond(StreamTokenResponse(StreamTokens.mint()))
        }
        get("/{id}/stream") {
            val t = call.request.queryParameters["t"]
            if (t == null || !StreamTokens.consume(t)) {
                return@get call.respond(HttpStatusCode.Unauthorized, AdminError("token de stream inválido o vencido", hint = "pide uno nuevo con POST /admin/events/stream-token"))
            }
            val eventId = call.parameters["id"]!!
            val eventChatId = DomainRepository.eventChatId(eventId)
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondTextWriter(ContentType.parse("text/event-stream")) {
                try {
                    write(": conectado\n\n"); flush()
                    val changes = ChangeBus.flow
                        .filter {
                            when {
                                it.kind == "chat" -> it.chatId != null && it.chatId == eventChatId
                                // Invitaciones a privados: entre oficiales (Control no las ve).
                                it.kind == "chat-invite" || it.kind == "image" -> false
                                // Ubicación: privada entre oficiales (Control no la ve).
                                it.kind.startsWith("location") -> false
                                else -> it.eventId == null || it.eventId == eventId
                            }
                        }
                        .map { "event: change\ndata: {\"kind\":\"${it.kind}\"}\n\n" }
                    // Keepalive: detecta desconexiones y mantiene vivos los proxies.
                    val pings = kotlinx.coroutines.flow.flow { while (true) { kotlinx.coroutines.delay(25_000); emit(": ping\n\n") } }
                    kotlinx.coroutines.flow.merge(changes, pings).collect { write(it); flush() }
                } catch (_: Exception) {
                    // Cliente desconectado: fin del stream.
                }
            }
        }
        get("/{id}/assignments") {
            call.requireAdmin("events") ?: return@get
            call.respond(AdminRepository.eventAssignments(call.parameters["id"]!!))
        }
        // Registro por HONOR: participaciones que declararon los oficiales (aparte del
        // roster; nunca dan permisos). El admin las revisa y puede quitar alguna.
        get("/{id}/participations") {
            call.requireAdmin("events") ?: return@get
            call.respond(ParticipationRepository.eventParticipations(call.parameters["id"]!!))
        }
        put("/{id}/sessions") {
            val actor = call.requireAdmin("events") ?: return@put
            val id = call.parameters["id"]!!
            // status es DERIVADO de día/hora (el body puede omitirlo; se recalcula al
            // guardar). category es OPCIONAL (vacía = actividad sin categoría).
            val body = call.receiveList<Session>(mapOf("id" to "", "eventId" to id, "status" to "UPCOMING", "category" to "")) ?: return@put
            if (call.isDryRun) return@put call.finishDryRun("sessions", id, "reemplazaría con ${body.size} sesiones")
            call.finish(actor, AdminRepository.replaceSessions(id, body))
        }
        put("/{id}/checklist") {
            val actor = call.requireAdmin("events") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receiveList<ChecklistItem>(mapOf("id" to "")) ?: return@put
            if (body.any { it.text.isBlank() }) return@put call.fail("hay ítems con text vacío", field = "text")
            if (call.isDryRun) return@put call.finishDryRun("checklist", id, "reemplazaría con ${body.size} ítems (el avance por puesto se conserva por id)")
            call.finish(actor, AdminRepository.replaceChecklist(id, body))
        }
        put("/{id}/assignments") {
            val actor = call.requireAdmin("events") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receiveList<Assignment>(mapOf("id" to "", "eventId" to id)) ?: return@put
            if (call.isDryRun) {
                // El dryRun corre las MISMAS validaciones que el guardado real.
                AdminRepository.assignmentsProblem(id, body)?.let {
                    return@put call.fail(it, hint = "corrige y reintenta (nada se escribió)")
                }
                return@put call.finishDryRun("assignments", id, "reemplazaría con ${body.size} asignaciones")
            }
            call.finish(actor, AdminRepository.replaceAssignments(id, body))
        }
    }

    // —— Puestos PROPUESTOS por los oficiales (registro por honor): cola de revisión.
    // Aprobar = crear el puesto (se publica para todos); fusionar = era uno existente;
    // rechazar = la participación queda sin puesto. Scope circuits (son puestos). ——
    route("/puesto-proposals") {
        get {
            call.requireAdmin("circuits") ?: return@get
            val raw = call.request.queryParameters["status"] ?: "PENDING"
            val status = runCatching { com.alephri.elpuesto.model.ProposalStatus.valueOf(raw) }.getOrNull()
                ?: return@get call.fail("status inválido", field = "status", hint = "PENDING (default), APPROVED, MERGED o REJECTED")
            call.respond(ProposalRepository.groups(status))
        }
        post("/approve") {
            val actor = call.requireAdmin("circuits") ?: return@post
            val req = call.receive<ApproveProposalsRequest>()
            ProposalRepository.approveProblem(req)?.let { return@post call.fail(it, hint = "revisa los ids (GET /admin/puesto-proposals) y reintenta") }
            if (call.isDryRun) return@post call.finishDryRun("puesto-proposal", req.ids.firstOrNull(), "se crearía el puesto '${req.label.trim()}' con ${req.ids.distinct().size} propuesta(s)")
            call.finish(actor, ProposalRepository.approve(req, actor.name))
        }
        post("/merge") {
            val actor = call.requireAdmin("circuits") ?: return@post
            val req = call.receive<MergeProposalsRequest>()
            ProposalRepository.mergeProblem(req)?.let { return@post call.fail(it, hint = "puestoId = un puesto vivo del mismo trazado (ver 'nearby')") }
            if (call.isDryRun) return@post call.finishDryRun("puesto-proposal", req.puestoId, "se fusionarían ${req.ids.distinct().size} propuesta(s) con el puesto")
            call.finish(actor, ProposalRepository.merge(req, actor.name))
        }
        post("/reject") {
            val actor = call.requireAdmin("circuits") ?: return@post
            val req = call.receive<RejectProposalsRequest>()
            ProposalRepository.rejectProblem(req)?.let { return@post call.fail(it) }
            if (call.isDryRun) return@post call.finishDryRun("puesto-proposal", req.ids.firstOrNull(), "se rechazarían ${req.ids.distinct().size} propuesta(s) (sus registros quedan sin puesto)")
            call.finish(actor, ProposalRepository.reject(req, actor.name))
        }
    }

    route("/participations") {
        delete("/{id}") {
            val actor = call.requireAdmin("events") ?: return@delete
            val id = call.parameters["id"]!!
            if (!ParticipationRepository.exists(id)) return@delete call.fail("participación '$id' no existe", hint = "consulta GET /admin/events/{id}/participations")
            if (call.isDryRun) return@delete call.finishDryRun("participation", id, "se quitaría el registro por honor (el oficial deja de verlo en su historial)")
            call.finish(actor, ParticipationRepository.deleteById(id))
        }
    }

    // —— Circuitos (scope: circuits) ——
    route("/circuits") {
        get {
            call.requireAdmin("circuits") ?: return@get
            call.respond(DomainRepository.circuits())
        }
        get("/{id}/trazados") {
            call.requireAdmin("circuits") ?: return@get
            call.respond(DomainRepository.trazados(call.parameters["id"]!!))
        }
        post {
            val actor = call.requireAdmin("circuits") ?: return@post
            val body = call.receiveCreate<Circuit>() ?: return@post
            if (!call.validCircuit(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("circuit", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertCircuit(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("circuits") ?: return@put
            val body = call.receiveEntity<Circuit>() ?: return@put
            if (!call.requireExisting("circuit", body.id, "circuits")) return@put
            if (!call.validCircuit(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("circuit", body.id)
            call.finish(actor, AdminRepository.upsertCircuit(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("circuits") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("circuit", id, "borrado lógico con sus trazados/puestos/activos; los eventos que lo referencian conservan sus datos")
            call.finish(actor, AdminRepository.deleteCircuit(id))
        }
    }
    route("/trazados") {
        get("/{id}/puestos") {
            call.requireAdmin("circuits") ?: return@get
            call.respond(DomainRepository.puestos(call.parameters["id"]!!))
        }
        get("/{id}/assets") {
            call.requireAdmin("circuits") ?: return@get
            call.respond(DomainRepository.assets(call.parameters["id"]!!))
        }
        post {
            val actor = call.requireAdmin("circuits") ?: return@post
            val body = call.receiveCreate<Trazado>() ?: return@post
            if (!call.validTrazado(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("trazado", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertTrazado(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("circuits") ?: return@put
            val body = call.receiveEntity<Trazado>() ?: return@put
            if (!call.requireExisting("trazado", body.id, "trazados")) return@put
            if (!call.validTrazado(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("trazado", body.id)
            call.finish(actor, AdminRepository.upsertTrazado(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("circuits") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("trazado", id, "borrado lógico con sus puestos/activos; los eventos que lo referencian conservan sus datos")
            call.finish(actor, AdminRepository.deleteTrazado(id))
        }
        // Dibujo del trazado (vértices geo sobre OSM + silueta normalizada derivada).
        get("/{id}/path") {
            call.requireAdmin("circuits") ?: return@get
            val geo = AdminRepository.trazadoGeo(call.parameters["id"]!!)
            call.respond(TrazadoPathResponse(geo, AdminRepository.normalizeGeo(geo)))
        }
        put("/{id}/path") {
            val actor = call.requireAdmin("circuits") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receive<TrazadoPathRequest>()
            body.geo.forEachIndexed { i, g ->
                if (g.lat !in -90.0..90.0 || g.lon !in -180.0..180.0) {
                    return@put call.fail("vértice $i fuera de rango (lat=${g.lat}, lon=${g.lon})", field = "geo",
                        hint = "lat -90..90, lon -180..180")
                }
            }
            if (body.geo.size == 1) return@put call.fail("un solo vértice no forma un trazado", field = "geo", hint = "manda 2+ vértices, o lista vacía para borrar el dibujo")
            if (call.isDryRun) return@put call.finishDryRun("trazado-path", id, "reemplazaría con ${body.geo.size} vértices")
            call.finish(actor, AdminRepository.replaceTrazadoPath(id, body.geo))
        }
        put("/{id}/puestos") {
            val actor = call.requireAdmin("circuits") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receiveList<Puesto>(mapOf("id" to "", "trazadoId" to id)) ?: return@put
            body.forEach { p ->
                if (p.number < 1) return@put call.fail("puesto con number ${p.number}: debe ser >= 1", field = "number")
                val plat = p.lat; val plon = p.lon
                if ((plat == null) != (plon == null)) {
                    return@put call.fail("puesto ${p.number}: lat y lon van juntas", field = "lat")
                }
                if (plat != null && plon != null && (plat !in -90.0..90.0 || plon !in -180.0..180.0)) {
                    return@put call.fail("puesto ${p.number}: lat/lon fuera de rango", field = "lat", hint = "lat -90..90, lon -180..180")
                }
                if (p.onMap && plat == null && (p.point.x !in 0f..1f || p.point.y !in 0f..1f)) {
                    return@put call.fail(
                        "puesto ${p.number}: point fuera de rango (x=${p.point.x}, y=${p.point.y})",
                        field = "point", hint = "manda lat/lon absolutas (recomendado) o point normalizado 0..1",
                    )
                }
            }
            if (call.isDryRun) return@put call.finishDryRun("puestos", id, "reemplazaría con ${body.size} puestos")
            call.finish(actor, AdminRepository.replacePuestos(id, body))
        }
        put("/{id}/assets") {
            val actor = call.requireAdmin("circuits") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receiveList<TrackAsset>(mapOf("id" to "", "trazadoId" to id)) ?: return@put
            body.forEach { a ->
                val alat = a.lat; val alon = a.lon
                if ((alat == null) != (alon == null)) {
                    return@put call.fail("activo '${a.label}': lat y lon van juntas", field = "lat")
                }
                if (alat != null && alon != null && (alat !in -90.0..90.0 || alon !in -180.0..180.0)) {
                    return@put call.fail("activo '${a.label}': lat/lon fuera de rango", field = "lat", hint = "lat -90..90, lon -180..180")
                }
                if (alat == null && (a.point.x !in 0f..1f || a.point.y !in 0f..1f)) {
                    return@put call.fail(
                        "activo '${a.label}': point fuera de rango", field = "point",
                        hint = "manda lat/lon absolutas (recomendado) o point normalizado 0..1",
                    )
                }
            }
            if (call.isDryRun) return@put call.finishDryRun("assets", id, "reemplazaría con ${body.size} activos")
            call.finish(actor, AdminRepository.replaceAssets(id, body))
        }
    }

    // —— Campeonatos = series (scope: championships) ——
    route("/series") {
        get {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.series())
        }
        post {
            val actor = call.requireAdmin("championships") ?: return@post
            val body = call.receiveCreate<com.alephri.elpuesto.model.Series>() ?: return@post
            if (!call.validSeries(body, null)) return@post
            if (call.isDryRun) return@post call.finishDryRun("series", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertSeries(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@put
            val body = call.receiveEntity<com.alephri.elpuesto.model.Series>() ?: return@put
            if (!call.requireExisting("series", body.id, "series")) return@put
            if (!call.validSeries(body, body.id)) return@put
            if (call.isDryRun) return@put call.finishDryRun("series", body.id)
            call.finish(actor, AdminRepository.upsertSeries(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("series", id, "se borraría si no tiene temporadas")
            call.finish(actor, AdminRepository.deleteSeries(id))
        }
    }
    // —— Temporadas = championships (scope: championships) ——
    route("/championships") {
        get {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.championships(call.request.queryParameters["seriesId"]))
        }
        get("/{id}/categories") {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.categories(call.parameters["id"]!!))
        }
        post {
            val actor = call.requireAdmin("championships") ?: return@post
            val body = call.receiveCreate<Championship>() ?: return@post
            if (!call.validChampionship(body, null)) return@post
            if (call.isDryRun) return@post call.finishDryRun("championship", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertChampionship(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@put
            val body = call.receiveEntity<Championship>() ?: return@put
            if (!call.requireExisting("championship", body.id, "championships")) return@put
            if (!call.validChampionship(body, body.id)) return@put
            if (call.isDryRun) return@put call.finishDryRun("championship", body.id)
            call.finish(actor, AdminRepository.upsertChampionship(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("championship", id, "se borraría con categorías/posiciones/fechas/pilotos")
            call.finish(actor, AdminRepository.deleteChampionship(id))
        }
    }
    // —— Ingesta automática de posiciones (scope: championships) ——
    route("/standings-ingest") {
        get {
            call.requireAdmin("championships") ?: return@get
            call.respond(StandingsIngest.status())
        }
        get("/sources") {
            call.requireAdmin("championships") ?: return@get
            call.respond(StandingsIngest.sources())
        }
    }
    route("/categories") {
        put("/{id}/standings-ingest") {
            val actor = call.requireAdmin("championships") ?: return@put
            val id = call.parameters["id"]!!
            if (!call.requireExisting("category", id, "categories")) return@put
            val body = call.receive<IngestConfig>()
            if (StandingsSources.byId(body.source) == null) {
                return@put call.fail(
                    "fuente desconocida: ${body.source}", field = "source",
                    hint = "fuentes disponibles: ${StandingsSources.all.joinToString { it.id }} (GET /admin/standings-ingest/sources)",
                )
            }
            if (call.isDryRun) return@put call.finishDryRun("standings-ingest", id, "configuraría la fuente ${body.source}")
            call.finish(actor, StandingsIngest.configure(id, body))
        }
        delete("/{id}/standings-ingest") {
            val actor = call.requireAdmin("championships") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("standings-ingest", id, "quitaría la ingesta (las posiciones se conservan)")
            call.finish(actor, StandingsIngest.remove(id))
        }
        // Tabla leída de una imagen (campeonatos que solo publican así: México Racing Cup).
        get("/{id}/standings-reading") {
            call.requireAdmin("championships") ?: return@get
            val reading = StandingsReadings.get(call.parameters["id"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("la categoría no tiene lectura guardada"))
            call.respond(reading)
        }
        put("/{id}/standings-reading") {
            val actor = call.requireAdmin("championships") ?: return@put
            val id = call.parameters["id"]!!
            if (!call.requireExisting("category", id, "categories")) return@put
            val body = call.receive<StandingsReading>()
            val rounds = StandingsReadings.roundsOf(id)
            StandingsReadings.problem(body, rounds)?.let {
                return@put call.fail(it, field = "reading", hint = "{imageUrl, throughRound, rows:[{pos, name, team?, points}]} — pos = la impresa, única")
            }
            if (call.isDryRun) return@put call.finishDryRun("standings-reading", id, "${body.rows.size} filas hasta la fecha ${body.throughRound}")
            val saved = StandingsReadings.save(id, body, actor.name)
            AdminAudit.record(actor, saved.action, saved.entity, saved.id, dryRun = false, detail = saved.detail)
            // Con la fuente de imagen configurada se publica YA (sin esperar al job).
            val run = if (StandingsIngest.sourceOf(id) == MexicoRacingCupSite.id) StandingsIngest.run(id, dryRun = false, round = null) else null
            call.respond(ReadingSaved(saved, run))
        }
        // Corre la ingesta YA (sin esperar al job). ?dryRun=true devuelve la tabla sin
        // escribir; ?round=N pide la tabla tras esa fecha (default: la última terminada).
        post("/{id}/standings-ingest/run") {
            call.requireAdmin("championships") ?: return@post
            val id = call.parameters["id"]!!
            val round = call.request.queryParameters["round"]?.let {
                it.toIntOrNull() ?: return@post call.fail("round inválido: $it", field = "round", hint = "número de fecha del calendario")
            }
            call.respond(StandingsIngest.run(id, call.isDryRun, round))
        }
        get("/{id}/standings") {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.standings(call.parameters["id"]!!))
        }
        get("/{id}/rounds") {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.rounds(call.parameters["id"]!!))
        }
        get("/{id}/drivers") {
            call.requireAdmin("championships") ?: return@get
            call.respond(DomainRepository.drivers(call.parameters["id"]!!))
        }
        post {
            val actor = call.requireAdmin("championships") ?: return@post
            val body = call.receiveCreate<Category>() ?: return@post
            if (!call.validCategory(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("category", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertCategory(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@put
            val body = call.receiveEntity<Category>() ?: return@put
            if (!call.requireExisting("category", body.id, "categories")) return@put
            if (!call.validCategory(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("category", body.id)
            call.finish(actor, AdminRepository.upsertCategory(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("championships") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("category", id, "se borraría con posiciones/fechas/pilotos")
            call.finish(actor, AdminRepository.deleteCategory(id))
        }
        put("/{id}/standings") {
            val actor = call.requireAdmin("championships") ?: return@put
            val id = call.parameters["id"]!!
            // driverName/team ya no se capturan (se resuelven de Pilotos al leer): el body
            // normalizado es {pos, driverRef | driverNumber, points}.
            val body = call.receiveList<Standing>(mapOf("driverName" to "", "team" to "")) ?: return@put
            val dupes = body.groupBy { it.pos }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) return@put call.fail("posiciones repetidas: ${dupes.joinToString()}", field = "pos")
            if (call.isDryRun) return@put call.finishDryRun("standings", id, "reemplazaría con ${body.size} posiciones")
            call.finish(actor, AdminRepository.replaceStandings(id, body))
        }
        put("/{id}/rounds") {
            val actor = call.requireAdmin("championships") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receive<List<Round>>()
            val dupes = body.groupBy { it.number }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) return@put call.fail("números de fecha repetidos: ${dupes.joinToString()}", field = "number")
            body.forEach { r ->
                if (r.circuitId == null && r.location.isNullOrBlank()) {
                    return@put call.fail(
                        "fecha ${r.number}: falta la sede", field = "circuitId",
                        hint = "usa un circuitId del catálogo (GET /admin/circuits) o, si no corre en circuito (rally), location \"Ciudad, País\"",
                    )
                }
                if (r.startDate != null && r.startDate!! > r.date) {
                    return@put call.fail("fecha ${r.number}: startDate posterior a date", field = "startDate", hint = "startDate = primer día del fin de semana; date = día de la carrera")
                }
            }
            if (call.isDryRun) return@put call.finishDryRun("rounds", id, "reemplazaría con ${body.size} fechas")
            call.finish(actor, AdminRepository.replaceRounds(id, body))
        }
        put("/{id}/drivers") {
            val actor = call.requireAdmin("championships") ?: return@put
            val id = call.parameters["id"]!!
            val body = call.receive<List<Driver>>()
            body.firstOrNull { it.name.isBlank() }?.let {
                return@put call.fail("piloto sin nombre", field = "name")
            }
            // La llave es ref (o, sin ref, el número; sin número, el nombre): debe ser única.
            val dupes = body.map { normalizeDriver(it).ref!! }.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) {
                return@put call.fail(
                    "pilotos repetidos (misma llave): ${dupes.joinToString()}", field = "ref",
                    hint = "si dos pilotos comparten número (sustitutos), dale a cada uno un `ref` distinto",
                )
            }
            if (call.isDryRun) return@put call.finishDryRun("drivers", id, "reemplazaría con ${body.size} pilotos")
            call.finish(actor, AdminRepository.replaceDrivers(id, body))
        }
    }

    // —— Convocatorias (scope: convocatorias) ——
    route("/convocatorias") {
        get {
            call.requireAdmin("convocatorias") ?: return@get
            call.respond(AdminRepository.allConvocatorias())
        }
        post {
            val actor = call.requireAdmin("convocatorias") ?: return@post
            val body = call.receiveCreate<Convocatoria>() ?: return@post
            if (!call.validConvocatoria(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("convocatoria", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertConvocatoria(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("convocatorias") ?: return@put
            val body = call.receiveEntity<Convocatoria>() ?: return@put
            if (!call.requireExisting("convocatoria", body.id, "convocatorias")) return@put
            if (!call.validConvocatoria(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("convocatoria", body.id)
            call.finish(actor, AdminRepository.upsertConvocatoria(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("convocatorias") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("convocatoria", id)
            call.finish(actor, AdminRepository.deleteConvocatoria(id))
        }
    }

    // —— Agenda global (scope: agenda) ——
    route("/agenda") {
        get {
            call.requireAdmin("agenda") ?: return@get
            call.respond(AdminRepository.globalAgenda())
        }
        post {
            val actor = call.requireAdmin("agenda") ?: return@post
            val body = call.receiveCreate<AgendaEntry>() ?: return@post
            if (!call.validAgenda(body)) return@post
            if (call.isDryRun) return@post call.finishDryRun("agenda", null, "válido; al crear el servidor asigna el id (UUID)")
            call.finish(actor, AdminRepository.upsertGlobalAgendaEntry(body))
        }
        put("/{id}") {
            val actor = call.requireAdmin("agenda") ?: return@put
            val body = call.receiveEntity<AgendaEntry>() ?: return@put
            if (!call.requireExisting("agenda", body.id, "agenda")) return@put
            if (!call.validAgenda(body)) return@put
            if (call.isDryRun) return@put call.finishDryRun("agenda", body.id)
            call.finish(actor, AdminRepository.upsertGlobalAgendaEntry(body))
        }
        delete("/{id}") {
            val actor = call.requireAdmin("agenda") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("agenda", id)
            call.finish(actor, AdminRepository.deleteGlobalAgendaEntry(id))
        }
    }

    // —— Moderación: reportes de mensajes (scope: moderation) ——
    route("/reports") {
        get {
            call.requireAdmin("moderation") ?: return@get
            // Mensajes, chats (nombre/imagen/descripción) y perfiles, recientes primero.
            call.respond((DomainRepository.listMessageReports() + DomainRepository.listContentReports()).sortedByDescending { it.createdAt })
        }
        // Descartar un reporte revisado (el mensaje no se toca; queda auditado).
        delete("/{id}") {
            val actor = call.requireAdmin("moderation") ?: return@delete
            val id = call.parameters["id"]!!
            if (call.isDryRun) return@delete call.finishDryRun("report", id)
            if (DomainRepository.deleteMessageReport(id)) {
                AdminAudit.record(actor, "dismiss", "report", id, dryRun = false)
                call.respond(ChangeSummary("deleted", "report", id, detail = "reporte descartado"))
            } else {
                call.fail("reporte '$id' no existe", hint = "GET /admin/reports lista los reportes")
            }
        }
    }

    // —— Imágenes: logos de campeonatos/circuitos y avatares (scope: images) ——
    route("/images") {
        post("/{kind}/{ownerId}") {
            val actor = call.requireAdmin("images") ?: return@post
            val kind = call.parameters["kind"]!!
            val ownerId = call.parameters["ownerId"]!!
            // El logo es del CAMPEONATO (series), no de la temporada.
            val allowed = setOf("series", "circuit", "avatar", "trazado", "event")
            if (kind !in allowed) {
                return@post call.fail("kind '$kind' no administrable", field = "kind", hint = "válidos: ${allowed.joinToString()}")
            }
            val bytes = call.receive<ByteArray>()
            // El mapa del trazado conserva su proporción (foto); logos/avatares van cuadrados.
            val stored = if (kind == "trazado") ImageService.processAndStorePhoto(kind, ownerId, bytes)
            else ImageService.processAndStore(kind, ownerId, bytes)
            if (!stored) {
                return@post call.fail("imagen inválida", hint = "manda los bytes crudos de un JPEG/PNG en el body")
            }
            // Deja la URL persistida donde el modelo la lee.
            when (kind) {
                "series" -> AdminRepository.setSeriesEmblem(ownerId, "/images/series/$ownerId/full")
                "avatar" -> DomainRepository.setOfficerAvatar(ownerId, "/images/avatar/$ownerId/full")
                "trazado" -> AdminRepository.setTrazadoMapUrl(ownerId, "/images/trazado/$ownerId/full")
            }
            AdminAudit.record(actor, "upload", "image", "$kind/$ownerId", dryRun = false)
            call.respond(ChangeSummary("updated", "image", "$kind/$ownerId", detail = if (kind == "trazado") "variante full generada (proporción original)" else "variantes thumb/full generadas"))
        }
        get("/{kind}/{ownerId}/{variant}") {
            call.requireAdmin("images") ?: return@get
            val bytes = ImageService.get(call.parameters["kind"]!!, call.parameters["ownerId"]!!, call.parameters["variant"]!!)
            if (bytes == null) call.respond(HttpStatusCode.NotFound, AdminError("imagen no encontrada"))
            else call.respondBytes(bytes, ContentType.parse(ImageService.CONTENT_TYPE))
        }
    }
}
