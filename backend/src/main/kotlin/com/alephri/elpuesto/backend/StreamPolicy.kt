package com.alephri.elpuesto.backend

/**
 * Qué avisos del bus ([ChangeBus]) llegan a los teléfonos de los oficiales. La regla es
 * LISTA BLANCA: lo que no está aquí no sale (antes el WebSocket reenviaba a todos cualquier
 * aviso nuevo, incluidos los correos de las cuentas que tocaba el admin).
 */
object StreamPolicy {
    /**
     * Entidades del admin (`admin:<entidad>`) que la app necesita para refrescarse sola.
     * Fuera: cuentas (su id es el correo), claves, cierres de sesión, reportes, chats de
     * Control (ya viajan como `chat`), registros por honor y puestos propuestos (van
     * dirigidos a su titular) y la ingesta de posiciones.
     */
    private val ADMIN_PARA_OFICIALES = setOf(
        "event", "convocatoria", "sessions", "assignments", "checklist", "image", "agenda",
        "circuit", "trazado", "trazado-path", "puestos", "assets", "series", "championship",
        "category", "rounds", "drivers", "standings", "officer",
    )

    /** ¿Este aviso del admin puede salir al WS de los oficiales? */
    fun adminVisible(c: ChangeBus.Change): Boolean {
        val entity = c.kind.removePrefix("admin:")
        if (entity !in ADMIN_PARA_OFICIALES) return false
        // "sessions" sin id = "cerrar TODAS las sesiones" (no es el MbM de un evento).
        if (entity == "sessions" && c.id == null) return false
        return true
    }

    /**
     * El detalle del audit nunca sale, salvo el que la app lee para notificar "evento en
     * curso" (`admin:event` + `set-active` → `active=true|false`).
     */
    fun adminDetail(c: ChangeBus.Change): String? =
        c.detail?.takeIf { c.kind == "admin:event" && c.action == "set-active" && it.startsWith("active=") }

    /** Avisos del SSE de un evento (`/events/{id}/stream`): solo lo de ESE evento. */
    private val SSE_EVENTO = setOf("sessions", "checklist", "attendance", "assignments", "event")

    fun eventStreamVisible(c: ChangeBus.Change, eventId: String): Boolean = when {
        c.kind in SSE_EVENTO -> c.eventId == eventId
        // Puestos/activos de un trazado (global): el mapa del evento se refresca.
        c.kind == "map" -> c.eventId == null || c.eventId == eventId
        else -> false
    }
}
