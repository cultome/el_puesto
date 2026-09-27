# Fuentes de posiciones — familia F1 (F1, F2, F3, F1 Academy) · temporada 2026

Todo lo de abajo lo verifiqué con curl real el **2026-09-25 (~14:50–15:05 UTC)**. Las respuestas
de muestra están en `fuentes/f1-family/` (junto a este archivo).

| Categoría | Fuente recomendada | Llamadas por corrida | Estado hoy |
|---|---|---|---|
| Fórmula 1 | Jolpica (API pública, sucesora de Ergast) | 2–3 | R14 Madrid (13-sep) ya está; R15 Bakú se corre el 26-sep |
| Fórmula 2 | `api.formula1.com` v2 (API interna de fiaformula2.com) | 1 + N sesiones | R11 Madrid completa; R12 Bakú a medias (2 de 3 carreras, las de HOY ya están) |
| Fórmula 3 | `api.formula1.com` v2 (API interna de fiaformula3.com) | 1 + N sesiones | **Temporada terminada**: R9 Madrid (13-sep) fue la última |
| F1 Academy | `api.formula1.com` v1 `f2f3-fom-results` (API interna de f1academy.com) | 2 | R4 Zandvoort (23-ago) completa; R5 Austin 22–25 oct |

Las cuatro son **JSON, sin navegador y sin scraping de HTML**. Para F2/F3/F1A no hay alternativa
pública documentada: **Jolpica solo cubre F1** (`/ergast/f2|f3|f1a/...` → 404). Las páginas HTML
de F2/F3 son peores que la API: el payload Next.js que vienen embebido estaba **desfasado** (mostraba
9 fechas cuando la API ya trae 11 fechas y media), así que el HTML no sirve ni como respaldo.

Comparación con nuestro calendario (`data/campeonatos/2026/f1.csv`): las cuatro fuentes coinciden
exactamente con la numeración y las fechas del CSV. **Ojo, la premisa del encargo no coincide con el
CSV**: F1 no va en la ronda 17-18. La última terminada es la **R14** y la R15 (Bakú) es mañana. La
fecha del 2026-08-23 (R4) es de **F1 Academy**; en F2 esa ronda es la R11 y en F3 la R9.

---

## 1. Fórmula 1 — Jolpica

### Endpoint
- Tabla: `GET https://api.jolpi.ca/ergast/f1/{año}/{ronda}/driverStandings/`
  (también `.../{año}/last/driverStandings/` y `.../{año}/driverStandings/`).
- Comprobar que la ronda ya se corrió: `GET https://api.jolpi.ca/ergast/f1/{año}/{ronda}/results/`
  y, si el fin de semana tiene sprint, `.../{ronda}/sprint/`.
- Calendario: `GET https://api.jolpi.ca/ergast/f1/{año}/races/` (cada carrera trae la llave `Sprint`
  si tiene sprint).
- Headers: **ninguno**. Funciona con UA vacío, `Java-http-client/21` o `ktor-client`. Sin API key.
- Paginación: `limit` máximo 100, más `offset` (p. ej. `/2026/results/` tiene total=308 filas, así que son
  4 páginas). La tabla de pilotos (23 filas) cabe en una página con `?limit=100`.
- **Límites** (docs/rate_limits.md): 4 req/s en ráfaga y **500 req/h** sostenido, sin token; responden 429
  si se pasan. Cloudflare cachea 600 s (`cache-control: max-age=600`), así que no conviene reintentar
  más seguido que cada 10 min.
- **Términos** (TERMS.md, 2025-08-27): uso **no comercial**; datos bajo **CC BY-NC-SA 4.0** (hay que dar
  atribución); para uso comercial hay que escribirles a admin@jolpi.ca. Es un proyecto voluntario sin garantía
  de uptime.

### Estructura
`MRData.StandingsTable.{season, round}` y `StandingsLists[0].DriverStandings[]`, donde cada elemento trae:
- `position` (string, **puede faltar**), `positionText` (`"1"` o `"D"`/`"-"` para
  descalificados o excluidos; p. ej. 1997: Schumacher `D`), `points` (**string**, puede ser fraccionario:
  2021 `"395.5"`), `wins`.
- `Driver.{driverId, permanentNumber, code, givenName, familyName}`: el número es `permanentNumber`.
- `Constructors[]` son **todos** los equipos de la temporada en orden cronológico; el vigente es el
  **último** (p. ej. Lawson `["RB F1 Team","Red Bull"]`).
- Los nombres de equipo salen en formato Ergast ("RB F1 Team", "Alpine F1 Team", "Haas F1 Team"), no
  como en la transmisión, así que habría que mapearlos a nuestro catálogo.

### Cómo saber hasta qué ronda refleja la tabla (IMPORTANTE)
**`StandingsTable.round` NO sirve.** Hoy `/2026/driverStandings/` dice `round: "15"` porque ya
cargaron la **qualy** de Bakú, pero la carrera no se ha corrido: los puntos son idénticos a los de la
R14 (verificado: `/2026/15/driverStandings/` = `/2026/14/driverStandings/`; `/2026/15/results/` y
`/2026/15/sprint/` tienen total=0; `/2026/15/qualifying/` tiene 22 filas). Esto es lo que funciona:
1. Tomar la ronda objetivo R del calendario.
2. Esperar a que `/{año}/{R}/results/` tenga `MRData.total > 0` y, si es fin de semana con sprint, también
   `/{año}/{R}/sprint/`.
3. Entonces pedir `/{año}/{R}/driverStandings/`.

`/{año}/last/driverStandings/` sí apuntó a la R14 (la última con resultados de carrera), pero la
verificación explícita del paso 2 es más robusta.

### Rarezas 2026 (revisé las 308 filas de resultados, R1–R14)
- Los números son **únicos** en 2026 y `permanentNumber` coincide con `Results[].number` de todos
  (Norris usa el #1). En otras temporadas no es así (Verstappen 2022-25: permanente 33 y en carrera 1),
  por eso conviene sacar el número del **último resultado** (`Results[].number`) y no de `permanentNumber`.
- **Cambio de equipo a media temporada**: Lawson pasó de RB a Red Bull desde la R12, con el mismo #30.
- **Sustituto / reacomodo**: Hadjar corrió R1–R11 (Red Bull) y Tsunoda (#22, RB) R12–R14. La tabla trae
  **23 pilotos** para 22 autos.
- Puntos: en 2026 todos son enteros, pero el tipo es string y puede ser decimal (regla de medios puntos).
- Posición: en 2026 va de 1 a 23 sin huecos. Históricamente puede faltar `position`.

---

## 2. Fórmula 2 — `api.formula1.com` v2 (brand `f2`)

### Endpoints
El sitio nuevo de fiaformula2.com (Next.js) publica su config en `globalThis.cwpConfig` dentro del
HTML: `api.baseUrl = https://api.formula1.com`, `api.key.public = «apikey del HTML del sitio»`,
`brand.id = f2`.

- **Tabla**: `GET https://api.formula1.com/v2/core-fom-results/f2/driver-standings-breakdown?season=2026`
  (`season` = año, obligatorio: sin él da 400; con 2025 también funciona).
- **Resultados de una carrera**: `GET https://api.formula1.com/v2/core-fom-results/f2/race?meeting={meetingKey}&session={sessionNumber}`
- **Estado de las sesiones de una fecha**: `GET https://api.formula1.com/v2/core-fom-results/f2/meeting?meeting={meetingKey}`
- Header obligatorio: **`apikey: «apikey del HTML del sitio»`** (sin él da 401). La llave es **por
  categoría**: con la de F3 responde 403 "App not allowed to access series". No hace falta User-Agent.
- Los endpoints editoriales (`/v1/core-editorial-drivers/...`) dan 401 con esta llave, así que no hay listado de
  pilotos por aquí.
- No vi headers de rate limit. CloudFront con `cache-control: public, max-age=0`.

### Estructura de la tabla (`driver-standings-breakdown`)
```
{ season, meetings:[{meetingKey, meetingLocation, meetingStartDate, meetingEndDate,
                     raceSessions:[{description:"Sprint Race"|"Feature Race"|"Feature Race 1"…, sessionNumber}]}],
  standings:[{position:"1st", displayPosition:"1", championshipPoints:182 (int),
              driverReference:"RAFCAM01", driverTLA, driverFirstName, driverLastName,
              points:[[sprint, feature], …]   // una lista por fecha, alineada con meetings[] y raceSessions
             }] }
```
- **La tabla NO trae número ni equipo.** Para eso hay que cruzar `driverReference` con los resultados
  de carrera (`/race?...`), que sí traen `racingNumber` (string), `teamName`, `driverReference`,
  `driverFirstName/LastName`, `positionNumber`, `racePoints`, `completionStatusCode`
  (OK/DNF/DNS/DSQ) y **`version`** (`"Final"`/`"Provisional"`).
- `sum(points)` coincide con `championshipPoints` para todos los pilotos (los bonus de pole y vuelta rápida ya vienen
  sumados en cada carrera).
- Estrategia: guardar cada sesión con `version=Final` (ya no cambia) y pedir solo las nuevas; tomar el
  número y el equipo de la **última aparición** de cada `driverReference`.

### Cómo saber la ronda
- **Ronda = índice + 1 en `meetings[]`** (Melbourne=1, Miami=2… Madrid=11, Bakú=12), que coincide
  con el "ROUND N" del sitio y con nuestro CSV. **No** hay que usar `meetingNumber` del endpoint
  `/meeting`, que es la numeración de F1 (Bakú=15).
- Una fecha está **reflejada** cuando, para CADA sesión j de `meetings[i].raceSessions`, algún
  piloto tiene `points[i][j] != null`. Lo que no se ha corrido llega como `null`. Los pilotos que no
  corrieron **sí llevan 0**, no `null`.
- Para confirmar, se puede consultar `/meeting?meeting=…`: `meetingSessions[].state` es
  `completed`/`upcoming` por sesión, y los resultados traen `version`.
- **Fechas con 3 carreras**: Bakú 2026 tiene Sprint + Feature 1 + Feature 2 (compensan las fechas
  canceladas). El arreglo de esa fecha tiene 3 elementos. Hoy vale `[0, 12, null]`, es decir, está a medias y NO
  debe darse por completa.

### Actualización
Excelente: la Sprint (08:15 local) y la Feature 1 (14:15 local) de Bakú, corridas **hoy**, ya
aparecen en la tabla y en los resultados con `version: Final` a las 14:52 UTC.

### Rarezas (revisé las 24 sesiones corridas)
- **Número compartido**: el **#22 (Van Amersfoort)** lo usó **Nico Varrone en R1–R11** y **Hiyu
  Yamakoshi en R12**. Los dos están en la tabla (15 y 2 pts). **Esto rompe `Driver.number` como llave
  única.**
- **Filas basura**: en Silverstone (R7) cada carrera trae 24 filas; **2 no tienen `driverReference`
  ni nombre** (solo `racingNumber` 20/22), así que hay que descartar las filas sin `driverReference`.
- Posición: `displayPosition` es única del 1 al 23 (los empates en puntos, p. ej. 8.º y 9.º con 90, se
  desempatan por conteo). `position` viene como ordinal en texto ("1st"), así que conviene usar `displayPosition`.
- Puntos enteros en 2026.

### F2 por las páginas públicas — lo que usa el backend (fuente `fiaformula2-web`, 2026-09-27)

Decisión del usuario: páginas públicas, **sin** la API interna ni su llave. Verificado el
2026-09-27 tras Bakú (R12, 3 carreras):
- **Tabla**: `GET https://www.fiaformula2.com/en/standings/{año}/drivers` (HTML, ~300 KB). No hay
  que leer las celdas: el HTML trae el "flight" de Next.js (`self.__next_f.push([1,"…"])`) y ahí
  va, como props, el MISMO JSON que `driver-standings-breakdown` (`{"meetings":[…],"standings":[…]}`).
- **Número y equipo**: la página de cada fecha (`meetings[i].url` → `/en/racing/{año}/{sede}`)
  incrusta en `"meetingSessions":[…]` los `results` de su ÚLTIMA carrera (una sola sesión; las otras
  se piden desde el navegador). Se recorren de la fecha pedida hacia atrás hasta cubrir a todos:
  2 páginas en 2026 (Bakú + Madrid para Varrone y Shields). Una por segundo.
- **Frescura**: el "desfase" que vimos el 25 es la regeneración en segundo plano de Next.js
  (`x-nextjs-cache: STALE`, CloudFront `max-age=300`): la primera visita tras la carrera puede
  servir la versión vieja y dispara la nueva; minutos después sale fresca. El job lo absorbe
  ("aún no" → reintenta en 1 h).
- **Posición**: el 2026-09-27 el sitio publicaba `displayPosition` **2, 4, 6…** (el líder
  aparecía como "2" en la propia página). Se usa el ORDEN de la tabla (ya desempatada) y se exige
  que venga ordenada por puntos.
- **Solo la tabla vigente**: no hay tabla histórica por fecha, así que pedir una fecha anterior
  a la última terminada da error (no se etiqueta mal).
- La fecha se coteja por **día** (`meetingStartDate..meetingEndDate` ±1 contra nuestro día de
  carrera), no por índice. Sin `robots.txt` (404). User-Agent propio con la URL de la app.

---

## 3. Fórmula 3 — `api.formula1.com` v2 (brand `f3`)

**Lo que usa el backend (2026-09-27)**: la fuente `fiaformula3-web`, el MISMO lector de páginas
públicas que F2 (ver "F2 por las páginas públicas"), con `https://www.fiaformula3.com`. Verificado
con la temporada terminada: 34 pilotos, todos con número y equipo (los compartidos quedan con su
última aparición; Heuzenroeder solo corrió la R1, así que se recorren las 9 páginas ≈ 10 s). La
posición sale del orden: Hanna queda 34.º donde el sitio dice 35 (el hueco es de un piloto que
el sitio no lista).

Es la misma API y la misma forma que F2, con **otra llave** (del `cwpConfig` de fiaformula3.com):
- `GET https://api.formula1.com/v2/core-fom-results/f3/driver-standings-breakdown?season=2026`
- `GET https://api.formula1.com/v2/core-fom-results/f3/race?meeting={meetingKey}&session={n}`
- Header **`apikey: «apikey del HTML del sitio»`**

Cómo saber la ronda: igual que en F2 (índice + 1 en `meetings[]`; Madrid = R9 con 3 carreras).
**La temporada 2026 ya terminó** (9 fechas, la última el 13-sep). El `seasonState` de los resultados
todavía dice `DURING-SEASON`, así que no hay que usarlo para decidir.

### Rarezas (revisé las 19 sesiones)
- **Cuatro números compartidos** por sustituciones:
  - #20 PREMA: Louis Sharp (R1–R7) y Alex Powell (R8–R9).
  - #26 AIX: Benavides (R1–R2), Escotto (R3, R5, R6, R8) y Hanna (R4, R7).
  - #19 Rodin: Christian Ho (R1–R8) y Niccolò Maccagnani (R9).
  - #3 Campos: Heuzenroeder (R1) y Ernesto Rivera (R2–R9).
- **Hueco en la tabla**: 34 filas con posiciones 1…33 y 35 (**falta la 34**). Maccagnani (NICMAC01) corrió
  R9 pero **no aparece en la tabla**; su fila de la Sprint de Madrid es la única con
  `version: "Provisional"`. No hay que suponer posiciones contiguas.
- Puntos enteros; `sum(points) == championshipPoints` en todos.

---

## 4. F1 Academy — `api.formula1.com` v1 `f2f3-fom-results`

f1academy.com todavía usa el Next.js "viejo" (pages router). Su `getInitialProps` llama a la API
con una llave embebida en el chunk JS
`/_next/static/chunks/a4479e9ec4e00e55dc512a82006c6f8552549134.*.js`.

### Endpoints
- **Temporadas** (para convertir año en SeasonId): `GET https://api.formula1.com/v1/f2f3-fom-results/seasons?website=fa`
  devuelve `[{SeasonId:4, SeasonName:"2026 F1 Academy", …}, {SeasonId:3, "2025 …"}, …]`.
- **Tabla**: `GET https://api.formula1.com/v1/f2f3-fom-results/driverstandings?website=fa&season=4`
  (**`season` es el SeasonId, no el año**: `season=2026` responde `{}` con HTTP 200, un fallo silencioso).
- **Calendario con estado**: `GET https://api.formula1.com/v1/f2f3-fom-results/races?website=fa&season=4`
- **Detalle de una fecha**: `GET https://api.formula1.com/v1/f2f3-fom-results/races/{RaceId}?website=fa`
- Header **`apikey: «apikey del HTML del sitio»`** (la llave decide la serie; `website=` se ignora).
- **La respuesta SIEMPRE viene gzip** (`content-encoding: gzip`), aunque se mande
  `Accept-Encoding: identity`. En JVM hay que descomprimir a mano (`GZIPInputStream`) o activar
  ContentEncoding en Ktor. `java.net.http` **no** descomprime solo.

### Estructura de la tabla
```
{ Season:"2026 F1 Academy", SeasonId:4,
  SeasonRaces:[{RaceId, CircuitShortName, RaceStartDate, RaceEndDate, Provisional,
                Sessions:[{SessionId, SessionName:"Opening Race"|"Reverse Grid Race"|"Feature Race", SessionShortName}]}],
  Standings:[{Position:int, CarNumber:int|null, DriverID:int, TLA, DisplayName, FullName,
              TeamName, TotalPoints:int, RacePoints:[[…por sesión…] por fecha]}] }
```
Aquí la tabla **sí** trae número (`CarNumber`) y equipo (`TeamName`), así que basta una llamada.

### Cómo saber la ronda
- `races?season=4` trae `Races[].{RoundNumber, RaceEndDate, State}` con `State` =
  `POST-RACE`/`PRE-RACE`. Hoy R1–R4 están en `POST-RACE` y R5–R6 en `PRE-RACE`.
- En la tabla, una fecha está reflejada si para cada sesión j de `SeasonRaces[i].Sessions` algún piloto tiene
  `RacePoints[i][j] != null`. **Aquí los pilotos que no corrieron llevan `null`**, no 0 como en F2/F3.
- Confirmación fina en `races/{RaceId}`: `SessionResults[].{SessionType:"RESULT", SessionResultsAvailable,
  ResultsAreOfficial, Unconfirmed}`.
- Algunas fechas tienen **3 carreras** (Montreal y Austin: Opening + Reverse Grid + Feature).

### Rarezas
- **Piloto sin número ni equipo**: `Alexia Danielsson (WCD)` con `CarNumber: null`, `TeamName: ""`,
  0 pts y todo `null`. Es un wild card anunciado que aún no corre. Hay que omitir las filas con `CarNumber` nulo.
- **Wild cards** (Hitech, "(WCD)" pegado al `FullName`): Bättig #6, Fisher #77, Florescu #15 y Shi #24
  corren fechas sueltas. Cada una tiene su propio número y no encontré números compartidos en los resultados de R1–R4.
- Nombres sucios: dobles espacios ("Alisha  Palmowski") y espacios al final en los resultados
  ("Nina "), así que hay que normalizar espacios y quitar el sufijo " (WCD)".
- Hay empates en puntos (49/49, 35/35, 22/22), pero `Position` es única (se desempata por conteo).
- Puntos enteros.

---

## 5. Riesgos transversales

1. **El modelo `Driver(number único)` + `Standing(driverNumber)` no aguanta F2/F3**: en 2026 hay 1
   número compartido en F2 y 4 en F3, y los dos pilotos de cada uno tienen fila en la tabla, la mayoría con
   puntos. Opciones:
   (a) **cambiar la llave del piloto a un id externo estable** (Jolpica `driverId`, F2/F3
   `driverReference`, F1A `DriverID`) y dejar el número como atributo no único. Es lo recomendable.
   (b) Mantener el número como llave, dárselo al último que lo usó y **perder** a los anteriores (Varrone tiene 15 pts): se
   pierde información.
2. **Puntos fraccionarios** posibles en F1 (Jolpica usa strings, "395.5" en 2021) y en teoría en F2/F3
   (reglamento de medios puntos). Con `points: Int` hay que redondear o rechazar con alerta.
3. **Posiciones no contiguas** (F3 2026 no tiene la 34) y **posición ausente** en Jolpica
   (descalificados). La unicidad sí se cumple en 2026 para las cuatro.
4. **Legal y estabilidad de `api.formula1.com`**: está sin documentar y sus llaves son "públicas" solo porque van
   en el frontend. Los T&C de fiaformula2.com permiten uso **personal y no comercial** y prohíben "extract or
   commercially exploit" el contenido y hacerle ingeniería inversa, así que queda en **zona gris**. Además, las
   llaves pueden rotar: si se recibe 401 "Invalid ApiKey", conviene que el job **redescubra la llave** (F2/F3: regex sobre
   `"key":{"public":"…"}` del HTML de `/en/standings/{año}/drivers`; F1A: bajar los `<script src="/_next/static/chunks/…">` del HTML de
   `/Racing-Series/Standings/Driver` y buscar la cadena de 32 caracteres que devuelve la función junto a
   `"https://api.formula1.com/"`) y avise al admin.
   Jolpica es CC BY-NC-SA 4.0 (no comercial, con atribución).
5. Respaldo si cae `api.formula1.com`: no encontré otra fuente JSON pública para F2/F3/F1A. Wikipedia
   (MediaWiki API, wikitext) es frágil y tardía: la página de F2 2026 no se editaba desde 2025-11. Lo que
   queda es la **captura manual en el admin**, que ya existe. OpenF1 `championship_drivers` respondió 404 con
   `session_key=latest` (la última sesión es una qualy), así que tampoco sirve como respaldo automático simple.

## Archivos de muestra (`fuentes/f1-family/`)
- `jolpica-f1-2026-driverStandings.json`: tabla por defecto (dice round 15, ver la trampa arriba).
- `jolpica-f1-2026-round15-driverStandings.json` y `jolpica-f1-2026-last-driverStandings.json` (round 14).
- `jolpica-f1-2026-races.json` (calendario) y `jolpica-f1-last-results.json` (resultados de la R14).
- `f2-2026-driver-standings-breakdown.json`, `f2-2026-meeting-baku.json` (estados por sesión),
  `f2-2026-race-madrid-feature.json`, `f2-2026-race-baku-feature1.json`,
  `f2-2026-race-silverstone-sprint.json` (con las 2 filas sin `driverReference`).
- `f3-2026-driver-standings-breakdown.json` (con el hueco en la 34) y `f3-2026-race-madrid-feature2.json`.
- `f1academy-seasons.json`, `f1academy-2026-driverstandings.json`, `f1academy-2026-races.json`,
  `f1academy-2026-race-26-zandvoort.json` (ya descomprimidos).
