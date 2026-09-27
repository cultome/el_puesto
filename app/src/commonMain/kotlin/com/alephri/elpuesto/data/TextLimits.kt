package com.alephri.elpuesto.data

/**
 * Largo máximo de lo que el oficial captura: los mismos topes que aplica el servidor (más
 * largo = 400 y, si iba por la cola, se descartaría). Los campos los cortan al escribir
 * (`AppTextField(maxLength = …)`). Nombres de oficial y de chat: `NameRules` (shared).
 */
object TextLimits {
    const val MESSAGE = 2000
    const val CAPTION = 1000
    /** Título de la planeación; también el texto de una nota o el pie de una foto de bitácora. */
    const val TRIP_TITLE = 200
    const val TRIP_DETAIL = 500
    const val CHAT_DESCRIPTION = 300
    /** Motivo de un reporte (mensaje, chat o perfil). */
    const val REASON = 300
    const val EMERGENCY_CONTACT = 80
    const val PHONE = 40
    const val EMERGENCY_TEXT = 300
    const val EMAIL = 254
    const val SEARCH = 80
}
