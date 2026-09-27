package com.alephri.elpuesto.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de los secretos de sesión guardados en el teléfono (tokens y verificador del
 * enlace): AES-256-GCM con una llave que vive en el Android Keystore y nunca sale de él
 * (ni la app puede leerla; tampoco viaja en respaldos). Sin exigir huella/PIN: la sesión
 * debe poder renovarse sola en segundo plano (servicio de ubicación, recordatorios).
 *
 * Formato guardado: `v1:<iv base64>:<cifrado+etiqueta base64>`. Cualquier falla (llave
 * perdida tras restaurar el teléfono o reiniciar el Keystore, dato corrupto) devuelve null:
 * quien llama lo trata como "no hay sesión" y el oficial vuelve a entrar; nunca truena.
 */
internal object KeystoreCipher {
    private const val ALIAS = "el_puesto_auth"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** La llave del Keystore; se crea la primera vez. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Cifra [plain]; null si el Keystore no está disponible. */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV aleatorio que genera el propio Keystore
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "v1:" + b64(cipher.iv) + ":" + b64(sealed)
    }.getOrNull()

    /** Descifra lo que produjo [encrypt]; null si no se puede (llave perdida, dato alterado). */
    fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, unb64(parts[1])))
        String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
