# Deuda técnica — El Puesto

Detalles pequeños y pulido que **no deben atorar el desarrollo importante**. Se anotan aquí
para atenderlos más adelante. Un ítem sale de la lista cuando se implementa y verifica.

Convención: `[ ]` pendiente · `[~]` en progreso · `[x]` hecho (mover al historial abajo).

---

## Navegación conectada — bloqueados (auditoría 2026-07-27)

Cableado lo que tiene pantalla destino (compañeros→perfil, agenda→detalle, circuito desde
Home/Modo evento, pilotos→detalle). Quedan SOLO los que necesitan algo que no existe aún:

- [x] ~~Calendario de campeonato → circuito~~ — resuelto 2026-08-01: fila del calendario
  clickeable (con chevron) cuando la fecha trae `circuitId`; abre el detalle del circuito.
- [x] ~~Convocatoria → circuito~~ — resuelto 2026-08-02: `Convocatoria.circuitId` opcional
  (select en el admin web, validado contra el catálogo vivo); el detalle muestra la fila
  "Circuito ›" que abre el detalle del circuito. `location` sigue siendo el texto mostrado.

## Agenda (restante tras la agenda editable de 2026-07-27)

La agenda ya es editable: "+" crea Transporte/Hospedaje/Recordatorio (persistido en el
backend como trip item propio), editar/eliminar desde el detalle, vínculo a evento,
"+ Agregar al viaje", hoy resaltado, mes por defecto real y sección "Sin fecha". Queda:

- [x] ~~Escrituras de agenda en el outbox~~ — resuelto 2026-08-01: red primero; offline se
  aplica local (ids temporales `local-…`, mapa persistido al drenar) y se encola.
- [x] ~~Recordatorios con hora → notificación~~ — resuelto 2026-08-01: `Reminders`
  (AlarmManager + BootReceiver + re-programación tras sync); canal "recordatorios";
  tocar navega al detalle. Incluye "Recordarme antes del cierre" de convocatorias.
- [x] ~~Vincular a convocatoria~~ — resuelto 2026-08-02: `TripItem.convocatoriaId` /
  `AgendaEntry.convocatoriaId` (excluyente con el evento en el editor); el detalle de la
  entrada muestra "Convocatoria vinculada" que navega. Las entradas globales CONVOCATORIA
  también se ligan desde el admin (select + tool MCP).

## Bitácora de viaje (restante tras el timeline + fotos/notas de 2026-07-27)

- [x] ~~Fotos requieren red~~ — resuelto 2026-08-01: ítem y foto se encolan offline
  (payload base64; el drain corre create → foto en orden y traduce el id temporal).
- [x] ~~Borrar la imagen al eliminar un ítem PHOTO~~ — resuelto 2026-08-01
  (`deleteTripItem` limpia el kind `trip` de `images`; verificado por curl + psql).
- [x] ~~Editar un PHOTO~~ — decisión de producto (2026-07-27): de una foto **solo** se edita
  el pie o se elimina; imagen, hora y evento quedan como se capturaron.
- [x] ~~FAB global solo en el shell~~ — decidido e implementado 2026-08-02: el FAB aparece
  sobre TODOS los overlays salvo lista negra (Modo evento, chat, visor de imagen,
  editores/capturas). `bitacoraFabAllowed()` en `ElPuestoApp.kt`.

## Chats (pendiente además del tiempo real)

- [x] ~~Invitar oficiales a un chat~~ — resuelto 2026-08-02: "+ Invitar oficial" en los
  detalles de un chat PÚBLICO donde eres miembro (reusa `GET /officers/search`
  privacy-first: correo solo exacto y jamás mostrado). Decisiones: **solo públicos**
  (evento/puesto derivan su membresía de las asignaciones) y el invitado **entra
  directo** (los públicos son de libre unión: invitar es un atajo, no un permiso);
  queda **mensaje de sistema** visible y solo miembros pueden invitar
  (`POST /chats/{id}/members`).
- [x] ~~Moderación~~ — resuelto 2026-08-02 (5ª): long-press en una burbuja → sheet con
  **Reportar mensaje** (motivo opcional; tabla `message_reports`, `GET/DELETE
  /admin/reports` con scope nuevo `moderation`, sección "Moderación" en el admin web y
  tools MCP `listar_reportes`/`descartar_reporte`; el reporte NO altera el mensaje) y
  **Silenciar chat** (decisión del usuario: silencia las notificaciones del CHAT, solo
  local — `muted_chats` en SettingsStore, filtro en el colector global, tag "Silenciado"
  en el hub). De pilón: `GET /chats/{id}/messages` ganó guard de lectura (`canReadChat`:
  públicos libres, evento/puesto por asignación).
- [x] ~~Recibir en tiempo real~~ — resuelto 2026-08-01 (WebSocket `/chats/stream`; luego
  ampliado a `WS /stream` multiplexado con indicador de conexión global, 2026-08-02).
- [ ] **Video en mensajes** (fase b de multimedia): storage de archivos (no blobs en
  Postgres), límite de tamaño/duración, thumbnail generado por la app, streaming con
  range requests y ExoPlayer/Media3 para reproducir.
- [x] ~~Fotos de chat sin outbox~~ — resuelto 2026-08-01: texto e imagen se encolan
  offline con **eco local** (aparecen como propios al final de la conversación).
- [x] ~~Enviar mensajes es local~~ — resuelto 2026-07-28 (`POST /chats/{id}/messages`).
- [ ] **Privados (2026-09-25), lo que quedó fuera de la v1**: el creador no puede SACAR a
  un miembro (solo archivar el chat); una invitación enviada no se puede cancelar y no
  caduca; si el último miembro sale, el chat queda huérfano (sin miembros, no archivado).
  El invitado se ve a sí mismo en "Invitaciones pendientes" de los detalles.
- [ ] **Push con la app cerrada** también para invitaciones a privados (hoy el aviso
  "Te invitaron…" llega por el WS, solo con la app viva — mismo pendiente de FCM).
- [ ] **Mensaje de Control sin notificación (por investigar, visto 2026-09-26)**: en el
  emulador, con la app abierta en Inicio, un `POST /admin/events/{id}/chat/messages` al chat
  del evento activo NO generó la notificación "Mensajes" (sí llegaron "Cambió el MbM" y
  "Evento en curso" por el mismo WS). Revisar la cadena del colector de `ElPuestoApp.kt`
  (rama `c.kind == "chat"`): ¿llega el kind `chat` a ese oficial?, `unread` > 0,
  `newerMessages()` vacío si la conversación no estaba en caché, o el filtro de propios
  con `senderId` null.

## API admin + MCP (tras la implementación 2026-07-28)

- [x] ~~**Endurecer para producción**~~ — resuelto 2026-09-25 (el backend ya estaba
  expuesto por ngrok con usuarios reales): freno de fuerza bruta en `/admin` (10 claves
  inválidas en 15 min bloquean la IP 15 min, 429 + Retry-After; IP real = último valor de
  X-Forwarded-For solo si llega por loopback), límite del enlace mágico (5 por correo y 10
  por IP cada 15 min), el `devLink` SOLO en local (antes se devolvía si el SMTP fallaba:
  toma de cuentas), el backend NO arranca expuesto (`PUBLIC_BASE_URL`) con JWT_SECRET o
  ADMIN_API_KEY de desarrollo o de <32 caracteres, CORS solo para `CORS_ORIGINS` (default
  ninguno), cabeceras X-Frame-Options/nosniff/no-referrer, backend en loopback (`HOST`) y
  Postgres publicado solo en 127.0.0.1. El MCP usa una clave NOMBRADA (`mcp-claude-code`,
  sin scope keys) leída de `./setenv`; `.mcp.json` ya no lleva secretos. Queda: la
  contraseña de Postgres sigue siendo la de dev (mitigado: solo loopback) y el límite
  es en memoria (por instancia).
- [x] ~~OpenAPI formal~~ — resuelto 2026-08-02: `GET /admin/openapi.json` (misma auth que
  /docs) sirve `admin-openapi.json` (OpenAPI 3.0.3; con moderación: 71 operaciones/47
  paths/47 schemas).
  Es un recurso estático **mantenido a mano**: actualizarlo al cambiar la API (igual que
  admin-api.md).
- [x] ~~`subir_imagen` del MCP lee rutas locales~~ — resuelto 2026-08-02 (5ª): acepta
  `archivo` (ruta local) O `url` http(s) — el MCP descarga y sube; exactamente uno de
  los dos (error accionable si no).
- [x] ~~Borrar campeonato/evento no borra sus imágenes~~ — resuelto 2026-08-01: borrar
  campeonato limpia su emblema; borrar evento limpia su imagen (kind `event`) y las de
  sus chats (`chatimg` + `chatmedia` de los mensajes). Circuitos/trazados son borrado
  lógico (revivibles): ahí conservar el blob es correcto.
- [x] ~~La app no refleja `mapUrl` de trazados~~ — resuelto 2026-08-02: cadena de
  fallback del mapa = path dibujado → imagen `mapUrl` (contenida en el área cuadrada de
  referencia, caché offline) → cinta provisional por puestos. Con dibujo la silueta
  manda (una foto arbitraria no alinearía con los pins OSM).
- [x] ~~Admin web: referencias como texto libre, confirm() nativo, sin orden en bulk~~ —
  resuelto 2026-07-28 (2ª pasada): selects dependientes (circuito→trazado, oficiales,
  eventos, campeonatos), modal propio, ↑/↓ en bulk, filtros, botón Validar (dryRun),
  miniaturas de avatar/logo, preview de Markdown y **editor visual de puestos/activos
  sobre el mapa** (imagen kind `trazado` + colocar/arrastrar puntos).
- [x] ~~Admin web: bulk regeneran ids / editor 16:10~~ — resuelto 2026-08-02: el MbM ya
  acarrea sus ids (checklist/asignaciones ya lo hacían; el backend preserva los que
  vienen); el editor de imagen 16:10 legado ya no existe (lo reemplazó el editor
  unificado sobre OSM).
- [x] ~~App: dibujar `Trazado.path`~~ — resuelto 2026-08-01: silueta real cuando hay
  dibujo (transformación cuadrada compartida con los pins, como el admin web);
  fallback provisional si no. (`Event.trazadoIds` en la app: hecho 2026-08-02, ver docs/HISTORIAL.md.)
- [x] ~~Editor OSM: offline/zoom/debounce~~ — resuelto 2026-08-02: la búsqueda Nominatim
  sugiere con debounce de 600ms (elegir recentra) y los tiles caídos muestran un aviso
  que se quita solo al volver la red (el dibujo sigue editable). El zoom con rueda YA
  estaba anclado al cursor (deuda desactualizada). Los tiles siguen pidiendo red — un
  caché local de tiles no vale la pena por ahora.

## Seguridad y abuso (tras la auditoría completa del 2026-09-26)

Hecho en la auditoría (ver CLAUDE.md → "Sesión 2026-09-26 (3ª)"): XSS de la página puente,
CSP en todo, admin en su propio host, reparto del tiempo real por lista blanca, PKCE del
enlace mágico, tokens hasheados, cuentas sin correo expuesto, invitaciones con
consentimiento, bloqueos, reportes de chats/perfiles, nombres reservados, paginación,
cupos y topes diarios, exportación en streaming, pausas por función, congelamiento
automático, vista de uso, tokens cifrados en el teléfono, sin respaldo de Android,
FLAG_SECURE, CI sin credenciales al compilar, respaldos inmutables, IMDS bloqueado,
dependencias verificadas y aviso de privacidad de los logs.

- [ ] **Encender `AUTH_REQUIRE_PKCE=true`** cuando casi todos tengan la app con PKCE (1.1.2+):
  hasta entonces se aceptan enlaces sin reto de apps anteriores.
- [ ] **Bloque 3 — CloudFront + AWS WAF delante del API** (pospuesto por costo): límites por
  IP en el borde, reglas administradas (IPs con mala reputación, entradas maliciosas),
  Shield Standard y cerrar el security group a la lista de prefijos de CloudFront. Al
  hacerlo: `clientIp()` toma el ÚLTIMO X-Forwarded-For (detrás de CloudFront sería la IP
  del borde → todos compartirían cubeta y el freno del enlace mágico bloquearía a todo
  mundo; usar `CloudFront-Viewer-Address` o `trusted_proxies`), y el certificado de Caddy
  tendría que renovarse por DNS. Misma regla por IP en `/descargas/*.apk` del sitio.
- [ ] **Apretar los límites** (`RateLimits`) cuando casi todos tengan la app ≥ 1.1.1: la
  v1.1.0 descarta lo que recibe 429, por eso hoy son holgados. Revisar con la vista "Uso"
  del admin el uso real de un evento grande.
- [ ] **Aprobación manual de despliegues**: el workflow ya usa el entorno `produccion`;
  exigir un revisor necesita repo público o plan de pago. Mientras, un push malicioso a
  master despliega código del backend (sin root, sin credenciales de la instancia, sin
  respaldos ni Parameter Store).
- [ ] Contadores de límites, congelados y uso en memoria: con varias instancias harían falta
  en un almacén compartido (como `Throttle`); se reinician con el backend.
- [ ] Mover a `shared` los topes de texto y reglas de búsqueda que la app copia del backend
  (`TextLimits`, `OfficerSearchRules` en `androidApp/.../data/`).
- Decidido NO hacer (por ahora): *certificate pinning* en la app (rotar el certificado de
  Let's Encrypt dejaría la app sin conexión; TLS + HSTS de `.app` bastan) y ofuscar el
  release con R8 (no es una defensa: el código es auditable a propósito).

## Actualizaciones de la app (tras la opción 2, 2026-09-26)

Hoy: la app revisa `version.json` al abrirse (cada 6 h como mucho), avisa en el Inicio y en
Configuración, descarga y verifica el APK y Android pide confirmar (`AppUpdates`).
- [ ] **Opción 3 — sin confirmar** (Android 12+): `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
  + permiso `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (la app se actualiza a sí misma y ya es su
  propio instalador tras la primera actualización desde la app). Instalar CIERRA la app:
  solo en segundo plano, sin evento activo asignado y sin compartir ubicación; interruptor
  en Configuración. Siempre manejar `STATUS_PENDING_USER_ACTION` (el umbral de targetSdk
  sube con cada Android).
- [ ] **Versión mínima obligatoria** (`minVersionCode` en `version.json` → pantalla "Actualiza
  para seguir") para cambios de wire que rompan versiones viejas. Decidir antes con el usuario.
- [ ] Revisión en segundo plano (WorkManager/alarma diaria) + notificación "Hay versión
  nueva" para quien no abre la app; descarga automática solo con Wi-Fi.
- [ ] Reanudar descargas cortadas (`Range`) — hoy se reinicia desde cero (APK de ~11 MB).

## App web (primera versión, 2026-09-26)

La web es la MISMA app (`app/`); esto es lo que falta o funciona distinto en el navegador:

- [x] ~~Desplegarla en producción~~ **HECHO 2026-09-26**: `https://app.elpuesto.app` (ver
  DESPLIEGUE.md). Pendiente menor: caché larga para los `.wasm` con nombre por hash (hoy
  `no-cache` + ETag: revalida con 304 en cada carga).
- [ ] **Lint de AGP 8.7 con Kotlin 2.3**: `assembleRelease` imprime "Module was compiled with an
  incompatible version of Kotlin … expected 2.0.0" (el lint trae su propio Kotlin 2.0 y no lee
  los metadatos 2.3; el APK sale bien). Se va al subir AGP (8.13+ o 9, con Gradle nuevo); de paso
  se podría volver a activar `NullSafeMutableLiveData`.
- [ ] **`HEAD /app/…` da 404** (el `GET` sí sirve): Ktor sin AutoHeadResponse en esa ruta.
- [ ] **Notificaciones del navegador** (Notification API con la pestaña abierta; Web Push con
  el navegador cerrado — sería el primer "push" con la app cerrada y no requiere Firebase).
  Hoy la sección se oculta en la web.
- [ ] **Ubicación propia desde el navegador** (Geolocation con la pestaña visible). Hoy la web
  ve el mapa en vivo y administra la lista de permisos, pero no transmite.
- [ ] **Sin conexión**: caché y cola SOLO en memoria (decisión de privacidad: computadoras
  compartidas). Si se quiere offline en la web: IndexedDB + service worker, con un "recordar
  este dispositivo" explícito.
- [ ] **Emoji**: sin fuente de color, 📷 (vista previa "📷 Foto" del servidor) y otros emoji
  salen como cuadro. Opción: precargar Noto Emoji (monocromática, ~1-2 MB) como respaldo,
  igual que DejaVu Sans. `⤢` (zoom "ver completo" del mapa) tampoco está en DejaVu Sans.
- [ ] **Recorte del avatar**: en la web la foto se recorta al centro (sin recortador).
- [ ] Textos pensados para el teléfono: el mapa dice "Pellizca para acercar" (en escritorio,
  botones + y −).
- [ ] **kotlinx-datetime 0.7.1-0.6.x-compat**: material3 web exige 0.7.1; migrar `shared` y el
  backend a `kotlin.time.Instant` permitiría la 0.7 normal y quitar los avisos de deprecación.

## Controles inertes (visibles pero sin acción — TODO)

- [x] ~~Logos de circuitos~~ — resuelto 2026-08-02: el admin web ("logo" en Circuitos) y
  el MCP ya subían el kind `circuit`; la app ahora lo muestra por convención
  `/images/circuit/{id}` en el catálogo (fallback al nº de configuraciones) y en el
  header del detalle.
- [x] ~~Convocatorias: "Recordarme antes del cierre"~~ — resuelto 2026-08-01 (toggle con
  recordatorio local un día antes del cierre).
- [x] ~~"+ Agregar oficial" (compartir ubicación)~~ — resuelto 2026-08-01: buscador
  privacy-first (`GET /officers/search`: nombre/OMDAI ID; correo solo exacto y jamás
  se muestra) + allowlist local persistida.
- [x] ~~Configuración: "Descargar mis datos" y "Dar de baja mi cuenta" inertes~~ —
  ambas se **quitaron de la UI** 2026-08-01 (decisión del usuario: no mostrar opciones
  sin funcionalidad) y viven como features futuros en `docs/IDEAS.md`.

---

## Historial (resueltos)

- [x] UI del jefe de puesto → emergencia de compañero: "Consultar como jefe de puesto" en el
  perfil ajeno (403 vs. sin conexión diferenciados; acceso auditado; sin caché local).
- [x] Pantalla de detalle de evento pasado + clic del historial del perfil.
- [x] Avatares con foto en listas (compañeros, registro de accesos, burbujas de chat) por
  convención `/images/avatar/{officerId}` con fallback a iniciales.
- [x] Onboarding: subir foto de perfil (reusa el flujo de recorte de Editar perfil).
- [x] Agenda editable: CRUD de planeación personal persistido (backend /trip) + editor con
  pickers nativos + calendario pulido (hoy, mes real, sin fecha).
- [x] Chats: crear público + unirse/salir persistente (tabla chat_members; joined por usuario).

- [x] B fase 2: perfil/historial/emergencia/accesos/viajes/mensajes por backend; `PUT /me` y `PUT /me/emergency` reales (la edición local pasó a fallback offline).
- [x] Backend: standings/calendario/pilotos por categoría real (B fase 1; en Postgres).
- [x] Backend: el toggle de checklist persiste (destino real del outbox; antes stub).
- [x] Error visible + reintento al fallar el envío del magic link.
- [x] Campeonatos → detalle de piloto (CH-4). *(Luego se eliminó junto con el tab Pilotos por decisión de producto: Posiciones cubre la misma información.)*
- [x] Navegación conectada: circuito clickeable desde Home y Modo evento; pilotos → detalle.
- [x] Detalle de entrada de agenda (con planeación de viaje / AG-2).
- [x] Preservar el tab activo (Modo evento, etc.) al volver de un detalle — `SaveableStateHolder` en `AppNav`.
- [x] Los flujos de auth ya no crashean ante errores de red (se atrapa la excepción).
- [x] Agenda con calendario mensual + lista del día (antes lista plana).
- [x] Perfil: registro de accesos a emergencia (pantalla read-only).
- [x] Perfil: edición de perfil e info de emergencia (los botones "Editar" ya funcionan).
