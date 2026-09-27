package com.alephri.elpuesto.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Catálogo de roles operativos de una posición en un evento: lo validan el roster del
 * admin (asignaciones) y el registro por honor (participaciones); la app lo usa para
 * preguntar "¿qué rol desempeñaste?". Distinto del "Área asignada" del perfil ([Area]).
 */
object OperationalRoles {
    const val CHIEF = "Chief Post Marshal"

    val ALL: List<String> = listOf(CHIEF, "Comunicador", "Bandera Azul", "Bandera Amarilla") +
        (1..5).map { "Intervención $it" } + (1..3).map { "Bombero $it" } +
        listOf("Jefe Telehandler", "Operador Telehandler", "Jefe IFRT", "Operador IFRT", "Operador HIAB") +
        // 2026-09-24 (roster GP México): TSP = "Panel de luz" (antes "Track Safety
        // Personnel", renombrado 2026-09-25); Driver Rider = moto que traslada pilotos
        // accidentados; Coordinador de zona = posición sin mapa.
        listOf("Panel de luz", "Driver Rider", "Coordinador de zona")

    /**
     * Roles que hacen JEFE de su posición. En el ROSTER de ahí derivan el guard "máx. uno
     * por posición", el chip CMP y el acceso a emergencia; una participación DECLARADA con
     * uno de estos roles no da nada de eso (solo cuenta para su historia y sus logros).
     */
    val CHIEFS: Set<String> = setOf(CHIEF, "Jefe Telehandler", "Jefe IFRT")
}

/**
 * Participación DECLARADA por el propio oficial (sistema de honor, 2026-09-25): "yo
 * trabajé en este evento". Vive APARTE de las asignaciones del roster: alimenta su
 * bitácora (historial, pasaporte, logros, eventos en común, agenda) pero NUNCA da permisos
 * (Modo evento, chats, emergencia, pase de lista y ubicación salen solo del roster). Solo
 * en eventos con autoregistro permitido ([Event.selfRegistration]); si el roster del admin
 * incluye al oficial, el roster manda.
 */
@Serializable
data class Participation(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val eventId: String,
    @ProtoNumber(3) val role: String,
    /** Puesto o activo de un trazado del evento; null = sin puesto (no aparece / sin puesto fijo). */
    @ProtoNumber(4) val positionId: String? = null,
    /** Nombre para mostrar de la posición ("MP 7"), derivado al leer; vacío = sin puesto. */
    @ProtoNumber(5) val positionLabel: String = "",
    /** Días que trabajó; vacío = todos los días del evento. */
    @ProtoNumber(6) val days: List<LocalDate> = emptyList(),
    /**
     * Puesto que PROPUSO porque no aparecía en el trazado (en revisión: solo lo ve él
     * hasta que el admin lo aprueba; entonces pasa a [positionId]). Excluyente con él.
     */
    @ProtoNumber(7) val proposal: PuestoProposal? = null,
)

/** Estado de un puesto propuesto por un oficial. */
@Serializable
enum class ProposalStatus {
    /** En revisión: solo lo ve quien lo propuso. */
    PENDING,

    /** El admin lo verificó y lo publicó como puesto nuevo del trazado. */
    APPROVED,

    /** Era un puesto que ya existía (o lo propusieron otros): se juntó con ese. */
    MERGED,

    /** No procedió: la participación queda sin puesto. */
    REJECTED,
}

/**
 * Puesto PROPUESTO por un oficial al registrarse (fase 3 del registro por honor): el
 * trazado no traía su puesto, así que lo colocó en el mapa con su número. Queda en
 * revisión y SOLO lo ve él hasta que el admin lo aprueba (se publica para todos),
 * lo fusiona con uno existente o lo rechaza.
 */
@Serializable
data class PuestoProposal(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val trazadoId: String,
    /** Número o nombre del puesto como lo conoce el oficial ("7", "MP 7"). */
    @ProtoNumber(3) val label: String,
    /** Dónde estaba (null = trazado sin mapa: solo el número). */
    @ProtoNumber(4) val lat: Double? = null,
    @ProtoNumber(5) val lon: Double? = null,
    /** Derivado de lat/lon con el marco del trazado (para dibujarlo). */
    @ProtoNumber(6) val point: MapPoint? = null,
    @ProtoNumber(7) val status: ProposalStatus = ProposalStatus.PENDING,
)

/** El puesto que el oficial propone al registrarse (ver [PuestoProposal]). */
@Serializable
data class NewPuestoProposal(
    @ProtoNumber(1) val trazadoId: String,
    @ProtoNumber(2) val label: String,
    @ProtoNumber(3) val lat: Double? = null,
    @ProtoNumber(4) val lon: Double? = null,
)

/** Alta o edición de la participación propia (`PUT /events/{id}/participation`). */
@Serializable
data class SetParticipationRequest(
    @ProtoNumber(1) val role: String,
    @ProtoNumber(2) val positionId: String? = null,
    /** Días que trabajó (dentro del evento); vacío = todos. */
    @ProtoNumber(3) val days: List<LocalDate> = emptyList(),
    /** Su puesto no aparece: lo propone (excluyente con [positionId]). */
    @ProtoNumber(4) val proposal: NewPuestoProposal? = null,
)

/** Si quien consulta puede registrarse por honor en un evento. */
@Serializable
enum class RegistrationState {
    /** Puede registrarse (o editar su registro). */
    OPEN,

    /** El autoregistro abre el primer día del evento. */
    NOT_YET,

    /** La organización registra este evento (autoregistro no permitido). */
    CLOSED,

    /** Ya está en el roster del evento: su participación la registró la organización. */
    ROSTERED,
}

/** Estado del autoregistro de un evento para quien consulta (`GET /events/{id}/registration`). */
@Serializable
data class EventRegistration(
    @ProtoNumber(1) val eventId: String,
    @ProtoNumber(2) val state: RegistrationState,
    /** Primer día en que se puede registrar (el inicio del evento). */
    @ProtoNumber(3) val opensOn: LocalDate,
    /** Su participación declarada, si ya se registró. */
    @ProtoNumber(4) val mine: Participation? = null,
    // Lo que la pantalla de registro necesita del evento (una sola lectura, cacheable
    // para registrarse sin señal): nombre, circuito, trazados y último día.
    @ProtoNumber(5) val eventName: String = "",
    @ProtoNumber(6) val circuitId: String = "",
    /** Trazados del evento (el primero = principal): sus puestos y activos son las posiciones. */
    @ProtoNumber(7) val trazadoIds: List<String> = emptyList(),
    @ProtoNumber(8) val endsOn: LocalDate? = null,
)
