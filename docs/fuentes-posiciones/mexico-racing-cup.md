# México Racing Cup 2026 — posiciones (investigado 2026-09-27)

Las tablas de campeonato solo existen como **IMAGEN** (JPEG) en el sitio oficial. No hay tabla en
texto en ningún lado. Muestras y prototipos en `prototipos/mexico-racing-cup/`.

## Dónde están
- Menú "Puntuacion" → `https://mexicoracingcup.com/33-<slug>` (no `/resultados` ni `/32-<cat>`,
  que solo enlazan las libretas PDF de cada fecha). Apache normal, sin reto, 200 con nuestro UA.
  Sin `robots.txt` ni términos publicados (solo "© 2026 BYD México Racig Cup / DUNCAN Eventos").
  El reglamento compromete la "Publicación de resultados y puntuación en website oficial".
- Imágenes en `https://duncanwebmin.notiauto.com/repository/sitios/innerpicstmp/tinyimg<epoch_ms><azar>.jpg`
  (1165–2105 px de ancho, hasta 5330 de alto, 97 KB–2.2 MB). Tabla nueva = URL nueva en la página
  (la hora de subida va en el nombre). Solo la vigente; las viejas siguen en su URL.
- Se suben con retraso irregular (8 días a +5 semanas). La fecha impresa no es confiable: lo
  confiable es la última columna con datos. Las columnas usan la numeración de nuestro CSV.

| Categoría | Página | Refleja (2026-09-27) |
|---|---|---|
| Copa TC2000 | `33-copa-tc2000` | fecha 8 (8a y 8b = dos carreras del 12-sep) |
| Súper Turismos | `33-super-turismos-1` | fecha 5 (atrasada: faltan 6–8) |
| Copa 1.8 | `33-copa-18` | fecha 7 |
| ST Light 1 / ST Light 2 | `33-st-light-1`, `33-st-light-2` | fecha 8 (DOS campeonatos; nosotros tenemos uno) |
| F4 NACAM | `33-f4-nacam` | fecha 5 (3 carreras por fecha) |
| Endurance Challenge | `33-endurance-challenge` | fecha 3 (además tablas por clase) |
| TCR México | `33-tcr-mxico` (= `tcrmexico.com/puntuacin`) | Sprint 2–4 y Endurance 1; sin tabla absoluta |

## Columnas y reglas
Pos, Piloto ("A / B" o tripulación), Auto, División o País/Equipo, **PUNTOS REALES**, Diferencia,
puntos por carrera, SUMA y PEOR FECHA. **Sin número de coche ni id.** Oficial (art. 9.6) =
REALES = SUMA − peor fecha (la ausente cuenta 0; la sancionada no cuenta como peor). Celdas
amarillas = sanciones. TCR trae decimales (280,6).

## Lectura de imágenes
Un modelo de visión las leyó sin errores (9 tablas, 335 filas; la suma por carrera = SUMA en todas,
al primer intento). Validaciones en `transcripcion/validar.py`: V1 suma = SUMA, V2 SUMA − PEOR =
REALES, V3 diferencias, V4/V5 posiciones seguidas y ordenadas por REALES, V6 peor fecha. Las
imágenes muy altas se leen en bandas. Los errores encontrados son DEL SITIO: TC2000 ordenada por
SUMA y no por REALES, filas fuera de orden (Copa 1.8, TCR), una peor fecha mal calculada (ST Light
1), nombres inestables o con erratas, duplicados, título de imagen equivocado.

Complemento en texto: las libretas PDF de HIGH TECH Timing (resultados por carrera con número y
equipo, sin puntos) sirven para validar la columna más nueva y completar números.

## Implementado (2026-09-27)
Decisiones del usuario: **posición impresa**, **Súper Turismos Light = dos categorías** (Light 1 y
Light 2, mismo calendario), **TCR = solo la tabla "Campeonato General (Sprint)"** (la primera de su
página) y **puntos enteros** (se redondea: 280,6 → 281). Puntos = PUNTOS REALES (el total oficial).
- Fuente `mexicoracingcup-img` (parámetro = página `33-…`): revisa la página y, mientras la imagen
  vigente sea la leída, publica la lectura; las imágenes del encabezado (1100×180) y del pie
  (835×136) se descartan por proporción (más de 3 veces más anchas que altas).
- Lecturas: `PUT /admin/categories/{id}/standings-reading` (tool MCP `guardar_lectura_posiciones`)
  o versionadas en `data/campeonatos/2026/posiciones-imagen/` (el cargador las sube si cambiaron).
- Sin número de coche ni foto (la imagen no los trae); `ref` = nombre normalizado (una fila repetida
  en el sitio va con `-2`). Equipo = auto (+ división cuando la hay).
- Cuando el sitio suba una tabla nueva, la ingesta queda "aún no" con la URL que falta leer: hoy la
  lee un agente (Claude) y se carga; la lectura automática con un modelo de visión espera la llave
  de la API (ver IDEAS.md → "Importar posiciones desde una imagen").

## Recomendación y decisiones pendientes del usuario (investigación original)
- Recomendación del agente: detección, lectura y validación AUTOMÁTICAS con aprobación de un
  toque del admin (necesita una llave de la API de Anthropic en Parameter Store). Encaja con la
  función "Importar posiciones desde una imagen" de `docs/IDEAS.md`; aquí el backend SÍ puede bajar
  la imagen (sin reto), a diferencia de NASCAR México.
- Decidir: posición impresa o reordenar por REALES; guardar REALES o SUMA; partir ST Light en dos;
  TCR (Sprint, suma o dos categorías); puntos con decimales (`Standing.points` es entero);
  identidad de pilotos sin id (nombre normalizado + alias; números desde las libretas); cómo
  mostrar tablas atrasadas.
