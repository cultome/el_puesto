package com.alephri.elpuesto.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// El navegador corre la app en UN hilo: no hay E/S bloqueante ni carreras entre hilos.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

actual class SafeMap<K : Any, V : Any> actual constructor() {
    private val m = HashMap<K, V>()
    actual operator fun get(key: K): V? = m[key]
    actual operator fun set(key: K, value: V) { m[key] = value }
    actual fun remove(key: K): V? = m.remove(key)
    actual fun clear() = m.clear()
    actual fun getOrPut(key: K, create: () -> V): V = m.getOrPut(key, create)
    actual fun merge(key: K, value: V, combine: (V, V) -> V): V {
        val next = m[key]?.let { combine(it, value) } ?: value
        m[key] = next
        return next
    }
}

actual class SafeSet<T : Any> actual constructor() {
    private val s = HashSet<T>()
    actual fun add(value: T): Boolean = s.add(value)
    actual fun remove(value: T): Boolean = s.remove(value)
    actual operator fun contains(value: T): Boolean = value in s
    actual fun clear() = s.clear()
    actual fun toList(): List<T> = s.toList()
}

/** Un byte de `crypto.getRandomValues` (generador criptográfico del navegador). */
private fun jsRandomByte(): Int = js("crypto.getRandomValues(new Uint8Array(1))[0]")

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size) { jsRandomByte().toByte() }

actual fun platformHttpClient(streaming: Boolean, config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) { config() }
