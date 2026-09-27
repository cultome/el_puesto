package com.alephri.elpuesto.backend

import java.security.SecureRandom

private val idRandom = SecureRandom()

/**
 * UUID v7 (RFC 9562): 48 bits de timestamp en ms + bits aleatorios. Ordenable por
 * tiempo de creación (amable con los índices de Postgres). Los IDs de entidad los
 * asigna SIEMPRE el servidor con esta función; el cliente nunca los elige.
 */
fun uuidv7(): String {
    val b = ByteArray(16)
    idRandom.nextBytes(b)
    val ts = System.currentTimeMillis()
    b[0] = (ts shr 40).toByte(); b[1] = (ts shr 32).toByte(); b[2] = (ts shr 24).toByte()
    b[3] = (ts shr 16).toByte(); b[4] = (ts shr 8).toByte(); b[5] = ts.toByte()
    b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = b.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
