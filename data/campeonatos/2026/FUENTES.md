# Fuentes y verificación — calendarios 2026

Consultados el **2026-09-24**. Cada fila de los CSV cita su fuente (`fuente`); aquí van las
decisiones, cancelaciones y dudas por campeonato. Las fechas son **locales** al circuito.

| Campeonato | Categoría | Fechas |
|---|---|---|
| F1 | Fórmula 2 | 14 |
| F1 | Fórmula 3 | 9 |
| F1 | F1 Academy | 6 |
| F1 | Fórmula 1 | 23 |
| FE · 2025-26 | Fórmula E | 17 |
| FE · 2026-27 | Fórmula E | 21 |
| IndyCar | IndyCar Series | 18 |
| IndyCar | Indy NXT | 17 |
| NASCAR | Cup Series | 36 |
| NASCAR | O'Reilly Auto Parts Series | 33 |
| NASCAR | Craftsman Truck Series | 25 |
| WEC | Hypercar | 8 |
| WEC | LMGT3 | 8 |
| WRC | WRC | 13 |
| WRC | WRC2 | 13 |
| WRC | WRC3 | 13 |
| WRC | Junior WRC | 5 |
| NASCAR México | NASCAR México Series | 12 |
| NASCAR México | NASCAR Challenge Series | 12 |
| NASCAR México | Trucks México Series | 12 |
| México Racing Cup | Copa TC2000 | 10 |
| México Racing Cup | Súper Turismos | 10 |
| México Racing Cup | Copa 1.8 | 10 |
| México Racing Cup | Súper Turismos Light 1 | 10 |
| México Racing Cup | Súper Turismos Light 2 | 10 |
| México Racing Cup | TCR México | 7 |
| México Racing Cup | F4 NACAM | 7 |
| México Racing Cup | Endurance Challenge | 5 |

**Pendiente de re-verificar** (cambian después de esta fecha):
- F1 / F2: Catar y Abu Dhabi (fin de temporada) — F1 decide a mediados de octubre si se
  corren (respaldo europeo: Imola o Portimão).
- Fórmula E 2026-27: calendario **provisional**; Jeddah (rondas 1–2, 18–19 dic) podría
  posponerse y abrir en Ciudad de México el 16 ene 2027 (renumeraría todo).
- WRC: la cancelación de Arabia Saudita espera ratificación formal del WMSC.

## Fórmula 1

- Fuente: API pública Jolpica (sucesora de Ergast), `https://api.jolpi.ca/ergast/f1/2026/races/`;
  confirmada contra formula1.com y prensa.
- **23 rondas** (se anunciaron 24): los GP de **Bahréin y Arabia Saudita** (12 y 19 de abril)
  se cancelaron por el conflicto en Medio Oriente; en julio se reinstaló el "Bahrain Grand
  Prix" **en Sepang, Malasia** (4 oct). Arabia Saudita no se repone.
  https://www.skysports.com/f1/news/13566600/malaysia-added-to-2026-f1-calendar-in-october-to-host-postponed-bahrain-gp-amid-continued-conflict-in-middle-east
- Azerbaiyán se corre en **sábado** (26 sep).
- Jolpica da fechas en UTC: **Las Vegas** se corrigió a hora local (FP1 jue 19, carrera sáb 21
  nov). Las otras 22 coinciden en hora local (verificado con las horas de sesión).

## NASCAR

- Fuente: API pública de NASCAR, `https://cf.nascar.com/cacher/2026/<serie>/race_list_basic.json`
  (1 = Cup, 2 = O'Reilly Auto Parts, 3 = Craftsman Truck).
- Solo carreras **por puntos** (`race_type_id = 1`); las de exhibición (Cook Out Clash,
  Duels de Daytona, All-Star) no son rondas del campeonato.
- `fecha` = día real de la carrera (`race_date`; una carrera de Truck en Charlotte se
  reprogramó del 22 al 23 de mayo). `fecha_inicio` = primera práctica/calificación del
  fin de semana (hora UTC ajustada −5 h).
- La final de Cup 2026 es en **Homestead-Miami** (8 nov).

## Notas — categorías de soporte de F1 2026 (`f1.csv`)

Consultado: **2026-09-24**. Categorías: Fórmula 2 (14 rondas), Fórmula 3 (9), F1 Academy (6).

### Fuentes

**Primarias (oficiales)** — de ellas sale cada fila:

- Calendarios: <https://www.fiaformula2.com/Calendar>, <https://www.fiaformula3.com/Calendar>,
  <https://www.f1academy.com/Racing-Series/Calendar> (número de ronda y rango de fechas).
- Página de cada ronda (la `fuente` de cada fila): `https://www.fiaformula2.com/en/racing/2026/<sede>`,
  `https://www.fiaformula3.com/en/racing/2026/<sede>`, `https://www.f1academy.com/Racing-Series/Results?raceid=<n>`.
  De su horario (JSON embebido con hora local + `gmtOffset`) salen `fecha_inicio` (día de la
  práctica) y `fecha` (día de la última carrera del fin de semana). El nombre del evento de
  F2/F3 es el `meetingName` que publican esas páginas (nombre del GP de F1, sin patrocinador).

**Contraste independiente** (todas las filas coinciden):

- Wikipedia: [2026 Formula 2 Championship](https://en.wikipedia.org/wiki/2026_Formula_2_Championship),
  [2026 FIA Formula 3 Championship](https://en.wikipedia.org/wiki/2026_FIA_Formula_3_Championship),
  [2026 F1 Academy season](https://en.wikipedia.org/wiki/2026_F1_Academy_season). Tablas de
  calendario: coinciden la ronda, la sede y el día de la carrera principal de las 29 filas.
- Horarios completos de cada fin de semana en formula1.com (listan las sesiones de F2/F3/F1 Academy
  por día local). Confirman el día de práctica y el de la carrera principal de todas las rondas ya
  publicadas:
  [AUS](https://www.formula1.com/en/latest/article/formula-1-qatar-airways-australian-grand-prix-2026.3WPmeYNnxPoMvNtp6AAxx9) ·
  [CHN](https://www.formula1.com/en/latest/article/formula-1-heineken-chinese-grand-prix-2026.76iAtl4siBaqL4ZQ4jUmRi) ·
  [MIA](https://www.formula1.com/en/latest/article/formula-1-crypto-com-miami-grand-prix-2026.1qMVtvBLsKbjQeRsVuHeGk) ·
  [CAN](https://www.formula1.com/en/latest/article/formula-1-lenovo-grand-prix-du-canada-2026.2gnv5Mah7Gjaf7fhooh1N5) ·
  [MON](https://www.formula1.com/en/latest/article/formula-1-louis-vuitton-grand-prix-de-monaco-2026.5eqj7xSRWW6dylGmncfs6T) ·
  [BCN](https://www.formula1.com/en/latest/article/formula-1-msc-cruises-gran-premio-de-barcelona-catalunya-2026.Jc4qZQHWhFwetPKF7z3S5) ·
  [AUT](https://www.formula1.com/en/latest/article/formula-1-lenovo-austrian-grand-prix-2026.7jl4utJeIV75DpBVcPFDQU) ·
  [GBR](https://www.formula1.com/en/latest/article/formula-1-pirelli-british-grand-prix-2026.6doq6E52hg1t1YuKl8xtFR) ·
  [BEL](https://www.formula1.com/en/latest/article/formula-1-moet-and-chandon-belgian-grand-prix-2026.6TSnaKuwMr6OvrtF0gZbXF) ·
  [HUN](https://www.formula1.com/en/latest/article/formula-1-aws-hungarian-grand-prix-2026.6rqroq0TO24FJxxSOxwXVq) ·
  [NED](https://www.formula1.com/en/latest/article/formula-1-heineken-dutch-grand-prix-2026.VYghWPhEDqYBlWbd1iKe6) ·
  [ITA](https://www.formula1.com/en/latest/article/formula-1-pirelli-gran-premio-ditalia-2026.7zaXkRgenHXVVXXGb1RGcf) ·
  [ESP/Madrid](https://www.formula1.com/en/latest/article/formula-1-tag-heuer-gran-premio-de-espana-2026.3EpMV9Idk1jwxq3KtxcC6x) ·
  [AZE](https://www.formula1.com/en/latest/article/formula-1-qatar-airways-azerbaijan-grand-prix-2026.EsMvgTyba8dZZ6RynZXv7) ·
  [USA](https://www.formula1.com/en/latest/article/formula-1-msc-cruises-united-states-grand-prix-2026.2u6li2pzNhPzL3TSYaagrm).
- Rondas sin horario de formula1.com todavía: F2 Lusail/Yas Marina → el anuncio oficial del
  calendario (27–29 nov y 4–6 dic) + Wikipedia; F1 Academy Austin → práctica el jueves 22 oct
  confirmada por [Visit Austin](https://www.austintexas.org/austin-insider-blog/blog/post/f1-returns-to-austin-circuit-of-the-americas/)
  ("Grand PrixView Thursday, October 22", con F1 Academy en pista) y
  [f1academycalendar.com](https://f1academycalendar.com/); F1 Academy Las Vegas → práctica el jueves
  19 nov según f1academycalendar.com, carrera principal el 21 nov según Wikipedia.

### Cancelaciones y cambios

- **Bahréin y Arabia Saudita (abril) cancelados** por el conflicto en Medio Oriente
  ([F1, 14-mar-2026](https://www.formula1.com/en/latest/article/bahrain-and-saudi-arabian-grands-prix-will-not-take-place-in-april.1hnqllVG85RSt8pbFc5Ivx):
  "The Formula 2, Formula 3 and F1 ACADEMY rounds will also not take place during their scheduled times").
  - **F2**: el calendario original ([10-jun-2025](https://www.fiaformula2.com/Latest/28OvYB98ektUm0D8vrUgYS/fia-formula-2-championship-2026-season-calendar-revealed), 14 rondas)
    tenía Sakhir (R2) y Jeddah (R3). Se **reemplazaron por Miami (1–3 may) y Montreal (22–24 may)**
    ([anuncio F2](https://www.fiaformula2.com/Latest/piQa16CR1LT7Ks5rIkqAM/miami-and-montreal-to-host-fia-formula-2-championship-rounds-in-2026)),
    así que siguen siendo 14 rondas con numeración nueva.
  - **F3**: el calendario original ([10-jun-2025](https://www.fiaformula3.com/Latest/16F8XBnz2PSr8Rn29WCsIs/fia-formula-3-championship-2026-season-calendar-revealed), 10 rondas)
    tenía Bahréin (R2). No se sustituyó: quedan **9 rondas**, y el cierre en Madrid tuvo una
    segunda clasificación y una **segunda carrera principal** para compensar
    ([anuncio F3](https://www.fiaformula3.com/Latest/1VGQYdEuNMGEGVM51PyEDH/fia-formula-3-to-hold-official-tests-at-madring-in-august-as-the-2026-f3-season-finale-expands-with-additional-feature-race)).
  - **F1 Academy**: el calendario original ([F1, 10-dic-2025](https://www.formula1.com/en/latest/article/f1-academy-unveils-calendar-for-2026-season.7nDrvikRZ2Q0UaDPd3HzZ), 7 rondas)
    tenía Jeddah (R2, 17–19 abr). No se sustituyó: quedan **6 rondas**, y Montreal y Austin pasan a
    tener tres carreras (se agrega una "Opening Race")
    ([anuncio F1 Academy, 1-abr-2026](https://www.f1academy.com/Latest/2yLXCGLOvdRDoHwDlYxj6h/f1-academy-introduces-three-race-weekend-format-in-montreal-and-austin)).
    El enlace `Results?raceid=23` (la ronda de Jeddah) redirige a la nota de cancelación.
- **Sepang ("Bahrain Grand Prix in Malaysia", 2–4 oct): no corren F2, F3 ni F1 Academy.** El
  [horario oficial](https://www.formula1.com/en/latest/article/formula-1-gulf-air-bahrain-grand-prix-in-malaysia-2026.1IHoy7D2dfCIugb5mH78cE)
  solo trae la Formula Trophy Malaysia como soporte (lo confirma
  [GPDestinations](https://gpdestinations.com/trackside-malaysian-f1-grand-prix/): "There will only be one support
  category at Sepang this year, Formula Trophy Malaysia"). Ninguna de las tres series tiene a
  Sepang en su calendario.
- **F2 Baku (R12)**: las carreras se adelantaron un día por el Día del Recuerdo de Azerbaiyán, y la
  ronda se amplió ([F2, 14-sep-2026](https://www.fiaformula2.com/en/latest/article/fia-formula-2-announce-supersized-baku-round.5ixzvM3F663c6zjTWZHLDX)):
  práctica y dos clasificaciones el jue 24, sprint y Feature Race 1 el vie 25, **Feature Race 2 el sáb 26**.
  Por eso `fecha` = 2026-09-26 (la última carrera del fin de semana).
- **F3 Madrid (R9)**: tuvo Feature Race 1 (sáb 12) y **Feature Race 2 (dom 13)**, así que `fecha` = 2026-09-13.
- **Mónaco**: F2 y F3 tienen práctica el **jueves 4 jun**, un día antes que la F1 (`fecha_inicio` = 2026-06-04).
- **F1 Academy Austin (R5)**: la práctica y la clasificación son el **jueves 22 oct**, un día antes
  que la F1. El anuncio original decía 23–25 oct; la página oficial actual dice 22–25 oct.
- **F1 Academy Las Vegas (R6)**: la práctica es el jue 19 nov y la carrera principal el **sáb 21 nov**.

### Criterios aplicados

- `fecha` = día de la última carrera de esa serie en el fin de semana: la Feature Race en F2/F3 y
  en F1 Academy; en Baku (F2) y Madrid (F3) es la Feature Race 2.
- `evento` de F1 Academy: su sitio nombra las rondas solo por ciudad (Shanghai, Montreal…), así que
  se usó el nombre del GP de F1 anfitrión, igual que en `f1.csv` (Chinese, Canadian, British, Dutch,
  United States y Las Vegas Grand Prix).
- Circuitos: todas las sedes ya existen en `circuitos.csv`, así que **no hubo circuitos nuevos** ni se
  creó `_nuevos_f1.csv`.

### Dudas / riesgos (no omitidos, pero a vigilar)

- **F2 R13 Lusail (27–29 nov) y R14 Yas Marina (4–6 dic)** siguen en el calendario oficial al
  2026-09-24, pero siguen en riesgo por el conflicto. La F1 los mantiene
  "confirmados", aunque pospuso la decisión final a **mediados de octubre** y tiene un plan de
  contingencia en Europa (Imola/Portimão) ([The National, 24-sep-2026](https://www.thenationalnews.com/sport/f1/2026/09/24/lewis-hamilton-backs-f1-season-to-finish-in-qatar-and-abu-dhabi/),
  [Motorsport.com, 23-sep-2026](https://www.motorsport.com/f1/news/whats-behind-the-delay-in-cancelling-the-qatar-and-abu-dhabi-gps/10858214/)).
  Hay que revisarlos después de mediados de octubre.
- Rondas futuras (F2 R13–14, F1 Academy R5–6): los horarios siguen "TBC". Los días vienen de las
  sesiones que ya publica el sitio oficial. Las horas son todavía de relleno.
- Discrepancias menores con Wikipedia, que no afectan las columnas: el artículo del round de Mónaco de
  F2 dice "held between 5 and 7 June", pero el sitio oficial y formula1.com ponen la práctica el
  4 jun (se usó el 4). Para Austin, Wikipedia pone la Opening Race de F1 Academy el 24 oct, mientras
  que el sitio oficial y formula1.com la ponen el viernes 23.
- La página `fiaformula2.com/Calendar` parecía desactualizada (mostraba Monza como "Next Race" sin
  resultados de Monza ni de Madrid), aunque las páginas por ronda sí marcan Madrid como terminada. Las fechas
  coinciden en ambas.

### Observación fuera de alcance (no se modificó)

- En `f1.csv`, el **Las Vegas Grand Prix** tiene `2026-11-20 → 2026-11-22`, que parecen fechas **UTC**
  (de la API de Ergast). En hora local (UTC−8), formula1.com da FP1 el jue 19 nov a las 16:30 y la carrera el
  **sáb 21 nov a las 20:00**, es decir `2026-11-19 → 2026-11-21`. Es consistente con F1 Academy Las Vegas
  (19–21 nov).

## WEC 2026 — notas de verificación

Archivo: `wec.csv` (campeonato `WEC`, temporada `2026`).
Consultado: 2026-09-24 (víspera de la FP1 de Fuji, ronda 6).

### Resultado

- **8 rondas vigentes**, cada una escrita dos veces (`Hypercar` y `LMGT3`) = **16 filas**.
  Las dos clases corren **todas** las rondas: las rondas 1–5 ya tienen ganador en ambas
  clases (Wikipedia, tabla de resultados) y los horarios oficiales de Fuji, Barcelona y
  Monza incluyen Qualifying/Hyperpole de LMGT3 y de HYPERCAR. Ninguna ronda se omitió
  para una clase.
- `fecha_inicio` = día de la Free Practice 1 (inicio del evento según fiawec.com);
  `fecha` = día en que arranca la carrera (Le Mans: sábado 13 de junio, 16:00 CEST).
- `evento` sin patrocinador del título: "TotalEnergies 6 Hours of Spa-Francorchamps" →
  `6 Hours of Spa-Francorchamps`; "ROLEX 6 Hours of São Paulo" → `6 Hours of São Paulo`.

| Rnd | Evento | FP1 | Carrera | circuito |
|---|---|---|---|---|
| 1 | 6 Hours of Imola | 2026-04-17 | 2026-04-19 | `imola` (nuevo) |
| 2 | 6 Hours of Spa-Francorchamps | 2026-05-07 | 2026-05-09 | `spa` |
| 3 | 24 Hours of Le Mans | 2026-06-10 | 2026-06-13 | `le_mans` (nuevo) |
| 4 | 6 Hours of São Paulo | 2026-07-10 | 2026-07-12 | `interlagos` |
| 5 | Lone Star Le Mans | 2026-09-04 | 2026-09-06 | `americas` |
| 6 | 6 Hours of Fuji | 2026-09-25 | 2026-09-27 | `fuji` (nuevo) |
| 7 | 6 Hours of Barcelona | 2026-10-16 | 2026-10-18 | `catalunya` |
| 8 | 6 Hours of Monza | 2026-11-06 | 2026-11-08 | `monza` |

### Fuentes

Primaria (fiawec.com, oficial):
- Página de cada carrera (rango "From X to Y", horario por sesión y JSON-LD
  `startDate`/`endDate`, todos coinciden): `https://www.fiawec.com/en/race/<slug>-2026`
  (la URL exacta va en la columna `fuente` de cada fila).
- 2026-03-03 — Qatar pospuesto, la temporada arranca en Imola:
  https://www.fiawec.com/en/news/qatar-1812km-postponed-season-to-start-at-imola/11933
- 2026-03-13 — Qatar reprogramado al 22–24 oct; Prólogo en Imola el 14 abr:
  https://www.fiawec.com/en/news/dates-confirmed-for-rescheduled-qatar-1812km-and-fia-wec-prologue/13118
- 2026-07-28 — Qatar y Bahréin cancelados, Barcelona (16–18 oct) y Monza (6–8 nov) los
  reemplazan: https://www.fiawec.com/en/news/fia-wec-and-fia-confirm-venues-for-final-two-rounds-of-2026/13698

Contraste independiente (cada fila revisada contra al menos una):
- Wikipedia, "2026 FIA World Endurance Championship" (tabla de calendario con rondas 1–8,
  fechas de carrera, tabla de canceladas y resultados de las rondas 1–5):
  https://en.wikipedia.org/wiki/2026_FIA_World_Endurance_Championship
- Artículos de Wikipedia por carrera (día de la FP1, citando los PDF de Al Kamel, el
  cronometraje oficial): 2026 6 Hours of Imola (vie 17 abr), 2026 6 Hours of
  Spa-Francorchamps (jue 7 may), 2026 24 Hours of Le Mans (FP1 10 jun; Test Day 7 jun),
  2026 6 Hours of São Paulo (vie 10 jul), 2026 Lone Star Le Mans (vie 4 sep).
- Dailysportscar, 2026-07-28 — rondas 5–8 con rangos 4/6 sep, 25/27 sep, 16/18 oct, 6/8 nov:
  https://www.dailysportscar.com/2026/07/28/barcelona-monza-to-complete-2026-fia-wec-season.html
- Sportscar365, 2026-07-28 — Barcelona 16–18 oct, Monza 6–8 nov, aprobado por el Consejo
  Mundial (WMSC) por voto electrónico:
  https://sportscar365.com/lemans/wec/barcelona-monza-replace-middle-east-rounds-in-revised-calendar/
- Motorsport.com, 2026-09-23 — horario de Fuji (FP1 vie 25 sep, carrera dom 27 sep 11:00):
  https://www.motorsport.com/wec/news/fuji-six-hours-full-schedule-tv-times/10858072/
- The Peninsula (Qatar), 2026-07-28 — retiro de la Qatar 1812 km:
  http://thepeninsulaqatar.com/article/28/07/2026/fia-world-endurance-championship-wec-withdraws-qatar-1812-km-from-2026-calendar

### Cancelaciones y cambios (conflicto en Medio Oriente)

- **Qatar 1812 km (Lusail) — CANCELADA.** Iba a abrir la temporada el 26–28 mar; el
  2026-03-03 se pospuso por la situación geopolítica en Medio Oriente, el 2026-03-13 se
  reprogramó al 22–24 oct (penúltima ronda) y el **2026-07-28 se canceló** para 2026.
  Su lugar lo tomó el **6 Hours of Barcelona** (16–18 oct, primera visita del WEC como
  carrera; el circuito ya había albergado el Prólogo 2019).
- **8 Hours of Bahrain (Sakhir) — CANCELADA** el 2026-07-28 (iba a ser el final, 7 nov).
  La reemplaza el **6 Hours of Monza** (6–8 nov), regreso a Monza por primera vez desde 2023.
- Ambas carreras de Medio Oriente iban a dar 1.5× puntos; sus reemplazos son 6 h normales.
- El comunicado oficial del 28-jul decía "sujeto a aprobación del Consejo Mundial del
  Deporte Motor de la FIA"; Sportscar365 reporta que ya se aprobó por voto electrónico.
- Por la cancelación de Qatar, **Imola pasó a ser la ronda 1** y la numeración vigente es
  1–8 (la que usan fiawec, Wikipedia y Dailysportscar).
- El calendario 2027 no cambia (fiawec: "nine-round 2027 calendar remains as announced").

### Excluido a propósito

- **Official Prologue** (Imola, 14 abr): prueba colectiva de pretemporada, no es ronda
  de campeonato → no va en el CSV.
- Qatar 1812 km y 8 Hours of Bahrain: canceladas (ver arriba).

### Circuitos nuevos (`circuitos/_nuevos_wec.csv`)

- `imola` — **Autodromo Internazionale Enzo e Dino Ferrari** (nombre como lo escribe
  fiawec; Jolpica lo abrevia "Autodromo Enzo e Dino Ferrari"). Clave y coordenadas de
  Jolpica/Ergast.
- `le_mans` — **Circuit des 24 Heures du Mans** (en.wikipedia lo titula "Circuit de la
  Sarthe"). **No** se usó la clave de Ergast `lemans` a propósito: en Ergast esa clave es
  el **Circuito Bugatti** (el trazado corto del GP de Francia de 1967), otro circuito.
  Coordenadas de fr.wikipedia ("Circuit des 24 Heures").
- `fuji` — **Fuji Speedway**, Oyama (Shizuoka), Japón. Clave y coordenadas de Jolpica/Ergast.

Ya existían en `circuitos.csv`: `spa`, `interlagos`, `americas`, `catalunya`, `monza`.

### Dudas / puntos a revisar

- **Le Mans, `fecha_inicio`**: se usó el **10 jun** (FP1; es el inicio del evento según
  fiawec, "From 10 to 14 June"). El **Test Day** obligatorio fue el **domingo 7 jun** y el
  pesaje/escrutinio es antes aún; si en la app se quiere reflejar toda la semana de
  actividad en pista, cambiar a 2026-06-07.
- **Rondas 6–8 aún no se corren** (Fuji arranca el 25 sep). Las fechas están
  confirmadas, pero podrían cambiar; Monza todavía tiene los horarios de FP1–FP3 "TBC"
  (el día sí está publicado: vie 6 nov).
- Las fechas son locales del circuito (la FP1 de Fuji es el 25 sep en Japón, que aún es
  24 sep en México).

## Mundial de Rally (WRC) 2026 — notas de verificación

Archivo: `wrc.csv` (consultado 2026-09-24). Categorías: `WRC`, `WRC2`, `WRC3` (13 rondas
cada una) y `Junior WRC` (5 rondas). 44 filas.

### Cancelaciones / cambios de calendario

- **Rally Saudi Arabia (ronda 14, Jeddah) CANCELADO para el WRC** — anunciado el
  **2026-09-17** por la FIA y WRC Promoter por la situación en Medio Oriente (guerra con Irán;
  Toyota y Hyundai avisaron que su personal no podía viajar). **No se reemplaza** (una
  propuesta de sustituirlo con un evento de grava en Catalunya no prosperó, según DirtFish).
  El evento sí corre en noviembre, pero solo como fecha del FIA Middle East Rally
  Championship (MERC). **Rally Italia Sardegna (1–4 oct) pasa a ser la ronda 13 y final**;
  la temporada queda en 13 rondas. Excluido del CSV en todas las categorías.
  - Fuentes: https://www.wrc.com/en/news/fia-and-wrc-promoter-confirm-final-round-of-the-2026-wrc-season ·
    https://www.fia.com/news/fia-and-wrc-promoter-confirm-final-round-2026-fia-world-rally-championship ·
    https://www.motorsport.com/wrc/news/wrc-2026-to-end-in-sardinia-as-rally-saudi-arabia-is-cancelled/10856489/ ·
    https://dirtfish.com/rally/wrc/wrc-drops-2026-season-finale-and-wont-replace-it/ ·
    https://en.wikipedia.org/wiki/2026_World_Rally_Championship
  - Pendiente formal: el comunicado dice que el cambio está **sujeto a aprobación del
    Consejo Mundial del Deporte Motor (WMSC)**; al 2026-09-24 no encontré la ratificación
    publicada. No afecta al CSV (la ronda ya no se disputará en el WRC).
  - Ojo: la página https://www.wrc.com/en/calendar **todavía muestra** Rally Saudi Arabia
    como "Upcoming event" (11–14 nov); no está actualizada.
  - Fechas originales de Arabia (solo informativo): calendario wrc.com, FIA y Wikipedia =
    11–14 nov (shakedown el 11); algunas notas de prensa dicen 12–15 nov.
- Otros cambios 2026 (no son cancelaciones): Croatia Rally regresa al WRC con nueva base en
  **Rijeka** (el comunicado del Junior WRC de dic-2025 todavía hablaba de Zagreb); el
  Acropolis cambia su base de Lamia a **Loutraki**; Italia y Japón intercambiaron fechas;
  Cerdeña vuelve a Alghero tras un año en Olbia. Ninguna otra ronda cambió de fecha
  respecto al calendario publicado el 2025-07-31.

### Reglas aplicadas

- **Rondas por categoría**
  - **WRC2 y WRC3 corren en TODAS las rondas del WRC** (cada tripulación nomina hasta 7
    rallies y cuentan los 6 mejores) → misma numeración que el WRC (1–13). wrc.com no
    publica calendarios separados de WRC2/WRC3 (su página de calendario solo tiene
    pestañas "WRC 2026" y "Junior WRC 2026"). Evidencia: Wikipedia (2026 WRC2/WRC3
    Championship: ganadores en las 12 rondas disputadas y la 13 = Cerdeña); wrc.com sobre
    Japón: "WRC3 its sixth different winner from seven rounds" (Japón = ronda 7 del WRC3);
    lista de inscritos de Cerdeña en wrc.com con WRC2 y WRC3; Virves fue campeón de WRC2
    con la cancelación de Arabia "con 25 puntos todavía en juego" (Cerdeña cuenta).
  - **Junior WRC = 5 rondas**: Sweden (1), Croatia (2), Portugal (3), Finland (4),
    Chile (5, final con puntos dobles); cuentan los 4 mejores resultados.
    Fuente: https://www.wrc.com/en/news/chile-finale-highlights-new-look-fia-junior-wrc-calendar
    (05-12-2025) · FIA: https://api.fia.com/news/2026-fia-junior-wrc-calendar-revealed ·
    Wikipedia: https://en.wikipedia.org/wiki/2026_Junior_WRC_Championship (temporada ya
    terminada; campeón Ali Türkkan). No lo afectó la cancelación de Arabia.
- **`fecha_inicio` = día del shakedown** (primer día con coches en tramo, como pide el
  README: "prácticas / shakedown"); **`fecha` = último día (Power Stage, domingo)**.
  En 10 de 13 rallies coincide con el primer día oficial (jueves) de wrc.com. **Tres
  difieren del rango oficial de wrc.com** (verificados con ≥2 fuentes):
  | Rally | Rango oficial wrc.com | Shakedown | fecha_inicio usada |
  |---|---|---|---|
  | Rallye Monte-Carlo | 22–25 ene | **miércoles 21 ene** (Gap) | 2026-01-21 |
  | Rally de Portugal | 7–10 may | **miércoles 6 may** (Baltar) | 2026-05-06 |
  | Rally Estonia | 16–19 jul | **viernes 17 jul** (Kastre; el jueves 16 no hubo actividad en tramo) | 2026-07-17 |
  Si se prefiere el rango oficial, cambiar esas tres a 2026-01-22, 2026-05-07 y 2026-07-16.
  Fuentes: Monte-Carlo — https://www.wrc.com/en/news/katsuta-sets-early-pace-on-dry-monte-carlo-shakedown
  ("shakedown ... on Wednesday afternoon") + https://fr.motorsport.com/wrc/news/rallye-monte-carlo-programme-horaires-2026/10790859/
  ("shakedown ... ce mercredi 21 janvier") + itinerario de Wikipedia/eWRC. Portugal —
  https://www.wrc.com/en/news/neuville-heads-pajari-in-portugal-shakedown (publicado
  06-05-2026, "Wednesday afternoon's shakedown at Baltar") +
  https://www.razaoautomovel.com/noticias/rally-de-portugal-202-horarios/ (shakedown 6 may
  15:01). Estonia — itinerario oficial del organizador (Bulletin 1, Attachment 1, v1.1,
  encabezado "16 - 19 July", shakedown viernes 17 jul 08:01):
  https://app-cdn.sportity.com/392e3e6a-f267-408f-ba12-c7546319bb23/32864754-1611-4946-8394-60e31bf966b6_Itinerary%20v.1.1%20-%2001.07.2026.pdf
  + https://en.wikipedia.org/wiki/2026_Rally_Estonia.
- **`evento`** = nombre de wrc.com sin patrocinador del título (Vodafone, FORUM8, EKO,
  Delfi, Secto, ueno). "Rally Chile Bio Bío" se deja así porque "Bio Bío" es la región, no
  un patrocinador comercial (así lo publica el calendario de wrc.com; Wikipedia lo llama
  solo "Rally Chile").
- **`sede`** = ciudad del parque de asistencia / cuartel general (fuente: artículo de cada
  rally 2026 en Wikipedia, campo *rallybase* + tabla de itinerario con las asistencias,
  cruzado con wrc.com/prensa):
  - Monte-Carlo → **Gap, Francia** (parque de asistencia en Gap; salida ceremonial y
    podio en Mónaco). wrc.com lista el rally con país "Monaco".
  - Safari → **Naivasha, Kenia** (asistencia y HQ en el WRTI de Naivasha; en 2026 se
    eliminó la super especial de Kasarani/Nairobi). Ojo: la tabla general del artículo
    "2026 World Rally Championship" de Wikipedia todavía dice Nairobi; el artículo del
    rally, The Star y Citizen Digital dicen Naivasha.
  - Croatia → **Rijeka, Croacia** (base Rijeka; el parque de asistencia está en el
    Automotodrom Grobnik, a las afueras).
  - Islas Canarias → **Las Palmas de Gran Canaria, España** (asistencia en el Estadio de
    Gran Canaria).
  - Portugal → **Matosinhos, Portugal** (asistencia en Exponor).
  - Japan → **Toyota, Japón** (asistencia en el Toyota Stadium; el texto de Wikipedia dice
    "based in Nagoya" pero su propio *rallybase* y el itinerario dicen Toyota).
  - Estonia → **Tartu, Estonia** (asistencia en Raadi, Tartu).
  - Chile → **Talcahuano, Chile**: HQ y parque de asistencia en el MDS Hotel Concepción,
    que está en la comuna de Talcahuano (Gran Concepción). wrc.com lo llama "Concepción
    service park"; Wikipedia, rally-maps y The Rio Times dicen Talcahuano. Si se prefiere
    el nombre más conocido, usar "Concepción, Chile".
  - Resto sin ambigüedad: Umeå, Loutraki, Jyväskylä, Encarnación, Alghero.
- **`fuente`** por fila: calendario de wrc.com (https://www.wrc.com/en/calendar) para
  ronda y fechas; en Monte-Carlo, Portugal y Estonia la fuente del shakedown (ver tabla);
  en Junior WRC el comunicado de wrc.com de su calendario. Segunda fuente de TODAS las
  filas: Wikipedia (artículo de la temporada + artículo de cada rally 2026, con
  itinerarios de eWRC-results) y el comunicado FIA/wrc.com del calendario del 2025-07-31
  (https://www.wrc.com/en/news/2026-fia-world-rally-championship-calendar). Las fechas
  coinciden en todas las fuentes.
- Las páginas de noticias/eventos de wrc.com se renderizan con JavaScript y a veces se leen
  vacías; se leyeron con éxito agregando un parámetro de consulta (`?lang=en`).

### Dudas / pendientes

- Rally Italia Sardegna (1–4 oct) aún no se corre: fechas y sede vigentes al 2026-09-24.
- Ratificación del WMSC del calendario de 13 rondas (ver arriba).
- Nada se dejó fuera por falta de verificación: todas las filas tienen ≥2 fuentes que
  coinciden. Las únicas decisiones de criterio son las tres `fecha_inicio` por shakedown y
  las sedes de Monte-Carlo (Gap vs. Mónaco) y Chile (Talcahuano vs. Concepción).

## IndyCar 2026 — notas de verificación

Archivo: `indycar.csv` (campeonato `IndyCar`, temporada `2026`). Consultado el 2026-09-24,
con la temporada **ya terminada** (las 18 carreras de IndyCar y las 17 de Indy NXT se
corrieron; todas tienen ganador publicado). Ninguna fecha se canceló.

- **IndyCar Series**: 18 filas (rondas 1-18).
- **Indy NXT**: 17 filas (rondas 1-17).

### Fuentes

Primarias (oficiales, IndyCar):
- Calendario IndyCar: https://www.indycar.com/schedule, más la página de cada evento
  `https://www.indycar.com/Schedule/2026/<evento>`. Ahí salen el nombre oficial, la sede,
  el rango de fechas del evento (encabezado, p. ej. "February 27 - March 1") y el horario
  de pista por día.
- Calendario Indy NXT: https://www.indynxt.com/schedule (`indycar.com/indynxt/schedule`
  redirige ahí), más la página de cada carrera `https://www.indynxt.com/Schedule/2026/<carrera>`.
- PDF oficial del calendario (versión del 2026-07-16, **anterior** a las dos
  reprogramaciones por lluvia): https://www.indycar.com/-/media/Files/Current-Schedule.pdf

Segunda fuente independiente (cotejo fila por fila, todo coincide salvo lo anotado abajo):
- https://en.wikipedia.org/wiki/2026_IndyCar_Series (tabla "Schedule" y "Schedule changes")
- https://en.wikipedia.org/wiki/2026_Indy_NXT (tabla "Schedule")
- Prensa, para los cambios:
  - Nashville: https://www.indycar.com/news/2026/07/07-19-nashville-rain- ,
    https://www.wishtv.com/sports/racing/indycars-music-city-grand-prix-postponed-until-monday-at-3-p-m/
  - Milwaukee: https://www.indycar.com/news/2026/08/08-29-milwaukee-restart ,
    https://www.motorsport.com/indycar/news/indycar-milwaukee-race-1-suspended-by-rain-will-resume-sunday/10850597/
  - Indy NXT Nashville (19 de julio): https://racer.com/2026/07/19/nannini-leads-cape-1-2-indy-nxt-finish-at-nashville
  - Indy NXT Milwaukee (30 de agosto): https://racer.com/2026/08/30/garcia-surges-to-first-indy-nxt-win-at-milwaukee

### Cambios respecto al calendario original

- **Freedom 250 Grand Prix of Washington DC** (ronda 15 de IndyCar): se agregó el
  2026-01-30 (el calendario original, publicado el 2025-09-16, tenía 17 carreras). Por eso
  Milwaukee pasó a ser las rondas 16-17 y Laguna Seca la 18. El CSV usa la numeración
  vigente.
- **Ronda 12, Borchetta Bourbon Music City Grand Prix (Nashville)**: estaba programada para
  el domingo 19 de julio y **se pospuso por lluvia al lunes 20 de julio** (3 p.m. ET).
  `fecha` = 2026-07-20 (día en que se corrió); `fecha_inicio` = 2026-07-18 (así lo publica
  hoy la página del evento: "July 18 - 20"). La carrera de **Indy NXT** de ese fin de
  semana (ronda 13) **sí se corrió el domingo 19** (antes de la lluvia), así que queda con
  2026-07-19.
- **Ronda 16, Snap-on Makers and Fixers 250 (Milwaukee, carrera 1)**: arrancó el sábado
  29 de agosto, se detuvo con bandera roja por lluvia tras 90 de 250 vueltas y se terminó
  el domingo 30 **después** de la carrera 2. **Decisión**: `fecha` = **2026-08-29** (el
  día en que arrancó, por analogía con la regla del README para carreras de más de un
  día); la fila cita la nota de indycar.com que lo documenta. **Ojo**: el listado de
  indycar.com hoy la muestra con fecha "Aug 30" (fecha en que terminó) y Wikipedia pone
  "August 29–30". Si se prefiere la fecha en que terminó, cambiar a 2026-08-30.
- La ronda 17 (Snap-on Milwaukee Mile 250) se corrió el 30 de agosto como estaba previsto.
  La Indy NXT de Milwaukee (ronda 15) también se corrió el 30.

### Criterios usados

- `evento`: nombre oficial como lo publica IndyCar / Indy NXT, con patrocinador
  (convención del README para IndyCar). Excepciones deliberadas:
  - Ronda 7 de IndyCar: `Indianapolis 500` (indycar.com la publica como "110th Running of
    the Indianapolis 500").
  - Las carreras en el **circuito mixto** de Indianapolis llevan el sufijo
    ` (IMS Road Course)` para distinguirlas del óvalo, porque ambas usan la misma clave
    `indianapolis`: "Sonsio Grand Prix (IMS Road Course)" e "Indianapolis Grand Prix
    Race 1/2 (IMS Road Course)". El sufijo **no** es parte del nombre oficial.
  - Dobles fechas: una fila por carrera, con el nombre que publica la serie ("... Race 1",
    "... Race 2"; en Milwaukee cada carrera de IndyCar tiene su propio nombre).
  - "Freedom 250 Grand Prix of Washington DC" se escribe como en indycar.com (Wikipedia
    pone "Washington, D.C.").
- `fecha_inicio`: primer día del rango de fechas que muestra la página del evento. En las
  dobles fechas de Indy NXT, el primer día del fin de semana de Indy NXT (mismo valor en
  las dos filas). Casos a notar:
  - Indianapolis 500: 2026-05-12 (primera práctica; la página dice "May 12 - 24").
  - Markham: la página del evento dice "August 14 - 16", aunque la primera sesión de
    IndyCar en pista fue el sábado 15. Se usó el 14 (fecha del evento publicada).
  - Washington: el evento fue de solo dos días (22-23 de agosto).
- `circuito`: ninguna sede de IndyCar existía en `circuitos.csv`; las 16 se agregaron en
  `data/circuitos/_nuevos_indycar.csv` (no se tocó `circuitos.csv`).

### Circuitos nuevos (`_nuevos_indycar.csv`)

| clave | nombre | nota |
|---|---|---|
| `st_petersburg` | Streets of St. Petersburg | urbano |
| `phoenix` | Phoenix Raceway | óvalo. Ojo: en Ergast/Jolpica `phoenix` es el viejo circuito urbano de F1 (1989-91); aquí es el óvalo de Avondale |
| `arlington` | Streets of Arlington | urbano, debutó en 2026 |
| `barber` | Barber Motorsports Park | IndyCar lo ubica en Birmingham (está en Leeds, AL) |
| `long_beach` | Streets of Long Beach | id de Ergast/Jolpica |
| `indianapolis` | Indianapolis Motor Speedway | id de Ergast/Jolpica; óvalo y circuito mixto comparten clave |
| `detroit` | Streets of Detroit | id de Ergast/Jolpica (mismo recinto junto al Renaissance Center, trazado reducido desde 2023) |
| `gateway` | World Wide Technology Raceway | clave estable ante cambios de patrocinador (antes Gateway International Raceway / Gateway Motorsports Park) |
| `road_america` | Road America | |
| `mid_ohio` | Mid-Ohio Sports Car Course | |
| `nashville_superspeedway` | Nashville Superspeedway | no `nashville`, para no confundir con el antiguo circuito urbano de Nashville |
| `portland` | Portland International Raceway | |
| `markham` | Streets of Markham | urbano, reemplazó a Toronto (Exhibition Place) desde 2026 |
| `washington_dc` | Streets of Washington | urbano, debutó en 2026 |
| `milwaukee_mile` | Milwaukee Mile | |
| `laguna_seca` | WeatherTech Raceway Laguna Seca | |

- `nombre` y ciudad: como los publica indycar.com (la columna `fuente` apunta a la página
  del evento). `ubicacion` usa `Ciudad, Estados Unidos` / `Markham, Canadá`, sin estado,
  como pidió el formato. Ojo con los homónimos: Madison es la de **Illinois**, Lexington la
  de **Ohio**, Lebanon la de **Tennessee** y Birmingham la de **Alabama**.
- `lat`/`lon`: coordenadas principales del artículo de Wikipedia de cada sede (API
  `prop=coordinates`), redondeadas a 4 decimales:
  St_Petersburg_Street_Circuit, Phoenix_Raceway, Arlington_Street_Circuit,
  Barber_Motorsports_Park, Long_Beach_Street_Circuit, Indianapolis_Motor_Speedway,
  Detroit_Street_Circuit, Gateway_Motorsports_Park, Road_America,
  Mid-Ohio_Sports_Car_Course, Nashville_Superspeedway, Portland_International_Raceway,
  Markham_Street_Circuit, Washington_Street_Circuit, Milwaukee_Mile, Laguna_Seca
  (todas en `https://en.wikipedia.org/wiki/<artículo>`). Cotejadas con Jolpica: Detroit
  coincide; Indianapolis difiere ~400 m (el recinto mide más de 1 km por lado). Milwaukee Mile y Mid-Ohio solo traen 2-3
  decimales en Wikipedia (precisión ~1 km).
- Es probable que otras series (NASCAR, IMSA) agreguen algunas de estas mismas sedes con
  otra clave (p. ej. `wwt_raceway` en vez de `gateway`): hay que unificar al integrar los
  archivos `_nuevos_*`.

### Dudas / lo que no se incluyó

- Nada se dejó fuera: todas las filas están confirmadas por la fuente oficial y por
  Wikipedia (y por prensa en el caso de los cambios).
- Única decisión discutible: la fecha de la ronda 16 de IndyCar (29 vs. 30 de agosto),
  ver arriba.

## Fórmula E — notas de verificación (temporadas 12 y 13)

Archivo: `formula-e.csv`, campeonato `FE` con dos temporadas en el mismo archivo:

- temporada `2025-26` (Season 12) — **17 filas, temporada TERMINADA**
  (última ronda: Londres, 16 ago 2026; campeón Pascal Wehrlein).
- temporada `2026-27` (Season 13, arranque de la era GEN4) — **21 filas,
  calendario PROVISIONAL** (ver abajo).

`categoria` = `Fórmula E` en todas las filas. Doble fecha = dos rondas = dos filas.
`evento` sin el patrocinador del título ("2026 Hankook Mexico City E-Prix" →
`Mexico City E-Prix`; "2026 CUPRA Raval Madrid E-Prix" → `Madrid E-Prix`; "2026 Lianxin
Sanya E-Prix" → `Sanya E-Prix`; "2026 TDK Tokyo E-Prix" → `Tokyo E-Prix`; "2025 Google
Cloud São Paulo E-Prix" → `São Paulo E-Prix`).
Consultado: 2026-09-24.

### Cancelaciones y cambios (conflicto en Medio Oriente)

- **Season 12: ninguna ronda cancelada ni movida.** Las 17 rondas del calendario oficial
  (16 oct 2025) se corrieron en la fecha publicada. Jeddah (R4–R5, 13–14 feb 2026) se
  corrió **antes** de que escalara el conflicto regional (Motorsport.com, 25 ago 2026:
  "The Saudi Arabian round proceeded safely in mid-February"). Formula E no tuvo ronda
  en Doha/Catar en ninguna de las dos temporadas.
- Antes del calendario definitivo de S12 (no son cancelaciones de rondas publicadas): el
  calendario provisional de junio 2025 tenía dos huecos "TBC" (30 may y 20 jun 2026). El
  20 jun lo tomó **Sanya** (anuncio oficial 16 oct 2025) y el otro hueco desapareció;
  **Yakarta** quedó fuera "after a contract fell through" (Wikipedia, citando el mismo
  anuncio oficial). Fuente del provisional: https://www.the-race.com/formula-e/formula-e-calendar-revealed-2025-2026-miami-jarama/
- **Season 13 — Jeddah (R1–R2, 18–19 dic 2026) sigue en el calendario oficial, pero en
  observación.** Por la guerra en la región (F1 canceló Arabia Saudita y movió Bahréin a
  Malasia; WEC y MotoGP también cancelaron/movieron fechas), Formula E ha declarado
  varias veces que el opener va: RacingNews365 (25 jun 2026), Motorsport.com (25 ago 2026:
  riesgo "low-to-moderate" según tres organismos de seguridad) y RacingNews365 (17 sep
  2026: "Preparations for the season opener at the Jeddah Corniche Circuit are ongoing";
  la decisión final puede esperar a mediados de noviembre, durante el test de pretemporada
  en Jarama, 16–20 nov). **Plan B declarado**: si Jeddah no puede correrse, se
  **pospone** (no se reemplaza) a otra fecha de la temporada y la temporada arranca en
  **Ciudad de México el 16 ene 2027** — eso renumeraría todas las rondas. Revisar antes
  de diciembre.
- **Season 13 — Mónaco movido** (anuncio oficial 5 ago 2026): del 15–16 may al **1–2 may
  2027** (choque con WEC / calendario del Principado). Con eso Mónaco pasa a ser R8–R9 y
  Berlín R10–R11 (antes Berlín R8–R9 y Mónaco R10–R11). El CSV ya usa la numeración
  vigente. https://fiaformulae.com/en/news/1081405/formula-e-updates-2026-27-calendar-and-secures-long-term-future-in-monaco

### Estado de la Season 13: PROVISIONAL

El anuncio oficial del 23 jun 2026 lo llama textualmente "the first provisional calendar
for the 2026-27 season … following validation from the FIA World Motor Sport Council".
Desde entonces solo hubo un cambio (Mónaco, arriba). La página oficial de calendario
(https://www.fiaformulae.com/en/calendar, "Season 13 • 0 of 21 races complete") coincide
fila por fila con el CSV, pero **los eventos aún no tienen nombre ni horario** (el JSON-LD
los trae como "TBC"), por eso **`fecha_inicio` va vacío en las 21 filas** de la S13
(regla del README: solo si la fuente lo da). Único dato suelto: RacingNews365 (17 sep 2026)
habla de Jeddah "December 17th to 19th", pero no es fuente oficial; no se usó.

### Fuentes

#### Season 12 (2025-26)

Calendario oficial y confirmación de fechas de carrera (las tres coinciden en las 17 rondas):
- Anuncio oficial del calendario (16 oct 2025, tabla por ronda):
  https://www.fiaformulae.com/en/news/761110/sanya-returns-while-formula-e-updates-sporting-and-financial-regulations
- Resultados oficiales, 17 rondas con `raceDate`: https://www.fiaformulae.com/en/results-and-standings?season=12
- FIA, calendario de la temporada: https://www.fia.com/events/abb-fia-formula-e-world-championship/season-2025-2026/races-calendar

`fecha_inicio` = día de la Free Practice 1 según el artículo oficial de horarios de cada
evento (su URL va en la columna `fuente` de la fila):

| Rnd | Evento | FP1 (fecha_inicio) | Carrera | Fuente (fiaformulae.com/en/news/…) |
|---|---|---|---|---|
| 1 | São Paulo | 2025-12-05 (vie) | 2025-12-06 | 822290 |
| 2 | Mexico City | 2026-01-09 (vie) | 2026-01-10 | 824495 |
| 3 | Miami | 2026-01-30 (vie) | 2026-01-31 | 826677 |
| 4–5 | Jeddah | 2026-02-12 (jue) | 13 y 14 feb | 873269 |
| 6 | Madrid | 2026-03-20 (vie) | 2026-03-21 | 1005554 |
| 7–8 | Berlin | 2026-05-01 (vie) | 2 y 3 may | 1060729 |
| 9–10 | Monaco | 2026-05-16 (sáb) | 16 y 17 may | 1067537 |
| 11 | Sanya | 2026-06-19 (vie) | 2026-06-20 | 1073161 |
| 12–13 | Shanghai | 2026-07-03 (vie) | 4 y 5 jul | 1076199 |
| 14–15 | Tokyo | 2026-07-24 (vie) | 25 y 26 jul | 1079181 |
| 16–17 | London | 2026-08-14 (vie) | 15 y 16 ago | 755455 |

Segunda fuente independiente:
- Wikipedia "2025–26 Formula E World Championship" (tabla de calendario y de resultados):
  coinciden ronda, sede y fecha de carrera de las 17 filas.
  https://en.wikipedia.org/wiki/2025%E2%80%9326_Formula_E_World_Championship
- Motorsport.com, horario por sesión (FP1 y carrera) de todos los eventos: coinciden las
  17 `fecha_inicio` y `fecha`. https://www.motorsport.com/formula-e/schedule/2026/ y
  https://www.motorsport.com/formula-e/schedule/2025/ (São Paulo).

#### Season 13 (2026-27)

- Oficial, calendario por ronda ya con el cambio de Mónaco (columna `fuente`):
  https://www.fiaformulae.com/en/news/1074658 ("CALENDAR: Where will Formula E be racing in 2026/27")
- Oficial, anuncio original (23 jun 2026, "first provisional calendar"):
  https://www.fiaformulae.com/en/news/1074657
- Oficial, cambio de Mónaco (5 ago 2026): https://fiaformulae.com/en/news/1081405
- Oficial, página de calendario en vivo: https://www.fiaformulae.com/en/calendar
- Segunda fuente: Wikipedia "2026–27 Formula E World Championship" — coinciden las 21
  rondas (ronda, sede, fecha). https://en.wikipedia.org/wiki/2026%E2%80%9327_Formula_E_World_Championship
- Zandvoort confirmado también por el circuito (18 y 19 jun 2027, "de eerste Zandvoort
  E-Prix"): https://www.circuitzandvoort.nl/nieuws/formula-e-rijdt-vanaf-2027-in-zandvoort-focus-op-innovatie-energietransitie-en-topsport/

### Circuitos

Existentes en `circuitos.csv`: `rodriguez`, `miami`, `monaco`, `shanghai` (S12 y S13),
`americas` (Austin, S13) y `zandvoort` (S13).

Nuevos en `circuitos/_nuevos_formula-e.csv` (8): `sao_paulo_street`, `jeddah_corniche`,
`jarama`, `tempelhof`, `sanya_street`, `tokyo_street`, `excel_london` (solo S12) y
`brands_hatch` (solo S13). Coordenadas del infobox de Wikipedia (columna `fuente`),
contrastadas con OpenStreetMap/Nominatim (diferencias < 0.5 km), salvo Sanya (ver abajo).
Nombres:
- `sao_paulo_street` → **"Anhembi Sambadrome Circuit"**: así lo publica Formula E en S12 y
  S13 (Wikipedia lo titula "São Paulo Street Circuit"; la clave sigue la sugerencia del
  encargo).
- `jarama` → **"Circuito de Madrid Jarama - RACE"**: nombre que usa el propio circuito
  (jarama.org) y la S13 oficial ("Circuito de Madrid Jarama-RACE"); en S12 Formula E lo
  llamó "Circuito del Jarama". Está en San Sebastián de los Reyes (~32 km al norte de
  Madrid, según Formula E). Ojo: NO es `madring` (el circuito urbano del GP de España de F1).
- `sanya_street` → **"Sanya Street Circuit"**: nombre usado por Formula E en S12 y por
  Wikipedia. En el calendario oficial S13 aparece como "Haitang Bay Circuit" (mismo
  distrito de Haitang Bay en Sanya); se asume la misma sede. Coordenadas de Wikipedia
  (trazado de 2019; en 2026 se modificaron las primeras curvas) — aproximadas.
- `excel_london` → "ExCeL London Circuit" (Wikipedia: "Excel London Circuit"; FIA: "London
  ExCel Arena FE"). El London E-Prix se muda a `brands_hatch` en S13.
- `jeddah_corniche`: ubicación en español "Yeda" (grafía recomendada en español para
  Jeddah).

### Dudas y observaciones

- **São Paulo S12**: la FP1 del viernes 5 dic **se canceló** en pista por fallas de radio
  (https://www.fiaformulae.com/en/news/823329); la actividad arrancó con una FP2 extendida
  el sábado 6. Se dejó `fecha_inicio` = 2025-12-05 porque fue el primer día programado del
  evento. Cambiarlo a 2025-12-06 si se prefiere "primer
  día con pista activa".
- **Shanghai S12**: el artículo oficial "What time is the 2026 Shanghai E-Prix" (1075199)
  trae fechas equivocadas ("Saturday 3 & Sunday 4 July", "Friday 2 July"). No se usó. Las
  fechas buenas: FP1 el vie 3 jul (artículo oficial "FP1 RESULTS" fechado 03 jul,
  https://www.fiaformulae.com/en/news/1076219, y Motorsport.com), carreras 4 y 5 jul
  (aviso oficial de cambio de horario por mal clima, 1076199, que adelantó FP2/FP3,
  qualy y carreras de ambos días; resultados oficiales).
- **Jeddah S12**: el artículo oficial de horarios (873269) dice "Race: Saturday 15
  February" para la carrera 2 — errata; el sábado fue 14 feb (resultados oficiales, FIA,
  Wikipedia, Motorsport.com). En el anuncio oficial del 16 oct 2025 el texto dice "Round
  12" para Sanya, pero su propia tabla (y todo lo demás) dice ronda 11.
- **Monaco S12**: no hay actividad el viernes; FP1 fue el sábado (día de la carrera 1),
  por eso `fecha_inicio` = `fecha` en la ronda 9.
- **Nombres de evento S13**: Wikipedia llama "British ePrix" a Brands Hatch y "Dutch
  ePrix" a Zandvoort; se usaron los nombres de la serie/organizador: `London E-Prix`
  (Formula E: "a new home for the London E-Prix at … Brands Hatch") y `Zandvoort E-Prix`
  (circuito organizador). Austin = `Austin E-Prix` (Formula E: "the brand new Austin
  E-Prix at COTA").
- **Zandvoort S13** corre viernes 18 y sábado 19 jun 2027 (no sáb/dom): así lo publican
  Formula E y el circuito.
- **Trazados** (para quien cargue trazados, no afecta al CSV): Formula E usa trazados
  propios: Miami 2.32 km / 14 curvas (más corto que el de F1), Madrid (Jarama) 3.934 km /
  14 curvas, São Paulo 2.933 km / 11 curvas (artículos oficiales "TRACK MAP").
  En **Ciudad de México la S13 correrá el trazado completo de F1 (~4.3 km)** en lugar del
  de ~2.6 km que usaba Formula E (RacingNews365, 24 sep 2026:
  https://racingnews365.com/formula-e-to-race-full-f1-mexico-city-layout-as-gen4-arrives).
- La S13 usa la temporada `2027` (año en que termina, regla del README) aunque sus dos
  primeras rondas son en diciembre de 2026.

## NASCAR México 2026 — notas de verificación

Consultado: **2026-09-25** (NASCAR México y México Racing Cup se agregaron ese día; el resto
del archivo es del 24-sep).

- **Fuentes**: calendario oficial https://www.nascar.mx/schedule/ y
  https://www.nascar.mx/challengeseries/ (12 fechas con ganadores de las ya corridas) + la
  nota de resultados de cada fecha (la `fuente` de cada fila; publicada el mismo día de la
  carrera). nascar.mx devuelve 403 a curl/WebFetch (Cloudflare): se leyó con Chromium
  headless y su API de WordPress (`/wp-json/wp/v2/posts`). Calendario original del 19-ene
  (https://www.nascar.mx/2026/01/19/se-dio-a-conocer-el-calendario-de-nascar-mexico-2026/,
  vía Wayback Machine), aviso de cambios del 24-jun
  (https://www.nascar.mx/2026/06/24/cambios-en-el-calendario-de-la-temporada-2026/).
- **Categorías**: las dos divisiones oficiales (https://www.nascar.mx/about/): **NASCAR México
  Series** y **NASCAR Challenge Series** (nascar.mx también la llama "Challenge Series"); corren
  las 12 fechas, casi siempre en parrilla compartida (en Tulum, por separado). Formato: 8
  fechas de temporada regular + 4 de **Chase** sin eliminaciones.
- **Trucks México Series** (TMS; antes "NASCAR Mikel's Truck Series"): nascar.mx y el
  organizador de Yucatán la llaman "categoría invitada" y no publica calendario propio, pero
  corre las mismas 12 fechas **con la misma numeración** — FEMADAC: Aguascalientes (27-sep) es
  "la 10ª. fecha de la temporada 2026, primera carrera de 'El Chase'", con "4 fechas" de Chase
  (10, 9, 11 y 12) y 7 pilotos contendientes
  (https://www.femadac.org.mx/trucks-mexico-series-inicia-la-batalla-por-el-titulo-en-aguascalientes/).
  Se agregó (decisión del usuario, 2026-09-25) con los mismos nombres de evento que las otras
  dos. Fuente de cada fila: la nota de nascar.mx con el programa de ese fin de semana (su
  carrera suele ser el mismo día, antes de la principal; en Querétaro, 70 vueltas; en SLP y
  Aguascalientes, "Trucks 100"); Puebla 23-ago, la presentación de Monterrey (ganó Bob
  Espinosa); Yucatán, https://granpremioyucatan.com/ ("Trucks México (categoría invitada)" —
  no detalla si corre las DOS carreras; se infiere de las 4 fechas de Chase).
- **"Por definir" resueltos**: fecha 2 → Súper Óvalo Chiapas; 3 → óvalo temporal de 600 m en
  el Aeropuerto Internacional de Tulum; 6 ("Chihuahua o SLP") → Querétaro 140 en sábado 27-jun
  (Chihuahua no tuvo fecha en 2026); 7 → Súper Óvalo Potosino 2-ago; 8 → Puebla, óvalo,
  23-ago; 11 ("Mérida o Querétaro") → Yucatán; 12 (Puebla 15-nov) → **CDMX 7–8 nov** (Puebla se
  quedó con la de agosto).
- **Fecha 9 CANCELADA y numeración**: Monterrey ("Monterrey 120", óvalo El Frijol, sábado
  5-sep) no se corrió por lluvia
  (https://www.nascar.mx/2026/09/06/por-razones-climaticas-que-afectaron-la-pista-no-se-corrio-la-novena-fecha-de-nascar-mexico-series-en-monterrey/).
  Se **recupera el sábado 17-oct en Yucatán** (doble fecha:
  https://www.nascar.mx/2026/09/09/yucatan-esta-listo-para-hacer-historia-nascar-mexico-llega-con-doble-fecha-y-la-semifinal-del-campeonato/).
  Se conserva la **numeración oficial**: Aguascalientes (27-sep) sigue siendo la "décima
  fecha", así que la ronda 9 cae después de la 10. El calendario de la app se ordena por
  fecha (no por número).
- **Nombres de evento** de las notas oficiales. Sin nombre publicado: la carrera del sábado
  en Yucatán (se usó "Gran Premio Yucatán 200 (carrera 1)" y "(carrera 2)" para el domingo,
  para no repetir nombre en la misma sede) y la final ("Fecha 12 — Ciudad de México"). La
  fecha 7 aparece también como "La Potosina 200" en notas de resultados; se usó el del
  calendario ("San Luis Potosí 200").
- `fecha_inicio`: primer día con sesiones del programa oficial de cada nota (las prácticas
  libres del viernes 13-mar en SLP para probar llantas no eran parte del programa).
- **Pendiente de re-verificar**:
  - CDMX 8-nov lleva asterisco en el calendario oficial; sin trazado publicado (2024: óvalo de
    1,609 m; jun-2025: circuito completo).
  - Trazado de Yucatán (sin publicar).
  - Confirmar que Aguascalientes se corrió el 27-sep.
- Logo: ícono del sitio nascar.mx (ver `campeonatos/logos.csv`).

## México Racing Cup 2026 — notas de verificación

Consultado: **2026-09-25**.

- **Nombre**: **México Racing Cup** (reglamento y logo; el sitio a veces sin acento). "BYD" es
  patrocinio del título desde el 23-feb-2026 ("BYD México Racing Cup",
  https://mexicoracingcup.com/noticia/5139-Nace-BYD-Mxico-Racing-Cup). Organiza DUNCAN Eventos
  / Grupo Notiauto (es la antigua Copa Notiauto); reglamento avalado por **FEMADAC**
  (https://duncanwebmin.notiauto.com/repository/s/pdf/Reglamento-Competencia-MRC-2026.pdf);
  TCR México y F4 NACAM además con aval FIA/OMDAI.
- **Fuentes**: fechas ya corridas = **libretas oficiales de resultados** (HIGH TECH, enlazadas
  en https://mexicoracingcup.com/32-<categoria>), que dan el día de cada sesión y la longitud
  del trazado; fechas futuras = calendario oficial https://mexicoracingcup.com/calendario y el
  de TCR https://tcrmexico.com/calendario; tablas de posiciones oficiales (imágenes) para
  confirmar el nº de fechas.
- **Modelo**: un campeonato con **7 categorías**, cada una con **su propia numeración**
  (la de sus libretas y tablas). El sitio numera los *eventos* (FECHA 1–18), que no es la
  ronda de ninguna categoría: p. ej. el GP México Súper Fan es el evento 10, la fecha 8 de
  TC2000 y la 5 de F4 NACAM. Títulos definidos en el reglamento único (art. 9.2).
  - **Copa TC2000** va primero (categoría principal: corre las 10 fechas de velocidad).
  - **F4 NACAM** corre DENTRO de la MRC (título en el reglamento, tabla con logo MRC); su
    ronda 7 es soporte del GP de F1 y aun así está en el calendario MRC.
  - **Endurance Challenge**: calendario propio de 5 fechas; la Endurance 24 es la 5.
  - **TCR México**: fechas 1 y 5 son de resistencia (500 km, 6 Horas); la Endurance 24 es
    "invitación" (no es ronda de TCR).
  - **No se incluyen**: Vintage Pro Series (corrió en fechas que nunca se numeraron; sin tabla
    publicada) ni Vintage Fórmula Ford (no se publican sus fechas 7 y 8). Las clases
    (ST1/ST2, Copa Clío, E1–E8, Pro/Am) no son categorías con calendario.
  - **Súper Turismos Light 1 y 2** (decisión del usuario 2026-09-27): el sitio publica DOS
    campeonatos de STL (tablas `33-st-light-1` y `33-st-light-2`), así que son dos categorías
    con el mismo calendario (antes una sola "Súper Turismos Light").
  - **Posiciones**: solo como IMAGEN en el sitio; las leídas viven en `posiciones-imagen/`
    (una por categoría: imagen de origen, hasta qué fecha llega y filas con la posición
    IMPRESA y los PUNTOS REALES; TCR = la tabla "Campeonato General (Sprint)"). Detalles en
    `docs/fuentes-posiciones/mexico-racing-cup.md`.
- `fecha_inicio`/`fecha`: días reales de la libreta de ESA categoría (primer día con sesión /
  día de la carrera principal; con carreras en dos días, el de la mayoría). Futuras: rango del
  calendario oficial; resistencia de más de un día: día de arranque (Endurance 24 = 12-dic).
- **Cambios y cancelaciones** (sin filas): calendarios de enero provisionales —
  velocidad (https://notiauto.com/noticia/4774-Mxico-Racing-Cup-encender-el-asfalto-con-10-fechas-en-2026):
  el 15-ago tentativo en el Autódromo Moisés Solana (Hgo.) se corrió en el AHR; Endurance
  (https://mexicoracingcup.com/noticia/4773-Endurance-Challenge-tendr-cinco-fechas-en-2026):
  **6 Horas de Querétaro (18-abr) y Mérida (22–23 ago) no se corrieron**, Endurance 24 pasó del
  AHR a Puebla; TCR (https://mexicoracingcup.com/noticia/4781-TCR-Mxico-con-calendario-intenso-en-2026):
  Mérida no se corrió y dejó la fecha del GP de F1 (1-nov); F4 NACAM: Mérida → Puebla 11–12 sep.
- **Pendiente de re-verificar**:
  - Monterrey 17–18 oct: solo en el calendario oficial, sin prensa ni trazado.
  - Trazado de GT Challenge de las Américas (3–4 oct), La Batalla Final (7–8 nov; `fecha` =
    8-nov supuesta) y Endurance 24.
  - **TCR México fecha 7 (21–22 nov)** = "TBA, CDMX": va sin `circuito` y con `sede` (el AHR es
    lo probable, sin confirmar).
  - Endurance 24: el calendario aún dice "TBA CDMX/Puebla"; la nota del 24-ago dice Puebla
    (https://mexicoracingcup.com/noticia/6729-La-Endurance-24-se-celebrar-en-el-Autdromo-Miguel-E-Abed).
  - El Gran Premio Ida y Vuelta (Querétaro) corrió carreras en ambos sentidos del trazado.
- Posiciones automáticas: no hay fuente pública (las tablas oficiales son imágenes JPG).
- Logo: el del encabezado del sitio (lleva la marca BYD; no hay versión 2026 sin ella) — ver
  `campeonatos/logos.csv`.
