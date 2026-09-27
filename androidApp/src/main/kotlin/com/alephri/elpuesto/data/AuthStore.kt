package com.alephri.elpuesto.data

import android.content.Context

/**
 * Sesión guardada en el teléfono. Los secretos (access token, refresh de 60 días y el
 * verificador del enlace) van CIFRADOS con una llave del Android Keystore
 * ([KeystoreCipher]); en SharedPreferences solo queda el cifrado. Lo demás (estado de la
 * cuenta, último oficial, huella del último enlace) no es secreto y va tal cual.
 */
class KeystoreAuthStore(context: Context) : AuthStore {
    private val prefs = context.getSharedPreferences("el_puesto_auth", Context.MODE_PRIVATE)

    /** Secretos ya descifrados (en memoria del proceso): no se descifra en cada petición. */
    private val secrets = HashMap<String, String?>()

    /**
     * Lee el secreto [name] (guardado como "<name>_enc"). Hasta la 1.1.1 iban en claro: si
     * aparece uno así, se cifra y el claro se borra (migración transparente). Si no se
     * puede descifrar (llave perdida: restauración, Keystore reiniciado), se borra y cuenta
     * como que no hay nada: el oficial vuelve a entrar.
     */
    private fun readSecret(name: String): String? = synchronized(secrets) {
        if (secrets.containsKey(name)) return secrets[name]
        val enc = prefs.getString("${name}_enc", null)
        val value = if (enc != null) {
            KeystoreCipher.decrypt(enc) ?: run { prefs.edit().remove("${name}_enc").apply(); null }
        } else {
            prefs.getString(name, null)?.also { plain ->
                val edit = prefs.edit().remove(name)
                KeystoreCipher.encrypt(plain)?.let { edit.putString("${name}_enc", it) }
                edit.apply()
            }
        }
        secrets[name] = value
        value
    }

    /**
     * Guarda (o borra, con null) el secreto [name] cifrado; nunca en claro. Si el Keystore
     * no responde, el valor solo vive en memoria mientras dure el proceso.
     */
    private fun writeSecret(name: String, value: String?, sync: Boolean = false) = synchronized(secrets) {
        secrets[name] = value
        val edit = prefs.edit().remove(name).remove("${name}_enc")
        value?.let { KeystoreCipher.encrypt(it) }?.let { edit.putString("${name}_enc", it) }
        if (sync) edit.commit() else edit.apply()
    }

    /** Access token (corto). */
    override var jwt: String?
        get() = readSecret("jwt")
        set(value) { writeSecret("jwt", value) }

    /** Refresh token (largo) para renovar el access sin re-login. */
    override var refresh: String?
        get() = readSecret("refresh")
        set(value) { writeSecret("refresh", value) }

    /** Último estado de cuenta que dio el servidor (para arrancar sin señal). */
    override var lastStatus: com.alephri.elpuesto.model.AccountStatus?
        get() = prefs.getString("status", null)?.let { runCatching { com.alephri.elpuesto.model.AccountStatus.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString("status", value?.name).apply() }

    /** Persiste una sesión nueva (access + refresh, si vino uno rotado). */
    override fun save(access: String, refresh: String?) {
        jwt = access
        if (refresh != null) this.refresh = refresh
    }

    /**
     * Cierra la sesión del teléfono. NO toca el verificador PKCE ([savePkce]): un enlace
     * pedido antes de cambiar de cuenta aún debe poder canjearse.
     */
    override fun clear() {
        jwt = null
        refresh = null
        prefs.edit().remove("status").apply()
    }

    // —— Enlace mágico ligado a ESTE teléfono (estilo PKCE; ver MagicLinkRequest) ——
    // El verificador se guarda al pedir el enlace (sobrevive a que Android mate la app
    // mientras el oficial abre su correo), se presenta al canjearlo y se borra al entrar.
    // Pedir otro enlace lo reemplaza; caduca a los [PKCE_TTL_MS].

    /** Guarda el verificador del enlace recién pedido (reemplaza el anterior). */
    override fun savePkce(verifier: String, email: String) {
        // Síncrono: el correo puede abrirse con el proceso ya muerto.
        writeSecret("pkce_verifier", verifier, sync = true)
        writeSecret("pkce_email", email, sync = true)
        prefs.edit().putLong("pkce_at", System.currentTimeMillis()).commit()
    }

    /** Verificador vigente del último enlace pedido (null si no hay o ya caducó). */
    override fun pkceVerifier(): String? {
        val v = readSecret("pkce_verifier") ?: return null
        val at = prefs.getLong("pkce_at", 0L)
        if (System.currentTimeMillis() - at > PKCE_TTL_MS) { clearPkce(); return null }
        return v
    }

    override fun clearPkce() {
        writeSecret("pkce_verifier", null)
        writeSecret("pkce_email", null)
        prefs.edit().remove("pkce_at").apply()
    }

    /**
     * Oficial de la última sesión abierta en este teléfono (del `sub` del JWT). Sobrevive
     * al cierre de sesión: si la siguiente es de OTRA cuenta, se borra lo local antes de
     * sincronizar.
     */
    override var lastOfficerId: String?
        get() = prefs.getString("last_officer", null)
        set(value) { prefs.edit().putString("last_officer", value).apply() }

    /** Huella del último enlace canjeado: el mismo enlace abierto otra vez se ignora. */
    override var lastLinkHash: String?
        get() = prefs.getString("last_link", null)
        set(value) { prefs.edit().putString("last_link", value).apply() }

    companion object {
        /** El enlace vence a los 15 min; el verificador se guarda el doble, por holgura. */
        const val PKCE_TTL_MS = 30 * 60_000L
    }
}
