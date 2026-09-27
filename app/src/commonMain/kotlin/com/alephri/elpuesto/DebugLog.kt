package com.alephri.elpuesto

/**
 * Log de depuración que SOLO se escribe en builds de debug: en release (sin minify, los
 * `Log.d` sobreviven) el logcat de un teléfono no debe contar qué hace el oficial. El
 * mensaje se arma dentro de la lambda, así en release ni se construye. Lo enciende cada
 * plataforma al arrancar ([DebugLog.enabled]; Android con `BuildConfig.DEBUG`).
 *
 * Nunca registrar tokens, correos, datos de emergencia, ubicaciones ni texto de mensajes;
 * de las rutas HTTP, sin su query string ([pathForLog]).
 */
object DebugLog {
    var enabled: Boolean = false
    var sink: (tag: String, message: String) -> Unit = { _, _ -> }
}

inline fun logd(tag: String, message: () -> String) {
    if (DebugLog.enabled) DebugLog.sink(tag, message())
}

/** Ruta sin query string: `?q=` puede traer un nombre o un correo. */
fun pathForLog(path: String): String = path.substringBefore('?')
