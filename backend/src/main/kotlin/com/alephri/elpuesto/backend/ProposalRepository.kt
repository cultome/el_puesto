package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.NewPuestoProposal
import com.alephri.elpuesto.model.ProposalStatus
import com.alephri.elpuesto.model.PuestoProposal
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Una propuesta como la ve el admin (con quién la hizo y en qué evento). */
@Serializable
data class AdminProposalInfo(
    val id: String,
    val trazadoId: String,
    val label: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val point: MapPoint? = null,
    val status: String,
    val officerId: String,
    val officerName: String,
    val omdaiId: Int,
    val eventId: String,
    val eventName: String,
    val createdAt: String,
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
    val resolvedPuestoId: String? = null,
)

/** Puesto existente del trazado cerca de (o con la misma etiqueta que) un grupo propuesto. */
@Serializable
data class AdminNearbyPuesto(val id: String, val label: String, val distanceM: Int? = null, val sameLabel: Boolean = false)

/**
 * Propuestas del MISMO puesto (misma etiqueta normalizada: "7" = "MP 7" = "Puesto 7")
 * en un trazado: que varios oficiales coincidan es la mejor verificación. Se revisan juntas.
 */
@Serializable
data class AdminProposalGroup(
    val trazadoId: String,
    val trazadoName: String,
    val circuitId: String,
    val circuitName: String,
    /** La etiqueta más propuesta del grupo (sugerencia para el puesto nuevo). */
    val label: String,
    /** Promedio de las ubicaciones propuestas (null = nadie la marcó en el mapa). */
    val lat: Double? = null,
    val lon: Double? = null,
    val point: MapPoint? = null,
    val proposals: List<AdminProposalInfo>,
    /** Puestos existentes con la misma etiqueta o cercanos (candidatos a "fusionar"). */
    val nearby: List<AdminNearbyPuesto> = emptyList(),
)

@Serializable
data class ApproveProposalsRequest(
    val ids: List<String>,
    /** Etiqueta del puesto nuevo (la que verá todo el mundo). */
    val label: String,
    /** Número del puesto; sin él: el de la etiqueta si es entero, si no el siguiente libre. */
    val number: Int? = null,
    /** Ubicación final; sin ella: el promedio de lo propuesto (sin nada: fuera del mapa). */
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class MergeProposalsRequest(val ids: List<String>, val puestoId: String)

@Serializable
data class RejectProposalsRequest(val ids: List<String>, val reason: String? = null)

/**
 * Puestos PROPUESTOS por los oficiales (registro por honor, fase 3): si el trazado no
 * trae su puesto, el oficial lo coloca en el mapa con su número. Queda en revisión y solo
 * lo ve él; el admin lo aprueba (se crea el puesto y se publica para todos), lo fusiona
 * con uno existente o lo rechaza. Al resolverse, las participaciones que lo usaban pasan
 * solas al puesto real (o quedan sin puesto si se rechazó).
 */
object ProposalRepository {

    private fun now(): String = java.time.Instant.now().toString()

    /** "MP 7" / "Puesto 7" / "p7" → "7": la llave para reconocer el mismo puesto. */
    internal fun labelKey(label: String): String =
        label.trim().lowercase()
            .replace(Regex("""^(mp|puesto|p)\s*[-#.]?\s*(?=\d)"""), "")
            .replace(Regex("""[^\p{L}\p{N}.]"""), "")

    /** Marco (lat/lon → punto normalizado) del trazado; null si no está dibujado. */
    private fun frameOf(trazadoId: String): ((Double, Double) -> MapPoint)? =
        AdminRepository.geoFrame(AdminRepository.trazadoGeo(trazadoId))

    private fun ResultRow.toProposal(frame: ((Double, Double) -> MapPoint)?): PuestoProposal {
        val lat = this[PuestoProposalsT.lat]
        val lon = this[PuestoProposalsT.lon]
        return PuestoProposal(
            id = this[PuestoProposalsT.id], trazadoId = this[PuestoProposalsT.trazadoId], label = this[PuestoProposalsT.label],
            lat = lat, lon = lon,
            point = if (lat != null && lon != null) frame?.invoke(lat, lon) else null,
            status = ProposalStatus.valueOf(this[PuestoProposalsT.status]),
        )
    }

    /** La propuesta de una participación (con su punto), dentro de una transacción. */
    internal fun proposalTx(id: String): PuestoProposal? =
        PuestoProposalsT.selectAll().where { PuestoProposalsT.id eq id }.firstOrNull()
            ?.let { it.toProposal(frameOf(it[PuestoProposalsT.trazadoId])) }

    /** Etiquetas de propuestas PENDIENTES por id (para historial/agenda del proponente). */
    internal fun pendingLabelsTx(ids: Collection<String>): Map<String, String> =
        if (ids.isEmpty()) emptyMap()
        else PuestoProposalsT.selectAll()
            .where { (PuestoProposalsT.id inList ids.toSet()) and (PuestoProposalsT.status eq ProposalStatus.PENDING.name) }
            .associate { it[PuestoProposalsT.id] to it[PuestoProposalsT.label] }

    /** Valida una propuesta del oficial para [eventTrazados]: null = válida. */
    internal fun problem(p: NewPuestoProposal, eventTrazados: Collection<String>): String? {
        if (p.trazadoId !in eventTrazados) return "el trazado no es de este evento"
        val label = p.label.trim()
        if (label.isEmpty() || label.length > 20) return "el número o nombre de tu puesto va de 1 a 20 caracteres"
        if ((p.lat == null) != (p.lon == null)) return "ubicación incompleta"
        if (p.lat != null && (p.lat!! !in -90.0..90.0 || p.lon!! !in -180.0..180.0)) return "ubicación inválida"
        return null
    }

    /**
     * Crea o actualiza (si ya tenía una PENDIENTE) la propuesta de una participación.
     * Devuelve su id. Dentro de la transacción del registro.
     */
    internal fun upsertPendingTx(existingId: String?, officerId: String, eventId: String, p: NewPuestoProposal): String {
        val at = now()
        val pending = existingId?.let { id ->
            PuestoProposalsT.selectAll()
                .where { (PuestoProposalsT.id eq id) and (PuestoProposalsT.status eq ProposalStatus.PENDING.name) }.firstOrNull()
        }
        if (pending != null) {
            PuestoProposalsT.update({ PuestoProposalsT.id eq pending[PuestoProposalsT.id] }) {
                it[trazadoId] = p.trazadoId; it[label] = p.label.trim(); it[lat] = p.lat; it[lon] = p.lon; it[updatedAt] = at
            }
            return pending[PuestoProposalsT.id]
        }
        val id = uuidv7()
        PuestoProposalsT.insert {
            it[PuestoProposalsT.id] = id; it[trazadoId] = p.trazadoId; it[PuestoProposalsT.officerId] = officerId
            it[PuestoProposalsT.eventId] = eventId; it[label] = p.label.trim(); it[lat] = p.lat; it[lon] = p.lon
            it[status] = ProposalStatus.PENDING.name; it[createdAt] = at; it[updatedAt] = at
        }
        return id
    }

    /** El oficial ya no la propone (eligió otro puesto o quitó su registro): se retira si nadie la revisó. */
    internal fun withdrawTx(id: String?) {
        if (id == null) return
        PuestoProposalsT.deleteWhere { (PuestoProposalsT.id eq id) and (PuestoProposalsT.status eq ProposalStatus.PENDING.name) }
    }

    // —— Admin: cola de revisión ——

    /** Propuestas agrupadas por trazado y etiqueta (default: las pendientes). */
    fun groups(status: ProposalStatus = ProposalStatus.PENDING): List<AdminProposalGroup> = transaction {
        val rows = PuestoProposalsT.selectAll().where { PuestoProposalsT.status eq status.name }.toList()
        if (rows.isEmpty()) return@transaction emptyList()
        val officers = Officers.selectAll().where { Officers.id inList rows.map { it[PuestoProposalsT.officerId] }.toSet() }
            .associateBy { it[Officers.id] }
        val events = Events.selectAll().where { Events.id inList rows.map { it[PuestoProposalsT.eventId] }.toSet() }
            .associate { it[Events.id] to it[Events.name] }
        val trazados = TrazadosT.selectAll().where { TrazadosT.id inList rows.map { it[PuestoProposalsT.trazadoId] }.toSet() }
            .associateBy { it[TrazadosT.id] }
        val circuits = CircuitsT.selectAll().where { CircuitsT.id inList trazados.values.map { it[TrazadosT.circuitId] }.toSet() }
            .associate { it[CircuitsT.id] to it[CircuitsT.name] }
        rows.groupBy { it[PuestoProposalsT.trazadoId] }.flatMap { (tz, list) ->
            val frame = frameOf(tz)
            val t = trazados[tz]
            val puestos = PuestosT.selectAll().where { (PuestosT.trazadoId eq tz) and PuestosT.deletedAt.isNull() }.toList()
            list.groupBy { labelKey(it[PuestoProposalsT.label]) }.map { (key, g) ->
                val infos = g.sortedBy { it[PuestoProposalsT.createdAt] }.map { r ->
                    val o = officers[r[PuestoProposalsT.officerId]]
                    val pr = r.toProposal(frame)
                    AdminProposalInfo(
                        id = pr.id, trazadoId = tz, label = pr.label, lat = pr.lat, lon = pr.lon, point = pr.point,
                        status = r[PuestoProposalsT.status],
                        officerId = r[PuestoProposalsT.officerId], officerName = o?.get(Officers.displayName) ?: "—",
                        omdaiId = o?.get(Officers.omdaiId) ?: 0,
                        eventId = r[PuestoProposalsT.eventId], eventName = events[r[PuestoProposalsT.eventId]] ?: "—",
                        createdAt = r[PuestoProposalsT.createdAt],
                        reviewedBy = r[PuestoProposalsT.reviewedBy], reviewedAt = r[PuestoProposalsT.reviewedAt],
                        reviewNote = r[PuestoProposalsT.reviewNote], resolvedPuestoId = r[PuestoProposalsT.resolvedPuestoId],
                    )
                }
                val located = infos.filter { it.lat != null && it.lon != null }
                val lat = located.takeIf { it.isNotEmpty() }?.map { it.lat!! }?.average()
                val lon = located.takeIf { it.isNotEmpty() }?.map { it.lon!! }?.average()
                val nearby = puestos.mapNotNull { p ->
                    val label = p[PuestosT.label]?.ifBlank { null } ?: "P ${p[PuestosT.number]}"
                    val same = labelKey(label) == key
                    val d = if (lat != null && lon != null && p[PuestosT.lat] != null && p[PuestosT.lon] != null) {
                        metros(lat, lon, p[PuestosT.lat]!!, p[PuestosT.lon]!!)
                    } else null
                    if (same || (d != null && d <= NEARBY_M)) AdminNearbyPuesto(p[PuestosT.id], label, d?.toInt(), same) else null
                }.sortedWith(compareBy({ !it.sameLabel }, { it.distanceM ?: Int.MAX_VALUE })).take(5)
                AdminProposalGroup(
                    trazadoId = tz, trazadoName = t?.get(TrazadosT.name) ?: "—",
                    circuitId = t?.get(TrazadosT.circuitId).orEmpty(), circuitName = t?.let { circuits[it[TrazadosT.circuitId]] } ?: "—",
                    label = infos.groupingBy { it.label.trim() }.eachCount().maxByOrNull { it.value }?.key ?: infos.first().label,
                    lat = lat, lon = lon, point = if (lat != null && lon != null) frame?.invoke(lat, lon) else null,
                    proposals = infos, nearby = nearby,
                )
            }
        }.sortedWith(compareByDescending<AdminProposalGroup> { it.proposals.size }.thenBy { it.proposals.minOf { p -> p.createdAt } })
    }

    /** Las propuestas PENDIENTES [ids] de un mismo trazado; o el motivo por el que no. */
    private fun pendingOrProblem(ids: List<String>): Pair<List<ResultRow>, String?> {
        if (ids.isEmpty()) return emptyList<ResultRow>() to "ids requerido (al menos una propuesta)"
        val rows = PuestoProposalsT.selectAll().where { PuestoProposalsT.id inList ids.distinct() }.toList()
        val missing = ids.distinct() - rows.map { it[PuestoProposalsT.id] }.toSet()
        if (missing.isNotEmpty()) return rows to "no existen: ${missing.joinToString()}"
        val resolved = rows.filter { it[PuestoProposalsT.status] != ProposalStatus.PENDING.name }
        if (resolved.isNotEmpty()) return rows to "ya se resolvieron: ${resolved.joinToString { it[PuestoProposalsT.id] }}"
        if (rows.map { it[PuestoProposalsT.trazadoId] }.distinct().size > 1) return rows to "las propuestas son de trazados distintos: revísalas por separado"
        return rows to null
    }

    fun approveProblem(req: ApproveProposalsRequest): String? = transaction {
        val (rows, problem) = pendingOrProblem(req.ids)
        if (problem != null) return@transaction problem
        val label = req.label.trim()
        if (label.isEmpty() || label.length > 40) return@transaction "label requerido (1 a 40 caracteres)"
        if (req.number != null && req.number < 1) return@transaction "number debe ser >= 1"
        if ((req.lat == null) != (req.lon == null)) return@transaction "lat y lon van juntas"
        val tz = rows.first()[PuestoProposalsT.trazadoId]
        val taken = PuestosT.selectAll().where { (PuestosT.trazadoId eq tz) and PuestosT.deletedAt.isNull() }
            .any { (it[PuestosT.label]?.ifBlank { null } ?: "P ${it[PuestosT.number]}").equals(label, ignoreCase = true) }
        if (taken) "ya existe el puesto '$label' en el trazado: usa fusionar (merge) con ese puesto" else null
    }

    /** Crea el puesto nuevo con las propuestas [ApproveProposalsRequest.ids] y mueve sus participaciones. */
    fun approve(req: ApproveProposalsRequest, actor: String): ChangeSummary {
        approveProblem(req)?.let { return ChangeSummary("none", "puesto-proposal", req.ids.firstOrNull(), detail = it) }
        val (puestoId, moved) = transaction {
            val rows = PuestoProposalsT.selectAll().where { PuestoProposalsT.id inList req.ids.distinct() }
                .orderBy(PuestoProposalsT.createdAt).toList()
            val tz = rows.first()[PuestoProposalsT.trazadoId]
            val located = rows.filter { it[PuestoProposalsT.lat] != null && it[PuestoProposalsT.lon] != null }
            val lat = req.lat ?: located.takeIf { it.isNotEmpty() }?.map { it[PuestoProposalsT.lat]!! }?.average()
            val lon = req.lon ?: located.takeIf { it.isNotEmpty() }?.map { it[PuestoProposalsT.lon]!! }?.average()
            val label = req.label.trim()
            val live = PuestosT.selectAll().where { (PuestosT.trazadoId eq tz) and PuestosT.deletedAt.isNull() }.toList()
            val number = req.number ?: label.toIntOrNull()?.takeIf { it >= 1 }
                ?: ((live.maxOfOrNull { it[PuestosT.number] } ?: 0) + 1)
            val onMap = lat != null && lon != null
            val pt = if (onMap) frameOf(tz)?.invoke(lat!!, lon!!) ?: MapPoint(0.5f, 0.5f) else MapPoint(0f, 0f)
            val id = uuidv7()
            PuestosT.insert {
                it[PuestosT.id] = id; it[trazadoId] = tz; it[PuestosT.number] = number; it[PuestosT.label] = label
                it[x] = pt.x; it[y] = pt.y; it[PuestosT.lat] = lat; it[PuestosT.lon] = lon; it[PuestosT.onMap] = onMap
            }
            id to resolveTx(rows, id, actor, note = null, firstStatus = ProposalStatus.APPROVED)
        }
        ChangeBus.emit(null, "map")
        notifyOwners(moved)
        return ChangeSummary(
            "created", "puesto-proposal", puestoId,
            detail = "puesto '${req.label.trim()}' creado con ${req.ids.distinct().size} propuesta(s); ${moved.size} participación(es) movidas al puesto",
        )
    }

    fun mergeProblem(req: MergeProposalsRequest): String? = transaction {
        val (rows, problem) = pendingOrProblem(req.ids)
        if (problem != null) return@transaction problem
        val tz = rows.first()[PuestoProposalsT.trazadoId]
        val puesto = PuestosT.selectAll().where { (PuestosT.id eq req.puestoId) and PuestosT.deletedAt.isNull() }.firstOrNull()
            ?: return@transaction "el puesto '${req.puestoId}' no existe (o está archivado)"
        if (puesto[PuestosT.trazadoId] != tz) "el puesto es de otro trazado" else null
    }

    /** Las propuestas eran un puesto existente: se juntan con él y sus participaciones pasan a ese puesto. */
    fun merge(req: MergeProposalsRequest, actor: String): ChangeSummary {
        mergeProblem(req)?.let { return ChangeSummary("none", "puesto-proposal", req.ids.firstOrNull(), detail = it) }
        val moved = transaction {
            val rows = PuestoProposalsT.selectAll().where { PuestoProposalsT.id inList req.ids.distinct() }.toList()
            resolveTx(rows, req.puestoId, actor, note = null, firstStatus = ProposalStatus.MERGED)
        }
        notifyOwners(moved)
        return ChangeSummary("updated", "puesto-proposal", req.puestoId, detail = "${req.ids.distinct().size} propuesta(s) fusionadas; ${moved.size} participación(es) movidas al puesto")
    }

    fun rejectProblem(req: RejectProposalsRequest): String? = transaction { pendingOrProblem(req.ids).second }

    /** No procede: las participaciones que la usaban quedan sin puesto (no se pierden). */
    fun reject(req: RejectProposalsRequest, actor: String): ChangeSummary {
        rejectProblem(req)?.let { return ChangeSummary("none", "puesto-proposal", req.ids.firstOrNull(), detail = it) }
        val moved = transaction {
            val rows = PuestoProposalsT.selectAll().where { PuestoProposalsT.id inList req.ids.distinct() }.toList()
            resolveTx(rows, null, actor, note = req.reason?.trim()?.take(500)?.ifBlank { null }, firstStatus = ProposalStatus.REJECTED)
        }
        notifyOwners(moved)
        return ChangeSummary("updated", "puesto-proposal", req.ids.first(), detail = "${req.ids.distinct().size} propuesta(s) rechazadas; ${moved.size} participación(es) quedan sin puesto")
    }

    /**
     * Marca las propuestas como resueltas (la primera con [firstStatus]; al aprobar, las
     * demás del grupo quedan MERGED) y mueve sus participaciones a [puestoId] (null = sin
     * puesto). Devuelve las participaciones movidas (evento, oficial) para avisar a sus
     * titulares — y solo a ellos (el aviso general del admin no sale a los oficiales).
     */
    private fun resolveTx(rows: List<ResultRow>, puestoId: String?, actor: String, note: String?, firstStatus: ProposalStatus): List<Pair<String, String>> {
        val at = now()
        rows.forEachIndexed { i, r ->
            val st = if (firstStatus == ProposalStatus.APPROVED && i > 0) ProposalStatus.MERGED else firstStatus
            PuestoProposalsT.update({ PuestoProposalsT.id eq r[PuestoProposalsT.id] }) {
                it[status] = st.name; it[resolvedPuestoId] = puestoId
                it[reviewedBy] = actor; it[reviewedAt] = at; it[reviewNote] = note; it[updatedAt] = at
            }
        }
        val ids = rows.map { it[PuestoProposalsT.id] }
        val affected = ParticipationsT.selectAll().where { ParticipationsT.proposalId inList ids }
            .map { it[ParticipationsT.eventId] to it[ParticipationsT.officerId] }
        ParticipationsT.update({ ParticipationsT.proposalId inList ids }) {
            it[positionId] = puestoId; it[proposalId] = null; it[updatedAt] = at
        }
        return affected
    }

    private fun notifyOwners(affected: List<Pair<String, String>>) {
        affected.distinct().forEach { (eventId, officerId) -> ChangeBus.emit(eventId, "participation", id = officerId) }
    }

    fun exists(id: String): Boolean = transaction { PuestoProposalsT.selectAll().where { PuestoProposalsT.id eq id }.any() }

    private const val NEARBY_M = 120.0

    /** Distancia en metros entre dos puntos (haversine). */
    private fun metros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
    }
}
