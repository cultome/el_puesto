package com.alephri.elpuesto.backend

import org.jetbrains.exposed.sql.Table

/**
 * Esquema NORMALIZADO del dominio (una tabla por entidad, columnas reales) para que la
 * admin web y la API de ingesta puedan consultar/editar con SQL normal.
 * Fechas/horas kotlinx (LocalDate/LocalTime/Instant) se guardan como texto ISO-8601.
 */

object Officers : Table("officers") {
    val id = varchar("id", 64)
    val omdaiId = integer("omdai_id")
    val displayName = varchar("display_name", 200)
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val area = varchar("area", 32).nullable()
    val systemRole = varchar("system_role", 32)
    val status = varchar("status", 32)
    val statEvents = integer("stat_events")
    val statSeason = integer("stat_season")
    val activeSince = integer("active_since").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Events : Table("events") {
    val id = varchar("id", 64)
    val name = varchar("name", 300)
    val startsOn = varchar("starts_on", 16) // ISO LocalDate
    val endsOn = varchar("ends_on", 16)
    val circuitId = varchar("circuit_id", 64)
    val trazadoId = varchar("trazado_id", 64)
    val status = varchar("status", 32)
    val active = bool("active").default(false)
    // Autoregistro por honor ([ParticipationsT]); permitido por defecto (2026-09-25).
    val selfRegistration = bool("self_registration").default(true)
    override val primaryKey = PrimaryKey(id)
}

/** Trazados en uso por evento (N:M ordenado; el de ord 0 es el "principal"/compat). */
object EventTrazadosT : Table("event_trazados") {
    val eventId = varchar("event_id", 64)
    val ord = integer("ord")
    val trazadoId = varchar("trazado_id", 64)
    override val primaryKey = PrimaryKey(eventId, ord)
}

/** Campeonatos que corren en el evento (alimentan el combo de categoría del MbM). */
object EventChampionshipsT : Table("event_championships") {
    val eventId = varchar("event_id", 64)
    val ord = integer("ord")
    val championshipId = varchar("championship_id", 64)
    override val primaryKey = PrimaryKey(eventId, ord)
}

object Assignments : Table("assignments") {
    val id = varchar("id", 64)
    val eventId = varchar("event_id", 64)
    val officerId = varchar("officer_id", 64)
    val role = varchar("role", 32)
    val puestoId = varchar("puesto_id", 64)
    val puestoNumber = integer("puesto_number")
    val shift = varchar("shift", 100).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Participaciones DECLARADAS por el propio oficial (sistema de honor, 2026-09-25): "yo
 * trabajé en este evento". APARTE de [Assignments] a propósito: compañeros, chats,
 * emergencia, pase de lista y ubicación se derivan SOLO del roster, así que declarar
 * nunca da permisos. Alimenta la bitácora (historial, logros, eventos en común, agenda).
 * Una por oficial y evento; si el roster incluye al oficial, el roster manda (al leer).
 */
object ParticipationsT : Table("participations") {
    val id = varchar("id", 64)
    val eventId = varchar("event_id", 64)
    val officerId = varchar("officer_id", 64)
    val role = varchar("role", 64)
    // Puesto o activo de un trazado del evento; null = sin puesto.
    val positionId = varchar("position_id", 64).nullable()
    // Días trabajados (ISO separados por coma); null = todos los días del evento.
    val days = text("days").nullable()
    // Puesto PROPUESTO por el oficial (en revisión; excluyente con position_id). Al
    // aprobarse/fusionarse, la participación pasa a position_id; al rechazarse, sin puesto.
    val proposalId = varchar("proposal_id", 64).nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId, officerId)
    }
}

/**
 * Puestos PROPUESTOS por los oficiales al registrarse por honor (el trazado no traía su
 * puesto). Solo los ve quien los propuso hasta que el admin los revisa: APPROVED (se crea
 * el puesto), MERGED (era uno existente o lo propusieron otros) o REJECTED. Las filas
 * resueltas se conservan (trazabilidad: quién propuso qué y qué se decidió).
 */
object PuestoProposalsT : Table("puesto_proposals") {
    val id = varchar("id", 64)
    val trazadoId = varchar("trazado_id", 64)
    val officerId = varchar("officer_id", 64)
    val eventId = varchar("event_id", 64) // evento donde lo propuso (contexto)
    val label = varchar("label", 40)
    val lat = double("lat").nullable() // null = trazado sin mapa: solo el número
    val lon = double("lon").nullable()
    val status = varchar("status", 16) // ProposalStatus
    val resolvedPuestoId = varchar("resolved_puesto_id", 64).nullable()
    val reviewedBy = varchar("reviewed_by", 100).nullable() // actor admin
    val reviewedAt = text("reviewed_at").nullable()
    val reviewNote = varchar("review_note", 500).nullable()
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// Los "compañeros de puesto" se DERIVAN de las asignaciones (mismo puesto; jefe = rol
// "Chief Post Marshal"): la tabla puesto_mates se eliminó (migración 2026-08-01).

object SessionsT : Table("sessions") {
    val id = varchar("id", 64)
    val eventId = varchar("event_id", 64)
    val ord = integer("ord")
    val day = varchar("day", 16) // ISO LocalDate
    val time = varchar("time", 8) // ISO LocalTime
    val category = varchar("category", 100)
    val name = varchar("name", 300)
    val status = varchar("status", 32)
    val endsInMin = integer("ends_in_min").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** PLANTILLA del checklist del evento (el estado vive por puesto en [ChecklistStateT]). */
object ChecklistItems : Table("checklist_items") {
    val id = varchar("id", 64)
    val eventId = varchar("event_id", 64)
    val ord = integer("ord")
    val text = varchar("text", 500)
    override val primaryKey = PrimaryKey(id)
}

/** Estado del checklist POR PUESTO: cada puesto marca su propia copia de la plantilla. */
object ChecklistStateT : Table("checklist_state") {
    val itemId = varchar("item_id", 64)
    val puestoId = varchar("puesto_id", 64)
    val eventId = varchar("event_id", 64) // redundante: facilita lecturas/limpieza por evento
    val done = bool("done").default(false)
    // Quién y cuándo hizo el último cambio (auditable para el admin).
    val markedBy = varchar("marked_by", 64).nullable()
    val markedAt = text("marked_at").nullable()
    override val primaryKey = PrimaryKey(itemId, puestoId)
}

/**
 * Registro diario de checklist COMPLETO por posición (logro "Puesto impecable"). A
 * diferencia de [ChecklistStateT] (se purga cada día) NUNCA se purga: una fila = ese día
 * la posición tenía marcadas todas las revisiones de la plantilla. Desmarcar una el mismo
 * día la quita.
 */
object ChecklistCompletionsT : Table("checklist_completions") {
    val eventId = varchar("event_id", 64)
    val puestoId = varchar("puesto_id", 64)
    val day = varchar("day", 16) // ISO LocalDate (corte medianoche CDMX, como el checklist)
    override val primaryKey = PrimaryKey(eventId, puestoId, day)
}

/**
 * Pase de lista del jefe de posición: asistencia POR DÍA del evento. REGISTRO
 * HISTÓRICO (a diferencia de [ChecklistStateT] nunca se purga); sin fila = sin marcar.
 */
object AttendanceT : Table("attendance") {
    val eventId = varchar("event_id", 64)
    val day = varchar("day", 16) // ISO LocalDate (corte medianoche CDMX)
    val officerId = varchar("officer_id", 64)
    val present = bool("present")
    val markedBy = varchar("marked_by", 64)
    val markedAt = text("marked_at") // Instant ISO-UTC
    // Snapshot de la posición (puesto o activo) al marcar: los reacomodos posteriores
    // de asignaciones no reescriben el registro.
    val puestoId = varchar("puesto_id", 64)
    override val primaryKey = PrimaryKey(eventId, day, officerId)
}

object CircuitsT : Table("circuits") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    val name = varchar("name", 200)
    val location = varchar("location", 200)
    val country = varchar("country", 100).nullable()
    // Borrado LÓGICO (ISO-8601; null = vivo): la cadena circuito→trazado→puesto/activo
    // se archiva en vez de borrarse porque eventos/asignaciones históricos la referencian.
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TrazadosT : Table("trazados") {
    val id = varchar("id", 64)
    val circuitId = varchar("circuit_id", 64)
    val ord = integer("ord")
    val name = varchar("name", 200)
    // Longitud en METROS (entero). default(0) permite el ALTER sobre una tabla viva;
    // la migración de init() la rellena desde el legado length_km.
    val lengthM = integer("length_m").default(0)
    val curves = integer("curves")
    val direction = varchar("direction", 32)
    val mapUrl = varchar("map_url", 500).nullable()
    // Silueta normalizada (JSON [{x,y}…]), derivada del dibujo geo del admin.
    val pathJson = text("path_json").nullable()
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Vértices GEO (lat/lon) del trazado tal como se dibujó sobre OSM; solo admin (para re-editar). */
object TrazadoGeoT : Table("trazado_geo") {
    val trazadoId = varchar("trazado_id", 64)
    val ord = integer("ord")
    val lat = double("lat")
    val lon = double("lon")
    override val primaryKey = PrimaryKey(trazadoId, ord)
}

object PuestosT : Table("puestos") {
    val id = varchar("id", 64)
    val trazadoId = varchar("trazado_id", 64)
    val number = integer("number")
    // Identificador mostrado ("MP 5"); null = derivar de number.
    val label = varchar("label", 100).nullable()
    // x/y normalizadas: DERIVADAS de lat/lon con la referencia del trazado.
    val x = float("x")
    val y = float("y")
    // Coordenadas absolutas (fuente de verdad; null = dato legado sin geo).
    val lat = double("lat").nullable()
    val lon = double("lon").nullable()
    // false = posición sin lugar en el mapa (coordinación de zona): no se dibuja.
    val onMap = bool("on_map").default(true)
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TrackAssetsT : Table("track_assets") {
    val id = varchar("id", 64)
    val trazadoId = varchar("trazado_id", 64)
    val type = varchar("type", 32)
    val label = varchar("label", 100)
    val x = float("x")
    val y = float("y")
    val lat = double("lat").nullable()
    val lon = double("lon").nullable()
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** CAMPEONATO (serie): Fórmula 1, NASCAR… Dueño del logo y de sus temporadas. */
object SeriesT : Table("series") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    val name = varchar("name", 200)
    val emblemUrl = varchar("emblem_url", 500).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * TEMPORADA de un campeonato (API: "championship"). `name` es copia del nombre del
 * campeonato (se mantiene al renombrarlo); el logo vive en [SeriesT].
 */
object ChampionshipsT : Table("championships") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    val name = varchar("name", 200)
    val season = integer("season") // año para ordenar (el de cierre: 2025-26 → 2026)
    val emblemUrl = varchar("emblem_url", 500).nullable() // legado: el logo pasó a series
    // Nullable solo para el ALTER sobre una DB viva; init() los rellena.
    val seriesId = varchar("series_id", 64).nullable()
    val seasonLabel = varchar("season_label", 32).nullable() // "2026" | "2025-26"
    override val primaryKey = PrimaryKey(id)
}

object CategoriesT : Table("categories") {
    val id = varchar("id", 64)
    val championshipId = varchar("championship_id", 64)
    val ord = integer("ord")
    val name = varchar("name", 200)
    override val primaryKey = PrimaryKey(id)
}

// Normalizada: solo referencia al piloto (category_id + driver_ref); número, nombre y
// equipo se resuelven de la tabla de pilotos al leer.
object StandingsT : Table("standings") {
    val categoryId = varchar("category_id", 64)
    val pos = integer("pos")
    val driverRef = varchar("driver_ref", 120)
    val points = integer("points")
    override val primaryKey = PrimaryKey(categoryId, pos)
}

/**
 * Ingesta AUTOMÁTICA de posiciones por categoría: configuración (fuente + parámetro) y
 * estado del job (hasta qué fecha del calendario llega lo guardado, confirmación, error).
 * Solo admin; la app ve el crédito y la fecha derivados en `Category`.
 */
/**
 * Tabla leída de una IMAGEN (campeonatos que solo publican así su clasificación, p. ej. la México
 * Racing Cup): la guarda quien la lee (hoy, un agente por la API admin o el cargador) y la fuente
 * `mexicoracingcup-img` la publica mientras la imagen vigente del sitio sea [imageUrl].
 */
object StandingsReadingsT : Table("standings_readings") {
    val categoryId = varchar("category_id", 64)
    val imageUrl = varchar("image_url", 500)
    val throughRound = integer("through_round")
    val rowsJson = text("rows_json")
    val readAt = varchar("read_at", 40)
    val readBy = varchar("read_by", 120)
    override val primaryKey = PrimaryKey(categoryId)
}

object StandingsIngestT : Table("standings_ingest") {
    val categoryId = varchar("category_id", 64)
    val sourceId = varchar("source", 40)
    val param = varchar("param", 200).nullable()
    val enabled = bool("enabled").default(true)
    val throughRound = integer("through_round").nullable() // fecha reflejada por lo guardado
    val confirmedRound = integer("confirmed_round").nullable() // pasada de confirmación hecha
    val contentHash = varchar("content_hash", 64).nullable()
    val syncedAt = varchar("synced_at", 40).nullable() // ISO Instant del último cambio escrito
    val lastAttemptAt = varchar("last_attempt_at", 40).nullable()
    val lastNote = varchar("last_note", 300).nullable() // "esperando: …" / "sin cambios"
    val lastError = varchar("last_error", 1000).nullable()
    override val primaryKey = PrimaryKey(categoryId)
}

object RoundsT : Table("rounds") {
    val categoryId = varchar("category_id", 64)
    val number = integer("number")
    val date = varchar("date", 16) // ISO LocalDate
    val circuitName = varchar("circuit_name", 200)
    val status = varchar("status", 32)
    val winner = varchar("winner", 200).nullable()
    // Referencia al catálogo de circuitos (null = sin circuito: rally o dato legado).
    val circuitId = varchar("circuit_id", 64).nullable()
    val name = varchar("name", 200).nullable() // nombre del evento
    val location = varchar("location", 200).nullable() // sede en texto (rallies)
    val startDate = varchar("start_date", 16).nullable() // ISO LocalDate
    // Id estable (UUIDv7): la planeación de los oficiales se liga a la carrera y el
    // reemplazo del calendario lo conserva (sede + nombre, o sede + fecha cercana).
    // Nullable solo para el ALTER sobre una DB viva; init() rellena los que falten.
    val id = varchar("id", 64).nullable()
    override val primaryKey = PrimaryKey(categoryId, number)
}

// Llave = ref (estable: id de la fuente o número/nombre capturado); el número no sirve
// de llave (se comparte entre sustitutos, "007" ≠ "7", en rally cambia cada fecha).
object DriversT : Table("drivers") {
    val categoryId = varchar("category_id", 64)
    val ref = varchar("ref", 120)
    val number = integer("number") // legado numérico (0 = sin número o no numérico)
    val numberText = varchar("number_text", 8).nullable()
    val ord = integer("ord")
    val name = varchar("name", 200)
    val team = varchar("team", 200)
    /** Id de su foto en `images` (kind `driver`); la trae la ingesta automática. */
    val photo = varchar("photo", 64).nullable()
    override val primaryKey = PrimaryKey(categoryId, ref)
}

object ConvocatoriasT : Table("convocatorias") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    val eventName = varchar("event_name", 300)
    val eventDate = varchar("event_date", 100)
    val location = varchar("location", 200)
    val registrationCloseAt = varchar("registration_close_at", 40) // ISO Instant
    val cupo = integer("cupo")
    val indicacionesMarkdown = text("indicaciones_md")
    val status = varchar("status", 32)
    val externalApplyUrl = varchar("external_apply_url", 500).nullable()
    val participated = bool("participated").default(false)
    // Referencia al catálogo de circuitos (null = sede solo como texto libre).
    val circuitId = varchar("circuit_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

object AgendaEntriesT : Table("agenda_entries") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    // null = entrada global (visible para todos); si no, del oficial dueño.
    val officerId = varchar("officer_id", 64).nullable()
    val kind = varchar("kind", 32)
    val title = varchar("title", 300)
    val at = varchar("at", 40).nullable() // ISO Instant
    val allDay = bool("all_day").default(false)
    val location = varchar("location", 200).nullable()
    val eventId = varchar("event_id", 64).nullable()
    val convocatoriaId = varchar("convocatoria_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Info de emergencia: privada; solo el dueño (PUT/GET propios) o el jefe de puesto en evento activo (auditado). */
object EmergencyInfoT : Table("emergency_info") {
    val officerId = varchar("officer_id", 64)
    val contactName = varchar("contact_name", 200).nullable()
    val contactPhone = varchar("contact_phone", 60).nullable()
    val bloodType = varchar("blood_type", 8).nullable()
    val allergies = varchar("allergies", 300).nullable()
    override val primaryKey = PrimaryKey(officerId)
}

/** Registro auditable: cada consulta a la emergencia de otro oficial queda registrada. */
object EmergencyAccessesT : Table("emergency_accesses") {
    val id = varchar("id", 64)
    val ownerId = varchar("owner_id", 64)
    val viewerId = varchar("viewer_id", 64)
    val at = varchar("at", 40) // ISO Instant
    val eventId = varchar("event_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

object TripItemsT : Table("trip_items") {
    val id = varchar("id", 64)
    val eventId = varchar("event_id", 64).nullable()
    val convocatoriaId = varchar("convocatoria_id", 64).nullable()
    val officerId = varchar("officer_id", 64).nullable() // null = global (demo)
    val ord = integer("ord")
    val kind = varchar("kind", 32)
    val title = varchar("title", 300)
    val detail = varchar("detail", 500).nullable()
    val at = varchar("at", 40).nullable() // ISO Instant
    // Carrera del calendario vinculada (alternativa a event_id/convocatoria_id).
    val roundId = varchar("round_id", 64).nullable()
    // Fin opcional: llegada del transporte / salida del hospedaje (ISO Instant).
    val endsAt = varchar("ends_at", 40).nullable()
    override val primaryKey = PrimaryKey(id)
}

object MessagesT : Table("messages") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64)
    val ord = integer("ord")
    val senderId = varchar("sender_id", 64).nullable()
    val senderName = varchar("sender_name", 200)
    val text = varchar("text", 2000)
    val at = varchar("at", 40) // ISO Instant
    val system = bool("system").default(false)
    val mediaType = varchar("media_type", 16).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Imágenes procesadas (pipeline genérico): del original recortado se generan variantes
 * (thumb/full). `kind` = avatar | championship | circuit… para reusar con logos.
 */
object ImagesT : Table("images") {
    val kind = varchar("kind", 32)
    val ownerId = varchar("owner_id", 64)
    val variant = varchar("variant", 16) // thumb | full
    val contentType = varchar("content_type", 64)
    val data = binary("data")
    override val primaryKey = PrimaryKey(kind, ownerId, variant)
}

/** Membresía por oficial en chats públicos (unirse/salir persistente). */
object ChatMembersT : Table("chat_members") {
    val chatId = varchar("chat_id", 64)
    val officerId = varchar("officer_id", 64)
    override val primaryKey = PrimaryKey(chatId, officerId)
}

/**
 * Invitaciones PENDIENTES a chats privados: el invitado entra solo si acepta (privacy-
 * first: nadie te mete a un grupo sin tu consentimiento). Aceptar/rechazar borra la fila.
 */
object ChatInvitesT : Table("chat_invites") {
    val chatId = varchar("chat_id", 64)
    val officerId = varchar("officer_id", 64)
    val inviterId = varchar("inviter_id", 64)
    val createdAt = varchar("created_at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(chatId, officerId)
}

/** Reportes de mensajes (moderación básica): quién reportó qué; los revisa el admin. */
object MessageReportsT : Table("message_reports") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64)
    val messageId = varchar("message_id", 64)
    val reporterId = varchar("reporter_id", 64)
    val reason = varchar("reason", 300).nullable()
    val createdAt = varchar("created_at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(messageId, reporterId)
    }
}

/** Hasta dónde ha leído cada oficial en cada chat (para el indicador de no leídos). */
object ChatReadsT : Table("chat_reads") {
    val chatId = varchar("chat_id", 64)
    val officerId = varchar("officer_id", 64)
    val lastReadOrd = integer("last_read_ord").default(0)
    override val primaryKey = PrimaryKey(chatId, officerId)
}

object ChatsT : Table("chats") {
    val id = varchar("id", 64)
    val ord = integer("ord")
    val type = varchar("type", 32)
    val name = varchar("name", 200)
    val membersCount = integer("members_count").default(0)
    val lastPreview = varchar("last_preview", 500).nullable()
    val unread = integer("unread").default(0)
    val archived = bool("archived").default(false)
    val archivedAt = varchar("archived_at", 40).nullable() // ISO Instant
    val joined = bool("joined").default(true)
    val description = varchar("description", 300).nullable()
    val creatorId = varchar("creator_id", 64).nullable()
    // Chats de evento/puesto: a qué evento pertenecen (null = público o legado).
    val eventId = varchar("event_id", 64).nullable()
    // Chats de PUESTO: la posición (puesto o activo tripulado) del evento; uno por posición
    // con asignaciones. null = legado (ya no se lista).
    val puestoId = varchar("puesto_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

// —— Compartir ubicación (opt-in, allowlist que el usuario controla; NO es grafo social) ——

/** Interruptor por oficial (sin fila = apagado). */
object LocationSettingsT : Table("location_settings") {
    val officerId = varchar("officer_id", 64)
    val enabled = bool("enabled").default(false)
    override val primaryKey = PrimaryKey(officerId)
}

/**
 * Allowlist PERMANENTE: [ownerId] comparte su ubicación con [targetId]. Al leer se filtra
 * por evento: solo fluye si ambos están asignados al evento activo.
 */
object LocationSharesT : Table("location_shares") {
    val ownerId = varchar("owner_id", 64)
    val targetId = varchar("target_id", 64)
    val createdAt = varchar("created_at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(ownerId, targetId)
}

/** Oficiales que [viewerId] ocultó de SU mapa (vista propia; no revoca al emisor). */
object LocationHiddenT : Table("location_hidden") {
    val viewerId = varchar("viewer_id", 64)
    val targetId = varchar("target_id", 64)
    override val primaryKey = PrimaryKey(viewerId, targetId)
}

// —— Convivencia (2026-09-26): nadie te mete a un grupo ni te avisa sin tu consentimiento ——

/**
 * "No me vuelvas a invitar a ESTE chat": quien rechazó una invitación o salió de un grupo.
 * Nadie puede volver a invitarlo a ese chat (para entrar a un público le basta con unirse).
 */
object ChatInviteDeclinesT : Table("chat_invite_declines") {
    val chatId = varchar("chat_id", 64)
    val officerId = varchar("officer_id", 64)
    val at = varchar("at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(chatId, officerId)
}

/**
 * Bloqueos: [blockerId] no quiere nada de [blockedId] — ni invitaciones a chats ni que le
 * comparta su ubicación (ni el aviso). Solo lo ve quien bloquea.
 */
object OfficerBlocksT : Table("officer_blocks") {
    val blockerId = varchar("blocker_id", 64)
    val blockedId = varchar("blocked_id", 64)
    val createdAt = varchar("created_at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(blockerId, blockedId)
}

/**
 * Reportes de un CHAT (nombre, imagen o descripción) o del PERFIL de un oficial (nombre o
 * foto); los de mensajes viven en [MessageReportsT]. Uno por reportero y objetivo.
 */
object ContentReportsT : Table("content_reports") {
    val id = varchar("id", 64)
    val kind = varchar("kind", 16) // chat | officer
    val targetId = varchar("target_id", 64)
    val reporterId = varchar("reporter_id", 64)
    val reason = varchar("reason", 1000).nullable()
    val createdAt = varchar("created_at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(kind, targetId, reporterId)
    }
}

/**
 * Último aviso "X te comparte su ubicación" por pareja: prender y apagar la allowlist ya
 * no genera un aviso cada vez (como mucho uno por semana por pareja).
 */
object LocationShareNoticesT : Table("location_share_notices") {
    val ownerId = varchar("owner_id", 64)
    val targetId = varchar("target_id", 64)
    val at = varchar("at", 40) // ISO Instant
    override val primaryKey = PrimaryKey(ownerId, targetId)
}
