package com.alephri.elpuesto.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class ConvocatoriaStatus { OPEN, CLOSED }

/**
 * Convocatoria: solo consulta en la app (re-publicada desde el sistema externo). La
 * postulación ocurre fuera de la app. Solo los campos que normalmente tenemos.
 */
@Serializable
data class Convocatoria(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val eventName: String,
    @ProtoNumber(3) val eventDate: String, // texto libre (rango de fechas del evento)
    @ProtoNumber(4) val location: String,
    @ProtoNumber(5) val registrationCloseAt: Instant, // fin de inscripciones
    @ProtoNumber(6) val cupo: Int, // un número total, no desglose por área
    @ProtoNumber(7) val indicacionesMarkdown: String, // texto libre (Markdown ligero)
    @ProtoNumber(8) val status: ConvocatoriaStatus,
    @ProtoNumber(9) val externalApplyUrl: String? = null, // "Postularme ↗" abre esto
    @ProtoNumber(10) val participated: Boolean = false, // para el historial
    // Referencia al catálogo de circuitos (navegación conectada); location sigue siendo
    // el texto mostrado. null = solo texto libre (legado/sede sin catálogo).
    @ProtoNumber(11) val circuitId: String? = null,
)
