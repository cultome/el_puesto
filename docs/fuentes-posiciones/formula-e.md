# Fuente de posiciones — Fórmula E (verificado con curl el 2026-09-25)

## Lo que usa el backend (fuente `formulae-web`, 2026-09-27)

`FiaFormulaESite` en `backend/.../FormulaESource.kt`, sin parámetro, crédito `fiaformulae.com`. La
API de pulselive NO se usa (certificado vencido; no se desactiva TLS). Temporada del sitio = año
inicial de la etiqueta − 2013 (2025-26 → 12, 2026-27 → 13), siempre con `?season=N` explícito.
- **3 páginas por corrida**, una por segundo: calendario (`/en/results-and-standings?season=N`,
  flight `"rounds":[{roundNum, raceDate, href}]`), tabla (`…?tab=drivers&season=N`: HTML
  `tr[data-testid=standings-row-driver]` + flight `"gridPanel":` con los puntos por fecha) y parrilla
  (`/en/drivers`, solo para el número). Si la tabla no está lista, son 2.
- **Validaciones**: el calendario debe tener tantas fechas como el nuestro (si no, error) y la
  fecha se coteja por día (±1) y por número; la tabla debe ser de la temporada pedida (selector
  `seasonItems` activo + título "Driver Standings, season N"); el HTML y el grid deben coincidir
  fila por fila (nombre, posición, total) y cada total = suma de sus fechas, si no "aún no".
  Temporada sin tabla ("There are no standings for this season", hoy la 13) → "aún no".
- **Fecha reflejada** = la mayor con ganador y ≥ 10 clasificados. Si el sitio va más allá de la
  pedida (o ya suma la pole de la siguiente) → `aheadOf` ("aún no"; no se reconstruyen tablas viejas).
- **Decisiones del usuario (2026-09-27)**: `ref` = slug de `/en/drivers/{slug}` (sustitutos sin
  página: slug del nombre, "david-beckmann"); número solo si `/en/drivers` dice "Season N" de
  NUESTRA temporada (si no, vacío); equipos con `properCase(…, acronyms = true)`; los sustitutos con
  0 puntos SÍ salen. Posición = orden de la tabla (el sitio ya desempata; se verifica contra el grid).
- **Fotos**: `media.headshot` del grid es una referencia del flight a la imagen de Contentful
  (`images.ctfassets.net`, PNG con transparencia, 530×588). Se guarda la completa acotada
  (`?w=720&h=1440&fm=png`, no agranda) y la cara recortada por Contentful
  (`?fit=thumb&f=face&w=256&h=256&fm=png`). No cuesta páginas extra; cada foto se baja una vez
  (2 imágenes por piloto la primera corrida de la temporada, luego ninguna).
- Verificado el 2026-09-27 contra una copia de la base: 2025-26 → 20 pilotos tras la fecha 17
  (1.º #94 Pascal Wehrlein, Porsche Formula E Team, 169; idéntica a la tabla de §5), 20/20 con foto,
  segunda corrida `unchanged` y fecha confirmada; 2026-27 → "aún no"; fechas 4 y 15 → `aheadOf`.
  Una temporada 2024-25 de prueba (16 fechas) sacó los 24, con David Beckmann (sin página) y Zane
  Maloney (0 puntos) y sin números (la parrilla del sitio es la 12). Ojo: `properCase` escribe
  "Neom Mclaren" (2024-25; en 2025-26 no hay equipos así).

## Por la página pública (investigado 2026-09-27; prototipo en `prototipos/formula-e/`)

Viable sin llave ni reto (Cloudflare + CDN de Google, sin `cf-mitigated`; `robots.txt` solo
prohíbe `/api/`). La API de pulselive sigue con el **certificado vencido** (descartada).
- **Tabla**: `https://www.fiaformulae.com/en/results-and-standings?tab=drivers&season={N}` con
  N = año inicial − 2013 (2025-26 → 12, 2026-27 → 13; de `seasonLabel`, no de `season`). Filas
  `tr[data-testid=standings-row-driver]` y, en el flight de Next (`NextFlight`), `"gridPanel":`
  con cada piloto y sus `rounds:[{roundNum, points, position, pole, fastestLap, status}]`.
- **Calendario del sitio** (`?season=N`, flight `"rounds":[{roundNum, raceDate}]`): coincide al
  100 % con `formula-e.csv` (17/17 y 21/21).
- **Número**: solo el de la parrilla actual (`/en/drivers`, `driverCard__number`), por eso los
  sustitutos y temporadas pasadas quedan sin número. `ref` = slug de `/en/drivers/{slug}`
  (sustitutos sin página: `slugify(nombre)`). Equipos en MAYÚSCULAS.
- **Fecha reflejada**: la mayor `roundNum` con un ganador (`position == 1`) y ≥ 10 clasificados
  (la pole suma 3 horas antes de la carrera); tabla HTML y grid deben coincidir y la suma por fecha
  dar el total. Solo publica la vigente (`&round=` se ignora); con el grid se puede reconstruir la
  tabla de una fecha anterior (reprodujo el desempate oficial en S9–S12).
- 3 peticiones por corrida. Términos: solo uso personal no comercial.
- Valores propuestos al usuario (aprobados el 2026-09-27): "aún no" si el sitio va adelante,
  `ref` = slug, número vacío para sustitutos, equipos a mayúscula inicial, sustitutos con 0 puntos
  SÍ se muestran.


Muestras en `fuentes/formula-e/` (JSON crudos numerados `01_…` a `11_…`, resultados de
carrera de las 17 rondas de S12 en `s12_race_results_por_ronda/`, escaneos `s9..s12_scan.txt`
y `all_seasons_scan.txt`, tabla final unida en `s12_tabla_final.md`, extracto de términos
en `terms.txt`; scripts `scan*.py`).

## 0. ALERTA — el certificado TLS de la API venció AYER

- `api.formula-e.pulselive.com` sirve un certificado ACM (`CN=formula-e.pulselive.com`,
  SAN `*.formula-e.pulselive.com`, `*.fiaformulae.com`…) con **notAfter = 2026-09-24
  23:59:59 GMT**. Hoy **todas** las IPs del edge (CloudFront, 4 IPs) lo sirven vencido.
- `curl` normal → error 60 (`certificate has expired`). **Probado con JDK 21
  `java.net.http.HttpClient`: `SSLHandshakeException … CertificateExpiredException`.**
  Con `curl -k` la API responde 200 con datos correctos (así se hizo todo este análisis).
- `www.fiaformulae.com` (otro certificado, válido) sigue mostrando la tabla (render en
  servidor, Next.js), así que el backend de datos está vivo; lo roto es solo el cert del
  host de la API. Es un certificado ACM que no se renovó — puede ser un descuido que
  corrijan en días, o un síntoma de que migran el host. No encontré alias con cert válido
  (`formula-e.pulselive.com` es un portal S3 de Pulselive, no la API; `api.fiaformulae.com`
  no resuelve; `www.fiaformulae.com/api/...` da 500 y está en `Disallow` del robots).
- **Recomendación para el job**: tratar el fallo TLS como "fuente no disponible" (reintentar
  + alertar al admin), **no** desactivar la verificación TLS globalmente. Si urge, la única
  salida razonable sería un TrustManager SOLO para ese host que acepte ese certificado
  concreto por huella SHA-256 (pinning temporal) — decisión del usuario.

## 1. Endpoint de standings de pilotos

Base: `https://api.formula-e.pulselive.com/formula-e/v1`

| Qué | URL | Notas |
|---|---|---|
| Temporadas | `GET /championships` | `{championships:[{id, name:"SEASON 2025-2026", status:"Past"\|"Present", lastFinishedRound, series}]}` — 12 temporadas (S1..S12), sin paginación |
| Temporada vigente | `GET /championships/latest` | hoy = S12 (`8088703b-96c1-410d-a48b-77fca322334f`, status `Present`) |
| **Standings pilotos** | **`GET /standings/drivers?championshipId={uuid}`** | **arreglo JSON plano**, TODAS las filas sin paginar (S10: 28 filas) |
| Standings equipos | `GET /standings/teams?championshipId={uuid}` | (no se necesita) |
| Rondas | `GET /races?championshipId={uuid}` | `{pageInfo, races:[…]}` (17 en una página) |
| Pilotos (números) | `GET /drivers?championshipId={uuid}&page=N` | **paginado fijo de 20** (`pageSize` se ignora); recorrer `page=0..pageInfo.numPages-1` |
| Sesiones de una ronda | `GET /races/{raceId}/sessions` | buscar `sessionName == "Race"` |
| Resultado de carrera | `GET /races/{raceId}/sessions/{sessionId}/results` | arreglo plano |

IDs de temporada (UUID; el nombre sigue el patrón `SEASON AAAA-AAAA`):
- S12 2025-26: `8088703b-96c1-410d-a48b-77fca322334f`
- S11 2024-25: `4e287a6d-e2da-471a-9c8a-01141d6a1819`
- S10 2023-24: `84467676-4d5d-4c97-ae07-0b7520bb95ea`

Headers: **ninguno obligatorio** (sin API key, sin cookies, sin User-Agent especial).
Responde `application/json`, `cache-control: no-cache, no-store` (cada llamada pega al
origen: "Miss from cloudfront"), sin headers de rate-limit. CORS solo permite
`https://www.fiaformulae.com` (irrelevante para un backend). Errores en JSON Spring:
temporada inexistente → **404** `"Championship does not exist for …"`; falta
`championshipId` → 400.

Términos: la API **no es pública ni documentada** (es la interna del sitio; puede cambiar
sin aviso). `robots.txt` de la API: 404 (no hay). Los T&C de fiaformulae.com dicen:
*"Material may not be copied, reproduced, republished, downloaded… except for your own
personal non-commercial home use. Any other use requires prior written permission."* —
republicar la tabla en la app cae técnicamente en "other use". Riesgo legal bajo (son
hechos deportivos) pero existe; volumen sugerido mínimo (unas pocas llamadas por fin de
semana).

## 2. Estructura de la respuesta (`/standings/drivers`)

Cada fila (ver `03_s12_standings_drivers.json`):

```
driverId (UUID, ESTABLE entre temporadas), driverPosition (Int), driverPoints (Int),
driverFirstName, driverLastName, driverTLA ("WEH"), driverCountry,
driverTeamId, driverTeamName ("PORSCHE FORMULA E TEAM", en MAYÚSCULAS),
driverColour, teamColour,
driverRaceStandings: [ {raceSequence, raceCountry, racePoints, racePosition,
   polePosition, fastestLap, dnf, dnq, dns, dsq, exc,
   championshipPoints, championshipPosition, championshipPositionIncrement} ×4 ]
```

- posición = `driverPosition`; puntos = `driverPoints`; nombre = `driverFirstName + " " +
  driverLastName`; equipo = `driverTeamName`.
- **El número del coche NO viene en standings.** Se une por `driverId` con
  `/drivers?championshipId=` (`driverNumber`, **String** p. ej. `"94"`) o con los
  resultados de carrera (`driverNumber` también String). Verificado: los 20 de S12 unen.
- Resultado de carrera (`07_s12_r17_race_results.json`): `driverPosition, driverId,
  driverNumber, driverFirstName/LastName, team{id,name}, points` (**ya incluye bonos** de
  pole/vuelta rápida), `startingPosition, polePosition, fastestLap, dnf/dns/dnq/dsq/exc`.

## 3. ¿Hasta qué ronda refleja la tabla?

- `championship.lastFinishedRound` **no sirve**: vale 0 en las 12 temporadas.
- **Indicador principal**: `driverRaceStandings` es una ventana con las **4 últimas rondas
  que incorpora la tabla**; `max(raceSequence)` sobre todas las filas = ronda reflejada.
  Verificado en las 12 temporadas: la ventana es siempre `[n-3..n]` con n = nº de rondas
  (S12 `[14,15,16,17]`, S1 con 11 carreras `[8..11]`, etc.). `raceSequence` =
  `races[].sequence` = columna `ronda` de `data/campeonatos/2026/formula-e.csv` (las 17
  coinciden en número y fecha; doble fecha = dos rondas).
- **Confirmación robusta** (recomendada, independiente de la ventana): Σ `points` de los
  resultados de "Race" de las rondas 1..N == `driverPoints` de cada piloto. Verificado
  exacto para TODOS los pilotos de S9, S10, S11 y S12.
- Condición previa: en `/races`, la ronda N con `hasRaceResults: true` y
  `raceLiveStatus: "RACE_FINISHED"` (único valor observado; no pude ver los valores de
  rondas futuras porque S12 ya terminó y S13 no existe en la API).
- No hay parámetro para pedir la tabla "después de la ronda X" (probé `raceId`, `round`,
  `sequence`, `upToRace`… — se ignoran). Para históricos por ronda hay que recalcular desde
  los resultados de carrera.
- Ojo: si un piloto **no corrió** una ronda de la ventana, esa entrada trae
  `championshipPoints: 0, championshipPosition: 0` (S11 De Vries R13/R14) → nunca usar la
  última entrada como total; usar `driverPoints`.
- Sugerencia: re-consultar unos días después (sanciones/descalificaciones posteriores
  pueden mover puntos; hay flags `dsq`/`exc`).

**Descubrir la temporada 2026-27**: hoy `/championships` **no la tiene** (último = "SEASON
2025-2026", aún `status: "Present"` aunque terminó en agosto; el sitio ya muestra "Season
13" en su selector, pero sin datos). El job debería buscar en `/championships` el `name ==
"SEASON 2026-2027"` (mapeo directo a la etiqueta `2026-27`), no fiarse de `status` ni de
`/latest` (que hoy aún devuelve S12). Antes de la 1.ª carrera, esperar 404 o tabla vacía.

## 4. Rarezas contra `Driver(number, name, team)` / `Standing(pos, driverNumber, points)`

1. **Números compartidos dentro de una temporada (rompe "número único")**: en FE el
   sustituto **usa el número del coche/titular**, y ambos aparecen en la tabla. Verificado:
   - S11: #21 De Vries / Drugovich (Mahindra), #17 Nato / Sette Câmara (Nissan).
   - S10: 6 números compartidos — #8 Bird/Barnard, #4 Frijns/Eriksson, #51 Müller/K. van der
     Linde, #21 De Vries/King, #16 Buemi/Aron, #22 Rowland/Collet.
   - S9: #8 Rowland/Merhi (reemplazo definitivo a media temporada), #4 Frijns/K. van der
     Linde, #36 Lotterer/Beckmann.
   - Todas las temporadas S1–S11 tienen ≥1 número compartido; **S12 ninguno** (20 pilotos,
     20 números, sin sustitutos).
   → La llave natural debe ser `driverId` (UUID estable), no el número; o el modelo debe
   tolerar dos pilotos con el mismo número.
2. **Sustitutos en la tabla**: aparecen como filas propias, incluso con 0 puntos (S10 pos
   25–28 con 0 pts). Los rookies del "Rookie Free Practice" NO aparecen (esa sesión no trae
   resultados en la API).
3. **El número cambia entre temporadas** (el campeón toma el #1: Rowland #22→#1, Dennis
   #1→#27; Barnard #8→#77; Eriksson #4→#14) → recargar pilotos por temporada.
4. **Cambio de equipo a media temporada**: la API guarda UN número por piloto-temporada y
   el equipo por carrera (Sarrazin S3 Venturi→Techeetah, Abt S6 Audi→NIO, Pic S1); la fila
   de standings trae un solo `driverTeamName`. No ocurrió en S9–S12.
5. **Puntos**: siempre enteros (12 temporadas, standings y por carrera). Sin fraccionarios.
6. **Empates**: hay empates de puntos (S12: 137 Rowland/Mortara → P4/P5; 68
   Barnard/Vergne → P11/P12) pero **`driverPosition` es siempre único y 1..n** (desempate
   por la fuente) en las 12 temporadas. OK para `pos` único.
7. **Nombres de equipo** en MAYÚSCULAS y con patrocinador variable entre temporadas ("TAG
   HEUER PORSCHE FORMULA E TEAM" en S11 → "PORSCHE FORMULA E TEAM" en S12);
   capitalización de partículas "De Vries", "Di Grassi", "Da Costa".

## 5. Tabla final Season 12 (2025-26) — completa

Disponible completa (17/17 rondas, ventana `[14..17]`, Σ resultados == puntos para los 20):

| pos | # | piloto | equipo | pts |
|---|---|---|---|---|
| 1 | 94 | Pascal Wehrlein | PORSCHE FORMULA E TEAM | 169 |
| 2 | 27 | Jake Dennis | ANDRETTI FORMULA E | 164 |
| 3 | 9 | Mitch Evans | JAGUAR TCS RACING | 160 |
| 4 | 1 | Oliver Rowland | NISSAN FORMULA E TEAM | 137 |
| 5 | 48 | Edoardo Mortara | MAHINDRA RACING | 137 |
| 6 | 13 | António Félix Da Costa | JAGUAR TCS RACING | 128 |
| 7 | 21 | Nyck De Vries | MAHINDRA RACING | 115 |
| 8 | 37 | Nick Cassidy | CITROËN RACING | 114 |
| 9 | 51 | Nico Müller | PORSCHE FORMULA E TEAM | 102 |
| 10 | 16 | Sébastien Buemi | ENVISION RACING | 97 |
| 11 | 77 | Taylor Barnard | DS PENSKE | 68 |
| 12 | 25 | Jean-Éric Vergne | CITROËN RACING | 68 |
| 13 | 3 | Josep Maria Martí | CUPRA KIRO | 66 |
| 14 | 28 | Felipe Drugovich | ANDRETTI FORMULA E | 65 |
| 15 | 33 | Dan Ticktum | CUPRA KIRO | 62 |
| 16 | 14 | Joel Eriksson | ENVISION RACING | 55 |
| 17 | 11 | Lucas Di Grassi | LOLA YAMAHA ABT FORMULA E TEAM | 32 |
| 18 | 7 | Maximilian Günther | DS PENSKE | 23 |
| 19 | 23 | Norman Nato | NISSAN FORMULA E TEAM | 20 |
| 20 | 22 | Zane Maloney | LOLA YAMAHA ABT FORMULA E TEAM | 3 |

## 6. Esbozo del job (Kotlin)

1. `GET /championships` → id por `name == "SEASON 2025-2026"` / `"SEASON 2026-2027"`.
2. `GET /races?championshipId=` → ronda N esperada (`sequence`, `date`, `hasRaceResults`).
3. `GET /standings/drivers?championshipId=` → `reflejada = max(driverRaceStandings[].raceSequence)`;
   si `< N`, reintentar más tarde.
4. `GET /drivers?championshipId=&page=0..` → mapa `driverId → driverNumber.toInt()`.
5. (Opcional) validar Σ puntos de "Race" 1..N == `driverPoints`.
6. kotlinx.serialization con `ignoreUnknownKeys = true`; `driverNumber` es String; manejar
   `SSLHandshakeException` como fuente caída (ver §0).
