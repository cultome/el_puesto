package com.alephri.elpuesto.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Área de un oficial. Solo se tiene UNA a la vez ("Área asignada").
 * TODO: confirmar el catálogo canónico de áreas con la fuente oficial.
 */
@Serializable
enum class Area { INTERVENCION, COMUNICACION, RECOVERY, ESCRUTINIO, MEDICO }

/** Estado de la cuenta. Gobierna el gate de la UI durante el onboarding. */
@Serializable
enum class AccountStatus { INVITED, PENDING_APPROVAL, ACTIVE, SUSPENDED }

/** Rol dentro del sistema (distinto del rol/área en pista). */
@Serializable
enum class SystemRole { OFICIAL, COORDINADOR, ADMIN }

@Serializable
data class OfficerStats(
    // Derivadas por el backend de las asignaciones (el admin solo captura activeSince).
    @ProtoNumber(1) val events: Int = 0,
    @ProtoNumber(2) val thisSeason: Int = 0,
    @ProtoNumber(3) val activeSince: Int? = null, // año
    // Solo en el perfil de OTRO oficial: eventos terminados en los que coincidieron.
    @ProtoNumber(4) val together: Int? = null,
)

@Serializable
data class Officer(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val omdaiId: Int, // número; TODO(OMDAI): ¿validado o libre?
    @ProtoNumber(3) val displayName: String,
    @ProtoNumber(4) val avatarUrl: String? = null, // avatar global, opcional
    @ProtoNumber(5) val assignedArea: Area? = null, // UNA sola área a la vez
    @ProtoNumber(6) val systemRole: SystemRole = SystemRole.OFICIAL,
    @ProtoNumber(7) val status: AccountStatus = AccountStatus.ACTIVE,
    @ProtoNumber(8) val stats: OfficerStats,
)

/**
 * Entrada del historial de eventos de un oficial (pantalla de perfil).
 * `roleLabel` ya viene formateado ("Banderas", "Jefe de puesto · Puesto 7").
 */
@Serializable
data class OfficerHistoryEntry(
    @ProtoNumber(1) val id: String, // = id del evento (el historial se DERIVA de asignaciones)
    @ProtoNumber(2) val date: LocalDate, // inicio del evento
    @ProtoNumber(3) val eventName: String,
    @ProtoNumber(4) val roleLabel: String, // rol del titular (en común: el MÍO, del visor)
    @ProtoNumber(5) val location: String, // nombre del circuito
    @ProtoNumber(6) val endsOn: LocalDate? = null,
    @ProtoNumber(7) val circuitId: String? = null,
    @ProtoNumber(8) val positionLabel: String? = null, // "MP 7" / "TH1" (en común: la MÍA)
    // Solo en "eventos en común": el rol/posición del OTRO oficial y si coincidimos en la
    // misma posición (puesto o activo) — compañeros, no solo mismo evento.
    @ProtoNumber(9) val otherRole: String? = null,
    @ProtoNumber(10) val otherPosition: String? = null,
    @ProtoNumber(11) val samePosition: Boolean = false,
    /** true = el titular la registró por honor (no viene del roster). Solo en el historial propio. */
    @ProtoNumber(12) val declared: Boolean = false,
    /** true = [positionLabel] es un puesto que el titular propuso y está en revisión. */
    @ProtoNumber(13) val positionPending: Boolean = false,
)

/** Actualización del perfil propio (no requiere aprobación del admin). */
@Serializable
data class UpdateProfileRequest(
    @ProtoNumber(1) val displayName: String,
    @ProtoNumber(2) val area: Area? = null,
)

/**
 * Información de emergencia: privada, acceso restringido + auditado. Sin seguro. No se
 * cifra (decisión 2026-09-25: no lo amerita; la protección es el acceso restringido).
 */
@Serializable
data class EmergencyInfo(
    @ProtoNumber(1) val contactName: String? = null,
    @ProtoNumber(2) val contactPhone: String? = null,
    @ProtoNumber(3) val bloodType: String? = null,
    @ProtoNumber(4) val allergies: String? = null,
)

/** Un acceso a la info de emergencia. Visible para ambas partes (auditable). */
@Serializable
data class EmergencyAccess(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val viewerId: String,
    @ProtoNumber(3) val viewerName: String,
    @ProtoNumber(4) val at: Instant,
    @ProtoNumber(5) val eventId: String? = null,
    /** null = consulta de tu emergencia; "export" = descargaste tus datos (viewer = tú). */
    @ProtoNumber(6) val kind: String? = null,
)

/** "Descargar mis datos": ¿se puede hoy? (una descarga cada 24 h). */
@Serializable
data class ExportStatus(
    @ProtoNumber(1) val available: Boolean,
    /** Cuándo podrá volver a descargar (si hoy ya lo hizo). */
    @ProtoNumber(2) val nextAt: Instant? = null,
)

/** Invitación entre pares (pre-verificación). Se lleva registro de quién invita a quién. */
@Serializable
data class Invitation(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val inviterId: String,
    @ProtoNumber(3) val inviteeEmail: String,
    @ProtoNumber(4) val status: AccountStatus,
    @ProtoNumber(5) val createdAt: Instant,
)
