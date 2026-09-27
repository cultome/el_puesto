package com.alephri.elpuesto.data

import kotlinx.coroutines.CoroutineDispatcher

/** Hilo para E/S (disco, decodificar): `Dispatchers.IO` en Android; en la web hay uno solo. */
expect val ioDispatcher: CoroutineDispatcher

/** Milisegundos desde la época (reloj del dispositivo). */
fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

/**
 * Mapa seguro entre hilos (Android: ConcurrentHashMap; la web tiene un solo hilo).
 * [getOrPut] es atómico: dos llamadas simultáneas reciben el MISMO valor.
 */
expect class SafeMap<K : Any, V : Any>() {
    operator fun get(key: K): V?
    operator fun set(key: K, value: V)
    fun remove(key: K): V?
    fun clear()
    fun getOrPut(key: K, create: () -> V): V
    /** Como `ConcurrentHashMap.merge`: combina con lo que había; devuelve el nuevo valor. */
    fun merge(key: K, value: V, combine: (V, V) -> V): V
}

/** Conjunto seguro entre hilos (ver [SafeMap]). */
expect class SafeSet<T : Any>() {
    /** true si no estaba (se agregó). */
    fun add(value: T): Boolean
    fun remove(value: T): Boolean
    operator fun contains(value: T): Boolean
    fun clear()
    fun toList(): List<T>
}

/** Bytes aleatorios criptográficamente seguros (SecureRandom / crypto.getRandomValues). */
expect fun secureRandomBytes(size: Int): ByteArray

/** Sin red en el dispositivo: ni se intenta la petición. */
class NoNetworkException : Exception("sin red")
