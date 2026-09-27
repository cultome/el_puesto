# WRC 2026 — fuente para la ingesta automática de posiciones (pilotos)

## Lo que usa el backend (fuente `wrc-feed`, 2026-09-27)

`backend/…/WrcSource.kt` (`WrcFeed`). El usuario aprobó los JSON públicos que descarga la página de
standings de wrc.com (`p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api`, sin llave ni reto;
www.wrc.com no se toca). Parámetro de la ingesta = categoría (`wrc | wrc2 | wrc3 | jwrc`); crédito
`wrc.com`; ≤ 1 petición por segundo.
1. `seasons.json` (nombre "World Rally Championship" + año) → `season-detail.json` → id de la tabla
   por NOMBRE exacto del campeonato de pilotos y rallies con fechas. Nuestra fecha se coteja por
   **día** (±1) contra `startDate..finishDate` (la fuente aún lista Arabia y 14 rondas para todas).
2. `championship-overall-results.json` → reflejada si el rally tiene resultados que no son
   `DidNotEnter`, todos `Published`, no está en `live-events.json` y, en WRC, sus puntos suman 130
   (si no, "aún no"). Si un rally posterior ya tiene resultados: "ya va en una fecha posterior".
3. `championship-detail.json` (nombres, por `championshipEntryId`). Filas con 0 puntos fuera;
   posición = `overallPosition` (única); `ref` = `personId`; apellido de MAYÚSCULAS a normal
   (`properCase` + "Mc": McErlean).
4. Número y equipo de la inscripción MÁS RECIENTE (`events/{id}.json` → rally principal →
   `entries.json`, rallies hacia atrás; en memoria del proceso). Equipo: WRC = `fieldFive` de la
   tabla; WRC2/WRC3/Junior = el de la inscripción; si falta, es "None" o es el propio nombre del
   piloto (privados), la marca de la inscripción.
5. Fotos: `driverImageUrl` (img.redbull.com, el Cloudinary de Red Bull; original de 600 px + cara con
   `g_face`), sin peticiones extra a la API.
- Peticiones por corrida: 5; la primera tras un despliegue, + 2 por rally recorrido (29 como máximo
  para toda la temporada; las inscripciones sirven a las 4 categorías).
- Verificado el 2026-09-27 contra una copia de la base, tras Chile (fecha 12; Junior WRC fecha 5,
  su final): WRC 32 pilotos (Evans #33 Toyota Gazoo Racing WRT 230), WRC2 58 (Virves #20 Toksport WRT
  2 117), WRC3 30 (Fontana #54 Ford 117), Junior WRC 7 (Türkkan #52 Castrol Ford Team Türkiye 130).
  Fotos: WRC 16 de 32, WRC2 3 de 58, WRC3 y Junior 0 (la fuente no las publica).
- Rarezas: Zaldivar sale como "Fau" (así en la tabla) y su `personId` cambió en las inscripciones
  de los últimos rallies (se queda el número de su última inscripción con el id de la tabla); un
  piloto sin nombre de pila ("Flandy"); si un rally del WRC se acorta y reparte menos de 130 puntos,
  la fecha se queda en "aún no" y hay que revisarla a mano.

## Por la página pública (investigado 2026-09-27; prototipo en `prototipos/wrc/`)

`https://www.wrc.com/en/results-and-standings/championship-standings` no trae la tabla: la carga
un script con `fetch()` pelón (sin llave, sin reto) a los JSON de Red Bull — mismo caso que
NASCAR. www.wrc.com (Akamai) da 403 a `Java-http-client` pero 200 con nuestro User-Agent.
Base `https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api` (Cloudflare, sin reto, caché 30 s):
1. `seasons.json` → id de la temporada (`name == "World Rally Championship"`, `year == 2026` → 47).
2. `season-detail.json?seasonId=47` → ids de las tablas por NOMBRE (WRC 333 "FIA World Rally
   Championship for Drivers", WRC2 337, WRC3 344, Junior WRC 346; cambian cada año) y rallies
   con `startDate`/`finishDate`.
3. `championship-overall-results.json?championshipId=C&seasonId=47` → posición (ya desempatada),
   puntos (incluyen Power Stage/Super Sunday) y resultado por rally.
4. `championship-detail.json?championshipId=C&seasonId=47` → nombres (apellido en MAYÚSCULAS),
   marca, equipo y `personId` (unir por `championshipEntryId`). `personId` NO es estable entre
   temporadas (Ogier 670 → 21334), sí dentro de una.
5. Número/equipo por rally: `events/<eventId>.json` → `events/<eventId>/rallies/<rallyId>/entries.json`
   (la inscripción más reciente; los números se repiten entre pilotos).
- Calendario: el sitio aún lista Arabia (cancelada): 14 contra nuestros 13 → emparejar por DÍA.
- Fecha reflejada: algún `roundResult` del rally con `status != "DidNotEnter"`, todo `Published`,
  no en `live-events.json`, y en WRC que los puntos del rally sumen 130.
- Peticiones: 5 por categoría sin número; ~44 la primera vez con números (memoria después).
- Términos (Red Bull/WRC): prohíben el scraping; uso personal.
- Valores propuestos al usuario (pendiente su visto bueno): omitir filas con 0 puntos (WRC2: 68
  de 126), número y equipo de la última inscripción, apellidos a formato normal, fuente
  `wrc-feed` con parámetro `wrc|wrc2|wrc3|jwrc`.


Investigado y verificado con curl el **2026-09-25**. Muestras en `fuentes/wrc/`.

## TL;DR

- **Sí hay fuente JSON viable**: la API interna que usa el widget de standings de wrc.com,
  `https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api` (Express en DigitalOcean detrás
  de Cloudflare). **No pide auth ni cabeceras** (probado sin User-Agent, con "Ktor client" y
  con "Java-http-client": 200 en todos). `cache-control: public, max-age=30`.
- `api.wrc.com` (el de RallyDataJunkie y los tutoriales) **ya no existe** (NXDOMAIN).
  `www.wrc.com` está detrás de Akamai y da 403 a curl pelón (solo pasa con cabeceras de
  navegador) — no lo necesitamos.
- **Hoy refleja la ronda 12 (Rally Chile Bío Bío)** en las 4 categorías. Junior WRC ya
  terminó (5/5; Chile fue su final con puntos dobles). Cifras cruzadas con Wikipedia
  (Evans 230, Pajari 213).
- **Problema serio de términos**: los Terms of Use de wrc.com (policies.redbull.com, versión
  2026-08-26) prohíben expresamente *"use any means to spider, harvest, scrape, crawl, or
  participate in the use of software … to collect data from the Properties"* y dicen que el
  contenido es *"for your personal use only … cannot use it for any commercial … purposes"*.
  La API es no documentada y puede cambiar sin aviso (su antecesora ya murió). Texto
  completo en `wrc/terminos-wrc.txt`. **Decisión de producto pendiente** (ver Alternativas).
- **Los números de coche NO sirven como llave en WRC2/WRC3/JWRC**: de los pilotos con ≥2
  salidas, **0** conservan su número (48 en WRC2, 19 en WRC3, 7 en JWRC). La tabla de
  standings ni siquiera trae número; hay que sacarlo de las listas de inscritos por rally.

## 1. Endpoints (base `B = https://p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api`)

| Paso | URL | Para qué |
|---|---|---|
| 1 | `GET B/seasons.json` | `[{seasonId, name, year}]`. Filtrar `name == "World Rally Championship"` y `year`. 2026 → **`seasonId=47`** (2025 = 34; ERC 2026 = 48). |
| 2 | `GET B/season-detail.json?seasonId=47` | `championships[]` (ids de cada tabla) + `seasonRounds[]` (`order`, `eventId`, `event.name/startDate/finishDate/slug`). |
| 3 | `GET B/championship-overall-results.json?championshipId=<id>&seasonId=47` | **La tabla**: `entryResults[]` con `overallPosition`, `overallPoints` y `roundResults[]`. **Sin nombres.** |
| 4 | `GET B/championship-detail.json?championshipId=<id>&seasonId=47` | `championshipEntries[]`: nombre/país/marca/equipo por `championshipEntryId` (join con el paso 3). |
| (op) | `GET B/live-events.json` | `[]` si no hay rally en vivo (hoy `[]`). Sirve para no dar por buena una tabla mientras corre un rally. |
| (op) | `GET B/events/<eventId>.json` → `rallies[isMain].rallyId`; `GET B/events/<eventId>/rallies/<rallyId>/entries.json[?championshipId=<id>]` | Lista de inscritos del rally: aquí sí viene el **número** (`identifier`, string), copiloto, `entrant.name`, `group.name` (Rally1/Rally2/Rally3). |
| (op) | `GET B/championship-live-results.json?championshipId=&seasonId=&eventId=&rallyId=&stageId=` | Standings **provisionales** durante el rally (trae nombres inline). No usar para la ingesta final. |

Faltar `seasonId` o `championshipId` → `400 {"error":"Missing required query parameters: championshipId and seasonId"}`.

**Ids de championship 2026 (cambian CADA temporada — descubrirlos por nombre en el paso 2):**

| Categoría app | championshipId | `name` exacto en la fuente | `type` |
|---|---|---|---|
| WRC (Rally1) | **333** | FIA World Rally Championship for Drivers | Drivers |
| WRC2 | **337** | FIA WRC2 Championship for Drivers | DriversWithEntrant |
| WRC3 | **344** | FIA WRC3 Championship for Drivers | DriversWithEntrant |
| Junior WRC | **346** | FIA Junior WRC Championship for Drivers | DriversWithEntrant |

(Co-drivers: 334/338/345/347. También existen WRC2 Challenger 339, Masters Cup 342, equipos/marcas 335/336/341.)
Los nombres fueron idénticos en 2025 (287/291/298/300), así que el match por nombre es estable.

## 2. Estructura y mapeo al modelo

`championship-overall-results` → `entryResults[i]`:
- `overallPosition` (int, 1..N contiguo, único) → `Standing.pos`
- `overallPoints` (int) → `Standing.points`
- `championshipEntryId` → join con `championship-detail.championshipEntries[]`
- `roundResults[]`: `{eventId, position (string: "1".."N", "R"=retirado, "D"=no salió, "F"=fuerza mayor, "-"=no inscrito), totalPoints, pointsBreakdown ("25 + 5 + 4" = rally + Super Sunday + Power Stage en WRC; "50+2" en la final JWRC), dropped, status, publishedStatus, entryId (SIEMPRE null)}`

`championship-detail` → `championshipEntries[j]` (campos genéricos cuyo significado da `fieldXDescription`):
- `fieldOne` = nombre, `fieldTwo` = APELLIDO (en mayúsculas), `fieldThree` = país ISO3
- `fieldFour` = marca (Toyota, Hyundai, Skoda, Lancia, Ford, Citroën…)
- `fieldFive` = en WRC "ManufacturerFull" (equipo real, p. ej. `TOYOTA GAZOO RACING WRT`); en
  WRC2/WRC3/JWRC "Entrant" pero **viene el string literal `"None"` en el 100 %** (126/36/8).
- `personId` (int, estable entre temporadas y entre categorías) — **la llave buena**.
- `driverProfileUrl`, `driverImageUrl` (solo en las tablas de WRC/WRC2).

Propuesta: `name = fieldOne + " " + titlecase(fieldTwo)`; `team = fieldFive` si no es
vacío/`"None"`, si no `fieldFour` (marca). Si se quiere el equipo real de WRC2/3 hay que
tomar `entrant.name` de la última inscripción del piloto (p. ej. `TOKSPORT WRT 2`).

**Copiloto**: NO viene en la tabla de pilotos (es otro campeonato). Solo en las listas de
inscritos por rally (`codriver`), y puede cambiar entre rallies.

## 3. Cómo saber hasta qué ronda refleja la tabla

Los `roundResults` de un rally **solo aparecen cuando la fuente lo procesa**: hoy existen para
las rondas 1–12 y **no hay ninguno** de Cerdeña (13) ni Arabia (14). Una vez procesado, cada
piloto de la tabla tiene una fila para ese evento (los no inscritos con `DidNotEnter`).

Regla para el job (por categoría):
1. El job sabe qué rally acaba de terminar (por fechas de su propio calendario) → lo mapea a
   `eventId` vía `season-detail.seasonRounds` (match por fecha `startDate/finishDate`, o por
   `slug` `26-wrc-sardegna`).
2. La tabla está al día si existe algún `roundResult` con ese `eventId`, `status !=
   "DidNotEnter"` y `publishedStatus == "Published"` (enum del front: Preview/Published/
   Retracted; hoy todo es Published).
3. Opcional: exigir además `live-events.json == []`.
4. Reintentar hasta cumplirse. "Ronda reflejada" = máximo `order` de los eventos con
   resultados publicados (hoy = 12 en las 4 tablas).

Ojo: el sitio avisa que *"standings become final after the rally, once the results have been
approved by the FIA"* — conviene re-ingestar también al día siguiente (sanciones/apelaciones).
No pude medir cuánto tarda en publicarse tras el rally (no hay rally en curso hasta el 1-oct).

## 4. Rarezas vs. el modelo `Driver(number, name, team)` / `Standing(pos, driverNumber, points)`

Datos contados sobre la temporada 2026 (rondas 1-12) — detalle en `wrc/analisis-numeros.txt`:

| Tabla | Pilotos | con 0 pts | ≥2 salidas | …con número FIJO | números reusados por ≥2 pilotos | pts fraccionarios | empates de puntos |
|---|---|---|---|---|---|---|---|
| WRC (333) | 36 | 4 | 24 | 14 (los de Rally1) | 10 (#20–31) | 0 | sí (p. ej. 4 pilotos con 2) |
| WRC2 (337) | 126 | **68** | 48 | **0** | 33 | 0 | sí (68 con 0, 7 con 6…) |
| WRC3 (344) | 36 | 6 | 19 | **0** | 21 | 0 | sí (5 con 10…) |
| Junior WRC (346) | 8 | 1 | 7 | **0** | 4 | 0 | no |

- **Número de coche**: no está en la tabla (`entryId` null). Hay que sacarlo de
  `entries.json` de cada rally (`identifier`, string; hoy todos numéricos) y unir por
  `personId`. En WRC2/3/JWRC cambia en cada rally (Virves: 21, 27, 23, 25, 21, 20). Usar "el
  último número" como llave da colisiones (WRC2: #32, #36, #43, #44, #46 los comparten hasta 6
  pilotos). Además la tabla WRC incluye pilotos Rally2 que sumaron en la general (con número
  variable) y un piloto (Jürgenson) con 0 puntos y ninguna salida registrada.
- **Posiciones**: únicas y contiguas 1..N (la fuente ya desempata); pero los 68 pilotos de
  WRC2 con 0 puntos tienen posiciones 59..126 de orden poco significativo.
- **Puntos**: enteros siempre (`int` en JSON). `dropped` = false en todo (no hay descartes hoy).
- **Calendario de la fuente desactualizado**: `season-detail` y `championshipRounds` siguen
  listando **Rally Saudi Arabia (order 14, 12–15 nov, eventId 648)** aunque se canceló el
  2026-09-17 (ver `data/campeonatos/2026/FUENTES.md`). Y `championshipRounds` lista las 14
  rondas para TODAS las categorías (también JWRC, que solo tuvo 5). → No usar la fuente para
  "fecha N de M"; mapear por `eventId`/fecha contra el calendario propio.

**Propuesta de mapeo** (el modelo exige número único por categoría y `Standing` lo referencia):
- Opción A (recomendada, sin tocar el wire): para las 4 categorías de rally,
  `Driver.number = personId` de la fuente (int, único y estable toda la temporada — incluso
  entre temporadas) y `Standing.driverNumber = personId`. La UI de rally NO debe mostrar ese
  número (marca por categoría/serie "sin número fijo"), o mostrar el número de la última
  inscripción como texto informativo.
- Opción B (más limpia, cambia el modelo): `Driver.number` = 0/nullable + `Driver.key`
  (`"wrc:21336"`) y `Standing.driverKey`; `number = 0` sin llave alterna NO funciona porque
  todos colisionarían en `Standing.driverNumber`.
- En WRC (Rally1) los titulares sí tienen número fijo, pero la tabla mezcla pilotos Rally2 →
  aplicar la misma regla a toda la tabla para no tener dos esquemas.
- Sugerencia: descartar filas con `overallPoints == 0` (sobre todo WRC2: 68 de 126) o
  mostrarlas al final sin posición.

## 5. Frescura (hoy, 2026-09-25)

- Las 4 tablas reflejan **Rally Chile (ronda 12, terminó el 13-sep)**; `live-events` vacío;
  Cerdeña (1–4 oct) aún sin inscritos en la API (`entries.json` = `[]`).
- Líderes: WRC Evans 230 · WRC2 Virves 117 · WRC3 Fontana 117 · JWRC Türkkan 130 (campeón).

## Alternativas (si los términos de wrc.com pesan)

1. **Wikipedia (MediaWiki API, CC BY-SA 4.0 — permitido con atribución)**:
   `https://en.wikipedia.org/w/api.php?action=parse&page=2026_WRC2_Championship&prop=wikitext&format=json&formatversion=2`
   (páginas `2026_World_Rally_Championship`, `2026_WRC2_Championship`,
   `2026_WRC3_Championship`, `2026_Junior_WRC_Championship`; secciones "FIA … Championship
   for Drivers"). WRC, WRC2 y WRC3 ya traen la columna de Chile (JWRC no lo revisé; últimas ediciones 15–25 sep). Contras: es
   wikitext de tabla con formato inconsistente ("!22" vs "! 22"), editado por voluntarios
   (retraso variable, vandalismo posible), sin número de coche. Detección de ronda: la
   columna del rally ya tiene celdas. Es JSON solo como sobre; el contenido hay que parsearlo.
2. **Pedir permiso/licencia a WRC Promoter** (contacto en wrc.com/en/misc/contact-us) para usar
   la API de standings; o captura manual por admin/agente (el MCP ya existe) una vez por rally
   — con 1 rally cada 2-3 semanas y 4 tablas es poco trabajo.
