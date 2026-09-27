package com.alephri.elpuesto.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * CAMPEONATO (la serie: Fórmula 1, NASCAR, Fórmula E…). Agrupa sus temporadas y es dueño
 * del logo. En el API se llama `series`; en la UI, "Campeonato". Solo lectura en la app.
 */
@Serializable
data class Series(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val emblemUrl: String? = null,
)

/**
 * TEMPORADA de un campeonato (en el API se llama `championship`: "the 2026 championship").
 * Tiene varias categorías; de la categoría dependen posiciones, calendario y pilotos. Se
 * alimenta por ingesta y/o captura del admin; solo lectura en la app.
 *
 * [name] y [emblemUrl] se DERIVAN del campeonato ([seriesId]) al leer. [seasonLabel] es la
 * etiqueta visible ("2026", "2025-26") y [season] el año para ordenar (el de cierre).
 */
@Serializable
data class Championship(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val season: Int = 0,
    @ProtoNumber(4) val emblemUrl: String? = null,
    @ProtoNumber(5) val seriesId: String = "",
    @ProtoNumber(6) val seasonLabel: String = "",
    /** Derivados del calendario al leer: primer y último día de la temporada. */
    @ProtoNumber(7) val startsOn: LocalDate? = null,
    @ProtoNumber(8) val endsOn: LocalDate? = null,
    // —— Derivados al leer del calendario de su categoría PRINCIPAL (la primera) ——
    /** Fechas de la temporada y cuántas ya terminaron. */
    @ProtoNumber(9) val roundsTotal: Int = 0,
    @ProtoNumber(10) val roundsDone: Int = 0,
    /** La siguiente fecha (en curso o próxima); null = temporada terminada o sin calendario. */
    @ProtoNumber(11) val nextRound: Round? = null,
    /** Nombres de sus categorías, en orden. */
    @ProtoNumber(12) val categoryNames: List<String> = emptyList(),
)

@Serializable
data class Category(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val championshipId: String,
    @ProtoNumber(3) val name: String,
    // —— Derivados AL LEER de la ingesta automática de posiciones (no se capturan) ——
    /** Crédito de la fuente de las posiciones ("Jolpica · CC BY-NC-SA"); null = captura manual. */
    @ProtoNumber(4) val standingsCredit: String? = null,
    /** Fecha del calendario hasta la que llegan las posiciones; null = no se sabe. */
    @ProtoNumber(5) val standingsThroughRound: Int? = null,
)

/**
 * Fila de la tabla de posiciones. Referencia al piloto por [driverRef] (llave estable en
 * la categoría); número, nombre y equipo se resuelven del piloto al leer. [driverNumber]
 * es el número legado (0 si no es numérico o no hay): mostrar [numberText].
 */
@Serializable
data class Standing(
    @ProtoNumber(1) val pos: Int,
    @ProtoNumber(2) val driverNumber: Int = 0,
    @ProtoNumber(3) val driverName: String = "",
    @ProtoNumber(4) val team: String = "",
    @ProtoNumber(5) val points: Int,
    /** Número del coche tal cual ("007", "00", "12"); null = sin número. */
    @ProtoNumber(6) val numberText: String? = null,
    @ProtoNumber(7) val driverRef: String? = null,
    /** Foto del piloto (`/images/driver/{id}/full`; `thumb` = la cara). Derivada al leer; null = sin foto. */
    @ProtoNumber(8) val photoUrl: String? = null,
)

/**
 * Fecha del calendario de una categoría. `status` se deriva de la fecha AL LEER (no se
 * captura); `winner` ya no se captura (compatibilidad de lectura). La sede es un circuito
 * del catálogo (`circuitId`; `circuitName` se deriva de él) o, en eventos sin circuito
 * como los rallies, el texto `location` ("Ciudad, País").
 */
@Serializable
data class Round(
    @ProtoNumber(1) val number: Int,
    @ProtoNumber(2) val date: LocalDate, // día de la carrera principal (rally: último día)
    @ProtoNumber(3) val circuitName: String = "",
    @ProtoNumber(4) val status: EventStatus = EventStatus.UPCOMING,
    @ProtoNumber(5) val winner: String? = null,
    @ProtoNumber(6) val circuitId: String? = null,
    @ProtoNumber(7) val name: String? = null, // nombre del evento ("Monaco Grand Prix")
    @ProtoNumber(8) val location: String? = null, // "Ciudad, País" (del circuito o del rally)
    @ProtoNumber(9) val startDate: LocalDate? = null, // primer día del fin de semana, si se conoce
    /**
     * Id estable asignado por el backend. Sobrevive a los reemplazos del calendario (se
     * conserva por sede + nombre, o sede + fecha cercana) porque la planeación de los
     * oficiales se liga a él. Al capturar es opcional: mandarlo fuerza la conservación.
     */
    @ProtoNumber(10) val id: String? = null,
)

/**
 * Piloto de una categoría. La llave es [ref] (estable: el id de la fuente de ingesta o,
 * capturado a mano, el número/nombre); el número NO sirve de llave — se comparte entre
 * sustitutos, "007" ≠ "7" y en rally cambia cada fecha. [number] es el legado numérico.
 */
@Serializable
data class Driver(
    @ProtoNumber(1) val number: Int = 0,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val team: String = "",
    /** Número del coche tal cual ("007", "00"); null = sin número. */
    @ProtoNumber(4) val numberText: String? = null,
    @ProtoNumber(5) val ref: String? = null,
)
