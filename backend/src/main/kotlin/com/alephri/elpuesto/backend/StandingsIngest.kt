package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.NameRules
import com.alephri.elpuesto.model.Standing
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

// ————————————————————— Fuentes —————————————————————

/** Temporada/categoría para la que se pide la tabla ([rounds] = fechas de NUESTRO calendario). */
data class SourceContext(
    val season: Int,
    val seasonLabel: String,
    val categoryName: String,
    val param: String?,
    val rounds: Int = 0,
    val categoryId: String = "",
)

/** Respuesta de una fuente al pedir la tabla tras una fecha del calendario. */
sealed interface SourceResult {
    /**
     * Tabla lista; refleja hasta [throughRound] (numeración de NUESTRO calendario). [photos] =
     * ref → foto del piloto en la fuente (la que no la publique, vacío).
     */
    data class Ready(
        val throughRound: Int,
        val drivers: List<Driver>,
        val standings: List<Standing>,
        val photos: Map<String, DriverPhoto> = emptyMap(),
    ) : SourceResult

    /** La fuente todavía no refleja esa fecha: el job reintenta más tarde. */
    data class NotReady(val reason: String) : SourceResult
}

/**
 * Fuente de posiciones de una categoría. Cada fuente sabe detectar si ya refleja la fecha
 * pedida (no basta con que responda: varias publican la tabla "a medias" durante el fin
 * de semana) y arma pilotos con `ref` ESTABLE (el id del piloto en la fuente).
 */
interface StandingsSource {
    val id: String
    /** Crédito visible en la app (atribución que piden las licencias). */
    val credit: String
    val description: String
    val paramHint: String? get() = null
    /** [raceDay] = día de carrera de esa fecha en NUESTRO calendario (para cotejar con la fuente). */
    suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult
}

object StandingsSources {
    val all: List<StandingsSource> = listOf(JolpicaF1, FiaFormula2, FiaFormula3, FiaFormulaESite, NascarFeeds, FiaWecSite, WrcFeed, IndyCarFeeds, MexicoRacingCupSite)
    fun byId(id: String): StandingsSource? = all.firstOrNull { it.id == id }
    fun credit(id: String): String? = byId(id)?.credit
}

/** HTTP para las fuentes: JDK puro, timeouts cortos, gzip a mano (el JDK no lo descomprime). */
internal object SourceHttp {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(get(url, "application/json", headers, MAX_BYTES).decodeToString())

    /** Página pública (fuentes sin API): el HTML tal cual. */
    suspend fun getText(url: String): String = get(url, "text/html", emptyMap(), MAX_BYTES).decodeToString()

    /**
     * Imagen (fotos de pilotos). Tope más alto: hay originales de 8 MB (WEC). Decodificarla es
     * seguro: ImageService lee las dimensiones antes y submuestrea las enormes.
     */
    suspend fun getBytes(url: String): ByteArray = get(url, "image/*", emptyMap(), MAX_IMAGE_BYTES)

    private suspend fun get(url: String, accept: String, headers: Map<String, String>, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "el-puesto-backend (ingesta de posiciones; https://elpuesto.app)")
            .header("Accept", accept)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        resp.body().use { body ->
            if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()} en $url")
            // Tope de lo que se lee (comprimido y descomprimido): una respuesta enorme o una
            // "bomba" gzip de la fuente no llena la memoria del backend.
            val raw = readAtMost(body, maxBytes)
            if (resp.headers().firstValue("content-encoding").orElse("").contains("gzip")) {
                readAtMost(GZIPInputStream(raw.inputStream()), maxBytes)
            } else raw
        }
    }

    /** Una tabla pesa KB y una página pública cientos de KB: 5 MB ya es un error (o un abuso) de la fuente. */
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) error("respuesta de la fuente mayor a ${max / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

internal fun JsonElement.obj(key: String): JsonObject = jsonObject[key]!!.jsonObject
internal fun JsonElement.arr(key: String): JsonArray = jsonObject[key]?.jsonArray ?: JsonArray(emptyList())
/** El texto de [key]; null si falta o viene `null` (JsonNull.content sería la cadena "null"). */
internal fun JsonElement.str(key: String): String? = (jsonObject[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * La fuente solo publica la tabla VIGENTE y ya refleja una fecha posterior a la pedida. Pasa unas
 * horas tras cada carrera (el job da una fecha por terminada cuando acaba su día en UTC, y el
 * sitio se actualiza antes): no es un error, se guarda en cuanto esa fecha termine. Pedir a mano
 * una fecha vieja cae aquí también: no se puede etiquetar con la tabla de hoy.
 */
internal fun aheadOf(credit: String, round: Int) = SourceResult.NotReady(
    "$credit ya refleja una fecha posterior a la $round y solo publica la tabla vigente: se guarda cuando esa fecha termine en nuestro calendario",
)

/**
 * Fórmula 1 por Jolpica (sucesora pública de Ergast; CC BY-NC-SA: uso no comercial con
 * atribución). Ojo: `StandingsTable.round` avanza en cuanto cargan la qualy, así que la
 * fecha se da por reflejada solo cuando ya hay resultados de la CARRERA (y del sprint,
 * si el fin de semana lo tiene); entonces se pide la tabla TRAS esa fecha.
 */
object JolpicaF1 : StandingsSource {
    override val id = "jolpica-f1"
    override val credit = "Jolpica F1 · CC BY-NC-SA · fotos: formula1.com"
    override val description = "Fórmula 1 (api.jolpi.ca, sucesora de Ergast). La temporada es el año; sin parámetro."
    private const val BASE = "https://api.jolpi.ca/ergast/f1"

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val y = ctx.season
        val results = SourceHttp.getJson("$BASE/$y/$round/results/?limit=100").obj("MRData")
        val races = results.obj("RaceTable").arr("Races")
        if (races.isEmpty()) return SourceResult.NotReady("Jolpica aún no tiene resultados de la carrera de la fecha $round")
        val race = races[0]
        if (race.jsonObject.containsKey("Sprint") || hasSprint(y, round)) {
            val sprint = SourceHttp.getJson("$BASE/$y/$round/sprint/?limit=1").obj("MRData")
            if (sprint.obj("RaceTable").arr("Races").isEmpty()) {
                return SourceResult.NotReady("Jolpica aún no tiene resultados del sprint de la fecha $round")
            }
        }
        // Número del coche: el de la carrera (el permanente puede diferir: el campeón usa el 1).
        val raceNumber = race.arr("Results").associate { r -> r.obj("Driver").str("driverId")!! to r.str("number") }
        val lists = SourceHttp.getJson("$BASE/$y/$round/driverStandings/?limit=100")
            .obj("MRData").obj("StandingsTable").arr("StandingsLists")
        if (lists.isEmpty()) return SourceResult.NotReady("Jolpica aún no publica la tabla tras la fecha $round")
        val rows = lists[0].arr("DriverStandings")
        val drivers = mutableListOf<Driver>()
        val standings = mutableListOf<Standing>()
        rows.forEachIndexed { i, row ->
            val d = row.obj("Driver")
            val ref = d.str("driverId")!!
            val team = row.arr("Constructors").lastOrNull()?.str("name").orEmpty() // el vigente es el último
            val number = raceNumber[ref] ?: d.str("permanentNumber")
            drivers += Driver(name = "${d.str("givenName").orEmpty()} ${d.str("familyName").orEmpty()}".trim(), team = team, numberText = number, ref = ref)
            standings += Standing(
                pos = row.str("position")?.toIntOrNull() ?: (i + 1),
                points = row.str("points")?.toDoubleOrNull()?.roundToInt() ?: 0,
                driverRef = ref,
            )
        }
        val names = rows.associate { row -> row.obj("Driver").let { d -> d.str("driverId")!! to (d.str("givenName").orEmpty() to d.str("familyName").orEmpty()) } }
        // Jolpica no trae fotos: las de formula1.com (sin ellas, la tabla se guarda igual).
        val photos = runCatching { f1Photos(y, names) }
            .onFailure { LoggerFactory.getLogger("StandingsIngest").warn("fotos de formula1.com: {}", it.message) }
            .getOrDefault(emptyMap())
        return SourceResult.Ready(round, drivers, standings, photos)
    }

    /**
     * Fotos de la página pública de pilotos de formula1.com (solo la parrilla vigente). Su id de
     * piloto es nombre(3) + apellido(3) + "01" ("lannor01" = Lando Norris): se empata con el
     * nombre de Jolpica, solo si hay un único candidato.
     */
    private suspend fun f1Photos(year: Int, names: Map<String, Pair<String, String>>): Map<String, DriverPhoto> {
        val html = SourceHttp.getText("https://www.formula1.com/en/drivers")
        val byKey = Regex("common/f1/$year/([a-z0-9]+)/([a-z]{6})(\\d{2})/$year\\1\\2\\3right").findAll(html)
            .map { m -> m.groupValues[2] to m.value }.distinct().groupBy({ it.first }, { it.second })
        return names.mapNotNull { (ref, n) ->
            val key = asciiKey(n.first).take(3) + asciiKey(n.second).take(3)
            val ids = byKey[key]?.distinct()?.takeIf { it.size == 1 } ?: return@mapNotNull null
            fomPhoto("https://media.formula1.com/image/upload", ids[0])?.let { ref to it }
        }.toMap()
    }

    /** El resultado de la carrera no dice si hubo sprint; el calendario de la fecha sí. */
    private suspend fun hasSprint(year: Int, round: Int): Boolean =
        SourceHttp.getJson("$BASE/$year/$round/races/").obj("MRData").obj("RaceTable").arr("Races")
            .firstOrNull()?.jsonObject?.containsKey("Sprint") == true
}

/** Foto de un piloto en su fuente: [full] = la foto; [face] = la cara ya recortada, si la fuente sabe hacerlo. */
data class DriverPhoto(val full: String, val face: String? = null)

/**
 * Fotos de los pilotos (decisión 2026-09-27: miniatura en Posiciones y foto en grande al tocarla).
 * Se bajan UNA vez y se sirven desde nuestro pipeline de imágenes (kind `driver`): el id es la
 * huella de sus URLs, así una foto nueva en la fuente es otro id. Solo de los servidores de
 * imágenes de las fuentes (las URLs salen de páginas ajenas: no se le sigue a cualquier lado).
 */
internal object DriverPhotos {
    private val log = LoggerFactory.getLogger("DriverPhotos")
    private val HOSTS = setOf("res.cloudinary.com", "media.formula1.com", "www.fiawec.com", "images.ctfassets.net", "img.redbull.com", "www.indycar.com", "www.indynxt.com")

    fun idOf(p: DriverPhoto): String = MessageDigest.getInstance("SHA-256")
        .digest("${p.full}|${p.face.orEmpty()}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** ref → id de su foto, bajando las que falten (una petición por segundo). La que falla se omite. */
    suspend fun ensure(photos: Map<String, DriverPhoto>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((ref, p) in photos) {
            val id = idOf(p)
            if (!ImageService.exists("driver", id, "thumb")) {
                if (!allowed(p.full) || (p.face != null && !allowed(p.face))) {
                    log.warn("foto de {} fuera de los servidores permitidos: {}", ref, p.full)
                    continue
                }
                val stored = runCatching {
                    delay(1_000)
                    val full = SourceHttp.getBytes(p.full)
                    val face = p.face?.let { delay(1_000); SourceHttp.getBytes(it) }
                    ImageService.storeDriverPhoto(id, full, face)
                }.getOrElse { log.warn("foto de {}: {}", ref, it.message); false }
                if (!stored) continue
            }
            out[ref] = id
        }
        return out
    }

    private fun allowed(url: String): Boolean =
        runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host in HOSTS } == true
}

/**
 * Foto de la familia FOM (F1, F2, F3: el mismo sistema en Cloudinary) por su `public_id`: el
 * cuerpo completo y la cara recortada por su propio CDN, ya en JPEG sobre el fondo de la app.
 */
internal fun fomPhoto(uploadBase: String, publicId: String): DriverPhoto? {
    if (!Regex("^[a-z0-9/_-]+$").matches(publicId) || "fallback" in publicId) return null
    return DriverPhoto(
        full = "$uploadBase/b_rgb:1e222b,c_limit,w_720,h_1440,f_jpg,q_85/$publicId",
        face = "$uploadBase/c_thumb,g_face,w_256,h_256,b_rgb:1e222b,f_jpg,q_85/$publicId",
    )
}

/** "Hülkenberg" → "hulkenberg": para empatar nombres entre fuentes. */
internal fun asciiKey(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z]"), "")

/**
 * Lo que un sitio Next.js (app router) incrusta en su HTML para hidratar la página: cada
 * `self.__next_f.push([1,"…"])` es un pedazo del "flight" de React, y ahí viajan las props
 * de los componentes como JSON. Leer esas props es más estable que leer las celdas.
 */
internal object NextFlight {
    private const val PUSH = "self.__next_f.push("

    /** El flight completo: los pedazos de texto concatenados en orden. */
    fun of(html: String): String = buildString {
        var i = html.indexOf(PUSH)
        while (i >= 0) {
            val start = i + PUSH.length
            val end = jsonEnd(html, start) ?: break
            val chunk = Json.parseToJsonElement(html.substring(start, end)).jsonArray
            (chunk.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.let { append(it.content) }
            i = html.indexOf(PUSH, end)
        }
    }

    /** Cada objeto JSON del flight que empieza con [prefix] (p. ej. `{"meetings":[`). */
    fun objectsStartingWith(flight: String, prefix: String): List<JsonObject> =
        occurrences(flight, prefix).mapNotNull { at -> parseAt(flight, at) as? JsonObject }

    /** Cada valor (objeto o arreglo) que sigue a [key] (p. ej. `"meetingSessions":`). */
    fun valuesAfter(flight: String, key: String): List<JsonElement> =
        occurrences(flight, key).mapNotNull { at -> parseAt(flight, at + key.length) }

    private fun occurrences(s: String, needle: String): List<Int> =
        generateSequence(s.indexOf(needle).takeIf { it >= 0 }) { s.indexOf(needle, it + 1).takeIf { i -> i >= 0 } }.toList()

    private fun parseAt(s: String, start: Int): JsonElement? =
        jsonEnd(s, start)?.let { end -> runCatching { Json.parseToJsonElement(s.substring(start, end)) }.getOrNull() }

    /** Índice justo después del objeto/arreglo JSON que abre en [start] (respeta cadenas y escapes). */
    private fun jsonEnd(s: String, start: Int): Int? {
        if (start >= s.length || (s[start] != '{' && s[start] != '[')) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (k in start until s.length) {
            val c = s[k]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return k + 1
            }
        }
        return null
    }
}

/**
 * Fórmula 2 y Fórmula 3 leyendo las PÁGINAS PÚBLICAS de sus sitios (fiaformula2.com y
 * fiaformula3.com son el mismo sistema; decisión 2026-09-27: las posiciones se obtienen aunque
 * haya que leer el sitio; no usamos su API interna ni su llave).
 *
 * - La tabla (`/en/standings/{año}/drivers`) trae puntos por carrera pero NO número ni equipo:
 *   salen de la página de cada fecha (`/en/racing/{año}/{sede}`), que incrusta los resultados de
 *   su última carrera. Se recorren de la fecha pedida hacia atrás hasta cubrir a todos, así cada
 *   piloto queda con su aparición MÁS RECIENTE (hay números compartidos por sustitutos: el #22
 *   de 2026 fue de Varrone y luego de Yamakoshi).
 * - Una fecha está reflejada cuando CADA carrera suya tiene puntos (lo no corrido llega null; el
 *   que no corrió lleva 0). Las páginas se regeneran en segundo plano: la primera visita tras la
 *   carrera puede servir la versión vieja → "aún no" y el job reintenta.
 * - La posición publicada no es confiable (el 2026-09-27 el sitio mostraba 2, 4, 6…): se usa el
 *   ORDEN de la tabla, que ya viene desempatada por conteo.
 */
class FiaSeriesSite(override val id: String, private val host: String, private val series: String) : StandingsSource {
    override val credit = host.removePrefix("www.")
    override val description = "$series: lee las páginas públicas de $credit (tabla + resultados de cada fecha para número y equipo). La temporada es el año; sin parámetro."

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val base = "https://$host"
        val html = SourceHttp.getText("$base/en/standings/${ctx.season}/drivers")
        val flight = NextFlight.of(html)
        val table = NextFlight.objectsStartingWith(flight, "{\"meetings\":[").firstOrNull { it["standings"] is JsonArray }
            ?: error("$credit cambió su página de posiciones: no se encontró la tabla")
        val meetings = table.arr("meetings")
        // La fecha se coteja por día (no por índice): si el sitio quita o agrega una, no se desfasa.
        val mi = meetings.indexOfFirst { m ->
            val start = m.str("meetingStartDate")?.let(LocalDate::parse)
            val end = m.str("meetingEndDate")?.let(LocalDate::parse)
            start != null && end != null && raceDay in start.minusDays(1)..end.plusDays(1)
        }
        if (mi < 0) error("$credit no tiene una fecha que coincida con el $raceDay (fecha $round de nuestro calendario)")
        val races = meetings[mi].arr("raceSessions")
        if (races.isEmpty()) error("$credit no lista carreras en la fecha $round")
        val rows = table.arr("standings")
        fun ran(meeting: Int, race: Int) =
            rows.any { row -> (row.arr("points").getOrNull(meeting) as? JsonArray)?.getOrNull(race).let { it != null && it !is JsonNull } }
        races.forEachIndexed { j, race ->
            if (!ran(mi, j)) return SourceResult.NotReady("$credit aún no refleja «${race.str("description") ?: "carrera ${j + 1}"}» de la fecha $round (la página se regenera sola; se reintenta)")
        }
        // El sitio solo publica la tabla VIGENTE: no sirve para etiquetar una fecha anterior.
        if ((mi + 1 until meetings.size).any { k -> ran(k, 0) }) return aheadOf(credit, round)

        val refs = rows.mapIndexed { i, row -> row.str("driverReference")?.trim()?.ifBlank { null } ?: error("$credit: la fila ${i + 1} de la tabla no trae piloto") }
        val points = rows.mapIndexed { i, row -> row.jsonObject["championshipPoints"]?.jsonPrimitive?.doubleOrNull ?: error("$credit: la fila ${i + 1} no trae puntos") }
        if (points.zipWithNext().any { (a, b) -> b > a }) error("la tabla de $credit no viene ordenada por puntos")

        val found = numbersAndTeams(base, meetings, mi, refs.toSet())
        val drivers = rows.mapIndexed { i, row ->
            val first = row.str("driverFirstName").orEmpty()
            val last = row.str("driverLastName").orEmpty()
            val name = if (row.str("driverNameFormat") == "LastNameIsPrimary") "$last $first" else "$first $last"
            val seen = found[refs[i]]
            Driver(name = name.replace(Regex("\\s+"), " ").trim(), team = seen?.team.orEmpty(), numberText = seen?.number, ref = refs[i])
        }
        val standings = rows.indices.map { i -> Standing(pos = i + 1, points = points[i].roundToInt(), driverRef = refs[i]) }
        // Fotos: el mismo sistema de F1 en la nube Cloudinary que el sitio declara en su HTML.
        val cloud = Regex("https://res\\.cloudinary\\.com/([a-z0-9-]+)").find(html)?.groupValues?.get(1)
        val photos = if (cloud == null) emptyMap() else found.mapNotNull { (ref, seen) ->
            seen.photo?.let { fomPhoto("https://res.cloudinary.com/$cloud/image/upload", it) }?.let { ref to it }
        }.toMap()
        return SourceResult.Ready(round, drivers, standings, photos)
    }

    /** Lo último que se vio de un piloto en los resultados: número, equipo y `public_id` de su foto. */
    private data class Seen(val number: String?, val team: String, val photo: String?)

    /** ref → su aparición más reciente, de la fecha [from] hacia atrás. */
    private suspend fun numbersAndTeams(base: String, meetings: JsonArray, from: Int, refs: Set<String>): Map<String, Seen> {
        val found = mutableMapOf<String, Seen>()
        for (k in from downTo 0) {
            if (found.keys.containsAll(refs)) break
            // Solo rutas de fecha del mismo sitio: la URL sale de la página, no se le sigue a cualquier lado.
            val path = meetings[k].str("url")?.takeIf { RACE_PAGE.matches(it) } ?: continue
            delay(1_000) // cortesía con el sitio: una página por segundo
            val page = NextFlight.of(SourceHttp.getText(base + path))
            val sessions = NextFlight.valuesAfter(page, "\"meetingSessions\":").flatMap { (it as? JsonArray).orEmpty() }
            for (session in sessions.asReversed()) {
                for (r in session.arr("results")) {
                    val ref = r.str("driverReference")?.trim()?.ifBlank { null } ?: continue // hay filas sin piloto
                    if (ref !in refs || ref in found) continue
                    found[ref] = Seen(
                        number = r.str("racingNumber")?.trim()?.ifBlank { null },
                        team = (r.str("teamName") ?: r.str("displayTeamName")).orEmpty().trim(),
                        photo = (r.jsonObject["driverAvatarImage"] as? JsonObject)?.str("public_id"),
                    )
                }
            }
        }
        return found
    }

    private companion object {
        val RACE_PAGE = Regex("^/en/racing/\\d{4}/[a-z0-9-]+$")
    }
}

val FiaFormula2 = FiaSeriesSite(id = "fiaformula2-web", host = "www.fiaformula2.com", series = "Fórmula 2")
val FiaFormula3 = FiaSeriesSite(id = "fiaformula3-web", host = "www.fiaformula3.com", series = "Fórmula 3")

/**
 * NASCAR (Cup, O'Reilly y Truck) con los JSON públicos de cf.nascar.com: los mismos archivos que
 * descarga la página de standings de nascar.com para dibujar su tabla (la página está detrás de
 * un reto anti-bots de Cloudflare; los archivos no piden llave ni reto). Decisión 2026-09-27.
 *
 * - `{año}/{serie}/race_list_basic.json`: calendario. Solo cuentan las carreras de puntos
 *   (`race_type_id` 1; el 2 son exhibiciones), la fecha se coteja por día y está corrida
 *   cuando trae ganador.
 * - `{año}/{serie}/points-feed.json`: la tabla en su orden oficial (número como TEXTO: "00" ≠
 *   "0"; es el último que usó el piloto). Se da por reflejada cuando los `points_earned` de la
 *   tabla coinciden con los del resultado de esa carrera (contra la anterior casi no coinciden;
 *   se tolera un 10% por sanciones que corrigen uno de los dos archivos).
 * - Equipo: no viene en la tabla; sale del `raceResults.json` de su carrera más reciente.
 * - Filas con 0 puntos = pilotos que declararon otra serie o no arrancaron (incluso ganadores
 *   de carrera): no compiten por ese campeonato y se omiten. Los negativos (sanciones) sí van.
 * - The Chase (2026): los 16/12/10 de arriba llevan los puntos reiniciados (~2000) y el resto sus
 *   acumulados; así lo publica NASCAR y así se guarda.
 */
object NascarFeeds : StandingsSource {
    override val id = "nascar-feed"
    override val credit = "NASCAR"
    override val description = "NASCAR con los JSON públicos de cf.nascar.com (los que usa la página de standings de nascar.com). La temporada es el año."
    override val paramHint = "serie: 1 = Cup, 2 = O'Reilly, 3 = Truck"
    private const val BASE = "https://cf.nascar.com/cacher"

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val series = ctx.param?.trim()?.takeIf { it in setOf("1", "2", "3") }
            ?: error("el parámetro debe ser la serie de NASCAR: 1 = Cup, 2 = O'Reilly, 3 = Truck")
        val base = "$BASE/${ctx.season}/$series"
        val races = SourceHttp.getJson("$base/race_list_basic.json").jsonArray
            .filter { it.str("race_type_id") == "1" }
            .sortedBy { it.str("race_date") }
        // race_date viene en hora del Este sin zona: basta el día.
        val ri = races.indexOfFirst { r -> r.str("race_date")?.take(10)?.let(LocalDate::parse)?.let { it in raceDay.minusDays(1)..raceDay.plusDays(1) } == true }
        if (ri < 0) error("NASCAR no tiene una carrera de puntos que coincida con el $raceDay (fecha $round de nuestro calendario)")
        val race = races[ri]
        val raceName = race.str("race_name")?.trim() ?: "fecha $round"
        if (race.str("winner_driver_id") == null) return SourceResult.NotReady("NASCAR aún no publica el resultado de «$raceName» (fecha $round)")
        // El archivo solo trae la tabla VIGENTE: no sirve para etiquetar una fecha anterior.
        if (races.drop(ri + 1).any { it.str("winner_driver_id") != null }) return aheadOf(credit, round)

        val rows = SourceHttp.getJson("$base/points-feed.json").jsonArray
            .filter { (it.jsonObject["points"]?.jsonPrimitive?.intOrNull ?: 0) != 0 }
            .sortedBy { it.jsonObject["position"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
        if (rows.isEmpty()) return SourceResult.NotReady("NASCAR aún no publica la tabla de la temporada")
        val refs = rows.mapIndexed { i, row -> row.str("driver_id") ?: error("NASCAR: la fila ${i + 1} de la tabla no trae piloto") }
        val earned = refs.zip(rows.map { it.str("points_earned") }).toMap()

        val raceId = race.str("race_id")!!
        val results = raceResults(base, raceId)
        val compared = results.filter { it.str("driver_id") in earned }
        val matching = compared.count { earned[it.str("driver_id")] == it.str("points_earned") }
        if (compared.isEmpty() || matching * 10 < compared.size * 9) {
            return SourceResult.NotReady("la tabla de NASCAR aún no refleja «$raceName» ($matching de ${compared.size} pilotos con los puntos de esa carrera)")
        }

        val teams = teamsByDriver(base, races, ri, results, refs.toSet())
        val drivers = rows.mapIndexed { i, row ->
            Driver(name = row.str("driver_name").orEmpty().replace(Regex("\\s+"), " ").trim(), team = teams[refs[i]].orEmpty(), numberText = row.str("car_no")?.trim()?.ifBlank { null }, ref = refs[i])
        }
        val standings = rows.mapIndexed { i, row -> Standing(pos = i + 1, points = row.jsonObject["points"]!!.jsonPrimitive.intOrNull ?: 0, driverRef = refs[i]) }
        return SourceResult.Ready(round, drivers, standings)
    }

    private suspend fun raceResults(base: String, raceId: String): JsonArray {
        require(raceId.all(Char::isDigit)) { "race_id inesperado: $raceId" } // va en la ruta
        return SourceHttp.getJson("$base/$raceId/raceResults.json").jsonArray
    }

    /** driver_id → equipo de su carrera más reciente, de la carrera [from] hacia atrás. */
    private suspend fun teamsByDriver(base: String, races: List<JsonElement>, from: Int, latest: JsonArray, refs: Set<String>): Map<String, String> {
        val found = mutableMapOf<String, String>()
        for (k in from downTo 0) {
            if (found.keys.containsAll(refs)) break
            val results = if (k == from) latest else {
                delay(1_000) // cortesía con el sitio: un archivo por segundo
                raceResults(base, races[k].str("race_id") ?: continue)
            }
            for (r in results) {
                val ref = r.str("driver_id") ?: continue
                if (ref in refs && ref !in found) r.str("team_name")?.trim()?.ifBlank { null }?.let { found[ref] = it }
            }
        }
        return found
    }
}

/**
 * FIA WEC (Hypercar y LMGT3) leyendo la página pública de clasificaciones de fiawec.com:
 * `/en/season/{año}` (la misma que `/en/page/manufacturers-classification`, pero con el año fijo:
 * aquella siguió mostrando la temporada anterior hasta la primera carrera). HTML hecho en el
 * servidor, sin JSON incrustado: se lee con Jsoup. Decisión 2026-09-27: el campeonato de PILOTOS
 * de cada clase tal cual lo publica la FIA (no el de fabricantes ni una vista por coche).
 *
 * - Una fila = una tripulación (quienes compartieron coche y suman lo mismo); un coche sale dos
 *   veces si su tripulación cambió. `ref` = ids de sus pilotos en el sitio; nombre = los pilotos
 *   unidos con " / ". El sitio los escribe en MAYÚSCULAS: el nombre bien escrito sale de la página
 *   de cada piloto (una vez por proceso, uno por segundo). Equipo = el de la tabla de equipos
 *   (LMGT3) o el fabricante (Hypercar no tiene tabla de equipos).
 * - Columnas de carrera en orden de calendario; deben ser tantas como fechas en el nuestro.
 *   Fecha reflejada = su carrera ya está seleccionada en el sitio y su columna tiene puntos: durante
 *   el fin de semana la columna aparece con 0 en todos, así que "ya no es -" no basta.
 */
object FiaWecSite : StandingsSource {
    override val id = "fiawec-web"
    override val credit = "fiawec.com"
    override val description = "FIA WEC: campeonato de pilotos de la clase, de la página pública de clasificaciones de fiawec.com. La temporada es el año."
    override val paramHint = "clase: hypercar o lmgt3"
    private const val BASE = "https://www.fiawec.com"
    /** Lo que se toma de la página de cada piloto: su nombre bien escrito y su foto (URL original). */
    private data class DriverPage(val name: String, val photo: String?)
    /** id del piloto en el sitio → su página ya leída (una vez por proceso). */
    private val driverPages = java.util.concurrent.ConcurrentHashMap<String, DriverPage>()

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val cls = ctx.param?.trim()?.lowercase()?.takeIf { it == "hypercar" || it == "lmgt3" }
            ?: error("el parámetro debe ser la clase: hypercar o lmgt3")
        val doc = Jsoup.parse(SourceHttp.getText("$BASE/en/season/${ctx.season}"), BASE)
        val tables = doc.select("button[data-bs-target^=#results-]").mapNotNull { b ->
            doc.getElementById(b.attr("data-bs-target").removePrefix("#"))?.selectFirst("table")?.let { b.text().lowercase() to it }
        }
        val drivers = tables.firstOrNull { (title, _) -> cls in title.replace(" ", "") && "driver" in title }?.second
            ?: error("fiawec.com cambió su página: no se encontró el campeonato de pilotos de $cls")

        // Columnas de carrera: las que enlazan a /en/race/<slug>-<año>, en orden de calendario.
        val header = drivers.select("thead tr").last()?.children().orEmpty()
        val raceCols = header.withIndex().filter { (_, th) -> th.selectFirst("a[href^=/en/race/]") != null }.map { it.index }
        if (raceCols.size != ctx.rounds) error("fiawec.com tiene ${raceCols.size} carreras y nuestro calendario ${ctx.rounds}: revisa el calendario antes de seguir")
        val slugs = raceCols.map { header[it].selectFirst("a[href^=/en/race/]")!!.attr("href") }
        if (slugs.any { !it.endsWith("-${ctx.season}") }) error("fiawec.com no muestra la temporada ${ctx.season} (${slugs.first()})")
        if (round !in 1..raceCols.size) error("fiawec.com no tiene la fecha $round")

        val rows = drivers.select("tbody tr").map { it.select("td") }
            .filter { td -> td.size == header.size && td[2].text().removePrefix("#").isNotBlank() && td[3].select("a[href^=/en/driver/]").isNotEmpty() }
        if (rows.isEmpty()) return SourceResult.NotReady("fiawec.com aún no publica el campeonato de pilotos de $cls")
        fun scored(col: Int) = rows.any { td -> (td[col].ownText().trim().toDoubleOrNull() ?: 0.0) > 0 }

        // Carrera seleccionada en el sitio = hasta dónde llega la tabla.
        val selected = doc.select("a[data-model=raceId]").indexOfFirst { it.hasClass("bg-primary-subtle") }
        if (selected < round - 1 || !scored(raceCols[round - 1])) {
            return SourceResult.NotReady("fiawec.com aún no refleja la fecha $round (${slugs[round - 1].substringAfterLast('/')})")
        }
        // El sitio solo publica la tabla VIGENTE: no sirve para etiquetar una fecha anterior.
        if (selected > round - 1 || (round until raceCols.size).any { scored(raceCols[it]) }) return aheadOf(credit, round)

        // Equipo por número de coche (solo LMGT3 tiene tabla de equipos).
        val teamByCar = tables.firstOrNull { (title, _) -> cls in title.replace(" ", "") && "team" in title }?.second
            ?.select("tbody tr")?.map { it.select("td") }?.filter { it.size > 3 }
            ?.associate { td -> td[2].text().removePrefix("#").trim() to properCase(td[3].text(), acronyms = true) }
            .orEmpty()

        val out = mutableListOf<Driver>()
        val standings = mutableListOf<Standing>()
        val photos = mutableMapOf<String, DriverPhoto>()
        rows.forEachIndexed { i, td ->
            val car = td[2].text().removePrefix("#").trim()
            val crew = td[3].select("a[href^=/en/driver/]").map { a -> a.attr("href").substringAfterLast('/') to a.text() }
            val pages = crew.map { (id, upper) -> driverPage(ctx.season, id, upper) }
            val maker = td[1].selectFirst("img[alt]")?.attr("alt").orEmpty()
            val ref = crew.map { it.first }.sorted().joinToString("-")
            out += Driver(name = pages.joinToString(" / ") { it.name }, team = teamByCar[car] ?: properCase(maker, acronyms = true), numberText = car, ref = ref)
            // Una fila puede ser una tripulación: su foto es la del primero que tenga.
            pages.firstNotNullOfOrNull { it.photo }?.let { photos[ref] = DriverPhoto(full = it) }
            val total = td.last()!!.ownText().trim().toDoubleOrNull() ?: error("fiawec.com: la fila ${i + 1} no trae el total de puntos")
            standings += Standing(pos = i + 1, points = total.roundToInt(), driverRef = ref)
        }
        return SourceResult.Ready(round, out, standings, photos)
    }

    private suspend fun driverPage(season: Int, id: String, upper: String): DriverPage {
        driverPages[id]?.let { return it }
        val doc = if (id.all(Char::isDigit)) runCatching {
            delay(1_000) // cortesía con el sitio: una página por segundo
            Jsoup.parse(SourceHttp.getText("$BASE/en/driver/$season/$id"), BASE)
        }.getOrNull() else null
        val fromPage = doc?.title()?.substringAfter(" - ", "")?.trim()
        // La página a veces también trae el apellido en mayúsculas ("Kevin MAGNUSSEN"): esas palabras se corrigen.
        val name = fromPage?.takeIf { it.isNotBlank() && it.equals(upper, ignoreCase = true) }
            ?.split(' ')?.joinToString(" ") { w -> if (w.length > 1 && w == w.uppercase() && w != w.lowercase()) properCase(w, acronyms = false) else w }
            ?: properCase(upper, acronyms = false)
        return DriverPage(name, doc?.let { photoOf(it, name) }).also { driverPages[id] = it }
    }

    /**
     * Su foto de piloto: un `/uploads/…-right-….png` cuyo nombre trae su apellido (la página
     * también muestra a otros). Se usa el archivo original, no la miniatura de la página.
     */
    private fun photoOf(doc: org.jsoup.nodes.Document, name: String): String? {
        val surname = asciiKey(name.substringAfterLast(' '))
        if (surname.length < 2) return null
        return doc.select("img[src*=/uploads/]").asSequence()
            .map { it.attr("src").substringAfterLast("/uploads/") }
            .firstOrNull { f -> FILE.matches(f) && "-right-" in f && surname in asciiKey(f) }
            ?.let { "$BASE/uploads/$it" }
    }

    private val FILE = Regex("^[A-Za-z0-9._-]+\\.(png|jpe?g)$")

}

/**
 * Nombre en MAYÚSCULAS → mayúscula inicial: "SÉBASTIEN BUEMI" → "Sébastien Buemi"; con [acronyms]
 * (equipos), las palabras de hasta 3 letras se quedan ("TF SPORT" → "TF Sport") salvo "of/by/the/and"
 * después de la primera ("HEART OF RACING" → "Heart of Racing").
 */
internal fun properCase(upper: String, acronyms: Boolean): String =
    upper.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.withIndex().joinToString(" ") { (i, word) ->
        when {
            acronyms && i > 0 && word.lowercase() in setOf("of", "by", "the", "and") -> word.lowercase()
            acronyms && word.length <= 3 -> word
            else -> word.split('-').joinToString("-") { part -> part.lowercase().replaceFirstChar { it.titlecase() } }
        }
    }

// ————————————————————— Tablas leídas de imágenes —————————————————————

/** Una fila tal cual viene impresa en la imagen (posición impresa, decisión 2026-09-27). */
@Serializable
data class ReadingRow(val pos: Int, val name: String, val team: String? = null, val points: Double)

/**
 * Lo que alguien leyó de la imagen [imageUrl]: la tabla y hasta qué fecha de NUESTRO calendario
 * llega (la última columna con datos: la fecha impresa en la imagen no es confiable).
 */
@Serializable
data class StandingsReading(
    val imageUrl: String,
    val throughRound: Int,
    val rows: List<ReadingRow>,
    val readAt: String? = null,
    val readBy: String? = null,
)

@Serializable
data class ReadingSaved(val summary: ChangeSummary, val run: IngestRun? = null)

internal object StandingsReadings {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(categoryId: String): StandingsReading? = transaction {
        StandingsReadingsT.selectAll().where { StandingsReadingsT.categoryId eq categoryId }.firstOrNull()?.let {
            StandingsReading(
                imageUrl = it[StandingsReadingsT.imageUrl],
                throughRound = it[StandingsReadingsT.throughRound],
                rows = json.decodeFromString(it[StandingsReadingsT.rowsJson]),
                readAt = it[StandingsReadingsT.readAt],
                readBy = it[StandingsReadingsT.readBy],
            )
        }
    }

    fun roundsOf(categoryId: String): Int = transaction {
        RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }.count().toInt()
    }

    /** null = válida. [rounds] = fechas del calendario de la categoría. */
    fun problem(r: StandingsReading, rounds: Int): String? {
        if (!MexicoRacingCupSite.isImageUrl(r.imageUrl)) return "imageUrl debe ser la imagen de la tabla en ${MexicoRacingCupSite.IMAGE_HOST} (https)"
        if (r.throughRound < 1 || (rounds > 0 && r.throughRound > rounds)) return "throughRound fuera del calendario (1..$rounds)"
        if (r.rows.isEmpty() || r.rows.size > 1000) return "la tabla debe traer entre 1 y 1000 filas"
        r.rows.groupBy { it.pos }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let { return "posiciones repetidas: ${it.sorted().joinToString()}" }
        r.rows.firstOrNull { it.pos < 1 }?.let { return "posición inválida: ${it.pos}" }
        r.rows.firstOrNull { it.name.isBlank() || it.name.length > 200 || NameRules.hasHiddenChars(it.name) }?.let { return "nombre inválido en la posición ${it.pos}" }
        r.rows.firstOrNull { (it.team?.length ?: 0) > 200 }?.let { return "equipo demasiado largo en la posición ${it.pos}" }
        r.rows.firstOrNull { !it.points.isFinite() || it.points < -10_000 || it.points > 100_000 }?.let { return "puntos inválidos en la posición ${it.pos}" }
        return null
    }

    fun save(categoryId: String, r: StandingsReading, by: String): ChangeSummary = transaction {
        val existed = StandingsReadingsT.deleteWhere { StandingsReadingsT.categoryId eq categoryId } > 0
        StandingsReadingsT.insert {
            it[StandingsReadingsT.categoryId] = categoryId
            it[imageUrl] = r.imageUrl
            it[throughRound] = r.throughRound
            it[rowsJson] = json.encodeToString(r.rows.sortedBy { row -> row.pos })
            it[readAt] = Instant.now().toString()
            it[readBy] = by.take(120)
        }
        ChangeSummary(if (existed) "updated" else "created", "standings-reading", categoryId, detail = "${r.rows.size} filas hasta la fecha ${r.throughRound}")
    }
}

/**
 * México Racing Cup: su clasificación de cada categoría es una IMAGEN en
 * `mexicoracingcup.com/33-<categoría>` (menú "Puntuación"; sin reto anti-bots). La lectura la
 * guarda alguien aparte (`PUT /admin/categories/{id}/standings-reading`); esta fuente solo revisa
 * la página: mientras la imagen vigente sea la leída, publica esa tabla (posición IMPRESA y puntos
 * enteros, decisiones del usuario 2026-09-27); si el sitio sube otra, avisa que falta leerla. Las
 * tablas se suben con semanas de atraso: la fecha que reflejan es la de la lectura.
 */
object MexicoRacingCupSite : StandingsSource {
    override val id = "mexicoracingcup-img"
    override val credit = "mexicoracingcup.com"
    override val description = "México Racing Cup: su tabla es una IMAGEN en mexicoracingcup.com. Publica la lectura guardada (standings-reading) mientras sea la imagen vigente; si el sitio sube otra, avisa que falta leerla."
    override val paramHint = "página de la categoría (p. ej. 33-copa-tc2000) y, si trae varias tablas, #N (la primera = 1)"
    const val IMAGE_HOST = "duncanwebmin.notiauto.com"
    private val PAGE = Regex("^33-[a-z0-9-]+$")
    private val IMAGE = Regex("https://duncanwebmin\\.notiauto\\.com/repository/sitios/innerpicstmp/tinyimg(\\d{13})\\d*\\.(?:jpe?g|png)")

    fun isImageUrl(url: String): Boolean = url.length <= 500 && IMAGE.matches(url)

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val param = ctx.param?.trim().orEmpty()
        val page = param.substringBefore('#')
        if (!PAGE.matches(page)) error("el parámetro debe ser la página de la categoría (33-…), p. ej. 33-copa-tc2000")
        val index = param.substringAfter('#', "1").toIntOrNull()?.minus(1)?.takeIf { it >= 0 } ?: error("#N debe ser un número (la primera tabla = 1)")
        val doc = Jsoup.parse(SourceHttp.getText("https://mexicoracingcup.com/$page"))
        // La página trae más imágenes del mismo servidor: el encabezado de la categoría (1100×180)
        // y el banner del pie (835×136). Las tablas nunca son tan anchas: se descartan por proporción.
        val images = doc.select("img[src*=/innerpicstmp/tinyimg]").mapNotNull { img ->
            val src = img.attr("src").substringBefore('?')
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            src.takeIf { IMAGE.matches(it) && !(w != null && h != null && h > 0 && w > 3 * h) }
        }.distinct()
        val current = images.getOrNull(index) ?: error("mexicoracingcup.com/$page no trae la tabla ${index + 1} (trae ${images.size} imágenes)")
        val reading = StandingsReadings.get(ctx.categoryId)
        if (reading == null || reading.imageUrl != current) {
            return SourceResult.NotReady("hay una tabla nueva en mexicoracingcup.com (subida ${uploadedOn(current)}) que falta leer: $current")
        }
        val rows = reading.rows.sortedBy { it.pos }
        // Sin número ni id en la imagen: la llave es el nombre (una fila repetida en el sitio va con -2).
        val seen = mutableMapOf<String, Int>()
        val refs = rows.map { r ->
            val base = asciiKey(r.name).ifEmpty { "piloto" }.take(100)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base-$n"
        }
        val drivers = rows.mapIndexed { i, r -> Driver(name = r.name.trim(), team = r.team?.trim().orEmpty(), ref = refs[i]) }
        val standings = rows.mapIndexed { i, r -> Standing(pos = r.pos, points = r.points.roundToInt(), driverRef = refs[i]) }
        return SourceResult.Ready(reading.throughRound, drivers, standings)
    }

    /** El nombre del archivo lleva la hora de subida en milisegundos: "20 sep". */
    private fun uploadedOn(url: String): String {
        val ms = IMAGE.matchEntire(url)?.groupValues?.get(1)?.toLongOrNull() ?: return "hace poco"
        val d = Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("America/Mexico_City")).toLocalDate()
        val meses = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
        return "el ${d.dayOfMonth} ${meses[d.monthValue - 1]}"
    }
}

// ————————————————————— Job —————————————————————

/** Estado de la ingesta de una categoría (admin). */
@Serializable
data class IngestStatus(
    val categoryId: String,
    val category: String,
    val championship: String,
    val season: String,
    val source: String,
    val param: String? = null,
    val enabled: Boolean,
    val credit: String? = null,
    /** Última fecha del calendario ya terminada (la que el job persigue). */
    val targetRound: Int? = null,
    val throughRound: Int? = null,
    val confirmedRound: Int? = null,
    val syncedAt: String? = null,
    val lastAttemptAt: String? = null,
    val lastNote: String? = null,
    val lastError: String? = null,
)

/** Configuración de la ingesta de una categoría (PUT). */
@Serializable
data class IngestConfig(val source: String, val param: String? = null, val enabled: Boolean = true)

@Serializable
data class SourceInfo(val id: String, val credit: String, val description: String, val paramHint: String? = null)

/** Resultado de una corrida (manual o del job). */
@Serializable
data class IngestRun(
    val categoryId: String,
    /** written | unchanged | not-ready | preview | idle | error */
    val status: String,
    val round: Int? = null,
    val detail: String? = null,
    /** Solo en dryRun: la tabla que se escribiría (número/nombre/equipo resueltos). */
    val rows: List<Standing>? = null,
)

/**
 * Ingesta AUTOMÁTICA de posiciones: al terminar el fin de semana de una fecha, pide la
 * tabla SOLO de las categorías afectadas (las que tienen una fecha recién terminada) a su
 * fuente, y la escribe si cambió (auditado + aviso al bus → las apps refrescan).
 *
 * Por categoría: mientras la fuente no refleje la última fecha terminada, reintenta con
 * espera creciente (1 h los primeros 3 días, 6 h hasta el día 10, luego diario); ya
 * reflejada, hace UNA pasada de confirmación 3 días después de la carrera (sanciones y
 * correcciones posteriores). Temporadas terminadas y confirmadas no vuelven a consultarse.
 */
object StandingsIngest {
    private val log = LoggerFactory.getLogger("StandingsIngest")
    private val lock = Mutex()
    private val CONFIRM_AFTER: Duration = Duration.ofDays(2) // tras el fin del día de carrera (≈72 h)

    fun init() = transaction { org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns(StandingsIngestT) }

    fun start(scope: CoroutineScope) {
        val every = Config.standingsEveryMin
        if (every <= 0) { log.info("ingesta de posiciones apagada (STANDINGS_INGEST_EVERY_MIN=0)"); return }
        scope.launch(Dispatchers.IO) {
            delay(30_000) // que el arranque termine primero
            while (true) {
                runCatching { tick() }.onFailure { log.warn("ingesta de posiciones: tick falló", it) }
                delay(every * 60_000)
            }
        }
    }

    /** Una vuelta del job: corre las categorías a las que les toca. */
    suspend fun tick(now: Instant = Instant.now()) = lock.withLock {
        val rows = transaction { StandingsIngestT.selectAll().where { StandingsIngestT.enabled eq true }.toList() }
        rows.forEach { r ->
            val categoryId = r[StandingsIngestT.categoryId]
            val target = transaction { targetRound(categoryId, now) } ?: return@forEach
            if (!due(r, target, now)) return@forEach
            val run = runOne(categoryId, dryRun = false, round = null, now = now)
            log.info("ingesta {} → {} (fecha {}): {}", categoryId, run.status, run.round, run.detail)
        }
    }

    /** Corrida manual (admin): fuerza la consulta aunque no toque. */
    suspend fun run(categoryId: String, dryRun: Boolean, round: Int?): IngestRun =
        lock.withLock { runOne(categoryId, dryRun, round, Instant.now()) }

    private fun due(r: org.jetbrains.exposed.sql.ResultRow, target: Pair<Int, LocalDate>, now: Instant): Boolean {
        val (round, date) = target
        val raceDayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val last = r[StandingsIngestT.lastAttemptAt]?.let(Instant::parse)
        if ((r[StandingsIngestT.throughRound] ?: 0) < round) {
            if (last == null) return true
            val sinceRace = Duration.between(raceDayEnd, now)
            val wait = when {
                sinceRace < Duration.ofDays(3) -> Duration.ofHours(1)
                sinceRace < Duration.ofDays(10) -> Duration.ofHours(6)
                else -> Duration.ofHours(24)
            }
            return Duration.between(last, now) >= wait
        }
        // Confirmación: si la fuente responde "aún no" (p. ej. ya va en una fecha posterior), se
        // reintenta cada 6 h y no en cada vuelta del job.
        return (r[StandingsIngestT.confirmedRound] ?: 0) < round && now >= raceDayEnd.plus(CONFIRM_AFTER) &&
            (last == null || Duration.between(last, now) >= Duration.ofHours(6))
    }

    /**
     * Última fecha TERMINADA del calendario de la categoría: su día de carrera ya pasó
     * completo en UTC (para las de América, horas después de la bandera a cuadros).
     */
    private fun targetRound(categoryId: String, now: Instant): Pair<Int, LocalDate>? {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        return RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }
            .map { it[RoundsT.number] to LocalDate.parse(it[RoundsT.date]) }
            .filter { it.second < today }
            .maxWithOrNull(compareBy<Pair<Int, LocalDate>>({ it.second }, { it.first }))
    }

    private fun context(categoryId: String, param: String?): SourceContext? = transaction {
        val cat = CategoriesT.selectAll().where { CategoriesT.id eq categoryId }.firstOrNull() ?: return@transaction null
        val ch = ChampionshipsT.selectAll().where { ChampionshipsT.id eq cat[CategoriesT.championshipId] }.firstOrNull()
            ?: return@transaction null
        SourceContext(
            season = ch[ChampionshipsT.season],
            seasonLabel = ch[ChampionshipsT.seasonLabel] ?: ch[ChampionshipsT.season].toString(),
            categoryName = cat[CategoriesT.name],
            param = param,
            rounds = RoundsT.selectAll().where { RoundsT.categoryId eq categoryId }.count().toInt(),
            categoryId = categoryId,
        )
    }

    private suspend fun runOne(categoryId: String, dryRun: Boolean, round: Int?, now: Instant): IngestRun {
        val cfg = transaction { StandingsIngestT.selectAll().where { StandingsIngestT.categoryId eq categoryId }.firstOrNull() }
            ?: return IngestRun(categoryId, "error", detail = "la categoría no tiene fuente de posiciones configurada")
        val source = StandingsSources.byId(cfg[StandingsIngestT.sourceId])
            ?: return IngestRun(categoryId, "error", detail = "fuente desconocida: ${cfg[StandingsIngestT.sourceId]}")
        val ctx = context(categoryId, cfg[StandingsIngestT.param])
            ?: return IngestRun(categoryId, "error", detail = "la categoría ya no existe")
        val target = round?.let { r -> transaction { RoundsT.selectAll().where { (RoundsT.categoryId eq categoryId) and (RoundsT.number eq r) }.firstOrNull() }?.let { r to LocalDate.parse(it[RoundsT.date]) } }
            ?: transaction { targetRound(categoryId, now) }
            ?: return IngestRun(categoryId, "idle", detail = "todavía no termina ninguna fecha del calendario")
        val (targetNumber, targetDate) = target

        fun saveState(block: (org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit) {
            if (dryRun) return
            transaction {
                StandingsIngestT.update({ StandingsIngestT.categoryId eq categoryId }) {
                    it[lastAttemptAt] = now.toString()
                    block(it)
                }
            }
        }

        val result = try {
            source.fetch(ctx, targetNumber, targetDate)
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}".take(1000)
            saveState { it[StandingsIngestT.lastError] = msg; it[StandingsIngestT.lastNote] = null }
            return IngestRun(categoryId, "error", targetNumber, msg)
        }
        return when (result) {
            is SourceResult.NotReady -> {
                saveState { it[StandingsIngestT.lastError] = null; it[StandingsIngestT.lastNote] = result.reason.take(300) }
                IngestRun(categoryId, "not-ready", targetNumber, result.reason)
            }
            is SourceResult.Ready -> {
                val problem = validate(result)
                if (problem != null) {
                    saveState { it[StandingsIngestT.lastError] = problem; it[StandingsIngestT.lastNote] = null }
                    return IngestRun(categoryId, "error", result.throughRound, problem)
                }
                val drivers = result.drivers.map(::normalizeDriver)
                if (dryRun) {
                    val byRef = drivers.associateBy { it.ref }
                    val rows = result.standings.sortedBy { it.pos }.map { s ->
                        val d = byRef[s.driverRef]
                        s.copy(driverName = d?.name.orEmpty(), team = d?.team.orEmpty(), numberText = d?.numberText, driverNumber = d?.number ?: 0)
                    }
                    return IngestRun(categoryId, "preview", result.throughRound, "${rows.size} posiciones tras la fecha ${result.throughRound}", rows)
                }
                // Fotos nuevas se bajan ANTES de escribir, para que la tabla salga completa.
                val photos = DriverPhotos.ensure(result.photos)
                val hash = hashOf(drivers, result.standings, photos)
                val changed = hash != cfg[StandingsIngestT.contentHash]
                if (changed) {
                    AdminRepository.replaceStandingsTable(categoryId, drivers, result.standings, photos)
                    AdminAudit.record(
                        AdminActor("ingesta:${source.id}", setOf(AdminScopes.ALL)), "replace", "standings", categoryId, false,
                        "posiciones de ${ctx.categoryName} ${ctx.seasonLabel} tras la fecha ${result.throughRound} (${result.standings.size} filas)",
                    )
                }
                val raceDayEnd = targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                val confirms = now >= raceDayEnd.plus(CONFIRM_AFTER) && result.throughRound >= targetNumber
                saveState {
                    it[StandingsIngestT.throughRound] = result.throughRound
                    it[StandingsIngestT.contentHash] = hash
                    if (changed) it[StandingsIngestT.syncedAt] = now.toString()
                    if (confirms) it[StandingsIngestT.confirmedRound] = result.throughRound
                    it[StandingsIngestT.lastError] = null
                    it[StandingsIngestT.lastNote] = if (changed) "actualizada tras la fecha ${result.throughRound}" else "sin cambios"
                }
                IngestRun(categoryId, if (changed) "written" else "unchanged", result.throughRound, "${result.standings.size} posiciones")
            }
        }
    }

    /** La fuente debe entregar una tabla coherente antes de reemplazar la guardada. */
    private fun validate(r: SourceResult.Ready): String? {
        if (r.standings.isEmpty()) return "la fuente devolvió una tabla vacía"
        val refs = r.drivers.map { normalizeDriver(it).ref!! }
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let { return "pilotos repetidos en la fuente: ${it.joinToString()}" }
        r.standings.groupBy { it.pos }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let { return "posiciones repetidas en la fuente: ${it.joinToString()}" }
        val missing = r.standings.mapNotNull { it.driverRef }.filter { it !in refs.toSet() } + r.standings.filter { it.driverRef == null }.map { "pos ${it.pos}" }
        if (missing.isNotEmpty()) return "posiciones sin piloto: ${missing.joinToString()}"
        return null
    }

    private fun hashOf(drivers: List<Driver>, standings: List<Standing>, photos: Map<String, String>): String {
        val text = drivers.joinToString("\n") { "${it.ref}|${it.numberText}|${it.name}|${it.team}|${photos[it.ref].orEmpty()}" } + "\n--\n" +
            standings.sortedBy { it.pos }.joinToString("\n") { "${it.pos}|${it.driverRef}|${it.points}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // —— Admin ——

    fun sources(): List<SourceInfo> = StandingsSources.all.map { SourceInfo(it.id, it.credit, it.description, it.paramHint) }

    fun status(): List<IngestStatus> = transaction {
        val now = Instant.now()
        StandingsIngestT.selectAll().toList().mapNotNull { r ->
            val categoryId = r[StandingsIngestT.categoryId]
            val cat = CategoriesT.selectAll().where { CategoriesT.id eq categoryId }.firstOrNull() ?: return@mapNotNull null
            val ch = ChampionshipsT.selectAll().where { ChampionshipsT.id eq cat[CategoriesT.championshipId] }.firstOrNull()
            IngestStatus(
                categoryId = categoryId,
                category = cat[CategoriesT.name],
                championship = ch?.get(ChampionshipsT.name).orEmpty(),
                season = ch?.let { it[ChampionshipsT.seasonLabel] ?: it[ChampionshipsT.season].toString() }.orEmpty(),
                source = r[StandingsIngestT.sourceId],
                param = r[StandingsIngestT.param],
                enabled = r[StandingsIngestT.enabled],
                credit = StandingsSources.credit(r[StandingsIngestT.sourceId]),
                targetRound = targetRound(categoryId, now)?.first,
                throughRound = r[StandingsIngestT.throughRound],
                confirmedRound = r[StandingsIngestT.confirmedRound],
                syncedAt = r[StandingsIngestT.syncedAt],
                lastAttemptAt = r[StandingsIngestT.lastAttemptAt],
                lastNote = r[StandingsIngestT.lastNote],
                lastError = r[StandingsIngestT.lastError],
            )
        }.sortedWith(compareBy({ it.championship }, { it.season }, { it.category }))
    }

    /** Alta/cambio de la fuente de una categoría. Cambiar de fuente reinicia el estado. */
    fun configure(categoryId: String, cfg: IngestConfig): ChangeSummary = transaction {
        val prev = StandingsIngestT.selectAll().where { StandingsIngestT.categoryId eq categoryId }.firstOrNull()
        if (prev == null) {
            StandingsIngestT.insert {
                it[StandingsIngestT.categoryId] = categoryId
                it[sourceId] = cfg.source; it[param] = cfg.param?.trim()?.ifBlank { null }; it[enabled] = cfg.enabled
            }
            return@transaction ChangeSummary("created", "standings-ingest", categoryId, detail = "fuente ${cfg.source}")
        }
        val sameSource = prev[StandingsIngestT.sourceId] == cfg.source && prev[StandingsIngestT.param] == cfg.param?.trim()?.ifBlank { null }
        StandingsIngestT.update({ StandingsIngestT.categoryId eq categoryId }) {
            it[sourceId] = cfg.source; it[param] = cfg.param?.trim()?.ifBlank { null }; it[enabled] = cfg.enabled
            if (!sameSource) {
                it[throughRound] = null; it[confirmedRound] = null; it[contentHash] = null
                it[lastAttemptAt] = null; it[lastError] = null; it[lastNote] = null
            }
        }
        ChangeSummary("updated", "standings-ingest", categoryId, detail = "fuente ${cfg.source}${if (cfg.enabled) "" else " (pausada)"}")
    }

    /** Quita la ingesta automática (las posiciones guardadas se quedan). */
    fun remove(categoryId: String): ChangeSummary = transaction {
        val n = StandingsIngestT.deleteWhere { StandingsIngestT.categoryId eq categoryId }
        if (n == 0) ChangeSummary("none", "standings-ingest", categoryId, detail = "no tenía fuente")
        else ChangeSummary("deleted", "standings-ingest", categoryId, detail = "las posiciones guardadas se conservan")
    }

    /** Id de la fuente configurada en la categoría (null = sin ingesta). */
    fun sourceOf(categoryId: String): String? = transaction {
        StandingsIngestT.selectAll().where { StandingsIngestT.categoryId eq categoryId }.firstOrNull()?.get(StandingsIngestT.sourceId)
    }

    /** Borrar la categoría borra su configuración de ingesta. */
    fun forgetCategory(categoryId: String) {
        StandingsIngestT.deleteWhere { StandingsIngestT.categoryId eq categoryId }
    }

}
