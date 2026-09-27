package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.Achievement
import com.alephri.elpuesto.model.AchievementRules
import com.alephri.elpuesto.model.AchievementRules.CLEAN_POST
import com.alephri.elpuesto.model.AchievementRules.FIRST_CHAT_PHOTO
import com.alephri.elpuesto.model.AchievementRules.FIRST_LOG_NOTE
import com.alephri.elpuesto.model.AchievementRules.FIRST_LOG_PHOTO
import com.alephri.elpuesto.model.AchievementRules.FIRST_MESSAGE
import com.alephri.elpuesto.model.AchievementRules.FIRST_REMINDER
import com.alephri.elpuesto.model.AchievementRules.FIRST_TRIP
import com.alephri.elpuesto.model.AchievementRules.GRAND_PRIX
import com.alephri.elpuesto.model.AchievementRules.INTERNATIONAL
import com.alephri.elpuesto.model.AchievementRules.PASSPORT_MX
import com.alephri.elpuesto.model.AchievementRules.PROMOTION
import com.alephri.elpuesto.model.AchievementRules.READY
import com.alephri.elpuesto.model.AchievementRules.RECRUITER
import com.alephri.elpuesto.model.AchievementRules.SENIORITY
import com.alephri.elpuesto.model.AchievementRules.TRACK_DAYS
import com.alephri.elpuesto.model.AchievementRules.VERSATILITY
import com.alephri.elpuesto.model.Achievements
import com.alephri.elpuesto.model.PassportStamp
import com.alephri.elpuesto.model.SeriesPatch
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.model.YearValue
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Logros DERIVADOS al leer (gamificación, 2026-09-25): nada se captura ni se guarda — se
 * calculan de las participaciones en eventos terminados (roster y registro por honor: el
 * mismo historial del perfil), el
 * pase de lista, el registro diario de checklist completo, la bitácora, los mensajes y
 * las invitaciones. Umbrales, familias de rol y visibilidad viven en [AchievementRules]
 * (shared) para que la app muestre lo mismo que aquí se calcula.
 */
object AchievementsRepository {

    private val ZONA = java.time.ZoneId.of("America/Mexico_City")

    /**
     * Una participación que CUENTA: evento terminado con al menos un día no marcado ausente
     * (roster) o declarada por honor (con sus días). Lo declarado cuenta igual, también en
     * los logros públicos (decisión 2026-09-25: sistema de honor).
     */
    private data class Worked(
        val p: DomainRepository.PastAssignment,
        val start: LocalDate,
        val end: LocalDate,
        val days: Int,
        val country: String?,
        /** Temporadas (championship) del evento → su campeonato (serie). */
        val seasons: Map<String, String>,
    ) {
        val seriesIds: Set<String> get() = seasons.values.toSet()
    }

    /**
     * Logros de [officerId] vistos por [viewerId]: completos para el titular; de otro
     * oficial SOLO los públicos (pasaporte, campeonatos, carrera en pista). null = no existe.
     */
    fun forOfficer(officerId: String, viewerId: String): Achievements? = transaction {
        val officer = Officers.selectAll().where { Officers.id eq officerId }.firstOrNull() ?: return@transaction null
        val self = officerId == viewerId
        val worked = workedTx(officerId)
        val series = SeriesT.selectAll().associate { it[SeriesT.id] to (it[SeriesT.name] to it[SeriesT.emblemUrl]) }

        val stamps = worked.groupBy { it.p.circuitId }.map { (circuitId, ws) ->
            val first = ws.minBy { it.start }
            val last = ws.maxBy { it.start }
            PassportStamp(
                circuitId = circuitId, country = first.country, firstOn = first.start, events = ws.size,
                lastOn = last.start, lastEventName = last.p.eventName,
            )
        }.sortedBy { it.firstOn }

        val patches = worked.flatMap { w -> w.seriesIds.map { it to w } }.groupBy({ it.first }, { it.second })
            .mapNotNull { (seriesId, ws) ->
                val (name, emblem) = series[seriesId] ?: return@mapNotNull null
                val seasons = ws.flatMap { w -> w.seasons.filterValues { it == seriesId }.keys }.toSet().size
                SeriesPatch(seriesId, name, seasons.coerceAtLeast(1), emblem, ws.minOf { it.start })
            }
            .sortedWith(compareByDescending<SeriesPatch> { it.seasons }.thenBy { it.firstOn })

        val items = mutableListOf<Achievement>()
        items += tiered(PASSPORT_MX, worked) { acc, w -> if (w.country == AchievementRules.HOME_COUNTRY) acc + w.p.circuitId else acc }
        items += milestone(INTERNATIONAL, worked.firstOrNull { it.country != null && it.country != AchievementRules.HOME_COUNTRY }?.start)
        items += trackDays(worked)
        items += tiered(VERSATILITY, worked) { acc, w -> AchievementRules.roleFamily(w.p.role)?.let { acc + it } ?: acc }
        worked.firstOrNull { it.p.role in CHIEF_ROLES }.let { w ->
            items += milestone(PROMOTION, w?.start).copy(detail = w?.p?.role)
        }
        items += seniority(officer[Officers.activeSince])
        worked.filter { w -> w.seriesIds.any { s -> series[s]?.first?.let(AchievementRules::isGrandPrixSeries) == true } }.let { gps ->
            items += counter(GRAND_PRIX, gps.size, gps.firstOrNull()?.start)
        }

        if (self) {
            items += ready(officerId, officer[Officers.avatarUrl])
            items += cleanPost(worked)
            items += recruiter(officerId)
            items += firsts(officerId)
        }
        val all = Achievements(stamps = stamps, patches = patches, items = items.filter { self || AchievementRules.isPublic(it.key) })
        if (self) all else trimForOthers(all)
    }

    /**
     * Lo que otro oficial ve: solo el AÑO de cada fecha (1 de enero) y sin nombres de
     * eventos. El pasaporte dice dónde has trabajado, no cuándo ni en qué evento: el
     * historial ajeno solo se lee por "eventos en común".
     */
    private fun trimForOthers(a: Achievements): Achievements {
        fun year(d: LocalDate) = LocalDate(d.year, 1, 1)
        return a.copy(
            stamps = a.stamps.map { it.copy(firstOn = year(it.firstOn), lastOn = null, lastEventName = null) },
            patches = a.patches.map { it.copy(firstOn = it.firstOn?.let(::year)) },
            items = a.items.map { it.copy(reachedOn = it.reachedOn.map(::year)) },
        )
    }

    /** Participaciones que cuentan, en orden cronológico (la primera primero). */
    private fun workedTx(officerId: String): List<Worked> {
        val past = DomainRepository.pastAssignmentsTx(officerId).sortedBy { it.startsOn }
        if (past.isEmpty()) return emptyList()
        val eventIds = past.map { it.eventId }.toSet()
        // Días que el jefe marcó ausente (el pase de lista es registro histórico).
        val absent = AttendanceT.selectAll()
            .where { (AttendanceT.officerId eq officerId) and (AttendanceT.present eq false) }
            .filter { it[AttendanceT.eventId] in eventIds }
            .groupingBy { it[AttendanceT.eventId] }.eachCount()
        // Incluye circuitos archivados: el historial conserva su referencia.
        val countries = CircuitsT.selectAll().where { CircuitsT.id inList past.map { it.circuitId }.toSet() }
            .associate { it[CircuitsT.id] to it[CircuitsT.country] }
        val eventSeasons = EventChampionshipsT.selectAll().where { EventChampionshipsT.eventId inList eventIds }
            .groupBy({ it[EventChampionshipsT.eventId] }, { it[EventChampionshipsT.championshipId] })
        val seasonSeries = ChampionshipsT.selectAll().mapNotNull { r -> r[ChampionshipsT.seriesId]?.let { r[ChampionshipsT.id] to it } }.toMap()
        return past.mapNotNull { p ->
            val start = LocalDate.parse(p.startsOn)
            val end = LocalDate.parse(p.endsOn)
            // Declarada por honor: los días que el oficial dijo; roster: el evento menos ausencias.
            val days = p.dayCount ?: (daysBetween(start, end) - (absent[p.eventId] ?: 0)).coerceAtLeast(0)
            if (days == 0) return@mapNotNull null
            val seasons = eventSeasons[p.eventId].orEmpty().mapNotNull { s -> seasonSeries[s]?.let { s to it } }.toMap()
            Worked(p, start, end, days, countries[p.circuitId], seasons)
        }
    }

    private fun daysBetween(start: LocalDate, end: LocalDate): Int =
        (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(start.toString()), java.time.LocalDate.parse(end.toString())) + 1)
            .toInt().coerceAtLeast(1)

    /**
     * Logro con niveles cuyo valor es el tamaño de un conjunto que crece evento a evento
     * (circuitos de México, familias de rol): la fecha de cada nivel es la del evento que
     * lo alcanzó.
     */
    private fun tiered(key: String, worked: List<Worked>, add: (Set<String>, Worked) -> Set<String>): Achievement {
        var acc = emptySet<String>()
        val reached = mutableListOf<LocalDate>()
        val thresholds = AchievementRules.THRESHOLDS.getValue(key)
        worked.forEach { w ->
            acc = add(acc, w)
            while (reached.size < thresholds.size && acc.size >= thresholds[reached.size]) reached += w.start
        }
        return Achievement(key, value = acc.size, tier = AchievementRules.tierFor(key, acc.size), reachedOn = reached)
    }

    private fun milestone(key: String, on: LocalDate?): Achievement =
        Achievement(key, value = if (on != null) 1 else 0, tier = if (on != null) 1 else 0, reachedOn = listOfNotNull(on))

    private fun counter(key: String, value: Int, firstOn: LocalDate?): Achievement =
        Achievement(key, value = value, tier = AchievementRules.tierFor(key, value), reachedOn = listOfNotNull(firstOn.takeIf { value > 0 }))

    /** Días en pista: los de cada evento terminado menos los marcados ausente; desglose por año. */
    private fun trackDays(worked: List<Worked>): Achievement {
        val thresholds = AchievementRules.THRESHOLDS.getValue(TRACK_DAYS)
        var total = 0
        val reached = mutableListOf<LocalDate>()
        worked.forEach { w ->
            total += w.days
            while (reached.size < thresholds.size && total >= thresholds[reached.size]) reached += w.end
        }
        val byYear = worked.groupBy { it.start.year }.map { (y, ws) -> YearValue(y, ws.sumOf { it.days }) }.sortedByDescending { it.year }
        return Achievement(TRACK_DAYS, value = total, tier = AchievementRules.tierFor(TRACK_DAYS, total), reachedOn = reached, byYear = byYear)
    }

    /** Antigüedad: SOLO de "Activo desde" capturado (decisión 2026-09-25); sin dato = sin medalla. */
    private fun seniority(activeSince: Int?): Achievement {
        if (activeSince == null) return Achievement(SENIORITY)
        val years = (java.time.LocalDate.now(ZONA).year - activeSince).coerceAtLeast(0)
        val reached = AchievementRules.THRESHOLDS.getValue(SENIORITY).filter { years >= it }.map { LocalDate(activeSince + it, 1, 1) }
        // detail = el año de "Activo desde" (sin él, la app explica por qué no hay medalla).
        return Achievement(SENIORITY, value = years, tier = AchievementRules.tierFor(SENIORITY, years), reachedOn = reached, detail = activeSince.toString())
    }

    /** Listo para pista: foto de perfil + contacto, teléfono y tipo de sangre de emergencia. */
    private fun ready(officerId: String, avatarUrl: String?): Achievement {
        val e = EmergencyInfoT.selectAll().where { EmergencyInfoT.officerId eq officerId }.firstOrNull()
        val complete = e != null && listOf(e[EmergencyInfoT.contactName], e[EmergencyInfoT.contactPhone], e[EmergencyInfoT.bloodType])
            .all { !it.isNullOrBlank() }
        val done = complete && !avatarUrl.isNullOrBlank()
        return Achievement(READY, value = if (done) 1 else 0, tier = if (done) 1 else 0)
    }

    /** Puesto impecable: eventos donde tu posición completó el checklist TODOS los días. */
    private fun cleanPost(worked: List<Worked>): Achievement {
        if (worked.isEmpty()) return Achievement(CLEAN_POST)
        val eventIds = worked.map { it.p.eventId }.toSet()
        val withChecklist = ChecklistItems.selectAll().where { ChecklistItems.eventId inList eventIds }
            .map { it[ChecklistItems.eventId] }.toSet()
        val completions = ChecklistCompletionsT.selectAll().where { ChecklistCompletionsT.eventId inList eventIds }
            .groupBy({ it[ChecklistCompletionsT.eventId] to it[ChecklistCompletionsT.puestoId] }, { it[ChecklistCompletionsT.day] })
            .mapValues { it.value.toSet() }
        val clean = worked.filter { w ->
            w.p.eventId in withChecklist && run {
                val done = completions[w.p.eventId to w.p.positionId].orEmpty()
                generateSequence(java.time.LocalDate.parse(w.start.toString())) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(java.time.LocalDate.parse(w.end.toString())) }
                    .all { it.toString() in done }
            }
        }
        return counter(CLEAN_POST, clean.size, clean.firstOrNull()?.end)
    }

    /** Reclutador: oficiales que invitaste y ya tienen su cuenta activa. */
    private fun recruiter(officerId: String): Achievement {
        val invites = Invitations.selectAll().where { Invitations.inviterId eq officerId }.toList()
        if (invites.isEmpty()) return Achievement(RECRUITER)
        val active = Accounts.selectAll()
            .where { (Accounts.email inList invites.map { it[Invitations.inviteeEmail] }) and (Accounts.status eq AccountStatus.ACTIVE.name) }
            .map { it[Accounts.email] }.toSet()
        val ok = invites.filter { it[Invitations.inviteeEmail] in active }
        return counter(RECRUITER, ok.size, ok.minOfOrNull { it[Invitations.createdAt] }?.let(::dayOf))
    }

    /** Primeras veces: la fecha de la primera de cada cosa (una sola vez, nada se cuenta dos veces). */
    private fun firsts(officerId: String): List<Achievement> {
        fun firstMessage(photo: Boolean): LocalDate? =
            MessagesT.selectAll()
                .where {
                    val base = (MessagesT.senderId eq officerId) and (MessagesT.system eq false)
                    if (photo) base and MessagesT.mediaType.isNotNull() else base
                }
                .orderBy(MessagesT.at to SortOrder.ASC).limit(1)
                .firstOrNull()?.get(MessagesT.at)?.let(::dayOf)
        val trips = TripItemsT.selectAll().where { TripItemsT.officerId eq officerId }.toList()
        fun firstTrip(vararg kinds: TripItemKind): LocalDate? =
            trips.filter { it[TripItemsT.kind] in kinds.map { k -> k.name } }
                .mapNotNull { createdOn(it[TripItemsT.id]) ?: it[TripItemsT.at]?.let(::dayOf) }
                .minOrNull()
        return listOf(
            milestone(FIRST_MESSAGE, firstMessage(photo = false)),
            milestone(FIRST_CHAT_PHOTO, firstMessage(photo = true)),
            milestone(FIRST_LOG_PHOTO, firstTrip(TripItemKind.PHOTO)),
            milestone(FIRST_LOG_NOTE, firstTrip(TripItemKind.NOTE)),
            milestone(FIRST_TRIP, firstTrip(TripItemKind.TRANSPORT, TripItemKind.LODGING)),
            milestone(FIRST_REMINDER, firstTrip(TripItemKind.REMINDER)),
        )
    }

    /** Día (CDMX) de un Instant ISO; null si no se puede leer. */
    private fun dayOf(iso: String): LocalDate? = runCatching {
        LocalDate.parse(java.time.Instant.parse(iso).atZone(ZONA).toLocalDate().toString())
    }.getOrNull()

    /** Día de creación de un id UUIDv7 (los genera el servidor: su prefijo es el instante). */
    private fun createdOn(id: String): LocalDate? {
        val hex = id.replace("-", "")
        if (hex.length != 32 || hex[12] != '7') return null
        val ms = hex.substring(0, 12).toLongOrNull(16) ?: return null
        return LocalDate.parse(java.time.Instant.ofEpochMilli(ms).atZone(ZONA).toLocalDate().toString())
    }
}
