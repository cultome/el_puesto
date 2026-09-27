package com.alephri.elpuesto.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Soporte de la API administrativa (consumida por agentes vía MCP, el admin web y curl):
 * - API keys nombradas con scopes (además de la master key `ADMIN_API_KEY` de env).
 * - Auditoría de toda mutación admin: quién (humano o agente), qué y cuándo.
 * - Errores accionables (campo + hint) para que un agente pueda autocorregirse.
 */

// —— Scopes ——

object AdminScopes {
    const val ALL = "*"
    val KNOWN = setOf(
        "accounts", "officers", "events", "circuits", "championships",
        "convocatorias", "agenda", "images", "keys", "moderation",
    )
}

/** Identidad admin resuelta desde el header X-Admin-Key. */
data class AdminActor(val name: String, val scopes: Set<String>) {
    fun can(scope: String): Boolean = AdminScopes.ALL in scopes || scope in scopes
}

// —— Tablas ——

object AdminApiKeysT : Table("admin_api_keys") {
    val id = varchar("id", 64)
    val name = varchar("name", 100).uniqueIndex()
    val keyHash = varchar("key_hash", 64) // SHA-256 hex del key en claro
    val scopes = varchar("scopes", 500) // separados por coma; "*" = todos
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AdminAuditT : Table("admin_audit") {
    val id = varchar("id", 64)
    val at = timestamp("at")
    val actor = varchar("actor", 100)
    val action = varchar("action", 32) // upsert | delete | replace | approve | upload…
    val entity = varchar("entity", 64)
    val entityId = varchar("entity_id", 200).nullable()
    val dryRun = bool("dry_run").default(false)
    val detail = varchar("detail", 1000).nullable()
    override val primaryKey = PrimaryKey(id)
}

// —— DTOs (JSON; solo los usa la interfaz admin, no la app) ——

/** Error accionable: siempre di qué estuvo mal y cómo corregirlo. */
@Serializable
data class AdminError(val error: String, val field: String? = null, val hint: String? = null)

/** Resultado de una mutación (o de su simulación con ?dryRun=true). */
@Serializable
data class ChangeSummary(
    val action: String, // created | updated | deleted | replaced | none
    val entity: String,
    val id: String? = null,
    val dryRun: Boolean = false,
    val detail: String? = null,
)

/** Reporte de mensaje (moderación) con su contexto resuelto para el admin. */
@Serializable
data class AdminReportInfo(
    val id: String,
    val chatId: String,
    val chatName: String,
    val messageId: String,
    val messageText: String,
    val messageSender: String,
    val messageSenderId: String? = null,
    val messageAt: String? = null,
    val mediaType: String? = null,
    val reporterId: String,
    val reporterName: String,
    val reason: String? = null,
    val createdAt: String,
    /** Qué se reporta: `message`, `chat` (nombre/imagen/descripción) u `officer` (perfil). */
    val kind: String = "message",
    /** chat/officer: id y nombre de lo reportado. */
    val targetId: String? = null,
    val targetName: String? = null,
)

@Serializable
data class AdminKeyInfo(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val active: Boolean,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

@Serializable
data class CreateKeyRequest(val name: String, val scopes: List<String>)

/** El key en claro se devuelve UNA sola vez, al crearlo. */
@Serializable
data class CreatedKey(val id: String, val name: String, val scopes: List<String>, val key: String)

@Serializable
data class AuditEntry(
    val id: String,
    val at: String,
    val actor: String,
    val action: String,
    val entity: String,
    val entityId: String? = null,
    val dryRun: Boolean = false,
    val detail: String? = null,
)

@Serializable
data class WhoAmI(val name: String, val scopes: List<String>)

/** Vértice geográfico (dibujo del trazado sobre OSM). */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

@Serializable
data class TrazadoPathRequest(val geo: List<GeoPoint>)

@Serializable
data class TrazadoPathResponse(
    val geo: List<GeoPoint>,
    val path: List<com.alephri.elpuesto.model.MapPoint>,
)

// —— Auth ——

object AdminAuth {
    private val rng = SecureRandom()

    fun init() = transaction { SchemaUtils.create(AdminApiKeysT, AdminAuditT) }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Resuelve el header X-Admin-Key a un actor (master key de env o key nombrada). */
    fun resolve(key: String?): AdminActor? {
        if (key.isNullOrBlank()) return null
        if (constantTimeEquals(key, Config.adminApiKey)) return AdminActor("master", setOf(AdminScopes.ALL))
        val hash = sha256Hex(key)
        return transaction {
            val row = AdminApiKeysT.selectAll()
                .where { (AdminApiKeysT.keyHash eq hash) and (AdminApiKeysT.active eq true) }
                .firstOrNull() ?: return@transaction null
            AdminApiKeysT.update({ AdminApiKeysT.id eq row[AdminApiKeysT.id] }) { it[lastUsedAt] = Instant.now() }
            AdminActor(row[AdminApiKeysT.name], row[AdminApiKeysT.scopes].split(",").map(String::trim).toSet())
        }
    }

    /** Crea una key nombrada; devuelve el key en claro (única vez que se ve). */
    fun createKey(name: String, scopes: Set<String>): CreatedKey = transaction {
        val id = uuidv7()
        val bytes = ByteArray(32).also(rng::nextBytes)
        val plain = "epk_" + bytes.joinToString("") { "%02x".format(it) }
        AdminApiKeysT.insert {
            it[AdminApiKeysT.id] = id
            it[AdminApiKeysT.name] = name
            it[keyHash] = sha256Hex(plain)
            it[AdminApiKeysT.scopes] = scopes.joinToString(",")
            it[createdAt] = Instant.now()
        }
        CreatedKey(id, name, scopes.toList(), plain)
    }

    fun listKeys(): List<AdminKeyInfo> = transaction {
        AdminApiKeysT.selectAll().orderBy(AdminApiKeysT.createdAt to SortOrder.ASC).map {
            AdminKeyInfo(
                id = it[AdminApiKeysT.id], name = it[AdminApiKeysT.name],
                scopes = it[AdminApiKeysT.scopes].split(",").map(String::trim),
                active = it[AdminApiKeysT.active],
                createdAt = it[AdminApiKeysT.createdAt].toString(),
                lastUsedAt = it[AdminApiKeysT.lastUsedAt]?.toString(),
            )
        }
    }

    fun revokeKey(id: String): Boolean = transaction {
        AdminApiKeysT.update({ AdminApiKeysT.id eq id }) { it[active] = false } > 0
    }
}

// —— Auditoría ——

object AdminAudit {
    fun record(actor: AdminActor, action: String, entity: String, entityId: String?, dryRun: Boolean, detail: String? = null) {
        transaction {
            AdminAuditT.insert {
                it[id] = uuidv7()
                it[at] = Instant.now()
                it[AdminAuditT.actor] = actor.name
                it[AdminAuditT.action] = action
                it[AdminAuditT.entity] = entity
                it[AdminAuditT.entityId] = entityId
                it[AdminAuditT.dryRun] = dryRun
                it[AdminAuditT.detail] = detail?.take(1000)
            }
        }
        // Toda mutación admin real avisa al bus (kind `admin:<entidad>`): los dispositivos
        // conectados al stream refrescan solos, sin esperar un pull-to-refresh.
        if (!dryRun) ChangeBus.emit(null, "admin:$entity", id = entityId, action = action, detail = detail)
    }

    fun list(limit: Int, entity: String?): List<AuditEntry> = transaction {
        val base = AdminAuditT.selectAll()
        val filtered = if (entity != null) base.where { AdminAuditT.entity eq entity } else base
        filtered.orderBy(AdminAuditT.at to SortOrder.DESC).limit(limit).map {
            AuditEntry(
                id = it[AdminAuditT.id], at = it[AdminAuditT.at].toString(), actor = it[AdminAuditT.actor],
                action = it[AdminAuditT.action], entity = it[AdminAuditT.entity], entityId = it[AdminAuditT.entityId],
                dryRun = it[AdminAuditT.dryRun], detail = it[AdminAuditT.detail],
            )
        }
    }
}

// —— Guard de rutas ——

/**
 * Valida X-Admin-Key y el scope requerido. Responde 401/403 con hint y devuelve null
 * (el handler debe hacer `?: return@...`). El scope se declara por ruta.
 */
suspend fun ApplicationCall.requireAdmin(scope: String): AdminActor? {
    // Con freno de fuerza bruta por IP (Security.kt).
    val actor = adminActorOrFail() ?: return null
    if (!actor.can(scope)) {
        respond(
            HttpStatusCode.Forbidden,
            AdminError(
                "la clave '${actor.name}' no tiene el scope '$scope'",
                hint = "scopes de esta clave: ${actor.scopes.joinToString(", ")}",
            ),
        )
        return null
    }
    return actor
}

/** ¿El dryRun viene activado en el query string? (`?dryRun=true`) */
val ApplicationCall.isDryRun: Boolean
    get() = request.queryParameters["dryRun"] == "true"
