# NASCAR — posiciones de pilotos (Cup / O'Reilly / Truck 2026)

Todo verificado con curl el **2026-09-25** (~14:50 UTC). Muestras en `fuentes/nascar/`
(`analisis-rarezas.txt` tiene la salida completa de los conteos).

Estado del calendario ese día (`race_list_basic.json`, solo `race_type_id == 1` = carreras
de puntos; el 2 son exhibiciones: Clash, Duels, All-Star):

| Serie | series_id | Hechas / total | Última carrera con resultado | Siguiente |
|---|---|---|---|---|
| Cup | 1 | 29 / 36 | 5626 Bristol (2026-09-19) | 5628 Kansas 09-27 |
| O'Reilly | 2 | 27 / 33 | 5660 Bristol (2026-09-18) | 5663 Las Vegas 10-03 |
| Truck | 3 | 19 / 25 | 5686 Bristol (2026-09-17) | 5675 Kansas 09-26 |

Los totales cuadran con nuestro calendario (36 / 33 / 25).

## Lo que usa el backend (fuente `nascar-feed`, 2026-09-27)

El usuario pidió leer la página pública `https://www.nascar.com/standings/nascar-cup-series/`,
pero **da 403 con un reto de Cloudflare** ("Just a moment…", `cf-mitigated: challenge`) con
cualquier User-Agent. Evadirlo exigiría un navegador automatizado para engañar la protección
anti-bots: no se hace. El usuario eligió los **JSON públicos de cf.nascar.com** que esa misma
página descarga (sin llave, sin reto). Parámetro de la ingesta = serie (1 Cup, 2 O'Reilly, 3 Truck):
1. `cacher/{año}/{serie}/race_list_basic.json` → carreras de puntos (`race_type_id` 1) por fecha;
   la nuestra se coteja por **día** (±1) y está corrida cuando trae `winner_driver_id`. Si una
   carrera posterior ya tiene ganador, error: el archivo solo trae la tabla vigente.
2. `cacher/{año}/{serie}/points-feed.json` → tabla; se omiten las filas con 0 puntos, se ordena
   por `position` y la posición guardada es el orden (Shafer, −25, queda al final: 93.º en Truck).
3. `cacher/{año}/{serie}/{race_id}/raceResults.json` de esa carrera: la tabla cuenta como
   reflejada si ≥ 90% de los pilotos coinciden en `points_earned` (tolera sanciones sueltas).
   El **equipo** sale de ahí, recorriendo carreras hacia atrás (un archivo por segundo) hasta
   cubrir a todos: 20–35 s por serie con los eventuales de 2026.

Verificado el 2026-09-27 contra una copia de la base: nuestros calendarios coinciden día por día
con las 94 carreras de puntos; Cup 40 pilotos tras la fecha 29 (Kansas de hoy → "aún no"),
O'Reilly 66 tras la 27, Truck 93 tras la 20 (Kansas del 26 ya reflejada). Todos con equipo.

---

## 1. Endpoint, headers y términos

### Recomendado (uno por serie)

```
https://cf.nascar.com/cacher/2026/1/points-feed.json   # Cup
https://cf.nascar.com/cacher/2026/2/points-feed.json   # O'Reilly
https://cf.nascar.com/cacher/2026/3/points-feed.json   # Truck
```

- Es **idéntico byte a byte** (mismo ETag) a `cacher/2026/{s}/final/{s}-drivers-points.json`
  y a `data/cacher/production/2026/{s}/points-feed.json`. Cualquiera de los tres sirve.
- **Respeta el año**: `cacher/2025/1/points-feed.json` regresa la tabla final de 2025
  (61 filas, Larson 5034 con el reinicio a 5000 del formato viejo).

### Lo que usa nascar.com (sirve para validar, no como fuente principal)

```
https://cf.nascar.com/data/cacher/production/2026/{s}/racinginsights-points-feed.json
  = https://cf.nascar.com/cacher/2026/{s}/racinginsights-points-feed.json
  = https://feed.racinginsights.com/feed/driverpoints?series=ncs|nxs|nts
```

- La página oficial de standings lo carga como `driverFeedURL`: lo confirmé en la
  configuración embebida de capturas de Wayback del 2026-08-29 (Cup), 09-03 (O'Reilly) y
  09-14 (Truck). La de Truck apuntaba directo a `feed.racinginsights.com` con
  `overrideDriverStandingsFeed = "1"`.
- **Ignora el año.** `cacher/2025/1/racinginsights-points-feed.json` regresa exactamente los
  datos de 2026, y en el origen `?season=2025` tampoco cambia nada. El JSON no trae ningún
  campo de temporada. Si un job pide la temporada N, al arrancar la N+1 recibiría la nueva
  sin enterarse.
- Solo trae pilotos con `points != 0`. `playoff_rank` siempre vale 0 y `playoff_eligible`
  es inconsistente (Cup y O'Reilly dan 1 a todos; Truck solo a 10). Los nombres están menos
  limpios: espacios al final (`"Matt Wilson "`), sin acentos (`Suarez`) y abreviados
  (`Gio Ruggiero`, `Andres Perez`).
- En posición y puntos coincide con `points-feed.json` en todos los pilotos con puntos. La
  única diferencia es la posición de un piloto con puntos negativos (121 contra 123).

**Por qué recomiendo `points-feed.json`:** su URL está atada al año, trae el seed de la
Chase (`playoff_rank`) y sus nombres son canónicos.

### Headers y comportamiento HTTP (verificado)

- No pide nada: ni User-Agent (funciona con UA vacío), ni auth, ni cookies. Es S3 detrás
  de CloudFront.
- Manda `access-control-allow-origin: *`, no manda `Cache-Control` y no comprime (sin gzip).
  El JSON pesa 33 KB en Cup, 58 KB en O'Reilly y 74 KB en Truck.
- **El ETag es estable (MD5 del contenido) y `If-None-Match` regresa 304** (probado). Hay
  que usarlo para no bajar de nuevo lo que no cambió.
- **`Last-Modified` NO es confiable.** La copia `cacher/.../racinginsights...` se reescribe
  cada ~5 min con el mismo contenido. El `points-feed.json` de Truck se reescribió el 25-09
  sin cambios. Cup tenía `Last-Modified` del 23-09 cuando su `final/` era del 20-09 y el
  contenido era idéntico.
- `www.nascar.com` está detrás de un challenge de Cloudflare: curl recibe 403 incluso con
  UA de navegador (`nascar-com-cloudflare-403.html`). No hay que depender de ese dominio.
  `cf.nascar.com` no tiene robots.txt (da 403) y tampoco deja listar el bucket.

### Términos

- Los Terms of Use de NASCAR Digital Media (actualizados en 2025) prohíben usar
  *"robots, spiders, scripts, service, software or any manual or automatic device, tool, or
  process designed to data mine or scrape the NDM Network Services, including all images,
  video, data and other information … or collect such information … using automated
  means"*. También prohíben la explotación comercial sin permiso.
- Los feeds de `cf.nascar.com` no están documentados ni tienen licencia pública, y pueden
  cambiar sin aviso.
- Riesgo: contractual, de zona gris. La tabla en sí son hechos, pero el ToS aplica.
- Mitigación: pocas peticiones (unas decenas por fin de semana con ETag), citar
  "Fuente: NASCAR" y no redistribuir otro contenido.
- Para producción formal conviene un proveedor con licencia (p. ej. el API de NASCAR de
  Sportradar).

---

## 2. Estructura de la respuesta

`points-feed.json` es un **arreglo JSON sin envoltorio ni metadatos**: no trae temporada,
`race_id` ni timestamp. Cada fila tiene:

```json
{"position":1,"driver_id":4030,"driver_name":"Kyle Larson","driver_first_name":"Kyle",
 "driver_last_name":"Larson","driver_suffix":"","car_no":"5","manufacturer":"Chevrolet",
 "points":2206,"points_earned":47,"playoff_rank":7,"starts":29,"wins":1,"poles":1,
 "top_5":12,"top_10":16,"laps_led":883,"dnf":3,"stage_points":233,"delta_leader":0,
 "delta_next":0,"delta_playoff":134,"playoff_points":0,"bonus_points":0,
 "is_clinch":false,"delta_chase":-1,"winnings":0.0, ...}
```

| Modelo destino | Campo de NASCAR | Notas |
|---|---|---|
| `pos` | `position` | Único y contiguo 1..N en `points-feed` |
| número | `car_no` | **String** (`"00"`, `"07"`). Es el **último número que usó** el piloto: 0 discrepancias contra sus resultados en las 3 series |
| nombre | `driver_name` | La identidad estable es `driver_id` (la misma en las 3 series y en todos los feeds) |
| equipo | **no viene** | Ver abajo |
| puntos | `points` | Siempre `Int` |
| (extra) | `playoff_rank` | Seed de la Chase (1..16/12/10); 0 = fuera de la Chase |
| (extra) | `points_earned` | Puntos de la **última carrera**; es la clave para detectarla |

**Equipo:** sale de los resultados por carrera,

```
https://cf.nascar.com/cacher/2026/{s}/{race_id}/raceResults.json
```

Es un arreglo, igual que `weekend-feed.json → weekend_race[0].results`, con `driver_id`,
`car_number`, `team_name`, `owner_fullname`, `points_earned`, `points_position`,
`finishing_status`. El equipo de cada piloto es el `team_name` de su **última**
participación. Conviene mantener un mapa local `driver_id → (número, equipo)` que se
actualice cada semana. El backfill inicial son 75 peticiones (29 + 27 + 19, ya bajadas en
`nascar/raceResults/`).

---

## 3. Cómo saber hasta qué carrera refleja la tabla

El feed no lo dice explícitamente. Este algoritmo está verificado con los datos de hoy:

1. En `race_list_basic.json`: filtrar `race_type_id == 1` y ordenar por `race_date`. La
   carrera terminada R es la última con `winner_driver_id != null`, y su ordinal N es
   "fecha N de M" (29 de 36). Ojo: `race_date` viene en hora local del Este **sin offset**.
2. Bajar `points-feed.json` con `If-None-Match`.
3. **Chequeo barato:** `max(starts) == N`. Hoy da 29/29, 27/27 y 19/19. Hay 33, 26 y 20
   pilotos con `starts == N`, así que es robusto.
4. **Chequeo fuerte:** bajar `raceResults.json` de R y exigir que para cada `driver_id`
   presente `standings.points_earned == result.points_earned`.
   - Contra la carrera correcta: **37/37 (Cup), 39/39 (O'Reilly), 37/37 (Truck)**.
   - Contra la carrera anterior solo coinciden 3/36, 2/36 y 8/41, así que discrimina bien.
   - Además, `result.points_position == standings.position` en todos los pilotos con puntos
     (35/35, 35/35, 36/36).
5. Si no pasa, reintentar más tarde.

**Tiempos observados:**
- `final/1-drivers-points.json` de Cup se escribió el 2026-09-20 a las 04:34 UTC, unos
  90–100 min después de la bandera a cuadros (el `live-points` de esa carrera se escribió
  por última vez a las 02:52 UTC).
- El de Truck, a las 03:41 UTC, ~1.5 h después.

**Sugerencia de reintentos:**
- Primer intento ~1 h después del final.
- Luego cada 30 min hasta 24 h.
- **Una pasada extra martes/miércoles** para las sanciones, que restan puntos a mitad de
  semana. Con el ETag cuesta un 304.

Además de reintentar, validar siempre con el chequeo fuerte. No verifiqué si
`points-feed.json` o el de racinginsights se actualizan **en vivo** durante la carrera,
porque hoy no había carrera. El chequeo de `points_earned` y el requisito de
`winner_driver_id` protegen contra tomar una tabla a medias.

---

## 4. Playoffs → en 2026 es "The Chase"

**Formato 2026** (NASCAR, 2026-01-12; confirmado con los datos):
- **Tamaño de la Chase:** Cup = top 16 en 10 carreras (27–36, desde la Southern 500).
  O'Reilly = top 12 en 9 (25–33). Truck = top 10 en 7 (19–25).
- **Coincide con el calendario:** `race_list_basic.playoff_round == 1` marca exactamente
  esas 10 / 9 / 7 carreras.
- **Reinicio de puntos al empezar la Chase:** seed 1 = 2100, 2 = 2075, 3 = 2065 y luego −5
  por lugar (Cup #16 = 2000). **Verificado:** `points` menos lo ganado en las carreras de la
  Chase da exactamente esos valores en las 3 series, ordenado por `playoff_rank`.
- **Otras reglas:** sin eliminaciones ni Championship 4; la victoria vale 55. El campeón es
  el de más puntos al terminar Homestead.
- **Fuera de la Chase:** los demás conservan sus puntos acumulados sin reinicio (Cup #16
  Suárez 2072, #17 van Gisbergen 647).

**Qué muestra NASCAR oficialmente:** **una sola tabla de pilotos ordenada por `position`**.
- Los de la Chase ocupan 1..16/12/10 con puntos reiniciados y el resto va abajo con sus
  puntos acumulados.
- La configuración de la página trae `clinchedKeyLabel = "CLINCHED CHASE SPOT"`,
  `cutoffPosition = 10` en Truck y una línea de corte.
- El texto de la página dice: *"The top 10 drivers in points are competing in The Chase, a
  seven-race championship battle."*
- La pestaña vieja "PLAYOFFS" está oculta (`hide-desktop hide-mobile`).
- No existe un feed público de "temporada regular sin reinicio" para 2026: probé
  `playoff-points-feed`, `chase-points-feed` y `regular-season-points-feed` y todos dan 403.

**Recomendación:**
- Guardar `position` y `points` **tal cual**. Es el orden oficial del campeonato y es el que
  se vuelve la clasificación final.
- Opcionalmente, marcar a los de la Chase (`playoff_rank > 0`) y dibujar un divisor
  "The Chase" después del 16/12/10. Sin ese divisor, el salto de 2072 a 647 parece un error.
- No reconstruir una tabla sin reinicio: no es oficial.
- El seed de la temporada regular está en `playoff_rank` por si se quiere mostrar.

---

## 5. Rarezas contra `Driver(number:Int único, name, team)` / `Standing(pos, driverNumber:Int, points)`

Conteo sobre los feeds del 2026-09-25 y los resultados de todas las carreras ya corridas.
"Con puntos" significa `points != 0`.

| | Cup | O'Reilly | Truck |
|---|---|---|---|
| Filas en `points-feed` | 55 | 96 | 123 |
| Pilotos con puntos | 40 | 66 | 93 |
| Filas con 0 puntos (no elegibles o sin arrancar) | 15 (12 corrieron) | 30 (26 corrieron) | 30 (25 corrieron) |
| **Número compartido entre pilotos con puntos** (String) | 1 número / 2 pilotos | 9 / 32 | 15 / 64 |
| **Colisiones extra al convertir a Int** | 0 | 3: `0↔00`, `2↔02`, `7↔07` → 37 pilotos afectados | 0 |
| Pilotos con puntos que **cambiaron de número** en la temporada | 2 | 20 | 16 |
| Números usados por más de un piloto en la temporada | 8 de 47 | 20 de 50 | 20 de 48 |
| Pilotos con puntos que corrieron para más de un equipo | 1 | 15 | 7 |
| **Empates de puntos** (la posición sí viene desempatada) | 1 grupo / 2 pilotos | 5 / 14 | 19 / 44 |
| Puntos negativos | 0 | 0 | 1 (Jonathan Shafer −25, 0 arranques) |
| Puntos fraccionarios | 0 | 0 | 0 (siempre `Int`) |
| `position` única | sí | sí | sí (en racinginsights hay un hueco: 1..92 y luego 121) |

**Ejemplos:**
- **Números compartidos:**
  - Cup: #78 Katherine Legge (8) y BJ McLeod (4).
  - O'Reilly: #42 lo usan 6 pilotos con puntos y #53 siete.
  - Truck: #25 lo usan **10** pilotos con puntos (Stewart, Bowyer, McMurray, Sadler,
    Newman, Regan Smith, Pastrana, Daly, Braun, Ferguson) y #22 ocho.
- **Colisión con Int en O'Reilly:** el **líder** Sheldon Creed es **#00** y chocaría con
  los #0 (Smithley, Labbé, Snider). Los de la Chase Justin Allgaier **#7** y Jesse Love
  **#2** chocarían con Bilicki **#07** y Ryan Ellis **#02**.
- **Cambio de número:**
  - Cup: Casey Mears 62→66, John H. Nemechek 40/42.
  - O'Reilly: Carson Kvapil 1/9/91 y David Starr 42/47/53/55.
  - Truck: Parker Kligerman 25/75/77 y Corey LaJoie 10/25/75.
- **Corren sin puntos en esa serie** (declaran otra) **y hasta ganan**:
  - Cup: Corey Heim #67 (10 arranques, **2 victorias**, 0 pts), Austin Hill #33
    (19 arranques).
  - O'Reilly: Kyle Larson #88 (2 victorias), Shane van Gisbergen #9 (2), Connor Zilisch #1
    (2), Ross Chastain #9 (1).
  - Truck: Christopher Bell #62 (1), Carson Hocevar #77 (1).
  - **El ganador de una carrera puede no estar en la tabla de puntos.** En `points-feed`
    aparecen con 0 puntos y posiciones arbitrarias al final, y hay que filtrarlos.
- **Nombres inconsistentes entre feeds:** Suárez/Suarez, "Shane van/Van Gisbergen",
  "John H./H Nemechek", espacios al final. La llave debe ser `driver_id`, nunca el nombre.

**Implicaciones para el modelo:**
1. **`Driver.number` como Int único por categoría no se sostiene.** Truck: 64 de 93 pilotos
   con puntos comparten número. O'Reilly: 32 de 66 (37 si se convierte a Int).
2. Convertir a `Int` **destruye** `00`/`07`/`02`: el número debe ser **String**.
3. `Standing.driverNumber` no puede ser la llave. Hay que ligar la posición con el piloto
   por `driver_id` de NASCAR (guardado como id externo), o como mínimo por nombre, y dejar
   el número como dato visible: el último que usó.
4. Filtrar las filas con `points == 0`. Decidir qué hacer con los negativos: nascar.com sí
   los muestra (Shafer al final), y conservarlos deja un hueco en `pos` sin romper la
   unicidad.
5. Los empates no rompen nada: `position` ya trae el desempate oficial y es única.

---

## Archivos de muestra (`fuentes/nascar/`)

- `points-feed-{1,2,3}.json`: fuente recomendada, más sus `hdr-points-feed-*.txt`.
  `hdr-final-*.txt` muestra el mismo ETag de `final/{s}-drivers-points.json`.
- `points-feed-1-2025.json`: prueba de que `points-feed` respeta el año.
- `racinginsights-points-feed-{1,2,3}.json` y `rifeed-driverpoints-{ncs,nxs,nts}.json`: el
  feed de nascar.com y su origen, idénticos entre sí. `racinginsights-points-feed-1-URL2025.json`
  es la prueba de que ignora el año.
- `race_list_basic-{1,2,3}.json`: calendario con `winner_driver_id` y `playoff_round`.
- `raceResults/{s}-{race_id}.json`: resultados de las 75 carreras ya corridas, con equipo.
- `weekend-feed-1-5626.json`, `live-points-1-5626.json`: otros feeds por carrera.
  `live-points` trae `race_id: 0` y marca la Chase como `"Larson (C)"`; no sirve para esto.
- `wayback-standings-*.html`: páginas oficiales archivadas con la configuración de feeds
  (`driverFeedURL`, `cutoffPosition`, texto de la Chase).
- `analisis-rarezas.txt`: salida completa de los conteos.
