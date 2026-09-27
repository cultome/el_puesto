package com.alephri.elpuesto.backend

import com.alephri.elpuesto.model.Driver
import com.alephri.elpuesto.model.Standing
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Fórmula E leyendo las PÁGINAS PÚBLICAS de fiaformulae.com (decisión 2026-09-27: las posiciones se
 * obtienen aunque haya que leer el sitio). Su API (api.formula-e.pulselive.com) quedó con el
 * certificado vencido el 2026-09-24: no se usa ni se desactiva TLS. `robots.txt` solo prohíbe `/api/`.
 *
 * - **Temporada**: el sitio las numera (S1 = 2014-15), así que sale de la ETIQUETA, no del año:
 *   "2025-26" → 12, "2026-27" → 13. Se pide siempre `?season=N` explícito y se comprueba que la
 *   página sea de esa temporada (el selector de temporadas del flight y el título de la tabla).
 * - **Tres páginas por corrida** (una por segundo): el calendario `/en/results-and-standings?season=N`
 *   (en el flight de Next, `"rounds":[{roundNum, raceDate, href}]`), la tabla
 *   `…?tab=drivers&season=N` y la parrilla `/en/drivers` (solo para el número). Si la tabla no está
 *   lista, la tercera no se pide.
 * - **Calendario**: debe tener tantas fechas como el nuestro (si no, error: revisar calendario) y la
 *   fecha pedida se coteja por DÍA (±1) y por número; las dobles fechas son dos rondas en días seguidos.
 * - **Tabla**: el HTML (`tr[data-testid=standings-row-driver]`: posición, nombre con enlace
 *   `/en/drivers/{slug}`, equipo en MAYÚSCULAS, total) y, en el flight, `"gridPanel":` (la "Season
 *   Grid": cada piloto con `rounds:[{roundNum, points, position, pole, …}]`). Ambos deben coincidir
 *   fila por fila y cada total debe ser la suma de sus fechas; si no, la página se está regenerando →
 *   "aún no". Temporada sin tabla ("There are no standings for this season") → "aún no".
 * - **Fecha reflejada** = la mayor con resultado de CARRERA: alguien con posición 1 y al menos 10
 *   clasificados. No basta con que una fecha tenga puntos: la pole suma 3 puntos horas antes de la
 *   carrera (en las dobles fechas, la del domingo se reparte cuando la tabla aún es la del sábado). Si
 *   ya hay puntos de una fecha posterior a la reflejada, el sitio va adelante. Solo publica la tabla VIGENTE (`&round=`
 *   se ignora): una fecha vieja cae en [aheadOf] (no se reconstruyen tablas viejas con el grid).
 * - **Piloto**: `ref` = el slug de su página; los sustitutos sin página ("David Beckmann" en 2024-25)
 *   usan el slug de su nombre. Aparecen aunque tengan 0 puntos (decisión del usuario). Posición =
 *   orden de la tabla (el sitio ya la desempata), verificada contra el grid.
 * - **Número**: solo lo publica la parrilla ACTUAL (`/en/drivers`, "Season 12 - 2025/26"), así que se
 *   usa únicamente si esa página es de NUESTRA temporada; si no (temporada pasada o la siguiente antes
 *   de que el sitio cambie de parrilla), va vacío. Los sustitutos nunca tienen: comparten el número
 *   del titular y no están en la parrilla.
 * - **Equipo**: viene en MAYÚSCULAS; se escribe con [properCase] respetando siglas ("Porsche
 *   Formula E Team", "Jaguar TCS Racing", "DS Penske").
 * - **Fotos**: el grid enlaza la foto de cada piloto (`media.headshot` → referencia del flight → imagen
 *   de Contentful en images.ctfassets.net, PNG con transparencia). Contentful recorta la cara
 *   (`fit=thumb&f=face`), así que se pide la foto completa acotada y la cara aparte; no cuesta
 *   páginas extra y cada foto se baja una sola vez ([DriverPhotos]).
 */
object FiaFormulaESite : StandingsSource {
    override val id = "formulae-web"
    override val credit = "fiaformulae.com"
    override val description = "Fórmula E: lee las páginas públicas de fiaformulae.com (calendario, tabla con puntos por fecha y parrilla para el número). La temporada sale de la etiqueta (2025-26 = temporada 12 del sitio); sin parámetro."
    private const val BASE = "https://www.fiaformulae.com"

    /** Una fecha del calendario del sitio. */
    private data class SiteRound(val number: Int, val raceDate: LocalDate)

    /** Lo que la fila del grid dice de un piloto en una fecha. */
    private data class Cell(val round: Int, val points: Int, val position: Int?)

    /** Una fila del "Season Grid" (props del flight). */
    private data class GridRow(val name: String, val position: Int?, val points: Int?, val cells: List<Cell>, val media: JsonElement?)

    /** Una fila de la tabla HTML. */
    private data class TableRow(val pos: Int?, val name: String, val slug: String?, val team: String, val points: Int?)

    override suspend fun fetch(ctx: SourceContext, round: Int, raceDay: LocalDate): SourceResult {
        val season = siteSeason(ctx.seasonLabel)

        // 1. Calendario: mismo número de fechas y la pedida coincide por día y por número.
        val calendar = calendar(season)
        if (calendar.size != ctx.rounds) {
            error("$credit tiene ${calendar.size} fechas en la temporada $season y nuestro calendario ${ctx.rounds}: revisa el calendario antes de seguir")
        }
        val near = calendar.filter { abs(ChronoUnit.DAYS.between(it.raceDate, raceDay)) <= 1 }
        val match = near.minWithOrNull(compareBy<SiteRound>({ abs(ChronoUnit.DAYS.between(it.raceDate, raceDay)) }, { it.number != round }))
            ?: error("$credit no tiene una fecha que coincida con el $raceDay (fecha $round de nuestro calendario)")
        if (match.number != round) {
            error("la carrera del $raceDay es la fecha ${match.number} en $credit y la $round en nuestro calendario: revisa el calendario")
        }

        // 2. Tabla (HTML) + grid por fecha (flight), de la temporada pedida.
        delay(1_000) // cortesía con el sitio: una página por segundo
        val html = SourceHttp.getText("$BASE/en/results-and-standings?tab=drivers&season=$season")
        val flight = NextFlight.of(html)
        val shown = activeSeason(flight)
        if (shown != season) error("$credit no muestra la temporada $season (${ctx.seasonLabel}) sino la ${shown ?: "?"}")
        val doc = Jsoup.parse(html, BASE)
        val rowEls = doc.select("tr[data-testid=standings-row-driver]")
        if (rowEls.isEmpty()) {
            if ("There are no standings for this season" in doc.text()) {
                return SourceResult.NotReady("$credit aún no publica la tabla de pilotos de la temporada ${ctx.seasonLabel}")
            }
            error("$credit cambió su página de posiciones: no se encontró la tabla de pilotos")
        }
        val caption = rowEls[0].parents().firstOrNull { it.tagName() == "table" }?.selectFirst("caption")?.text()?.trim()
        if (caption != null && caption != "Driver Standings, season $season") error("la tabla de $credit no es de la temporada $season («$caption»)")
        val table = rowEls.mapIndexed { i, el -> tableRow(el) ?: error("$credit: no se pudo leer la fila ${i + 1} de la tabla") }
        val grid = gridRows(flight) ?: error("$credit cambió su página de posiciones: no trae los puntos por fecha (gridPanel)")

        // Fecha reflejada: la última con resultado de carrera (ganador y ≥ 10 clasificados).
        val byRound = grid.flatMap { it.cells }.groupBy { it.round }
        val through = byRound.filterValues { cells -> cells.any { it.position == 1 } && cells.count { it.position != null } >= 10 }.keys.maxOrNull() ?: 0
        if (through < round) {
            return SourceResult.NotReady(
                if (through == 0) "$credit aún no refleja ninguna carrera de la temporada ${ctx.seasonLabel}"
                else "$credit aún refleja hasta la fecha $through; falta la $round (la tabla se actualiza tras la carrera; se reintenta)",
            )
        }
        // El sitio solo publica la tabla VIGENTE: no sirve para etiquetar una fecha anterior, ni
        // cuando ya suma puntos de la siguiente (la pole se reparte antes de la carrera).
        if (through > round || byRound.any { (r, cells) -> r > through && cells.any { it.points != 0 } }) return aheadOf(credit, round)

        // Tabla a medias: el HTML y el grid se regeneran por separado.
        if (grid.size != table.size) {
            return SourceResult.NotReady("en $credit la tabla (${table.size} filas) y los puntos por fecha (${grid.size}) aún no coinciden; se reintenta")
        }
        table.forEachIndexed { i, t ->
            val g = grid[i]
            if (squash(t.name) != squash(g.name) || t.points != g.points || t.pos != g.position) {
                return SourceResult.NotReady("en $credit la fila ${i + 1} de la tabla (${t.name}, ${t.points}) no coincide con los puntos por fecha (${g.name}, ${g.points}); se reintenta")
            }
            val sum = g.cells.sumOf { it.points }
            if (sum != g.points) {
                return SourceResult.NotReady("en $credit la suma por fecha de ${g.name} ($sum) no da su total (${g.points}); se reintenta")
            }
        }
        val points = table.mapIndexed { i, t -> t.points ?: error("$credit: la fila ${i + 1} no trae puntos") }
        if (points.zipWithNext().any { (a, b) -> b > a }) error("la tabla de $credit no viene ordenada por puntos")

        // 3. Número: solo si la parrilla actual del sitio es de esta temporada.
        delay(1_000)
        val numbers = numbers(season)
        val refs = table.map { t -> t.slug ?: driverSlug(t.name) }
        val drivers = table.mapIndexed { i, t ->
            Driver(name = t.name, team = properCase(t.team, acronyms = true), numberText = t.slug?.let { numbers[it] }, ref = refs[i])
        }
        val standings = table.indices.map { i -> Standing(pos = i + 1, points = points[i], driverRef = refs[i]) }
        val photos = grid.indices.mapNotNull { i -> headshot(flight, grid[i].media)?.let { refs[i] to it } }.toMap()
        return SourceResult.Ready(round, drivers, standings, photos)
    }

    /** "2025-26" → 12: el sitio numera las temporadas desde 2014-15 (la 1). */
    private fun siteSeason(label: String): Int {
        val m = Regex("^(\\d{4})-(\\d{2}|\\d{4})$").matchEntire(label.trim())
            ?: error("la temporada de Fórmula E debe tener etiqueta tipo 2025-26 (tiene «$label»)")
        return (m.groupValues[1].toInt() - 2013).takeIf { it >= 1 } ?: error("temporada de Fórmula E fuera de rango: $label")
    }

    /** Fechas del sitio en la temporada [season], en orden. */
    private suspend fun calendar(season: Int): List<SiteRound> {
        val flight = NextFlight.of(SourceHttp.getText("$BASE/en/results-and-standings?season=$season"))
        val list = NextFlight.valuesAfter(flight, "\"rounds\":").filterIsInstance<JsonArray>()
            .firstOrNull { a -> a.isNotEmpty() && a.all { (it as? JsonObject)?.containsKey("raceDate") == true } }
            ?: error("$credit cambió su página de resultados: no se encontró el calendario de la temporada $season")
        return list.map { r ->
            // Cada fecha enlaza a sus resultados con la temporada: confirma que el calendario es el pedido.
            val linked = r.str("href")?.let { SEASON_PARAM.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (linked != season) error("el calendario de $credit no es de la temporada $season (${r.str("href")})")
            SiteRound(
                number = (r.jsonObject["roundNum"] as? JsonPrimitive)?.intOrNull ?: error("$credit: una fecha del calendario no trae número"),
                raceDate = r.str("raceDate")?.let(LocalDate::parse) ?: error("$credit: una fecha del calendario no trae día"),
            )
        }.sortedBy { it.number }
    }

    /** Temporada activa del selector de la página (`"seasonItems":[{id, active}]`). */
    private fun activeSeason(flight: String): Int? =
        NextFlight.valuesAfter(flight, "\"seasonItems\":").filterIsInstance<JsonArray>().firstOrNull()
            ?.firstOrNull { ((it as? JsonObject)?.get("active") as? JsonPrimitive)?.booleanOrNull == true }
            ?.str("id")?.toIntOrNull()

    private fun tableRow(el: Element): TableRow? {
        val label = el.selectFirst("[class*=standingsRow__nameLabel]") ?: return null
        val slug = label.selectFirst("a[href]")?.attr("href")?.let { DRIVER_PAGE.matchEntire(it)?.groupValues?.get(1) }
        val team = (el.selectFirst("[class*=standingsRow__teamLabel]") ?: el.selectFirst("[class*=standingsRow__teamLine]"))?.text().orEmpty()
        return TableRow(
            pos = el.selectFirst("th")?.text()?.trim()?.toIntOrNull(),
            name = squash(label.text()).ifEmpty { return null },
            slug = slug,
            team = team.trim(),
            points = el.selectFirst("td[class*=standingsRow__points]")?.text()?.trim()?.toIntOrNull(),
        )
    }

    /** Las filas de pilotos del "Season Grid", en su orden. null = la página ya no lo trae. */
    private fun gridRows(flight: String): List<GridRow>? {
        val panel = NextFlight.valuesAfter(flight, "\"gridPanel\":").firstOrNull() ?: return null
        val out = mutableListOf<GridRow>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> if (e.str("entity") == "driver" && e["rounds"] is JsonArray) {
                    out += GridRow(
                        name = e.str("name").orEmpty(),
                        position = (e["position"] as? JsonPrimitive)?.intOrNull,
                        points = (e["points"] as? JsonPrimitive)?.intOrNull,
                        cells = e.arr("rounds").mapNotNull { c ->
                            val o = c as? JsonObject ?: return@mapNotNull null
                            val n = (o["roundNum"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                            Cell(n, (o["points"] as? JsonPrimitive)?.intOrNull ?: 0, (o["position"] as? JsonPrimitive)?.intOrNull)
                        },
                        media = e["media"],
                    )
                } else e.values.forEach(::walk)
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(panel)
        return out
    }

    /** slug → número de coche, de la parrilla actual; vacío si esa parrilla no es de [season]. */
    private suspend fun numbers(season: Int): Map<String, String> {
        val html = SourceHttp.getText("$BASE/en/drivers")
        val shown = Regex("Season (\\d+) - \\d{4}/\\d{2}").findAll(html).map { it.groupValues[1].toInt() }.toSet()
        if (shown != setOf(season)) return emptyMap()
        return Jsoup.parse(html, BASE).select("a[href^=/en/drivers/]").mapNotNull { a ->
            val slug = DRIVER_PAGE.matchEntire(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val number = a.selectFirst("[class*=driverCard__number]")?.text()?.trim()?.takeIf { NUMBER.matches(it) } ?: return@mapNotNull null
            slug to number
        }.toMap()
    }

    /**
     * Foto del piloto: `media.headshot` es una referencia del flight ("$8f") a un elemento de React
     * cuyas props ("$90") son la imagen de Contentful. Solo imágenes de images.ctfassets.net.
     */
    private fun headshot(flight: String, media: JsonElement?): DriverPhoto? {
        var node: JsonElement = (media as? JsonObject)?.get("headshot") ?: return null
        repeat(4) {
            when (val cur = node) {
                is JsonObject -> return cur.str("url")?.let(::photoOf)
                is JsonArray -> node = cur.getOrNull(3) ?: return null // ["$", tipo, llave, props]
                is JsonPrimitive -> {
                    val ref = FLIGHT_REF.matchEntire(cur.contentOrNull.orEmpty())?.groupValues?.get(1) ?: return null
                    node = NextFlight.valuesAfter(flight, "\n$ref:").firstOrNull() ?: return null
                }
            }
        }
        return null
    }

    /** La foto completa acotada (sin agrandar) y la cara recortada por Contentful, ambas en PNG. */
    private fun photoOf(url: String): DriverPhoto? {
        val base = url.substringBefore('?')
        if (!PHOTO.matches(base)) return null
        return DriverPhoto(full = "$base?w=720&h=1440&fm=png", face = "$base?fit=thumb&f=face&w=256&h=256&fm=png")
    }

    /** Espacios repetidos (el HTML y el flight los escriben distinto). */
    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private val DRIVER_PAGE = Regex("^/en/drivers/([a-z0-9-]+)$")
    private val NUMBER = Regex("^\\d{1,3}$")
    private val SEASON_PARAM = Regex("[?&]season=(\\d+)(?:&|$)")
    private val FLIGHT_REF = Regex("^\\$([0-9a-f]+)$")
    private val PHOTO = Regex("^https://images\\.ctfassets\\.net/[A-Za-z0-9]+/[A-Za-z0-9]+/[0-9a-f]+/[A-Za-z0-9._%-]+\\.(?i:png|jpe?g|webp)$")
}
