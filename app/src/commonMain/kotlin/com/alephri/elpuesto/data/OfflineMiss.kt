package com.alephri.elpuesto.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Rastreador de una carga de pantalla; viaja en el contexto de la corrutina para no
 * cambiar las firmas del repositorio.
 *
 * - [missed]: el repositorio la levanta cuando una lectura no pudo ir a la red Y tampoco
 *   estaba en la caché (lo que devuelve entonces — vacío/null — NO es el dato real). La
 *   pantalla decide si muestra "sin conexión" en vez de quedarse en skeleton para siempre.
 * - [onRead]: el repositorio le avisa qué llaves de caché leyó (lo instala
 *   `Reloader.track`), para que la pantalla se recargue sola cuando cambien.
 */
class OfflineMiss(internal val onRead: ((String) -> Unit)? = null) : AbstractCoroutineContextElement(OfflineMiss) {
    @kotlin.concurrent.Volatile var missed: Boolean = false
    companion object Key : CoroutineContext.Key<OfflineMiss>
}

/** Resultado de una carga de pantalla: el valor y si alguna lectura quedó sin red ni caché. */
data class Tracked<T>(val value: T, val missed: Boolean)

/** Corre [block] registrando si alguna lectura del repositorio no tuvo red ni caché. */
suspend fun <T> trackMiss(block: suspend () -> T): Tracked<T> = trackLoad(null, block)

/** Como [trackMiss], y además con lecturas caché-primero avisando cada llave a [onRead]. */
internal suspend fun <T> trackLoad(onRead: ((String) -> Unit)?, block: suspend () -> T): Tracked<T> {
    val tracker = OfflineMiss(onRead)
    val v = withContext(tracker) { block() }
    return Tracked(v, tracker.missed)
}

/** Lo llama el repositorio: la lectura en curso no tuvo red ni caché. */
internal suspend fun reportMiss() {
    currentCoroutineContext()[OfflineMiss]?.missed = true
}

/**
 * Marca de "hace falta lo último del servidor": jalar para refrescar, recargas por un aviso
 * en vivo, decisiones que dependen del estado actual. Dentro, red primero.
 */
private class FreshReads : AbstractCoroutineContextElement(FreshReads) {
    companion object Key : CoroutineContext.Key<FreshReads>
}

/** Corre [block] con las lecturas del repositorio yendo a la red primero (ver [reportRead]). */
suspend fun <T> freshReads(block: suspend () -> T): T = withContext(FreshReads()) { block() }

/**
 * Lo llama el repositorio antes de leer [key]: se la anota a la pantalla que carga (si la
 * hay) y responde si la lectura va CACHÉ PRIMERO (true, lo normal) o red primero (false,
 * dentro de [freshReads]).
 */
internal suspend fun reportRead(key: String): Boolean {
    val ctx = currentCoroutineContext()
    ctx[OfflineMiss]?.onRead?.invoke(key)
    return ctx[FreshReads] == null
}
