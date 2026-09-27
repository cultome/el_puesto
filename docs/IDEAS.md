# Ideas de features futuros

Backlog de **funcionalidad grande** que decidimos atacar más adelante. No es deuda
técnica (para pulido/detalles ver `DEUDA-TECNICA.md`): cada entrada de aquí es un
feature con diseño propio que merece su propia sesión. Al tomar una idea, moverla al
plan de la sesión y borrarla de aquí al completarla.

---

## Dar de baja mi cuenta (anotado 2026-08-01)

**Qué**: flujo para que el oficial **elimine su cuenta** desde Configuración. Existió
como fila inerte ("Dar de baja mi cuenta", roja, junto a Cerrar sesión) y se **quitó de
la UI el 2026-08-01** (misma decisión que "Descargar mis datos": no mostrar opciones que
no funcionan). Al implementarla, restaurar la `DangerRow` al final de
`SettingsScreens.kt`.

**Por qué es un feature grande** (no deuda): requiere **política de datos** de producto
antes que código:

- ¿Borrado físico o **anonimización**? El historial operativo (asignaciones, checklist
  marcado, mensajes en chats de evento, accesos de emergencia auditados) tiene valor de
  registro para la organización y referencias cruzadas — borrar en cascada rompería auditoría;
  anonimizar (officer → "Oficial dado de baja") preserva el registro sin identidad.
- Qué SÍ se borra siempre: emergencia, avatar, planeación/bitácora personal
  con sus fotos, allowlist de ubicación, invitaciones pendientes que emitió.
- Estado de cuenta: probablemente un estado terminal nuevo (p. ej. `DEACTIVATED`) que
  invalida tokens y bloquea re-registro accidental del mismo email sin admin.
- **UX destructiva**: confirmación fuerte (escribir algo, doble paso), aviso de
  irreversibilidad, y ¿periodo de gracia (reactivable N días)? — decidir.
- **Relación**: "Descargar mis datos" ya existe (2026-09-25, `GET /me/export`): lo
  natural es ofrecerlo ANTES de consumar la baja.

---

## Detalle del piloto (anotado 2026-09-27; PENDIENTE)

**Qué**: tocar un piloto en Posiciones abre su pantalla: foto grande, número, equipo, puntos y
posición, y (si la fuente lo da) resultados fecha por fecha. Hoy el toque abre solo la foto en
grande (`Overlay.ImageView`), que es el paso previo que pidió el usuario. Las fuentes ya traen
datos para esto: puntos por carrera (F2/F3, WEC, Fórmula E), nacionalidad y equipo por fecha.

---

## Importar posiciones desde una imagen (anotado 2026-09-27; PENDIENTE)

**Por qué**: hay campeonatos que solo publican su tabla como IMAGEN y detrás de un reto
anti-bots (NASCAR México: `https://www.nascar.mx/standings/`; todo nascar.mx — página, feed, API
de WordPress, sitemap y `wp-content/uploads` — responde 403 "Just a moment…" de Cloudflare, que
no se evade). El backend no puede bajar la imagen, pero una persona sí la ve en su navegador.

**Qué**: una pantalla en el admin web (y su tool de MCP) para **subir la imagen de posiciones**
de una categoría: un modelo de visión (API de Claude) arma la tabla (posición, número, piloto,
equipo, puntos), el admin la **revisa y corrige** contra la imagen y la guarda por el mismo
camino que la captura manual (pilotos + posiciones, reemplazo total, auditado). Crédito visible
en la app ("Fuente: nascar.mx", captura del admin). Se guarda la imagen original como evidencia.

**Decisiones abiertas**: llave de la API de Anthropic en producción (secreto nuevo en Parameter
Store; costo de centavos por imagen) vs. que la lectura la haga Claude en una sesión (sin llave,
pero sin pantalla); cómo empatar pilotos entre cargas (ref por número + nombre normalizado, ya
que la imagen no trae ids); la fecha "hasta la que llega" la elige el admin.

**Mientras tanto**: el usuario guarda la imagen y se la pasa a Claude en una sesión, que la lee,
muestra la tabla para revisión y la carga con la API admin. **Otros campeonatos en la misma
situación**: NASCAR México Series / Challenge Series / Trucks México (a confirmar cuál imagen
trae cada una) y la **México Racing Cup** (HECHA la mitad el 2026-09-27: la fuente
`mexicoracingcup-img` detecta la imagen nueva y publica la lectura guardada; falta que la LECTURA
sea automática — hoy la hace un agente y se guarda con `guardar_lectura_posiciones`; ver
`docs/fuentes-posiciones/mexico-racing-cup.md`).

---

## Posiciones automáticas — las categorías restantes (anotado 2026-09-25; EN CURSO desde 2026-09-27)

**Decisión del usuario (2026-09-27)**: se obtienen las posiciones **aunque haya que leer las
páginas públicas** de cada campeonato (revierte el "descartado" del 2026-09-25). Una categoría
a la vez; sin llaves de API. Hecho: **Fórmula 2** y **Fórmula 3**
(`fiaformula2-web` / `fiaformula3-web`, el mismo lector `FiaSeriesSite`: `/en/standings/{año}/drivers`
+ la página de cada fecha; detalles en `docs/fuentes-posiciones/f1-family.md` → "F2 por las
páginas públicas") y **NASCAR Cup/O'Reilly/Truck** (`nascar-feed`, parámetro = serie). NASCAR:
www.nascar.com está detrás de un reto anti-bots de Cloudflare (no se evade), así que el usuario
eligió los JSON públicos de cf.nascar.com que esa página descarga (sin llave ni reto); ver
`docs/fuentes-posiciones/nascar.md` → "Lo que usa el backend". **WEC Hypercar/LMGT3** (`fiawec-web`,
parámetro = clase): campeonato de PILOTOS de cada clase (decisión del usuario), leído del HTML
con Jsoup; ver `docs/fuentes-posiciones/wec.md` → "Lo que usa el backend".

Antes (2026-09-25): era un nice-to-have y quedó solo F1 (Jolpica). La app oculta la pestaña
Posiciones en las categorías sin tabla. La tabla de abajo es la investigación de entonces
(APIs internas); sirve para saber qué datos hay detrás de cada sitio.

**Hecho**: la infraestructura (job `StandingsIngest`, fuentes enchufables, modelo de
piloto por `ref`, admin/MCP/web), la F1 vía Jolpica, F2/F3 por su sitio, las 3 de NASCAR y las 2
de WEC. **Falta** una fuente por cada una de las otras 9 categorías (más los campeonatos nacionales). Investigadas el 2026-09-25 (resúmenes completos en
`docs/fuentes-posiciones/`; lo esencial abajo). **Bloqueo de entonces (levantado el 2026-09-27)**:
salvo Jolpica, todas son APIs INTERNAS cuyos términos prohíben la extracción automatizada
(uso personal/no comercial). Alternativa limpia: Wikipedia (CC BY-SA, wikitext irregular,
sin número de coche en varias).

| Categoría | Fuente viable | Notas |
|---|---|---|
| F2, F3 | `api.formula1.com/v2/core-fom-results/{f2\|f3}/driver-standings-breakdown?season=2026` + header `apikey` embebido en su sitio | número/equipo de `/race`; fecha = `meetings[]` con puntos no nulos |
| F1 Academy | `api.formula1.com/v1/f2f3-fom-results/driverstandings?website=fa&season=<SeasonId>` | siempre gzip; SeasonId de `/seasons` (4 = 2026) |
| Fórmula E | `api.formula-e.pulselive.com/formula-e/v1/standings/drivers?championshipId=<uuid>` | **certificado TLS vencido el 2026-09-24**; ronda = máx. `raceSequence` |
| IndyCar, Indy NXT | `indycar.com/api/results/YearPointSummary?year=&id=<guid>` | sin número/equipo (de cada carrera); 06 vs 6 |
| NASCAR ×3 | `cf.nascar.com/cacher/2026/{1,2,3}/points-feed.json` | llave `driver_id`; filtrar pilotos sin puntos; Chase (tabla única) |
| WEC ×2 | HTML de `fiawec.com/en/season/2026` (no hay JSON) | una fila por coche ("A / B / C"); #007 vs #7 |
| WRC ×4 | `p-p.redbull.com/rb-wrccom-lintegration-yv-prod/api/…` | número por rally (llave = `personId`); ids de campeonato cambian cada año |

---

## Compartir elementos de la bitácora (anotado 2026-09-25; EN ESPERA)

**Qué**: compartir un elemento de la bitácora (foto, nota, planeación) **dentro** del sistema
(mandarlo a un chat) y **fuera** (hoja de compartir de Android → WhatsApp, correo…). Hoy la
bitácora es privada ("Solo tú la ves"); compartir encaja con privacy-first si es un acto
explícito, elemento por elemento.

**Estado**: el usuario lo pospone hasta tener **feedback de sus usuarios** (2026-09-25).

**Propuesta inicial** (a validar con ese feedback):
- Dentro: "Enviar al chat" → elegir chat (evento, puesto o público unido). Foto = mensaje
  con imagen y su pie; nota = texto; planeación = texto con formato o tarjeta nueva.
- Fuera: `ACTION_SEND` (FileProvider para la foto, re-codificada **sin EXIF/GPS**; texto
  para nota/planeación). Sin backend nuevo.

**Preguntas abiertas**: ¿qué tipos se comparten? ¿varias fotos o un día completo a la vez?
¿planeación como texto o tarjeta visual en el chat? ¿marcar en el chat que viene de una
bitácora?

## El MbM como "Live Update" de Android 16 (anotado 2026-09-26)

**Qué**: nivel 2 de "consultar el MbM sin desbloquear" (el nivel 1 ya está: barra pública,
canal que no es silencioso, grupo propio — ver `LiveEventBar.kt`). Android 16+ puede
**promover** la barra: hasta arriba de la pantalla de bloqueo y expandida, en la pantalla
siempre encendida, como chip en la barra de estado con la cuenta regresiva y en Wear OS. En
Android 15 o anterior se ignora y queda la notificación normal.

**Qué hace falta**:
- Herramientas: compileSdk 36 + androidx.core 1.17 (hoy 35 y 1.13.1) → AGP ≥ 8.9.1 (hoy
  8.7.2) → Gradle ≥ 8.11.1 (el standalone es 8.10.2) + regenerar
  `gradle/verification-metadata.xml`.
- Permiso `POST_PROMOTED_NOTIFICATIONS` en el manifest (listarlo en `/descargas`). No se
  pide, pero el oficial puede apagarlo: revisar `canPostPromotedNotifications()` y poner el
  atajo `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` en Configuración →
  Notificaciones.
- `NotificationCompat.Builder.setRequestPromotedOngoing(true)`. Lo demás ya se cumple
  (fija, con título, `BigTextStyle`, sin colorear ni vistas propias, canal no MIN). Chip:
  la cuenta regresiva que ya pinta, o `setShortCriticalText`.
- Reglas de Google (solo actividades en curso que el usuario inició): pedir la promoción
  solo con una actividad EN CURSO o la siguiente del mismo día — no con "SIGUE · sáb
  14:30" —, y si el oficial la descarta (`setDeleteIntent`), no volver a pedirla.
- Opcional: `ProgressStyle` con el día en segmentos (uno por actividad) y un punto en
  "ahora"; exige repintar cada pocos minutos.

**Lo que no resuelve**: con el teléfono bloqueado la app no está en primer plano y el
WebSocket se cierra (visto al probar el nivel 1), así que un cambio del MbM no llega hasta
abrir la app; la barra se repinta desde la caché con sus alarmas. Eso depende del push con
la app cerrada (FCM), pendiente.
