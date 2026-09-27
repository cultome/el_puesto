package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Standing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * NTT IndyCar Series e Indy NXT con los JSON públicos de indycar.com / indynxt.com: los mismos
 * que descarga su página de Resultados (sin llave ni reto; Azure Front Door, `no-store`).
 * Decisión 2026-09-27. Parámetro = serie (`indycar` | `indynxt`), que aquí se traduce a su host
 * y al GUID de la serie en su API (los dos hosts sirven lo mismo). Por corrida:
 *
 * 1. `YearPointSummary?year=&id={GUID}` → la tabla: `OverallPosition` (ya DESEMPATADA: hay pilotos
 *    empatados en puntos) y `TotalPoints` (enteros), más los puntos de cada piloto por carrera.
 *    Trae desde antes de la temporada UNA COLUMNA POR CARRERA del año (también las futuras); la
 *    llave de la columna es `EventsSessionsID`, nunca el índice ni `Track` (las dos de Milwaukee se
 *    llaman "MIL" y el sitio las pone en orden inverso al nuestro, las dos el 30/8). `UpdatedDate`
 *    es la fecha de la petición: no sirve. Temporada sin empezar = `DriverList` vacío.
 * 2. Fecha reflejada: una carrera lo está cuando su columna suma > 0 (el ganador se lleva ≥ 50) y
 *    deben ser las primeras k. Además cada piloto debe sumar su total (si no, la tabla está a
 *    medias) y los puntos de la columna pedida deben ser los `PointsEarned` del resultado de esa
 *    carrera (así se detecta la tabla aún sin actualizar; incluye los puntos de clasificación de
 *    la Indy 500). Su `SessionDate` debe caer a ±1 día de nuestro día de carrera.
 * 3. `DriversByYear` → `DriverOverrideID` por `OverallPosition` (= `ref`: hay números compartidos,
 *    en Indy NXT 2026 el #15, #17, #48 y #76 los usaron dos pilotos).
 * 4. `EventsSessionDetails?id=` de cada carrera, de la pedida hacia atrás hasta cubrir a todos:
 *    número (`CarNumber` es TEXTO: "06" es Castroneves y "6" Siegel) y equipo de su aparición MÁS
 *    RECIENTE. Los de solo la Indy 500 obligan a llegar a la fecha 7 la primera vez (~12 carreras);
 *    lo de carreras ya vistas se queda en memoria del proceso (número y equipo no cambian), la
 *    pedida siempre se vuelve a leer (sus puntos sí se corrigen: la Indy 500 cambió 2 días después).
 * 5. La página `/standings/{año}` (HTML hecho en el servidor): el equipo con su nombre CORTO (el
 *    `alt` del logo: "Meyer Shank Racing" y no la inscripción "Meyer Shank w/ Curb-Agajanian") y la
 *    foto del piloto (`driverPortraitImg`: medio cuerpo; en IndyCar PNG transparente, en Indy NXT
 *    JPG con fondo blanco). Se empata por nombre; el que no aparezca se queda con el equipo de su
 *    inscripción y sin foto. Si la página falla, error: el job reintenta (guardar la tabla con los
 *    nombres largos la dejaría así hasta la confirmación).
 *
 * El sitio refleja una carrera unas 3 h después de la bandera y la corrige días después (la
 * pasada de confirmación del job lo cubre). Solo publica la tabla VIGENTE. Sus términos prohíben
 * el scraping: el volumen es mínimo (≤ 1 petición por segundo, unas cuantas por fin de semana).
 */
object IndyCarFeeds : StandingsSource {
    override val id = "indycar-feed"
    override val credit = "indycar.com"
    override val description = "IndyCar e Indy NXT con los JSON públicos de indycar.com / indynxt.com (los que usa su página de Resultados), más su página de posiciones para el nombre corto del equipo y la foto. La temporada es el año."
    override val paramHint = "serie: indycar o indynxt"

    private class Series(val host: String, val guid: String, val name: String)

    private val SERIES = mapOf(
        "indycar" to Series("www.indycar.com", "b856a4f1-e85c-4fac-8c36-fd58d962227a", "IndyCar"),
        "indynxt" to Series("www.indynxt.com", "09341e09-3216-4f89-a45f-db697d72ee13", "Indy NXT"),
    )

    /** Inscripción de un piloto en una carrera: número (texto) y equipo. */
    private data class Entry(val number: String?, val team: String)

    /** Una carrera: sus puntos y su inscripción por `DriverOverrideID`. */
    private class Race(val name: String, val date: LocalDate?, val type: String?, val points: Map<Int, Int>, val entries: Map<Int, Entry>)

    /** "host|EventsSessionsID" → inscripciones de esa carrera (no cambian: en memoria del proceso). */
    private val entriesByRace = ConcurrentHashMap<String, Map<Int, Entry>>()

    /** Una fila de la tabla. */
    private class Row(val pos: Int, val name: String, val total: Int, val byRace: Map<Int, Int>)

    /** Lo que aporta la página de posiciones: equipo corto y foto. */
    private class PageRow(val team: String?, val photo: String?)

    private val SESSION_DATE = DateTimeFormatter.ofPattern("M/d/yyyy")
    private val PORTRAIT = Regex("^/-/media/IndyCar/Drivers/[A-Za-z0-9/_-]+\\.(?:png|jpe?g)$")

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val s = ctx.param?.trim()?.lowercase()?.let { SERIES[it] } ?: error("el parámetro debe ser la serie: indycar o indynxt")
        val api = "https://${s.host}/api/results"

        val summary = SourceHttp.getJson("$api/YearPointSummary?year=${ctx.season}&id=${s.guid}")
        val year = summary.int("Year")
        if (year != ctx.season) error("${s.host} devolvió la temporada $year y no la ${ctx.season}")
        val list = summary.arr("DriverList")
        if (list.isEmpty()) return SourceResult.NotReady("${s.host} aún no publica la tabla de ${s.name} ${ctx.season}")

        val cols = summary.arr("RaceAbbreviations").filter { it.str("Track") != "Total" }
            .map { it.int("EventsSessionsID")?.takeIf { id -> id > 0 } ?: error("${s.host}: una columna de carrera no trae su id") }
        if (cols.size != ctx.rounds) error("${s.host} tiene ${cols.size} carreras de ${s.name} y nuestro calendario ${ctx.rounds}: revisa el calendario antes de seguir")
        if (cols.toSet().size != cols.size) error("${s.host} repite una carrera en la tabla")
        if (round !in 1..cols.size) error("${s.host} no tiene la fecha $round")

        val table = list.mapIndexed { i, r ->
            Row(
                pos = r.int("OverallPosition")?.takeIf { it > 0 } ?: error("${s.host}: la fila ${i + 1} no trae posición"),
                name = r.str("DriverName")?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null } ?: error("${s.host}: la fila ${i + 1} no trae piloto"),
                total = r.int("TotalPoints") ?: error("${s.host}: la fila ${i + 1} no trae puntos"),
                byRace = r.arr("Points").mapNotNull { p -> p.int("EventsSessionsID")?.takeIf { it > 0 }?.let { it to (p.int("Points") ?: 0) } }.toMap(),
            )
        }.sortedBy { it.pos }
        table.groupBy { it.pos }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }
            ?.let { error("${s.host} repite posiciones: ${it.joinToString()}") }
        table.firstOrNull { it.byRace.values.sum() != it.total }
            ?.let { return SourceResult.NotReady("la tabla de ${s.host} está a medias: ${it.name} no suma su total (se reintenta)") }

        val reflected = cols.map { c -> table.sumOf { it.byRace[c] ?: 0 } > 0 }
        val k = reflected.takeWhile { it }.size
        if (reflected.drop(k).any { it }) {
            error("las carreras con puntos en ${s.host} no son las primeras $k de su calendario (${reflected.joinToString("") { if (it) "x" else "-" }}): revisa el calendario")
        }
        if (k < round) return SourceResult.NotReady("${s.host} aún no refleja la fecha $round (lleva $k de ${cols.size} carreras)")
        // El sitio solo publica la tabla VIGENTE: no sirve para etiquetar una fecha anterior.
        if (k > round) return aheadOf(credit, round)

        delay(1_000) // cortesía con el sitio: una petición por segundo
        val byPos = SourceHttp.getJson("$api/DriversByYear?year=${ctx.season}&id=${s.guid}").jsonArray
            .mapNotNull { b -> b.int("OverallPosition")?.let { it to b } }.toMap()
        val refs = table.map { row ->
            val b = byPos[row.pos] ?: return SourceResult.NotReady("${s.host}: la lista de pilotos aún no trae la posición ${row.pos} (se reintenta)")
            val name = "${b.str("FirstName").orEmpty()} ${b.str("LastName").orEmpty()}"
            if (asciiKey(name) != asciiKey(row.name)) {
                return SourceResult.NotReady("${s.host}: la lista de pilotos no coincide con la tabla en la posición ${row.pos} ($name / ${row.name}; se reintenta)")
            }
            b.int("DriverOverrideID")?.takeIf { it > 0 } ?: error("${s.host}: ${row.name} no trae id")
        }

        // La carrera pedida siempre fresca: sus puntos se corrigen días después.
        val targetId = cols[round - 1]
        delay(1_000)
        val target = race(s.host, targetId)
        if (target.type != "R") error("la columna $round de ${s.host} no es una carrera («${target.name}», tipo ${target.type})")
        if (target.date == null || target.date !in raceDay.minusDays(1)..raceDay.plusDays(1)) {
            error("la columna $round de ${s.host} es «${target.name}» del ${target.date}; nuestra fecha $round es el $raceDay: revisa el calendario")
        }
        val differ = table.indices.count { i -> (table[i].byRace[targetId] ?: 0) != (target.points[refs[i]] ?: 0) } +
            target.points.count { (id, pts) -> pts != 0 && id !in refs }
        if (differ > 0) return SourceResult.NotReady("la tabla de ${s.host} aún no refleja «${target.name}» ($differ pilotos con otros puntos que el resultado; se reintenta)")

        val found = latestEntries(s.host, cols, round - 1, target, refs.toSet())
        val page = standingsPage(s, ctx.season)
        val drivers = table.mapIndexed { i, row ->
            val seen = found[refs[i]]
            Driver(name = row.name, team = page[asciiKey(row.name)]?.team ?: seen?.team.orEmpty(), numberText = seen?.number, ref = refs[i].toString())
        }
        val standings = table.mapIndexed { i, row -> Standing(pos = row.pos, points = row.total, driverRef = refs[i].toString()) }
        val photos = table.indices.mapNotNull { i ->
            page[asciiKey(table[i].name)]?.photo?.let { refs[i].toString() to DriverPhoto(full = "https://${s.host}$it") }
        }.toMap()
        return SourceResult.Ready(round, drivers, standings, photos)
    }

    /** Resultado de una carrera; guarda sus inscripciones para no volver a pedirlas. */
    private suspend fun race(host: String, sessionId: Int): Race {
        require(sessionId > 0) { "id de carrera inesperado: $sessionId" } // va en la URL
        val j = SourceHttp.getJson("https://$host/api/results/EventsSessionDetails?id=$sessionId")
        val records = j.arr("records").filter { r -> (r.jsonObject["IsDeleted"] as? JsonPrimitive)?.booleanOrNull != true }
        val points = mutableMapOf<Int, Int>()
        val entries = mutableMapOf<Int, Entry>()
        for (r in records) {
            val id = r.int("DriverOverrideID")?.takeIf { it > 0 } ?: continue
            if (id in entries) continue
            points[id] = r.int("PointsEarned") ?: 0
            entries[id] = Entry(
                number = r.str("CarNumber")?.trim()?.takeIf { it.isNotEmpty() && it != "--" },
                team = r.str("TeamName")?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
            )
        }
        if (entries.isNotEmpty()) {
            if (entriesByRace.size > 500) entriesByRace.clear() // años de carreras: se vuelven a pedir si hacen falta
            entriesByRace["$host|$sessionId"] = entries
        }
        return Race(
            name = j.str("EventName")?.trim() ?: "carrera $sessionId",
            date = j.str("SessionDate")?.let { runCatching { LocalDate.parse(it.trim(), SESSION_DATE) }.getOrNull() },
            type = j.str("SessionType"),
            points = points,
            entries = entries,
        )
    }

    /** id del piloto → su inscripción más reciente, de la columna [from] (la pedida) hacia atrás. */
    private suspend fun latestEntries(host: String, cols: List<Int>, from: Int, target: Race, refs: Set<Int>): Map<Int, Entry> {
        val found = mutableMapOf<Int, Entry>()
        for (i in from downTo 0) {
            if (found.keys.containsAll(refs)) break
            val entries = if (i == from) target.entries else entriesByRace["$host|${cols[i]}"] ?: run {
                delay(1_000) // cortesía con el sitio: una petición por segundo
                race(host, cols[i]).entries
            }
            for ((id, e) in entries) if (id in refs && id !in found) found[id] = e
        }
        return found
    }

    /**
     * La página `/standings/{año}`: nombre (asciiKey) → equipo corto y foto. Cada fila trae un
     * `data-driver-data` con JSON (nombre, foto) y el logo del equipo con su nombre en el `alt`
     * ("Chip Ganassi Racing Logo "). Los nombres del JSON vienen escapados dos veces ("O&apos;Ward").
     */
    private suspend fun standingsPage(s: Series, season: Int): Map<String, PageRow> {
        delay(1_000)
        val doc = Jsoup.parse(SourceHttp.getText("https://${s.host}/standings/$season"), "https://${s.host}")
        val rows = doc.select("tr.data-table-driver-row")
        if (rows.isEmpty()) error("${s.host} cambió su página de posiciones: no se encontró la tabla de pilotos")
        val out = mutableMapOf<String, PageRow?>()
        for (tr in rows) {
            val data = tr.selectFirst("[data-driver-data]")?.attr("data-driver-data")
                ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: continue
            val name = listOf("firstName", "lastName").joinToString(" ") { Parser.unescapeEntities(data.str(it).orEmpty(), false) }
            val key = asciiKey(name)
            if (key.isEmpty()) continue
            val team = tr.selectFirst(".data-table-team-img-container img[alt]")?.attr("alt")
                ?.trim()?.removeSuffix("Logo")?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }
            val photo = data.str("driverPortraitImg")?.trim()?.takeIf { PORTRAIT.matches(it) }
            // Un nombre repetido no se puede empatar: ninguno de los dos toma nada de la página.
            out[key] = if (key in out) null else PageRow(team, photo)
        }
        return out.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /** El entero de [key] (null si falta o viene `null`); error si trae otra cosa. */
    private fun JsonElement.int(key: String): Int? {
        val p = (jsonObject[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        return p.intOrNull ?: p.doubleOrNull?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) < 1e9 }?.toInt()
            ?: error("«$key» no es un número entero: ${p.content.take(20)}")
    }
}
