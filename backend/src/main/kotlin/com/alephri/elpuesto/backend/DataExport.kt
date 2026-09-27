package com.alephri.elpuesto.backend

import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/** Descargas de "mis datos" (límite diario, registro visible para el titular). */
object DataExportsT : Table("data_exports") {
    val id = varchar("id", 64)
    val officerId = varchar("officer_id", 64)
    val at = varchar("at", 40) // ISO Instant
    /**
     * false = empezó y no terminó (se cortó): cuenta igual, pero su espera es corta
     * ([RETRY_AFTER_FAILED]). Así abortar a propósito no sirve para pedir sin límite.
     */
    val complete = bool("complete").default(true)
    override val primaryKey = PrimaryKey(id)
}

/**
 * "Descargar mis datos": un ZIP con TODO lo que el sistema guarda del oficial, en JSON
 * (un archivo por tema) + sus fotos. Privacy-first: solo lo PROPIO — de los chats, solo
 * los mensajes que él envió; nombres de terceros solo donde la app ya se los muestra
 * (quién consultó su emergencia, quién le pasó lista, con quién comparte ubicación).
 * Nunca correos ajenos salvo los de SUS invitaciones (él los capturó).
 */
object DataExport {
    /** Una descarga por día (ventana de 24 h). */
    val COOLDOWN: Duration = Duration.ofHours(24)

    /** Una descarga que se cortó se puede reintentar tras 1 h. */
    val RETRY_AFTER_FAILED: Duration = Duration.ofHours(1)

    /** Oficiales con una descarga EN CURSO (una a la vez por oficial). */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val ZONA = ZoneId.of("America/Mexico_City")
    private val json = Json { prettyPrint = true }

    fun init() = transaction { SchemaUtils.createMissingTablesAndColumns(DataExportsT) }

    /** Cuándo podrá volver a descargar; null = ya puede. */
    fun nextAllowedAt(officerId: String, now: Instant = Instant.now()): Instant? = transaction {
        DataExportsT.selectAll().where { DataExportsT.officerId eq officerId }
            .map { Instant.parse(it[DataExportsT.at]).plus(if (it[DataExportsT.complete]) COOLDOWN else RETRY_AFTER_FAILED) }
            .maxOrNull()?.takeIf { it.isAfter(now) }
    }

    /**
     * Aparta la descarga ANTES de generarla: una sola en curso por oficial y queda
     * registrada desde que empieza (antes contaba solo al terminar y muchas en paralelo
     * pasaban el límite). Devuelve el id del registro, o null si ya hay una en curso.
     */
    fun begin(officerId: String, at: Instant = Instant.now()): String? {
        if (!inFlight.add(officerId)) return null
        return try {
            transaction {
                val id = uuidv7()
                DataExportsT.insert {
                    it[DataExportsT.id] = id; it[DataExportsT.officerId] = officerId
                    it[DataExportsT.at] = at.toString(); it[complete] = false
                }
                id
            }
        } catch (e: Exception) {
            inFlight.remove(officerId)
            throw e
        }
    }

    /** Fin de la descarga apartada con [begin]: completa (espera 24 h) o cortada (1 h). */
    fun finish(officerId: String, recordId: String, completed: Boolean) {
        try {
            if (completed) transaction { DataExportsT.update({ DataExportsT.id eq recordId }) { it[complete] = true } }
        } finally {
            inFlight.remove(officerId)
        }
    }

    /** Descargas completas previas (para el "Registro de accesos" y el propio export). */
    fun exportsOf(officerId: String): List<Instant> = transaction {
        DataExportsT.selectAll().where { (DataExportsT.officerId eq officerId) and (DataExportsT.complete eq true) }
            .orderBy(DataExportsT.at to SortOrder.DESC).map { Instant.parse(it[DataExportsT.at]) }
    }

    /** Fecha legible en CDMX ("25 sep 2026, 14:05") para correos y el LÉEME. */
    fun humanDate(at: Instant): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("es-MX")).format(at.atZone(ZONA))

    fun fileName(now: Instant = Instant.now()): String = "el-puesto-mis-datos-${now.atZone(ZONA).toLocalDate()}.zip"

    private fun s(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    /** Foto por incluir: se lee de la base y se escribe al ZIP de una en una. */
    private data class PhotoRef(val path: String, val kind: String, val owner: String)

    /**
     * Escribe el ZIP completo en [out]. Las fotos NO se cargan todas en memoria (antes, un
     * oficial con cientos de fotos podía tumbar el servidor): se anotan y se copian una por
     * una al final.
     */
    fun write(officerId: String, out: OutputStream, now: Instant = Instant.now()) {
        val files = linkedMapOf<String, JsonElement>()
        val photos = mutableListOf<PhotoRef>()
        transaction {
            /** Anota la foto si existe (sin traer sus bytes); devuelve su ruta en el ZIP. */
            fun image(kind: String, owner: String, path: String): String? {
                val exists = ImagesT.select(ImagesT.ownerId)
                    .where { (ImagesT.kind eq kind) and (ImagesT.ownerId eq owner) and (ImagesT.variant eq "full") }.any()
                if (!exists) return null
                photos += PhotoRef(path, kind, owner)
                return path
            }
            fun officerNames(ids: Collection<String>): Map<String, String> =
                if (ids.isEmpty()) emptyMap()
                else Officers.selectAll().where { Officers.id inList ids.toSet() }.associate { it[Officers.id] to it[Officers.displayName] }

            val me = Officers.selectAll().where { Officers.id eq officerId }.first()
            val accounts = Accounts.selectAll().where { Accounts.officerId eq officerId }.toList()
            val emails = accounts.map { it[Accounts.email] }

            // —— Cuenta y perfil ——
            val invitedBy = Invitations.selectAll().where { Invitations.inviteeEmail inList emails }
                .orderBy(Invitations.createdAt to SortOrder.ASC).firstOrNull()
            files["cuenta.json"] = buildJsonObject {
                put("correos", JsonArray(accounts.map { a ->
                    buildJsonObject {
                        put("correo", a[Accounts.email]); put("estado", a[Accounts.status])
                        put("bienvenidaCompletada", a[Accounts.onboardedAt]?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    }
                }))
                put("invitadoPor", invitedBy?.let { i ->
                    buildJsonObject {
                        put("nombre", officerNames(listOf(i[Invitations.inviterId]))[i[Invitations.inviterId]] ?: "—")
                        put("fecha", i[Invitations.createdAt])
                    }
                } ?: JsonNull)
                put("exportadoEl", now.toString())
            }
            val avatar = image("avatar", officerId, "fotos/perfil.jpg")
            files["perfil.json"] = buildJsonObject {
                put("id", officerId)
                put("omdaiId", me[Officers.omdaiId])
                put("nombre", me[Officers.displayName])
                put("areaAsignada", s(me[Officers.area]))
                put("activoDesde", me[Officers.activeSince]?.let { JsonPrimitive(it) } ?: JsonNull)
                put("foto", s(avatar))
            }
            val emg = EmergencyInfoT.selectAll().where { EmergencyInfoT.officerId eq officerId }.firstOrNull()
            files["emergencia.json"] = buildJsonObject {
                put("contacto", s(emg?.get(EmergencyInfoT.contactName)))
                put("telefono", s(emg?.get(EmergencyInfoT.contactPhone)))
                put("tipoDeSangre", s(emg?.get(EmergencyInfoT.bloodType)))
                put("alergias", s(emg?.get(EmergencyInfoT.allergies)))
            }

            // —— Eventos (roster y registro por honor) y asistencia ——
            val assignments = Assignments.selectAll().where { Assignments.officerId eq officerId }.toList()
            val declared = ParticipationsT.selectAll().where { ParticipationsT.officerId eq officerId }.toList()
            val events = Events.selectAll()
                .where { Events.id inList (assignments.map { it[Assignments.eventId] } + declared.map { it[ParticipationsT.eventId] }).toSet() }
                .associateBy { it[Events.id] }
            val attendance = AttendanceT.selectAll().where { AttendanceT.officerId eq officerId }.toList()
            val allEventIds = (events.keys + attendance.map { it[AttendanceT.eventId] }).toSet()
            val eventNames = if (allEventIds.isEmpty()) emptyMap()
            else Events.selectAll().where { Events.id inList allEventIds }.associate { it[Events.id] to it[Events.name] }
            val circuits = CircuitsT.selectAll().where { CircuitsT.id inList events.values.map { it[Events.circuitId] }.toSet() }
                .associate { it[CircuitsT.id] to it[CircuitsT.name] }
            val positions = DomainRepository.puestoDisplayNames(
                assignments.map { it[Assignments.puestoId] } + attendance.map { it[AttendanceT.puestoId] } +
                    declared.mapNotNull { it[ParticipationsT.positionId] },
            )
            val proposed = ProposalRepository.pendingLabelsTx(declared.mapNotNull { it[ParticipationsT.proposalId] })
            files["eventos.json"] = JsonArray(
                assignments.mapNotNull { a ->
                    val e = events[a[Assignments.eventId]] ?: return@mapNotNull null
                    buildJsonObject {
                        put("evento", e[Events.name])
                        put("inicio", e[Events.startsOn]); put("fin", e[Events.endsOn])
                        put("circuito", circuits[e[Events.circuitId]] ?: "—")
                        put("posicion", positions[a[Assignments.puestoId]] ?: "P ${a[Assignments.puestoNumber]}")
                        put("rol", a[Assignments.role])
                        put("turno", s(a[Assignments.shift]))
                        put("origen", "roster de la organización")
                    }
                } + declared.mapNotNull { d ->
                    val e = events[d[ParticipationsT.eventId]] ?: return@mapNotNull null
                    buildJsonObject {
                        put("evento", e[Events.name])
                        put("inicio", e[Events.startsOn]); put("fin", e[Events.endsOn])
                        put("circuito", circuits[e[Events.circuitId]] ?: "—")
                        put(
                            "posicion",
                            s(
                                d[ParticipationsT.positionId]?.let { positions[it] }
                                    ?: d[ParticipationsT.proposalId]?.let { proposed[it] }?.let { "$it (propuesto, en revisión)" },
                            ),
                        )
                        put("rol", d[ParticipationsT.role])
                        put("dias", JsonArray(ParticipationRepository.daysOf(d[ParticipationsT.days], e[Events.startsOn], e[Events.endsOn]).map { JsonPrimitive(it) }))
                        put("origen", "registro propio (sistema de honor)")
                        put("registradoEl", d[ParticipationsT.createdAt])
                    }
                }.sortedBy { it["inicio"].toString() },
            )
            val markers = officerNames(attendance.map { it[AttendanceT.markedBy] })
            files["asistencia.json"] = JsonArray(
                attendance.sortedWith(compareBy({ it[AttendanceT.day] }, { it[AttendanceT.eventId] })).map { r ->
                    buildJsonObject {
                        put("evento", eventNames[r[AttendanceT.eventId]] ?: "—")
                        put("dia", r[AttendanceT.day])
                        put("presente", r[AttendanceT.present])
                        put("posicion", positions[r[AttendanceT.puestoId]] ?: "—")
                        put("marcadaPor", markers[r[AttendanceT.markedBy]] ?: "—")
                        put("marcadaEl", r[AttendanceT.markedAt])
                    }
                },
            )

            // —— Bitácora y planeación (con fotos) ——
            val trips = TripItemsT.selectAll().where { TripItemsT.officerId eq officerId }.orderBy(TripItemsT.at to SortOrder.ASC).toList()
            val tripEvents = trips.mapNotNull { it[TripItemsT.eventId] }.toSet().let { ids ->
                if (ids.isEmpty()) emptyMap() else Events.selectAll().where { Events.id inList ids }.associate { it[Events.id] to it[Events.name] }
            }
            val tripConvs = trips.mapNotNull { it[TripItemsT.convocatoriaId] }.toSet().let { ids ->
                if (ids.isEmpty()) emptyMap() else ConvocatoriasT.selectAll().where { ConvocatoriasT.id inList ids }.associate { it[ConvocatoriasT.id] to it[ConvocatoriasT.eventName] }
            }
            val tripRounds = trips.mapNotNull { it[TripItemsT.roundId] }.toSet().let { ids ->
                if (ids.isEmpty()) emptyMap() else RoundsT.selectAll().where { RoundsT.id inList ids }.associate { it[RoundsT.id]!! to (it[RoundsT.name] ?: "Fecha ${it[RoundsT.number]}") }
            }
            files["bitacora.json"] = JsonArray(
                trips.map { t ->
                    val photoPath = if (t[TripItemsT.kind] == "PHOTO") image("trip", t[TripItemsT.id], "fotos/bitacora/${t[TripItemsT.id]}.jpg") else null
                    buildJsonObject {
                        put("tipo", t[TripItemsT.kind])
                        put("titulo", t[TripItemsT.title])
                        put("detalle", s(t[TripItemsT.detail]))
                        put("fecha", s(t[TripItemsT.at]))
                        put("hasta", s(t[TripItemsT.endsAt]))
                        put("evento", s(t[TripItemsT.eventId]?.let(tripEvents::get)))
                        put("carrera", s(t[TripItemsT.roundId]?.let(tripRounds::get)))
                        put("convocatoria", s(t[TripItemsT.convocatoriaId]?.let(tripConvs::get)))
                        put("foto", s(photoPath))
                    }
                },
            )

            // —— Chats: SOLO los mensajes propios, membresías y reportes hechos ——
            val messages = MessagesT.selectAll()
                .where { (MessagesT.senderId eq officerId) and (MessagesT.system eq false) }
                .orderBy(MessagesT.at to SortOrder.ASC).toList()
            val memberships = ChatMembersT.selectAll().where { ChatMembersT.officerId eq officerId }.map { it[ChatMembersT.chatId] }
            val reports = MessageReportsT.selectAll().where { MessageReportsT.reporterId eq officerId }.toList()
            // Invitaciones a chats privados que aún no respondes (quién te invitó y cuándo).
            val chatInvites = ChatInvitesT.selectAll().where { ChatInvitesT.officerId eq officerId }.toList()
            val inviters = chatInvites.map { it[ChatInvitesT.inviterId] }.distinct().let { ids ->
                if (ids.isEmpty()) emptyMap()
                else Officers.selectAll().where { Officers.id inList ids }.associate { it[Officers.id] to it[Officers.displayName] }
            }
            val chatIds = (messages.map { it[MessagesT.chatId] } + memberships + reports.map { it[MessageReportsT.chatId] } +
                chatInvites.map { it[ChatInvitesT.chatId] }).toSet()
            val chats = if (chatIds.isEmpty()) emptyMap()
            else ChatsT.selectAll().where { ChatsT.id inList chatIds }.associate { it[ChatsT.id] to (it[ChatsT.name] to it[ChatsT.type]) }
            files["mensajes.json"] = JsonArray(
                messages.map { m ->
                    val photoPath = if (m[MessagesT.mediaType] != null) image("chatmedia", m[MessagesT.id], "fotos/chat/${m[MessagesT.id]}.jpg") else null
                    buildJsonObject {
                        put("chat", chats[m[MessagesT.chatId]]?.first ?: "—")
                        put("tipoDeChat", chats[m[MessagesT.chatId]]?.second ?: "—")
                        put("fecha", m[MessagesT.at])
                        put("texto", m[MessagesT.text])
                        put("foto", s(photoPath))
                    }
                },
            )
            files["chats.json"] = JsonArray(
                memberships.mapNotNull { id ->
                    chats[id]?.let { (name, type) -> buildJsonObject { put("chat", name); put("tipo", type); put("estado", "miembro") } }
                } + chatInvites.mapNotNull { inv ->
                    chats[inv[ChatInvitesT.chatId]]?.let { (name, type) ->
                        buildJsonObject {
                            put("chat", name); put("tipo", type); put("estado", "invitación pendiente")
                            put("invitadoPor", inviters[inv[ChatInvitesT.inviterId]] ?: "—")
                            put("fecha", inv[ChatInvitesT.createdAt])
                        }
                    }
                },
            )
            files["reportes.json"] = JsonArray(
                reports.sortedBy { it[MessageReportsT.createdAt] }.map { r ->
                    buildJsonObject {
                        put("chat", chats[r[MessageReportsT.chatId]]?.first ?: "—")
                        put("fecha", r[MessageReportsT.createdAt])
                        put("motivo", s(r[MessageReportsT.reason]))
                    }
                },
            )

            // —— Compartir ubicación (nunca se guardan posiciones) ——
            val sharesMine = LocationSharesT.selectAll().where { LocationSharesT.ownerId eq officerId }.map { it[LocationSharesT.targetId] }
            val sharesToMe = LocationSharesT.selectAll().where { LocationSharesT.targetId eq officerId }.map { it[LocationSharesT.ownerId] }
            val hidden = LocationHiddenT.selectAll().where { LocationHiddenT.viewerId eq officerId }.map { it[LocationHiddenT.targetId] }
            val locNames = officerNames(sharesMine + sharesToMe + hidden)
            files["ubicacion.json"] = buildJsonObject {
                put("compartirActivado", LocationSettingsT.selectAll().where { LocationSettingsT.officerId eq officerId }.firstOrNull()?.get(LocationSettingsT.enabled) ?: false)
                put("compartesCon", JsonArray(sharesMine.map { JsonPrimitive(locNames[it] ?: "—") }))
                put("teComparten", JsonArray(sharesToMe.map { JsonPrimitive(locNames[it] ?: "—") }))
                put("ocultos", JsonArray(hidden.map { JsonPrimitive(locNames[it] ?: "—") }))
                put("nota", "Las posiciones nunca se guardan: solo viven en memoria durante un evento.")
            }

            // —— Invitaciones, accesos a la emergencia y descargas previas ——
            val invites = Invitations.selectAll().where { Invitations.inviterId eq officerId }.orderBy(Invitations.createdAt to SortOrder.ASC).toList()
            val inviteeStatus = Accounts.selectAll().where { Accounts.email inList invites.map { it[Invitations.inviteeEmail] } }
                .associate { it[Accounts.email] to it[Accounts.status] }
            files["invitaciones.json"] = JsonArray(
                invites.map { i ->
                    buildJsonObject {
                        put("correo", i[Invitations.inviteeEmail])
                        put("fecha", i[Invitations.createdAt])
                        put("estado", inviteeStatus[i[Invitations.inviteeEmail]] ?: "—")
                    }
                },
            )
            val accesses = EmergencyAccessesT.selectAll().where { EmergencyAccessesT.ownerId eq officerId }.orderBy(EmergencyAccessesT.at to SortOrder.ASC).toList()
            val viewers = officerNames(accesses.map { it[EmergencyAccessesT.viewerId] })
            val accessEvents = accesses.mapNotNull { it[EmergencyAccessesT.eventId] }.toSet().let { ids ->
                if (ids.isEmpty()) emptyMap() else Events.selectAll().where { Events.id inList ids }.associate { it[Events.id] to it[Events.name] }
            }
            files["accesos-a-tu-emergencia.json"] = JsonArray(
                accesses.map { a ->
                    buildJsonObject {
                        put("quien", viewers[a[EmergencyAccessesT.viewerId]] ?: "—")
                        put("fecha", a[EmergencyAccessesT.at])
                        put("evento", s(a[EmergencyAccessesT.eventId]?.let(accessEvents::get)))
                    }
                },
            )
            files["descargas-anteriores.json"] = buildJsonArray {
                DataExportsT.selectAll().where { (DataExportsT.officerId eq officerId) and (DataExportsT.complete eq true) }
                    .orderBy(DataExportsT.at to SortOrder.ASC).forEach { add(JsonPrimitive(it[DataExportsT.at])) }
            }
        }

        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry("LEEME.txt", readme(now, files.keys, photos.size).toByteArray())
            files.forEach { (name, el) -> entry(name, json.encodeToString(JsonElement.serializer(), el).toByteArray()) }
            photos.forEach { ref ->
                val bytes = transaction {
                    ImagesT.selectAll()
                        .where { (ImagesT.kind eq ref.kind) and (ImagesT.ownerId eq ref.owner) and (ImagesT.variant eq "full") }
                        .firstOrNull()?.get(ImagesT.data)
                } ?: return@forEach
                entry(ref.path, bytes)
            }
        }
    }

    private fun readme(now: Instant, files: Collection<String>, photos: Int): String = """
        EL PUESTO · TUS DATOS
        Copia generada el ${humanDate(now)} (hora del centro de México).

        Contiene todo lo que El Puesto guarda de ti, en archivos JSON (texto que puedes
        abrir con cualquier editor o procesar con otras herramientas) y tus fotos.

        ${files.joinToString("\n        ") { "· $it" }}
        · fotos/ ($photos archivos: tu foto de perfil, las de tu bitácora y las que enviaste al chat)

        Privacidad: solo incluye datos TUYOS. De los chats, únicamente los mensajes que tú
        enviaste; de otras personas, solo los nombres que la app ya te muestra.

        Este archivo incluye tu información de emergencia: guárdalo en un lugar seguro.
    """.trimIndent() + "\n"
}
