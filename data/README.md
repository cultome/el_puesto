# Datos reales de El Puesto

Fuente de verdad **versionada** de los datos que se cargan al sistema (catálogos, calendarios).
Nada aquí es de prueba: cada fila viene de una fuente citada y consultada en una fecha.
Se sube al backend con `scripts/cargar-datos.py` (vía la API admin), que se puede correr
varias veces sin duplicar.

- `circuitos/circuitos.csv` — catálogo de circuitos (sedes de todos los campeonatos).
- `circuitos/trazados.csv` — trazados (configuraciones) de un circuito: longitud, curvas, sentido.
- `circuitos/dibujos/<circuito>/<trazado>.geojson` — dibujo (silueta) de un trazado:
  sus vértices lat/lon en el sentido de carrera.
- `circuitos/logos.csv` + `circuitos/logos/` — logo de cada circuito (archivo, fuente y
  licencia).
- `circuitos/posiciones/<circuito>/<trazado>.csv` — puestos y activos de un trazado
  (etiqueta, tipo, lat/lon). Se acomodan en el editor del admin web y se traen de vuelta
  con `--exportar-posiciones`. Las que salen de un plano que no es público (hoy el del GP de
  la Ciudad de México, de OMDAI) van en `privado/circuitos/posiciones/` con el mismo formato.
- `eventos/eventos.csv` — eventos (fechas, circuito, trazados, campeonatos) y la ruta a su
  roster privado; `privado/eventos/roles-omdai.csv` traduce las columnas del "Track
  Personnel" de OMDAI (CPM, COM, BF, YF, TSP, INT 1–7, OP) a los roles de la app.
- `campeonatos/<año>/*.csv` — calendarios: una fila por fecha de una categoría.
- `campeonatos/<año>/FUENTES.md` — fuentes, cancelaciones y notas de verificación.
- `campeonatos/fuentes-posiciones.csv` — fuente de las posiciones AUTOMÁTICAS de cada categoría
  (`fuente` = id del backend, `parametro` según la fuente).
- `campeonatos/<año>/posiciones-imagen/<categoria>.json` — tablas de posiciones LEÍDAS de la
  imagen que publica el sitio (hoy la México Racing Cup; ver abajo).
- `campeonatos/logos.csv` + `campeonatos/logos/` — logo de cada campeonato (archivo, fuente y
  licencia).
- `privado/` — **NO versionado** (`.gitignore`): datos personales (cuentas, oficiales,
  rosters) y material de OMDAI que no es público (posiciones del plano, mapeo de roles).
  Mismo formato y mismo script; ver `privado/LEEME.md`.

**Atribución**: los dibujos de trazados marcados como OpenStreetMap son © OpenStreetMap
contributors (ODbL, https://www.openstreetmap.org/copyright); cada archivo cita su fuente en
`properties.fuente`. Los logos son marcas de sus dueños (fuente y condición de uso en cada
`logos.csv`) y se usan solo para identificar el circuito o campeonato.

Formato común: CSV UTF-8, separador coma, primera fila = encabezados, fechas ISO
`AAAA-MM-DD`, textos con coma entre comillas dobles.

## `circuitos/circuitos.csv`

| columna | contenido |
|---|---|
| `clave` | identificador estable en minúsculas con `_` (p. ej. `monza`, `rodriguez`). Lo usan los calendarios. **No se cambia** una vez publicado. |
| `nombre` | nombre oficial del circuito (como lo publica el circuito/serie). Es la llave con la que el script encuentra el circuito en el sistema: cambiarlo crea otro. |
| `ubicacion` | texto que ve el usuario, en español: `Ciudad, País` (en México: `Ciudad, Estado`). |
| `pais` | país en español. |
| `lat`, `lon` | coordenadas decimales (opcional). |
| `fuente` | URL de donde salió el dato. |

## `campeonatos/<año>/*.csv` (calendarios)

| columna | contenido |
|---|---|
| `campeonato` | nombre del campeonato (la serie) en la app, **sin año** (p. ej. `F1`, `FE`, `NASCAR`); es el nombre que se ve en la app. Se crea una vez; sus años son temporadas. |
| `temporada` | etiqueta de la temporada dentro del campeonato: el año (`2026`) o, si cruza el año, `2025-26`. Con `campeonato` identifica la temporada; el año para ordenar se deriva (2025-26 → 2026). |
| `categoria` | categoría dentro del campeonato (p. ej. `Fórmula 2`). |
| `ronda` | número de ronda oficial de esa categoría (numeración vigente, tras cancelaciones). |
| `evento` | nombre del evento como lo publica la serie, sin el patrocinador del título cuando lo tiene (F1/WEC/WRC/FE: `Australian Grand Prix`, `Rallye Monte-Carlo`, `Mexico City E-Prix`); NASCAR/IndyCar: nombre oficial de la carrera. |
| `fecha_inicio` | primer día del fin de semana del evento (prácticas / shakedown), si la fuente lo da; si no, vacío. |
| `fecha` | día de la carrera principal. Rallies: último día. Carreras de resistencia de más de un día (24 h): el día en que arrancan. |
| `circuito` | `clave` de `circuitos/circuitos.csv`. Vacío solo para rallies (que corren en tramos, no en un circuito) o para una fecha cuya sede aún no tiene autódromo confirmado (se documenta en `FUENTES.md`). |
| `sede` | texto `Ciudad, País` en español. Obligatorio si `circuito` está vacío; si hay circuito, vacío (se toma del catálogo). |
| `fuente` | URL de donde salió la fila. |
| `consultado` | fecha en que se consultó la fuente. |

Las fechas **canceladas** no se incluyen (se documentan en `FUENTES.md`).

El calendario de la app se ordena **por fecha**: si la serie conserva el número oficial de una
fecha reprogramada (NASCAR México 2026: la 9 se corre después de la 10), se deja su número.

## `campeonatos/logos.csv`

| columna | contenido |
|---|---|
| `campeonato` | nombre del campeonato tal como va en los calendarios (`F1`, `NASCAR México`). |
| `archivo` | nombre del archivo en `campeonatos/logos/` (PNG o JPEG, cuadrado, opaco — el sistema lo recorta al centro y lo pasa a JPEG con fondo blanco). |
| `fuente` | URL del archivo original. |
| `licencia` | licencia o condición de uso. |

Como con los circuitos, el cargador sube el logo **solo si el campeonato aún no tiene uno**;
`--recargar-logos` los vuelve a subir todos.

## `circuitos/dibujos/<circuito>/<trazado>.geojson`

Carpeta = `clave` del circuito; archivo = nombre del trazado en minúsculas con guiones
(`Gran Premio` → `gran-premio.geojson`). Un `Feature` GeoJSON con geometría `LineString`
(`[lon, lat]`, un vértice por renglón), **en el sentido de carrera** y sin repetir el
primer vértice al final (el cierre es implícito). `properties`: `circuito`, `trazado` y
`fuente` (obligatoria: de dónde salió el dibujo — p. ej. `bacinger/f1-circuits (MIT)` u
`OpenStreetMap ways … (© OpenStreetMap contributors, ODbL)`). El cargador lo sube con
`PUT /admin/trazados/{id}/path` solo si cambió. Un trazado dibujado a mano en el editor del
admin web se trae a `data/` con `--exportar-dibujos`.

## `circuitos/logos.csv`

| columna | contenido |
|---|---|
| `circuito` | `clave` del circuito. |
| `archivo` | nombre del archivo en `circuitos/logos/` (PNG o JPEG, cuadrado, opaco — el sistema lo pasa a JPEG con fondo blanco). |
| `fuente` | URL del archivo original. |
| `licencia` | licencia o condición de uso (p. ej. dominio público, marca registrada). |

El cargador sube el logo **solo si el circuito aún no tiene uno** (no pisa uno subido a
mano en el admin); `--recargar-logos` los vuelve a subir todos.

## `circuitos/posiciones/<circuito>/<trazado>.csv`

(o `privado/circuitos/posiciones/…` si el plano de origen no es público; un trazado vive en
una sola de las dos). Carpeta = `clave` del circuito; archivo = nombre del trazado en minúsculas con guiones
(`Gran Premio` → `gran-premio.csv`).

| columna | contenido |
|---|---|
| `tipo` | `PUESTO` o un tipo de activo: `TELEHANDLER`, `IFRT`, `HIAB` (también los Flat Bed), `DRIVER_RIDER`, `AMBULANCIA`, `TRACK_SWEEPER`, `SAFETY_CAR`. |
| `etiqueta` | lo que ve el usuario y la llave con la que se cruzan las asignaciones (`11.7`, `TH3`, `FB1`). |
| `numero` | solo puestos: entero ≥ 1 (orden de vuelta). |
| `lat`, `lon` | coordenadas; vacías solo si `en_mapa = no`. |
| `en_mapa` | `no` = posición asignable sin lugar en el mapa (p. ej. `Coordinación de zona`). |
| `fuente` | de dónde salió la posición. |

## `campeonatos/<año>/posiciones-imagen/<categoria>.json`

Para campeonatos que solo publican su clasificación como IMAGEN. Una por categoría:
`{campeonato, temporada, categoria, imageUrl, throughRound, leidaEl, nota, rows:[{pos, name,
team, points}]}` — `imageUrl` = la imagen exacta del sitio que se leyó, `throughRound` = última
fecha con datos en la numeración del calendario (la fecha impresa no es confiable), `pos` = la
IMPRESA, `points` = el total oficial impreso (el backend lo redondea a entero). Al leer una tabla
nueva: verificar que la suma de puntos por carrera dé el total de cada fila (validador en
`docs/fuentes-posiciones/prototipos/mexico-racing-cup/transcripcion/validar.py`), actualizar el
JSON y correr el cargador; la categoría debe tener la fuente `mexicoracingcup-img` en
`fuentes-posiciones.csv`. Mientras el sitio muestre esa imagen se publica la lectura; si sube otra,
el panel del admin avisa que falta leerla.

## Eventos y rosters

`eventos/eventos.csv`: `nombre, inicio, fin, circuito, trazados` (nombres separados por
`|`), `campeonatos` (`Nombre:temporada`, separados por `|`), `autoregistro` (`si` | `no`;
vacío = `si`: los oficiales pueden registrar por honor desde la app que trabajaron el
evento — `no` en los eventos cuya participación sale solo del roster, como el GP),
`roster` (ruta dentro de `privado/`), `fuente`. El roster privado (`posicion, tipo_origen, rol_origen, omdai_id,
nombre`) se genera del Excel de OMDAI con `scripts/importar-roster.py` (requiere openpyxl).
Al cargar: se crean los oficiales que falten (por OMDAI ID) y se REEMPLAZAN las
asignaciones del evento con las del roster (el roster es la fuente de verdad).

## Cargar al sistema

```sh
fish -c 'source ./setenv; python3 scripts/cargar-datos.py --dry-run'   # valida sin escribir
fish -c 'source ./setenv; python3 scripts/cargar-datos.py'             # carga
fish -c 'source ./setenv; python3 scripts/cargar-datos.py --exportar-posiciones'  # trae al CSV lo acomodado en el editor (y los puestos nuevos aprobados desde propuestas)
```

Usa `EL_PUESTO_API` y `EL_PUESTO_ADMIN_KEY` del entorno (o `--api` / `--key`).

**Puestos propuestos por los oficiales**: al aprobarlos en el admin, el sistema crea
puestos que los CSV de posiciones no conocen. La carga NO los archiva en silencio: se
detiene y pide `--exportar-posiciones` (los agrega al CSV con su fuente; revisar y hacer
commit) o `--archivar-sobrantes` para archivarlos a propósito.
