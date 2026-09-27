package com.alephri.elpuesto.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual class SafeMap<K : Any, V : Any> actual constructor() {
    private val m = ConcurrentHashMap<K, V>()
    actual operator fun get(key: K): V? = m[key]
    actual operator fun set(key: K, value: V) { m[key] = value }
    actual fun remove(key: K): V? = m.remove(key)
    actual fun clear() = m.clear()
    actual fun getOrPut(key: K, create: () -> V): V = m.computeIfAbsent(key) { create() }
    actual fun merge(key: K, value: V, combine: (V, V) -> V): V = m.merge(key, value) { a, b -> combine(a, b) }!!
}

actual class SafeSet<T : Any> actual constructor() {
    private val s = ConcurrentHashMap.newKeySet<T>()
    actual fun add(value: T): Boolean = s.add(value)
    actual fun remove(value: T): Boolean = s.remove(value)
    actual operator fun contains(value: T): Boolean = value in s
    actual fun clear() = s.clear()
    actual fun toList(): List<T> = s.toList()
}

private val secureRandom = java.security.SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun platformHttpClient(streaming: Boolean, config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        // Streams (SSE/WebSocket/descargas): los keepalive del backend llegan cada 25 s; el
        // timeout de lectura por defecto de OkHttp (10 s) los mataría.
        if (streaming) engine { config { readTimeout(java.time.Duration.ofMinutes(2)) } }
        config()
    }
