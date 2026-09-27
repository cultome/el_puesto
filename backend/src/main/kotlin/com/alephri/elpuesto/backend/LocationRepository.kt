package com.alephri.elpuesto.backend

import com.alephri.elpuesto.backend.DomainRepository.toOfficer
import com.alephri.elpuesto.model.GeoFence
import com.alephri.elpuesto.model.LivePosition
import com.alephri.elpuesto.model.LocationSharing
import com.alephri.elpuesto.model.LocationUpdate
import com.alephri.elpuesto.model.Officer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Compartir ubicación (privacy-first, decisiones 2026-09-24 en docs/IDEAS.md): el oficial
 * decide con quién (allowlist permanente) y la posición SOLO fluye durante el evento
 * activo, entre oficiales asignados a él. Nadie la ve por su rol (ni Control ni el jefe).
 * Kinds del bus: `location-sharing` (cambió la config de [ChangeBus.Change.id]),
 * `location-share` (id = target, detail = owner, action added/removed) y `location`
 * (posición de id: updated/removed; la emite [LocationHub]).
 */
object LocationRepository {

    /** Quién me comparte, visible AHORA: nombre/avatar del emisor + su evento activo. */
    data class Sender(val officer: Officer, val eventId: String)

    private fun enabledTx(officerId: String): Boolean =
        LocationSettingsT.selectAll().where { LocationSettingsT.officerId eq officerId }
            .firstOrNull()?.get(LocationSettingsT.enabled) ?: false

    /** Oficiales que [officerId] bloqueó (para él no existen aquí). */
    private fun blockedByTx(officerId: String): Set<String> =
        OfficerBlocksT.selectAll().where { OfficerBlocksT.blockerId eq officerId }.map { it[OfficerBlocksT.blockedId] }.toSet()

    /** Un aviso "te comparte" por pareja cada 7 días como mucho (prender y apagar no avisa de nuevo). */
    private val NOTICE_EVERY: java.time.Duration = java.time.Duration.ofDays(7)

    private fun officersTx(ids: Collection<String>): Map<String, Officer> =
        if (ids.isEmpty()) emptyMap()
        else Officers.selectAll().where { Officers.id inList ids.toSet() }.associate { it[Officers.id] to it.toOfficer() }

    fun sharing(officerId: String): LocationSharing = transaction {
        val mine = LocationSharesT.selectAll().where { LocationSharesT.ownerId eq officerId }
            .orderBy(LocationSharesT.createdAt to SortOrder.ASC).map { it[LocationSharesT.targetId] }
        // Quien bloqueaste no aparece en "Te comparten" (ni su ubicación te llega).
        val blocked = blockedByTx(officerId)
        val toMe = LocationSharesT.selectAll().where { LocationSharesT.targetId eq officerId }
            .orderBy(LocationSharesT.createdAt to SortOrder.ASC).map { it[LocationSharesT.ownerId] }
            .filter { it !in blocked }
        val officers = officersTx(mine + toMe)
        LocationSharing(
            enabled = enabledTx(officerId),
            sharesWith = mine.mapNotNull { officers[it] },
            sharedWithYou = toMe.mapNotNull { officers[it] },
            hiddenIds = LocationHiddenT.selectAll().where { LocationHiddenT.viewerId eq officerId }
                .map { it[LocationHiddenT.targetId] },
        )
    }.copy(fence = sharingEvent(officerId)?.let(::fenceFor)) // el teléfono no envía fuera de ella

    private const val FENCE_TTL_SEC = 60L
    private val fences = ConcurrentHashMap<String, Pair<GeoFence?, Instant>>()

    /**
     * Zona del evento donde se comparte ubicación ([GeoFence]): el dibujo de sus trazados
     * más el margen; sin dibujo, las posiciones con coordenadas. null = el circuito no tiene
     * mapa y NO se comparte (no podemos saber si estás cerca). Caché corta: los trazados
     * casi no cambian y esto se consulta con cada posición (~15 s por oficial).
     */
    fun fenceFor(eventId: String): GeoFence? {
        fences[eventId]?.takeIf { it.second.isAfter(Instant.now().minusSeconds(FENCE_TTL_SEC)) }?.let { return it.first }
        val fence = transaction {
            val trazados = (
                EventTrazadosT.selectAll().where { EventTrazadosT.eventId eq eventId }.map { it[EventTrazadosT.trazadoId] } +
                    Events.selectAll().where { Events.id eq eventId }.map { it[Events.trazadoId] }
                ).filter { it.isNotBlank() }.toSet()
            if (trazados.isEmpty()) return@transaction null
            val drawn = TrazadoGeoT.selectAll().where { TrazadoGeoT.trazadoId inList trazados }
                .map { it[TrazadoGeoT.lat] to it[TrazadoGeoT.lon] }
            // Solo el dibujo cuando existe: una posición mal capturada lejos no agranda la zona.
            val points = drawn.ifEmpty {
                PuestosT.selectAll().where { (PuestosT.trazadoId inList trazados) and PuestosT.deletedAt.isNull() }
                    .mapNotNull { r -> r[PuestosT.lat]?.let { lat -> r[PuestosT.lon]?.let { lon -> lat to lon } } } +
                    TrackAssetsT.selectAll().where { (TrackAssetsT.trazadoId inList trazados) and TrackAssetsT.deletedAt.isNull() }
                        .mapNotNull { r -> r[TrackAssetsT.lat]?.let { lat -> r[TrackAssetsT.lon]?.let { lon -> lat to lon } } }
            }
            GeoFence.around(eventId, points)
        }
        fences[eventId] = fence to Instant.now()
        return fence
    }

    fun setEnabled(officerId: String, enabled: Boolean): LocationSharing {
        val changed = transaction {
            val row = LocationSettingsT.selectAll().where { LocationSettingsT.officerId eq officerId }.firstOrNull()
            when {
                row == null -> { LocationSettingsT.insert { it[LocationSettingsT.officerId] = officerId; it[LocationSettingsT.enabled] = enabled }; true }
                row[LocationSettingsT.enabled] != enabled -> {
                    LocationSettingsT.update({ LocationSettingsT.officerId eq officerId }) { it[LocationSettingsT.enabled] = enabled }
                    true
                }
                else -> false
            }
        }
        // Apagar = su pin desaparece YA para quien lo veía (antes de recalcular visibilidad).
        if (!enabled) LocationHub.remove(officerId)
        // Sin cambio no hay aviso: repetirlo no hace trabajar a todos los sockets.
        if (changed) ChangeBus.emit(null, "location-sharing", id = officerId)
        return sharing(officerId)
    }

    /** Agrega a la allowlist; null = hecho, texto = motivo del rechazo. */
    fun addShare(ownerId: String, targetId: String): String? {
        val problem = transaction {
            if (ownerId == targetId) return@transaction "no puedes compartir contigo"
            if (Officers.selectAll().where { Officers.id eq targetId }.empty()) return@transaction "oficial no encontrado"
            val key = (LocationSharesT.ownerId eq ownerId) and (LocationSharesT.targetId eq targetId)
            if (LocationSharesT.selectAll().where { key }.any()) return@transaction null // idempotente, sin aviso
            LocationSharesT.insert {
                it[LocationSharesT.ownerId] = ownerId; it[LocationSharesT.targetId] = targetId
                it[createdAt] = Instant.now().toString()
            }
            // Aviso al destinatario: no si te bloqueó (ni se entera) y como mucho uno por
            // semana por pareja — agregar y quitar en bucle ya no lo bombardea.
            if (OfficerBlocksT.selectAll().where { (OfficerBlocksT.blockerId eq targetId) and (OfficerBlocksT.blockedId eq ownerId) }.any()) {
                return@transaction null
            }
            val noticeKey = (LocationShareNoticesT.ownerId eq ownerId) and (LocationShareNoticesT.targetId eq targetId)
            val last = LocationShareNoticesT.selectAll().where { noticeKey }.firstOrNull()?.get(LocationShareNoticesT.at)?.let(Instant::parse)
            val now = Instant.now()
            if (last != null && last.plus(NOTICE_EVERY).isAfter(now)) return@transaction "silencioso"
            LocationShareNoticesT.deleteWhere { noticeKey }
            LocationShareNoticesT.insert {
                it[LocationShareNoticesT.ownerId] = ownerId; it[LocationShareNoticesT.targetId] = targetId; it[at] = now.toString()
            }
            "avisar"
        }
        return when (problem) {
            "avisar" -> { ChangeBus.emit(null, "location-share", id = targetId, action = "added", detail = ownerId); null }
            "silencioso" -> { ChangeBus.emit(null, "location-sharing", id = targetId); null }
            else -> problem
        }
    }

    fun removeShare(ownerId: String, targetId: String) {
        val removed = transaction {
            LocationSharesT.deleteWhere { (LocationSharesT.ownerId eq ownerId) and (LocationSharesT.targetId eq targetId) }
        }
        if (removed > 0) ChangeBus.emit(null, "location-share", id = targetId, action = "removed", detail = ownerId)
    }

    fun setHidden(viewerId: String, targetId: String, hidden: Boolean) {
        val changed = transaction {
            val key = (LocationHiddenT.viewerId eq viewerId) and (LocationHiddenT.targetId eq targetId)
            if (!hidden) LocationHiddenT.deleteWhere { key } > 0
            else if (LocationHiddenT.selectAll().where { key }.empty()) {
                LocationHiddenT.insert { it[LocationHiddenT.viewerId] = viewerId; it[LocationHiddenT.targetId] = targetId }
                true
            } else false
        }
        if (changed) ChangeBus.emit(null, "location-sharing", id = viewerId)
    }

    /**
     * Evento activo en el que [officerId] puede transmitir: interruptor encendido y
     * asignado a un evento activo. null = no debe transmitir (la app detiene el servicio).
     */
    fun sharingEvent(officerId: String): String? = transaction {
        if (!enabledTx(officerId)) return@transaction null
        val active = Events.selectAll().where { Events.active eq true }.map { it[Events.id] }
        if (active.isEmpty()) return@transaction null
        Assignments.selectAll()
            .where { (Assignments.officerId eq officerId) and (Assignments.eventId inList active) }
            .firstOrNull()?.get(Assignments.eventId)
    }

    /**
     * Emisores que [viewerId] puede ver AHORA: me incluyen en su allowlist, tienen el
     * interruptor encendido, ambos estamos asignados al MISMO evento activo y no los oculté.
     */
    fun visibleSenders(viewerId: String): Map<String, Sender> = transaction {
        val active = Events.selectAll().where { Events.active eq true }.map { it[Events.id] }
        if (active.isEmpty()) return@transaction emptyMap()
        val myEvents = Assignments.selectAll()
            .where { (Assignments.officerId eq viewerId) and (Assignments.eventId inList active) }
            .map { it[Assignments.eventId] }.toSet()
        if (myEvents.isEmpty()) return@transaction emptyMap()
        // Ocultos por el viewer y bloqueados por él: no se ven (el bloqueo es más fuerte).
        val hidden = LocationHiddenT.selectAll().where { LocationHiddenT.viewerId eq viewerId }
            .map { it[LocationHiddenT.targetId] }.toSet() + blockedByTx(viewerId)
        val owners = LocationSharesT.selectAll().where { LocationSharesT.targetId eq viewerId }
            .map { it[LocationSharesT.ownerId] }.filter { it !in hidden }
        if (owners.isEmpty()) return@transaction emptyMap()
        val enabled = LocationSettingsT.selectAll()
            .where { (LocationSettingsT.officerId inList owners) and (LocationSettingsT.enabled eq true) }
            .map { it[LocationSettingsT.officerId] }.toSet()
        val eventOf = Assignments.selectAll()
            .where { (Assignments.officerId inList enabled) and (Assignments.eventId inList myEvents) }
            .associate { it[Assignments.officerId] to it[Assignments.eventId] }
        val officers = officersTx(eventOf.keys)
        eventOf.mapNotNull { (id, ev) -> officers[id]?.let { id to Sender(it, ev) } }.toMap()
    }

    /** Posiciones vivas que [viewerId] puede ver en [eventId] (foto inicial del mapa). */
    fun livePositions(viewerId: String, eventId: String): List<LivePosition> {
        val live = visibleSenders(viewerId).filterValues { it.eventId == eventId }.mapNotNull { (id, s) ->
            LocationHub.get(id)?.takeIf { it.eventId == eventId }?.let { it to s.officer }
        }
        if (live.isEmpty()) return emptyList()
        // Rol y posición de cada quien en el evento ("Intervención 2 · 11.5") para la lista.
        val (ctx, labels) = transaction {
            val c = Assignments.selectAll()
                .where { (Assignments.eventId eq eventId) and (Assignments.officerId inList live.map { it.second.id }) }
                .associate { it[Assignments.officerId] to (it[Assignments.role] to it[Assignments.puestoId]) }
            c to DomainRepository.puestoDisplayNames(c.values.map { it.second })
        }
        return live.map { (pos, officer) ->
            val (role, puestoId) = ctx[officer.id] ?: (null to null)
            pos.toLive(officer).copy(role = role, position = puestoId?.let(labels::get))
        }
    }
}

/**
 * Última posición por oficial, SOLO EN MEMORIA (sin histórico, decisión de privacidad):
 * un reinicio del backend la olvida y la app la repone en su siguiente envío (~15 s).
 * Se purga al apagar, al terminar/desactivarse el evento y tras [TTL_SEC] sin envíos.
 */
object LocationHub {
    const val TTL_SEC = 10 * 60L

    data class Pos(val officerId: String, val eventId: String, val lat: Double, val lon: Double, val accuracyM: Float, val at: Instant) {
        fun toLive(o: Officer) = LivePosition(o.id, o.displayName, o.avatarUrl, lat, lon, accuracyM, at.toString())
    }

    private val positions = ConcurrentHashMap<String, Pos>()

    fun get(officerId: String): Pos? = positions[officerId]

    fun update(officerId: String, eventId: String, u: LocationUpdate) {
        positions[officerId] = Pos(officerId, eventId, u.lat, u.lon, u.accuracyM, Instant.now())
        ChangeBus.emit(eventId, "location", id = officerId, action = "updated")
    }

    fun remove(officerId: String) {
        val old = positions.remove(officerId) ?: return
        ChangeBus.emit(old.eventId, "location", id = officerId, action = "removed")
    }

    /** Quita posiciones vencidas o de eventos que ya no están activos. */
    fun sweep() {
        if (positions.isEmpty()) return
        val active = transaction { Events.selectAll().where { Events.active eq true }.map { it[Events.id] }.toSet() }
        val cutoff = Instant.now().minusSeconds(TTL_SEC)
        positions.values.filter { it.eventId !in active || it.at.isBefore(cutoff) }.forEach { remove(it.officerId) }
    }

    /** Barrido periódico + inmediato ante cambios de eventos (p. ej. desactivar). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(30_000)
                runCatching { sweep() }
            }
        }
        scope.launch {
            ChangeBus.flow.filter { it.kind == "admin:event" }.collect { runCatching { sweep() } }
        }
    }
}
