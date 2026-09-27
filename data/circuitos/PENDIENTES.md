# Circuitos — pendientes (2026-09-25)

Estado tras la investigación de dibujos y logos y el alta de las sedes de NASCAR México y
México Racing Cup: **96 trazados en los 83 circuitos, 88 con dibujo, 64 logos**. Fuentes de cada dato en `trazados.csv`, `logos.csv` y en la propiedad
`fuente` de cada `dibujos/**/*.geojson`.

## Dibujos por trazar a mano (editor OSM del admin web → `--exportar-dibujos`)

OpenStreetMap no tiene estos circuitos o pasan por estacionamientos/recintos sin calles:

- `arlington` (Callejero) — plano: https://commons.wikimedia.org/wiki/File:Arlington_GP_Layout.png
- `san_diego_street` (Callejero) — plano: https://commons.wikimedia.org/wiki/File:Coronado_Street_Course_2026.png
- `excel_london`, `sanya_street`, `tokyo_street`, `tempelhof` (Callejero, Fórmula E) —
  sobre el mapa oficial de FE (Tempelhof: la vía de OSM es una variante anterior).
- `miguel_e_abed` (NASCAR corto, 2,590 m) — **recorrido no publicado** (solo "combina parte
  del óvalo con el circuito"). Hay dos candidatos armados con ways de OSM en el scratchpad de
  la sesión del 2026-09-25 (no versionados): **B** óvalo + infield central por el enlace de la
  recta opuesta (way 677361279) y T7–T10 del Internacional corto, 2,605 m (+0.6 %; con las
  eses T1–T3 da 2,845 m ≈ los 2,840 m del "Long NASCAR Road Course"); **A** óvalo + lazo
  derecho T11–T14, 2,529 m (−2.4 %). Confirmar con NASCAR México, el autódromo o alguien que
  trabajó La Poblana 75 (17-may-2026) y trazarlo en el editor.
- `aeropuerto_tulum` (Óvalo, 600 m, TEMPORAL) — OSM no tiene la pista y la imagen satelital
  disponible es anterior al evento. Dibujarlo sobre la plataforma de aviación general (junto
  al hangar "FBO Tulum", OSM way 1218655877) con fotos/videos aéreos del evento; prioridad
  baja (una sola fecha). De paso confirmar que el óvalo estuvo ahí (las coordenadas del
  catálogo son inferidas).

Aproximados (afinar si OSM los mapea): `jeddah_corniche/formula-e` sin las chicanas
T8–T9/T10–T11; `jarama/formula-e` sin la chicana temporal T12–T14 (hoy igual al GP).

Óvalos cuyo dibujo (revisado sobre satélite) difiere del largo publicado por la línea con
que se mide: `darlington` (−4.4%), `dover` (+3.2%), `north_wilkesboro` (−16%: el óvalo
mide ~0.525 mi aunque se publica 0.625), `indianapolis_raceway_park` (−4%), `phoenix`
(+3.4% contra 1 mi; +1.1% contra 1.022 mi medidas). Óvalos mexicanos (ways de OSM):
`ovalo_aguascalientes` (−4.6%: way con pocos vértices por una pista muy ancha),
`super_ovalo_chiapas` (+2.9% contra 1,200 m; +2.1% contra 1.21 km), `super_ovalo_potosino`
(−2.7%) y `queretaro/ovalo` (+2.0%; el Completo del mismo mapeo sale −2.0%: parece escala o
línea de medición de OSM).

## Trazados por confirmar o crear

- `rodriguez` — el E-Prix de 2027 (Gen4) usará el trazado completo de F1 con la chicana de
  la T1: esa carrera va con "Gran Premio", no con "Fórmula E" (que es el de 2026).
- `americas`, `zandvoort` — Fórmula E 2026-27 aún no publica su trazado ahí.
- `brands_hatch` — FE 2026-27 usará el GP "con pequeñas modificaciones" (revisar el mapa).
- `monterrey` — las fuentes no coinciden (2.965 / 3.200 / 3.409 km); se usó 2.965 km, que
  cuadra con el dibujo. Confirmar largo y curvas con el autódromo.
- `lime_rock` — largo de NASCAR 2026 (1.478 mi); el circuito publica 1.53 mi.
- `atlanta` — se llama oficialmente **EchoPark Speedway** desde 2025 (el logo es el nuevo);
  el catálogo y el feed de NASCAR siguen con "Atlanta Motor Speedway".
- `rodriguez` / **Óvalo con Estadio** (1,665 m) — **sentido horario** (mapa "Oval Circuit with
  Foro Sol" de racingcircuits.info; solo el óvalo simple es antihorario); la libreta de
  HIGH TECH del 7-feb-2026 no trae flecha: confirmar. Las 7 curvas son inferidas (Curva
  Plana + T12–T17 del GP).
- `rodriguez` / **Nacional** — 10 curvas y sentido del mapa T1–T10 de las libretas de HIGH
  TECH (may/jul/ago 2026). Wikipedia registra un "National Circuit with Chicane" de 3,850 m
  en el GP LTH, pero la libreta trae el mismo mapa sin chicana.
- `miguel_e_abed` / **NASCAR corto** — sentido antihorario y 9 curvas inferidos (usa las
  peraltadas del óvalo; curvas contadas sobre el candidato B).
- `miguel_e_abed` / **Internacional corto** — 2,930 m (organizador y cronometraje); Wikipedia
  da 2,982 y el dibujo de OSM 2,952. Las 15 curvas cuentan la peraltada NE como una (T15).
- `autodromo_yucatan` — confirmar que NASCAR México (17 y 18-oct) usa el Completo (único
  trazado del autódromo). Largo en duda: 3,340 m (RacingCircuits, Expansión, Wikipedia; cuadra
  con el dibujo) contra "3,5 km +/-" del sitio oficial y MYLAPS. OJO: el plano de Commons
  `File:Autodromo-Yucatan.png` trae el sentido al revés; es antihorario (fotos oficiales).
- Curvas o sentido **inferidos** (sin fuente que dé el total): 4 curvas de
  `super_ovalo_chiapas` y `aeropuerto_tulum`; sentido de `super_ovalo_potosino` (OSM
  `oneway`) y `aeropuerto_tulum` (convención de óvalos NASCAR); `ovalo_aguascalientes` usa
  las 4 curvas que nombra nascar.mx (en.wikipedia dice 3).
- `aeropuerto_tulum` — la `ubicacion` dice Tulum (como lo nombran NASCAR y la prensa), pero
  el aeropuerto está en el municipio de Felipe Carrillo Puerto (≈20 km al SO).
- **Trazados que existen pero no se crearon** (por si una fecha los usa): `rodriguez` —
  **Óvalo 1,607/1,609 m, antihorario** (NASCAR México lo usó en la final de 2024; el de la
  fecha 12 de 2026, 8-nov, no está publicado), Gran Premio sin Foro Sol 4,256 m, Nacional
  con Foro Sol 3,909 m, recta principal 1,100 m, estadio 800–2,500 m; `miguel_e_abed` —
  "Long NASCAR Road Course" 2,840 m y "Resistencia" 3,300 m (dudoso: armado con OSM da
  3,082); `queretaro` — Completo en sentido inverso (el GP Ida y Vuelta de mar-2026 corrió en
  ambos sentidos); `monterrey` — óvalo "El Frijol" 1,600 m / 5 curvas (la fecha de NASCAR
  México del 5-sep se canceló).

## Circuitos sin logo

Sin logo propio (solo existe el del evento/promotor, que no se usa): `albert_park`,
`monaco`, `marina_bay`, `vegas`, `villeneuve`, `long_beach`, `st_petersburg`, `detroit`,
`arlington`, `markham`, `washington_dc`, `excel_london`, `sanya_street`,
`sao_paulo_street`, `tempelhof`, `tokyo_street`, `aeropuerto_tulum` (sede temporal).
No se consiguió: `shanghai` (kit de prensa del SIC), `queretaro` (solo Facebook).
Mejorables (fuente de baja resolución): `michigan`, `new_hampshire`, `jarama`, `monterrey`,
`ovalo_aguascalientes` (original de 300×157 px). `super_ovalo_potosino` es la versión de
aniversario "43 años": cambiarla por la estándar si aparece.
