package com.alephri.elpuesto.ui.components

/**
 * Caché LRU mínima (común a Android y web): al pasarse de [maxSize] (en las unidades de
 * [sizeOf]) se descarta primero lo usado hace más tiempo. Pensada para el hilo principal
 * (imágenes ya decodificadas de [ImageMemory]).
 */
internal class LruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (K, V) -> Int = { _, _ -> 1 },
) {
    // LinkedHashMap conserva el orden de inserción: re-insertar al leer = "usado ahora".
    private val map = LinkedHashMap<K, V>()
    private var size = 0

    fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    fun put(key: K, value: V) {
        map.remove(key)?.let { size -= sizeOf(key, it) }
        map[key] = value
        size += sizeOf(key, value)
        trim()
    }

    fun remove(key: K): V? = map.remove(key)?.also { size -= sizeOf(key, it) }

    fun evictAll() {
        map.clear()
        size = 0
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (size > maxSize && it.hasNext()) {
            val e = it.next()
            size -= sizeOf(e.key, e.value)
            it.remove()
        }
    }
}
