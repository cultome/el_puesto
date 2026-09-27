package com.alephri.elpuesto.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** EVENT y CONVOCATORIA llegan del sistema; TRIP y REMINDER los agrega el usuario. */
@Serializable
enum class AgendaKind { EVENT, CONVOCATORIA, TRIP, REMINDER }

/** TRANSPORT/LODGING/REMINDER = planeación; PHOTO/NOTE = momentos de la bitácora. */
@Serializable
enum class TripItemKind { TRANSPORT, LODGING, REMINDER, PHOTO, NOTE }

@Serializable
data class AgendaEntry(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val kind: AgendaKind,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val at: Instant? = null,
    @ProtoNumber(5) val allDay: Boolean = false,
    @ProtoNumber(6) val location: String? = null,
    @ProtoNumber(7) val eventId: String? = null,
    /** true = planeación personal del oficial (editable); false = del sistema (solo lectura). */
    @ProtoNumber(8) val personal: Boolean = false,
    /** Tipo específico elegido al crear la planeación (Transporte/Hospedaje/Recordatorio). */
    @ProtoNumber(9) val tripKind: TripItemKind? = null,
    /** Convocatoria vinculada (navegación conectada); excluyente con eventId en la práctica. */
    @ProtoNumber(10) val convocatoriaId: String? = null,
    /**
     * Carrera del calendario: en una entrada de campeonato, su carrera principal (ancla
     * estable para ligar planeación); en una planeación personal, la carrera vinculada.
     */
    @ProtoNumber(11) val roundId: String? = null,
    /** Planeación: fin opcional (llegada del transporte / salida del hospedaje). */
    @ProtoNumber(12) val endsAt: Instant? = null,
    // —— Contexto derivado por el backend al leer (entradas de evento/campeonato) ——
    @ProtoNumber(13) val championshipId: String? = null,
    @ProtoNumber(14) val championshipName: String? = null,
    @ProtoNumber(15) val circuitId: String? = null,
    /** Rango del fin de semana (campeonato) o del evento operativo. */
    @ProtoNumber(16) val startsOn: LocalDate? = null,
    @ProtoNumber(17) val endsOn: LocalDate? = null,
    /** Carreras del fin de semana (una por categoría y día), en orden cronológico. */
    @ProtoNumber(18) val races: List<AgendaRace> = emptyList(),
    /**
     * true = evento operativo donde el oficial trabaja ("Trabajas este evento"): por el
     * roster o por su registro por honor ([AgendaAssignment.declared]).
     */
    @ProtoNumber(19) val assigned: Boolean = false,
    /** Resumen de la asignación del oficial (solo con [assigned]). */
    @ProtoNumber(20) val assignment: AgendaAssignment? = null,
    /** Logo del campeonato (serie) de la entrada, si tiene; las tarjetas lo usan en vez del nombre. */
    @ProtoNumber(21) val championshipEmblemUrl: String? = null,
    /**
     * Evento (creado por el admin) ligado a la entrada donde el oficial podría registrar
     * su participación por honor; null = la entrada no tiene evento.
     */
    @ProtoNumber(22) val registrationEventId: String? = null,
    /** Estado de ese registro para el oficial (solo con [registrationEventId]). */
    @ProtoNumber(23) val registration: RegistrationState? = null,
)

/** Una carrera de un fin de semana de campeonato (fila del calendario). */
@Serializable
data class AgendaRace(
    @ProtoNumber(1) val roundId: String,
    @ProtoNumber(2) val categoryName: String,
    /** Número de fecha dentro de su categoría y total de fechas de la categoría. */
    @ProtoNumber(3) val number: Int,
    @ProtoNumber(4) val total: Int,
    @ProtoNumber(5) val name: String? = null,
    @ProtoNumber(6) val date: LocalDate,
    /** true = la carrera principal del fin de semana (categoría principal del campeonato). */
    @ProtoNumber(7) val main: Boolean = false,
)

/** Resumen de la asignación del oficial en un evento (para la agenda, sin otra llamada). */
@Serializable
data class AgendaAssignment(
    @ProtoNumber(1) val position: String,
    @ProtoNumber(2) val role: String,
    @ProtoNumber(3) val shift: String = "",
    @ProtoNumber(4) val mates: Int = 0,
    /** true = la registró el propio oficial (sistema de honor), no el roster; es editable. */
    @ProtoNumber(5) val declared: Boolean = false,
    /** true = [position] es un puesto que propuso y está en revisión. */
    @ProtoNumber(6) val positionPending: Boolean = false,
)

/** Planeación de viaje del usuario, típicamente ligada a un evento. */
@Serializable
data class TripItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val eventId: String? = null,
    @ProtoNumber(3) val kind: TripItemKind,
    @ProtoNumber(4) val title: String,
    @ProtoNumber(5) val detail: String? = null,
    @ProtoNumber(6) val at: Instant? = null,
    /** true = creado por el oficial (editable); false = de la semilla/sistema. */
    @ProtoNumber(7) val personal: Boolean = false,
    /** Convocatoria vinculada (alternativa a eventId: la planeación de una postulación). */
    @ProtoNumber(8) val convocatoriaId: String? = null,
    /** Carrera del calendario vinculada (alternativa a eventId/convocatoriaId). */
    @ProtoNumber(9) val roundId: String? = null,
    /** Fin opcional: llegada del transporte / salida del hospedaje. */
    @ProtoNumber(10) val endsAt: Instant? = null,
)
