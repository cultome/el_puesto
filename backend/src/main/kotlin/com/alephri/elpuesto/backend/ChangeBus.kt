package com.alephri.elpuesto.backend

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bus de cambios en memoria (un solo proceso): las mutaciones relevantes emiten qué
 * cambió y los streams SSE del admin lo reenvían al navegador (patrón notificar-y-refetch:
 * el evento solo dice QUÉ cambió; el cliente re-pide esa sección). Si algún día hay varias
 * instancias del backend, este bus se respalda con Postgres LISTEN/NOTIFY.
 */
object ChangeBus {
    /**
     * [eventId] null = cambio global (p. ej. puestos de un trazado: afecta a cualquier
     * evento que lo use). Los cambios administrativos (kind `admin:<entidad>`, emitidos
     * desde la auditoría) además llevan [id] (entityId), [action] y [detail] para que el
     * cliente decida qué refrescar/notificar.
     */
    data class Change(
        val eventId: String?,
        val kind: String,
        val chatId: String? = null,
        val id: String? = null,
        val action: String? = null,
        val detail: String? = null,
    )

    /**
     * Cada suscriptor lee a su ritmo: si uno se atrasa (un stream que no lee), pierde SUS
     * avisos más viejos en vez de llenar el búfer y dejar sin avisos a todos los demás.
     */
    private val _flow = MutableSharedFlow<Change>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val flow = _flow.asSharedFlow()

    fun emit(
        eventId: String?,
        kind: String,
        chatId: String? = null,
        id: String? = null,
        action: String? = null,
        detail: String? = null,
    ) {
        _flow.tryEmit(Change(eventId, kind, chatId, id, action, detail))
    }
}
