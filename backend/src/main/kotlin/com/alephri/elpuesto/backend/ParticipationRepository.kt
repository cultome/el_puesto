package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.EventRegistration
import com.alephri.elpuesto.model.OperationalRoles
import com.alephri.elpuesto.model.Participation
import com.alephri.elpuesto.model.RegistrationState
import com.alephri.elpuesto.model.SetParticipationRequest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Participación declarada como la ve el admin (con el oficial resuelto). */
@Serializable
data class AdminParticipationInfo(
    val id: String,
    val eventId: String,
    val officerId: String,
    val officerName: String,
    val omdaiId: Int,
    val role: String,
    val positionId: String? = null,
    val positionLabel: String = "",
    /** Días declarados (ISO); vacío = todos los días del evento. */
    val days: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    /** true = el oficial también está en el roster del evento: su asignación manda. */
    val rostered: Boolean = false,
    /** Puesto que propuso (en revisión; ver GET /admin/puesto-proposals). */
    val proposalId: String? = null,
)

/**
 * Registro por HONOR (2026-09-25): el oficial declara que trabajó un evento. Vive en
 * [ParticipationsT], APARTE de las asignaciones, para que declarar nunca dé permisos (todo
 * lo operativo se deriva del roster). Solo en eventos con autoregistro permitido
 * ([Events.selfRegistration]) y desde su primer día (CDMX); si el roster incluye al
 * oficial, el roster manda. Las lecturas de bitácora (historial, logros, agenda) lo suman
 * en [DomainRepository.pastAssignmentsTx] y la agenda.
 */
object ParticipationRepository {

    private val ZONA = java.time.ZoneId.of("America/Mexico_City")

    private fun today(): String = java.time.LocalDate.now(ZONA).toString()

    private fun now(): String = java.time.Instant.now().toString()

    sealed interface SetResult {
        data class Ok(val participation: Participation) : SetResult
        data class Rejected(val reason: String, val notFound: Boolean = false) : SetResult
    }

    /** ¿[officerId] está en el roster (asignaciones del admin) de [eventId]? */
    internal fun rosteredTx(eventId: String, officerId: String): Boolean =
        Assignments.selectAll().where { (Assignments.eventId eq eventId) and (Assignments.officerId eq officerId) }.any()

    /** Estado del autoregistro de [ev] para quien consulta (dentro de una transacción). */
    internal fun stateTx(ev: ResultRow, rostered: Boolean): RegistrationState = when {
        rostered -> RegistrationState.ROSTERED
        !ev[Events.selfRegistration] -> RegistrationState.CLOSED
        today() < ev[Events.startsOn] -> RegistrationState.NOT_YET
        else -> RegistrationState.OPEN
    }

    /** Días declarados guardados (null = todos) → lista; los que caen fuera del evento se ignoran. */
    internal fun daysOf(raw: String?, startsOn: String, endsOn: String): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it >= startsOn && it <= endsOn }.orEmpty()

    private fun ResultRow.toParticipation(labels: Map<String, String>, ev: ResultRow): Participation {
        val pos = this[ParticipationsT.positionId]
        return Participation(
            id = this[ParticipationsT.id], eventId = this[ParticipationsT.eventId], role = this[ParticipationsT.role],
            positionId = pos, positionLabel = pos?.let { labels[it] }.orEmpty(),
            days = daysOf(this[ParticipationsT.days], ev[Events.startsOn], ev[Events.endsOn]).map(LocalDate::parse),
            proposal = this[ParticipationsT.proposalId]?.let { ProposalRepository.proposalTx(it) },
        )
    }

    /** Trazados del evento (el primero = principal). */
    private fun eventTrazadosTx(ev: ResultRow): List<String> =
        EventTrazadosT.selectAll().where { EventTrazadosT.eventId eq ev[Events.id] }
            .orderBy(EventTrazadosT.ord).map { it[EventTrazadosT.trazadoId] }
            .ifEmpty { listOf(ev[Events.trazadoId]) }

    private fun mineTx(ev: ResultRow, officerId: String): Participation? {
        val row = ParticipationsT.selectAll()
            .where { (ParticipationsT.eventId eq ev[Events.id]) and (ParticipationsT.officerId eq officerId) }
            .firstOrNull() ?: return null
        val labels = DomainRepository.puestoDisplayNames(listOfNotNull(row[ParticipationsT.positionId]))
        return row.toParticipation(labels, ev)
    }

    /** Estado del registro y la participación propia; null = el evento no existe. */
    fun registration(eventId: String, officerId: String): EventRegistration? = transaction {
        val ev = Events.selectAll().where { Events.id eq eventId }.firstOrNull() ?: return@transaction null
        val trazados = eventTrazadosTx(ev)
        EventRegistration(
            eventId = eventId,
            state = stateTx(ev, rosteredTx(eventId, officerId)),
            opensOn = LocalDate.parse(ev[Events.startsOn]),
            mine = mineTx(ev, officerId),
            eventName = ev[Events.name],
            circuitId = ev[Events.circuitId],
            trazadoIds = trazados,
            endsOn = LocalDate.parse(ev[Events.endsOn]),
        )
    }

    /** Alta o edición de la participación propia (una por oficial y evento). */
    fun set(eventId: String, officerId: String, req: SetParticipationRequest): SetResult = transaction {
        val ev = Events.selectAll().where { Events.id eq eventId }.firstOrNull()
            ?: return@transaction SetResult.Rejected("evento no encontrado", notFound = true)
        when (stateTx(ev, rosteredTx(eventId, officerId))) {
            RegistrationState.ROSTERED ->
                return@transaction SetResult.Rejected("ya estás en el roster de este evento: tu participación la registró la organización")
            RegistrationState.CLOSED ->
                return@transaction SetResult.Rejected("este evento lo registra la organización (sin autoregistro)")
            RegistrationState.NOT_YET ->
                return@transaction SetResult.Rejected("el registro abre el ${ev[Events.startsOn]}, primer día del evento")
            RegistrationState.OPEN -> Unit
        }
        if (req.role !in OperationalRoles.ALL) {
            return@transaction SetResult.Rejected("rol inválido: '${req.role}'")
        }
        val position = req.positionId?.ifBlank { null }
        if (position != null && position !in AdminRepository.eventPositions(eventId)) {
            return@transaction SetResult.Rejected("la posición no es de los trazados del evento")
        }
        // Su puesto no aparece: lo propone (en revisión hasta que el admin lo resuelva).
        val proposal = req.proposal
        if (proposal != null && position != null) {
            return@transaction SetResult.Rejected("elige un puesto de la lista o propón el tuyo, no ambos")
        }
        if (proposal != null) {
            ProposalRepository.problem(proposal, eventTrazadosTx(ev))?.let { return@transaction SetResult.Rejected(it) }
        }
        val start = ev[Events.startsOn]
        val end = ev[Events.endsOn]
        val days = req.days.map { it.toString() }.distinct().sorted()
        val outside = days.filter { it < start || it > end }
        if (outside.isNotEmpty()) {
            return@transaction SetResult.Rejected("días fuera del evento ($start a $end): ${outside.joinToString()}")
        }
        // Todos los días del evento = sin lista (null): si el admin mueve las fechas, sigue
        // valiendo "todos".
        val allDays = generateSequence(java.time.LocalDate.parse(start)) { it.plusDays(1) }
            .takeWhile { it.toString() <= end }.map { it.toString() }.toList()
        val stored = days.takeIf { it.isNotEmpty() && it != allDays }?.joinToString(",")
        val existing = ParticipationsT.selectAll()
            .where { (ParticipationsT.eventId eq eventId) and (ParticipationsT.officerId eq officerId) }
            .firstOrNull()
        val at = now()
        // Propuesta: se crea o se actualiza la PENDIENTE que ya tenía; si ya no propone
        // (eligió un puesto de la lista o "sin puesto"), la pendiente se retira.
        val previousProposal = existing?.get(ParticipationsT.proposalId)
        val proposalId = if (proposal != null) {
            ProposalRepository.upsertPendingTx(previousProposal, officerId, eventId, proposal)
        } else {
            ProposalRepository.withdrawTx(previousProposal)
            null
        }
        if (existing != null) {
            ParticipationsT.update({ ParticipationsT.id eq existing[ParticipationsT.id] }) {
                it[role] = req.role; it[positionId] = position; it[ParticipationsT.days] = stored
                it[ParticipationsT.proposalId] = proposalId; it[updatedAt] = at
            }
        } else {
            ParticipationsT.insert {
                it[id] = uuidv7(); it[ParticipationsT.eventId] = eventId; it[ParticipationsT.officerId] = officerId
                it[role] = req.role; it[positionId] = position; it[ParticipationsT.days] = stored
                it[ParticipationsT.proposalId] = proposalId
                it[createdAt] = at; it[updatedAt] = at
            }
        }
        SetResult.Ok(mineTx(ev, officerId)!!)
    }.also { if (it is SetResult.Ok) ChangeBus.emit(eventId, "participation", id = officerId) }

    /** Borra la participación propia (siempre permitido: es su bitácora). */
    fun delete(eventId: String, officerId: String): Boolean = transaction {
        // Su puesto propuesto (si nadie lo revisó aún) se retira con el registro.
        ParticipationsT.selectAll().where { (ParticipationsT.eventId eq eventId) and (ParticipationsT.officerId eq officerId) }
            .firstOrNull()?.let { ProposalRepository.withdrawTx(it[ParticipationsT.proposalId]) }
        ParticipationsT.deleteWhere { (ParticipationsT.eventId eq eventId) and (ParticipationsT.officerId eq officerId) } > 0
    }.also { if (it) ChangeBus.emit(eventId, "participation", id = officerId) }

    /**
     * Participaciones declaradas de [officerId] con su evento (para sumarlas a la
     * bitácora), dentro de una transacción. El caller decide qué eventos cuentan.
     */
    internal fun declaredWithEventTx(officerId: String): List<ResultRow> =
        ParticipationsT.join(Events, org.jetbrains.exposed.sql.JoinType.INNER, onColumn = ParticipationsT.eventId, otherColumn = Events.id)
            .selectAll().where { ParticipationsT.officerId eq officerId }
            .toList()

    // —— Admin ——

    fun eventParticipations(eventId: String): List<AdminParticipationInfo> = transaction {
        val ev = Events.selectAll().where { Events.id eq eventId }.firstOrNull() ?: return@transaction emptyList()
        val rows = ParticipationsT.selectAll().where { ParticipationsT.eventId eq eventId }.toList()
        if (rows.isEmpty()) return@transaction emptyList()
        val officers = Officers.selectAll().where { Officers.id inList rows.map { it[ParticipationsT.officerId] }.toSet() }
            .associateBy { it[Officers.id] }
        val rostered = Assignments.selectAll().where { Assignments.eventId eq eventId }
            .map { it[Assignments.officerId] }.toSet()
        val labels = DomainRepository.puestoDisplayNames(rows.mapNotNull { it[ParticipationsT.positionId] })
        val proposed = ProposalRepository.pendingLabelsTx(rows.mapNotNull { it[ParticipationsT.proposalId] })
        rows.map { r ->
            val o = officers[r[ParticipationsT.officerId]]
            AdminParticipationInfo(
                id = r[ParticipationsT.id], eventId = eventId, officerId = r[ParticipationsT.officerId],
                officerName = o?.get(Officers.displayName) ?: "—", omdaiId = o?.get(Officers.omdaiId) ?: 0,
                role = r[ParticipationsT.role], positionId = r[ParticipationsT.positionId],
                positionLabel = r[ParticipationsT.positionId]?.let { labels[it] }
                    ?: r[ParticipationsT.proposalId]?.let { proposed[it] }?.let { "$it (propuesto)" }.orEmpty(),
                proposalId = r[ParticipationsT.proposalId],
                days = daysOf(r[ParticipationsT.days], ev[Events.startsOn], ev[Events.endsOn]),
                createdAt = r[ParticipationsT.createdAt], updatedAt = r[ParticipationsT.updatedAt],
                rostered = r[ParticipationsT.officerId] in rostered,
            )
        }.sortedWith(compareBy({ it.positionLabel.isEmpty() }, { it.positionLabel }, { it.officerName }))
    }

    fun deleteById(id: String): ChangeSummary {
        val (eventId, officerId) = transaction {
            val row = ParticipationsT.selectAll().where { ParticipationsT.id eq id }.firstOrNull() ?: return@transaction null
            ParticipationsT.deleteWhere { ParticipationsT.id eq id }
            row[ParticipationsT.eventId] to row[ParticipationsT.officerId]
        } ?: return ChangeSummary("none", "participation", id, detail = "no existe")
        // Solo a su titular (el WS lo filtra por id): nadie más se entera de quién se registró.
        ChangeBus.emit(eventId, "participation", id = officerId)
        return ChangeSummary("deleted", "participation", id)
    }

    fun exists(id: String): Boolean = transaction {
        ParticipationsT.selectAll().where { ParticipationsT.id eq id }.any()
    }
}
