package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AgendaAssignment
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.AgendaRace
import com.alephri.elpuesto.model.Area
import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.AssetType
import com.alephri.elpuesto.model.AttendanceEntry
import com.alephri.elpuesto.model.Category
import com.alephri.elpuesto.model.Championship
import com.alephri.elpuesto.model.Chat
import com.alephri.elpuesto.model.ChatMember
import com.alephri.elpuesto.model.ChatType
import com.alephri.elpuesto.model.ChecklistItem
import com.alephri.elpuesto.model.Circuit
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.ConvocatoriaStatus
import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.model.OfficerStats
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.PuestoMate
import com.alephri.elpuesto.model.Round
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.Standing
import com.alephri.elpuesto.model.SystemRole
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.model.Trazado
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Rol de asignación que hace a un oficial jefe de su puesto. */
const val CHIEF_ROLE = com.alephri.elpuesto.model.OperationalRoles.CHIEF

/**
 * Roles que hacen a un oficial JEFE de su posición (puesto o activo tripulado): de aquí
 * derivan el guard "máx. uno por posición", el chip CMP y el acceso a emergencia — SOLO
 * desde el roster ([Assignments]); las participaciones declaradas nunca dan permisos.
 */
val CHIEF_ROLES = com.alephri.elpuesto.model.OperationalRoles.CHIEFS

/**
 * Dominio servido desde Postgres (tablas normalizadas). Sin datos de prueba: arranca vacío
 * y los datos reales llegan por la API admin (`data/` + `scripts/cargar-datos.py`).
 */
object DomainRepository {

    fun init() = transaction {
        SchemaUtils.create(
            Officers, Events, Assignments, SessionsT, ChecklistItems,
            CircuitsT, TrazadosT, PuestosT, TrackAssetsT,
            SeriesT, ChampionshipsT, CategoriesT, StandingsT, RoundsT, DriversT,
            ConvocatoriasT, AgendaEntriesT, ChatsT,
            EmergencyInfoT, EmergencyAccessesT, TripItemsT, MessagesT,
            ImagesT, ChatMembersT, ChatReadsT, MessageReportsT,
        )
        // Migración 2026-09-25: el piloto se identifica por `ref` (no por su número) y
        // la posición lo referencia por driver_ref. Las filas previas usan su número.
        exec(
            """
            DO $$ BEGIN
              IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                             WHERE table_name = 'drivers' AND column_name = 'ref') THEN
                ALTER TABLE drivers ADD COLUMN ref varchar(120);
                ALTER TABLE drivers ADD COLUMN number_text varchar(8);
                UPDATE drivers SET ref = number::text, number_text = number::text;
                ALTER TABLE drivers ALTER COLUMN ref SET NOT NULL;
                ALTER TABLE drivers DROP CONSTRAINT IF EXISTS pk_drivers;
                ALTER TABLE drivers ADD CONSTRAINT pk_drivers PRIMARY KEY (category_id, ref);
              END IF;
              IF EXISTS (SELECT 1 FROM information_schema.columns
                         WHERE table_name = 'standings' AND column_name = 'driver_number') THEN
                ALTER TABLE standings ADD COLUMN IF NOT EXISTS driver_ref varchar(120);
                UPDATE standings SET driver_ref = driver_number::text;
                ALTER TABLE standings ALTER COLUMN driver_ref SET NOT NULL;
                ALTER TABLE standings DROP COLUMN driver_number;
              END IF;
            END $$
            """.trimIndent(),
        )
        // Columnas/tablas agregadas después sobre una DB viva (creator_id, media_type,
        // path_json del trazado y su tabla geo, lat/lon absolutas de puestos/activos).
        SchemaUtils.createMissingTablesAndColumns(Events, DriversT, ParticipationsT, PuestoProposalsT, StandingsIngestT, StandingsReadingsT, SeriesT, ChampionshipsT, ChatsT, ChatInvitesT, ChatInviteDeclinesT, OfficerBlocksT, ContentReportsT, LocationShareNoticesT, MessagesT, CircuitsT, TrazadosT, TrazadoGeoT, PuestosT, TrackAssetsT, RoundsT, EventTrazadosT, EventChampionshipsT, ChecklistStateT, ChecklistCompletionsT, AttendanceT, ConvocatoriasT, AgendaEntriesT, TripItemsT, MessageReportsT, LocationSettingsT, LocationSharesT, LocationHiddenT)
        // Migración 2026-09-25: cada carrera del calendario tiene id ESTABLE (la
        // planeación de los oficiales se liga a ella); las filas previas lo reciben aquí.
        RoundsT.selectAll().where { RoundsT.id.isNull() }.toList().forEach { r ->
            RoundsT.update({ (RoundsT.categoryId eq r[RoundsT.categoryId]) and (RoundsT.number eq r[RoundsT.number]) }) {
                it[RoundsT.id] = uuidv7()
            }
        }
        // Migración 2026-09-25: el CAMPEONATO (serie) se separa de la TEMPORADA. Cada
        // temporada sin campeonato lo obtiene de su nombre ("Fórmula E 2025-26" → campeonato
        // "Fórmula E", temporada "2025-26"; sin sufijo → la etiqueta es el año) y su logo
        // pasa al campeonato (el primero que haya).
        ChampionshipsT.selectAll().where { ChampionshipsT.seriesId.isNull() }
            .orderBy(ChampionshipsT.ord to SortOrder.ASC).toList().forEach { row ->
                val raw = row[ChampionshipsT.name].trim()
                val m = Regex("""^(.*?)\s+(\d{4}(?:-\d{2})?)$""").find(raw)
                val seriesName = m?.groupValues?.get(1) ?: raw
                val label = m?.groupValues?.get(2) ?: row[ChampionshipsT.season].toString()
                val sid = SeriesT.selectAll().where { SeriesT.name eq seriesName }.firstOrNull()?.get(SeriesT.id)
                    ?: uuidv7().also { newId ->
                        val next = SeriesT.selectAll().count().toInt()
                        SeriesT.insert { it[id] = newId; it[ord] = next; it[name] = seriesName; it[emblemUrl] = null }
                    }
                ChampionshipsT.update({ ChampionshipsT.id eq row[ChampionshipsT.id] }) {
                    it[seriesId] = sid; it[seasonLabel] = label; it[name] = seriesName
                }
                val hasLogo = SeriesT.selectAll().where { SeriesT.id eq sid }.first()[SeriesT.emblemUrl] != null
                val logo = ImagesT.selectAll()
                    .where { (ImagesT.kind eq "championship") and (ImagesT.ownerId eq row[ChampionshipsT.id]) }.toList()
                if (!hasLogo && logo.isNotEmpty()) {
                    logo.forEach { img ->
                        ImagesT.insert {
                            it[kind] = "series"; it[ownerId] = sid; it[variant] = img[ImagesT.variant]
                            it[contentType] = img[ImagesT.contentType]; it[data] = img[ImagesT.data]
                        }
                    }
                    SeriesT.update({ SeriesT.id eq sid }) { it[emblemUrl] = "/images/series/$sid/full" }
                }
            }
        // Los logos por temporada (kind "championship") ya se copiaron a su campeonato: fuera.
        ImagesT.deleteWhere { ImagesT.kind eq "championship" }
        ChampionshipsT.update({ ChampionshipsT.emblemUrl.isNotNull() }) { it[emblemUrl] = null }
        // Migración 2026-08-01: el checklist se marca POR PUESTO (tabla checklist_state,
        // con quién/cuándo); la columna done global del template desaparece.
        exec("ALTER TABLE checklist_items DROP COLUMN IF EXISTS done")
        // Migración 2026-07-28: un evento puede usar varios trazados → tabla de unión;
        // los eventos existentes migran su trazado único como ord 0.
        val withTrazados = EventTrazadosT.selectAll().map { it[EventTrazadosT.eventId] }.toSet()
        Events.selectAll().forEach { ev ->
            if (ev[Events.id] !in withTrazados) {
                EventTrazadosT.insert {
                    it[eventId] = ev[Events.id]; it[ord] = 0; it[trazadoId] = ev[Events.trazadoId]
                }
            }
        }
        // Migración 2026-07-28: el tipo de activo GRUA se renombró HIAB.
        TrackAssetsT.update({ TrackAssetsT.type eq "GRUA" }) { it[type] = "HIAB" }
        // Migración 2026-07-28: rounds legados con nombre libre → ligar al catálogo
        // de circuitos cuando el nombre coincide exactamente.
        CircuitsT.selectAll().forEach { c ->
            RoundsT.update({ (RoundsT.circuitName eq c[CircuitsT.name]) and RoundsT.circuitId.isNull() }) {
                it[circuitId] = c[CircuitsT.id]
            }
        }
        // Migración 2026-07-28: el turno pasó a catálogo ("Día completo" | "Turno 1".."8");
        // el valor libre legado de la semilla se normaliza.
        Assignments.update({ Assignments.shift eq "Turno completo" }) { it[shift] = "Día completo" }
        // Migración 2026-07-29: "Sector" se eliminó del sistema. En puestos su valor pasa
        // a label (ahí ya vivía el "ID Puesto" del editor del mapa; createMissing ya creó
        // la columna); en asignaciones desaparece.
        exec(
            """
            DO $$ BEGIN
              IF EXISTS (SELECT 1 FROM information_schema.columns
                         WHERE table_name = 'puestos' AND column_name = 'sector') THEN
                UPDATE puestos SET label = sector WHERE label IS NULL;
                ALTER TABLE puestos DROP COLUMN sector;
              END IF;
            END $$
            """.trimIndent(),
        )
        exec("ALTER TABLE assignments DROP COLUMN IF EXISTS sector")
        // Migración 2026-08-01: posiciones normalizadas — nombre/equipo se resuelven del
        // piloto (category_id + driver_number); las columnas denormalizadas se eliminan.
        exec("ALTER TABLE standings DROP COLUMN IF EXISTS driver_name")
        exec("ALTER TABLE standings DROP COLUMN IF EXISTS team")
        // Migración 2026-07-31: la longitud del trazado pasó a METROS enteros (length_m);
        // el legado length_km (decimal) se convierte y se elimina.
        exec(
            """
            DO $$ BEGIN
              IF EXISTS (SELECT 1 FROM information_schema.columns
                         WHERE table_name = 'trazados' AND column_name = 'length_km') THEN
                UPDATE trazados SET length_m = ROUND(length_km * 1000);
                ALTER TABLE trazados DROP COLUMN length_km;
              END IF;
            END $$
            """.trimIndent(),
        )
        // Migración 2026-08-01: el rol de la asignación dejó de ser el enum Area y pasó
        // al catálogo operativo del puesto; los valores legados se mapean a su
        // equivalente más cercano (dato de dev).
        mapOf(
            "BANDERAS" to "Bandera Amarilla", "INTERVENCION" to "Intervención 1",
            "COMUNICACION" to "Comunicador", "CRONOMETRAJE" to "Comunicador", "RESCATE" to "Bombero 1",
        ).forEach { (old, new) ->
            Assignments.update({ Assignments.role eq old }) { it[role] = new }
        }
        // Migración 2026-09-25: el rol TSP del roster de OMDAI se llama "Panel de luz"
        // (antes "Track Safety Personnel"); asignaciones y registros por honor se renombran.
        Assignments.update({ Assignments.role eq "Track Safety Personnel" }) { it[role] = "Panel de luz" }
        ParticipationsT.update({ ParticipationsT.role eq "Track Safety Personnel" }) { it[role] = "Panel de luz" }
        // Migración 2026-08-01: las áreas del oficial cambiaron de catálogo
        // (Intervención, Comunicación, Recovery, Escrutinio, Médico); los valores que
        // desaparecieron se mapean al más cercano.
        mapOf("BANDERAS" to "INTERVENCION", "CRONOMETRAJE" to "ESCRUTINIO", "RESCATE" to "RECOVERY")
            .forEach { (old, new) -> Officers.update({ Officers.area eq old }) { it[area] = new } }
        // Migración 2026-08-01: los compañeros de puesto se derivan de las asignaciones
        // (mismo puesto; jefe = rol "Chief Post Marshal") — la tabla dedicada desaparece.
        exec("DROP TABLE IF EXISTS puesto_mates")
        // Migración 2026-09-24: el historial de eventos se DERIVA de las asignaciones de
        // eventos terminados; la tabla capturada (texto libre, solo semilla) desaparece.
        exec("DROP TABLE IF EXISTS officer_history")
        // Migración 2026-09-25: "asignado antes" se DERIVA del historial del visor al leer;
        // el flag capturado por puesto (igual para todos los oficiales) desaparece.
        exec("ALTER TABLE puestos DROP COLUMN IF EXISTS assigned_before")
        // Migración 2026-08-01: los chats de EVENTO se ligan a su evento por id
        // (Chat.eventId); los legados se ligan por nombre exacto y cada evento sin chat
        // recibe el suyo (el detalle del evento en el admin lo usa como canal de Control).
        Events.selectAll().forEach { ev ->
            ChatsT.update({
                (ChatsT.type eq ChatType.EVENT.name) and ChatsT.eventId.isNull() and (ChatsT.name eq ev[Events.name])
            }) { it[eventId] = ev[Events.id] }
        }
        Events.selectAll().forEach { ev -> ensureEventChat(ev[Events.id], ev[Events.name]) }
        Events.selectAll().forEach { ev -> ensurePuestoChats(ev[Events.id]) }
    }

    // —— Lecturas ——

    /** Perfil con estadísticas derivadas; con [viewerId] ajeno incluye "juntos". */
    fun officer(id: String, viewerId: String? = null): Officer? = transaction {
        Officers.selectAll().where { Officers.id eq id }.firstOrNull()?.toOfficer()?.let {
            it.copy(stats = derivedStatsTx(id, it.stats.activeSince, viewerId))
        }
    }

    private const val ACENTOS = "áàäâéèëêíìïîóòöôúùüûñç"
    private const val SIN_ACENTOS = "aaaaeeeeiiiioooouuuunc"

    /** Quita acentos (mismo mapeo que [ACENTOS] → [SIN_ACENTOS] en SQL). */
    private fun sinAcentos(s: String) = s.map { c -> ACENTOS.indexOf(c).let { i -> if (i >= 0) SIN_ACENTOS[i] else c } }.joinToString("")

    /**
     * Búsqueda de oficiales para elegir a quién compartir ubicación / invitar — NO es una
     * función social (privacy-first §1). Matching: nombre (cada palabra, sin acentos ni mayúsculas),
     * OMDAI ID (prefijo si [rawQ] es numérico) y correo SOLO por igualdad exacta contra
     * la cuenta (nunca por prefijo, para no permitir adivinar correos; la respuesta
     * tampoco lleva ningún correo — `Officer` no tiene ese campo). Excluye a [viewerId].
     */
    /** Tope de resultados de [searchOfficers]: elegir a alguien, no recorrer el directorio. */
    const val SEARCH_MAX = 10

    /**
     * Búsqueda para ELEGIR a un oficial (compartir ubicación, invitar a un chat): por nombre
     * (palabras de 3+ letras, sin acentos) o por el inicio de su OMDAI ID (3+ dígitos).
     * Nunca por correo (era un oráculo de "¿este correo es oficial?") y como mucho
     * [SEARCH_MAX] resultados: no sirve para bajarse el directorio completo.
     */
    fun searchOfficers(rawQ: String, viewerId: String): List<Officer> = transaction {
        val q = rawQ.trim()
        val soloDigitos = q.isNotEmpty() && q.all { it.isDigit() }
        // Por palabras, sin acentos ni mayúsculas: "alan perez" encuentra a "Alan Pérez
        // Ramírez" (en el teléfono casi nadie teclea acentos). Cada palabra debe aparecer;
        // los comodines de LIKE del usuario se buscan literales.
        val palabras = sinAcentos(q.lowercase()).split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") }
        val suficiente = if (soloDigitos) q.length >= 3 else palabras.any { it.length >= 3 }
        if (!suficiente) return@transaction emptyList()
        val nombre = CustomFunction<String>(
            "translate", VarCharColumnType(),
            Officers.displayName.lowerCase(), stringLiteral(ACENTOS), stringLiteral(SIN_ACENTOS),
        )
        Officers.selectAll()
            .where {
                var cond: Op<Boolean> = palabras.map<String, Op<Boolean>> { nombre like "%$it%" }.reduce { a, b -> a and b }
                if (soloDigitos) {
                    cond = cond or (Officers.omdaiId.castTo(VarCharColumnType(32)) like "$q%")
                }
                cond and (Officers.id neq viewerId)
            }
            .orderBy(Officers.displayName to SortOrder.ASC)
            .limit(SEARCH_MAX)
            .map { it.toOfficer() }
    }

    fun activeEvents(): List<Event> = transaction {
        Events.selectAll().where { Events.active eq true }.map { it.toEvent() }
    }

    fun event(id: String): Event? = transaction {
        Events.selectAll().where { Events.id eq id }.firstOrNull()?.toEvent()
    }

    /**
     * Id de POSICIÓN (puesto o activo tripulado) → nombre para MOSTRAR: el label del
     * editor del trazado ("MP 1" / "TH1" / "IFRT4"); fallback "P N" si el puesto no tiene
     * label. Incluye posiciones archivadas (historial).
     */
    fun puestoDisplayNames(puestoIds: Collection<String>): Map<String, String> =
        if (puestoIds.isEmpty()) {
            emptyMap()
        } else {
            val ids = puestoIds.toSet()
            PuestosT.selectAll().where { PuestosT.id inList ids }.associate {
                it[PuestosT.id] to (it[PuestosT.label]?.ifBlank { null } ?: "P ${it[PuestosT.number]}")
            } + TrackAssetsT.selectAll().where { TrackAssetsT.id inList ids }.associate {
                it[TrackAssetsT.id] to it[TrackAssetsT.label]
            }
        }

    fun assignmentFor(eventId: String, officerId: String): Assignment? = transaction {
        Assignments.selectAll().where { (Assignments.eventId eq eventId) and (Assignments.officerId eq officerId) }
            .firstOrNull()?.let {
                Assignment(
                    id = it[Assignments.id], eventId = it[Assignments.eventId], officerId = it[Assignments.officerId],
                    role = it[Assignments.role], puestoId = it[Assignments.puestoId],
                    puestoNumber = it[Assignments.puestoNumber], shift = it[Assignments.shift],
                    puestoLabel = puestoDisplayNames(listOf(it[Assignments.puestoId]))[it[Assignments.puestoId]] ?: "",
                )
            }
    }

    /**
     * Compañeros DERIVADOS de las asignaciones: los oficiales asignados a la MISMA
     * posición (puesto o activo) que [viewerId] en el evento (jefe = rol de [CHIEF_ROLES]).
     * Sin asignación propia no hay posición de referencia → lista vacía.
     */
    fun mates(eventId: String, viewerId: String): List<PuestoMate> = transaction {
        val mine = Assignments.selectAll()
            .where { (Assignments.eventId eq eventId) and (Assignments.officerId eq viewerId) }
            .firstOrNull() ?: return@transaction emptyList()
        val puesto = mine[Assignments.puestoId]
        Assignments.join(Officers, org.jetbrains.exposed.sql.JoinType.INNER, onColumn = Assignments.officerId, otherColumn = Officers.id)
            .selectAll().where { (Assignments.eventId eq eventId) and (Assignments.puestoId eq puesto) }
            .map {
                PuestoMate(
                    officerId = it[Assignments.officerId],
                    displayName = it[Officers.displayName],
                    area = it[Officers.area]?.let(Area::valueOf) ?: Area.INTERVENCION,
                    isChief = it[Assignments.role] in CHIEF_ROLES,
                    role = it[Assignments.role],
                )
            }
            .sortedByDescending { it.isChief }
    }

    fun schedule(eventId: String): List<Session> = transaction {
        val list = SessionsT.selectAll().where { SessionsT.eventId eq eventId }
            .orderBy(SessionsT.ord to SortOrder.ASC)
            .map {
                Session(
                    id = it[SessionsT.id], eventId = it[SessionsT.eventId],
                    day = LocalDate.parse(it[SessionsT.day]), time = LocalTime.parse(it[SessionsT.time]),
                    category = it[SessionsT.category], name = it[SessionsT.name],
                    status = EventStatus.valueOf(it[SessionsT.status]), endsInMin = it[SessionsT.endsInMin],
                )
            }
        deriveSessionStatus(list)
    }

    /**
     * Estatus de cada actividad DERIVADO del reloj al leer (la columna guardada es solo
     * informativa): EN CURSO desde su inicio hasta su fin — `endsInMin` (duración) o, sin
     * duración, el inicio de la siguiente actividad del MISMO día (la última sin duración
     * termina con su día).
     */
    private fun deriveSessionStatus(list: List<Session>): List<Session> {
        if (list.isEmpty()) return list
        val now = java.time.LocalDateTime.now()
        fun startOf(s: Session): java.time.LocalDateTime = java.time.LocalDateTime.of(
            java.time.LocalDate.parse(s.day.toString()), java.time.LocalTime.parse(s.time.toString()),
        )
        val byStart = list.sortedWith(compareBy({ it.day.toString() }, { it.time.toString() }))
        val endById = byStart.mapIndexed { i, s ->
            val start = startOf(s)
            val end = when {
                s.endsInMin != null -> start.plusMinutes(s.endsInMin!!.toLong())
                else -> byStart.getOrNull(i + 1)?.takeIf { it.day == s.day }?.let(::startOf)
                    ?: start.toLocalDate().atTime(java.time.LocalTime.MAX)
            }
            s.id to end
        }.toMap()
        return list.map { s ->
            val start = startOf(s)
            val derived = when {
                now.isBefore(start) -> EventStatus.UPCOMING
                now.isBefore(endById.getValue(s.id)) -> EventStatus.LIVE
                else -> EventStatus.FINISHED
            }
            if (derived == s.status) s else s.copy(status = derived)
        }
    }

    /**
     * Zona del cronograma de OMDAI: el "día" del checklist corta a medianoche CDMX.
     * Si algún día un evento cruza medianoche con actividad, basta mover este corte
     * (p. ej. a las 04:00) — es el ÚNICO lugar que define "hoy".
     */
    private val ZONA_CHECKLIST = java.time.ZoneId.of("America/Mexico_City")

    /** Inicio de HOY (CDMX) como Instant ISO-UTC, comparable con marked_at (lexicográfico). */
    private fun checklistHoyDesdeIso(): String =
        java.time.LocalDate.now(ZONA_CHECKLIST).atStartOfDay(ZONA_CHECKLIST).toInstant().toString()

    /**
     * El checklist se reinicia CADA DÍA (mismas revisiones todos los días del evento;
     * el histórico no importa — decisión de producto): borra las marcas que no son de
     * HOY. Sin cron: el primer lector/escritor del día limpia. true si borró algo
     * (para que el caller emita al bus FUERA de la transacción).
     */
    private fun purgeChecklistViejoTx(eventId: String): Boolean {
        val corte = checklistHoyDesdeIso()
        return ChecklistStateT.deleteWhere {
            (ChecklistStateT.eventId eq eventId) and
                (ChecklistStateT.markedAt.isNull() or (ChecklistStateT.markedAt less corte))
        } > 0
    }

    /**
     * Checklist del evento PERSONALIZADO: el `done` es el estado del PUESTO asignado a
     * [viewerId] (cada puesto marca su propia copia). Sin viewer o sin asignación, la
     * plantilla llega sin marcar (es el caso del editor admin).
     */
    fun checklist(eventId: String, viewerId: String? = null): List<ChecklistItem> {
        var purged = false
        val list = transaction {
            purged = purgeChecklistViejoTx(eventId)
            val puesto = viewerId?.let { off ->
                Assignments.selectAll().where { (Assignments.eventId eq eventId) and (Assignments.officerId eq off) }
                    .firstOrNull()?.get(Assignments.puestoId)
            }
            val state = puesto?.let { p ->
                ChecklistStateT.selectAll().where { (ChecklistStateT.eventId eq eventId) and (ChecklistStateT.puestoId eq p) }
                    .associate { it[ChecklistStateT.itemId] to it[ChecklistStateT.done] }
            } ?: emptyMap()
            ChecklistItems.selectAll().where { ChecklistItems.eventId eq eventId }
                .orderBy(ChecklistItems.ord to SortOrder.ASC)
                .map { ChecklistItem(id = it[ChecklistItems.id], text = it[ChecklistItems.text], done = state[it[ChecklistItems.id]] ?: false) }
        }
        // El reseteo diario también es un cambio: los conectados refetchean y amanecen limpios.
        if (purged) ChangeBus.emit(eventId, "checklist")
        return list
    }

    /**
     * Persiste el toggle del checklist (destino real del outbox de la app) en el estado
     * del PUESTO del oficial, con quién/cuándo. false = el oficial no tiene asignación
     * en el evento del ítem (no hay puesto al cual marcar).
     */
    fun setChecklistDone(itemId: String, done: Boolean, officerId: String): Boolean {
        var changedEvent: String? = null
        val ok = setChecklistDoneTx(itemId, done, officerId) { changedEvent = it }
        if (ok) ChangeBus.emit(changedEvent, "checklist")
        return ok
    }

    private fun setChecklistDoneTx(itemId: String, done: Boolean, officerId: String, onEvent: (String) -> Unit): Boolean = transaction {
        val item = ChecklistItems.selectAll().where { ChecklistItems.id eq itemId }.firstOrNull()
            ?: return@transaction false
        val evId = item[ChecklistItems.eventId]
        // Solo durante el evento ACTIVO: el checklist es de hoy, en pista (no se marca antes
        // ni después, ni en eventos a los que ya no se va).
        if (Events.selectAll().where { (Events.id eq evId) and (Events.active eq true) }.none()) return@transaction false
        onEvent(evId)
        // El primer toggle del día escribe sobre tabla limpia (nunca revive marcas de ayer).
        purgeChecklistViejoTx(evId)
        val puesto = Assignments.selectAll().where { (Assignments.eventId eq evId) and (Assignments.officerId eq officerId) }
            .firstOrNull()?.get(Assignments.puestoId) ?: return@transaction false
        val at = java.time.Instant.now().toString()
        val exists = ChecklistStateT.selectAll()
            .where { (ChecklistStateT.itemId eq itemId) and (ChecklistStateT.puestoId eq puesto) }.any()
        if (exists) {
            ChecklistStateT.update({ (ChecklistStateT.itemId eq itemId) and (ChecklistStateT.puestoId eq puesto) }) {
                it[ChecklistStateT.done] = done; it[markedBy] = officerId; it[markedAt] = at
            }
        } else {
            ChecklistStateT.insert {
                it[ChecklistStateT.itemId] = itemId; it[ChecklistStateT.puestoId] = puesto; it[ChecklistStateT.eventId] = evId
                it[ChecklistStateT.done] = done; it[markedBy] = officerId; it[markedAt] = at
            }
        }
        recordChecklistCompletionTx(evId, puesto)
        true
    }

    /**
     * Registro diario para "Puesto impecable": si con este toggle la posición tiene HOY toda
     * la plantilla marcada, queda la fila del día; si deja de estarlo, se quita. El estado
     * del checklist se purga cada día; este registro no.
     */
    private fun recordChecklistCompletionTx(evId: String, puesto: String) {
        val items = ChecklistItems.selectAll().where { ChecklistItems.eventId eq evId }.map { it[ChecklistItems.id] }.toSet()
        val done = ChecklistStateT.selectAll()
            .where { (ChecklistStateT.eventId eq evId) and (ChecklistStateT.puestoId eq puesto) and (ChecklistStateT.done eq true) }
            .count { it[ChecklistStateT.itemId] in items }
        val hoy = hoyOperativo()
        ChecklistCompletionsT.deleteWhere {
            (ChecklistCompletionsT.eventId eq evId) and (ChecklistCompletionsT.puestoId eq puesto) and (ChecklistCompletionsT.day eq hoy)
        }
        if (items.isNotEmpty() && done >= items.size) {
            ChecklistCompletionsT.insert {
                it[ChecklistCompletionsT.eventId] = evId; it[ChecklistCompletionsT.puestoId] = puesto; it[ChecklistCompletionsT.day] = hoy
            }
        }
    }

    /** Estado del checklist de TODOS los puestos del evento (visor del admin). Solo HOY: el avance se reinicia a diario. */
    fun checklistState(eventId: String): List<ChecklistPuestoState> {
        var purged = false
        val list = transaction {
            purged = purgeChecklistViejoTx(eventId)
            ChecklistStateT.selectAll().where { ChecklistStateT.eventId eq eventId }.map {
                ChecklistPuestoState(
                    itemId = it[ChecklistStateT.itemId], puestoId = it[ChecklistStateT.puestoId],
                    done = it[ChecklistStateT.done], markedBy = it[ChecklistStateT.markedBy], markedAt = it[ChecklistStateT.markedAt],
                )
            }
        }
        if (purged) ChangeBus.emit(eventId, "checklist")
        return list
    }

    /** Día operativo de HOY (mismo corte a medianoche CDMX que el checklist). */
    private fun hoyOperativo(): String = java.time.LocalDate.now(ZONA_CHECKLIST).toString()

    private fun attendanceEntry(it: ResultRow) = AttendanceEntry(
        officerId = it[AttendanceT.officerId], day = LocalDate.parse(it[AttendanceT.day]),
        present = it[AttendanceT.present], markedBy = it[AttendanceT.markedBy],
        markedAt = it[AttendanceT.markedAt], puestoId = it[AttendanceT.puestoId],
    )

    /**
     * Pase de lista PERSONALIZADO de un día ([day] ISO; default HOY CDMX): el jefe de la
     * posición ve las marcas de SU posición (por el snapshot puesto_id); cualquier otro
     * oficial ve SOLO la suya (transparencia read-only). Sin asignación → vacío.
     * Es registro histórico: aquí no hay purga (a diferencia del checklist).
     */
    fun attendance(eventId: String, viewerId: String, day: String? = null): List<AttendanceEntry> = transaction {
        val d = day ?: hoyOperativo()
        val mine = Assignments.selectAll()
            .where { (Assignments.eventId eq eventId) and (Assignments.officerId eq viewerId) }
            .firstOrNull() ?: return@transaction emptyList()
        val base = (AttendanceT.eventId eq eventId) and (AttendanceT.day eq d)
        val filter = if (mine[Assignments.role] in CHIEF_ROLES) {
            base and (AttendanceT.puestoId eq mine[Assignments.puestoId])
        } else {
            base and (AttendanceT.officerId eq viewerId)
        }
        AttendanceT.selectAll().where { filter }.map(::attendanceEntry)
    }

    /**
     * Marca del pase de lista: SOLO el jefe de la posición del oficial, SOLO sobre HOY
     * (CDMX) y SOLO en el evento activo. present=null borra la marca (vuelve a "sin
     * marcar"). Devuelve null si se aplicó, o el motivo del rechazo (para el Ack — un
     * rechazo real hace que el outbox de la app descarte la operación).
     */
    fun setAttendance(eventId: String, targetId: String, present: Boolean?, byId: String): String? {
        val reason = transaction {
            val ev = Events.selectAll().where { Events.id eq eventId }.firstOrNull()
                ?: return@transaction "evento no encontrado"
            if (!ev[Events.active]) return@transaction "el pase de lista solo aplica al evento activo"
            val mine = Assignments.selectAll()
                .where { (Assignments.eventId eq eventId) and (Assignments.officerId eq byId) }
                .firstOrNull() ?: return@transaction "sin asignación en el evento"
            if (mine[Assignments.role] !in CHIEF_ROLES) return@transaction "solo el jefe de la posición puede pasar lista"
            val target = Assignments.selectAll()
                .where { (Assignments.eventId eq eventId) and (Assignments.officerId eq targetId) }
                .firstOrNull() ?: return@transaction "el oficial no tiene asignación en el evento"
            val puesto = mine[Assignments.puestoId]
            if (target[Assignments.puestoId] != puesto) return@transaction "el oficial no es de tu posición"
            val d = hoyOperativo()
            val key = (AttendanceT.eventId eq eventId) and (AttendanceT.day eq d) and (AttendanceT.officerId eq targetId)
            if (present == null) {
                AttendanceT.deleteWhere { key }
            } else {
                val at = java.time.Instant.now().toString()
                val exists = AttendanceT.selectAll().where { key }.any()
                if (exists) {
                    AttendanceT.update({ key }) {
                        it[AttendanceT.present] = present; it[markedBy] = byId; it[markedAt] = at; it[puestoId] = puesto
                    }
                } else {
                    AttendanceT.insert {
                        it[AttendanceT.eventId] = eventId; it[AttendanceT.day] = d; it[officerId] = targetId
                        it[AttendanceT.present] = present; it[markedBy] = byId; it[markedAt] = at; it[puestoId] = puesto
                    }
                }
            }
            null
        }
        if (reason == null) ChangeBus.emit(eventId, "attendance")
        return reason
    }

    /** Registro COMPLETO de asistencia del evento (visor admin): todos los días y posiciones. */
    fun attendanceAll(eventId: String, day: String? = null): List<AttendanceEntry> = transaction {
        val base = AttendanceT.eventId eq eventId
        val filter = day?.let { base and (AttendanceT.day eq it) } ?: base
        AttendanceT.selectAll().where { filter }
            .orderBy(AttendanceT.day to SortOrder.ASC)
            .map(::attendanceEntry)
    }

    // Los catálogos EXCLUYEN lo borrado lógicamente; los datos siguen en la tabla
    // porque eventos/asignaciones históricos los referencian.
    fun circuits(withMain: Boolean = false): List<Circuit> = transaction {
        val alive = TrazadosT.selectAll().where { TrazadosT.deletedAt.isNull() }
            .orderBy(TrazadosT.ord to SortOrder.ASC).toList()
        val counts = alive.groupingBy { it[TrazadosT.circuitId] }.eachCount()
        // El principal (el primero) con su silueta, solo para la galería de la app.
        val main = if (!withMain) emptyMap() else alive.groupBy { it[TrazadosT.circuitId] }.mapValues { (_, rs) ->
            val t = rs.first()
            Trazado(
                t[TrazadosT.id], t[TrazadosT.circuitId], t[TrazadosT.name], t[TrazadosT.lengthM], t[TrazadosT.curves],
                t[TrazadosT.direction], t[TrazadosT.mapUrl], path = parsePath(t[TrazadosT.pathJson]),
            )
        }
        CircuitsT.selectAll().where { CircuitsT.deletedAt.isNull() }
            .orderBy(CircuitsT.ord to SortOrder.ASC)
            .map {
                Circuit(
                    it[CircuitsT.id], it[CircuitsT.name], it[CircuitsT.location],
                    country = it[CircuitsT.country], trazadoCount = counts[it[CircuitsT.id]] ?: 0,
                    mainTrazado = main[it[CircuitsT.id]],
                )
            }
    }

    fun trazados(circuitId: String): List<Trazado> = transaction {
        TrazadosT.selectAll().where { (TrazadosT.circuitId eq circuitId) and TrazadosT.deletedAt.isNull() }
            .orderBy(TrazadosT.ord to SortOrder.ASC)
            .map {
                Trazado(
                    it[TrazadosT.id], it[TrazadosT.circuitId], it[TrazadosT.name],
                    it[TrazadosT.lengthM], it[TrazadosT.curves], it[TrazadosT.direction], it[TrazadosT.mapUrl],
                    path = parsePath(it[TrazadosT.pathJson]),
                    geoFrame = com.alephri.elpuesto.model.GeoFrame.of(
                        TrazadoGeoT.selectAll().where { TrazadoGeoT.trazadoId eq it[TrazadosT.id] }
                            .map { g -> g[TrazadoGeoT.lat] to g[TrazadoGeoT.lon] },
                    ),
                )
            }
    }

    internal fun parsePath(json: String?): List<MapPoint> = json?.let {
        runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MapPoint.serializer()), it,
            )
        }.getOrNull()
    } ?: emptyList()

    /**
     * Posiciones (puestos o activos) donde [viewerId] ya trabajó: sus asignaciones en
     * eventos TERMINADOS (mismo criterio que su historial). Sin visor = ninguna.
     */
    private fun pastPositionIdsTx(viewerId: String?): Set<String> =
        viewerId?.let { v -> pastAssignmentsTx(v).map { it.positionId }.toSet() }.orEmpty()

    /** [viewerId] deriva "asignado antes" (null en la API admin: siempre false). */
    fun puestos(trazadoId: String, viewerId: String? = null): List<Puesto> = transaction {
        val mine = pastPositionIdsTx(viewerId)
        PuestosT.selectAll().where { (PuestosT.trazadoId eq trazadoId) and PuestosT.deletedAt.isNull() }
            .orderBy(PuestosT.number to SortOrder.ASC)
            .map {
                Puesto(
                    it[PuestosT.id], it[PuestosT.trazadoId], it[PuestosT.number], it[PuestosT.label],
                    MapPoint(it[PuestosT.x], it[PuestosT.y]), assignedBefore = it[PuestosT.id] in mine,
                    lat = it[PuestosT.lat], lon = it[PuestosT.lon], onMap = it[PuestosT.onMap],
                )
            }
    }

    fun assets(trazadoId: String, viewerId: String? = null): List<TrackAsset> = transaction {
        val mine = pastPositionIdsTx(viewerId)
        TrackAssetsT.selectAll().where { (TrackAssetsT.trazadoId eq trazadoId) and TrackAssetsT.deletedAt.isNull() }
            .map {
                TrackAsset(
                    it[TrackAssetsT.id], it[TrackAssetsT.trazadoId], AssetType.valueOf(it[TrackAssetsT.type]),
                    it[TrackAssetsT.label], MapPoint(it[TrackAssetsT.x], it[TrackAssetsT.y]),
                    lat = it[TrackAssetsT.lat], lon = it[TrackAssetsT.lon],
                    assignedBefore = it[TrackAssetsT.id] in mine,
                )
            }
    }

    /** Campeonatos (series), en el orden del catálogo. */
    fun series(): List<com.alephri.elpuesto.model.Series> = transaction {
        SeriesT.selectAll().orderBy(SeriesT.ord to SortOrder.ASC).map {
            com.alephri.elpuesto.model.Series(it[SeriesT.id], it[SeriesT.name], it[SeriesT.emblemUrl])
        }
    }

    /**
     * Temporadas ([seriesId] null = todas), con nombre y logo de su campeonato y su rango de
     * fechas derivado del calendario. Orden: el del campeonato y, dentro, la más reciente primero.
     */
    fun championships(seriesId: String? = null): List<Championship> = transaction {
        val series = SeriesT.selectAll().associateBy { it[SeriesT.id] }
        val catChamp = CategoriesT.selectAll().associate { it[CategoriesT.id] to it[CategoriesT.championshipId] }
        val ranges = RoundsT.selectAll().groupBy { catChamp[it[RoundsT.categoryId]] }.mapValues { (_, rs) ->
            rs.minOf { it[RoundsT.startDate] ?: it[RoundsT.date] } to rs.maxOf { it[RoundsT.date] }
        }
        val catsBy = CategoriesT.selectAll().orderBy(CategoriesT.ord to SortOrder.ASC)
            .groupBy({ it[CategoriesT.championshipId] }, { it[CategoriesT.id] to it[CategoriesT.name] })
        ChampionshipsT.selectAll()
            .where { if (seriesId == null) Op.TRUE else ChampionshipsT.seriesId eq seriesId }
            .map { row ->
                val s = row[ChampionshipsT.seriesId]?.let(series::get)
                val range = ranges[row[ChampionshipsT.id]]
                // Avance y siguiente fecha: calendario de la categoría principal (la primera),
                // con el estatus por reloj de rounds() (terminada = FINISHED).
                val cats = catsBy[row[ChampionshipsT.id]].orEmpty()
                val mainRounds = cats.firstOrNull()?.let { rounds(it.first) }.orEmpty().sortedBy { it.date }
                Championship(
                    id = row[ChampionshipsT.id],
                    name = s?.get(SeriesT.name) ?: row[ChampionshipsT.name],
                    season = row[ChampionshipsT.season],
                    emblemUrl = s?.get(SeriesT.emblemUrl),
                    seriesId = row[ChampionshipsT.seriesId].orEmpty(),
                    seasonLabel = row[ChampionshipsT.seasonLabel] ?: row[ChampionshipsT.season].toString(),
                    startsOn = range?.first?.let(LocalDate::parse), endsOn = range?.second?.let(LocalDate::parse),
                    roundsTotal = mainRounds.size,
                    roundsDone = mainRounds.count { it.status == EventStatus.FINISHED },
                    nextRound = mainRounds.firstOrNull { it.status != EventStatus.FINISHED },
                    categoryNames = cats.map { it.second },
                ) to ((s?.get(SeriesT.ord) ?: Int.MAX_VALUE) to row[ChampionshipsT.ord])
            }
            .sortedWith(compareBy<Pair<Championship, Pair<Int, Int>>>({ it.second.first }, { -it.first.season }, { it.second.second }))
            .map { it.first }
    }

    fun categories(championshipId: String): List<Category> = transaction {
        val rows = CategoriesT.selectAll().where { CategoriesT.championshipId eq championshipId }
            .orderBy(CategoriesT.ord to SortOrder.ASC).toList()
        // Crédito y fecha de las posiciones: de la ingesta automática (si la categoría la tiene).
        val ingest = StandingsIngestT.selectAll().where { StandingsIngestT.categoryId inList rows.map { it[CategoriesT.id] } }
            .associateBy { it[StandingsIngestT.categoryId] }
        rows.map {
            val ing = ingest[it[CategoriesT.id]]
            Category(
                it[CategoriesT.id], it[CategoriesT.championshipId], it[CategoriesT.name],
                standingsCredit = ing?.let { r -> StandingsSources.credit(r[StandingsIngestT.sourceId]) },
                standingsThroughRound = ing?.get(StandingsIngestT.throughRound),
            )
        }
    }

    fun standings(categoryId: String): List<Standing> = transaction {
        // Número, nombre y equipo salen del PILOTO referenciado (normalizado); el fallback
        // solo cubre datos inconsistentes (el admin valida la referencia al escribir).
        StandingsT.join(
            DriversT, org.jetbrains.exposed.sql.JoinType.LEFT,
            additionalConstraint = { (StandingsT.categoryId eq DriversT.categoryId) and (StandingsT.driverRef eq DriversT.ref) },
        )
            .selectAll().where { StandingsT.categoryId eq categoryId }
            .orderBy(StandingsT.pos to SortOrder.ASC)
            .map {
                Standing(
                    pos = it[StandingsT.pos],
                    driverNumber = it.getOrNull(DriversT.number) ?: 0,
                    driverName = it.getOrNull(DriversT.name) ?: it[StandingsT.driverRef],
                    team = it.getOrNull(DriversT.team) ?: "",
                    points = it[StandingsT.points],
                    numberText = it.getOrNull(DriversT.numberText),
                    driverRef = it[StandingsT.driverRef],
                    photoUrl = it.getOrNull(DriversT.photo)?.let { id -> "/images/driver/$id/full" },
                )
            }
    }

    fun rounds(categoryId: String): List<Round> = transaction {
        // Orden cronológico (ISO = lexicográfico): una fecha reprogramada conserva su número
        // oficial aunque caiga después de la siguiente (NASCAR México 2026: la 9 va tras la 10).
        val rows = RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }
            .orderBy(RoundsT.date to SortOrder.ASC, RoundsT.number to SortOrder.ASC).toList()
        // Nombre y ubicación del circuito se derivan del catálogo (incluye archivados).
        val circuits = CircuitsT.selectAll().where { CircuitsT.id inList rows.mapNotNull { it[RoundsT.circuitId] }.toSet() }
            .associate { it[CircuitsT.id] to (it[CircuitsT.name] to it[CircuitsT.location]) }
        rows.map {
            val circuit = it[RoundsT.circuitId]?.let(circuits::get)
            val start = it[RoundsT.startDate] ?: it[RoundsT.date]
            Round(
                number = it[RoundsT.number], date = LocalDate.parse(it[RoundsT.date]),
                circuitName = circuit?.first ?: it[RoundsT.circuitName],
                // Estatus por reloj: antes del fin de semana = próxima, durante = en curso.
                status = eventStatus(start, it[RoundsT.date]),
                winner = it[RoundsT.winner], circuitId = it[RoundsT.circuitId],
                name = it[RoundsT.name], location = circuit?.second ?: it[RoundsT.location],
                startDate = it[RoundsT.startDate]?.let(LocalDate::parse),
                id = it[RoundsT.id],
            )
        }
    }

    fun drivers(categoryId: String): List<Driver> = transaction {
        DriversT.selectAll().where { DriversT.categoryId eq categoryId }
            .orderBy(DriversT.ord to SortOrder.ASC)
            .map {
                Driver(
                    number = it[DriversT.number], name = it[DriversT.name], team = it[DriversT.team],
                    numberText = it[DriversT.numberText], ref = it[DriversT.ref],
                )
            }
    }

    fun convocatorias(past: Boolean): List<Convocatoria> = transaction {
        val wanted = if (past) ConvocatoriaStatus.CLOSED else ConvocatoriaStatus.OPEN
        ConvocatoriasT.selectAll().where { ConvocatoriasT.status eq wanted.name }
            .orderBy(ConvocatoriasT.ord to SortOrder.ASC)
            .map {
                Convocatoria(
                    id = it[ConvocatoriasT.id], eventName = it[ConvocatoriasT.eventName],
                    eventDate = it[ConvocatoriasT.eventDate], location = it[ConvocatoriasT.location],
                    registrationCloseAt = Instant.parse(it[ConvocatoriasT.registrationCloseAt]),
                    cupo = it[ConvocatoriasT.cupo], indicacionesMarkdown = it[ConvocatoriasT.indicacionesMarkdown],
                    status = wanted, externalApplyUrl = it[ConvocatoriasT.externalApplyUrl],
                    participated = it[ConvocatoriasT.participated],
                    circuitId = it[ConvocatoriasT.circuitId],
                )
            }
    }

    /**
     * Agenda: entradas globales (officer_id null) + las del oficial + su planeación
     * personal (trip items propios proyectados como entradas del calendario, editables)
     * + los eventos operativos donde TRABAJA (derivados de sus asignaciones, decisión
     * 2026-09-25) + TODAS las fechas de los calendarios de campeonato (proyección de solo
     * lectura; decisión 2026-07-28: todos los oficiales conocen todas las fechas).
     */
    fun agenda(officerId: String): List<AgendaEntry> = transaction {
        val system = AgendaEntriesT.selectAll()
            .where { AgendaEntriesT.officerId.isNull() or (AgendaEntriesT.officerId eq officerId) }
            .orderBy(AgendaEntriesT.ord to SortOrder.ASC)
            .map {
                AgendaEntry(
                    id = it[AgendaEntriesT.id], kind = AgendaKind.valueOf(it[AgendaEntriesT.kind]),
                    title = it[AgendaEntriesT.title], at = it[AgendaEntriesT.at]?.let(Instant::parse),
                    allDay = it[AgendaEntriesT.allDay], location = it[AgendaEntriesT.location],
                    eventId = it[AgendaEntriesT.eventId],
                    convocatoriaId = it[AgendaEntriesT.convocatoriaId],
                )
            }
        val personal = TripItemsT.selectAll()
            .where { TripItemsT.officerId eq officerId }
            .orderBy(TripItemsT.ord to SortOrder.ASC)
            .mapNotNull {
                val tripKind = com.alephri.elpuesto.model.TripItemKind.valueOf(it[TripItemsT.kind])
                // Fotos y notas son momentos de la bitácora; no van al calendario de agenda.
                if (tripKind == com.alephri.elpuesto.model.TripItemKind.PHOTO ||
                    tripKind == com.alephri.elpuesto.model.TripItemKind.NOTE
                ) return@mapNotNull null
                AgendaEntry(
                    id = it[TripItemsT.id],
                    kind = if (tripKind == com.alephri.elpuesto.model.TripItemKind.REMINDER) AgendaKind.REMINDER else AgendaKind.TRIP,
                    title = it[TripItemsT.title], at = it[TripItemsT.at]?.let(Instant::parse),
                    allDay = false, location = it[TripItemsT.detail],
                    eventId = it[TripItemsT.eventId], personal = true, tripKind = tripKind,
                    convocatoriaId = it[TripItemsT.convocatoriaId],
                    roundId = it[TripItemsT.roundId], endsAt = it[TripItemsT.endsAt]?.let(Instant::parse),
                )
            }
        // Fechas de campeonato: UNA entrada por fin de semana de cada campeonato (ver
        // [weekendsTx]); los fines de semana que coinciden con un evento operativo donde
        // el oficial trabaja se FUNDEN en la entrada de ese evento (con su asignación).
        val weekends = weekendsTx()
        val (events, consumed) = assignedEventEntriesTx(officerId, weekends)
        val (registrable, standalone) = registrableEventsTx(events.mapNotNull { it.eventId }.toSet(), weekends)
        val eventIds = events.mapNotNull { it.eventId }.toSet() + standalone.mapNotNull { it.eventId }
        val rounds = weekends.filter { w -> w.first().id !in consumed }.map { w ->
            // Fin de semana con un evento del admin: ahí se registra la participación por honor.
            val ev = registrable[w.first().id]
            weekendEntry(w).let { e ->
                if (ev == null) e
                else e.copy(registrationEventId = ev[Events.id], registration = ParticipationRepository.stateTx(ev, rostered = false))
            }
        }
        // Una entrada manual (admin) del mismo evento sería duplicado de la derivada.
        system.filterNot { it.kind == AgendaKind.EVENT && it.eventId in eventIds } + personal + events + standalone + rounds
    }

    /**
     * Eventos del admin donde el oficial NO trabaja (ni roster ni registro), para ofrecer el
     * registro por honor desde la agenda: los que se funden con un fin de semana del
     * calendario (carrera principal → evento) y, aparte, los que tienen autoregistro
     * permitido y no caen en ningún fin de semana (entrada propia `evt-<id>`; los cerrados
     * sin fin de semana siguen sin aparecer: solo los ve quien está en su roster).
     */
    private fun registrableEventsTx(mine: Set<String>, weekends: List<List<CalRace>>): Pair<Map<String, ResultRow>, List<AgendaEntry>> {
        val byWeekend = mutableMapOf<String, ResultRow>()
        val standalone = mutableListOf<AgendaEntry>()
        val others = Events.selectAll().toList().filter { it[Events.id] !in mine }
        if (others.isEmpty()) return byWeekend to standalone
        val circuitNames = CircuitsT.selectAll().where { CircuitsT.id inList others.map { it[Events.circuitId] }.toSet() }
            .associate { it[CircuitsT.id] to it[CircuitsT.name] }
        others.sortedBy { it[Events.startsOn] }.forEach { ev ->
            val merged = eventWeekends(ev, weekends)
            merged.forEach { w -> byWeekend.putIfAbsent(w.first().id, ev) }
            if (merged.isEmpty() && ev[Events.selfRegistration]) {
                standalone += AgendaEntry(
                    id = "evt-${ev[Events.id]}",
                    kind = AgendaKind.EVENT,
                    title = ev[Events.name],
                    at = Instant.parse("${ev[Events.startsOn]}T18:00:00Z"),
                    allDay = true,
                    location = circuitNames[ev[Events.circuitId]],
                    eventId = ev[Events.id],
                    circuitId = ev[Events.circuitId],
                    startsOn = LocalDate.parse(ev[Events.startsOn]), endsOn = LocalDate.parse(ev[Events.endsOn]),
                    registrationEventId = ev[Events.id],
                    registration = ParticipationRepository.stateTx(ev, rostered = false),
                )
            }
        }
        return byWeekend to standalone
    }

    /** Una carrera del calendario con su contexto (para armar fines de semana). */
    private data class CalRace(
        val id: String, val champId: String, val champName: String, val champEmblem: String?,
        val catName: String, val catOrd: Int, val number: Int, val total: Int, val name: String?, val date: String, val start: String,
        val circuitId: String?, val venue: String,
    )

    /**
     * Fines de semana del calendario: por campeonato, carreras en la MISMA sede con fechas
     * a ≤4 días (NASCAR corre Truck/O'Reilly/Cup vie-sáb-dom; F2/F3 van con la F1).
     */
    private fun weekendsTx(): List<List<CalRace>> {
        // Nombre visible y logo = los del CAMPEONATO (serie), sin la temporada ("Fórmula E").
        val series = SeriesT.selectAll().associate { it[SeriesT.id] to (it[SeriesT.name] to it[SeriesT.emblemUrl]) }
        val champSeries = ChampionshipsT.selectAll().associate {
            it[ChampionshipsT.id] to (it[ChampionshipsT.seriesId]?.let(series::get) ?: (it[ChampionshipsT.name] to null))
        }
        val cats = CategoriesT.selectAll().associate {
            it[CategoriesT.id] to Triple(it[CategoriesT.championshipId], it[CategoriesT.name], it[CategoriesT.ord])
        }
        val circuitNames = CircuitsT.selectAll().associate { it[CircuitsT.id] to it[CircuitsT.name] }
        val rows = RoundsT.selectAll().toList()
        val totals = rows.groupingBy { it[RoundsT.categoryId] }.eachCount()
        return rows.mapNotNull { row ->
            val (champ, catName, ord) = cats[row[RoundsT.categoryId]] ?: return@mapNotNull null
            val id = row[RoundsT.id] ?: return@mapNotNull null
            val venue = row[RoundsT.circuitId]?.let { circuitNames[it] } ?: row[RoundsT.location] ?: row[RoundsT.circuitName]
            CalRace(
                id = id, champId = champ, champName = champSeries[champ]?.first ?: champ,
                champEmblem = champSeries[champ]?.second, catName = catName, catOrd = ord,
                number = row[RoundsT.number], total = totals[row[RoundsT.categoryId]] ?: 0, name = row[RoundsT.name],
                date = row[RoundsT.date], start = row[RoundsT.startDate] ?: row[RoundsT.date],
                circuitId = row[RoundsT.circuitId], venue = venue,
            )
        }
            .groupBy { it.champId }
            .flatMap { (_, list) ->
                val weekends = mutableListOf<MutableList<CalRace>>()
                list.sortedBy { it.date }.forEach { r ->
                    val w = weekends.lastOrNull { it.first().venue == r.venue && diasEntre(it.first().date, r.date) <= 4 }
                    if (w != null) w += r else weekends += mutableListOf(r)
                }
                // La primera carrera de cada lista es la PRINCIPAL (categoría principal del
                // campeonato si corre ese fin de semana): es el ancla estable de la entrada.
                weekends.map { w -> w.sortedWith(compareBy<CalRace>({ it.catOrd }, { it.date })) }
            }
    }

    private fun racesOf(w: List<CalRace>): List<AgendaRace> {
        val main = w.first()
        return w.sortedWith(compareBy<CalRace>({ it.date }, { it.catOrd })).map {
            AgendaRace(
                roundId = it.id, categoryName = it.catName, number = it.number, total = it.total,
                name = it.name, date = LocalDate.parse(it.date), main = it.id == main.id,
            )
        }
    }

    private fun weekendEntry(w: List<CalRace>): AgendaEntry {
        val main = w.first()
        return AgendaEntry(
            id = "rnd-${main.id}",
            kind = AgendaKind.EVENT,
            title = "${main.champName} · ${main.name ?: "Fecha ${main.number}"}",
            // Mediodía CDMX para que la fecha no se corra de día en ninguna zona.
            at = Instant.parse("${main.date}T18:00:00Z"),
            allDay = true,
            location = main.venue,
            roundId = main.id, championshipId = main.champId, championshipName = main.champName,
            championshipEmblemUrl = main.champEmblem,
            circuitId = main.circuitId,
            startsOn = LocalDate.parse(w.minOf { it.start }), endsOn = LocalDate.parse(w.maxOf { it.date }),
            races = racesOf(w),
        )
    }

    /** Fines de semana de los campeonatos del evento que se cruzan con sus fechas. */
    private fun eventWeekends(eventRow: ResultRow, weekends: List<List<CalRace>>): List<List<CalRace>> {
        val champs = EventChampionshipsT.selectAll().where { EventChampionshipsT.eventId eq eventRow[Events.id] }
            .map { it[EventChampionshipsT.championshipId] }.toSet()
        if (champs.isEmpty()) return emptyList()
        val from = eventRow[Events.startsOn]
        val to = eventRow[Events.endsOn]
        return weekends.filter { w ->
            w.first().champId in champs && w.minOf { it.start } <= to && w.maxOf { it.date } >= from
        }
    }

    /**
     * Eventos operativos donde [officerId] trabaja — por el roster o por su registro por
     * honor (el roster manda) —, como entradas de agenda ("Trabajas este evento"), fundidas
     * con sus fines de semana de campeonato. Devuelve también los ids (de la carrera
     * principal) de los fines de semana consumidos.
     */
    private fun assignedEventEntriesTx(officerId: String, weekends: List<List<CalRace>>): Pair<List<AgendaEntry>, Set<String>> {
        val mine = Assignments.selectAll().where { Assignments.officerId eq officerId }.toList()
        val byEvent = mine.associateBy { it[Assignments.eventId] }
        val declared = ParticipationsT.selectAll().where { ParticipationsT.officerId eq officerId }
            .filter { it[ParticipationsT.eventId] !in byEvent.keys }
            .associateBy { it[ParticipationsT.eventId] }
        if (byEvent.isEmpty() && declared.isEmpty()) return emptyList<AgendaEntry>() to emptySet()
        val labels = puestoDisplayNames(mine.map { it[Assignments.puestoId] } + declared.values.mapNotNull { it[ParticipationsT.positionId] })
        val proposed = ProposalRepository.pendingLabelsTx(declared.values.mapNotNull { it[ParticipationsT.proposalId] })
        val consumed = mutableSetOf<String>()
        val entries = Events.selectAll().where { Events.id inList (byEvent.keys + declared.keys) }.map { ev ->
            val a = byEvent[ev[Events.id]]
            val d = declared[ev[Events.id]]
            val merged = eventWeekends(ev, weekends)
            merged.forEach { consumed += it.first().id }
            val main = merged.firstOrNull()?.first()
            // Compañeros de posición: roster + declarados en la misma (sin puesto = 0).
            val positionId = a?.get(Assignments.puestoId) ?: d?.get(ParticipationsT.positionId)
            val mates = if (positionId == null) 0 else {
                val roster = Assignments.selectAll().where {
                    (Assignments.eventId eq ev[Events.id]) and (Assignments.puestoId eq positionId)
                }.map { it[Assignments.officerId] }.toSet()
                val honor = ParticipationsT.selectAll().where {
                    (ParticipationsT.eventId eq ev[Events.id]) and (ParticipationsT.positionId eq positionId)
                }.map { it[ParticipationsT.officerId] }.toSet()
                ((roster + honor) - officerId).size
            }
            val circuitName = CircuitsT.selectAll().where { CircuitsT.id eq ev[Events.circuitId] }
                .firstOrNull()?.get(CircuitsT.name)
            AgendaEntry(
                id = "evt-${ev[Events.id]}",
                kind = AgendaKind.EVENT,
                title = ev[Events.name],
                at = Instant.parse("${ev[Events.startsOn]}T18:00:00Z"),
                allDay = true,
                location = circuitName,
                eventId = ev[Events.id],
                roundId = main?.id, championshipId = main?.champId, championshipName = main?.champName,
                championshipEmblemUrl = main?.champEmblem,
                circuitId = ev[Events.circuitId],
                startsOn = LocalDate.parse(ev[Events.startsOn]), endsOn = LocalDate.parse(ev[Events.endsOn]),
                races = merged.flatMap { racesOf(it) }.sortedBy { it.date },
                assigned = true,
                assignment = if (a != null) {
                    AgendaAssignment(
                        position = labels[a[Assignments.puestoId]] ?: "P ${a[Assignments.puestoNumber]}",
                        role = a[Assignments.role], shift = a[Assignments.shift].orEmpty(),
                        mates = mates,
                    )
                } else {
                    val pending = if (d!![ParticipationsT.positionId] == null) d[ParticipationsT.proposalId]?.let(proposed::get) else null
                    AgendaAssignment(
                        position = d[ParticipationsT.positionId]?.let(labels::get) ?: pending.orEmpty(),
                        role = d[ParticipationsT.role], mates = mates, declared = true, positionPending = pending != null,
                    )
                },
                registrationEventId = ev[Events.id],
                registration = ParticipationRepository.stateTx(ev, rostered = a != null),
            )
        }
        return entries to consumed
    }

    /** Carreras del calendario fundidas con el evento (su planeación también es del evento). */
    private fun eventRoundIdsTx(eventId: String): Set<String> {
        val ev = Events.selectAll().where { Events.id eq eventId }.firstOrNull() ?: return emptySet()
        return eventWeekends(ev, weekendsTx()).flatten().map { it.id }.toSet()
    }

    fun roundExists(id: String): Boolean = transaction {
        RoundsT.selectAll().where { RoundsT.id eq id }.count() > 0
    }

    /**
     * Chats con `joined` personalizado (públicos según membresía; evento/puesto siempre
     * unidos) y `unread` REAL por lector: mensajes ajenos con ord > su marca de lectura
     * ([ChatReadsT]; sin marca = todo sin leer). Archivados y públicos no unidos = 0.
     */
    fun chats(officerId: String): List<Chat> = transaction {
        val myChats = ChatMembersT.selectAll().where { ChatMembersT.officerId eq officerId }
            .map { it[ChatMembersT.chatId] }.toSet()
        val reads = ChatReadsT.selectAll().where { ChatReadsT.officerId eq officerId }
            .associateBy({ it[ChatReadsT.chatId] }, { it[ChatReadsT.lastReadOrd] })
        // Privados: se listan si eres miembro o tienes una invitación pendiente (chat → quién invitó).
        val invites = ChatInvitesT.selectAll().where { ChatInvitesT.officerId eq officerId }
            .associate { it[ChatInvitesT.chatId] to it[ChatInvitesT.inviterId] }
        // Los chats de evento/puesto VIVOS solo se listan para el evento ACTIVO (misma
        // convención que canSeeChat/chatMembers: eventId null = el activo). Al desactivar
        // un evento, sus chats desaparecen del listado; los archivados siguen (historial).
        // Y solo a quien le tocan: el de evento a los asignados al evento; el de puesto a
        // los asignados a ESA posición (mismo criterio que canSeeChat).
        val activeEventId = Events.selectAll().where { Events.active eq true }.firstOrNull()?.get(Events.id)
        val mine = Assignments.selectAll().where { Assignments.officerId eq officerId }
            .map { it[Assignments.eventId] to it[Assignments.puestoId] }.toSet()
        val myEvents = mine.map { it.first }.toSet()
        val rows = ChatsT.selectAll().orderBy(ChatsT.ord to SortOrder.ASC)
            .filter { row ->
                val type = ChatType.valueOf(row[ChatsT.type])
                if (type == ChatType.PUBLIC) return@filter true
                if (type == ChatType.PRIVATE) return@filter row[ChatsT.id] in myChats || row[ChatsT.id] in invites
                val chatEvent = row[ChatsT.eventId] ?: activeEventId ?: return@filter false
                if (!row[ChatsT.archived] && chatEvent != activeEventId) return@filter false
                if (type == ChatType.PUESTO) row[ChatsT.puestoId]?.let { (chatEvent to it) in mine } == true
                else chatEvent in myEvents
            }
        val puestoNames = puestoChatNames(rows.mapNotNull { it[ChatsT.puestoId] })
        // Públicos/privados ligados a un evento: su nombre (para la etiqueta en la lista).
        val linkedEvents = rows.filter { isGroupChat(it[ChatsT.type]) }.mapNotNull { it[ChatsT.eventId] }.distinct()
        val eventNames = if (linkedEvents.isEmpty()) emptyMap()
            else Events.selectAll().where { Events.id inList linkedEvents }.associate { it[Events.id] to it[Events.name] }
        val inviterNames = if (invites.isEmpty()) emptyMap()
            else Officers.selectAll().where { Officers.id inList invites.values.distinct() }
                .associate { it[Officers.id] to it[Officers.displayName] }
        rows
            .map {
                val type = ChatType.valueOf(it[ChatsT.type])
                val id = it[ChatsT.id]
                val group = isGroupChat(it[ChatsT.type])
                val chatEvent = it[ChatsT.eventId] ?: activeEventId
                // Miembros de evento/puesto = asignados (derivado, como chatMembers).
                val members = when {
                    group || chatEvent == null -> it[ChatsT.membersCount]
                    type == ChatType.PUESTO -> Assignments.selectAll().where {
                        (Assignments.eventId eq chatEvent) and (Assignments.puestoId eq (it[ChatsT.puestoId] ?: ""))
                    }.map { a -> a[Assignments.officerId] }.distinct().size
                    else -> Assignments.selectAll().where { Assignments.eventId eq chatEvent }
                        .map { a -> a[Assignments.officerId] }.distinct().size
                }
                val joined = !group || id in myChats
                // Invitación pendiente (públicos y privados): de un privado se ve que existe,
                // no lo que se dice.
                val pendingInvite = !joined && id in invites
                // No leídos contados en SQL (antes se traían todos los mensajes del chat).
                val unread = if (!joined || it[ChatsT.archived]) 0 else {
                    val lastRead = reads[id] ?: 0
                    MessagesT.selectAll().where {
                        (MessagesT.chatId eq id) and (MessagesT.ord greater lastRead) and
                            (MessagesT.senderId.isNull() or (MessagesT.senderId neq officerId))
                    }.count().toInt()
                }
                Chat(
                    id = id, type = type, name = it[ChatsT.puestoId]?.let(puestoNames::get) ?: it[ChatsT.name],
                    membersCount = members,
                    lastPreview = if (pendingInvite && type == ChatType.PRIVATE) null else it[ChatsT.lastPreview],
                    unread = unread, archived = it[ChatsT.archived],
                    archivedAt = it[ChatsT.archivedAt]?.let(Instant::parse),
                    joined = joined,
                    description = it[ChatsT.description],
                    creatorId = it[ChatsT.creatorId],
                    eventId = it[ChatsT.eventId],
                    eventName = if (group) it[ChatsT.eventId]?.let(eventNames::get) else null,
                    invitedBy = if (pendingInvite) invites[id]?.let { inv -> inviterNames[inv] ?: "Alguien" } else null,
                )
            }
    }

    /** Nombre de un evento (para etiquetar cambios del stream general). */
    fun eventName(id: String): String? = transaction {
        Events.selectAll().where { Events.id eq id }.firstOrNull()?.get(Events.name)
    }

    /** Nombre del evento de una convocatoria (para etiquetar cambios del stream general). */
    fun convocatoriaName(id: String): String? = transaction {
        ConvocatoriasT.selectAll().where { ConvocatoriasT.id eq id }.firstOrNull()?.get(ConvocatoriasT.eventName)
    }

    /** El chat de EVENTO ligado a [eventId] (no archivado), si existe. */
    fun eventChatId(eventId: String): String? = transaction {
        ChatsT.selectAll()
            .where { (ChatsT.eventId eq eventId) and (ChatsT.type eq ChatType.EVENT.name) and (ChatsT.archived eq false) }
            .firstOrNull()?.get(ChatsT.id)
    }

    /**
     * Garantiza el chat de EVENTO de [eventId]: lo crea si no existe y mantiene su nombre
     * sincronizado con el del evento. Devuelve el chatId.
     */
    fun ensureEventChat(eventId: String, eventName: String): String = transaction {
        val existing = ChatsT.selectAll()
            .where { (ChatsT.eventId eq eventId) and (ChatsT.type eq ChatType.EVENT.name) }
            .firstOrNull()
        if (existing != null) {
            if (existing[ChatsT.name] != eventName) {
                ChatsT.update({ ChatsT.id eq existing[ChatsT.id] }) { it[name] = eventName }
            }
            return@transaction existing[ChatsT.id]
        }
        val id = uuidv7()
        val ord = (ChatsT.selectAll().count() + 1).toInt()
        ChatsT.insert {
            it[ChatsT.id] = id; it[ChatsT.ord] = ord; it[type] = ChatType.EVENT.name
            it[name] = eventName; it[membersCount] = 0; it[joined] = true
            it[ChatsT.eventId] = eventId
        }
        id
    }

    /**
     * Garantiza un chat de PUESTO por cada posición (puesto o activo tripulado) de
     * [eventId] que tenga asignaciones; los de posiciones que se quedaron sin gente se
     * conservan (historial) pero nadie los ve. Idempotente: se llama al reemplazar las
     * asignaciones y al arrancar. Devuelve cuántos creó.
     */
    fun ensurePuestoChats(eventId: String): Int = transaction {
        val positions = Assignments.selectAll().where { Assignments.eventId eq eventId }
            .map { it[Assignments.puestoId] }.distinct()
        val existing = ChatsT.selectAll()
            .where { (ChatsT.eventId eq eventId) and (ChatsT.type eq ChatType.PUESTO.name) }
            .mapNotNull { it[ChatsT.puestoId] }.toSet()
        val missing = positions.filter { it !in existing }
        if (missing.isEmpty()) return@transaction 0
        val names = puestoChatNames(missing)
        var ord = (ChatsT.selectAll().maxOfOrNull { it[ChatsT.ord] } ?: 0)
        missing.forEach { p ->
            ChatsT.insert {
                it[id] = uuidv7(); it[ChatsT.ord] = ++ord; it[type] = ChatType.PUESTO.name
                it[name] = names[p] ?: "Puesto"; it[membersCount] = 0; it[joined] = true
                it[ChatsT.eventId] = eventId; it[puestoId] = p
            }
        }
        missing.size
    }

    /**
     * Nombre visible del chat de cada posición: "Puesto 11.7" para puestos numerados, la
     * etiqueta tal cual para los demás ("MP 1", "Coordinación de zona") y para los activos
     * ("TH3", "IFRT4"). Se deriva al leer: renombrar la posición renombra su chat.
     */
    fun puestoChatNames(positionIds: Collection<String>): Map<String, String> =
        puestoDisplayNames(positionIds).mapValues { (id, label) ->
            val isPuesto = PuestosT.selectAll().where { PuestosT.id eq id }.any()
            if (isPuesto && label.firstOrNull()?.isDigit() == true) "Puesto $label" else label
        }

    /**
     * Mensaje de CONTROL (el admin desde la web): senderId null con nombre visible;
     * no exige oficial ni membresía (el chat de evento es del control de carrera).
     */
    fun sendControlMessage(chatId: String, text: String, senderName: String): com.alephri.elpuesto.model.Message? {
        val msg = transaction {
            val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction null
            if (chat[ChatsT.archived]) return@transaction null
            val id = uuidv7()
            val ord = nextOrdTx(chatId)
            val now = kotlinx.datetime.Clock.System.now()
            MessagesT.insert {
                it[MessagesT.id] = id; it[MessagesT.chatId] = chatId; it[MessagesT.ord] = ord
                it[senderId] = null; it[MessagesT.senderName] = senderName
                it[MessagesT.text] = text; it[at] = now.toString(); it[system] = false
            }
            ChatsT.update({ ChatsT.id eq chatId }) { it[lastPreview] = text.take(120) }
            com.alephri.elpuesto.model.Message(
                id = id, chatId = chatId, senderId = null, senderName = senderName,
                text = text, at = now, system = false,
            )
        }
        if (msg != null) ChangeBus.emit(null, "chat", chatId)
        return msg
    }

    /** Públicos y privados: grupos por membresía (evento/puesto se derivan de asignaciones). */
    private fun isGroupChat(type: String) = type == ChatType.PUBLIC.name || type == ChatType.PRIVATE.name

    /** Siguiente `ord` de un chat: MAX en SQL (antes se traían todos sus mensajes a memoria). */
    private fun nextOrdTx(chatId: String): Int {
        val maxOrd = MessagesT.ord.max()
        return (MessagesT.select(maxOrd).where { MessagesT.chatId eq chatId }.firstOrNull()?.get(maxOrd) ?: 0) + 1
    }

    /** ¿[blockerId] bloqueó a [blockedId]? (dentro de una transacción) */
    internal fun isBlockedTx(blockerId: String, blockedId: String): Boolean =
        OfficerBlocksT.selectAll()
            .where { (OfficerBlocksT.blockerId eq blockerId) and (OfficerBlocksT.blockedId eq blockedId) }.any()

    /** ¿Rechazó (o dejó) este chat? Entonces nadie lo vuelve a invitar a él. */
    private fun declinedTx(chatId: String, officerId: String): Boolean =
        ChatInviteDeclinesT.selectAll()
            .where { (ChatInviteDeclinesT.chatId eq chatId) and (ChatInviteDeclinesT.officerId eq officerId) }.any()

    private fun recordDeclineTx(chatId: String, officerId: String) {
        if (declinedTx(chatId, officerId)) return
        ChatInviteDeclinesT.insert {
            it[ChatInviteDeclinesT.chatId] = chatId; it[ChatInviteDeclinesT.officerId] = officerId
            it[at] = kotlinx.datetime.Clock.System.now().toString()
        }
    }

    /** Solo se liga un chat a un evento que el oficial TRABAJA (tiene asignación). */
    private fun chatEventProblemTx(officerId: String, eventId: String): String? {
        if (Events.selectAll().where { Events.id eq eventId }.none()) return "el evento no existe"
        val assigned = Assignments.selectAll()
            .where { (Assignments.eventId eq eventId) and (Assignments.officerId eq officerId) }.any()
        return if (assigned) null else "solo puedes ligar el chat a un evento que trabajas"
    }

    /** Mensaje de sistema (altas/bajas en privados, invitaciones en públicos). */
    private fun systemMessageTx(chatId: String, text: String) {
        val ord = nextOrdTx(chatId)
        MessagesT.insert {
            it[id] = uuidv7(); it[MessagesT.chatId] = chatId; it[MessagesT.ord] = ord
            it[senderId] = null; it[senderName] = "Sistema"
            it[MessagesT.text] = text
            it[at] = kotlinx.datetime.Clock.System.now().toString(); it[system] = true
        }
    }

    /**
     * Crea un chat público o PRIVADO; el creador queda unido (y puede archivarlo y
     * ligarlo a un evento). En los privados, [inviteeIds] reciben invitación: entran al
     * aceptarla. [eventId] opcional: un evento que el creador trabaja. Devuelve el chat o
     * el motivo del rechazo.
     */
    fun createChat(
        officerId: String,
        name: String,
        description: String?,
        isPrivate: Boolean = false,
        eventId: String? = null,
        inviteeIds: List<String> = emptyList(),
    ): Pair<Chat?, String?> {
        val type = if (isPrivate) ChatType.PRIVATE else ChatType.PUBLIC
        val invited = mutableListOf<String>()
        val result = transaction {
            if (eventId != null) chatEventProblemTx(officerId, eventId)?.let { return@transaction null to it }
            val requested = if (!isPrivate) emptyList() else inviteeIds.distinct().filter { it != officerId }
            val known = if (requested.isEmpty()) emptySet()
                else Officers.selectAll().where { Officers.id inList requested }.map { it[Officers.id] }.toSet()
            if (requested.any { it !in known }) return@transaction null to "algún oficial invitado no existe"
            // Quien bloqueó al creador no recibe la invitación (y el creador no lo sabe).
            val invitees = requested.filterNot { isBlockedTx(it, officerId) }
            val id = uuidv7()
            val ord = (ChatsT.selectAll().count() + 1).toInt()
            ChatsT.insert {
                it[ChatsT.id] = id; it[ChatsT.ord] = ord; it[ChatsT.type] = type.name
                it[ChatsT.name] = name; it[membersCount] = 1; it[lastPreview] = null
                it[unread] = 0; it[archived] = false; it[archivedAt] = null
                it[joined] = false; it[ChatsT.description] = description
                it[creatorId] = officerId; it[ChatsT.eventId] = eventId
            }
            ChatMembersT.insert { it[chatId] = id; it[ChatMembersT.officerId] = officerId }
            val now = kotlinx.datetime.Clock.System.now().toString()
            invitees.forEach { inv ->
                ChatInvitesT.insert {
                    it[chatId] = id; it[ChatInvitesT.officerId] = inv; it[inviterId] = officerId; it[createdAt] = now
                }
                invited += inv
            }
            val evName = eventId?.let { e -> Events.selectAll().where { Events.id eq e }.firstOrNull()?.get(Events.name) }
            Chat(
                id = id, type = type, name = name, membersCount = 1, joined = true, description = description,
                creatorId = officerId, eventId = eventId, eventName = evName,
            ) to null
        }
        result.first?.let { chat -> emitInvites(chat.id, officerId, invited) }
        return result
    }

    /** Aviso "te invitaron" SOLO a cada invitado (el stream general lo filtra por destino). */
    private fun emitInvites(chatId: String, inviterId: String, invitees: List<String>) {
        if (invitees.isEmpty()) return
        val inviterName = officer(inviterId)?.displayName ?: "Alguien"
        invitees.forEach { ChangeBus.emit(null, "chat-invite", chatId = chatId, id = it, detail = inviterName) }
    }

    /** Nombre de un chat (para etiquetar avisos del stream general). */
    fun chatName(id: String): String? = transaction {
        ChatsT.selectAll().where { ChatsT.id eq id }.firstOrNull()?.get(ChatsT.name)
    }

    /**
     * Liga (o desliga con null) un chat público/privado a un evento que el oficial
     * trabaja; solo quien lo creó. Null = ok; texto = motivo del rechazo.
     */
    fun setChatEvent(officerId: String, chatId: String, eventId: String?): String? {
        val problem = transaction {
            val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull()
                ?: return@transaction "chat no encontrado"
            if (!isGroupChat(chat[ChatsT.type])) return@transaction "solo los chats públicos y privados se ligan a un evento"
            if (chat[ChatsT.archived]) return@transaction "el chat está archivado"
            if (chat[ChatsT.creatorId] != officerId || !isMemberTx(officerId, chatId)) {
                return@transaction "solo quien creó el chat (y sigue en él) puede ligarlo a un evento"
            }
            if (eventId != null) chatEventProblemTx(officerId, eventId)?.let { return@transaction it }
            ChatsT.update({ ChatsT.id eq chatId }) { it[ChatsT.eventId] = eventId }
            null
        }
        if (problem == null) ChangeBus.emit(null, "chat", chatId)
        return problem
    }

    /** ¿Tiene [officerId] una invitación pendiente a este chat privado? */
    fun hasChatInvite(officerId: String, chatId: String): Boolean = transaction {
        ChatInvitesT.selectAll().where { (ChatInvitesT.chatId eq chatId) and (ChatInvitesT.officerId eq officerId) }.any()
    }

    /** ¿Puede ver la lista de participantes? Quien lee el chat, y el invitado (para decidir). */
    fun canListMembers(officerId: String, chatId: String): Boolean =
        canReadChat(officerId, chatId) || hasChatInvite(officerId, chatId)

    /**
     * Participantes de un chat: públicos por membresía; evento = todos los asignados del
     * evento activo; puesto = los del MISMO puesto que [viewerId] (derivado de asignaciones).
     * Cada uno lleva su contexto operativo (puesto/rol) del evento del chat; un público o
     * privado sin evento ligado no lleva ninguno ([senderContext]).
     */
    fun chatMembers(chatId: String, viewerId: String): List<ChatMember> = transaction {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction emptyList()
        val ids = if (isGroupChat(chat[ChatsT.type])) {
            ChatMembersT.selectAll().where { ChatMembersT.chatId eq chatId }.map { it[ChatMembersT.officerId] }
        } else {
            val chatEvent = chat[ChatsT.eventId]
                ?: Events.selectAll().where { Events.active eq true }.firstOrNull()?.get(Events.id)
                ?: return@transaction emptyList()
            if (chat[ChatsT.type] == ChatType.PUESTO.name) {
                val pos = chat[ChatsT.puestoId] ?: return@transaction emptyList()
                Assignments.selectAll().where { (Assignments.eventId eq chatEvent) and (Assignments.puestoId eq pos) }
                    .map { it[Assignments.officerId] }.distinct()
            } else {
                Assignments.selectAll().where { Assignments.eventId eq chatEvent }
                    .map { it[Assignments.officerId] }.distinct()
            }
        }
        // Los invitados que aún no aceptan, aparte (transparencia para el grupo).
        val pending = if (!isGroupChat(chat[ChatsT.type])) emptyList()
            else ChatInvitesT.selectAll().where { ChatInvitesT.chatId eq chatId }.map { it[ChatInvitesT.officerId] }
        val ctx = senderContext(chatId)
        (ids.map { it to false } + pending.map { it to true }).mapNotNull { (off, isPending) ->
            Officers.selectAll().where { Officers.id eq off }.firstOrNull()?.toOfficer()?.let { o ->
                val c = ctx[o.id]
                ChatMember(officer = o, puesto = c?.first, role = c?.second, pending = isPending)
            }
        }
    }

    /** Archiva un chat público o privado; solo su creador. */
    fun archiveChat(officerId: String, chatId: String): Boolean = transaction {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction false
        // Su creador, y solo mientras siga en el grupo (quien se salió ya no lo congela).
        if (!isGroupChat(chat[ChatsT.type]) || chat[ChatsT.creatorId] != officerId || !isMemberTx(officerId, chatId)) {
            return@transaction false
        }
        ChatsT.update({ ChatsT.id eq chatId }) {
            it[archived] = true; it[archivedAt] = kotlinx.datetime.Clock.System.now().toString()
        }
        true
    }

    /** ¿El chat es público y del oficial? (guard para archivar). */
    fun ownsPublicChat(officerId: String, chatId: String): Boolean = transaction {
        ChatsT.selectAll().where {
            (ChatsT.id eq chatId) and (ChatsT.type eq ChatType.PUBLIC.name) and (ChatsT.creatorId eq officerId)
        }.any()
    }

    /** ¿El oficial es miembro de un chat público? (guard para actualizar su imagen). */
    /**
     * ¿Puede cambiar la imagen del chat? Públicos: cualquier miembro; de PUESTO: cualquiera
     * asignado a esa posición (los únicos que lo ven). Evento: nadie (usa la del evento).
     */
    fun canSetChatImage(officerId: String, chatId: String): Boolean {
        val chat = transaction { ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() } ?: return false
        if (chat[ChatsT.archived]) return false
        return when (chat[ChatsT.type]) {
            ChatType.PUBLIC.name, ChatType.PRIVATE.name -> isGroupChatMember(officerId, chatId)
            ChatType.PUESTO.name -> canSeeChat(officerId, chatId)
            else -> false
        }
    }

    private fun isMemberTx(officerId: String, chatId: String): Boolean =
        ChatMembersT.selectAll().where { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }.any()

    fun isGroupChatMember(officerId: String, chatId: String): Boolean = transaction {
        val isGroup = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull()?.let { isGroupChat(it[ChatsT.type]) } == true
        isGroup && ChatMembersT.selectAll()
            .where { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }.any()
    }

    /**
     * Deja la marca de lectura de [officerId] en el último mensaje del chat (dentro de
     * una tx). Devuelve true solo si la marca AVANZÓ (para no emitir cambios en vacío:
     * el cliente re-marca al refetchear y eso ciclaría el bus).
     */
    private fun markReadTx(chatId: String, officerId: String, ord: Int? = null): Boolean {
        val lastOrd = ord ?: (nextOrdTx(chatId) - 1)
        val current = ChatReadsT.selectAll()
            .where { (ChatReadsT.chatId eq chatId) and (ChatReadsT.officerId eq officerId) }
            .firstOrNull()?.get(ChatReadsT.lastReadOrd)
        if (current != null && current >= lastOrd) return false
        ChatReadsT.deleteWhere { (ChatReadsT.chatId eq chatId) and (ChatReadsT.officerId eq officerId) }
        ChatReadsT.insert {
            it[ChatReadsT.chatId] = chatId; it[ChatReadsT.officerId] = officerId
            it[lastReadOrd] = lastOrd
        }
        return true
    }

    /**
     * Marca el chat como leído hasta su último mensaje. Si la marca avanzó, avisa por el
     * bus (los demás clientes del oficial refrescan su lista; unread queda en 0 para él).
     */
    fun markChatRead(officerId: String, chatId: String): Boolean {
        val advanced = transaction {
            if (ChatsT.selectAll().where { ChatsT.id eq chatId }.none()) return@transaction null
            markReadTx(chatId, officerId)
        } ?: return false
        if (advanced) ChangeBus.emit(null, "chat", chatId)
        return true
    }

    /** Persiste un mensaje (texto o con media) y actualiza el preview del chat. Null si no se puede escribir. */
    fun sendMessage(
        officerId: String,
        chatId: String,
        text: String,
        mediaType: com.alephri.elpuesto.model.MessageMediaType? = null,
    ): com.alephri.elpuesto.model.Message? {
        val msg = sendMessageTx(officerId, chatId, text, mediaType)
        if (msg != null) ChangeBus.emit(null, "chat", chatId)
        return msg
    }

    /** ¿Puede escribir en este chat? (mismo criterio que [sendMessage], sin escribir nada). */
    fun canWriteChat(officerId: String, chatId: String): Boolean = transaction {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction false
        when {
            chat[ChatsT.archived] -> false
            isGroupChat(chat[ChatsT.type]) -> isMemberTx(officerId, chatId)
            else -> canSeeChat(officerId, chatId)
        }
    }

    private fun sendMessageTx(
        officerId: String,
        chatId: String,
        text: String,
        mediaType: com.alephri.elpuesto.model.MessageMediaType?,
    ): com.alephri.elpuesto.model.Message? = transaction {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction null
        if (chat[ChatsT.archived]) return@transaction null
        if (isGroupChat(chat[ChatsT.type])) {
            val member = ChatMembersT.selectAll()
                .where { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }.any()
            if (!member) return@transaction null
        } else if (!canSeeChat(officerId, chatId)) {
            return@transaction null
        }
        val sender = Officers.selectAll().where { Officers.id eq officerId }.firstOrNull() ?: return@transaction null
        val id = uuidv7()
        // max(ord)+1: los ord de la semilla son globales, count()+1 ordenaría mal.
        val ord = nextOrdTx(chatId)
        val now = kotlinx.datetime.Clock.System.now()
        MessagesT.insert {
            it[MessagesT.id] = id; it[MessagesT.chatId] = chatId; it[MessagesT.ord] = ord
            it[senderId] = officerId; it[senderName] = sender[Officers.displayName]
            it[MessagesT.text] = text; it[at] = now.toString(); it[system] = false
            it[MessagesT.mediaType] = mediaType?.name
        }
        ChatsT.update({ ChatsT.id eq chatId }) {
            it[lastPreview] = if (mediaType != null) "📷 Foto" else text.take(120)
        }
        markReadTx(chatId, officerId, ord) // lo propio nunca cuenta como no leído
        com.alephri.elpuesto.model.Message(
            id = id, chatId = chatId, senderId = officerId, senderName = sender[Officers.displayName],
            text = text, at = now, system = false, mediaType = mediaType,
        )
    }

    /**
     * ¿El oficial puede VER este chat? (guard del stream de tiempo real: no se notifica
     * lo que no podría leer). Público/privado = miembro; evento/puesto = asignado al evento.
     */
    fun canSeeChat(officerId: String, chatId: String): Boolean = transaction {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction false
        if (isGroupChat(chat[ChatsT.type])) {
            ChatMembersT.selectAll()
                .where { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }.any()
        } else {
            val activeId = Events.selectAll().where { Events.active eq true }.firstOrNull()?.get(Events.id)
            val chatEvent = chat[ChatsT.eventId] ?: activeId ?: return@transaction false
            // Vivos: solo con su evento ACTIVO (como el listado). Archivados: se leen (historial;
            // escribir en ellos ya lo impide sendMessage).
            if (!chat[ChatsT.archived] && chatEvent != activeId) return@transaction false
            val mine = Assignments.selectAll()
                .where { (Assignments.eventId eq chatEvent) and (Assignments.officerId eq officerId) }
            if (chat[ChatsT.type] == ChatType.PUESTO.name) {
                // Solo los asignados a ESA posición (un chat por puesto/activo).
                val pos = chat[ChatsT.puestoId] ?: return@transaction false
                mine.any { it[Assignments.puestoId] == pos }
            } else mine.any()
        }
    }

    /**
     * Unirse/salir (persistente). Públicos: libre. Privados: unirse = ACEPTAR una
     * invitación pendiente; salir = salir del grupo o, si solo estabas invitado,
     * RECHAZAR. Altas y bajas de un privado dejan mensaje de sistema (transparencia).
     * false si el chat no existe, no es de grupo o no hay invitación que aceptar.
     */
    fun setChatJoined(officerId: String, chatId: String, join: Boolean): Boolean {
        var notify = false
        val ok = transaction {
            val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return@transaction false
            val type = chat[ChatsT.type]
            if (!isGroupChat(type)) return@transaction false
            val already = ChatMembersT.selectAll()
                .where { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }.any()
            val invite = ChatInvitesT.selectAll()
                .where { (ChatInvitesT.chatId eq chatId) and (ChatInvitesT.officerId eq officerId) }.firstOrNull()
            val myName = { Officers.selectAll().where { Officers.id eq officerId }.firstOrNull()?.get(Officers.displayName) ?: "Alguien" }
            if (join && !already) {
                if (chat[ChatsT.archived]) return@transaction false
                // Privado: solo con invitación. Público: libre; si había invitación, se acepta.
                if (type == ChatType.PRIVATE.name && invite == null) return@transaction false
                if (invite != null) {
                    ChatInvitesT.deleteWhere { (ChatInvitesT.chatId eq chatId) and (ChatInvitesT.officerId eq officerId) }
                    val inviterName = Officers.selectAll().where { Officers.id eq invite[ChatInvitesT.inviterId] }
                        .firstOrNull()?.get(Officers.displayName)
                    systemMessageTx(chatId, "${myName()} se unió al chat" + (inviterName?.let { " (invitación de $it)" } ?: ""))
                    notify = true
                }
                ChatMembersT.insert { it[ChatMembersT.chatId] = chatId; it[ChatMembersT.officerId] = officerId }
                ChatsT.update({ ChatsT.id eq chatId }) { it[membersCount] = chat[membersCount] + 1 }
            } else if (!join && already) {
                ChatMembersT.deleteWhere { (ChatMembersT.chatId eq chatId) and (ChatMembersT.officerId eq officerId) }
                ChatsT.update({ ChatsT.id eq chatId }) { it[membersCount] = maxOf(0, chat[membersCount] - 1) }
                // Quien sale no vuelve a recibir invitaciones a este chat.
                recordDeclineTx(chatId, officerId)
                if (type == ChatType.PRIVATE.name) { systemMessageTx(chatId, "${myName()} salió del chat"); notify = true }
            } else if (!join && invite != null) {
                // Rechazar la invitación: el grupo lo ve en sus detalles (ya no queda pendiente)
                // y nadie puede volver a invitarlo a este chat.
                ChatInvitesT.deleteWhere { (ChatInvitesT.chatId eq chatId) and (ChatInvitesT.officerId eq officerId) }
                recordDeclineTx(chatId, officerId)
                notify = true
            }
            true
        }
        if (notify) ChangeBus.emit(null, "chat", chatId)
        return ok
    }

    /**
     * Invita a [inviteeId] a un chat público o privado: le llega una invitación que ACEPTA
     * o rechaza (nadie entra a un grupo sin su consentimiento). Solo invita un MIEMBRO. Si
     * el invitado rechazó (o dejó) este chat, o bloqueó a quien invita, la respuesta es la
     * misma genérica: no se revela cuál de los dos. Null = ok; texto = motivo del rechazo.
     */
    fun addChatMember(inviterId: String, chatId: String, inviteeId: String): String? {
        val problem = transaction {
            val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull()
                ?: return@transaction "chat no encontrado"
            if (!isGroupChat(chat[ChatsT.type])) return@transaction "solo se puede invitar a chats públicos o privados"
            if (chat[ChatsT.archived]) return@transaction "el chat está archivado"
            if (!isMemberTx(inviterId, chatId)) return@transaction "solo los miembros pueden invitar"
            if (Officers.selectAll().where { Officers.id eq inviteeId }.none()) return@transaction "oficial no encontrado"
            if (isMemberTx(inviteeId, chatId)) return@transaction "ya es miembro del chat"
            if (declinedTx(chatId, inviteeId) || isBlockedTx(inviteeId, inviterId)) {
                return@transaction "no se puede invitar a este oficial a este chat"
            }
            val invited = ChatInvitesT.selectAll()
                .where { (ChatInvitesT.chatId eq chatId) and (ChatInvitesT.officerId eq inviteeId) }.any()
            if (invited) return@transaction "ya tiene una invitación pendiente"
            ChatInvitesT.insert {
                it[ChatInvitesT.chatId] = chatId; it[officerId] = inviteeId; it[ChatInvitesT.inviterId] = inviterId
                it[createdAt] = kotlinx.datetime.Clock.System.now().toString()
            }
            null
        }
        if (problem == null) {
            emitInvites(chatId, inviterId, listOf(inviteeId))
            ChangeBus.emit(null, "chat", chatId)
        }
        return problem
    }

    /**
     * ¿El oficial puede ver esta imagen? Las públicas (avatares, logos, eventos, mapas) sí;
     * la foto de una bitácora solo su dueño (la bitácora es privada) y las de un chat
     * (fotos de mensajes e imagen del chat) solo quien puede leer ese chat.
     */
    fun canViewImage(viewerId: String, kind: String, ownerId: String): Boolean = when (kind) {
        "trip" -> transaction {
            TripItemsT.selectAll().where { TripItemsT.id eq ownerId }.firstOrNull()
                ?.get(TripItemsT.officerId)?.let { it == viewerId } ?: true
        }
        "chatmedia" -> transaction {
            MessagesT.selectAll().where { MessagesT.id eq ownerId }.firstOrNull()?.get(MessagesT.chatId)
        }?.let { canReadChat(viewerId, it) } ?: true
        "chatimg" -> canReadChat(viewerId, ownerId)
        else -> true
    }

    /**
     * ¿El oficial puede LEER este chat? Los públicos son de libre lectura (unirse es
     * libre; leer es cómo decides unirte); privados (solo miembros), evento y puesto
     * siguen la regla de [canSeeChat].
     */
    fun canReadChat(officerId: String, chatId: String): Boolean {
        val type = transaction {
            ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull()?.get(ChatsT.type)
        } ?: return false
        return type == ChatType.PUBLIC.name || canSeeChat(officerId, chatId)
    }

    /**
     * Reporta un mensaje (moderación básica): queda registrado para que el admin lo
     * revise. Null = ok; texto = motivo del rechazo. La visibilidad del chat la valida
     * la ruta ([canReadChat]); aquí se valida el mensaje en sí.
     */
    fun reportMessage(reporterId: String, chatId: String, messageId: String, reason: String?): String? = transaction {
        val msg = MessagesT.selectAll().where { MessagesT.id eq messageId }.firstOrNull()
            ?: return@transaction "mensaje no encontrado"
        if (msg[MessagesT.chatId] != chatId) return@transaction "el mensaje no es de este chat"
        if (msg[MessagesT.system]) return@transaction "los mensajes de sistema no se reportan"
        if (msg[MessagesT.senderId] == reporterId) return@transaction "no puedes reportar tu propio mensaje"
        val already = MessageReportsT.selectAll()
            .where { (MessageReportsT.messageId eq messageId) and (MessageReportsT.reporterId eq reporterId) }.any()
        if (already) return@transaction "ya reportaste este mensaje"
        MessageReportsT.insert {
            it[id] = uuidv7(); it[MessageReportsT.chatId] = chatId
            it[MessageReportsT.messageId] = messageId; it[MessageReportsT.reporterId] = reporterId
            it[MessageReportsT.reason] = reason
            it[createdAt] = kotlinx.datetime.Clock.System.now().toString()
        }
        null
    }

    /** Reportes de mensajes con su contexto (mensaje, autor, reportero), recientes primero. */
    fun listMessageReports(): List<AdminReportInfo> = transaction {
        val officerNames = Officers.selectAll().associate { it[Officers.id] to it[Officers.displayName] }
        val chatNames = ChatsT.selectAll().associate { it[ChatsT.id] to it[ChatsT.name] }
        MessageReportsT.selectAll().map { r ->
            val msg = MessagesT.selectAll().where { MessagesT.id eq r[MessageReportsT.messageId] }.firstOrNull()
            AdminReportInfo(
                id = r[MessageReportsT.id],
                chatId = r[MessageReportsT.chatId],
                chatName = chatNames[r[MessageReportsT.chatId]] ?: "(chat eliminado)",
                messageId = r[MessageReportsT.messageId],
                messageText = msg?.get(MessagesT.text) ?: "(mensaje eliminado)",
                messageSender = msg?.get(MessagesT.senderName) ?: "",
                messageSenderId = msg?.get(MessagesT.senderId),
                messageAt = msg?.get(MessagesT.at),
                mediaType = msg?.get(MessagesT.mediaType),
                reporterId = r[MessageReportsT.reporterId],
                reporterName = officerNames[r[MessageReportsT.reporterId]] ?: r[MessageReportsT.reporterId],
                reason = r[MessageReportsT.reason],
                createdAt = r[MessageReportsT.createdAt],
            )
        }.sortedByDescending { it.createdAt }
    }

    /** Descarta (elimina) un reporte revisado (de mensaje, chat o perfil). */
    fun deleteMessageReport(id: String): Boolean = transaction {
        (MessageReportsT.deleteWhere { MessageReportsT.id eq id } + ContentReportsT.deleteWhere { ContentReportsT.id eq id }) > 0
    }

    /**
     * Reporta un CHAT (nombre, imagen o descripción) o el PERFIL de un oficial (nombre o
     * foto). Uno por reportero y objetivo; el admin lo revisa. Null = ok; texto = motivo.
     */
    fun reportContent(reporterId: String, kind: String, targetId: String, reason: String?): String? = transaction {
        when (kind) {
            "chat" -> if (ChatsT.selectAll().where { ChatsT.id eq targetId }.none()) return@transaction "chat no encontrado"
            "officer" -> {
                if (targetId == reporterId) return@transaction "no puedes reportar tu propio perfil"
                if (Officers.selectAll().where { Officers.id eq targetId }.none()) return@transaction "oficial no encontrado"
            }
            else -> return@transaction "tipo de reporte inválido"
        }
        val already = ContentReportsT.selectAll().where {
            (ContentReportsT.kind eq kind) and (ContentReportsT.targetId eq targetId) and (ContentReportsT.reporterId eq reporterId)
        }.any()
        if (already) return@transaction "ya lo reportaste"
        ContentReportsT.insert {
            it[id] = uuidv7(); it[ContentReportsT.kind] = kind; it[ContentReportsT.targetId] = targetId
            it[ContentReportsT.reporterId] = reporterId; it[ContentReportsT.reason] = reason
            it[createdAt] = kotlinx.datetime.Clock.System.now().toString()
        }
        null
    }

    /** Reportes de chats y perfiles con su contexto, para la cola de moderación. */
    fun listContentReports(): List<AdminReportInfo> = transaction {
        val officerNames = Officers.selectAll().associate { it[Officers.id] to it[Officers.displayName] }
        ContentReportsT.selectAll().map { r ->
            val kind = r[ContentReportsT.kind]
            val target = r[ContentReportsT.targetId]
            val chat = if (kind == "chat") ChatsT.selectAll().where { ChatsT.id eq target }.firstOrNull() else null
            AdminReportInfo(
                id = r[ContentReportsT.id],
                chatId = if (kind == "chat") target else "",
                chatName = chat?.get(ChatsT.name) ?: if (kind == "chat") "(chat eliminado)" else "",
                messageId = "",
                messageText = chat?.get(ChatsT.description) ?: "",
                messageSender = if (kind == "officer") officerNames[target] ?: "(oficial eliminado)" else "",
                messageSenderId = if (kind == "officer") target else chat?.get(ChatsT.creatorId),
                reporterId = r[ContentReportsT.reporterId],
                reporterName = officerNames[r[ContentReportsT.reporterId]] ?: r[ContentReportsT.reporterId],
                reason = r[ContentReportsT.reason],
                createdAt = r[ContentReportsT.createdAt],
                kind = kind,
                targetId = target,
                targetName = if (kind == "officer") officerNames[target] else chat?.get(ChatsT.name),
            )
        }
    }

    // —— Bloqueos (solo los ve quien bloquea) ——

    /** Oficiales que [blockerId] bloqueó. */
    fun blockedOfficers(blockerId: String): List<Officer> = transaction {
        val ids = OfficerBlocksT.selectAll().where { OfficerBlocksT.blockerId eq blockerId }.map { it[OfficerBlocksT.blockedId] }
        if (ids.isEmpty()) emptyList()
        else Officers.selectAll().where { Officers.id inList ids }.map { it.toOfficer() }.sortedBy { it.displayName }
    }

    /**
     * Bloquea a [blockedId]: ya no puede invitarte a chats ni compartirte su ubicación (ni
     * avisarte). Se retiran sus invitaciones pendientes para ti. Null = ok; texto = motivo.
     */
    fun block(blockerId: String, blockedId: String): String? {
        if (blockerId == blockedId) return "no puedes bloquearte a ti mismo"
        val problem = transaction {
            if (Officers.selectAll().where { Officers.id eq blockedId }.none()) return@transaction "oficial no encontrado"
            if (!isBlockedTx(blockerId, blockedId)) {
                OfficerBlocksT.insert {
                    it[OfficerBlocksT.blockerId] = blockerId; it[OfficerBlocksT.blockedId] = blockedId
                    it[createdAt] = kotlinx.datetime.Clock.System.now().toString()
                }
            }
            ChatInvitesT.deleteWhere { (ChatInvitesT.officerId eq blockerId) and (ChatInvitesT.inviterId eq blockedId) }
            null
        }
        // Quién ve a quién cambió (su ubicación deja de llegarte) y tu lista de chats también.
        if (problem == null) ChangeBus.emit(null, "location-sharing", id = blockerId)
        return problem
    }

    fun unblock(blockerId: String, blockedId: String) {
        transaction {
            OfficerBlocksT.deleteWhere { (OfficerBlocksT.blockerId eq blockerId) and (OfficerBlocksT.blockedId eq blockedId) }
        }
        ChangeBus.emit(null, "location-sharing", id = blockerId)
    }

    // —— Fase 2: perfil, emergencia (auditada), viajes, mensajes ——

    // —— Historial DERIVADO de las asignaciones (2026-09-24) y del registro por honor ——
    // Cuenta un evento TERMINADO por fecha y ya no marcado "en curso" (el flag active manda
    // para la operación: un evento activo aún no es historia aunque sus fechas pasaron).

    internal data class PastAssignment(
        val eventId: String, val eventName: String, val startsOn: String, val endsOn: String,
        val circuitId: String, val circuitName: String, val role: String,
        /** Posición (puesto o activo); vacía = participación declarada sin puesto. */
        val positionId: String, val positionLabel: String,
        /** true = la declaró el oficial (sistema de honor), no viene del roster. */
        val declared: Boolean = false,
        /** Días declarados que trabajó; null = todos los del evento (roster: menos ausencias). */
        val dayCount: Int? = null,
        /** [positionLabel] es un puesto PROPUESTO en revisión: solo lo ve el titular. */
        val positionPending: Boolean = false,
    ) {
        fun toEntry() = com.alephri.elpuesto.model.OfficerHistoryEntry(
            id = eventId, date = LocalDate.parse(startsOn), eventName = eventName, roleLabel = role,
            location = circuitName, endsOn = LocalDate.parse(endsOn), circuitId = circuitId,
            positionLabel = positionLabel.ifEmpty { null }, declared = declared, positionPending = positionPending,
        )
    }

    /**
     * Participaciones de [officerId] en eventos terminados, más recientes primero (también
     * la usan los logros, los eventos en común y "asignado antes"): las del ROSTER y las
     * DECLARADAS por honor en eventos donde el roster no lo incluye (el roster manda).
     */
    internal fun pastAssignmentsTx(officerId: String): List<PastAssignment> {
        fun finished(r: ResultRow) = !r[Events.active] && eventStatus(r[Events.startsOn], r[Events.endsOn]) == EventStatus.FINISHED
        val rows = Assignments.join(Events, org.jetbrains.exposed.sql.JoinType.INNER, onColumn = Assignments.eventId, otherColumn = Events.id)
            .selectAll().where { Assignments.officerId eq officerId }
            .toList()
        val rostered = rows.map { it[Events.id] }.toSet()
        val roster = rows.filter(::finished).distinctBy { it[Events.id] }
        val declared = ParticipationRepository.declaredWithEventTx(officerId)
            .filter { finished(it) && it[Events.id] !in rostered }
        if (roster.isEmpty() && declared.isEmpty()) return emptyList()
        val positions = puestoDisplayNames(roster.map { it[Assignments.puestoId] } + declared.mapNotNull { it[ParticipationsT.positionId] })
        val proposed = ProposalRepository.pendingLabelsTx(declared.mapNotNull { it[ParticipationsT.proposalId] })
        // Incluye circuitos archivados: el historial conserva su referencia.
        val circuits = CircuitsT.selectAll().where { CircuitsT.id inList (roster + declared).map { it[Events.circuitId] }.toSet() }
            .associate { it[CircuitsT.id] to it[CircuitsT.name] }
        fun base(r: ResultRow, role: String, positionId: String, positionLabel: String) = PastAssignment(
            eventId = r[Events.id], eventName = r[Events.name],
            startsOn = r[Events.startsOn], endsOn = r[Events.endsOn],
            circuitId = r[Events.circuitId], circuitName = circuits[r[Events.circuitId]] ?: "—",
            role = role, positionId = positionId, positionLabel = positionLabel,
        )
        return (
            roster.map {
                base(it, it[Assignments.role], it[Assignments.puestoId], positions[it[Assignments.puestoId]] ?: "P ${it[Assignments.puestoNumber]}")
            } + declared.map {
                val pos = it[ParticipationsT.positionId]
                // Si el admin movió las fechas y ningún día declarado quedó dentro: todos.
                val days = it[ParticipationsT.days]
                    ?.let { raw -> ParticipationRepository.daysOf(raw, it[Events.startsOn], it[Events.endsOn]).size }
                    ?.takeIf { n -> n > 0 }
                // Puesto propuesto (en revisión): su etiqueta, marcada; no es una posición real.
                val pending = if (pos == null) it[ParticipationsT.proposalId]?.let(proposed::get) else null
                base(it, it[ParticipationsT.role], pos.orEmpty(), pos?.let(positions::get) ?: pending.orEmpty())
                    .copy(declared = true, dayCount = days, positionPending = pending != null)
            }
        ).sortedByDescending { it.startsOn }
    }

    /** Historial PROPIO (la ruta solo lo sirve al titular). */
    fun officerHistory(officerId: String): List<com.alephri.elpuesto.model.OfficerHistoryEntry> = transaction {
        pastAssignmentsTx(officerId).map { it.toEntry() }
    }

    /**
     * Eventos terminados donde [viewerId] y [otherId] tuvieron asignación — NUNCA el
     * historial completo del otro (privacy-first). Desde la perspectiva del visor: rol y
     * posición propios + los del otro, y si coincidieron en la misma posición.
     */
    fun commonEvents(viewerId: String, otherId: String): List<com.alephri.elpuesto.model.OfficerHistoryEntry> = transaction {
        if (viewerId == otherId) return@transaction emptyList()
        val theirs = pastAssignmentsTx(otherId).associateBy { it.eventId }
        pastAssignmentsTx(viewerId).mapNotNull { mine ->
            theirs[mine.eventId]?.let { o ->
                // Si MI participación es un registro por honor, no destapa el rol ni el puesto
                // del otro: registrarse en muchos eventos no debe servir para leer lo que
                // hizo cada quien (solo se ve que ambos estuvieron).
                if (mine.declared) return@let mine.toEntry()
                mine.toEntry().copy(
                    // El puesto que el otro PROPUSO sigue en revisión: solo lo ve él.
                    otherRole = o.role, otherPosition = o.positionLabel.takeUnless { o.positionPending }?.ifEmpty { null },
                    // Sin puesto (declaración) no hay "mismo puesto" que afirmar.
                    samePosition = mine.positionId.isNotEmpty() && mine.positionId == o.positionId,
                )
            }
        }
    }

    /**
     * Estadísticas DERIVADAS: eventos terminados y de esta temporada (año calendario); con
     * [viewerId] ajeno, además los eventos juntos. "Activo desde" sigue capturado (antigüedad
     * real, anterior a la app).
     */
    private fun derivedStatsTx(officerId: String, activeSince: Int?, viewerId: String?): OfficerStats {
        val past = pastAssignmentsTx(officerId)
        val year = java.time.LocalDate.now().year.toString()
        val together = if (viewerId != null && viewerId != officerId) {
            val mine = pastAssignmentsTx(viewerId).map { it.eventId }.toSet()
            past.count { it.eventId in mine }
        } else null
        return OfficerStats(
            events = past.size, thisSeason = past.count { it.startsOn.startsWith(year) },
            activeSince = activeSince, together = together,
        )
    }

    fun emergencyInfo(officerId: String): com.alephri.elpuesto.model.EmergencyInfo = transaction {
        EmergencyInfoT.selectAll().where { EmergencyInfoT.officerId eq officerId }.firstOrNull()?.let {
            com.alephri.elpuesto.model.EmergencyInfo(
                contactName = it[EmergencyInfoT.contactName], contactPhone = it[EmergencyInfoT.contactPhone],
                bloodType = it[EmergencyInfoT.bloodType], allergies = it[EmergencyInfoT.allergies],
            )
        } ?: com.alephri.elpuesto.model.EmergencyInfo()
    }

    fun upsertEmergency(officerId: String, info: com.alephri.elpuesto.model.EmergencyInfo): Unit = transaction {
        val exists = EmergencyInfoT.selectAll().where { EmergencyInfoT.officerId eq officerId }.any()
        if (exists) {
            EmergencyInfoT.update({ EmergencyInfoT.officerId eq officerId }) {
                it[contactName] = info.contactName; it[contactPhone] = info.contactPhone
                it[bloodType] = info.bloodType; it[allergies] = info.allergies
            }
        } else {
            EmergencyInfoT.insert {
                it[EmergencyInfoT.officerId] = officerId
                it[contactName] = info.contactName; it[contactPhone] = info.contactPhone
                it[bloodType] = info.bloodType; it[allergies] = info.allergies
            }
        }
    }

    /**
     * Evento activo en el que [viewerId] es jefe (rol de [CHIEF_ROLES]) de la MISMA
     * posición (puesto o activo) donde está asignado [targetId] (o null). Derivado de
     * las asignaciones.
     */
    fun chiefViewContext(viewerId: String, targetId: String): String? = transaction {
        Events.selectAll().where { Events.active eq true }.map { it[Events.id] }.firstOrNull { ev ->
            val rows = Assignments.selectAll().where { Assignments.eventId eq ev }.toList()
            val myPuesto = rows.firstOrNull {
                it[Assignments.officerId] == viewerId && it[Assignments.role] in CHIEF_ROLES
            }?.get(Assignments.puestoId)
            myPuesto != null && rows.any { it[Assignments.officerId] == targetId && it[Assignments.puestoId] == myPuesto }
        }
    }

    /** Registra el acceso a la emergencia de [ownerId] — cada consulta queda auditada. */
    fun recordEmergencyAccess(ownerId: String, viewerId: String, eventId: String?): Unit = transaction {
        EmergencyAccessesT.insert {
            it[id] = uuidv7()
            it[EmergencyAccessesT.ownerId] = ownerId; it[EmergencyAccessesT.viewerId] = viewerId
            it[at] = java.time.Instant.now().toString(); it[EmergencyAccessesT.eventId] = eventId
        }
    }

    fun emergencyAccesses(ownerId: String): List<com.alephri.elpuesto.model.EmergencyAccess> = transaction {
        // Las descargas de "mis datos" también son un acceso a TUS datos: se listan aquí.
        val exports = DataExport.exportsOf(ownerId).map { at ->
            com.alephri.elpuesto.model.EmergencyAccess(
                id = "export-$at", viewerId = ownerId, viewerName = "Tú",
                at = Instant.parse(at.toString()), kind = "export",
            )
        }
        val consultas = EmergencyAccessesT.join(Officers, org.jetbrains.exposed.sql.JoinType.LEFT, onColumn = EmergencyAccessesT.viewerId, otherColumn = Officers.id)
            .selectAll().where { EmergencyAccessesT.ownerId eq ownerId }
            .orderBy(EmergencyAccessesT.at to SortOrder.DESC)
            .map {
                com.alephri.elpuesto.model.EmergencyAccess(
                    id = it[EmergencyAccessesT.id], viewerId = it[EmergencyAccessesT.viewerId],
                    viewerName = it.getOrNull(Officers.displayName) ?: it[EmergencyAccessesT.viewerId],
                    at = Instant.parse(it[EmergencyAccessesT.at]), eventId = it[EmergencyAccessesT.eventId],
                )
            }
        (consultas + exports).sortedByDescending { it.at }
    }

    /** Fija la URL del avatar tras procesar la imagen. Devuelve el oficial actualizado. */
    fun setOfficerAvatar(id: String, url: String): Officer? = transaction {
        Officers.update({ Officers.id eq id }) { it[avatarUrl] = url }
        Officers.selectAll().where { Officers.id eq id }.firstOrNull()?.toOfficer()
    }

    /** Actualiza el perfil propio (sin aprobación). Devuelve el oficial actualizado. */
    fun updateOfficer(id: String, displayName: String, area: Area?): Officer? = transaction {
        Officers.update({ Officers.id eq id }) {
            it[Officers.displayName] = displayName
            it[Officers.area] = area?.name
        }
        Officers.selectAll().where { Officers.id eq id }.firstOrNull()?.toOfficer()
    }

    private fun ResultRow.toTripItem(viewerId: String) = com.alephri.elpuesto.model.TripItem(
        id = this[TripItemsT.id], eventId = this[TripItemsT.eventId],
        kind = com.alephri.elpuesto.model.TripItemKind.valueOf(this[TripItemsT.kind]),
        title = this[TripItemsT.title], detail = this[TripItemsT.detail],
        at = this[TripItemsT.at]?.let(Instant::parse),
        personal = this[TripItemsT.officerId] == viewerId,
        convocatoriaId = this[TripItemsT.convocatoriaId],
        roundId = this[TripItemsT.roundId],
        endsAt = this[TripItemsT.endsAt]?.let(Instant::parse),
    )

    /**
     * Bitácora del evento: lo ligado al evento + lo ligado a las carreras del calendario
     * que se funden con él (p. ej. planeación hecha para el GP antes de tener asignación).
     */
    fun tripItems(eventId: String, officerId: String): List<com.alephri.elpuesto.model.TripItem> = transaction {
        val rounds = eventRoundIdsTx(eventId)
        TripItemsT.selectAll()
            .where {
                val linked = if (rounds.isEmpty()) TripItemsT.eventId eq eventId
                else (TripItemsT.eventId eq eventId) or (TripItemsT.roundId inList rounds)
                linked and (TripItemsT.officerId.isNull() or (TripItemsT.officerId eq officerId))
            }
            .orderBy(TripItemsT.ord to SortOrder.ASC)
            .map { it.toTripItem(officerId) }
    }

    /** Todos los ítems personales del oficial (con o sin evento). */
    fun myTripItems(officerId: String): List<com.alephri.elpuesto.model.TripItem> = transaction {
        TripItemsT.selectAll().where { TripItemsT.officerId eq officerId }
            .orderBy(TripItemsT.ord to SortOrder.ASC)
            .map { it.toTripItem(officerId) }
    }

    fun createTripItem(officerId: String, item: com.alephri.elpuesto.model.TripItem): com.alephri.elpuesto.model.TripItem = transaction {
        val id = uuidv7()
        val ord = TripItemsT.selectAll().count().toInt()
        TripItemsT.insert {
            it[TripItemsT.id] = id; it[eventId] = item.eventId; it[TripItemsT.officerId] = officerId
            it[TripItemsT.ord] = ord; it[kind] = item.kind.name; it[title] = item.title
            it[detail] = item.detail; it[at] = item.at?.toString()
            it[convocatoriaId] = item.convocatoriaId
            it[roundId] = item.roundId; it[endsAt] = item.endsAt?.toString()
        }
        item.copy(id = id, personal = true)
    }

    /** Actualiza un ítem personal PROPIO; null si no existe o no es del oficial. */
    fun updateTripItem(officerId: String, id: String, item: com.alephri.elpuesto.model.TripItem): com.alephri.elpuesto.model.TripItem? = transaction {
        val n = TripItemsT.update({ (TripItemsT.id eq id) and (TripItemsT.officerId eq officerId) }) {
            it[eventId] = item.eventId; it[kind] = item.kind.name; it[title] = item.title
            it[detail] = item.detail; it[at] = item.at?.toString()
            it[convocatoriaId] = item.convocatoriaId
            it[roundId] = item.roundId; it[endsAt] = item.endsAt?.toString()
        }
        if (n == 0) null else item.copy(id = id, personal = true)
    }

    /** Elimina un ítem personal PROPIO (y su foto de bitácora, si tiene). */
    fun deleteTripItem(officerId: String, id: String): Boolean = transaction {
        val gone = TripItemsT.deleteWhere { (TripItemsT.id eq id) and (TripItemsT.officerId eq officerId) } > 0
        if (gone) ImagesT.deleteWhere { (ImagesT.kind eq "trip") and (ImagesT.ownerId eq id) }
        gone
    }

    /** ¿El ítem existe y es del oficial? (guard para la subida de fotos de bitácora). */
    fun ownsTripItem(officerId: String, id: String): Boolean = transaction {
        TripItemsT.selectAll().where { (TripItemsT.id eq id) and (TripItemsT.officerId eq officerId) }.count() > 0
    }

    /**
     * Mensajes de un chat en orden ascendente, por páginas (nunca todo el historial de una
     * vez): sin cursor, los últimos [limit]; [beforeId] = los [limit] anteriores a ese
     * mensaje; [afterId] = los posteriores (hasta [limit]). Un cursor que no es de este chat
     * se ignora.
     */
    fun messages(
        chatId: String,
        limit: Int = MESSAGES_PAGE_MAX,
        beforeId: String? = null,
        afterId: String? = null,
    ): List<com.alephri.elpuesto.model.Message> = transaction {
        val n = limit.coerceIn(1, MESSAGES_PAGE_MAX)
        fun ordOf(id: String?): Int? = id?.let {
            MessagesT.selectAll().where { (MessagesT.id eq it) and (MessagesT.chatId eq chatId) }.firstOrNull()?.get(MessagesT.ord)
        }
        val before = ordOf(beforeId)
        val after = ordOf(afterId)
        // Contexto operativo del remitente (puesto/rol) desde las asignaciones del evento
        // del chat; públicos y privados solo si están ligados a un evento.
        val ctx = senderContext(chatId)
        val rows = when {
            after != null -> MessagesT.selectAll().where { (MessagesT.chatId eq chatId) and (MessagesT.ord greater after) }
                .orderBy(MessagesT.ord to SortOrder.ASC).limit(n).toList()
            else -> MessagesT.selectAll().where {
                if (before != null) (MessagesT.chatId eq chatId) and (MessagesT.ord less before) else MessagesT.chatId eq chatId
            }.orderBy(MessagesT.ord to SortOrder.DESC).limit(n).toList().asReversed()
        }
        rows
            .map {
                val c = it[MessagesT.senderId]?.let(ctx::get)
                com.alephri.elpuesto.model.Message(
                    id = it[MessagesT.id], chatId = it[MessagesT.chatId], senderId = it[MessagesT.senderId],
                    senderName = it[MessagesT.senderName], text = it[MessagesT.text],
                    at = Instant.parse(it[MessagesT.at]), system = it[MessagesT.system],
                    mediaType = it[MessagesT.mediaType]?.let(com.alephri.elpuesto.model.MessageMediaType::valueOf),
                    senderPuesto = c?.first, senderRole = c?.second,
                )
            }
    }

    /** Tope de mensajes por página ([messages]). */
    const val MESSAGES_PAGE_MAX = 200

    /**
     * officerId → (nombre del puesto "MP 1", rol) según las asignaciones del evento del chat.
     * La asignación solo tiene sentido dentro de un evento: los chats de evento/puesto sin
     * `eventId` son del ACTIVO (convención de [canSeeChat]); un público o privado solo lleva
     * contexto si está LIGADO a un evento (sin vínculo, ningún participante trae puesto/rol).
     */
    private fun senderContext(chatId: String): Map<String, Pair<String, String>> {
        val chat = ChatsT.selectAll().where { ChatsT.id eq chatId }.firstOrNull() ?: return emptyMap()
        val evId = chat[ChatsT.eventId]
            ?: (if (isGroupChat(chat[ChatsT.type])) null
                else Events.selectAll().where { Events.active eq true }.firstOrNull()?.get(Events.id))
            ?: return emptyMap()
        val rows = Assignments.selectAll().where { Assignments.eventId eq evId }.toList()
        val names = puestoDisplayNames(rows.map { it[Assignments.puestoId] })
        return rows.associateBy(
            { it[Assignments.officerId] },
            { (names[it[Assignments.puestoId]] ?: "P ${it[Assignments.puestoNumber]}") to it[Assignments.role] },
        )
    }

    // —— Mapeos ——

    internal fun ResultRow.toOfficer() = Officer(
        id = this[Officers.id], omdaiId = this[Officers.omdaiId], displayName = this[Officers.displayName],
        avatarUrl = this[Officers.avatarUrl], assignedArea = this[Officers.area]?.let(Area::valueOf),
        systemRole = SystemRole.valueOf(this[Officers.systemRole]), status = AccountStatus.valueOf(this[Officers.status]),
        stats = OfficerStats(this[Officers.statEvents], this[Officers.statSeason], this[Officers.activeSince]),
    )

    private fun diasEntre(a: String, b: String): Long =
        kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b)))

    /** El estatus de un evento no se captura: se deriva de sus fechas al leer. */
    internal fun eventStatus(startsOn: String, endsOn: String): EventStatus {
        val today = java.time.LocalDate.now().toString() // ISO: comparable como string
        return when {
            today < startsOn -> EventStatus.UPCOMING
            today <= endsOn -> EventStatus.LIVE
            else -> EventStatus.FINISHED
        }
    }

    private fun ResultRow.toEvent(): Event {
        val id = this[Events.id]
        val trazados = EventTrazadosT.selectAll().where { EventTrazadosT.eventId eq id }
            .orderBy(EventTrazadosT.ord).map { it[EventTrazadosT.trazadoId] }
        val championships = EventChampionshipsT.selectAll().where { EventChampionshipsT.eventId eq id }
            .orderBy(EventChampionshipsT.ord).map { it[EventChampionshipsT.championshipId] }
        return Event(
            id = id, name = this[Events.name],
            startsOn = LocalDate.parse(this[Events.startsOn]), endsOn = LocalDate.parse(this[Events.endsOn]),
            circuitId = this[Events.circuitId], trazadoId = this[Events.trazadoId],
            status = eventStatus(this[Events.startsOn], this[Events.endsOn]),
            trazadoIds = trazados.ifEmpty { listOf(this[Events.trazadoId]) },
            championshipIds = championships,
            selfRegistration = this[Events.selfRegistration],
        )
    }
}
