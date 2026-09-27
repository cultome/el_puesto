package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.Invitation
import org.jetbrains.exposed.sql.SortOrder
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.time.Instant

/** Cuenta de oficial (auth). `officerId` null mientras no está aprobada/ligada. */
data class Account(val email: String, val officerId: String?, val status: AccountStatus)

/** Refresh token válido resuelto a su cuenta. */
data class RefreshOwner(val email: String, val officerId: String)

object Accounts : Table("accounts") {
    val email = varchar("email", 320)
    val officerId = varchar("officer_id", 64).nullable()
    val status = varchar("status", 32)
    /**
     * Corte de sesiones: todo access token emitido ANTES de este instante deja de valer
     * (cerrar sesiones, reuso de refresh token, suspensión). null = nunca se cortó.
     */
    val sessionsRevokedAt = timestamp("sessions_revoked_at").nullable()
    /**
     * Cuándo la cuenta terminó (u omitió) la bienvenida "Completar perfil". Es por CUENTA, no
     * por dispositivo: iniciar sesión en otro teléfono o navegador no la vuelve a mostrar.
     * null = aún no.
     */
    val onboardedAt = timestamp("onboarded_at").nullable()
    override val primaryKey = PrimaryKey(email)
}

/**
 * Enlaces mágicos vivos. [token] es el SHA-256 (hex) del token, nunca el token: quien lea
 * la base (o un respaldo) no puede entrar con él. [challenge] = reto PKCE del teléfono que
 * lo pidió (null = app anterior).
 */
object MagicTokens : Table("magic_tokens") {
    val token = varchar("token", 64)
    val email = varchar("email", 320)
    val expiresAt = timestamp("expires_at")
    val challenge = varchar("challenge", 64).nullable()
    override val primaryKey = PrimaryKey(token)
}

/** Refresh tokens: [token] es su SHA-256 (hex), como en [MagicTokens]. */
object RefreshTokens : Table("refresh_tokens") {
    val token = varchar("token", 64)
    val email = varchar("email", 320)
    val officerId = varchar("officer_id", 64)
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked").default(false)
    /** Cuándo y por qué se revocó: "rotated" (se canjeó), "logout", "admin", "suspended", "reuse", "all". */
    val revokedAt = timestamp("revoked_at").nullable()
    val revokeReason = varchar("revoke_reason", 16).nullable()
    override val primaryKey = PrimaryKey(token)
}

/** SHA-256 en hex de un token: es lo único que se guarda de él. */
fun tokenHash(token: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

/** Conecta a Postgres (Hikari), crea el esquema y siembra las cuentas demo. */
fun initDatabase() {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = Config.dbUrl
        username = Config.dbUser
        password = Config.dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 6
        // Ninguna consulta de la app tarda más de milisegundos: una que pase de 15 s es
        // abuso o un bug, y sin tope acapararía una de las 6 conexiones del pool.
        connectionInitSql = "SET statement_timeout = '15s'"
    })
    Database.connect(ds)
    transaction {
        val hadOnboarding = exec(
            "SELECT 1 FROM information_schema.columns WHERE table_name = 'accounts' AND column_name = 'onboarded_at'",
        ) { it.next() } ?: false
        SchemaUtils.createMissingTablesAndColumns(Accounts, MagicTokens, RefreshTokens, Invitations)
        // Al crear la columna (una sola vez): quien ya había entrado alguna vez (tiene refresh
        // tokens, que nunca se borran) ya pasó por la bienvenida — antes vivía solo en cada
        // teléfono y se repetía en cada dispositivo nuevo.
        if (!hadOnboarding) {
            exec("UPDATE accounts SET onboarded_at = now() WHERE EXISTS (SELECT 1 FROM refresh_tokens r WHERE r.email = accounts.email)")
        }
        // Tokens guardados en claro por versiones anteriores (UUID de 36 caracteres) → su
        // SHA-256; idempotente (un hash tiene 64). Así valen igual, pero la base ya no los tiene.
        exec("UPDATE magic_tokens SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex') WHERE length(token) = 36")
        exec("UPDATE refresh_tokens SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex') WHERE length(token) = 36")
    }
}

/** ¿La cuenta ya pasó por la bienvenida (Completar perfil), en cualquier dispositivo? */
fun accountOnboarded(email: String): Boolean = transaction {
    Accounts.selectAll().where { Accounts.email eq email }.firstOrNull()?.get(Accounts.onboardedAt) != null
}

/** Marca la bienvenida como hecha (idempotente: conserva la primera fecha). */
fun markAccountOnboarded(email: String): Unit = transaction {
    Accounts.update({ (Accounts.email eq email) and Accounts.onboardedAt.isNull() }) { it[onboardedAt] = Instant.now() }
}

fun findAccount(email: String): Account? = transaction {
    Accounts.selectAll().where { Accounts.email eq email }.firstOrNull()?.let {
        Account(it[Accounts.email], it[Accounts.officerId], AccountStatus.valueOf(it[Accounts.status]))
    }
}

/**
 * Aprueba una cuenta. Exige un oficial ligado: una cuenta activa sin oficial no tendría
 * identidad propia en el sistema. Devuelve el problema o null si se aprobó.
 */
fun approveAccount(email: String): String? {
    val problem = transaction {
        val acc = Accounts.selectAll().where { Accounts.email eq email }.firstOrNull()
            ?: return@transaction "la cuenta '$email' no existe"
        if (acc[Accounts.officerId] == null) return@transaction "liga un oficial a la cuenta antes de aprobarla"
        Accounts.update({ Accounts.email eq email }) { it[status] = AccountStatus.ACTIVE.name }
        null
    }
    AccountGate.invalidate(email)
    return problem
}

/**
 * Sujeto del JWT de una cuenta sin oficial ligado (invitada o esperando aprobación): un
 * identificador opaco derivado del correo. Nunca el correo: el sujeto se usa como id del
 * autor en todo lo que la cuenta toque.
 */
fun pendingSubject(email: String): String = "pendiente-" + tokenHash("cuenta:$email").take(24)

/** Cuentas pendientes de aprobación (para el admin). */
fun pendingAccounts(): List<Account> = transaction {
    Accounts.selectAll().where { Accounts.status eq AccountStatus.PENDING_APPROVAL.name }.map {
        Account(it[Accounts.email], it[Accounts.officerId], AccountStatus.valueOf(it[Accounts.status]))
    }
}

/**
 * Guarda un enlace nuevo y BORRA los anteriores de ese correo: solo vale el último que se
 * pidió (un correo viejo reenviado o interceptado ya no sirve).
 */
fun saveMagicToken(token: String, email: String, expiresAt: Instant, challenge: String?): Unit = transaction {
    MagicTokens.deleteWhere { MagicTokens.email eq email }
    MagicTokens.insert {
        it[MagicTokens.token] = tokenHash(token)
        it[MagicTokens.email] = email
        it[MagicTokens.expiresAt] = expiresAt
        it[MagicTokens.challenge] = challenge
    }
}

/** Resultado de canjear un enlace mágico. */
sealed interface MagicRedeem {
    data class Ok(val email: String) : MagicRedeem
    /** No existe, ya se usó o venció. */
    data object Invalid : MagicRedeem
    /** Se pidió desde otro teléfono (el verificador no corresponde al reto) o falta el reto. */
    data object WrongDevice : MagicRedeem
}

/**
 * Canjea (borra) el token del enlace. Atómico: la fila se bloquea (FOR UPDATE), así dos
 * canjes simultáneos no obtienen dos sesiones. Si el enlace traía reto PKCE, [verifier]
 * debe corresponder; sin reto (app anterior) solo se acepta mientras no se exija PKCE.
 */
fun consumeMagicToken(token: String, verifier: String?, requirePkce: Boolean): MagicRedeem = transaction {
    val hash = tokenHash(token)
    val row = MagicTokens.selectAll().where { MagicTokens.token eq hash }.forUpdate().firstOrNull()
        ?: return@transaction MagicRedeem.Invalid
    MagicTokens.deleteWhere { MagicTokens.token eq hash }
    if (row[MagicTokens.expiresAt].isBefore(Instant.now())) return@transaction MagicRedeem.Invalid
    val challenge = row[MagicTokens.challenge]
    when {
        challenge == null -> if (requirePkce) MagicRedeem.WrongDevice else MagicRedeem.Ok(row[MagicTokens.email])
        verifier == null || Pkce.challengeOf(verifier) != challenge -> MagicRedeem.WrongDevice
        else -> MagicRedeem.Ok(row[MagicTokens.email])
    }
}

/** PKCE del enlace mágico (ver `MagicLinkRequest` en shared). */
object Pkce {
    private val FORMA = Regex("^[A-Za-z0-9_-]{43,128}$")

    fun validFormat(s: String?): Boolean = s != null && FORMA.matches(s)

    /** base64url sin relleno del SHA-256 del verificador. */
    fun challengeOf(verifier: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

fun saveRefreshToken(token: String, email: String, officerId: String, expiresAt: Instant): Unit = transaction {
    RefreshTokens.insert {
        it[RefreshTokens.token] = tokenHash(token)
        it[RefreshTokens.email] = email
        it[RefreshTokens.officerId] = officerId
        it[RefreshTokens.expiresAt] = expiresAt
    }
}

/** Resultado de canjear un refresh token (ver [rotateRefreshToken]). */
sealed interface RefreshResult {
    data class Ok(val owner: RefreshOwner) : RefreshResult
    /** No existe, expiró o se revocó por logout/admin/suspensión. */
    data object Invalid : RefreshResult
    /** Un token ya canjeado volvió a llegar fuera del margen: probable robo. Se cortaron TODAS las sesiones de [email]. */
    data class Reused(val email: String) : RefreshResult
}

/**
 * Margen para volver a canjear un token recién rotado: con mala señal en pista la
 * respuesta del refresh se pierde y el teléfono reintenta con el mismo token (o dos
 * peticiones renuevan a la vez). Dentro del margen se emite otra sesión; fuera, es reuso.
 */
private val REFRESH_GRACE: java.time.Duration = java.time.Duration.ofMinutes(2)

/**
 * Valida y **rota** el refresh token (un solo uso). Si llega uno YA canjeado fuera del
 * margen, alguien más lo tiene: se revocan todas las sesiones de esa cuenta.
 */
fun rotateRefreshToken(token: String, now: Instant = Instant.now()): RefreshResult = transaction {
    val hash = tokenHash(token)
    val row = RefreshTokens.selectAll().where { RefreshTokens.token eq hash }.forUpdate().firstOrNull()
        ?: return@transaction RefreshResult.Invalid
    if (row[RefreshTokens.expiresAt].isBefore(now)) return@transaction RefreshResult.Invalid
    val owner = RefreshOwner(row[RefreshTokens.email], row[RefreshTokens.officerId])
    if (!row[RefreshTokens.revoked]) {
        RefreshTokens.update({ RefreshTokens.token eq hash }) {
            it[revoked] = true; it[revokedAt] = now; it[revokeReason] = "rotated"
        }
        return@transaction RefreshResult.Ok(owner)
    }
    if (row[RefreshTokens.revokeReason] != "rotated") return@transaction RefreshResult.Invalid
    val rotatedAt = row[RefreshTokens.revokedAt]
    // Margen de reintento: solo si nadie cortó las sesiones DESPUÉS de rotarlo (un "cerrar
    // sesiones" o un reuso detectado no se esquiva con un token recién rotado).
    val cut = Accounts.selectAll().where { Accounts.email eq owner.email }.firstOrNull()?.get(Accounts.sessionsRevokedAt)
    val cutAfterRotation = cut != null && rotatedAt != null && !cut.isBefore(rotatedAt)
    if (rotatedAt != null && !cutAfterRotation && !rotatedAt.plus(REFRESH_GRACE).isBefore(now)) {
        return@transaction RefreshResult.Ok(owner)
    }
    if (cutAfterRotation) return@transaction RefreshResult.Invalid
    // Reuso: se cortan TODAS las sesiones UNA vez y el token queda marcado; si vuelve a
    // llegar ya es solo inválido (no otro corte ni otro correo al titular).
    revokeSessionsTx(owner.email, "reuse", now)
    RefreshTokens.update({ RefreshTokens.token eq hash }) { it[revokeReason] = "reuse" }
    RefreshResult.Reused(owner.email)
}

/** Revoca UN refresh token (cerrar sesión en ese teléfono). Idempotente. */
fun revokeRefreshToken(token: String, reason: String = "logout"): Unit = transaction {
    RefreshTokens.update({ (RefreshTokens.token eq tokenHash(token)) and (RefreshTokens.revoked eq false) }) {
        it[revoked] = true; it[revokedAt] = Instant.now(); it[revokeReason] = reason
    }
}

/**
 * Cierra TODAS las sesiones de una cuenta: revoca sus refresh tokens y corta los access
 * tokens ya emitidos (ver [AccountGate]). Devuelve cuántos refresh tokens seguían vivos.
 */
fun revokeSessions(email: String, reason: String): Int =
    transaction { revokeSessionsTx(email, reason, Instant.now()) }.also { AccountGate.invalidate(email) }

private fun revokeSessionsTx(email: String, reason: String, now: Instant): Int {
    val n = RefreshTokens.update({ (RefreshTokens.email eq email) and (RefreshTokens.revoked eq false) }) {
        it[revoked] = true; it[revokedAt] = now; it[revokeReason] = reason
    }
    Accounts.update({ Accounts.email eq email }) { it[sessionsRevokedAt] = sessionCut(now) }
    return n
}

/** Botón de emergencia: cierra las sesiones de TODAS las cuentas. Devuelve cuántas se cortaron. */
fun revokeAllSessions(): Int = transaction {
    val now = Instant.now()
    RefreshTokens.update({ RefreshTokens.revoked eq false }) {
        it[revoked] = true; it[revokedAt] = now; it[revokeReason] = "all"
    }
    Accounts.update { it[sessionsRevokedAt] = sessionCut(now) }
}.also { AccountGate.invalidateAll() }

/** Refresh tokens vivos (no revocados ni vencidos) por cuenta — para el admin. */
fun activeSessionCounts(): Map<String, Int> = transaction {
    val now = Instant.now()
    RefreshTokens.selectAll()
        .where { (RefreshTokens.revoked eq false) and (RefreshTokens.expiresAt greater now) }
        .groupingBy { it[RefreshTokens.email] }.eachCount()
}

/**
 * El `iat` de un JWT va en segundos: el corte se redondea al segundo SIGUIENTE para que
 * un token emitido en el mismo segundo que la revocación (antes que ella) no sobreviva.
 */
private fun sessionCut(now: Instant): Instant = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1)

/** Lo que [AccountGate] necesita saber de una cuenta. null = no existe. */
fun accountGateInfo(email: String): AccountGate.Info? = transaction {
    Accounts.selectAll().where { Accounts.email eq email }.firstOrNull()?.let {
        AccountGate.Info(AccountStatus.valueOf(it[Accounts.status]), it[Accounts.officerId], it[Accounts.sessionsRevokedAt])
    }
}

/** Registro de QUIÉN invita a quién (el invitado nace como cuenta INVITED). */
object Invitations : Table("invitations") {
    val id = varchar("id", 64)
    val inviterId = varchar("inviter_id", 64)
    val inviteeEmail = varchar("invitee_email", 320)
    val createdAt = varchar("created_at", 40) // ISO Instant
    /**
     * Esta invitación creó la cuenta. Si el correo ya tenía cuenta no se toca nada y el
     * invitador nunca ve su estado: invitar no sirve para averiguar quién es oficial.
     */
    val createdAccount = bool("created_account").default(true)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Registra la invitación. La respuesta es la MISMA exista o no una cuenta con ese correo
 * (antes "ya tiene cuenta" confirmaba quién es oficial): si no existe, nace como INVITED;
 * si ya existe, no se toca. Repetir la misma invitación devuelve la que ya había.
 */
fun createInvitation(inviterId: String, email: String): Invitation = transaction {
    Invitations.selectAll().where { (Invitations.inviterId eq inviterId) and (Invitations.inviteeEmail eq email) }
        .firstOrNull()?.let { row ->
            return@transaction Invitation(
                row[Invitations.id], inviterId, email, AccountStatus.INVITED,
                kotlinx.datetime.Instant.parse(row[Invitations.createdAt]),
            )
        }
    val exists = Accounts.selectAll().where { Accounts.email eq email }.any()
    if (!exists) {
        Accounts.insert {
            it[Accounts.email] = email; it[officerId] = null; it[status] = AccountStatus.INVITED.name
        }
    }
    val id = uuidv7()
    val now = kotlinx.datetime.Clock.System.now()
    Invitations.insert {
        it[Invitations.id] = id; it[Invitations.inviterId] = inviterId
        it[inviteeEmail] = email; it[createdAt] = now.toString(); it[createdAccount] = !exists
    }
    Invitation(id, inviterId, email, AccountStatus.INVITED, now)
}

/**
 * Invitaciones hechas por un oficial, con el estatus ACTUAL de las cuentas que ellas
 * crearon (las de correos que ya tenían cuenta se quedan como enviadas).
 */
fun invitationsBy(inviterId: String): List<Invitation> = transaction {
    Invitations.selectAll().where { Invitations.inviterId eq inviterId }
        .orderBy(Invitations.createdAt to SortOrder.DESC)
        .map { row ->
            val st = if (!row[Invitations.createdAccount]) AccountStatus.INVITED
                else Accounts.selectAll().where { Accounts.email eq row[Invitations.inviteeEmail] }
                    .firstOrNull()?.get(Accounts.status)?.let(AccountStatus::valueOf) ?: AccountStatus.INVITED
            Invitation(
                id = row[Invitations.id], inviterId = row[Invitations.inviterId],
                inviteeEmail = row[Invitations.inviteeEmail], status = st,
                createdAt = kotlinx.datetime.Instant.parse(row[Invitations.createdAt]),
            )
        }
}

/** email → inviterId (quien CREÓ la cuenta), para mostrar "invitado por" en el admin. */
fun invitersByEmail(): Map<String, String> = transaction {
    Invitations.selectAll().where { Invitations.createdAccount eq true }
        .associate { it[Invitations.inviteeEmail] to it[Invitations.inviterId] }
}

fun setAccountStatus(email: String, status: AccountStatus) {
    transaction { Accounts.update({ Accounts.email eq email }) { it[Accounts.status] = status.name } }
    AccountGate.invalidate(email)
}
