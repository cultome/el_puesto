package com.alephri.elpuesto.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Logros de un oficial (gamificación, 2026-09-25; diseño en `docs/design/Logros/`). TODO
 * se DERIVA en el backend al leer — asignaciones de eventos terminados, pase de lista,
 * checklist, bitácora, mensajes e invitaciones —; nada se captura. Sin rankings ni
 * comparaciones entre oficiales. De OTRO oficial el backend solo manda lo público
 * ([AchievementRules.isPublic]): pasaporte, campeonatos y carrera en pista.
 */
@Serializable
data class Achievements(
    /** Circuitos donde trabajó (todos los países), el más antiguo primero. */
    @ProtoNumber(1) val stamps: List<PassportStamp> = emptyList(),
    /** Campeonatos (series) de los eventos que trabajó, con cuántas temporadas. */
    @ProtoNumber(2) val patches: List<SeriesPatch> = emptyList(),
    /** Logros con niveles, hitos, contadores y primeras veces (claves en [AchievementRules]). */
    @ProtoNumber(3) val items: List<Achievement> = emptyList(),
)

/**
 * Sello del pasaporte: un circuito trabajado (sí/no; [events] para el detalle). En el
 * pasaporte de OTRO oficial el servidor solo manda el año ([firstOn] = 1 de enero de ese
 * año) y omite [lastOn]/[lastEventName]: su historial no se lee por aquí.
 */
@Serializable
data class PassportStamp(
    @ProtoNumber(1) val circuitId: String,
    @ProtoNumber(2) val country: String? = null,
    /** Inicio del primer evento trabajado ahí. */
    @ProtoNumber(3) val firstOn: LocalDate,
    @ProtoNumber(4) val events: Int = 1,
    /** Inicio del evento más reciente trabajado ahí. */
    @ProtoNumber(5) val lastOn: LocalDate? = null,
    @ProtoNumber(6) val lastEventName: String? = null,
)

/** Parche de campeonato (serie): trabajaste al menos un evento de alguna de sus temporadas. */
@Serializable
data class SeriesPatch(
    @ProtoNumber(1) val seriesId: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val seasons: Int,
    @ProtoNumber(4) val emblemUrl: String? = null,
    @ProtoNumber(5) val firstOn: LocalDate? = null,
)

@Serializable
data class Achievement(
    @ProtoNumber(1) val key: String,
    /** Progreso: circuitos, días, tipos de rol, años, veces… (1 = hecho, en hitos). */
    @ProtoNumber(2) val value: Int = 0,
    /** Nivel alcanzado: 0 = sin ganar; 1..3 = bronce/plata/oro (hitos y contadores: 1). */
    @ProtoNumber(3) val tier: Int = 0,
    /** Cuándo se alcanzó cada nivel (índice 0 = el primero); vacío = sin fecha conocida. */
    @ProtoNumber(4) val reachedOn: List<LocalDate> = emptyList(),
    /** Contexto del logro (p. ej. el rol del Ascenso). */
    @ProtoNumber(5) val detail: String? = null,
    /** Desglose por año (p. ej. días en pista por temporada), el más reciente primero. */
    @ProtoNumber(6) val byYear: List<YearValue> = emptyList(),
)

@Serializable
data class YearValue(
    @ProtoNumber(1) val year: Int,
    @ProtoNumber(2) val value: Int,
)

/**
 * Reglas ÚNICAS de los logros: el backend calcula con ellas y la app las usa para mostrar
 * "Plata a los 50 · te faltan 3". Cambiar un umbral aquí cambia ambos lados.
 */
object AchievementRules {
    // Pasaporte
    const val PASSPORT_MX = "passport_mx"
    const val INTERNATIONAL = "international"
    // Carrera en pista
    const val TRACK_DAYS = "track_days"
    const val VERSATILITY = "versatility"
    const val PROMOTION = "promotion"
    const val SENIORITY = "seniority"
    const val GRAND_PRIX = "grand_prix"
    // Operación (solo el titular)
    const val READY = "ready"
    const val CLEAN_POST = "clean_post"
    const val RECRUITER = "recruiter"
    // Primeras veces (solo el titular). Sin ubicación ni checklist a propósito: no se
    // premia prender el GPS ni palomear por palomear (el checklist cuenta en equipo).
    const val FIRST_MESSAGE = "first_message"
    const val FIRST_CHAT_PHOTO = "first_chat_photo"
    const val FIRST_LOG_PHOTO = "first_log_photo"
    const val FIRST_LOG_NOTE = "first_log_note"
    const val FIRST_TRIP = "first_trip"
    const val FIRST_REMINDER = "first_reminder"

    /** País "de casa" del pasaporte (el resto cuenta para Internacional). */
    const val HOME_COUNTRY = "México"

    /** Umbrales de bronce, plata y oro de los logros con niveles (decisión 2026-09-25). */
    val THRESHOLDS: Map<String, List<Int>> = mapOf(
        PASSPORT_MX to listOf(3, 5, 8),
        TRACK_DAYS to listOf(10, 50, 100),
        VERSATILITY to listOf(2, 4, 6),
        SENIORITY to listOf(5, 10, 20),
    )

    val PASSPORT = listOf(PASSPORT_MX, INTERNATIONAL)
    val CAREER = listOf(TRACK_DAYS, VERSATILITY, PROMOTION, SENIORITY, GRAND_PRIX)
    val OPERATION = listOf(READY, CLEAN_POST, RECRUITER)
    val FIRSTS = listOf(FIRST_MESSAGE, FIRST_CHAT_PHOTO, FIRST_LOG_PHOTO, FIRST_LOG_NOTE, FIRST_TRIP, FIRST_REMINDER)

    /** Visibles para otros oficiales: los logros de pista. Operación y primeras veces, solo el titular. */
    fun isPublic(key: String): Boolean = key in PASSPORT || key in CAREER

    /** Nivel para [value]: con umbrales, cuántos alcanza; sin umbrales (hito/contador), 1 si hay algo. */
    fun tierFor(key: String, value: Int): Int =
        THRESHOLDS[key]?.count { value >= it } ?: if (value > 0) 1 else 0

    /** Familias de rol ("tipos") de Versatilidad, en orden de presentación (decisión 2026-09-25). */
    val ROLE_FAMILIES = listOf("Banderas", "Intervención", "Bomberos", "Comunicación", "Unidades", "Jefatura")

    /** Familia de un rol operativo de asignación; null = rol fuera de catálogo (no cuenta). */
    fun roleFamily(role: String): String? = when {
        role.startsWith("Bandera") || role == "Panel de luz" || role == "Track Safety Personnel" -> "Banderas"
        role.startsWith("Intervención") -> "Intervención"
        role.startsWith("Bombero") -> "Bomberos"
        role == "Comunicador" -> "Comunicación"
        role.startsWith("Operador") || role == "Driver Rider" -> "Unidades"
        role == "Chief Post Marshal" || role.startsWith("Jefe") || role == "Coordinador de zona" -> "Jefatura"
        else -> null
    }

    /** ¿La serie es la Fórmula 1? (cuenta para Grandes Premios). */
    fun isGrandPrixSeries(name: String): Boolean =
        name.trim().lowercase().replace("ó", "o") in setOf("f1", "formula 1", "formula one")
}
