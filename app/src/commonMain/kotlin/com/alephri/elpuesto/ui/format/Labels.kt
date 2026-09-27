package com.alephri.elpuesto.ui.format

import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AssetType
import com.alephri.elpuesto.model.Area
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.EventStatus
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.model.TripItemKind
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes

/** Nombre del tipo de activo en pista, como lo dicen los oficiales. */
fun AssetType.display(): String = when (this) {
    AssetType.HIAB -> "HIAB"
    AssetType.AMBULANCIA -> "Ambulancia"
    AssetType.IFRT -> "IFRT"
    AssetType.TELEHANDLER -> "Telehandler"
    AssetType.TRACK_SWEEPER -> "Track Sweeper"
    AssetType.SAFETY_CAR -> "Safety Car"
    AssetType.DRIVER_RIDER -> "Driver Rider"
}

fun Area.display(): String = when (this) {
    Area.INTERVENCION -> "Intervención"
    Area.COMUNICACION -> "Comunicación"
    Area.RECOVERY -> "Recovery"
    Area.ESCRUTINIO -> "Escrutinio"
    Area.MEDICO -> "Médico"
}

/**
 * Minutos que le RESTAN a una actividad en curso, por reloj (endsInMin es la DURACIÓN
 * capturada por el admin; el fin = inicio + duración). null = sin duración conocida.
 */
fun Session.remainingMin(): Int? {
    val dur = endsInMin ?: return null
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val end = kotlinx.datetime.LocalDateTime(day, time).toInstant(tz) + dur.minutes
    return (end.toEpochMilliseconds() - com.alephri.elpuesto.data.nowMs()).coerceAtLeast(0).div(60_000).toInt()
}

fun Session.stateLabel(): String = when (status) {
    EventStatus.FINISHED -> "Terminada"
    EventStatus.LIVE -> "En curso" + (remainingMin()?.let { " · termina en $it min" } ?: "")
    EventStatus.UPCOMING -> "Próxima"
}

fun AgendaKind.display(): String = when (this) {
    AgendaKind.EVENT -> "Evento"
    AgendaKind.CONVOCATORIA -> "Convocatoria"
    AgendaKind.TRIP -> "Viaje"
    AgendaKind.REMINDER -> "Recordatorio"
}

fun TripItemKind.display(): String = when (this) {
    TripItemKind.TRANSPORT -> "Transporte"
    TripItemKind.LODGING -> "Hospedaje"
    TripItemKind.REMINDER -> "Recordatorio"
    TripItemKind.PHOTO -> "Foto"
    TripItemKind.NOTE -> "Nota"
}

/**
 * Etiqueta corta de una entrada para las listas: "Trabajas" (evento con asignación), el
 * nombre del campeonato de una carrera del calendario TAL CUAL se capturó en el admin, o el
 * tipo específico.
 */
fun AgendaEntry.kindDisplay(): String = when {
    kind == AgendaKind.EVENT && assigned -> "Trabajas"
    kind == AgendaKind.EVENT && championshipName != null -> championshipName!!
    else -> tripKind?.display() ?: kind.display()
}

/** true = fin de semana del calendario (carrera que el oficial no trabaja). */
val AgendaEntry.isRace: Boolean get() = kind == AgendaKind.EVENT && eventId == null && roundId != null

/** Título de la barra superior del detalle: el TIPO de la entrada (sin chip redundante). */
fun AgendaEntry.typeTitle(): String = when {
    kind == AgendaKind.EVENT && isRace -> "CARRERA"
    kind == AgendaKind.EVENT -> "EVENTO"
    else -> (tripKind?.display() ?: kind.display()).uppercase()
}

/**
 * Nombre corto de una serie para etiquetas: siglas entre paréntesis ("Mundial de Rally
 * (WRC)" → WRC), "Fórmula N" → "FN", sin la temporada ("Fórmula E 2025-26" → FE).
 */
fun seriesTag(name: String): String {
    Regex("""\(([^)]{2,6})\)""").find(name)?.let { return it.groupValues[1] }
    val base = name.replace(Regex("""\s+\d{4}(-\d{2})?$"""), "").trim()
    Regex("""^F[óo]rmula\s+(\S+)$""", RegexOption.IGNORE_CASE).find(base)?.let { return "F" + it.groupValues[1] }
    return base
}

/** Nombre corto de una categoría para la tira de días ("Craftsman Truck Series" → Truck). */
fun categoryShort(name: String): String {
    val known = mapOf(
        "craftsman truck series" to "Truck", "cup series" to "Cup", "o'reilly auto parts series" to "O'Reilly",
        "f1 academy" to "F1A", "indycar series" to "IndyCar", "indy nxt" to "NXT", "junior wrc" to "JWRC",
    )
    known[name.trim().lowercase()]?.let { return it }
    val tag = seriesTag(name)
    return if (tag.length <= 9) tag else tag.split(" ").first()
}

private val MX = kotlinx.datetime.TimeZone.of("America/Mexico_City")

/** Fecha relativa a hoy ("hoy", "mañana", "dentro de 8 días"…); null si no hay fecha. */
fun AgendaEntry.relativeDateLabel(): String? {
    val at = this.at ?: return null
    val today = kotlinx.datetime.Clock.System.now().toLocalDateTime(MX).date
    val days = today.daysUntil(at.toLocalDateTime(MX).date)
    return when {
        days == 0 -> "hoy"
        days == 1 -> "mañana"
        days > 1 -> "dentro de $days días"
        days == -1 -> "ayer"
        else -> "hace ${-days} días"
    }
}

/** Iniciales (hasta 2) para avatares placeholder. */
fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .map { it.first().uppercaseChar() }.joinToString("")

/**
 * Insignia de un campeonato (sin logos oficiales: son marcas registradas): las siglas entre
 * paréntesis si las hay ("Mundial de Rally (WRC)" → WRC), las mayúsculas de una sola
 * palabra ("IndyCar" → IC) o las iniciales ("Fórmula 1" → F1).
 */
fun championshipBadge(name: String): String {
    Regex("""\(([^)]{2,5})\)""").find(name)?.let { return it.groupValues[1] }
    val words = name.trim().split(" ").filter { it.isNotBlank() }
    if (words.size == 1) {
        val caps = words[0].filter { it.isUpperCase() }
        if (caps.length in 2..3 && caps.length < words[0].length) return caps
    }
    return initials(name)
}

