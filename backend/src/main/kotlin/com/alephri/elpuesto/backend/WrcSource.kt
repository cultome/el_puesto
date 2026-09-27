package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Standing
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * WRC (WRC, WRC2, WRC3 y Junior WRC) con los JSON públicos que descarga la página de standings de
 * wrc.com (`/en/results-and-standings/championship-standings`): la página no trae la tabla, la
 * pide un script con `fetch()` pelón —sin llave ni reto— a los JSON de `p-p.redbull.com/…/api/`.
 * www.wrc.com (Akamai) no hace falta. Decisión del usuario 2026-09-27 (mismo caso que NASCAR).
 * Parámetro = categoría: `wrc | wrc2 | wrc3 | jwrc`.
 *
 * - Temporada: `seasons.json` por nombre ("World Rally Championship") y AÑO. Los ids de las
 *   tablas cambian cada temporada: se buscan por el NOMBRE exacto del campeonato de pilotos en
 *   `season-detail.json`, que también trae los rallies con sus fechas.
 * - Calendario de la fuente ≠ el nuestro: sigue listando Arabia (cancelada) y las 14 rondas para
 *   TODAS las categorías (Junior WRC corrió 5). La fecha se coteja por DÍA (±1) contra
 *   `startDate..finishDate` del rally, nunca por número.
 * - Tabla: `championship-overall-results.json` (posición ya desempatada, puntos con Super Sunday y
 *   Power Stage, y resultado de cada rally) + `championship-detail.json` para los nombres (se unen
 *   por `championshipEntryId`). Los resultados de un rally solo aparecen cuando la fuente lo
 *   procesa: está reflejado si alguno no es `DidNotEnter`, todos están `Published` y el rally ya
 *   no está en `live-events.json`. En WRC además los puntos del rally deben sumar 130 (100 del
 *   rally + 15 del Super Sunday + 15 de la Power Stage): menos = resultados incompletos.
 * - Solo publica la tabla VIGENTE: si un rally posterior ya tiene resultados, [aheadOf].
 * - Filas con 0 puntos (inscritos que no sumaron: 68 de 126 en WRC2) se omiten.
 * - `ref` = `personId`: estable dentro de la temporada (no entre temporadas: Ogier 670 → 21334; y
 *   en las inscripciones a veces cambia a media temporada —Zaldivar—: se queda la de la tabla).
 * - Número y equipo NO vienen en la tabla: salen de la inscripción MÁS RECIENTE del piloto
 *   (`events/{id}.json` → rally principal → `entries.json`), recorriendo los rallies hacia atrás.
 *   En WRC2/WRC3/Junior el número cambia en cada rally y se repite entre pilotos. Las
 *   inscripciones de un rally ya corrido no cambian: se guardan en memoria del proceso (la primera
 *   corrida tras un despliegue pide 2 archivos por rally; después, ninguno).
 * - Equipo: en WRC el de la tabla (`fieldFive`, "ManufacturerFull"); en WRC2/WRC3/Junior el de la
 *   inscripción (en su tabla viene "None"). Si falta o es el propio nombre del piloto (así figuran
 *   los privados: "GUS GREENSMITH"), la marca.
 * - Nombres: el apellido viene en MAYÚSCULAS ("MCERLEAN" → "McErlean"); hay un piloto sin nombre
 *   de pila (" FLANDY").
 * - Fotos: `driverImageUrl` (img.redbull.com, el Cloudinary de Red Bull) solo de los de Rally1 y
 *   unos cuantos más; de ahí se pide el original y la cara recortada por su CDN (`g_face`).
 * - Los términos de wrc.com prohíben la extracción automatizada: se usa con el visto bueno del
 *   usuario, una categoría a la vez y a ≤ 1 petición por segundo.
 */
object WrcFeed : StandingsSource {
    override val id = "wrc-feed"
    override val credit = "wrc.com"
    override val description = "WRC (WRC, WRC2, WRC3 y Junior WRC) con los JSON públicos que descarga la página de standings de wrc.com. La temporada es el año."
    override val paramHint = "categoría: wrc, wrc2, wrc3 o jwrc"
    private const val BASE = "https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api"
    private const val SEASON = "World Rally Championship"
    /** Categoría (parámetro) → nombre EXACTO de su campeonato de pilotos en la fuente. */
    private val TABLES = mapOf(
        "wrc" to "FIA World Rally Championship for Drivers",
        "wrc2" to "FIA WRC2 Championship for Drivers",
        "wrc3" to "FIA WRC3 Championship for Drivers",
        "jwrc" to "FIA Junior WRC Championship for Drivers",
    )
    /** Lo que reparte un rally completo del WRC: 100 del rally + 15 del Super Sunday + 15 de la Power Stage. */
    private const val WRC_RALLY_POINTS = 130
    private const val IMAGES = "https://img.redbull.com/images/"
    private val PHOTO_ID = Regex("^redbullcom/[A-Za-z0-9/_-]+$")
    private val log = LoggerFactory.getLogger("StandingsIngest")

    private data class Rally(val order: Int, val eventId: Int, val name: String, val start: LocalDate, val finish: LocalDate)

    /** Una inscripción: número, equipo inscrito, marca, nombre (para reconocer a los privados) y foto. */
    private data class Entry(val personId: String, val number: String?, val entrant: String?, val maker: String?, val fullName: String?, val photo: String?)

    /** eventId → inscripciones de ese rally (ya corrido: no cambian), en memoria del proceso. */
    private val entriesByEvent = ConcurrentHashMap<Int, List<Entry>>()
    private val lastRequestAt = AtomicLong(0)
    private val requests = AtomicInteger(0)

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val cls = ctx.param?.trim()?.lowercase()?.takeIf { it in TABLES }
            ?: error("el parámetro debe ser la categoría: wrc, wrc2, wrc3 o jwrc")
        val table = TABLES.getValue(cls)
        val before = requests.get()
        val seasonId = get("/seasons.json").jsonArray
            .filter { it.str("name") == SEASON && it.str("year") == ctx.season.toString() }
            .map { id(it, "seasonId") }.singleOrNull()
            ?: error("wrc.com no tiene la temporada ${ctx.season} del WRC")
        val detail = get("/season-detail.json?seasonId=$seasonId")
        val cid = detail.arr("championships").filter { it.str("name") == table }.map { id(it, "championshipId") }.singleOrNull()
            ?: error("wrc.com cambió sus campeonatos: no está «$table» en ${ctx.season}")
        val rallies = detail.arr("seasonRounds").map { r ->
            val ev = r.obj("event")
            Rally(
                order = r.str("order")?.toIntOrNull() ?: error("wrc.com: un rally sin orden"),
                eventId = id(r, "eventId"),
                name = ev.str("name")?.trim().orEmpty().ifEmpty { "rally ${r.str("order")}" },
                start = LocalDate.parse(ev.str("startDate")),
                finish = LocalDate.parse(ev.str("finishDate")),
            )
        }.sortedBy { it.order }
        val target = rallies.filter { raceDay in it.start.minusDays(1)..it.finish.plusDays(1) }.singleOrNull()
            ?: error("wrc.com no tiene un rally que coincida con el $raceDay (fecha $round de nuestro calendario)")

        val all = get("/championship-overall-results.json?championshipId=$cid&seasonId=$seasonId").arr("entryResults")
        if (all.isEmpty()) return SourceResult.NotReady("wrc.com aún no publica «$table» ${ctx.season}")
        val byEvent = all.flatMap { it.arr("roundResults") }.groupBy { it.str("eventId").orEmpty() }
        fun ran(r: Rally) = byEvent[r.eventId.toString()].orEmpty().any { it.str("status") != "DidNotEnter" }
        if (!ran(target)) return SourceResult.NotReady("wrc.com aún no refleja «${target.name}» (fecha $round) en «$table»")
        // Solo publica la tabla VIGENTE: no sirve para etiquetar una fecha anterior.
        if (rallies.any { it.order > target.order && ran(it) }) return aheadOf(credit, round)
        val results = byEvent[target.eventId.toString()].orEmpty()
        results.firstOrNull { it.str("publishedStatus") != "Published" }?.let {
            return SourceResult.NotReady("«${target.name}» todavía no está publicado en wrc.com (${it.str("publishedStatus")}); se reintenta")
        }
        val live = get("/live-events.json") as? JsonArray ?: JsonArray(emptyList())
        if (live.any { e -> ((e as? JsonObject)?.str("eventId") ?: (e as? JsonPrimitive)?.content) == target.eventId.toString() }) {
            return SourceResult.NotReady("«${target.name}» sigue en vivo en wrc.com")
        }
        if (cls == "wrc") {
            val sum = results.sumOf { int(it, "totalPoints") ?: 0 }
            if (sum != WRC_RALLY_POINTS) return SourceResult.NotReady(
                "los puntos de «${target.name}» en wrc.com suman $sum de $WRC_RALLY_POINTS: resultados incompletos o provisionales " +
                    "(si el rally se acortó y repartió menos, hay que revisarlo a mano)",
            )
        }

        val rows = all.filter { (int(it, "overallPoints") ?: error("wrc.com: una fila sin puntos")) != 0 }
            .sortedBy { int(it, "overallPosition") ?: error("wrc.com: una fila sin posición") }
        if (rows.isEmpty()) return SourceResult.NotReady("wrc.com aún no tiene pilotos con puntos en «$table»")
        val positions = rows.map { int(it, "overallPosition")!! }
        positions.groupBy { it }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let { error("wrc.com repite posiciones: ${it.joinToString()}") }
        val points = rows.map { int(it, "overallPoints")!! }
        if (points.zipWithNext().any { (a, b) -> b > a }) error("la tabla de wrc.com no viene ordenada por puntos")

        val people = get("/championship-detail.json?championshipId=$cid&seasonId=$seasonId").arr("championshipEntries")
            .associateBy { it.str("championshipEntryId") }
        val persons = rows.mapIndexed { i, r -> people[r.str("championshipEntryId")] ?: error("wrc.com: la posición ${positions[i]} no tiene piloto en championship-detail") }
        val refs = persons.mapIndexed { i, p -> p.str("personId")?.toIntOrNull()?.toString() ?: error("wrc.com: el piloto de la posición ${positions[i]} no trae personId") }
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let { error("wrc.com repite pilotos en la tabla: ${it.joinToString()}") }

        // Inscripción más reciente: del rally pedido hacia atrás, solo los que ya se corrieron.
        val want = refs.toSet()
        val latest = mutableMapOf<String, Entry>()
        val photoUrls = mutableMapOf<String, String>()
        for (r in rallies.filter { it.order <= target.order && ran(it) }.asReversed()) {
            if (latest.keys.containsAll(want)) break
            for (e in entriesOf(r)) {
                if (e.personId !in want) continue
                latest.putIfAbsent(e.personId, e)
                e.photo?.let { photoUrls.putIfAbsent(e.personId, it) }
            }
        }

        val drivers = persons.mapIndexed { i, p ->
            val e = latest[refs[i]]
            val name = "${p.str("fieldOne").orEmpty()} ${surname(p.str("fieldTwo").orEmpty())}".replace(Regex("\\s+"), " ").trim()
            val own = setOf(asciiKey(name), asciiKey(e?.fullName.orEmpty())) - ""
            fun usable(team: String?) = team?.trim()?.takeIf { it.isNotEmpty() && !it.equals("None", ignoreCase = true) && asciiKey(it) !in own }
            val maker = e?.maker ?: p.str("fieldFour")?.trim()?.ifBlank { null }
            val team = (if (cls == "wrc") usable(p.str("fieldFive")) else usable(e?.entrant))?.let(::teamName) ?: maker.orEmpty()
            Driver(name = name, team = team, numberText = e?.number, ref = refs[i])
        }
        val standings = rows.indices.map { i -> Standing(pos = positions[i], points = points[i], driverRef = refs[i]) }
        val photos = persons.mapIndexedNotNull { i, p ->
            (photoUrls[refs[i]] ?: p.str("driverImageUrl"))?.let(::photoOf)?.let { refs[i] to it }
        }.toMap()
        log.info("wrc-feed {}: {} peticiones, {} rallies con inscripciones en memoria", cls, requests.get() - before, entriesByEvent.size)
        return SourceResult.Ready(round, drivers, standings, photos)
    }

    /** Inscripciones del rally principal de [r] (de memoria si ya se pidieron). */
    private suspend fun entriesOf(r: Rally): List<Entry> {
        entriesByEvent[r.eventId]?.let { return it }
        val main = get("/events/${r.eventId}.json").arr("rallies").filter { it.str("isMain") == "true" }.map { id(it, "rallyId") }.singleOrNull()
        if (main == null) {
            log.warn("wrc.com: «{}» no tiene rally principal", r.name)
            return emptyList<Entry>().also { entriesByEvent[r.eventId] = it }
        }
        val list = get("/events/${r.eventId}/rallies/$main/entries.json").jsonArray.mapNotNull { x ->
            val driver = x.jsonObject["driver"] as? JsonObject
            val personId = (driver?.str("personId") ?: x.str("driverId"))?.toIntOrNull()?.toString() ?: return@mapNotNull null
            Entry(
                personId = personId,
                number = x.str("identifier")?.trim()?.ifBlank { null },
                entrant = (x.jsonObject["entrant"] as? JsonObject)?.str("name")?.trim()?.ifBlank { null },
                maker = (x.jsonObject["manufacturer"] as? JsonObject)?.str("name")?.trim()?.ifBlank { null },
                fullName = driver?.str("fullName"),
                photo = x.str("driverImageUrl"),
            )
        }
        // Una lista vacía de un rally corrido sería un tropiezo de la fuente: se vuelve a pedir la próxima vez.
        if (list.isNotEmpty()) entriesByEvent[r.eventId] = list
        return list
    }

    /** GET a la API a ≤ 1 petición por segundo (las corridas van en serie: el job tiene su candado). */
    private suspend fun get(path: String): JsonElement {
        val wait = lastRequestAt.get() + 1_000 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        requests.incrementAndGet()
        try {
            return SourceHttp.getJson(BASE + path)
        } finally {
            lastRequestAt.set(System.currentTimeMillis())
        }
    }

    /** Id numérico positivo (va en rutas y consultas: nunca texto de la fuente tal cual). */
    private fun id(e: JsonElement, key: String): Int =
        e.str(key)?.toIntOrNull()?.takeIf { it > 0 } ?: error("wrc.com: «$key» inesperado (${e.str(key)})")

    private fun int(e: JsonElement, key: String): Int? = (e.jsonObject[key] as? JsonPrimitive)?.doubleOrNull?.roundToInt()

    /**
     * Foto de img.redbull.com: su `public_id` ("redbullcom/2026/1/19/…/evans-2026-1x1") con el
     * original (600 px) y la cara recortada por su CDN, ya en JPEG.
     */
    private fun photoOf(url: String): DriverPhoto? {
        if (!url.startsWith(IMAGES)) return null
        val path = url.removePrefix(IMAGES).substringBefore('?')
        val publicId = path.indexOf("redbullcom/").takeIf { it >= 0 }?.let { path.substring(it) } ?: return null
        if (!PHOTO_ID.matches(publicId)) return null
        return DriverPhoto(
            full = "${IMAGES}c_limit,w_720,h_1440/f_jpg,q_85/$publicId",
            face = "${IMAGES}c_thumb,g_face,w_256,h_256/f_jpg,q_85/$publicId",
        )
    }

    /** "MCERLEAN" → "McErlean", "O'NEILL" → "O'Neill", "TÜRKKAN" → "Türkkan". */
    private fun surname(upper: String): String = properCase(upper, acronyms = false)
        .replace(Regex("\\bMc(\\p{Ll})")) { "Mc" + it.groupValues[1].uppercase() }
        .replace(Regex("\\b(\\p{L})'(\\p{Ll})")) { it.groupValues[1] + "'" + it.groupValues[2].uppercase() }

    private val MINOR = setOf("of", "by", "the", "and", "de", "del", "di", "da", "du", "la", "le")

    /**
     * Equipo en MAYÚSCULAS → "Toyota Gazoo Racing WRT", "M-Sport Ford World Rally Team", "JML-Sports OY":
     * como [properCase] con siglas, pero por partes del guion y respetando las que llevan dígitos o
     * puntos ("WRT2", "R2RX", "PH.PH"). Si ya viene con minúsculas, se deja.
     */
    private fun teamName(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (text != text.uppercase()) return words.joinToString(" ")
        return words.withIndex().joinToString(" ") { (i, w) ->
            if (i > 0 && w.lowercase() in MINOR) w.lowercase()
            else w.split('-').joinToString("-") { part ->
                if (part.length <= 3 || part.any(Char::isDigit) || '.' in part) part
                else part.lowercase().replaceFirstChar { it.titlecase() }
            }
        }
    }
}
