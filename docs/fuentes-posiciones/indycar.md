# IndyCar Series + Indy NXT 2026: fuente para la ingesta automática de posiciones

## Lo que usa el backend (fuente `indycar-feed`, 2026-09-27)

Código en `backend/.../IndyCarSource.kt` (`IndyCarFeeds`). Parámetro = serie (`indycar` |
`indynxt`): el código lo traduce al host (`www.indycar.com` / `www.indynxt.com`) y al GUID de abajo.
Crédito visible: "indycar.com". Por corrida (una petición por segundo):
1. `YearPointSummary` → tabla: posición = `OverallPosition` (única, ya desempatada), puntos =
   `TotalPoints`. `DriverList` vacío = temporada sin empezar → "aún no".
2. Fecha reflejada: columnas por `EventsSessionsID` (deben ser tantas como fechas de nuestro
   calendario; si no, error "revisa el calendario"); reflejada = su columna suma > 0 y deben ser las
   primeras k; cada piloto debe sumar su total; los puntos de la columna pedida deben ser los
   `PointsEarned` de su resultado, y su `SessionDate` caer a ±1 día de nuestro día de carrera. Si k
   pasa de la fecha pedida → "aún no" (el sitio solo publica la tabla vigente).
3. `DriversByYear` → `ref` = `DriverOverrideID` (por posición, cotejando el nombre).
4. `EventsSessionDetails` de la carrera pedida (siempre fresca) y hacia atrás hasta cubrir a todos:
   número (texto) y equipo de la aparición más reciente. Las carreras ya leídas se quedan en memoria
   del proceso.
5. Página `/standings/{año}`: equipo = nombre CORTO (el `alt` del logo, decisión del usuario) y foto
   (`driverPortraitImg`, medio cuerpo; IndyCar = PNG transparente, que queda sobre el carbón de la
   app; Indy NXT = JPG con fondo blanco). Se empata por nombre; si un piloto no aparece, equipo de su
   inscripción y sin foto. Si la página falla, error y el job reintenta.
- **Peticiones**: la primera corrida tras arrancar el backend, 15 en IndyCar (tabla, pilotos, 12
  carreras hasta la Indy 500 y la página) y 13 en Indy NXT, más una foto por piloto (una sola vez);
  después, 4 por corrida. Una fecha vieja cuesta 1.
- **Verificado el 2026-09-27** contra una copia de la base: IndyCar 33 pilotos tras la fecha 18 (Palou
  #10 Chip Ganassi Racing 631, Kirkwood #27 Andretti Global 545, Lundgaard #7 Arrow McLaren 535) e Indy
  NXT 28 tras la 17 (N. Johnson #21 Cape Motorsports powered by ECR 585, Fittipaldi #67 HMD Motorsports
  556, Kucharczyk #71 HMD Motorsports 551); segunda corrida sin cambios; fecha vieja → "aún no"; fotos
  33/33 y 28/28. Los números compartidos de Indy NXT (#15, #17, #48, #76) quedan como pilotos
  distintos y "06" (Castroneves) ≠ "6" (Siegel).

## Por los JSON de la página de resultados (investigado 2026-09-27; prototipo en `prototipos/indycar/`)

`https://www.indycar.com/Standings` se arma en el servidor (sin ids de carrera; en Indy NXT omite
una carrera). La página de Resultados descarga JSON públicos sin llave ni reto (Azure Front Door,
`no-store`); indycar.com e indynxt.com sirven lo mismo:
- `/api/results/YearPointSummary?year=2026&id={GUID}` → tabla (`OverallPosition` desempatada,
  `TotalPoints`, columnas por carrera con `EventsSessionsID`).
- `/api/results/DriversByYear?year=2026&id={GUID}` → `DriverOverrideID` (= `ref`).
- `/api/results/EventsSessionDetails?id={EventsSessionsID}` → número (`CarNumber`, texto: "06" ≠
  "6"), equipo y fecha de cada carrera; recorrer hacia atrás hasta cubrir a todos.
- GUID IndyCar `b856a4f1-e85c-4fac-8c36-fd58d962227a`, Indy NXT `09341e09-3216-4f89-a45f-db697d72ee13`
  (parámetro sugerido `indycar|indynxt`).
- Fecha reflejada: columnas con suma > 0 (deben ser las primeras k), suma por piloto = total, y los
  puntos de la columna = `PointsEarned` del resultado. Llave = `EventsSessionsID` (las dos de
  Milwaukee se llaman "MIL"; el sitio las pone el 30/8 y en orden inverso al nuestro).
- 2026 ya terminó: IndyCar 18/18 (Palou 631), Indy NXT 17/17 (Nikita Johnson 585).
- ~13 peticiones la primera vez por serie, ~3 después. Términos: prohíben el scraping.
- Nombre de equipo: decidido el 2026-09-27, el corto de la página (ver arriba).


Verificado con curl el **2026-09-25**. Muestras en `fuentes/indycar/`.

## En corto

- **Sí hay fuente JSON viable**: la API interna (sin documentar) con la que indycar.com arma sus
  páginas de Resultados y Estadísticas: `https://www.indycar.com/api/results/...`. Es la misma
  para las dos series; cambia el **GUID de la serie**. No pide autenticación, clave, cookies ni
  un User-Agent especial.
- **Posiciones**: `YearPointSummary` (posición, nombre, puntos totales y puntos por carrera).
  **No trae número ni equipo**, así que hay que completarlos con `EventsSessionDetails` de las
  carreras, que sí los trae.
- **Detección de la fecha**: la tabla trae desde el inicio **una columna por cada carrera del
  año**, incluidas las futuras, con su `EventsSessionsID`. Una carrera ya está reflejada cuando
  **la suma de su columna es > 0**.
- **Estado hoy**: las dos temporadas **terminaron** y están completas. IndyCar tiene 18/18
  carreras reflejadas (campeón Alex Palou, #10, 631 pts). Indy NXT tiene 17/17 (campeón Nikita
  Johnson, #21, 585 pts). La última carrera de ambas fue Monterey, el 6 de septiembre de 2026.
- **Riesgo legal**: los Términos de Uso de INDYCAR (vigentes desde el 2025-09-10) prohíben
  *"use an automatic device (such as a robot or spider) or manual process to copy or 'scrape'
  the Services … for any purpose without our express written permission"*. También limitan el
  uso a *"personal, non-commercial use"*. No hay `robots.txt` (404). Se recomienda pedir permiso
  o asumir el riesgo con un volumen bajísimo (algunas peticiones por fin de semana).

## 1. Endpoints

Base: `https://www.indycar.com/api/results/`. El mismo path en `www.indynxt.com` devuelve una
respuesta idéntica, byte por byte.

| Serie | GUID (`id` / `series`) |
|---|---|
| NTT IndyCar Series | `b856a4f1-e85c-4fac-8c36-fd58d962227a` |
| Indy NXT | `09341e09-3216-4f89-a45f-db697d72ee13` |

Los GUID salen del `<input id="hdnSeries">` de las páginas `/results`.

| Uso | URL |
|---|---|
| **Posiciones (pilotos)** | `YearPointSummary?year=2026&id={GUID}` |
| Pilotos del año con su id | `DriversByYear?year=2026&id={GUID}` → `DriverOverrideID`, `FirstName`, `LastName`, `OverallPosition`, victorias, poles |
| Eventos y sesiones por año | `SeasonDropDown?id={GUID}` (todos los años) · `EventsByYearSeries?year=2026&id={GUID}` |
| **Resultado de una carrera** | `EventsSessionDetails?id={EventsSessionsID}` |
| Detalle de un piloto en el año | `DriverYearDetails?year=2026&series={GUID}&driverID={DriverOverrideID}` (incluye `CarNumber` por carrera) |
| Años disponibles | `YearsBySeries?series={GUID}` → `[2026, 2025, …, 1996]` |

- **Temporada** = parámetro `year`. `year=2027` hoy responde 200 con `DriverList: []` y solo la
  columna "Total".
- **Headers**: no se necesita ninguno. Probé el UA por defecto de curl, `Java-http-client/21` y
  `Ktor client`: todos dan 200. La respuesta es `application/json; charset=utf-8`,
  `cache-control: no-cache, no-store` (siempre fresca, sin ETag ni Last-Modified), sale por
  Azure Front Door y admite gzip.
- **Límites**: no encontré rate limit documentado. La API se usa desde el navegador
  (jQuery/DataTables en el bundle `/bundles/scripts/indycar/v2/bundle`). Web Archive la
  archiva seguido, lo que sugiere que otros la consultan sin bloqueo.

## 2. Estructura

### `YearPointSummary` (ver `indycar-YearPointSummary-2026.json`)

```
{ Year, SeriesTitle, SortTitle, UpdatedDate,           // UpdatedDate = FECHA DE LA PETICIÓN (no sirve)
  RaceAbbreviations: [ {Track:"STP", TrackType:"S", EventsSessionsID:6732, Points:0}, …,
                       {Track:"Total", EventsSessionsID:0} ],
  DriverList: [ { DriverName:"Alex Palou", Rookie:null, OverallPosition:1, TotalPoints:631,
                  OvalPoints, RoadPoints, TotalWins, TotalPoles, TotalTop5s, BestFinish,
                  Points:[ {Track, TrackType, EventsSessionsID, Points}, …, {Track:"Total", Points:631} ] } ],
  AltDriverList: "<JSON serializado como string, puntos como strings, claves 'mil','mil2'…>" }  // ignorar
```

- `pos` = `OverallPosition`, `name` = `DriverName` y `points` = `TotalPoints` (Int).
- No trae **número, equipo ni id del piloto**. `Rookie` siempre es null.

### `EventsSessionDetails?id=<carrera>` (ver `indycar-EventsSessionDetails-6736-monterey-race.json` y `sesiones/`)

`{EventName, SessionName:"Race", SessionDate:"9/6/2026", SessionType:"R", records:[…], SessionReports:[…]}`

Cada elemento de `records` trae: `DriverOverrideID` (= `DriversID`), `DriverName`, `FirstName`,
`LastName`, **`CarNumber` (String)**, **`TeamName`**, `PositionFinish`, `PositionStart`,
`Status` (Running/Contact/Mechanical/Retired/Off Course), `PointsEarned` (Int), `LapsLed` e
`IsDeleted`.

### Receta sugerida para el job (por serie)

1. `SeasonDropDown`: para cada evento del año, la sesión con `SessionName == "Race"` da su
   `EventsSessionsID`. Esto se mapea **una vez por temporada** a nuestras fechas (ver la rareza
   de Milwaukee más abajo). Los ids no cambiaron entre febrero y hoy.
2. `YearPointSummary`: da la posición, el nombre y los puntos.
3. Número y equipo: `EventsSessionDetails` de las carreras ya corridas, tomando la **última
   aparición** de cada piloto (bastan 1 a 18 peticiones, o una sola si se guarda el mapa entre
   corridas).
   - **Join**: `DriverName` coincide exacto con el de las carreras (verificado en los 61
     pilotos de 2026).
   - Más robusto: unir `OverallPosition` con `DriversByYear` para obtener `DriverOverrideID`
     (posiciones únicas; verificado sin discrepancias) y de ahí con `records.DriverOverrideID`.

## 3. Cómo saber hasta qué fecha refleja la tabla

- `RaceAbbreviations` lista **todas** las carreras programadas desde antes de la temporada. Lo
  confirmé con un snapshot de Web Archive del 2026-03-01, que tenía las 18 columnas y
  `DriverList` vacío.
- **Carrera reflejada** ⇔ `sum(driver.Points[i].Points for driver in DriverList) > 0` en la
  columna cuyo `EventsSessionsID` es el de la carrera. El ganador siempre suma ≥ 50, así que no
  hay ambigüedad.
- **Fechas reflejadas** = número de columnas (sin "Total") con suma > 0.
- Chequeo de consistencia: la suma de los puntos por carrera de cada piloto es igual a
  `TotalPoints`. Se cumple en 2026 para las dos series.
- `UpdatedDate` **no sirve**: es la fecha de la petición, no la de la última actualización.

### Latencia observada (snapshots de Web Archive, en `wayback/`)

| Snapshot | Qué se ve |
|---|---|
| 2026-03-05 | Solo St. Pete (1 columna con puntos) |
| 2026-03-14 | St. Pete + Phoenix |
| **2026-05-24 22:44Z** | Ya incluye la Indy 500 del **mismo día** (terminó ~19:45Z): unas 3 h |
| **2026-05-26** | La Indy 500 **cambió**: Palou pasó de 41 a 36 pts en esa carrera (suma de la columna: 620 → 615) |
| 2026-09-06 19:53Z | Monterey todavía en 0, con la carrera en curso |

Consecuencias para el job:

- Reintentar a partir de unas horas después de la bandera a cuadros.
- **Seguir resincronizando varios días después**, porque los resultados provisionales se
  corrigen. El bulk de reemplazo total lo hace idempotente.
- Contemplar carreras **pospuestas**: Nashville se movió del domingo 19 al lunes 20 de julio, y
  la carrera 1 de Milwaukee se terminó el domingo, después de la carrera 2.

## 4. Rarezas frente al modelo `Driver(number:Int, name, team)` / `Standing(pos:Int, driverNumber:Int, points:Int)`

1. **Indy NXT: 4 números compartidos por dos pilotos en la temporada** (28 pilotos en la tabla,
   solo 24 números). Son sustitutos y pilotos que cambiaron a media temporada, y **todos
   figuran en las posiciones**:
   - #15: Nicolas Stati y Yuven Sundaramoorthy (solo WWTR).
   - #17: Salvador de Alba y Bart Harrison (Portland y Monterey; de Alba volvió en Milwaukee).
     **Los dos usaron el #17 en su última carrera.**
   - #48: Jordan Missig (hasta Nashville) y Jacob Abel (desde Portland).
   - #76: Ricardo Escotto (hasta Road America) y Nolan Allaer (desde Mid-Ohio).
   - Con "número único por categoría" no se puede representar. Hace falta una llave estable del
     piloto (`DriverOverrideID`) o una regla de desempate.
2. **IndyCar: "06" y "6"**. `CarNumber` es String. El **#06** es Helio Castroneves (solo Indy
   500) y el **#6** es Nolan Siegel, así que `toInt()` los hace chocar. No hay otros números
   compartidos en IndyCar 2026.
3. **Entradas de solo la Indy 500 (IndyCar)**: 8 pilotos (Daly, Sato, Harvey, Abel,
   Castroneves, Carpenter, Hunter-Reay, Legge) figuran en la tabla (posiciones 26 a 33) con
   puntos solo de esa carrera. Tienen números propios, sin choque salvo el "06".
4. **Empates en puntos**: 259 (Grosjean P19 y Siegel P20) y 5 (cuatro pilotos, P30 a P33).
   **`OverallPosition` ya viene desempatada**: es única y va de 1 a N en las dos series.
5. **Puntos**: siempre Int. No hay fraccionarios (tampoco en la Indy 500, que da puntos por
   clasificación). Una ausencia vale 0 en la columna de esa carrera.
6. **Pilotos sin número**: ninguno en carrera. En `DriverYearDetails`, las carreras sin
   participación traen `CarNumber: "--"`. No hubo cambios de equipo en 2026.
7. **Orden de columnas ≠ nuestro número de fecha** en el doble de Milwaukee. La API ordena por
   fecha y luego por id: `MIL` 6738 "Snap-on Milwaukee Mile 250" (nuestra fecha 17) va
   **antes** que `MIL` 6739 "Snap-on Makers and Fixers 250" (nuestra fecha 16, terminada el
   domingo). En febrero el orden era el inverso. Además, las dos columnas tienen el mismo
   `Track` ("MIL"). **Hay que usar `EventsSessionsID` como llave, nunca el índice ni `Track`.**
   Los nombres de evento difieren un poco de nuestro CSV ("110th Running of the Indianapolis
   500" frente a "Indianapolis 500", "Sonsio Grand Prix" frente a "… (IMS Road Course)"), por
   lo que conviene una tabla fija que relacione cada fecha con su `EventsSessionsID`.
8. `YearPointSummary` solo incluye pilotos con al menos una carrera. `DriverName` puede traer
   apóstrofos ("Pato O'Ward"); el JSON viene limpio, sin entidades HTML.

## 5. Qué tan actualizada está hoy (2026-09-25)

Las dos tablas están completas y finales:

- **IndyCar**: 18 columnas, todas con suma > 0. Los 5 primeros son Palou 631, Kirkwood 545,
  Lundgaard 535, O'Ward 522 y Malukas 514.
- **Indy NXT**: 17 columnas, todas con suma > 0. Los 5 primeros son N. Johnson 585,
  Fittipaldi 556, Kucharczyk 551, Taylor 470 y Hughes 453.

Esto coincide con nuestro calendario: 18 fechas de IndyCar y 17 de Indy NXT, con Milwaukee
contado como dos fechas.

## Alternativas evaluadas

- **Página HTML `/standings`** (`indycar-standings-pagina.html`): la renderiza el servidor
  (Sitecore). El número **solo aparece en la URL de la imagen del endplate**
  (`…/Endplates/Black-Trans/10-Black120-T.png`) y el equipo en el `alt` del logo. Es frágil y
  la descarté.
- **ESPN core API**
  (`sports.core.api.espn.com/v2/sports/racing/leagues/irl/seasons/2026/types/2/standings/0`):
  - Trae `rank`, `championshipPts` y **`currentWeek` / `totalWeeks` (18/18)**, que son un
    indicador directo de la fecha.
  - Pero es hipermedia: el nombre exige una petición `$ref` por piloto, no trae el número a la
    vista y **no cubre Indy NXT**.
  - `site.api.espn.com` da 403. Además está sujeta a los términos de ESPN.
  - Muestra en `espn-irl-standings-2026.json`. Sirve como respaldo o verificación de IndyCar.
- **Wikipedia** ("2026 IndyCar Series" / "2026 Indy NXT", secciones *Driver standings* y
  *Drivers' championship* por la API de MediaWiki): la licencia CC BY-SA es la más limpia en lo
  legal, pero exige parsear wikitext, la edita la comunidad y la latencia es variable. Es el
  plan B si el ToS de INDYCAR resulta un bloqueo.

## Archivos de muestra (`fuentes/indycar/`)

- `indycar-YearPointSummary-2026.json` (+ `.headers.txt`) y `indynxt-YearPointSummary-2026.json`
- `indycar-DriversByYear-2026.json` y `indynxt-DriversByYear-2026.json`
- `indycar-SeasonDropDown.json`, `indynxt-SeasonDropDown.json`,
  `indycar-EventsByYearSeries-2026.json` e `indycar-YearsBySeries.json`
- `indycar-EventsSessionDetails-6736-monterey-race.json` e
  `indycar-DriverYearDetails-2026-palou.json`
- `sesiones/{serie}-race-{EventsSessionsID}.json`: las 35 carreras de 2026 (18 + 17)
- `wayback/indycar-YPS-<timestamp>.json`: snapshots de mitad de temporada (detección de fecha y
  corrección de la Indy 500)
- `indycar-standings-pagina.html` (alternativa HTML) y `espn-irl-standings-2026.json`
  (alternativa ESPN)
