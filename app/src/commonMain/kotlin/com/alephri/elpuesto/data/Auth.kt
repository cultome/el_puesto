package com.alephri.elpuesto.data

import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.MagicLinkResponse
import kotlin.io.encoding.Base64

/**
 * Sesión guardada en el dispositivo. Android: secretos cifrados con el Keystore. Web: el
 * access token SOLO en memoria y el refresh en una cookie HttpOnly que la app no ve
 * ([refresh] = null siempre; [hasSession] lo dice una marca no secreta).
 */
interface AuthStore {
    /** Access token (corto). */
    var jwt: String?
    /** Refresh token (largo) para renovar el access sin re-login. */
    var refresh: String?
    /** Último estado de cuenta que dio el servidor (para arrancar sin señal). */
    var lastStatus: AccountStatus?
    /** Persiste una sesión nueva (access + refresh, si vino uno rotado). */
    fun save(access: String, refresh: String?)
    /** Cierra la sesión del dispositivo (NO toca el verificador PKCE). */
    fun clear()
    /** Guarda el verificador del enlace recién pedido (reemplaza el anterior). */
    fun savePkce(verifier: String, email: String)
    /** Verificador vigente del último enlace pedido (null si no hay o ya caducó). */
    fun pkceVerifier(): String?
    fun clearPkce()
    /** Oficial de la última sesión abierta aquí (sobrevive al cierre de sesión). */
    var lastOfficerId: String?
    /** Huella del último enlace canjeado: el mismo enlace abierto otra vez se ignora. */
    var lastLinkHash: String?
    /** ¿Hay una sesión guardada (sin importar si sigue vigente)? */
    val hasSession: Boolean get() = jwt != null
}

/** Orquesta el flujo de autenticación (magic link → JWT) y el gate por estado de cuenta. */
class Auth(private val store: AuthStore, private val remote: HttpRepository) {

    /**
     * Pide el enlace ligado a ESTE teléfono: genera un verificador nuevo (reemplaza el de un
     * enlace anterior), lo guarda y manda solo su huella ([Pkce.challenge]).
     */
    suspend fun sendMagicLink(email: String): MagicLinkResponse {
        val verifier = Pkce.newVerifier()
        store.savePkce(verifier, email)
        return remote.requestMagicLink(email, Pkce.challenge(verifier))
    }

    /**
     * Canjea el token del enlace presentando el verificador guardado al pedirlo, SIN tocar
     * la sesión actual (la nueva se instala con [adopt]): así, si el enlace no sirve, quien
     * ya estaba dentro sigue dentro. Rechazado = el servidor dijo que no (enlace abierto en
     * otro teléfono, vencido o ya usado); Failed = no se pudo preguntar.
     */
    suspend fun redeem(token: String): LoginResult =
        when (val r = remote.exchange(token, store.pkceVerifier())) {
            is ExchangeResult.Ok -> LoginResult.Ok(r.result.status, subjectOf(r.result.jwt), r.result, token)
            is ExchangeResult.Rejected -> LoginResult.Rejected(r.message)
            ExchangeResult.Unreachable -> LoginResult.Failed
        }

    /** Instala la sesión de un canje exitoso ([redeem]). */
    fun adopt(ok: LoginResult.Ok) {
        store.save(ok.result.jwt, ok.result.refreshToken)
        store.lastStatus = ok.status
        store.clearPkce()
        store.lastLinkHash = linkHash(ok.token)
        store.lastOfficerId = ok.officerId
        remote.clearAuthCache() // el plugin re-lee los tokens recién guardados
    }

    /** Oficial de la última sesión abierta en este teléfono (aunque ya se haya cerrado). */
    fun lastOfficerId(): String? = store.lastOfficerId

    /** ¿Es el mismo enlace con el que ya se entró? (la página puente lo reabre sola). */
    fun isLastLink(token: String): Boolean = store.lastLinkHash == linkHash(token)

    /**
     * Estado de la sesión actual (null si no hay sesión válida). Sin conexión se usa el
     * último estado que dio el servidor: abrir la app sin señal entra con la caché en vez
     * de mandarte a la pantalla de acceso.
     */
    suspend fun currentStatus(): AccountStatus? {
        if (!store.hasSession) return null
        return when (val r = remote.checkSession()) {
            is SessionCheck.Valid -> r.status.also { store.lastStatus = it }
            SessionCheck.Invalid -> null
            SessionCheck.Unreachable -> store.lastStatus
        }
    }

    /** ¿Hay una sesión guardada en el teléfono (sin importar si sigue vigente)? */
    fun hasSession(): Boolean = store.hasSession

    /**
     * Cierra la sesión: la revoca en el servidor (si hay señal), la borra del teléfono y
     * corta el socket en vivo (que se abrió con ella).
     */
    suspend fun logout() {
        remote.revokeSession()
        store.clear()
        remote.clearAuthCache()
        remote.closeStreams()
    }

    companion object {
        /** Huella de un token de enlace (para reconocer el mismo enlace abierto dos veces). */
        fun linkHash(token: String): String = Sha256.hex(token.encodeToByteArray())

        /** `sub` del access token (= id del oficial), leído sin verificar la firma. */
        fun subjectOf(jwt: String): String? = runCatching {
            val payload = jwt.split('.')[1]
            val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
            (kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)["sub"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrNull()

        /** El token de un enlace mágico es un UUID (lo genera el backend). */
        private val TOKEN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** El token SOLO si tiene la forma exacta de uno del backend; cualquier otra cosa, null. */
        fun validToken(token: String?): String? = token?.takeIf { TOKEN.matches(it) }
    }
}

/** Resultado de revisar la sesión con el servidor (ver [HttpRepository.checkSession]). */
sealed interface SessionCheck {
    class Valid(val status: AccountStatus) : SessionCheck
    /** El servidor rechazó la sesión (401/403 aun tras renovar). */
    data object Invalid : SessionCheck
    /** Sin red o el backend no respondió bien: no se sabe nada nuevo. */
    data object Unreachable : SessionCheck
}

/** Resultado de canjear un enlace mágico ([Auth.complete]). */
sealed interface LoginResult {
    /** Canje aceptado (falta [Auth.adopt]); [officerId] = `sub` del JWT (null si no se pudo leer). */
    class Ok(
        val status: AccountStatus,
        val officerId: String?,
        internal val result: com.alephri.elpuesto.model.AuthResult,
        internal val token: String,
    ) : LoginResult
    /** El servidor rechazó el enlace; [message] es su motivo (null = sin motivo). */
    class Rejected(val message: String?) : LoginResult
    /** Sin red o el servidor no respondió: el enlace sigue sirviendo, se puede reintentar. */
    data object Failed : LoginResult
}

/** Respuesta del canje en [HttpRepository.exchange]. */
sealed interface ExchangeResult {
    class Ok(val result: com.alephri.elpuesto.model.AuthResult) : ExchangeResult
    class Rejected(val message: String?) : ExchangeResult
    data object Unreachable : ExchangeResult
}

/**
 * Enlace mágico estilo PKCE (ver `MagicLinkRequest` en shared): verificador = 32 bytes
 * aleatorios en base64url sin relleno (43 caracteres); huella = base64url sin relleno del
 * SHA-256 de sus bytes ASCII.
 */
object Pkce {
    fun newVerifier(): String = b64url(secureRandomBytes(32))

    // El verificador es ASCII (base64url): sus bytes UTF-8 son los mismos.
    fun challenge(verifier: String): String = b64url(Sha256.digest(verifier.encodeToByteArray()))

    private fun b64url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}
