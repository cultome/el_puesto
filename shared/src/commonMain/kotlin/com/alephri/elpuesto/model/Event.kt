package com.alephri.elpuesto.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class EventStatus { UPCOMING, LIVE, FINISHED }

@Serializable
data class Event(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val startsOn: LocalDate,
    @ProtoNumber(4) val endsOn: LocalDate,
    @ProtoNumber(5) val circuitId: String,
    @ProtoNumber(6) val trazadoId: String = "", // compat: el primer trazado de trazadoIds
    /** Derivado de las fechas por el backend (hoy < startsOn → UPCOMING, ≤ endsOn → LIVE, después → FINISHED). */
    @ProtoNumber(7) val status: EventStatus = EventStatus.UPCOMING,
    /** Trazados en uso en el evento (un evento puede correr sobre varias configuraciones). */
    @ProtoNumber(8) val trazadoIds: List<String> = emptyList(),
    /** Campeonatos que corren en el evento (alimentan el combo de categoría del MbM). */
    @ProtoNumber(9) val championshipIds: List<String> = emptyList(),
    /**
     * ¿Los oficiales pueden registrar por honor que trabajaron el evento? PERMITIDO por
     * defecto (decisión 2026-09-25: son los menos los que se cierran, p. ej. el GP con
     * roster). Las lecturas siempre lo traen; al actualizar en el admin, null = conservar.
     */
    @ProtoNumber(10) val selfRegistration: Boolean? = null,
)

/** Asignación de un oficial a un evento. En v1 la crea el admin manualmente. */
@Serializable
data class Assignment(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val eventId: String,
    @ProtoNumber(3) val officerId: String,
    /**
     * Rol operativo en el puesto (catálogo validado por el backend: Chief Post Marshal,
     * Comunicador, Bandera Azul/Amarilla, Intervención 1-5, Bombero 1-3, Jefe/Operador
     * Telehandler). Distinto del "Área asignada" del perfil del oficial.
     */
    @ProtoNumber(4) val role: String,
    /** Debe ser un puesto de alguno de los trazados del evento. */
    @ProtoNumber(5) val puestoId: String,
    /** Derivado del puesto por el backend; no se captura. */
    @ProtoNumber(6) val puestoNumber: Int = 0,
    /**
     * Nombre para MOSTRAR del puesto ("MP 1"): el label del editor del trazado, derivado
     * por el backend AL LEER (no se captura al escribir). Vacío = legado → la UI cae a
     * "P N" con [puestoNumber].
     */
    @ProtoNumber(9) val puestoLabel: String = "",
    /** "Día completo" (default) o "Turno 1".."Turno 8". */
    @ProtoNumber(8) val shift: String? = null,
)

/** Compañero de puesto. La fila lleva al perfil del oficial. */
@Serializable
data class PuestoMate(
    @ProtoNumber(1) val officerId: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val area: Area,
    @ProtoNumber(4) val isChief: Boolean = false,
    /** Rol de su asignación en ESTE evento ("Intervención 1", "Chief Post Marshal"…); derivado. */
    @ProtoNumber(5) val role: String = "",
)

/** Sesión del cronograma en pista. */
@Serializable
data class Session(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val eventId: String,
    @ProtoNumber(3) val day: LocalDate,
    @ProtoNumber(4) val time: LocalTime,
    @ProtoNumber(5) val category: String,
    @ProtoNumber(6) val name: String,
    @ProtoNumber(7) val status: EventStatus,
    @ProtoNumber(8) val endsInMin: Int? = null, // solo cuando está LIVE
)

/** Ítem del checklist del puesto (tareas del día). */
@Serializable
data class ChecklistItem(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val text: String,
    @ProtoNumber(3) val done: Boolean = false,
)

/**
 * Asistencia de un oficial en UN día del evento (pase de lista del jefe de posición).
 * A diferencia del checklist, es REGISTRO HISTÓRICO: nada se purga; "sin marcar" =
 * ausencia de fila. [puestoId] es snapshot de la posición al momento de marcar
 * (reacomodos posteriores de asignaciones no reescriben el pasado).
 */
@Serializable
data class AttendanceEntry(
    @ProtoNumber(1) val officerId: String,
    @ProtoNumber(2) val day: LocalDate,
    @ProtoNumber(3) val present: Boolean,
    @ProtoNumber(4) val markedBy: String = "",
    @ProtoNumber(5) val markedAt: String = "", // Instant ISO-UTC
    @ProtoNumber(6) val puestoId: String = "",
)

/** Marca del pase de lista (siempre sobre HOY, lo fija el server). present null = desmarcar. */
@Serializable
data class SetAttendanceRequest(
    @ProtoNumber(1) val officerId: String,
    @ProtoNumber(2) val present: Boolean? = null,
)
