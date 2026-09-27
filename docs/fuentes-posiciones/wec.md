# FIA WEC 2026 (Hypercar y LMGT3): fuente para ingerir posiciones

Investigado el 2026-09-25 con `curl` real. Las muestras están en `fuentes/wec/`.

## Lo que usa el backend (fuente `fiawec-web`, 2026-09-27)

Decisión del usuario: mostrar el campeonato de **PILOTOS** de cada clase tal cual lo publica la
FIA (no el de fabricantes que da nombre a la página que pasó, ni una vista por coche). Parámetro de
la ingesta = clase (`hypercar` | `lmgt3`). Se lee `https://www.fiawec.com/en/season/{año}` (misma
página que `/en/page/manufacturers-classification`, con el año fijo) con **Jsoup** (dependencia
nueva del backend): la tabla cuyo título tiene la clase y "Drivers".
- **Fila** = tripulación: número (texto, "#009" → "009"), pilotos unidos con " / ", puntos totales.
  `ref` = ids de sus pilotos en el sitio (`/en/driver/{año}/{id}`), ordenados. Posición = orden.
- **Nombres**: el sitio los pone en MAYÚSCULAS; el bien escrito sale del `<title>` de la página de
  cada piloto ("FIA WEC drivers - Brendon Hartley"), en memoria por proceso y uno por segundo
  (~50 páginas por clase la primera vez tras un despliegue). Si falla: mayúscula inicial.
- **Equipo**: LMGT3 = el de su tabla de equipos (por número de coche); Hypercar = fabricante (logo),
  porque en 2026 no hay tabla de equipos.
- **Fecha**: las columnas con enlace `/en/race/<slug>-{año}` son las carreras en orden de
  calendario y deben ser tantas como las de nuestro calendario (si no, error: revisar calendario).
  Reflejada = la carrera seleccionada del sitio (`bg-primary-subtle`) llegó a esa fecha **y** su
  columna tiene puntos > 0. Tabla vigente solamente: una fecha anterior da error.
- Verificado el 2026-09-27 contra una copia de la base (Fuji, fecha 6, corrida esa madrugada):
  Hypercar 26 filas (1.º #8 Hartley / Hirakawa / Buemi, Toyota, 89) y LMGT3 26 (1.º #33 Edgar, TF
  Sport, 86). El job normal todavía perseguía la fecha 5 (Fuji termina su día en UTC hasta el 28) y
  respondió "aún no" porque el sitio ya iba en la 6.
- Nombres: algunas páginas de piloto traen el apellido en mayúsculas ("Kevin MAGNUSSEN"): esas
  palabras se corrigen. El sitio escribe "Sheldon Van Der Linde" y "Roman De Angelis" así.

## Veredicto

**No hay una fuente JSON pública con las tablas del campeonato WEC.** Revisé esto:

| Candidato | Resultado |
|---|---|
| `www.fiawec.com` (tablas) | HTML **renderizado en servidor** (Symfony UX Live Components). No hay XHR/JSON: al cambiar de ronda hace un POST que devuelve **HTML** (`application/vnd.live-component+html`). |
| `storage.googleapis.com/ecm-prod/live/WEC/data.json` | Es JSON, pero solo es el **live timing de la sesión** en curso o de la última (orden en pista, vueltas). No tiene puntos de campeonato. El bucket no se puede listar y cualquier otro nombre da 403. |
| `storage.googleapis.com/fiawec-prod/assets/live/WEC/__data.json` | Datos viejos, de 2021. |
| `api.lmem.com/api` | 401 `Basic realm="Secured Area"`. Es el backend privado de LMEM. |
| `data.wec-master.6tm.eu` (aparece en el CSP del sitio) | Responde `{"message":"ok"}`, pero todas las rutas que probé dan `{"errorCode":"404","message":"No route found"}`. No se puede usar. |
| `livetiming.alkamelsystems.com/fiawec` (Meteor/DDP) | Su suscripción `standings` es la **clasificación de la sesión**, no la del campeonato. |
| `fiawec.alkamelsystems.com` (resultados oficiales) | Para WEC publica las tablas del campeonato **solo en PDF**. Hay CSV por sesión y clasificación de carrera, pero sin puntos. Hay JSON solo para otras series (p. ej. Mustang Challenge), no para WEC. |

**Mejor alternativa: parsear el HTML de fiawec.com** con Jsoup en el backend. Es una sola petición GET, trae las 4 tablas oficiales, marca el número de coche, trae los puntos por fecha y dice explícitamente "hasta qué carrera" llega. Como respaldo o verificación, están los PDF oficiales de Al Kamel.

## 1. Endpoint

```
GET https://www.fiawec.com/en/season/2026          ← recomendado (fija la temporada)
GET https://www.fiawec.com/en/page/manufacturers-classification   ← "temporada vigente" (ver rareza 7)
```

- **Temporada**: en el path, `/en/season/{YYYY}`. Verificado con 2025 (200, trae además la "FIA World Cup for Hypercar Teams") y con 2026. `/en/season/2024` da 404.
- **Headers**: no hace falta ninguno. Funciona con User-Agent `Java-http-client/21`, con Ktor y sin UA. No pide cookies ni autenticación. Respuesta `text/html; charset=UTF-8` de unos 900 KB (mucho SVG inline). Viene de la CDN de Google con `cache-control: max-age=120, public`, así que puede estar hasta 2 minutos atrasada.
- **Una ronda concreta** (opcional, para backfill o verificación): POST al Live Component.
  ```
  POST https://www.fiawec.com/en/_components/Editorial:CMS:StandingComponent
  Accept: application/vnd.live-component+html
  Content-Type: application/x-www-form-urlencoded
  data={"props":<data-live-props-value tal cual, con @checksum>,"updated":{"raceId":4952}}
  ```
  Verificado: devuelve la tabla "después de São Paulo" (R4). Requiere leer antes los `props` con su `@checksum` de la página; solo `raceId` se puede escribir. GET da 405/404. Depende de detalles internos de Symfony, así que úsese solo como extra.
- **raceId 2026**: Imola 4948, Spa 4949, Le Mans 4951, São Paulo 4952, Lone Star (COTA) 4953, Fuji 4954. Barcelona y Monza aún no aparecen.
- **robots.txt**: `Allow: /` para todos.
- **Términos** (`/en/page/mentions-legales-1`): *"This website and all its contents, including texts, images, videos and **databases** are protected by copyright. LMEM only gives permission to view the content for personal and private use … and specifically **excludes use for any public display or distribution**."* Republicar las tablas en la app queda **fuera del permiso explícito**. Los puntos son hechos, pero existe el derecho sui generis de bases de datos en la UE. Sugerencia: consultar poco (unas pocas peticiones por fin de semana), citar "Fuente: FIA WEC" y, si la app crece, pedir permiso a `web@fiawec.com` (LMEM). En Al Kamel no encontré términos publicados.

## 2. Qué tabla usar

En 2026 la página trae 4 tablas (el id interno es el sufijo del `#results-NN`):

| id | Tabla | ¿Por coche? |
|---|---|---|
| 65 | FIA Hypercar World Endurance **Manufacturers'** Championship | No: 8 marcas, sin número. No encaja en el modelo. |
| 55 | FIA Hypercar World Endurance **Drivers** Championship | Filas por **tripulación**. Un coche puede tener varias filas. |
| 73 | FIA Endurance Trophy for **LMGT3 Teams** | **Sí: una fila por coche** (número y equipo). |
| 72 | FIA Endurance Trophy for **LMGT3 Drivers** | Filas por tripulación o piloto. |

En 2026 **no existe tabla oficial por coche en Hypercar**. La "World Cup for Hypercar Teams" de 2025 solo tenía a los #83 y #99.

**Recomendación**, una fila por coche en ambas categorías (encaja con `Driver(number único)` y `Standing(pos única)`):

- **LMGT3: tabla oficial de EQUIPOS (73).** `number` = nº de coche, `team` = equipo y `points` = total oficial. `name` se arma con los pilotos que tienen ese número en la tabla de pilotos LMGT3 (72), unidos con " / ", en orden de aparición (mejor primero) y hasta 3. `pos` es la oficial y viene única.
- **Hypercar: tabla de PILOTOS (55) colapsada por coche.** Se recorre en orden y se toma la **primera** fila de cada número. `points` = su total, que coincide con los puntos del coche con su tripulación de temporada completa: lo comprobé contra el máximo por fecha de sus filas. `name` = todos los pilotos que aparecen con ese número ("Rast / Frijns / Van der Linde"). `team` = fabricante (el `alt` del logo; la tabla no trae el equipo). `pos` = consecutiva 1..N entre coches. Si se prefiere, se puede guardar también la posición oficial del mejor piloto (1, 2, 4, 5, …).

Resultado con los datos de hoy: `wec/wec-2026-modelo-destino.json`. Por ejemplo, Hypercar `1 #20 BMW 75`, `2 #7 TOYOTA 75`, `3 #8 TOYOTA 64`… y LMGT3 `1 #33 TF SPORT 76`, `2 #21 VISTA AF CORSE 54`…

## 3. Estructura del HTML (selectores Jsoup)

```
comp   = doc.selectFirst("[data-live-name-value=Editorial:CMS:StandingComponent]")
props  = JSON de comp.attr("data-live-props-value")   → seasonId (4175), raceId (4953 = "después de COTA")
año    = comp.selectFirst("h2 + div").text()           → "2026"
bloque = comp.select("button[data-bs-target^=#results-]")   → texto = nombre del campeonato
tabla  = comp.selectFirst("div#results-NN table.table-standing")
thead  : Pos. | Man. | <td>N°</td> (ojo: es <td>, no <th>) | Drivers / Team | 8× columna de carrera | Total points
         columna de carrera = th > a[href=/en/race/<slug>] > span.flag:XX  (+ clase opacity-50 si aún no se corre)
tbody tr:
  td[0]            pos            "1"
  td[1] img.alt    fabricante     "BMW"
  td[2]            nº de coche    "#20"  (texto; puede traer ceros a la izquierda: "#007")
  td[3]            pilotos        a[href=/en/driver/2026/<id>] separados por ", "  |  en Teams: nombre del equipo
  td[4..11]        puntos por fecha: "25", "0", "-" (no corrida) + opcional <sup>+1</sup> (bono de pole)
  td[12]           total          "75"
```

Parser prototipo en Python (el mismo esquema sirve en Jsoup): `wec/parse_standings.py`. Transformación al modelo: `wec/to_model.py`.

- **Puntos fraccionarios**: no aparece ninguno en 2025 ni en 2026. Las carreras de 1.5× (Catar y Bahréin 2025) se redondearon a enteros: 38, 27, 23… Pero el reglamento prevé **medios puntos** si el líder no completa el 75 % del tiempo de carrera ([WEC-Magazin, Sporting Regulations](https://wec-magazin.com/regulations/sporting-regulations/)), así que podrían aparecer `12.5`. Conviene parsear como decimal y alertar si no es entero, porque el modelo usa `Int`.
- **Empates**: la `pos` hay que tomarla de la fuente, nunca calcularla por puntos. Hay empates a puntos resueltos por desempate: #20 y #7 con 75 son P1 y P2; #92 y #23 con 49 son P3 y P4. En el sitio cada tripulación va en **una** fila con posición única. En el PDF, en cambio, cada piloto va en su fila y comparten posición (1, 1, 2, 2, 2…).
- **Invariante verificado**: `total == Σ(celdas numéricas + <sup>)`, con 0 discrepancias en las 9 tablas de 2025 y 2026.

## 4. Cómo saber hasta qué ronda refleja la tabla

1. **`props.raceId`** del componente es la carrera "después de la cual" está la tabla. Su índice en el selector de carreras da el número de ronda. El selector son 8 `<a>` en orden de calendario; los disponibles llevan `data-model="raceId" data-value="…"` y el seleccionado lleva `bg-primary-subtle`. Hoy: 4953 → **ronda 5 (USA/COTA)**.
2. Chequeo cruzado: las columnas del `thead` cuya bandera **no** tiene `opacity-50` son 5 hoy.
3. **Validación de contenido**: la columna de la ronda objetivo debe tener **algún valor > 0**, porque cada carrera da 25/38/50 puntos al ganador.
4. El job para la ronda R (Fuji = 6) está listo cuando `índice(props.raceId) ≥ R` **y** la columna R tiene algo distinto de cero. Si no, se reintenta (p. ej. cada 3 h, hasta 48–72 h).
5. **No sirve** usar "la celda ya no es `-`". Durante el fin de semana, la columna de la fecha en curso aparece con **`0`** en todas las filas: hoy pasa con Fuji, y el 2026-09-05 (sábado de COTA) pasó con USA.

## 5. Qué tan actualizada está hoy

- Al **2026-09-25**, la página refleja **R5 Lone Star Le Mans (6 sep), versión final**. Los PDF de Al Kamel "Final Championship Points … After_COTA" tienen `last-modified` del 7 sep a las 05:27 UTC y dicen "FINAL".
- **R6 Fuji es este fin de semana** (carrera el 27 sep). FP1 y FP2 ya se corrieron hoy y la columna JPN ya existe, atenuada y con 0.
- Latencia que se pudo medir (Wayback):
  - El 19 abr a las 16:11 UTC (día de carrera en Imola), la página "vigente" aún mostraba la tabla **final de 2025**.
  - El 20 abr a las 20:28 UTC ya mostraba 2026 después de Imola.
  - Los PDF de Al Kamel salen entre 5 y 12 h después de la carrera: Imola el 19 abr 22:00 UTC, Spa el 9 may 22:13, São Paulo el 13 jul 00:17 y COTA el 7 sep 05:27.
  - Sugerencia: primer intento unas 6 h después de la carrera y reintentos hasta 72 h.

## 6. Rarezas (importantes para el modelo)

1. **Números con ceros a la izquierda**: los Aston Martin Hypercar son **#007** y **#009**. `"007".toInt()` = 7 **choca con el Toyota #7**. Hay que guardar el número como texto, o agregar un campo de etiqueta o mapear a un entero reservado. Hoy es la única colisión (`numerosQueChocanComoInt: [7]`).
2. **Coches fantasma en la tabla de pilotos**: el "N°" es el número *del piloto* y no siempre es el coche con el que sumó. Ricky Taylor aparece como **#101** con 2 puntos, pero en COTA corrió el #12 según el CSV de Al Kamel. Colapsar por número crea la fila "#101 Ricky Taylor 2". Se puede filtrar contra los números de la clasificación de la última carrera (CSV de Al Kamel) o aceptarlo (queda al fondo).
3. **Hypercar no tiene tabla oficial por coche en 2026** (ver §2). La vista por coche es derivada.
4. **Fila vacía**: en 2025 hubo una fila `2 · # · (sin pilotos) · 117` con posición duplicada. Hay que saltarse filas sin número o sin pilotos.
5. **Inicio de temporada**: `/en/page/manufacturers-classification` siguió mostrando la temporada anterior hasta que se cargó la R1. Hay que usar `/en/season/2026` y validar el año (encabezado "2026", `props.seasonId` y los slugs `…-2026`).
6. **Nombres**: en el sitio vienen en MAYÚSCULAS ("RYŌ HIRAKAWA", "SÉBASTIEN BUEMI"). Pasarlos a mayúscula inicial es imperfecto ("Van Der Linde", "Paul-loup"). El CSV de Al Kamel los trae como "René RAST".
7. **Sin nombre de equipo en Hypercar**: la tabla de pilotos solo da el fabricante (`img alt`). Si se quiere "BMW M Team WRT", sale de la columna `TEAM` del CSV de clasificación de carrera de Al Kamel.
8. **Celdas por fecha**: `-` = no corrida. `0` = sin puntos, **o** fecha en curso, **o** fecha posterior a la ronda consultada al pedir una ronda pasada. `<sup>+1</sup>` = bono de pole.
9. **Calendario de los PDF desactualizado**: los encabezados de Al Kamel aún dicen "Round 7 Qatar / Round 8 Bahrain". El sitio tiene lo correcto: ESP 18 oct (Barcelona) e ITA 8 nov (Monza). Para la ronda hay que confiar en el sitio, no en el PDF.
10. **Le Mans** no aparece en `fiawec.alkamelsystems.com`: la opción "03_LE MANS" devuelve el contenido de Spa. Solo afecta al respaldo por PDF.
11. **Quirk del HTML**: la cabecera "N°" es un `<td>` dentro del `<thead>`.

## 7. Respaldo: PDF oficiales de Al Kamel

```
https://fiawec.alkamelsystems.com/Results/15_2026/05_CIRCUIT%20OF%20THE%20AMERICAS/673_FIA%20WEC/Final%20Championship%20Points/
   01_2026_Hypercar_World_Endurance_Drivers_Championship_After_COTA.pdf
   02_2026_Hypercar_World_Endurance_Manufacturers_Championship_After_COTA.pdf
   04_2026_FIA_Endurance_Trophy_for_LMGT3_Drivers_After_COTA.pdf
   05_2026_FIA_Endurance_Trophy_for_LMGT3_Teams_After_COTA.pdf
```

- Para descubrir los archivos hay que hacer `GET https://fiawec.alkamelsystems.com/?season=15_2026&evvent=<NN_EVENTO>` y buscar en los `href`. La carpeta se llama "Championship Points" o "Final Championship Points".
- **La existencia del archivo `After_<EVENTO>` es la señal de ronda.** Hay que parsear con PDFBox o pdftotext; el layout es de texto con columnas.
- El PDF de pilotos no trae número de coche; el de equipos LMGT3 sí.
- También hay CSV oficial de la clasificación de carrera, `…/<ts>_Race/<NN>_Hour N/03_Classification_Race_Hour N_Final.CSV`: separado por `;`, UTF-8 con BOM, columnas `NUMBER;TEAM;DRIVER_1..5;CLASS;STATUS`. Sirve para equipos y tripulaciones, no para puntos.

## Muestras en `fuentes/wec/`

- `fiawec-season-2026.html` y `fiawec-manufacturers-classification.html`: la página actual, después de R5.
- `standings-2026-parsed.json`: las 4 tablas normalizadas.
- `comp-race4952.html` y `…-parsed.json`: el POST al Live Component "después de R4".
- `fiawec-season-2025.html` y `standings-2025-parsed.json`: la temporada 2025 completa (5 tablas, fila vacía y puntos de 1.5×).
- `wec-2026-modelo-destino.json`: una fila por coche en el modelo `Driver`/`Standing`.
- `wb-std-*.json`: capturas de Wayback usadas para medir latencia y el problema de inicio de temporada.
- `alkamel-0*_After_COTA.pdf` y `.txt`, `alkamel-cota-race-classification.csv`, `alkamel-cota.html` (índice): el respaldo oficial.
- `live-session-data.json`: el JSON de live timing (no son tablas de campeonato).
- `fiawec-robots.txt`, `fiawec-mentions-legales-1.txt` y `headers-standings.txt`.
- `parse_standings.py` y `to_model.py`: los prototipos.
