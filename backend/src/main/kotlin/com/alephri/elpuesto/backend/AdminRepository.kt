package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.Category
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import com.alephri.elpuesto.model.MapPoint
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.update

/**
 * Escrituras del dominio para la interfaz administrativa (agentes + admin web).
 * Convenciones:
 * - **Los IDs los asigna el servidor (UUIDv7)**: crear = POST sin id; el PUT /{id}
 *   solo actualiza (la ruta gatea con [entityExists]). Reintentar un PUT nunca duplica.
 * - `ord` se asigna al crear (max+1) y se conserva al actualizar.
 * - Borrar cascadea a los hijos (no hay FKs en el esquema); si otra entidad
 *   referencia a la borrada, la operación se rechaza con la lista de referencias.
 * - Todo devuelve [ChangeSummary] para que la ruta lo audite y lo reporte.
 */
object AdminRepository {

    private fun newId() = uuidv7()

    private fun now() = java.time.Instant.now().toString()

    /**
     * ¿Existe la entidad? Gatea los PUT de actualización (crear es solo por POST).
     * Circuitos/trazados con borrado lógico cuentan como inexistentes.
     */
    fun entityExists(entity: String, id: String): Boolean = transaction {
        when (entity) {
            "officer" -> Officers.selectAll().where { Officers.id eq id }.any()
            "event" -> Events.selectAll().where { Events.id eq id }.any()
            "circuit" -> CircuitsT.selectAll().where { (CircuitsT.id eq id) and CircuitsT.deletedAt.isNull() }.any()
            "trazado" -> TrazadosT.selectAll().where { (TrazadosT.id eq id) and TrazadosT.deletedAt.isNull() }.any()
            "series" -> SeriesT.selectAll().where { SeriesT.id eq id }.any()
            "championship" -> ChampionshipsT.selectAll().where { ChampionshipsT.id eq id }.any()
            "category" -> CategoriesT.selectAll().where { CategoriesT.id eq id }.any()
            "convocatoria" -> ConvocatoriasT.selectAll().where { ConvocatoriasT.id eq id }.any()
            "agenda" -> AgendaEntriesT.selectAll().where { AgendaEntriesT.id eq id }.any()
            else -> false
        }
    }

    /** Año para ordenar una temporada desde su etiqueta: "2026" → 2026, "2025-26" → 2026. */
    fun seasonYear(label: String): Int? {
        val m = Regex("""^(\d{4})(?:-(\d{2}|\d{4}))?$""").find(label.trim()) ?: return null
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2]
        return when {
            end.isEmpty() -> start
            end.length == 4 -> end.toInt()
            else -> (start / 100) * 100 + end.toInt()
        }
    }

    private fun nextOrd(table: Table, ordCol: Column<Int>): Int {
        val maxOrd = ordCol.max()
        return table.select(maxOrd).firstOrNull()?.get(maxOrd)?.plus(1) ?: 0
    }

    // —— Oficiales ——

    fun listOfficers(): List<Officer> = transaction {
        Officers.selectAll().orderBy(Officers.displayName).map { row ->
            DomainRepository.officer(row[Officers.id])!!
        }
    }

    fun upsertOfficer(o: Officer): ChangeSummary = transaction {
        val exists = Officers.selectAll().where { Officers.id eq o.id }.any()
        val write: Officers.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { st ->
            st[omdaiId] = o.omdaiId
            st[displayName] = o.displayName
            st[area] = o.assignedArea?.name
            st[systemRole] = o.systemRole.name
            st[status] = o.status.name
            st[statEvents] = o.stats.events
            st[statSeason] = o.stats.thisSeason
            st[activeSince] = o.stats.activeSince
            // avatarUrl la administra el pipeline de imágenes: null/omitido = conservar.
            if (o.avatarUrl != null) st[avatarUrl] = o.avatarUrl
        }
        if (exists) Officers.update({ Officers.id eq o.id }) { Officers.write(it) }
        else Officers.insert { st -> st[id] = o.id; st[avatarUrl] = o.avatarUrl; Officers.write(st) }
        ChangeSummary(if (exists) "updated" else "created", "officer", o.id)
    }

    // —— Eventos ——

    /** Con el flag `active` (no viaja en el modelo compartido pero el admin lo necesita ver). */
    fun allEvents(): List<AdminEventInfo> = transaction {
        val trazadosByEvent = EventTrazadosT.selectAll().orderBy(EventTrazadosT.ord)
            .groupBy({ it[EventTrazadosT.eventId] }, { it[EventTrazadosT.trazadoId] })
        val championshipsByEvent = EventChampionshipsT.selectAll().orderBy(EventChampionshipsT.ord)
            .groupBy({ it[EventChampionshipsT.eventId] }, { it[EventChampionshipsT.championshipId] })
        val declared = ParticipationsT.selectAll().groupingBy { it[ParticipationsT.eventId] }.eachCount()
        Events.selectAll().orderBy(Events.startsOn).map {
            AdminEventInfo(
                id = it[Events.id], name = it[Events.name],
                startsOn = it[Events.startsOn], endsOn = it[Events.endsOn],
                circuitId = it[Events.circuitId], trazadoId = it[Events.trazadoId],
                trazadoIds = trazadosByEvent[it[Events.id]] ?: listOf(it[Events.trazadoId]),
                championshipIds = championshipsByEvent[it[Events.id]] ?: emptyList(),
                status = DomainRepository.eventStatus(it[Events.startsOn], it[Events.endsOn]).name,
                active = it[Events.active],
                selfRegistration = it[Events.selfRegistration],
                declaredCount = declared[it[Events.id]] ?: 0,
            )
        }
    }

    fun setTrazadoMapUrl(id: String, url: String): Boolean = transaction {
        TrazadosT.update({ TrazadosT.id eq id }) { it[mapUrl] = url } > 0
    }

    fun upsertEvent(e: Event): ChangeSummary = transaction {
        // Un evento puede correr sobre varios trazados; el primero de la lista queda
        // como el "principal" (columna trazado_id, compat con el modelo viejo).
        val trazados = e.trazadoIds.ifEmpty { listOf(e.trazadoId) }.filter { it.isNotBlank() }.distinct()
        if (trazados.isEmpty())
            return@transaction ChangeSummary("none", "event", e.id, detail = "trazadoIds requerido (al menos un trazado)")
        val missing = buildList {
            // Un circuito/trazado con borrado lógico no acepta eventos nuevos.
            if (CircuitsT.selectAll().where { (CircuitsT.id eq e.circuitId) and CircuitsT.deletedAt.isNull() }.empty()) add("circuitId '${e.circuitId}'")
            trazados.forEach { tz ->
                val row = TrazadosT.selectAll().where { (TrazadosT.id eq tz) and TrazadosT.deletedAt.isNull() }.firstOrNull()
                when {
                    row == null -> add("trazadoId '$tz'")
                    row[TrazadosT.circuitId] != e.circuitId ->
                        add("trazado '$tz' (es de '${row[TrazadosT.circuitId]}', no de '${e.circuitId}')")
                }
            }
            e.championshipIds.distinct().forEach { ch ->
                if (ChampionshipsT.selectAll().where { ChampionshipsT.id eq ch }.empty()) add("championshipId '$ch'")
            }
        }
        if (missing.isNotEmpty()) return@transaction ChangeSummary("none", "event", e.id, detail = "no existe: ${missing.joinToString()}")
        val exists = Events.selectAll().where { Events.id eq e.id }.any()
        // El estatus no se captura: se deriva de las fechas (la columna guarda el valor
        // derivado al guardar solo como informativo; las lecturas siempre re-derivan).
        val derived = DomainRepository.eventStatus(e.startsOn.toString(), e.endsOn.toString()).name
        if (exists) {
            Events.update({ Events.id eq e.id }) {
                it[name] = e.name; it[startsOn] = e.startsOn.toString(); it[endsOn] = e.endsOn.toString()
                it[circuitId] = e.circuitId; it[trazadoId] = trazados.first(); it[status] = derived
                // Autoregistro: null/omitido al actualizar = conservar.
                e.selfRegistration?.let { v -> it[selfRegistration] = v }
            }
        } else {
            Events.insert {
                it[id] = e.id; it[name] = e.name; it[startsOn] = e.startsOn.toString(); it[endsOn] = e.endsOn.toString()
                it[circuitId] = e.circuitId; it[trazadoId] = trazados.first(); it[status] = derived
                // Permitido por defecto (son los menos los eventos que se cierran).
                it[selfRegistration] = e.selfRegistration ?: true
            }
        }
        EventTrazadosT.deleteWhere { eventId eq e.id }
        trazados.forEachIndexed { i, tz ->
            EventTrazadosT.insert { it[eventId] = e.id; it[ord] = i; it[trazadoId] = tz }
        }
        EventChampionshipsT.deleteWhere { eventId eq e.id }
        e.championshipIds.distinct().forEachIndexed { i, ch ->
            EventChampionshipsT.insert { it[eventId] = e.id; it[ord] = i; it[championshipId] = ch }
        }
        ChangeSummary(if (exists) "updated" else "created", "event", e.id, detail = "${trazados.size} trazado(s), ${e.championshipIds.distinct().size} campeonato(s)")
    }.also {
        if (it.action != "none") {
            DomainRepository.ensureEventChat(e.id, e.name)
            ChangeBus.emit(e.id, "event")
        }
    }

    /** Todas las asignaciones de un evento (el repo de la app solo resuelve la propia). */
    fun eventAssignments(eventId: String): List<Assignment> = transaction {
        val rows = Assignments.selectAll().where { Assignments.eventId eq eventId }
            .orderBy(Assignments.puestoNumber)
            .toList()
        val names = DomainRepository.puestoDisplayNames(rows.map { it[Assignments.puestoId] })
        rows.map {
            Assignment(
                id = it[Assignments.id], eventId = it[Assignments.eventId], officerId = it[Assignments.officerId],
                role = it[Assignments.role],
                puestoId = it[Assignments.puestoId], puestoNumber = it[Assignments.puestoNumber],
                shift = it[Assignments.shift],
                puestoLabel = names[it[Assignments.puestoId]] ?: "",
            )
        }
    }

    /**
     * El flag `active` (qué evento está "en curso" para la app) no viaja en el modelo.
     * EXCLUYENTE: la app muestra UN evento en curso, así que activar uno desactiva
     * cualquier otro que estuviera activo.
     */
    fun setEventActive(id: String, active: Boolean): Boolean {
        val ok = transaction {
            if (active) Events.update({ Events.active eq true }) { it[Events.active] = false }
            Events.update({ Events.id eq id }) { it[Events.active] = active } > 0
        }
        if (ok) ChangeBus.emit(id, "event")
        return ok
    }

    fun deleteEvent(id: String): ChangeSummary = transaction {
        if (Events.selectAll().where { Events.id eq id }.empty())
            return@transaction ChangeSummary("none", "event", id, detail = "no existe")
        val children = Assignments.deleteWhere { eventId eq id } +
            ParticipationsT.deleteWhere { eventId eq id } +
            SessionsT.deleteWhere { eventId eq id } +
            ChecklistItems.deleteWhere { eventId eq id } +
            ChecklistStateT.deleteWhere { eventId eq id }
        EventTrazadosT.deleteWhere { eventId eq id }
        EventChampionshipsT.deleteWhere { eventId eq id }
        // Los chats del evento y de sus puestos se van con el evento (mensajes, membresías
        // e imágenes incluidos). Los públicos/privados que los oficiales LIGARON al evento
        // son suyos: solo pierden el vínculo.
        val eventChatTypes = listOf(com.alephri.elpuesto.model.ChatType.EVENT.name, com.alephri.elpuesto.model.ChatType.PUESTO.name)
        ChatsT.update({ (ChatsT.eventId eq id) and (ChatsT.type notInList eventChatTypes) }) { it[ChatsT.eventId] = null }
        ChatsT.selectAll().where { ChatsT.eventId eq id }.map { it[ChatsT.id] }.forEach { c ->
            val msgIds = MessagesT.selectAll().where { MessagesT.chatId eq c }.map { it[MessagesT.id] }
            if (msgIds.isNotEmpty()) ImagesT.deleteWhere { (ImagesT.kind eq "chatmedia") and (ImagesT.ownerId inList msgIds) }
            ImagesT.deleteWhere { (ImagesT.kind eq "chatimg") and (ImagesT.ownerId eq c) }
            MessagesT.deleteWhere { chatId eq c }
            ChatMembersT.deleteWhere { chatId eq c }
            ChatReadsT.deleteWhere { chatId eq c }
        }
        ChatsT.deleteWhere { ChatsT.eventId eq id }
        Events.deleteWhere { Events.id eq id }
        ImagesT.deleteWhere { (ImagesT.kind eq "event") and (ImagesT.ownerId eq id) }
        ChangeSummary("deleted", "event", id, detail = "$children filas hijas (asignaciones/registros por honor/sesiones/checklist)")
    }

    fun replaceSessions(eventId: String, sessions: List<Session>): ChangeSummary = transaction {
        val before = SessionsT.deleteWhere { SessionsT.eventId eq eventId }
        val now = java.time.LocalDateTime.now()
        sessions.forEachIndexed { i, s ->
            // El estatus no se captura: se deriva de día/hora (pasada = FINISHED). El
            // marcado fino de "EN CURSO" pertenece al flujo operativo del evento.
            val at = java.time.LocalDateTime.of(
                java.time.LocalDate.parse(s.day.toString()),
                java.time.LocalTime.parse(s.time.toString()),
            )
            val derived = if (at.isBefore(now)) EventStatus.FINISHED else EventStatus.UPCOMING
            SessionsT.insert {
                it[id] = s.id.ifBlank { newId() }
                it[SessionsT.eventId] = eventId
                it[ord] = i
                it[day] = s.day.toString(); it[time] = s.time.toString()
                it[category] = s.category; it[name] = s.name
                it[status] = derived.name; it[endsInMin] = s.endsInMin
            }
        }
        ChangeSummary("replaced", "sessions", eventId, detail = "${sessions.size} sesiones (antes $before; estatus derivado de fecha/hora)")
    }.also { ChangeBus.emit(eventId, "sessions")
    }

    /**
     * El admin define la PLANTILLA; el avance vive POR PUESTO en checklist_state y se
     * PRESERVA por id de ítem al reemplazar (el estado de los ítems removidos se limpia).
     */
    fun replaceChecklist(eventId: String, items: List<ChecklistItem>): ChangeSummary = transaction {
        val keptIds = items.map { it.id }.filter { it.isNotBlank() }.toSet()
        val cleared = ChecklistStateT.deleteWhere {
            (ChecklistStateT.eventId eq eventId) and (ChecklistStateT.itemId notInList keptIds)
        }
        val before = ChecklistItems.deleteWhere { ChecklistItems.eventId eq eventId }
        items.forEachIndexed { i, c ->
            ChecklistItems.insert {
                it[id] = c.id.ifBlank { newId() }
                it[ChecklistItems.eventId] = eventId
                it[ord] = i; it[text] = c.text
            }
        }
        ChangeSummary("replaced", "checklist", eventId, detail = "${items.size} ítems (antes $before; el avance por puesto se conserva por id, $cleared marcas de ítems removidos limpiadas)")
    }.also { ChangeBus.emit(eventId, "checklist")
    }

    /** Turnos válidos de una asignación. */
    private val validShifts = listOf("Día completo") + (1..8).map { "Turno $it" }

    /** Roles operativos válidos de una asignación (catálogo operativo del puesto). */
    val validRoles = com.alephri.elpuesto.model.OperationalRoles.ALL

    /**
     * Posiciones asignables de un evento (las de sus trazados, vivas): id → número.
     * Una posición es un PUESTO o un ACTIVO tripulable (TH/IFRT/HIAB…); los activos no
     * tienen número (0) — la UI muestra el label.
     */
    internal fun eventPositions(eventId: String): Map<String, Int> {
        val eventTrazados = EventTrazadosT.selectAll().where { EventTrazadosT.eventId eq eventId }
            .map { it[EventTrazadosT.trazadoId] }
            .ifEmpty { Events.selectAll().where { Events.id eq eventId }.map { it[Events.trazadoId] } }
        return PuestosT.selectAll()
            .where { (PuestosT.trazadoId inList eventTrazados) and PuestosT.deletedAt.isNull() }
            .associateBy({ it[PuestosT.id] }, { it[PuestosT.number] }) +
            TrackAssetsT.selectAll()
                .where { (TrackAssetsT.trazadoId inList eventTrazados) and TrackAssetsT.deletedAt.isNull() }
                .associateBy({ it[TrackAssetsT.id] }, { 0 })
    }

    /**
     * Valida un reemplazo de asignaciones SIN escribir: null = válido, texto = motivo del
     * rechazo. La corre el dryRun de la ruta y [replaceAssignments] antes de aplicar.
     */
    fun assignmentsProblem(eventId: String, assignments: List<Assignment>): String? = transaction {
        val missing = assignments.map { it.officerId }.distinct()
            .filter { off -> Officers.selectAll().where { Officers.id eq off }.empty() }
        if (missing.isNotEmpty())
            return@transaction "oficiales inexistentes: ${missing.joinToString()}"
        // Las posiciones válidas son los puestos Y activos de los trazados del evento
        // (el número se deriva del puesto; el admin ya no lo captura).
        val positions = eventPositions(eventId)
        val badPuestos = assignments.map { it.puestoId }.distinct().filter { it !in positions }
        if (badPuestos.isNotEmpty())
            return@transaction "posiciones (puestos/activos) que no son de los trazados del evento: ${badPuestos.joinToString()}"
        val badRoles = assignments.map { it.role }.filter { it !in validRoles }.distinct()
        if (badRoles.isNotEmpty())
            return@transaction "roles inválidos: ${badRoles.joinToString()} (opciones: ${validRoles.joinToString()})"
        val badShifts = assignments.mapNotNull { it.shift }.filter { it.isNotBlank() && it !in validShifts }.distinct()
        if (badShifts.isNotEmpty())
            return@transaction "turnos inválidos: ${badShifts.joinToString()} (opciones: ${validShifts.joinToString()})"
        // Máx. UN rol-jefe (Chief Post Marshal / Jefe Telehandler / Jefe IFRT) por
        // posición: del jefe se derivan compañeros, chats y el guard de emergencia —
        // dos jefes romperían esas derivaciones.
        val dupChiefPositions = assignments.filter { it.role in CHIEF_ROLES }
            .groupBy { it.puestoId }.filterValues { it.size > 1 }.keys
        if (dupChiefPositions.isNotEmpty()) {
            val names = DomainRepository.puestoDisplayNames(dupChiefPositions)
            return@transaction "más de un rol-jefe (${CHIEF_ROLES.joinToString()}) en la misma posición: " +
                dupChiefPositions.joinToString { names[it] ?: it } +
                " (máximo uno por posición)"
        }
        null
    }

    fun replaceAssignments(eventId: String, assignments: List<Assignment>): ChangeSummary = transaction {
        assignmentsProblem(eventId, assignments)?.let {
            return@transaction ChangeSummary("none", "assignments", eventId, detail = it)
        }
        val positions = eventPositions(eventId)
        val before = Assignments.deleteWhere { Assignments.eventId eq eventId }
        assignments.forEach { a ->
            val num = positions.getValue(a.puestoId)
            Assignments.insert {
                it[id] = a.id.ifBlank { newId() }
                it[Assignments.eventId] = eventId
                it[officerId] = a.officerId; it[role] = a.role
                it[puestoId] = a.puestoId; it[puestoNumber] = num
                it[shift] = a.shift?.ifBlank { null } ?: "Día completo"
            }
        }
        // Un chat por posición con gente (los nuevos nacen aquí; los viejos se conservan).
        DomainRepository.ensurePuestoChats(eventId)
        ChangeSummary("replaced", "assignments", eventId, detail = "${assignments.size} asignaciones (antes $before)")
    }.also { ChangeBus.emit(eventId, "assignments")
    }

    // —— Circuitos ——

    fun upsertCircuit(c: Circuit): ChangeSummary = transaction {
        val exists = CircuitsT.selectAll().where { CircuitsT.id eq c.id }.any()
        if (exists) CircuitsT.update({ CircuitsT.id eq c.id }) { it[name] = c.name; it[location] = c.location; it[country] = c.country }
        else {
            val ord = nextOrd(CircuitsT, CircuitsT.ord)
            CircuitsT.insert { it[id] = c.id; it[CircuitsT.ord] = ord; it[name] = c.name; it[location] = c.location; it[country] = c.country }
        }
        ChangeSummary(if (exists) "updated" else "created", "circuit", c.id)
    }

    /**
     * Borrado LÓGICO con cascada hacia abajo (trazados→puestos/activos): los registros
     * quedan archivados (fuera de catálogos y combos) pero los eventos y asignaciones
     * históricos que los referencian conservan sus datos.
     */
    fun deleteCircuit(id: String): ChangeSummary = transaction {
        if (CircuitsT.selectAll().where { (CircuitsT.id eq id) and CircuitsT.deletedAt.isNull() }.empty())
            return@transaction ChangeSummary("none", "circuit", id, detail = "no existe")
        val at = now()
        val trazadoIds = TrazadosT.selectAll()
            .where { (TrazadosT.circuitId eq id) and TrazadosT.deletedAt.isNull() }
            .map { it[TrazadosT.id] }
        var children = 0
        trazadoIds.forEach { tz ->
            children += PuestosT.update({ (PuestosT.trazadoId eq tz) and PuestosT.deletedAt.isNull() }) { it[deletedAt] = at } +
                TrackAssetsT.update({ (TrackAssetsT.trazadoId eq tz) and TrackAssetsT.deletedAt.isNull() }) { it[deletedAt] = at }
        }
        children += TrazadosT.update({ TrazadosT.id inList trazadoIds }) { it[deletedAt] = at }
        CircuitsT.update({ CircuitsT.id eq id }) { it[deletedAt] = at }
        ChangeSummary(
            "deleted", "circuit", id,
            detail = "borrado lógico con $children hijas (trazados/puestos/activos); los eventos que lo referencian conservan sus datos",
        )
    }

    fun upsertTrazado(t: Trazado): ChangeSummary = transaction {
        if (CircuitsT.selectAll().where { (CircuitsT.id eq t.circuitId) and CircuitsT.deletedAt.isNull() }.empty())
            return@transaction ChangeSummary("none", "trazado", t.id, detail = "no existe: circuitId '${t.circuitId}'")
        val exists = TrazadosT.selectAll().where { TrazadosT.id eq t.id }.any()
        if (exists) {
            TrazadosT.update({ TrazadosT.id eq t.id }) {
                it[circuitId] = t.circuitId; it[name] = t.name; it[lengthM] = t.lengthM
                it[curves] = t.curves; it[direction] = t.direction
                // mapUrl la administra el pipeline de imágenes: null/omitido = conservar.
                if (t.mapUrl != null) it[mapUrl] = t.mapUrl
            }
        } else {
            val ord = nextOrd(TrazadosT, TrazadosT.ord)
            TrazadosT.insert {
                it[id] = t.id; it[circuitId] = t.circuitId; it[TrazadosT.ord] = ord
                it[name] = t.name; it[lengthM] = t.lengthM
                it[curves] = t.curves; it[direction] = t.direction; it[mapUrl] = t.mapUrl
            }
        }
        ChangeSummary(if (exists) "updated" else "created", "trazado", t.id)
    }

    // —— Silueta del trazado (dibujada sobre OSM en el admin) ——

    fun trazadoGeo(id: String): List<GeoPoint> = transaction {
        TrazadoGeoT.selectAll().where { TrazadoGeoT.trazadoId eq id }
            .orderBy(TrazadoGeoT.ord)
            .map { GeoPoint(it[TrazadoGeoT.lat], it[TrazadoGeoT.lon]) }
    }

    /**
     * Reemplaza el dibujo geo del trazado y deriva la silueta normalizada 0..1
     * (proyección Web Mercator, aspecto preservado, margen 5%) que consume la app.
     * Lista vacía = borrar el dibujo.
     */
    fun replaceTrazadoPath(id: String, geo: List<GeoPoint>): ChangeSummary = transaction {
        if (TrazadosT.selectAll().where { TrazadosT.id eq id }.empty())
            return@transaction ChangeSummary("none", "trazado-path", id, detail = "no existe: trazado '$id'")
        TrazadoGeoT.deleteWhere { trazadoId eq id }
        geo.forEachIndexed { i, g ->
            TrazadoGeoT.insert {
                it[trazadoId] = id; it[ord] = i; it[lat] = g.lat; it[lon] = g.lon
            }
        }
        val normalized = normalizeGeo(geo)
        val json = if (normalized.isEmpty()) null else kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MapPoint.serializer()), normalized,
        )
        TrazadosT.update({ TrazadosT.id eq id }) { it[pathJson] = json }
        // El trazado define el marco: re-derivar el point normalizado de los
        // puestos/activos con coordenadas absolutas (la app los dibuja con point).
        var rederived = 0
        geoFrame(geo)?.let { frame ->
            PuestosT.selectAll().where { (PuestosT.trazadoId eq id) and PuestosT.lat.isNotNull() }.forEach { row ->
                val pt = frame(row[PuestosT.lat]!!, row[PuestosT.lon]!!)
                PuestosT.update({ PuestosT.id eq row[PuestosT.id] }) { it[x] = pt.x; it[y] = pt.y }
                rederived++
            }
            TrackAssetsT.selectAll().where { (TrackAssetsT.trazadoId eq id) and TrackAssetsT.lat.isNotNull() }.forEach { row ->
                val pt = frame(row[TrackAssetsT.lat]!!, row[TrackAssetsT.lon]!!)
                TrackAssetsT.update({ TrackAssetsT.id eq row[TrackAssetsT.id] }) { it[x] = pt.x; it[y] = pt.y }
                rederived++
            }
        }
        ChangeSummary("replaced", "trazado-path", id, detail = "${geo.size} vértices geo → ${normalized.size} normalizados; $rederived puntos re-derivados")
    }

    /**
     * Marco de normalización del trazado: proyección Web Mercator ajustada a 0..1 con
     * aspecto preservado y margen 5% (y invertida: norte arriba = y menor). Devuelve la
     * función (lat, lon) → MapPoint, o null si el trazado no define un marco.
     */
    internal fun geoFrame(geo: List<GeoPoint>): ((Double, Double) -> MapPoint)? {
        // La proyección vive en shared (GeoFrame): la app proyecta con la misma para las
        // posiciones vivas de compartir ubicación.
        val frame = com.alephri.elpuesto.model.GeoFrame.of(geo.map { it.lat to it.lon }) ?: return null
        return { lat, lon ->
            val p = frame.project(lat, lon)
            MapPoint(x = p.x.coerceIn(0f, 1f), y = p.y.coerceIn(0f, 1f))
        }
    }

    internal fun normalizeGeo(geo: List<GeoPoint>): List<MapPoint> =
        geoFrame(geo)?.let { f -> geo.map { f(it.lat, it.lon) } } ?: emptyList()

    /** Borrado LÓGICO del trazado con cascada a puestos/activos (ver [deleteCircuit]). */
    fun deleteTrazado(id: String): ChangeSummary = transaction {
        if (TrazadosT.selectAll().where { (TrazadosT.id eq id) and TrazadosT.deletedAt.isNull() }.empty())
            return@transaction ChangeSummary("none", "trazado", id, detail = "no existe")
        val at = now()
        val children = PuestosT.update({ (PuestosT.trazadoId eq id) and PuestosT.deletedAt.isNull() }) { it[deletedAt] = at } +
            TrackAssetsT.update({ (TrackAssetsT.trazadoId eq id) and TrackAssetsT.deletedAt.isNull() }) { it[deletedAt] = at }
        TrazadosT.update({ TrazadosT.id eq id }) { it[deletedAt] = at }
        ChangeSummary(
            "deleted", "trazado", id,
            detail = "borrado lógico con $children hijas (puestos/activos); los eventos que lo referencian conservan sus datos",
        )
    }

    fun replacePuestos(trazadoId: String, puestos: List<Puesto>): ChangeSummary = transaction {
        // lat/lon absolutas mandan: point se deriva del marco del trazado (si existe).
        val frame = geoFrame(trazadoGeo(trazadoId))
        // Reemplazo = la lista queda como vino, pero los puestos REMOVIDOS se archivan
        // (borrado lógico) porque asignaciones de eventos pasados pueden referenciarlos.
        val incoming = puestos.map { it.id }.filter { it.isNotBlank() }.toSet()
        val archived = PuestosT.update({
            (PuestosT.trazadoId eq trazadoId) and PuestosT.deletedAt.isNull() and (PuestosT.id notInList incoming)
        }) { it[deletedAt] = now() }
        puestos.forEach { p ->
            val pt = if (!p.onMap) MapPoint(0f, 0f)
                else if (p.lat != null && p.lon != null && frame != null) frame(p.lat!!, p.lon!!) else p.point
            val write: PuestosT.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { st ->
                st[PuestosT.trazadoId] = trazadoId
                st[number] = p.number; st[label] = p.label
                st[x] = pt.x; st[y] = pt.y
                st[lat] = if (p.onMap) p.lat else null; st[lon] = if (p.onMap) p.lon else null
                st[onMap] = p.onMap
                st[deletedAt] = null // mandar un id archivado lo revive
            }
            val exists = p.id.isNotBlank() && PuestosT.selectAll().where { PuestosT.id eq p.id }.any()
            if (exists) PuestosT.update({ PuestosT.id eq p.id }) { PuestosT.write(it) }
            else PuestosT.insert { st -> st[id] = p.id.ifBlank { newId() }; PuestosT.write(st) }
        }
        ChangeSummary("replaced", "puestos", trazadoId, detail = "${puestos.size} puestos ($archived removidos → archivados)")
    }.also { ChangeBus.emit(null, "map")
    }

    fun replaceAssets(trazadoId: String, assets: List<TrackAsset>): ChangeSummary = transaction {
        val frame = geoFrame(trazadoGeo(trazadoId))
        val incoming = assets.map { it.id }.filter { it.isNotBlank() }.toSet()
        val archived = TrackAssetsT.update({
            (TrackAssetsT.trazadoId eq trazadoId) and TrackAssetsT.deletedAt.isNull() and (TrackAssetsT.id notInList incoming)
        }) { it[deletedAt] = now() }
        assets.forEach { a ->
            val pt = if (a.lat != null && a.lon != null && frame != null) frame(a.lat!!, a.lon!!) else a.point
            val write: TrackAssetsT.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { st ->
                st[TrackAssetsT.trazadoId] = trazadoId
                st[type] = a.type.name; st[label] = a.label
                st[x] = pt.x; st[y] = pt.y
                st[lat] = a.lat; st[lon] = a.lon
                st[deletedAt] = null
            }
            val exists = a.id.isNotBlank() && TrackAssetsT.selectAll().where { TrackAssetsT.id eq a.id }.any()
            if (exists) TrackAssetsT.update({ TrackAssetsT.id eq a.id }) { TrackAssetsT.write(it) }
            else TrackAssetsT.insert { st -> st[id] = a.id.ifBlank { newId() }; TrackAssetsT.write(st) }
        }
        ChangeSummary("replaced", "assets", trazadoId, detail = "${assets.size} activos ($archived removidos → archivados)")
    }.also { ChangeBus.emit(null, "map")
    }

    // —— Campeonatos ——

    // —— Campeonatos (series) ——

    fun upsertSeries(s: com.alephri.elpuesto.model.Series): ChangeSummary = transaction {
        val exists = SeriesT.selectAll().where { SeriesT.id eq s.id }.any()
        if (exists) {
            SeriesT.update({ SeriesT.id eq s.id }) {
                it[name] = s.name.trim()
                // emblemUrl la administra el pipeline de imágenes: null/omitido = conservar.
                if (s.emblemUrl != null) it[emblemUrl] = s.emblemUrl
            }
            // El nombre de la temporada es copia del de su campeonato.
            ChampionshipsT.update({ ChampionshipsT.seriesId eq s.id }) { it[name] = s.name.trim() }
        } else {
            val ord = nextOrd(SeriesT, SeriesT.ord)
            SeriesT.insert { it[id] = s.id; it[SeriesT.ord] = ord; it[name] = s.name.trim(); it[emblemUrl] = s.emblemUrl }
        }
        ChangeSummary(if (exists) "updated" else "created", "series", s.id)
    }

    fun setSeriesEmblem(id: String, url: String): Boolean = transaction {
        SeriesT.update({ SeriesT.id eq id }) { it[emblemUrl] = url } > 0
    }

    /** ¿Otro campeonato ya se llama así? (el nombre identifica al campeonato en la carga). */
    fun seriesNameTaken(name: String, exceptId: String?): Boolean = transaction {
        SeriesT.selectAll().where { SeriesT.name.lowerCase() eq name.trim().lowercase() }
            .any { it[SeriesT.id] != exceptId }
    }

    /** Borra un campeonato SIN temporadas (las temporadas se borran antes, una por una). */
    fun deleteSeries(id: String): ChangeSummary = transaction {
        if (SeriesT.selectAll().where { SeriesT.id eq id }.empty())
            return@transaction ChangeSummary("none", "series", id, detail = "no existe")
        val seasons = ChampionshipsT.selectAll().where { ChampionshipsT.seriesId eq id }
            .map { it[ChampionshipsT.seasonLabel] ?: it[ChampionshipsT.season].toString() }
        if (seasons.isNotEmpty()) {
            return@transaction ChangeSummary(
                "none", "series", id,
                detail = "tiene temporadas: ${seasons.joinToString()} — bórralas antes (DELETE /admin/championships/{id})",
            )
        }
        SeriesT.deleteWhere { SeriesT.id eq id }
        ImagesT.deleteWhere { (ImagesT.kind eq "series") and (ImagesT.ownerId eq id) }
        ChangeSummary("deleted", "series", id)
    }

    // —— Temporadas (championships) ——

    /**
     * Crea/actualiza una TEMPORADA de un campeonato. El nombre se copia del campeonato; el
     * año para ordenar sale de `season` o, si no viene, de la etiqueta (2025-26 → 2026).
     */
    fun upsertChampionship(c: Championship): ChangeSummary = transaction {
        val exists = ChampionshipsT.selectAll().where { ChampionshipsT.id eq c.id }.any()
        val seriesName = SeriesT.selectAll().where { SeriesT.id eq c.seriesId }.first()[SeriesT.name]
        val label = c.seasonLabel.trim()
        val year = c.season.takeIf { it > 0 } ?: seasonYear(label) ?: 0
        if (exists) {
            ChampionshipsT.update({ ChampionshipsT.id eq c.id }) {
                it[name] = seriesName; it[season] = year
                it[seriesId] = c.seriesId; it[seasonLabel] = label
            }
        } else {
            val ord = nextOrd(ChampionshipsT, ChampionshipsT.ord)
            ChampionshipsT.insert {
                it[id] = c.id; it[ChampionshipsT.ord] = ord
                it[name] = seriesName; it[season] = year; it[emblemUrl] = null
                it[seriesId] = c.seriesId; it[seasonLabel] = label
            }
        }
        ChangeSummary(if (exists) "updated" else "created", "championship", c.id, detail = "$seriesName · $label")
    }

    /** ¿El campeonato ya tiene una temporada con esa etiqueta? */
    fun seasonLabelTaken(seriesId: String, label: String, exceptId: String?): Boolean = transaction {
        ChampionshipsT.selectAll()
            .where { (ChampionshipsT.seriesId eq seriesId) and (ChampionshipsT.seasonLabel eq label.trim()) }
            .any { it[ChampionshipsT.id] != exceptId }
    }

    fun deleteChampionship(id: String): ChangeSummary = transaction {
        if (ChampionshipsT.selectAll().where { ChampionshipsT.id eq id }.empty())
            return@transaction ChangeSummary("none", "championship", id, detail = "no existe")
        val refEvents = EventChampionshipsT.selectAll().where { EventChampionshipsT.championshipId eq id }
            .map { it[EventChampionshipsT.eventId] }.distinct()
        if (refEvents.isNotEmpty()) {
            val names = Events.selectAll().where { Events.id inList refEvents }.map { it[Events.name] }
            return@transaction ChangeSummary(
                "none", "championship", id,
                detail = "referenciado por eventos: ${names.joinToString()} — quita el campeonato de esos eventos antes",
            )
        }
        val catIds = CategoriesT.selectAll().where { CategoriesT.championshipId eq id }.map { it[CategoriesT.id] }
        var children = 0
        catIds.forEach { cat ->
            unlinkTripRounds(RoundsT.selectAll().where { RoundsT.categoryId eq cat }.mapNotNull { it[RoundsT.id] })
            children += StandingsT.deleteWhere { categoryId eq cat } +
                RoundsT.deleteWhere { categoryId eq cat } +
                DriversT.deleteWhere { categoryId eq cat }
            StandingsIngest.forgetCategory(cat)
        }
        children += CategoriesT.deleteWhere { championshipId eq id }
        ChampionshipsT.deleteWhere { ChampionshipsT.id eq id }
        ChangeSummary("deleted", "championship", id, detail = "$children filas hijas (categorías/posiciones/fechas/pilotos)")
    }

    fun upsertCategory(c: Category): ChangeSummary = transaction {
        if (ChampionshipsT.selectAll().where { ChampionshipsT.id eq c.championshipId }.empty())
            return@transaction ChangeSummary("none", "category", c.id, detail = "no existe: championshipId '${c.championshipId}'")
        val exists = CategoriesT.selectAll().where { CategoriesT.id eq c.id }.any()
        if (exists) CategoriesT.update({ CategoriesT.id eq c.id }) { it[championshipId] = c.championshipId; it[name] = c.name }
        else {
            val ord = nextOrd(CategoriesT, CategoriesT.ord)
            CategoriesT.insert { it[id] = c.id; it[championshipId] = c.championshipId; it[CategoriesT.ord] = ord; it[name] = c.name }
        }
        ChangeSummary(if (exists) "updated" else "created", "category", c.id)
    }

    fun deleteCategory(id: String): ChangeSummary = transaction {
        if (CategoriesT.selectAll().where { CategoriesT.id eq id }.empty())
            return@transaction ChangeSummary("none", "category", id, detail = "no existe")
        unlinkTripRounds(RoundsT.selectAll().where { RoundsT.categoryId eq id }.mapNotNull { it[RoundsT.id] })
        val children = StandingsT.deleteWhere { categoryId eq id } +
            RoundsT.deleteWhere { categoryId eq id } +
            DriversT.deleteWhere { categoryId eq id }
        StandingsIngest.forgetCategory(id)
        CategoriesT.deleteWhere { CategoriesT.id eq id }
        ChangeSummary("deleted", "category", id, detail = "$children filas hijas (posiciones/fechas/pilotos)")
    }

    fun replaceStandings(categoryId: String, standings: List<Standing>): ChangeSummary = transaction {
        // Normalizado: cada posición REFERENCIA a un piloto de la categoría — por `driverRef`
        // o, compatible con la captura previa, por su número (debe ser inequívoco).
        // Número/nombre/equipo viven en Pilotos y se resuelven al leer.
        val drivers = DriversT.selectAll().where { DriversT.categoryId eq categoryId }.toList()
        val refs = drivers.map { it[DriversT.ref] }.toSet()
        val problems = mutableListOf<String>()
        val resolved = standings.map { s ->
            val ref = s.driverRef?.trim()?.ifBlank { null }
            when {
                ref != null -> ref.also { if (it !in refs) problems += "driverRef $it" }
                s.driverNumber > 0 -> {
                    val byNumber = drivers.filter {
                        it[DriversT.numberText] == s.driverNumber.toString() || (it[DriversT.numberText] == null && it[DriversT.number] == s.driverNumber)
                    }
                    when (byNumber.size) {
                        1 -> byNumber[0][DriversT.ref]
                        0 -> { problems += "número ${s.driverNumber}"; "" }
                        else -> { problems += "número ${s.driverNumber} (lo usan ${byNumber.size} pilotos: usa driverRef)"; "" }
                    }
                }
                else -> { problems += "posición ${s.pos} sin driverRef ni driverNumber"; "" }
            }
        }
        if (problems.isNotEmpty()) {
            return@transaction ChangeSummary(
                "none", "standings", categoryId,
                detail = "pilotos no encontrados en la categoría: ${problems.joinToString()} — captúralos primero en Pilotos",
            )
        }
        val before = StandingsT.deleteWhere { StandingsT.categoryId eq categoryId }
        standings.forEachIndexed { i, s ->
            StandingsT.insert {
                it[StandingsT.categoryId] = categoryId
                it[pos] = s.pos; it[driverRef] = resolved[i]; it[points] = s.points
            }
        }
        ChangeSummary("replaced", "standings", categoryId, detail = "${standings.size} posiciones (antes $before; piloto/equipo se resuelven de Pilotos)")
    }

    /**
     * Reemplaza pilotos Y posiciones de una categoría en UNA transacción (lo usa la
     * ingesta automática: la tabla nueva puede traer pilotos distintos a la anterior).
     */
    /**
     * Reemplazo completo de pilotos + posiciones (la ingesta automática). [photos] = ref → id
     * de su foto; el piloto que no venga ahí conserva la que ya tenía.
     */
    fun replaceStandingsTable(categoryId: String, drivers: List<Driver>, standings: List<Standing>, photos: Map<String, String> = emptyMap()): ChangeSummary = transaction {
        val ds = drivers.map(::normalizeDriver)
        val before = currentPhotos(categoryId)
        StandingsT.deleteWhere { StandingsT.categoryId eq categoryId }
        DriversT.deleteWhere { DriversT.categoryId eq categoryId }
        insertDrivers(categoryId, ds, before + photos)
        standings.forEach { s ->
            StandingsT.insert {
                it[StandingsT.categoryId] = categoryId
                it[pos] = s.pos; it[driverRef] = s.driverRef!!; it[points] = s.points
            }
        }
        ChangeSummary("replaced", "standings", categoryId, detail = "${standings.size} posiciones · ${ds.size} pilotos")
    }

    fun replaceRounds(categoryId: String, rounds: List<Round>): ChangeSummary = transaction {
        // Sede = circuito del catálogo (el nombre se deriva de ahí) o, sin circuito (rallies),
        // `location` en texto. status se deriva de la fecha al leer; winner ya no se captura.
        val circuitNames = CircuitsT.selectAll().where { CircuitsT.deletedAt.isNull() }
            .associate { it[CircuitsT.id] to it[CircuitsT.name] }
        val missing = rounds.mapNotNull { it.circuitId }.distinct().filter { it !in circuitNames }
        if (missing.isNotEmpty())
            return@transaction ChangeSummary("none", "rounds", categoryId, detail = "no existe: circuitId ${missing.joinToString()}")
        val ids = stableRoundIds(categoryId, rounds)
        val newIds = ids.map { it.first }.toSet()
        val removed = RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }
            .mapNotNull { it[RoundsT.id] }.filter { it !in newIds }
        val kept = ids.count { !it.second }
        unlinkTripRounds(removed)
        val before = RoundsT.deleteWhere { RoundsT.categoryId eq categoryId }
        rounds.forEachIndexed { i, r ->
            RoundsT.insert {
                it[RoundsT.categoryId] = categoryId
                it[RoundsT.id] = ids[i].first
                it[number] = r.number; it[date] = r.date.toString()
                it[circuitName] = r.circuitId?.let(circuitNames::get) ?: r.circuitName
                it[status] = EventStatus.UPCOMING.name; it[winner] = null; it[circuitId] = r.circuitId
                it[name] = r.name?.trim()?.ifBlank { null }
                it[location] = if (r.circuitId == null) r.location?.trim()?.ifBlank { null } else null
                it[startDate] = r.startDate?.toString()
            }
        }
        ChangeSummary(
            "replaced", "rounds", categoryId,
            detail = "${rounds.size} fechas (antes $before; $kept ids conservados; estatus derivado, circuito del catálogo)",
        )
    }

    /**
     * Ids ESTABLES para el reemplazo del calendario (la planeación de los oficiales se liga
     * a la carrera): se conserva el id de la fila vieja que corresponde a cada fila nueva —
     * 1) el `id` que mande el body, 2) misma sede + mismo nombre, 3) misma sede y la fecha
     * más cercana a ≤45 días (reprogramaciones, cambios de patrocinador). Si no hay pareja,
     * id nuevo. Devuelve (id, esNuevo) en el orden de [rounds].
     */
    private fun stableRoundIds(categoryId: String, rounds: List<Round>): List<Pair<String, Boolean>> {
        data class Old(val id: String, val venue: String, val name: String?, val date: String)
        fun venue(circuitId: String?, location: String?) = circuitId ?: location?.trim()?.lowercase().orEmpty()
        val old = RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }.mapNotNull {
            val id = it[RoundsT.id] ?: return@mapNotNull null
            Old(id, venue(it[RoundsT.circuitId], it[RoundsT.location]), it[RoundsT.name]?.trim()?.lowercase(), it[RoundsT.date])
        }
        val out = arrayOfNulls<String>(rounds.size)
        val used = mutableSetOf<String>()
        rounds.forEachIndexed { i, r ->
            if (r.id != null && old.any { it.id == r.id } && r.id !in used) { out[i] = r.id; used += r.id!! }
        }
        rounds.forEachIndexed { i, r ->
            if (out[i] != null) return@forEachIndexed
            val v = venue(r.circuitId, r.location)
            val n = r.name?.trim()?.lowercase()
            old.firstOrNull { it.id !in used && n != null && it.venue == v && it.name == n }?.let { out[i] = it.id; used += it.id }
        }
        rounds.forEachIndexed { i, r ->
            if (out[i] != null) return@forEachIndexed
            val v = venue(r.circuitId, r.location)
            old.filter { it.id !in used && it.venue == v }
                .map { it to kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it.date), java.time.LocalDate.parse(r.date.toString()))) }
                .filter { it.second <= 45 }
                .minByOrNull { it.second }
                ?.let { out[i] = it.first.id; used += it.first.id }
        }
        return out.map { if (it != null) it to false else uuidv7() to true }
    }

    /** Planeación ligada a carreras que dejan de existir: queda sin vínculo (no se borra). */
    private fun unlinkTripRounds(roundIds: Collection<String>) {
        if (roundIds.isEmpty()) return
        TripItemsT.update({ TripItemsT.roundId inList roundIds }) { it[TripItemsT.roundId] = null }
    }

    fun replaceDrivers(categoryId: String, drivers: List<Driver>): ChangeSummary = transaction {
        // Las posiciones referencian pilotos por ref: no se puede quitar un piloto que la
        // tabla de posiciones aún usa (quita antes su fila de Posiciones).
        val ds = drivers.map(::normalizeDriver)
        val incoming = ds.map { it.ref!! }.toSet()
        val referenced = StandingsT.selectAll().where { StandingsT.categoryId eq categoryId }
            .map { it[StandingsT.driverRef] }.distinct().filter { it !in incoming }
        if (referenced.isNotEmpty()) {
            return@transaction ChangeSummary(
                "none", "drivers", categoryId,
                detail = "pilotos referenciados por Posiciones: ${referenced.joinToString()} — quítalos de Posiciones antes",
            )
        }
        val photos = currentPhotos(categoryId) // la captura manual no toca las fotos
        val before = DriversT.deleteWhere { DriversT.categoryId eq categoryId }
        insertDrivers(categoryId, ds, photos)
        ChangeSummary("replaced", "drivers", categoryId, detail = "${drivers.size} pilotos (antes $before)")
    }

    private fun currentPhotos(categoryId: String): Map<String, String> =
        DriversT.selectAll().where { DriversT.categoryId eq categoryId }
            .mapNotNull { r -> r[DriversT.photo]?.let { r[DriversT.ref] to it } }.toMap()

    private fun insertDrivers(categoryId: String, ds: List<Driver>, photos: Map<String, String> = emptyMap()) {
        ds.forEachIndexed { i, d ->
            DriversT.insert {
                it[DriversT.categoryId] = categoryId
                it[ref] = d.ref!!; it[number] = d.number; it[numberText] = d.numberText
                it[ord] = i; it[name] = d.name; it[team] = d.team; it[photo] = photos[d.ref]
            }
        }
    }

    // —— Convocatorias ——

    fun allConvocatorias(): List<Convocatoria> = transaction {
        DomainRepository.convocatorias(past = false) + DomainRepository.convocatorias(past = true)
    }

    fun upsertConvocatoria(c: Convocatoria): ChangeSummary = transaction {
        val exists = ConvocatoriasT.selectAll().where { ConvocatoriasT.id eq c.id }.any()
        val write: ConvocatoriasT.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { st ->
            st[eventName] = c.eventName
            st[eventDate] = c.eventDate
            st[location] = c.location
            st[registrationCloseAt] = c.registrationCloseAt.toString()
            st[cupo] = c.cupo
            st[indicacionesMarkdown] = c.indicacionesMarkdown
            st[status] = c.status.name
            st[externalApplyUrl] = c.externalApplyUrl
            st[participated] = c.participated
            st[circuitId] = c.circuitId
        }
        if (exists) ConvocatoriasT.update({ ConvocatoriasT.id eq c.id }) { ConvocatoriasT.write(it) }
        else {
            val ord = nextOrd(ConvocatoriasT, ConvocatoriasT.ord)
            ConvocatoriasT.insert { st -> st[id] = c.id; st[ConvocatoriasT.ord] = ord; ConvocatoriasT.write(st) }
        }
        ChangeSummary(if (exists) "updated" else "created", "convocatoria", c.id)
    }

    fun deleteConvocatoria(id: String): ChangeSummary = transaction {
        val n = ConvocatoriasT.deleteWhere { ConvocatoriasT.id eq id }
        if (n == 0) ChangeSummary("none", "convocatoria", id, detail = "no existe")
        else ChangeSummary("deleted", "convocatoria", id)
    }

    // —— Agenda (solo entradas GLOBALES; las personales son de cada oficial) ——

    fun globalAgenda(): List<AgendaEntry> = transaction {
        AgendaEntriesT.selectAll().where { AgendaEntriesT.officerId.isNull() }
            .orderBy(AgendaEntriesT.ord)
            .map {
                AgendaEntry(
                    id = it[AgendaEntriesT.id],
                    kind = com.alephri.elpuesto.model.AgendaKind.valueOf(it[AgendaEntriesT.kind]),
                    title = it[AgendaEntriesT.title],
                    at = it[AgendaEntriesT.at]?.let(kotlinx.datetime.Instant::parse),
                    allDay = it[AgendaEntriesT.allDay],
                    location = it[AgendaEntriesT.location],
                    eventId = it[AgendaEntriesT.eventId],
                    convocatoriaId = it[AgendaEntriesT.convocatoriaId],
                )
            }
    }

    fun upsertGlobalAgendaEntry(e: AgendaEntry): ChangeSummary = transaction {
        val row = AgendaEntriesT.selectAll().where { AgendaEntriesT.id eq e.id }.firstOrNull()
        if (row != null && row[AgendaEntriesT.officerId] != null)
            return@transaction ChangeSummary("none", "agenda", e.id, detail = "la entrada es personal de un oficial, no global")
        val write: AgendaEntriesT.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { st ->
            st[kind] = e.kind.name
            st[title] = e.title
            st[at] = e.at?.toString()
            st[allDay] = e.allDay
            st[location] = e.location
            st[eventId] = e.eventId
            st[convocatoriaId] = e.convocatoriaId
            st[officerId] = null
        }
        if (row != null) AgendaEntriesT.update({ AgendaEntriesT.id eq e.id }) { AgendaEntriesT.write(it) }
        else {
            val ord = nextOrd(AgendaEntriesT, AgendaEntriesT.ord)
            AgendaEntriesT.insert { st -> st[id] = e.id; st[AgendaEntriesT.ord] = ord; AgendaEntriesT.write(st) }
        }
        ChangeSummary(if (row != null) "updated" else "created", "agenda", e.id)
    }

    fun deleteGlobalAgendaEntry(id: String): ChangeSummary = transaction {
        val row = AgendaEntriesT.selectAll().where { AgendaEntriesT.id eq id }.firstOrNull()
            ?: return@transaction ChangeSummary("none", "agenda", id, detail = "no existe")
        if (row[AgendaEntriesT.officerId] != null)
            return@transaction ChangeSummary("none", "agenda", id, detail = "la entrada es personal de un oficial, no global")
        AgendaEntriesT.deleteWhere { AgendaEntriesT.id eq id }
        ChangeSummary("deleted", "agenda", id)
    }

    // —— Cuentas ——

    fun listAccounts(status: String?): List<Account> = transaction {
        val base = Accounts.selectAll()
        val filtered = if (status != null) base.where { Accounts.status eq status } else base
        filtered.map {
            Account(
                it[Accounts.email], it[Accounts.officerId],
                com.alephri.elpuesto.model.AccountStatus.valueOf(it[Accounts.status]),
            )
        }
    }

    fun upsertAccount(email: String, officerId: String?, status: com.alephri.elpuesto.model.AccountStatus): ChangeSummary =
        transaction {
            if (officerId != null && Officers.selectAll().where { Officers.id eq officerId }.empty())
                return@transaction ChangeSummary("none", "account", email, detail = "no existe: officerId '$officerId'")
            val exists = Accounts.selectAll().where { Accounts.email eq email }.any()
            if (exists) {
                Accounts.update({ Accounts.email eq email }) {
                    it[Accounts.officerId] = officerId; it[Accounts.status] = status.name
                }
            } else {
                Accounts.insert {
                    it[Accounts.email] = email; it[Accounts.officerId] = officerId; it[Accounts.status] = status.name
                }
            }
            ChangeSummary(if (exists) "updated" else "created", "account", email)
        }
}

/**
 * Piloto listo para guardar: número como texto (el de la captura o el legado numérico),
 * legado numérico derivado del texto y `ref` = la del body, o el número, o el nombre.
 */
internal fun normalizeDriver(d: Driver): Driver {
    val text = d.numberText?.trim()?.ifBlank { null } ?: d.number.takeIf { it > 0 }?.toString()
    val ref = d.ref?.trim()?.ifBlank { null } ?: text ?: driverSlug(d.name)
    return d.copy(
        name = d.name.trim(), team = d.team.trim(),
        numberText = text, number = text?.toIntOrNull() ?: 0, ref = ref,
    )
}

/** "Pérez, Sergio" → "perez-sergio" (llave de respaldo cuando no hay número ni id). */
internal fun driverSlug(name: String): String =
    java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(120)
