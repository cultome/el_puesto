package com.alephri.elpuesto.data

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun vectoresDelEstandar() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        // Un millón de 'a' (varios bloques).
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun retoPkce() {
        // Referencia: base64url_sin_relleno(hashlib.sha256(verificador)) en Python.
        assertEquals("22DPJVP3WANA2YDP3oJs97ZRseyulV9qgMNS8RGZgSQ", Pkce.challenge("dBjftJeZ4CVP-mB92K9uhvuYy_W2p0IhcRe9h9f04jQ"))
        // El verificador nuevo: 32 bytes → 43 caracteres base64url, sin relleno.
        assertEquals(43, Pkce.newVerifier().length)
    }
}
