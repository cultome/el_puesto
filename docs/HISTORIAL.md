# El Puesto — historial de sesiones

Bitácora sesión por sesión (julio–septiembre de 2026) que vivía en la §4 de `CLAUDE.md` hasta el
2026-09-27, cuando se movió aquí tal cual para que `CLAUDE.md` cargue ligero. Sirve para saber
POR QUÉ algo quedó como está (decisiones, lecciones, verificaciones). El estado vigente y los
avisos operativos están en `CLAUDE.md` §4; lo que aquí contradiga a `CLAUDE.md` ya cambió.

## 4. Estado de implementación (2026-09-24 — cierre de sesión)

**Hecho y verificado (corriendo en emulador y en teléfono físico vía ngrok):**
- Rebanada **M1**: Acceso → Home → Modo evento (Puesto/Cronograma), navegable.
- **Backend Ktor** con endpoints de lectura + auth + endpoint de mutación de checklist.
- **App ↔ backend por HTTP + protobuf** (con fallback a semilla + banner offline). URL del API configurable vía `-PapiBaseUrl` (BuildConfig); default `10.0.2.2:8080`.
- **SQLDelight offline-first + outbox**; checklist interactivo persistente (verificado 3/6→2/6).
- **Auth real endurecido**: magic link → **access token (15m) + refresh token (60d)**; gate por estado. **Cuentas/tokens en Postgres** (Exposed + Hikari), **secreto y TTLs desde config (env)**. Refresh con **rotación de un solo uso**. App renueva el access **transparente** ante 401 y reintenta (manejo manual en `HttpRepository`, no el plugin Auth). **Verificado end-to-end en teléfono físico**: al vencer el access, `/me`→401→`/auth/refresh`→reintento→200, sin re-login.
- **Navegación**: shell con barra inferior (**Inicio/Agenda/Chats**) + pila de overlays para detalles (hecho a mano, sin Navigation-Compose). **Mi Perfil se abre desde el avatar** del Home (no hay tab de Perfil). Inset de barra de estado global + `TopBarIconButton`/`BackButton` compartidos (42dp).
- **Pantalla de Perfil** (propio: emergencia + accesos + historial; otro oficial: emergencia bloqueada + eventos en común). Clic en compañeros de puesto → perfil. **Registro de accesos** (read-only) y **edición** de perfil/emergencia (por outbox desde 2026-09-25).
- **Pantalla de Agenda**: **calendario mensual** navegable + **lista de todo el mes visible agrupada por día** (el día seleccionado/hoy acentúan su encabezado); **detalle de entrada** al hacer clic (con planeación de viaje cuando aplica).
- **Modo evento**: swipe horizontal entre **Puesto/MbM/Chat/Bitácora** ("MbM" = minute by minute, el nombre corto del cronograma entre oficiales); detalle de actividad del cronograma al hacer clic. El tab **Chat lista los chats del evento y del puesto** y abre la conversación.
- **Circuitos**: catálogo (buscador + nº de configuraciones) → detalle con selector de trazado (ancho completo), mapa fijo con el trazado dibujado + posiciones (puestos/grúas/ambulancias), filtro por tipo y lista con "Ubicar" (highlight en el mapa). Acceso desde **"Explorar"** del Home.
- **Campeonatos** (solo lectura): catálogo → detalle con selector de categoría + tabs **Posiciones/Calendario** (con swipe). Acceso desde **"Explorar"** del Home. (El tab Pilotos y su detalle se **eliminaron por decisión de producto**: Posiciones ya trae número/piloto/equipo/puntos.)
- **Convocatorias** (solo consulta): lista de abiertas + **Historial** (pasadas) → detalle con datos del evento e **indicaciones en Markdown**, "Postularme ↗" (link externo). Acceso desde **"Convocatorias abiertas · Ver todas"** del Home.
- **Configuración**: hub (ubicación, notificaciones con toggles, privacidad, cuenta, **cerrar sesión**) + **Compartir ubicación** (opt-in, allowlist que el usuario controla, quitar oficiales). Acceso por **engrane** en Mi Perfil. Cierra la ubicación GPS fuera de Modo evento.
- **Chats (UI)**: hub (Del evento activo / Públicos / Archivados) → conversación, explorar públicos, archivados read-only. **Tiempo real por WebSocket HECHO 2026-08-01** (ver bloque abajo); **moderación HECHA 2026-08-02 (5ª)** (long-press → reportar/silenciar). Burbuja: nombre + **"P N · Rol" del remitente** en la misma línea (derivado de las asignaciones del evento del chat), hora sola (HH:mm) bajo el mensaje y **separadores de día estilo WhatsApp** (Hoy/Ayer/fecha) — mismo diseño en el chat del detalle de evento en la admin web (2026-08-01).
- **Chat persistente + multimedia + detalles** (2026-07-28, verificado por curl y en teléfono): **enviar mensajes es real** (`POST /chats/{id}/messages`, guard de membresía en públicos y de archivado; `ord = max+1` porque los de semilla son globales; actualiza `lastPreview`). **Mensajes con imagen** (`POST /chats/{id}/media`, pie en `?text=`, kind `chatmedia` con proporción original; `Message.mediaType`): clip en el composer → sheet Cámara (FileProvider)/Galería (**Photo Picker**, sin permisos), burbuja con miniatura → **visor a pantalla completa** (`ImageViewerScreen`, overlay `ImageView`). **Avatar junto a TODOS los mensajes** (propios incluidos). **Detalles del chat** (tap en el header): imagen del chat (kind `chatimg`, se elige al crear en `NewChatScreen`; **cualquier miembro del público la cambia** desde detalles — decisión 2026-07-28, el guard del backend valida membresía), participantes con avatar (tap → perfil; públicos por `chat_members`, evento/puesto = compañeros del evento activo), y opciones **Salir** (público unido) y **Archivar** (`POST /chats/{id}/archive`, solo creador — `Chat.creatorId` nuevo); evento/puesto sin opciones por ahora. `SchemaUtils.createMissingTablesAndColumns(ChatsT, MessagesT)` migra la DB viva. Video = fase posterior (necesita storage de archivos + streaming + ExoPlayer).
- **Completar perfil** (onboarding, 2 pasos): tras login activo, si el onboarding está pendiente (**por CUENTA en el servidor** desde 2026-09-26: `accounts.onboarded_at`, `GET/POST /me/onboarding`; el flag local `onboardingDone` solo evita preguntar; sin red no se fuerza) → identidad (con **foto opcional**, mismo flujo de recorte que Editar perfil) + emergencia opcional antes del Home.
- **Emergencia del jefe de puesto (UI)**: en el perfil de otro oficial, "Consultar como jefe de puesto" llama al endpoint auditado (403 = no autorizado vs. sin conexión, mensajes distintos); al concederse muestra los datos con el aviso de que el titular verá la consulta. Sin caché local. Verificado: carlos→laura 403.
- **Detalle de evento pasado**: read-only desde el historial del perfil (fecha, sede, rol).
- **Avatares en listas**: compañeros de puesto, registro de accesos y burbujas de chat usan `RemoteAvatar` por convención `/images/avatar/{officerId}` (fallback a iniciales).
- **Agenda editable** (verificada end-to-end por curl): "+" crea **Transporte/Hospedaje/Recordatorio** (editor con pickers nativos de fecha/hora y vínculo a evento); editar/eliminar desde el detalle (entrada personal e ítems del viaje con ✎) y **"+ Agregar al viaje"**. Backend: CRUD `/trip` (GET/POST, PUT/DELETE `/trip/{id}`, solo ítems propios con `officer_id`); la agenda **fusiona** los trip items personales como entradas (`personal=true`; shared: `TripItem.personal`, `AgendaEntry.personal`). La app resincroniza agenda+viaje en una transacción tras cada mutación. Calendario: **hoy** resaltado, mes por defecto del reloj real, sección **"Sin fecha"**. Pendiente: encolar estas escrituras en el outbox (hoy red directa con error visible).
- **Bitácora de viaje** (2026-07-27 noche, diseñada en Claude Design — turnos BV en `docs/design/Bitacora de viaje.dc.html` — y verificada en teléfono físico): la "Planeación de viaje" del detalle de entrada es ahora un **timeline cronológico** agrupado por día (nodos de color por tipo, chips, día HOY en verde, marcador **AHORA**, futuro atenuado) que además acepta **fotos** (cámara del sistema vía FileProvider, pie de foto, `POST /trip/{id}/photo`, variante full ≤1280 con proporción original, kind `trip` en `images`) y **notas rápidas** (`TripItemKind.PHOTO/NOTE`; excluidas de la proyección de agenda — viven solo en la bitácora). **Modo evento tiene 4ª pestaña "Bitácora"** (mismo timeline acotado al evento; "Cronograma" se abrevia **"MbM"**, término conocido por los oficiales) y hay **FAB global** (cámara ámbar + punto verde) en el shell mientras hay evento activo que abre el sheet "Agregar a la bitácora" (Foto/Nota/planeación con tipo preseleccionado). Pendientes en `docs/DEUDA-TECNICA.md` (fotos sin outbox, blob huérfano al borrar, retomar foto).
- **Bottom bar NB-3 simplificada** (de `docs/design/Bottom bar.dc.html`): barra plana con iconos dibujados (Canvas, sin librería) y etiquetas IBM Plex Mono en mayúsculas; activo en ámbar. El indicador a cuadros se probó y **se quitó por decisión del usuario** (implementado no se veía bien). **Íconos nuevos (2026-09-27)**, canvas `docs/design/Iconos bottom bar/`: box de pits (Inicio), calendario con franja a cuadros (Agenda) y radio de mano (Chats), como `LineIcon` de 24×24 a 26 dp (`GARAGE`/`CALENDAR_CHECKERED`/`RADIO`; `LineIcon.fill` = partes rellenas).
- **Home pulido (2026-07-28)**: cards de Tu agenda con **fechas relativas** ("dentro de 8 días", "mañana", "hace 3 días"), filtradas a la ventana **[hoy, +15 días]** (sin pasados ni sin fecha), **ordenadas por fecha** y con vacío amable; el carrusel de convocatorias descarta las de **cierre ya vencido** (cinturón además del flag OPEN).
- **Bitácora solo en entradas Evento (2026-07-28)**: el detalle de las demás entradas de agenda muestra solo sus datos + tarjeta **"Evento vinculado"** que abre el detalle del evento (ahí vive la bitácora). Editor de **fotos restringido** (decisión de producto: solo pie + eliminar; imagen/hora/evento se preservan) y **sin flasheo** al editar (skeleton mientras carga el ítem).
- **Teclado arreglado (2026-07-28)**: `adjustResize` + `imePadding()` global (ver convención en §6); el chat además ancla la conversación al último mensaje y su composer respeta la barra de gestos.
- **Chats con membresía real** (verificada por curl con carlos y laura): tabla `chat_members`, `GET /chats` personaliza `joined` por usuario; **crear chat público** (`POST /chats`, el creador queda unido; la app abre la conversación al crear) y **unirse/salir persistente** (`POST /chats/{id}/join|leave`, ajusta el conteo). Tiempo real (2026-08-01) y moderación (2026-08-02, 5ª) ya están hechos.

**Stub / provisional (a reemplazar):**
- **B COMPLETA (fases 1 y 2)**: todo el dominio se sirve desde **Postgres** (24 tablas normalizadas; siembra única desde `SeedData`, luego admin/ingesta). Incluye perfil de oficiales + historial, **emergencia con acceso auditado del jefe de puesto** (`GET /officers/{id}/emergency` registra cada consulta), `PUT /me` y `PUT /me/emergency` reales, viajes y mensajes. La app ya **no lee `SeedData` directamente**: red → caché → semilla (solo como último fallback offline). Enviar mensajes persiste (2026-07-28) y **recibir es en tiempo real por WebSocket** (2026-08-01).
- **Avatares con pipeline genérico de imágenes**: la app elige + **recorta** (image-cropper) + sube; el backend (`ImageService`) genera variantes **thumb 96 / full 512** (JPEG en Postgres, tabla `images(kind,owner,variant)`) — `POST /me/avatar`, `GET /images/{kind}/{owner}/{variant}`. `RemoteAvatar` en la UI (foto con caché offline, fallback a iniciales). El mismo pipeline servirá para **logos de campeonatos/circuitos** (kind ya parametrizado).
- **Correo (magic link) REAL (2026-08-02, 5ª)**: las credenciales SMTP viven en **`./setenv`** (script fish del usuario, en .gitignore; `source ./setenv` en el shell donde corre el backend). `EmailSender` envía de verdad (verificado: `sent:true, devLink:null` a la cuenta real del autor, ACTIVE, con su oficial). La app **ya NO tiene el acceso demo**: se quitó el botón "Abrir enlace (demo)" y el uso del `devLink` — el acceso llega SOLO por el correo (deep link `elpuesto://auth?token=…`, manejado en frío por onCreate y con la app abierta vía `MainActivity.pendingAuthToken`). Sin SMTP el backend sigue regresando `devLink` (útil para curl, la app lo ignora). **Correo HTML con tema Paddock** (multipart/alternative, tablas + estilos inline, sin imágenes remotas) con botón "Entrar a El Puesto"; como Gmail y similares QUITAN los links de esquema propio, el botón apunta a la **página puente `GET /auth/open?token=…`** cuando hay `PUBLIC_BASE_URL` en env (en `setenv`: localhost para dev; cambiarla a la URL de ngrok al probar en teléfono) — la página (misma estética) reintenta `elpuesto://` solo y deja botón manual. Cuentas de prueba reales: dos del autor (ACTIVE, un mismo oficial).
- ~~Checklist sincronizada + quién/cuándo~~ **HECHO 2026-08-01**: checklist POR PUESTO —
  plantilla en `checklist_items` (sin `done`; columna dropeada) + estado en
  `checklist_state(item_id, puesto_id, event_id, done, marked_by, marked_at)`. El GET de la
  app personaliza por el puesto del viewer (JWT) y el POST marca el puesto del oficial
  (mismo wire: la app NO cambió; los compañeros de puesto comparten estado). Reemplazar la
  plantilla preserva el avance por id de ítem (limpia el de removidos). Visor admin:
  `GET /admin/events/{id}/checklist-state` (quién/cuándo) + en el detalle de evento chips
  de avance por puesto y el checklist del puesto seleccionado (✓ verde con tooltip
  "Marcada por X · fecha"). Tool MCP `ver_checklist_por_puesto`. OJO ruta de la app:
  `POST /events/checklist/{id}` (anidada bajo /events).

**Orden acordado con el usuario (post-v1 UI):** C → F → G → A ✅ (todas). Después
(2026-07-27 tarde): **rápidos** ✅ y **medianos** ✅. Sesión 2026-07-27/28 (noche):
**bitácora de viaje completa** ✅ (diseño en Claude Design + implementación), ajustes
visuales (bottom bar, fechas relativas, filtros del Home, teclado) ✅ y **chat
persistente + imágenes + detalles** ✅ — todo validado por el usuario en su teléfono.

**Sesión 2026-07-28 (día): interfaz administrativa, fases 1 y 2 de 3** ✅ — decisión de
producto: el grueso de la administración la harán **agentes de AI**, así que primero la
**API admin JSON agent-friendly** (ver §3: keys con scopes, auditoría, upserts, dryRun,
bulk, errores accionables, `/admin/docs`) y el **servidor MCP `adminMcp`** (49 tools en
español, stdio, registrado en `.mcp.json`). Verificado por curl y hablando JSON-RPC al
MCP (initialize/tools/list/tools/call, dryRun, errores, logo de campeonato, smoke de la
app sin regresión). **Fase 3 (admin web para humanos)**: SPA vanilla en `/admin/ui/`
(ver §3). El usuario validó las pantallas y pidió completar la funcionalidad → **2ª
pasada hecha**: selects dependientes (circuito→trazado, oficiales, eventos,
campeonatos), editores bulk con orden ↑/↓, botón **Validar** (dryRun), modal de
confirmación propio, filtros en tablas, miniaturas de avatar/logo (fetch autenticado →
blob), preview de Markdown en convocatorias, **flag `active` de eventos visible con
toggle** (nuevo `AdminEventInfo` en `GET /admin/events`), y **editor visual de
puestos/activos sobre el mapa del trazado** (imagen kind `trazado` actualiza `mapUrl`;
colocar con clic y arrastrar; lista lateral sincronizada). Fix importante: los upserts
ya **conservan `avatarUrl`/`emblemUrl`/`mapUrl` cuando vienen null/omitidos** (antes los
borraban). Pendiente la validación del usuario de esta 2ª pasada.

**3ª pasada (2026-07-28, tras feedback del usuario):**
- **Bug de raíz de la "funcionalidad faltante"**: los editores de MbM/checklist/asignaciones/compañeros hacían GET a rutas que solo existían como PUT → 404 → pantalla en blanco. Se agregaron los **GET de sub-listas de evento** y las vistas fallidas ahora muestran **caja de error persistente** (no solo toast). Encontrado reproduciendo en **Chromium headless vía chromedriver/WebDriver HTTP puro** (arnés en scratchpad: `wd.py` — la extensión de Chrome no conecta en esta sesión; usar este arnés para validar la web).
- **Navegación**: botón **← Volver** además de las migas; **detalle de evento** (fila "abrir ▸") con tarjetas hacia MbM/checklist/asignaciones/compañeros y toggle de activo.
- **Trazado dibujado sobre OpenStreetMap**: `Trazado.path` (`@ProtoNumber(8)`, `List<MapPoint>` normalizada) + columna `path_json` + tabla `trazado_geo` (vértices lat/lon para re-editar) + `GET/PUT /admin/trazados/{id}/path` (proyección Web Mercator, aspecto preservado, margen 5%). Editor con pestañas: **"Trazado (dibujo sobre OSM)"** (slippy map hecho a mano — tiles de tile.openstreetmap.org + atribución, pan/zoom/rueda, búsqueda Nominatim, clic para agregar vértices, arrastrar para ajustar, cierre implícito) y **"Puestos y activos"** (editor normalizado con la **silueta dibujada de fondo**). MCP: tools `ver_dibujo_trazado`/`guardar_dibujo_trazado` (51 tools).
- Verificado con el arnés WebDriver: detalle de evento, MbM (5 filas, ✕ y ↑/↓), Volver×2, dibujo de 3 vértices con clics + guardar + silueta en pestaña puestos.

**Correcciones de producto sobre el modelo (2026-07-28, tarde):** checklist admin = solo **plantilla** (`[{id?, text}]`, el `done` lo marcan los oficiales y se **preserva por id** al reemplazar; visor por puesto diferido con "quién/cuándo"). **Eventos multi-trazado**: `Event.trazadoIds` (`@ProtoNumber(8)`, lista; `trazadoId` queda como el principal/compat con default `""`), tabla `event_trazados(event_id, ord, trazado_id)` con migración automática, upsert valida que todos los trazados sean del circuito del evento, borrar trazado revisa también la tabla de unión, y el form web usa checkboxes (`refmulti`, dependiente del circuito). La app aún ignora `trazadoIds` (lo consumirá junto con `Trazado.path`). **`Event.status` ya no se captura**: se **deriva de las fechas al leer** (`DomainRepository.eventStatus`: hoy < startsOn = UPCOMING, ≤ endsOn = LIVE, después = FINISHED; el body lo ignora, el form web no lo muestra); "en curso" para la app sigue siendo el flag `active`, independiente del estatus. **Asignaciones rediseñadas**: se capturan solo `{officerId, role, puestoId, shift}` — el puesto debe ser de un **trazado del evento** (selector de trazado arriba del editor alimenta el combo Puesto), `puestoNumber` se **deriva del puesto** al guardar, y `shift` es catálogo: **"Día completo"** (default) o **"Turno 1".."Turno 8"** (validado; el legado "Turno completo" migró). El oficial se elige con **`combosearch`** (widget nuevo en app.js: combo con búsqueda escrita por nombre/OMDAI ID, dropdown `position:fixed` para sobrevivir al scroll de la tabla, máx. 50 resultados) — pensado para ~10k oficiales; `bulkView` ganó `top:` (barra superior que puede mutar options y re-render) y las asignaciones acarrean su `id`. **"Sector" eliminado del sistema (2026-07-29)**: fuera `Assignment.sector` (y sus chips en Home/Modo evento) y `Puesto.sector` se renombró **`label`** (mismo `@ProtoNumber(4)`; es el "ID Puesto" del editor del mapa, default derivado "MP N"); migración en Postgres (copia sector→label y drop de columnas).

**Sesión 2026-07-31: IDs de entidad → UUIDv7 del servidor** (decisión de producto: los IDs
nunca los elige el usuario y no se muestran en la UI salvo razón clara):
- **Contrato admin nuevo**: crear = `POST /admin/<entidad>` sin `id` (400 si lo mandas; la
  respuesta trae el UUID); `PUT /{id}` **solo actualiza** (404 → "usa POST"); cuentas siguen
  por email. Validaciones compartidas POST/PUT (`validOfficer`… en `AdminRoutes`), gate
  `AdminRepository.entityExists`, generador `Ids.kt: uuidv7()` (también mensajes, trips,
  chats públicos, accesos de emergencia, keys y auditoría). `admin-api.md` actualizado.
- **SeedData reescrito** con UUIDs fijos (constantes `ID_*`, cross-refs por constante;
  `Persistence`/`seedPhase2` las usan). **DB de dev reseteada y resembrada** (decisión del
  usuario: sin migración; `docker compose down -v`).
- **MCP**: `guardar_*` con `id` OPCIONAL (sin id = POST crear, con id = PUT actualizar;
  helper `upsertTool`); sin hints de slugs.
- **Admin web sin IDs visibles**: fuera columnas y campos ID (el id editado vive en
  `editingId`, crear = POST); títulos/migas/chips/confirmaciones por **nombre** (helpers
  `labelOf`, `refNameCell`); combos sin `(id)`; toast sin id; tabla de claves sin columna ID
  (auditoría sí conserva su columna ID — trazabilidad). El id solo sobrevive en el hash de
  la URL (navegación). Cuentas: el email sigue en el form (`keyed: true`).
- **Verificado**: curl (POST/PUT/404/400/dryRun/DELETE), MCP por JSON-RPC (crear sin id →
  UUID; id inexistente → 404), app compila, y arnés WebDriver (`wd.py` recreado en el
  scratchpad de esta sesión) + captura: formularios sin ID, alta por POST, detalle de evento
  con nombres. Ojo: el botón del modal de confirmación se llama **"Confirmar"**.
- **Borrado lógico de la cadena de circuitos (2026-08-01, decisión del usuario)**: columna
  `deleted_at` (ISO, null = vivo) en circuits/trazados/puestos/track_assets. `DELETE` de
  circuito/trazado = **soft delete con cascada hacia abajo** (ya NO se rechaza por eventos:
  los eventos/asignaciones históricos conservan sus referencias); catálogos, combos y
  validaciones (crear evento, asignaciones, rounds, PUT/entityExists) tratan lo archivado
  como inexistente. Los **bulk de puestos/activos archivan las filas removidas** (no las
  destruyen; re-mandar un id archivado lo revive). El resto de entidades sigue con borrado
  físico + cascada. Ojo: "Autódromo de Querétaro" se borró FÍSICO durante la validación del
  usuario (era el comportamiento anterior); la semilla no lo repone (solo siembra DB vacía).
- **Tiempo real en el detalle de evento (2026-08-01, SSE)**: `ChangeBus` (SharedFlow en
  memoria; emiten las mutaciones de checklist/asignaciones/sesiones/evento/puestos) +
  `GET /admin/events/{id}/stream` (SSE hecho a mano con `respondTextWriter`, keepalive
  25s; Ktor 2 no trae plugin) con **token efímero de un solo uso** vía
  `POST /admin/events/stream-token` (EventSource no puede mandar X-Admin-Key). Patrón
  **notificar-y-refetch**: el evento `change` dice `kind` y la página re-pide esa sección
  con re-render dirigido (renderMbm/renderChk/renderMap/renderSide re-renderizables;
  `activeViewCleanup` global que renderMain corre al navegar cierra el EventSource y el
  tick de 60s del MbM; reconexión = re-pedir token). El mismo bus servirá para el
  WebSocket del chat cuando toque; multi-instancia futura = LISTEN/NOTIFY.
- **Detalle de evento "todo en una vista" (2026-08-01, diseñado en Claude Design —
  `docs/design/Admin - Detalle de evento.dc.html` — y aprobado por el usuario)**:
  `eventDetailView` reescrito — header con chips y acciones; panel central con **tabs por
  trazado del evento** y **mapa read-only** (silueta de `Trazado.path` en SVG dentro de un
  área cuadrada para no distorsionar la normalización; puestos por `point` con clic;
  activos con `ASSET_TYPES.abbr`); puestos con oficiales en ámbar + contador; **clic en
  puesto → panel derecho con sus asignaciones** (avatar real vía `authBlobUrl` con
  fallback a iniciales; Chief Post Marshal destacado); sin selección → **cobertura**
  (KPIs: oficiales, cubiertos/total del trazado, "sin jefe" en rojo + filas clickeables).
  Abajo: MbM agrupado por día (punto verde LIVE), campeonatos con sus categorías y
  checklist. Los editores bulk siguen siendo las mismas vistas (links "editar ›").
  Gotcha aprendido: `append()` nativo NO aplana arrays (nuestro `h()` sí) — esparcir.
- **"Compañeros de puesto" eliminado como captura (2026-08-01)**: la tabla `puesto_mates`,
  su editor web, `PUT/GET /admin/events/{id}/mates` y la tool `reemplazar_companeros`
  desaparecieron — todo se **DERIVA de las asignaciones**: compañeros = oficiales del
  MISMO puesto que el viewer (`DomainRepository.mates(eventId, viewerId)`); jefe = rol
  `CHIEF_ROLE` ("Chief Post Marshal"); participantes de chat de evento = todos los
  asignados, de puesto = mismo puesto; guard de emergencia (`chiefViewContext`) = jefe del
  MISMO puesto del consultado (antes: cualquier jefe del evento — quedó más estricto).
  La ruta de la app `/events/{id}/mates` ahora usa el JWT del viewer. `SeedData.assignments`
  siembra el puesto 7 completo (Laura = Chief). ~~Pendiente opcional~~ **HECHO 2026-08-01**:
  replaceAssignments valida máx. un Chief Post Marshal por puesto (rechaza el reemplazo
  con error accionable; documentado en admin-api.md y en la tool MCP).
- **Roles de asignación = catálogo operativo (2026-08-01, decisión del usuario)**:
  `Assignment.role` dejó de ser el enum `Area` y es **String validado por el backend**
  (`AdminRepository.validRoles`): Chief Post Marshal, Comunicador, Bandera Azul/Amarilla,
  Intervención 1-5, Bombero 1-3, Jefe/Operador Telehandler. **Distinto del "Área asignada"
  del perfil** (que sigue siendo el enum `Area` de 5 valores, decisión de producto §1).
  Migración: valores legados mapeados al equivalente más cercano (BANDERAS→Bandera
  Amarilla, INTERVENCION→Intervención 1, COMUNICACION/CRONOMETRAJE→Comunicador,
  RESCATE→Bombero 1). La app muestra el rol tal cual (`a.role`, ya sin `display()`).
- **Eventos ↔ campeonatos (2026-08-01)**: `Event.championshipIds` (`@ProtoNumber(9)`,
  lista) + tabla `event_championships(event_id, ord, championship_id)`; upsert valida
  existencia, borrar campeonato referenciado se rechaza (con NOMBRES de eventos). En el
  MbM, `category` es **OPCIONAL** (vacía = sin categoría; la ruta inyecta `""`) y el form
  web la captura con combo de las categorías de los campeonatos del evento ("—" = ninguna;
  valores legados aparecen como "fuera de catálogo"). Normalización previa: **Posiciones
  referencia a Pilotos** por `driverNumber` (standings sin driver_name/team, join al leer,
  validación cruzada en ambos sentidos).
- **Ajustes tras feedback del usuario (misma sesión)**: `Trazado.direction` = catálogo
  **"Horario" | "Antihorario"** (select en la web + validación backend con hint), y
  **`Trazado.lengthKm: Double` → `lengthM: Int` (METROS enteros)** — mismo `@ProtoNumber(4)`
  pero cambia el wire type (double→varint): caché protobuf vieja en un teléfono se sanea al
  resincronizar (catálogos van red-primero). Columna `length_m` (default 0 para el ALTER) con
  migración in-place desde `length_km` (×1000, redondeado; la columna legado se dropea); la
  app sigue MOSTRANDO km (`fmtKm(metros)`); form web "Longitud (m)"; validado end-to-end
  con la DB viva (conservó un trazado capturado por el usuario).
- **App en vivo + pull-to-refresh (2026-08-01)**: Modo evento escucha
  `GET /events/{id}/stream` (SSE con Bearer, sin token efímero; `HttpRepository.
  eventChanges()` con reconexión + tryRefresh) y refresca MbM/checklist al vuelo. El MbM
  resalta la **actividad EN CURSO por reloj** (estado derivado `deriveSessionStatus`:
  LIVE entre inicio y inicio+duración o la siguiente actividad del día) y se
  **auto-centra** en ella al abrir (`onGloballyPositioned` + viewportSize/2).
  **Pull-to-refresh en TODAS las pantallas** vía `Refreshable`
  (`ui/components/Refreshable.kt`, wrapper de `PullToRefreshBox` Material3 con indicador
  Panel/Amber). **Marcar un evento activo desactiva los demás** (`setEventActive`
  exclusivo — antes podía haber dos "En curso").
- **Detalle de evento web = centro de control (2026-08-01, iteraciones con el usuario)**:
  pins del mapa **verdes cuando el puesto completó su checklist** (y se quitaron los
  chips de puesto del panel); **chat del evento embebido** junto al MbM (columna derecha;
  Campeonatos y Checklist quedaron debajo del MbM) — el admin escribe como **"Control"**
  (`sendControlMessage`: senderId null, senderName "Control";
  `GET/POST /admin/events/{id}/chat(.../messages)`; `ensureEventChat` crea/renombra el
  chat del evento al guardar; `Chat.eventId` `@ProtoNumber(12)` liga chat↔evento). El SSE
  admin también notifica `kind="chat"` filtrado por el chat del evento.
- **Chat con contexto del remitente (2026-08-01)**: `Message.senderPuesto/
  senderRole` (`@ProtoNumber(9/10)`), derivados al leer (`senderContext(chatId)`:
  asignaciones del evento del chat; el fallback al evento activo es SOLO para chats de
  evento/puesto — un público o privado sin evento ligado no lleva puesto/rol ni en burbujas
  ni en participantes, corregido 2026-09-27). App y admin web: nombre +
  "P N · Rol" en ámbar mono, hora sola (24H) bajo la burbuja, separadores de día
  Hoy/Ayer/fecha. Verificado end-to-end (curl, emulador con llegada en vivo por WS,
  arnés headless en la web).
- **Participantes del chat con contexto (2026-08-01)**: `/chats/{id}/members` devuelve
  `ChatMember(officer, puesto, role)` (shared; mismo `senderContext`). En Detalles del
  chat, junto al nombre: chat de **puesto = solo el rol** (el puesto es obvio), evento y
  públicos = "P N · Rol" (ámbar mono, como la burbuja). La caché local cambió de clave
  (`chatmembers2:`) por el cambio de tipo del blob. Verificado por curl (puesto/evento/
  público).
- **Imagen de evento (2026-08-01)**: kind `event` en el pipeline de imágenes (cuadrada
  thumb/full, `POST /admin/images/event/{id}`; **por convención, sin campo en el modelo**
  — como circuit). La app la muestra donde se menciona el evento: chats de EVENTO
  (`chatImageUrl()`: lista, header de conversación y detalles usan
  `/images/event/{eventId}/full` en vez de `chatimg`; puesto/públicos siguen con la suya)
  y **banner arriba del EventHero** del Home (solo si existe; 110dp, crop). Admin web:
  miniatura en la tabla de Eventos + acción "imagen"; MCP `subir_imagen` acepta
  `tipo=event`. Verificado por curl (upload admin + GET con Bearer, thumb 96/full 512).
  Ojo: el evento "Súper Copa Telcel · Fecha 4" de dev quedó con un degradado de prueba.
- **No leídos reales + notificaciones locales de chat (2026-08-01)**: tabla
  `chat_reads(chat_id, officer_id, last_read_ord)`; `GET /chats` calcula `unread` por
  lector (mensajes ajenos con ord > marca; sin marca = todo sin leer; archivados y
  públicos no unidos = 0). `POST /chats/{id}/read` deja la marca en el último mensaje y
  emite `chat` al bus SOLO si avanzó (guard anti-ciclo: la conversación abierta re-marca
  al refetchear). Enviar mensaje marca leído al remitente en la misma tx. App: **punto
  ámbar** de no leídos (sin contador, decisión del usuario) en hub y tab Chat del Modo
  evento; abrir la conversación marca leído (y en cada mensaje en vivo). **Notificaciones
  del sistema** (canal "mensajes", estilo WhatsApp): colector global del WS en `AppNav` —
  notifica si el chat no está abierto, `NotificationPrefs.chatMessages` lo permite y el
  `unread` del server > 0 (árbitro contra duplicados); abrir la conversación la retira
  (`ChatNotifications`); permiso `POST_NOTIFICATIONS` runtime en Android 13+. **Tocarla
  abre ESA conversación** (intent explícito con extra `chatId` + SINGLE_TOP; requestCode
  por chat; `MainActivity.pendingChatId` StateFlow que el shell consume aterrizando en el
  tab Chats — verificado en caliente vía onNewIntent y en frío con proceso muerto). Solo
  viven mientras la app vive (WS); **push real (app cerrada) = FCM, pendiente** (requiere
  proyecto Firebase + google-services.json). Ojo pruebas: `am force-stop` BORRA las
  notificaciones de la app; para simular proceso muerto conservándolas usar `am kill`.

- **Tiempo real total + indicador de conexión global (2026-08-02)**: decisión del usuario
  — con internet TODO es en vivo; el modo offline es solo el fallback real. (1)
  `AdminAudit.record` emite al bus TODA mutación admin (kind `admin:<entidad>`, id/action/
  detail; no dryRun) y el nuevo **`WS /stream`** (Bearer) reenvía todo — chats con guard
  `canSeeChat`, admin con `label` resuelto (nombre de evento/convocatoria) — como
  `StreamChange{kind,id,action,detail,label}`. (2) App: **UN solo socket multiplexado**
  (`HttpRepository.changes()` con shareIn; `chatChanges()` es vista filtrada; el socket es
  además el **latido de conexión**: conectado=online, caída=offline en segundos) +
  colector global en AppNav: cualquier `admin:*` → `repo.refresh()` (conflate + 1.5s), y
  **notificaciones** de "Evento en curso" (set-active=true → tap abre Modo evento) y
  "Convocatoria nueva" (created, gateada por prefs.newConvocatorias → tap abre su
  detalle) en canal "avisos" (extras/StateFlows en MainActivity como el chat). (3)
  **ConnectionBanner global en el shell** (tabs Y overlays; las pantallas ya no ponen el
  suyo) con transitorio verde "De vuelta en línea" 2.5s. androidApp ganó el plugin
  kotlin-serialization + dep kotlinx-serialization-json (DTO del stream). Verificado en
  emulador: desactivar evento desaparece la card SOLA (sin pull), reactivar la regresa +
  notificación → Modo evento, convocatoria nueva → notificación → detalle, matar backend
  → banner rojo en un overlay a los segundos, revivirlo → se quita solo.

- **Resto de la sesión 2026-08-02** (commits chicos, verificados en emulador/teléfono):
  **saludo aleatorio del Home** (30 frases con sabor de pista, neutras, una por apertura
  — `GREETINGS` en `HomeScreen.kt`); **fix evento activo pegado**: `refresh()` ahora corre
  `clearActiveEvent()` SIEMPRE (antes solo si venía un activo → desactivar en el admin no
  se reflejaba ni con pull-to-refresh); **admin web**: el SSE del detalle reintenta si el
  stream-token falla (antes moría para siempre con el backend reiniciándose) y los
  mensajes propios (Control) van a la derecha como en la app; **notificación de chat
  navega** a la conversación (frío y caliente); **ngrok pasa los WebSockets** sin config
  (wss por el mismo túnel https; verificado con cliente wss + APK `-PapiBaseUrl` en
  teléfono físico). Emulador SDK actualizado a 37.1.11 / platform-tools 37.0.1.

- **Sesión 2026-08-02 (2ª — cierre)**: (1) **Pulido estético de la app** (verificado en
  emulador, instalado en teléfono): MbM colapsa las actividades anteriores a la previa
  ("N actividades anteriores" desplegable) y atenúa lo terminado (alpha 0.55); el tab
  Chat del Modo evento usa la imagen del chat/evento (`chatImageUrl` ahora internal);
  empty state protagónico de Bitácora (glyph de cámara, píldora hacia el FAB); detalle
  de actividad con fila **"Campeonato ›"** (resuelve la categoría contra el catálogo)
  que navega al campeonato; chip del jefe = **"CMP"** (antes "JEFE"). (2) **Fix chats de
  evento desactivado** (investigado por subagente): `DomainRepository.chats()` ahora
  excluye chats EVENT/PUESTO vivos cuyo evento no sea el ACTIVO (convención de
  canSeeChat: eventId null = el activo; archivados se listan), `refresh()` de la app
  resincroniza la caché de chats, y el hub refetchea ante cualquier `admin:*` — la
  sección "Del evento activo" desaparece/regresa sola (verificado con el hub abierto).
  (3) **Máx. un Chief Post Marshal por puesto**: validación en `assignmentsProblem()`
  (compartida por `replaceAssignments` Y el **dryRun de la ruta** — probando el editor
  web con dos jefes se vio que "Validar" pasaba y "Guardar" fallaba; ahora ambos
  rechazan igual); documentado en admin-api.md y la tool MCP. (4) **`docs/IDEAS.md`**
  nuevo (features grandes): eventos en común en perfil ajeno (gap: hoy muestra el
  historial completo del otro) y checklist que se limpia cada día. (5) **Regla de
  trabajo**: todo cambio de app se INSTALA en el dispositivo de prueba actual (hoy:
  teléfono vía ngrok con `-PapiBaseUrl`; guardada en memoria). Ojo: matar procesos del
  backend con `lsof -t -sTCP:LISTEN -i:8080` (sin `-sTCP:LISTEN` también mata al EMULADOR,
  que tiene conexiones abiertas a ese puerto; un `pkill -f` con el patrón del comando se mata a sí
  mismo).

**Sesión 2026-08-01/02 (3ª): deuda técnica — rápidos + medianos** ✅ (verificado en
emulador + curl/psql; instalado en teléfono): (1) calendario de campeonato → circuito y
**logos de campeonato en la app** (`RemoteImageBox`, base de RemoteAvatar); (2) borrar
foto de bitácora/campeonato/evento **limpia sus blobs** de `images` (evento incluye
chatimg/chatmedia; circuitos soft-delete los conservan a propósito); (3) **recordatorios
locales programados** (`Reminders.kt`: AlarmManager + BootReceiver + re-programación tras
sync; canal "recordatorios"; REMINDER de agenda con hora y "Recordarme antes del cierre"
de convocatorias; el tap navega al detalle — `EXTRA_AGENDA_ID`); (4) **outbox para
agenda/fotos/chat**: red primero, offline aplica local y encola (ids temporales
`local-…` + mapa persistido, coalescing de creates pendientes, fotos base64, eco local
en chat; drenaje FIFO que descarta rechazos reales del server para no atorar la cola;
reconexión → refresh() → drain); (5) **la app dibuja `Trazado.path`** (transformación
cuadrada compartida silueta/pins, fallback provisional); (6) **"+ Agregar oficial"** en
compartir ubicación: `GET /officers/search` privacy-first (nombre/OMDAI ID; correo SOLO
igualdad exacta y nunca devuelto) + buscador inline + allowlist local persistida. Ojo:
el trazado "Gran Premio" (AHR) quedó con un dibujo APROXIMADO de prueba.
Cierre de la sesión (todo instalado en el teléfono vía ngrok): (7) **Configuración sin
opciones inertes** — "Descargar mis datos" y "Dar de baja mi cuenta" se QUITARON de la
UI (decisión del usuario: no mostrar lo que no funciona) y viven como features en
`docs/IDEAS.md` (export ZIP privacy-first; baja = decidir borrado vs. anonimización
antes de codear). (8) **Fix "Mutuo" en Compartir ubicación**: el chip se DERIVA de la
allowlist real (solo si también le compartes — entonces aparece arriba con su ✕);
unidireccional muestra "Comparte su ubicación contigo" + atajo "Compartir también".
(9) `docs/IDEAS.md` ganó **"Compartir ubicación REAL"** (hoy todo es fachada local sin
GPS): allowlist en backend → foreground service de ubicación (puede SER la notificación
persistente del cronograma pendiente) → última-posición-only por el WS con guard en
servidor → pins sobre el mapa del trazado (la proyección lat/lon→x/y ya existe);
~3 sesiones. Dos de los medianos (5 y 6) los implementaron subagentes en paralelo.

**Sesión 2026-08-02 (4ª): navegación conectada + invitar a chats** ✅ (verificado por
curl y en emulador; **pendiente instalar en el teléfono** — no estaba conectado ni había
ngrok): (1) **Logos de circuitos en la app** (el admin/MCP ya subían el kind `circuit`):
catálogo con fallback al nº de configuraciones y header del detalle, por convención
`/images/circuit/{id}` sin campo en el modelo. (2) **Convocatoria → circuito**:
`Convocatoria.circuitId` (`@ProtoNumber(11)`, opcional, select en admin web, validado
contra el catálogo vivo) → fila "Circuito ›" en el detalle que navega. (3) **Agenda ↔
convocatoria**: `TripItem.convocatoriaId` (`@ProtoNumber(8)`) y
`AgendaEntry.convocatoriaId` (`@ProtoNumber(10)`); el editor de planeación liga a evento
O convocatoria (excluyentes: elegir uno limpia el otro), el detalle muestra
"Convocatoria vinculada" que navega (también las entradas globales CONVOCATORIA, con
select en el admin). (4) **Invitar oficiales a chats PÚBLICOS**
(`POST /chats/{id}/members`, `AddChatMemberRequest`): decisiones — solo públicos
(evento/puesto derivan de asignaciones) y el invitado **entra directo** (libre unión:
invitar es atajo, no permiso); guards (miembro invita, oficial existe, no repetido, no
archivado), **mensaje de sistema** "X agregó a Y", conteo/caché actualizados; UI
"+ Invitar oficial" en Detalles del chat con el buscador privacy-first de
`/officers/search`. Columnas nuevas migran solas (`createMissingTablesAndColumns` ahora
incluye Convocatorias/AgendaEntries/TripItems). Ojo datos de dev: la Copa Nissan · F5
quedó ligada al circuito HR (Querétaro se borró físico antes) y el circuito HR tiene un
logo "HR" de prueba.

**Sesión 2026-08-02 (4ª, continuación): más deuda saldada** ✅ (verificado en emulador +
curl + arnés WebDriver headless; **pendiente instalar en el teléfono**): (1) **FAB de
bitácora sobre los overlays** con lista negra (`bitacoraFabAllowed()`: fuera Modo
evento/chat/visor/editores; sin bottom bar baja a 28dp). (2) **La app muestra `mapUrl`
del trazado**: fallback path dibujado → imagen (área cuadrada compartida, caché
offline) → cinta por puestos. (3) **MbM acarrea ids** al guardar (backend ya
preservaba; era el único bulk sin `carry`). (4) **Editor OSM**: búsqueda Nominatim con
sugerencias (debounce 600ms, política ≤1 req/s) y aviso de tiles offline que se quita
solo; el zoom al cursor ya existía. (5) **`GET /admin/openapi.json`**: spec OpenAPI
3.0.3 en `admin-openapi.json` (69 ops/45 paths/46 schemas, autorado por subagente) —
**mantener a mano al cambiar la API**, como admin-api.md. Ojo datos de dev: el trazado
"Alterno" (AHR) quedó con una imagen de mapa de prueba.

**Sesión 2026-08-02 (5ª): moderación + subir_imagen URL + trazadoIds + login por correo
REAL** ✅ (emulador local por decisión del usuario; curl + arnés WebDriver + pantallas;
**el flujo de correo lo validó el usuario end-to-end** — el teléfono sigue pendiente):
- **Moderación de chats** (deuda saldada): long-press en una burbuja (texto o foto) →
  sheet **"Reportar mensaje"** (motivo opcional; `POST /chats/{id}/messages/{mid}/report`,
  rechazos como `Ack(ok=false)` — propio/sistema/duplicado; confirmación verde en la
  conversación) y **"Silenciar chat"** (decisión del usuario: silencia las
  NOTIFICACIONES del CHAT completo, solo local — `muted_chats` en SettingsStore, filtro
  en el colector global de AppNav, tag "Silenciado" + punto gris en el hub; reversible
  desde el mismo menú). Backend: tabla `message_reports` (única por mensaje+reportero),
  **scope admin nuevo `moderation`**, `GET/DELETE /admin/reports` (contexto resuelto:
  chat/mensaje/autor/reportero; descartar auditado + dryRun), sección **"Moderación"**
  en el admin web y tools MCP `listar_reportes`/`descartar_reporte`. De pilón:
  `GET /chats/{id}/messages` ganó guard `canReadChat` (públicos = libre lectura;
  evento/puesto = canSeeChat). Verificado: reporte desde emulador → admin web
  (descartar) → auditoría; silenciado NO notifica y el chat del evento sí.
- **`subir_imagen` (MCP) por URL**: acepta `archivo` O `url` http(s) (descarga con
  java.net.http; exactamente uno de los dos). Verificado por JSON-RPC.
- **La app consume `Event.trazadoIds`**: el tab Puesto muestra **"Mapa del trazado"**
  bajo Tu asignación (silueta path → mapUrl → cinta; pins de puestos/activos; el puesto
  PROPIO resaltado ámbar + halo) y **selector de trazado** cuando el evento tiene más de
  uno (el primero de `trazadoIds` = principal). `TrackMap`/`TrazadoSelector`/`MapItem`/
  `Kind` de CircuitScreens pasaron a `internal` (reuso); TrackMap ganó `hint` opcional.
- **El nombre visible del puesto es su label del trazado** (decisión del usuario, misma
  sesión): `Assignment.puestoLabel` (`@ProtoNumber(9)`, **derivado por el backend AL
  LEER** desde `Puesto.label` — `puestoDisplayNames()`, fallback "P N"; no se captura al
  escribir). Lo usan: chip del Home (antes "Puesto 1"), Tu asignación del Modo evento
  (antes "P1"), contexto del chat `senderPuesto` (burbujas, participantes, admin web),
  filas de cobertura del detalle de evento web y las listas del mapa (app: `Puesto.label`
  directo). Los **pins circulares siguen mostrando solo el número** (no cabe "MP 1").
  Verificado en emulador (Home/Modo evento) y por curl (senderPuesto = "MP 1").
- **Login por correo REAL, validado por el usuario**: credenciales SMTP en `./setenv`
  (fish, gitignored — se sourcea en el shell del backend; ojo: define también JAVA_HOME
  y `PUBLIC_BASE_URL`). La app perdió el acceso demo (botón devLink fuera); el token del
  enlace entra por `MainActivity.pendingAuthToken` (frío Y con la app abierta). Correo
  HTML tema Paddock + **página puente `GET /auth/open?token=…`** (los clientes de correo
  quitan los links `elpuesto://`; el botón usa `PUBLIC_BASE_URL` — localhost en dev,
  ngrok en teléfono). Cuentas reales de prueba: dos del autor
  (ambas ACTIVE → un mismo oficial).
- Ojo datos de dev: el logo del circuito HR quedó con un **degradado rojo-amarillo de
  prueba** (lo pisó la verificación de subir_imagen por URL).

**Sesión 2026-08-02 (6ª): asignaciones a ACTIVOS de pista — tripulaciones** ✅ (idea de
IDEAS.md completada y borrada de ahí; verificado por curl + arnés WebDriver + emulador):
- **Posición = puesto O activo**: `Assignment.puestoId` acepta el id de un activo del
  trazado (wire sin cambio; `puestoNumber` = 0 en activos). Backend:
  `AdminRepository.eventPositions()` (antes eventPuestos) valida contra puestos+activos
  vivos; `puestoDisplayNames()` resuelve labels de ambas tablas ("MP 1"/"TH1"); roles
  nuevos **Jefe IFRT / Operador IFRT / Operador HIAB** (decisión del usuario: roles
  específicos por unidad; el FM del IFRT usa "Bombero 1"). **`CHIEF_ROLES`** =
  {Chief Post Marshal, Jefe Telehandler, Jefe IFRT}: guard "máx. 1 rol-jefe por
  posición" (mensaje con labels), `mates.isChief` (chip CMP) y `chiefViewContext`
  (emergencia) usan el set. Derivaciones (compañeros/chat de puesto/checklist) ya
  keyaban por posición — sin cambio.
- **Admin web**: combo "Posición" del editor de asignaciones agrupa Puestos/Activos
  (populateSelect ganó optgroups vía opción `{g}`); detalle de evento: pins de activos
  tripulados en ámbar con contador y clickeables → panel "Activo seleccionado" con la
  crew; cobertura con KPI "activos tripulados" (33/42 en GP CDMX) y lista que incluye
  activos ("sin jefe" sigue contando SOLO puestos — un HIAB de un operador no tiene
  rol-jefe). **App**: el mapa del tab Puesto resalta el activo propio (ámbar + halo;
  `kindColors` generalizado: assignedBefore pinta ámbar cualquier kind); "Tu asignación"
  ya mostraba puestoLabel. MCP + admin-api.md + admin-openapi.json actualizados.
- **Tripulaciones GP CDMX importadas**: 58 asignaciones nuevas (137→195) desde el
  roster (~/Dropbox/omdai/roster-parse-gpcdmx-2025.md): TH jefe/operador (TH con una
  sola persona = Operador; la crew "TH5/8" quedó en TH5, TH8 sin crew), IFRT
  jefe/FM→Bombero 1/operador con vacantes "—", 4 operadores HIAB → HIAB1..4. Script en
  scratchpad con dryRun + verificación (labels TH1/IFRT4 derivados OK).
- Ojo pruebas de emulador con SMTP activo (sin devLink): insertar un token directo en
  `magic_tokens` (psql) y abrir `elpuesto://auth?token=…` por adb — no manda correo.
  Cuentas reales en dev: las del autor → su oficial, asignado a un puesto del GP
  (restaurado tras probar TH9).
- **Checklist se limpia cada día** ✅ (idea de IDEAS.md completada y borrada; misma
  sesión): las marcas de `checklist_state` que no son de HOY (corte a **medianoche
  America/Mexico_City** — `ZONA_CHECKLIST`/`checklistHoyDesdeIso()`, comparación
  lexicográfica contra `marked_at` UTC) se **borran oportunistamente** en
  `purgeChecklistViejoTx(eventId)`, invocada dentro de la transacción por los TRES
  consumidores: `checklist()` (GET de la app), `checklistState()` (visor admin/MCP/pins
  verdes) y `setChecklistDoneTx` (el primer toggle del día escribe sobre tabla limpia).
  Si la purga borró algo, el caller emite `checklist` al bus FUERA de la transacción
  (los conectados refetchean y amanecen limpios; sin bucle: la segunda purga borra 0).
  Sin cron, sin cambio de wire ni de lógica en app.js. UX: el tab Puesto titula
  **"Checklist de hoy · <d MMM>"** (fecha CDMX) y el panel web "Checklist de HOY" +
  leyenda "Checklist completa hoy". Verificado por psql/curl: frontera exacta
  (05:59:59Z se purga, 06:00:01Z sobrevive con CDMX=UTC-6), purga física confirmada,
  GET personalizado con Bearer y toggle que marca con el Instant de hoy; funciona igual
  con posiciones que son ACTIVOS. El GP de dev quedó SIN plantilla de checklist (la de
  la prueba se retiró).

**Sesión 2026-08-02 (7ª): pase de lista del CMP — asistencia por día** ✅ (idea de
IDEAS.md completada y borrada; verificado por curl + psql + arnés WebDriver + emulador
con dos cuentas; **pendiente instalar en el teléfono**):
- **Decisiones de producto** (confirmadas con el usuario): la asistencia es **REGISTRO
  HISTÓRICO** (a diferencia del checklist NUNCA se purga); el oficial no-jefe **ve su
  propia marca read-only** (transparencia); el jefe marca **solo HOY** (CDMX — días
  pasados inmutables, corrección excepcional vía admin/psql); estados = **sin marcar /
  presente / ausente** (sin fila = sin marcar ≠ ausente); **outbox desde v1**.
- **Backend**: tabla `attendance(event_id, day, officer_id, present, marked_by,
  marked_at, puesto_id)` PK (event,day,officer) — `puesto_id` es **snapshot al marcar**
  (reacomodos posteriores de asignaciones no reescriben el registro). Shared:
  `AttendanceEntry` / `SetAttendanceRequest` (present null = desmarcar; el server
  siempre marca HOY — no se confía en el reloj del cliente). `GET /events/{id}/
  attendance[?day=]` personalizado (jefe por rol de `CHIEF_ROLES` = filas de su
  posición; resto = solo la suya; sin asignación = vacío) y `POST` con guards calcados
  de emergencia (evento ACTIVO, viewer jefe, target de la MISMA posición; rechazos =
  `Ack(ok=false)` para que el outbox descarte). Emite kind **`attendance`** al bus
  (SSE evento y admin lo reenvían sin cambios). `hoyOperativo()` reusa
  `ZONA_CHECKLIST`.
- **Admin**: `GET /admin/events/{id}/attendance[?day=]` (histórico completo); panel
  "Asistencia (pase de lista)" en el detalle de evento web (chips por día — default
  HOY, agrupado por posición vía labels de puestos+activos de los trazados, ✓ verde /
  ✕ rojo con tooltip quién/cuándo, refetch por el SSE) — CSS nuevo `.bx.no`. Tool MCP
  **`ver_asistencia`** (`evento_id`, `dia?`). admin-api.md y admin-openapi.json al día.
- **App**: `attendance()`/`setAttendance()` en el seam; caché `catalog` con clave POR
  DÍA (`attendance:{eventId}:{yyyy-mm-dd}` CDMX — la de ayer nunca se muestra como
  hoy); outbox kind `attendance` con **coalescing por oficial** (refId
  `eventId|officerId`, la última orden gana) y **overlay de pendientes al LEER** (la
  caché guarda la verdad del server; un fetch nunca des-marca lo encolado). Tab
  Puesto: sección **"Pase de lista · HOY <fecha>"** solo para el jefe (tri-estado
  ✓ verde/✕ rojo/neutro, re-tocar desmarca, contador "N/M presentes") y
  **"Asistencia de hoy"** read-only para el resto; recarga con el SSE del evento y
  pull-to-refresh. Verificado en emulador: marcar/desmarcar, vista read-only, y ciclo
  **offline → marca local + "1 sin enviar" → revivir backend → drenaje solo**.
- Ojo datos de dev: cuenta de prueba **jefe66@ejemplo.mx** (ACTIVE → **Rodolfo Rule**,
  CMP de la posición 6.6 del GP, donde está Carlos) — útil para probar la vista del
  jefe; el GP quedó con asistencia de prueba del 2026-08-01 (insertada por psql) y
  del 2026-08-02 (4/4 marcadas por Rodolfo). El emulador tiene un override local de
  perfil viejo que muestra el nombre del autor como display name de cualquier cuenta
  (comportamiento provisional documentado de `ProfileStore`).

**Sesión 2026-09-24: máquina nueva + compartir ubicación REAL (3 fases)** ✅ (verificado
por curl + WebSocket + emulador con dos cuentas; **pendiente instalar y validar en el
teléfono**):
- **Entorno**: esta máquina se montó desde cero (JDK/Gradle/emulador — ver §5). El backend
  ganó `PORT` por env (el 8080 lo ocupaba otro proyecto local) y el keystore de debug
  quedó versionado en `androidApp/debug.keystore` (ojo: el teléfono tiene un APK firmado
  con la llave de la máquina anterior → **desinstalar una vez** antes del primer
  `install -r` con la nueva).
- **Fase 1 — allowlist en backend** (reemplaza la fachada local de SettingsStore/semilla):
  tablas `location_settings`/`location_shares`/`location_hidden`;
  `GET/PUT /me/location-sharing`, `POST/DELETE /me/location-shares/{id}` y
  `/me/location-hidden/{id}` (éxito = `LocationSharing`, rechazo = 4xx). App: red →
  caché `locsharing` + outbox `loc-enabled`/`loc-share`/`loc-hidden` (coalescing por
  destino, superpuesto al LEER como el pase de lista). Aviso al receptor
  ("Te comparten su ubicación", canal avisos → abre Compartir ubicación, extra
  `openLocation`); en "Te comparten": Ocultar/Mostrar y "Compartir también".
- **Fase 2 — GPS**: `LocationShareService` (FGS tipo location, FusedLocationProvider,
  ~15 s, notificación fija con **Pausar** que vale para ESE evento) + permiso
  "mientras se usa la app" al encender (sin ACCESS_BACKGROUND_LOCATION) + sugerencia de
  excluir del ahorro de batería. `LocationSharingController.sync()` arranca/detiene con la
  app en PRIMER PLANO (al abrir/volver, al cambiar el evento activo — "sin evento" se
  confirma 3 s por transitorios de la caché — y al tocar la config). `POST /me/location`
  solo acepta con interruptor + asignación al evento activo (si no, Ack ok=false → el
  servicio se detiene); `DELETE /me/location` al pausar/apagar/cerrar sesión.
  **`AppGraph`** = UNA instancia de HttpRepository/OfflineRepository por proceso
  (UI y servicio comparten el cliente: dos renovando romperían la rotación del refresh).
- **Fase 3 — reparto + mapa**: `LocationHub` (última posición en MEMORIA, TTL 10 min,
  barrido cada 30 s y ante `admin:event`); el `WS /stream` manda kind `location` con la
  posición en el frame, filtrado en memoria por `visibleSenders` (me comparte + encendido +
  mismo evento activo + no oculto; se recalcula ante cambios de allowlist/config/admin y
  cada 60 s); `GET /events/{id}/locations` = foto inicial. Los SSE de evento/admin
  EXCLUYEN `location*` (Control no ve ubicaciones). `Trazado.geoFrame` (`@ProtoNumber(9)`,
  `GeoFrame` en shared = la ÚNICA implementación de la proyección Web Mercator; el admin
  la reusa) → la app proyecta lat/lon vivas: pins circulares azules con iniciales (el
  propio "TÚ" en ámbar), atenuados >2 min, y sección **"Ubicaciones"** en el tab Puesto
  (estado Compartiendo/En pausa/Falta permiso + lista "hace N s · ±m" / "Fuera del
  circuito").
- Ojo datos de dev: la DB de esta máquina es NUEVA (semilla); el trazado "Gran Premio"
  (AHR) tiene un **dibujo geo aproximado de prueba** (necesario para los pins) y sus
  puestos de semilla NO tienen lat/lon (sus píldoras no están alineadas con la silueta).
  Laura↔Carlos se comparten mutuamente. Probar GPS en emulador: `adb emu geo fix <lon> <lat>`.

**Sesión 2026-09-24 (continuación): eventos en común + historial DERIVADO** ✅
(verificado por curl y en emulador; pendiente el teléfono junto con lo de ubicación).
Decisiones del usuario: derivar y eliminar `officer_history`; stats del perfil ajeno =
**Eventos · Juntos · Activo desde**; detalle "Ustedes dos + circuito"; **5 recientes +
"Ver todo (N)"** agrupado por año.
- **Backend**: historial = asignaciones de eventos **TERMINADOS por fecha y no activos**
  (`pastAssignmentsTx`; posición por `puestoDisplayNames`, circuito aunque esté archivado).
  `GET /officers/{id}/history` SOLO para uno mismo (403 si no — antes daba el historial
  completo de cualquiera); nuevo `GET /officers/{id}/common-events` (visor del JWT: mi
  rol/posición + `otherRole`/`otherPosition` + `samePosition`). `OfficerStats.events`/
  `thisSeason` se **derivan** al leer el perfil (`officer(id, viewerId)`) y `together`
  (`@ProtoNumber(4)`) solo en perfil ajeno; `activeSince` sigue capturado. La tabla
  `officer_history` se DROPEA al arrancar. `OfficerHistoryEntry` ganó `endsOn`,
  `circuitId`, `positionLabel`, `otherRole`, `otherPosition`, `samePosition` (6..11).
  Admin/MCP/OpenAPI: stats.events/thisSeason documentados como derivados (se ignoran).
- **App**: caché `history:{id}` (propio) / `common:{id}` (ajeno); fila con "Tú MP 7 ·
  Laura MP 7" + marca MISMO PUESTO; `HistoryListScreen` ("Ver todo", por año);
  `PastEventDetailScreen` con rango de fechas, bloque "Ustedes dos" y fila "Circuito ›".
- Semilla dev: 7 **eventos pasados** en AHR (`SeedData.pastEvents`/`pastAssignments`,
  sembrados idempotentes por id con `seedDemo`): Carlos 6 (3 en 2026), Laura 4; juntos 3
  (2 en el mismo puesto). El evento activo (SC4) NO cuenta como historial.
- ~~Idea abierta: derivar "Asignado antes"~~ **HECHO 2026-09-25 (6ª)**.

**Sesión 2026-09-24 (3ª): datos reales para producción — calendarios 2026** ✅ (verificado
por curl + cargador + emulador; **pendiente instalar en el teléfono**). Decisiones del
usuario: borrar TODO dato de prueba; datos reales versionados en CSV auditables; sedes
internacionales **en el catálogo de circuitos**; categorías **principal + series soporte**;
Fórmula E **ambas temporadas** (2025-26 y la provisional 2026-27); datos personales en
`data/privado/` **fuera de git**.
- **Datos** (`data/`): 78 circuitos (26 de F1 vía Jolpica, 31 de NASCAR, los mexicanos,
  IndyCar/WEC/FE) y **279 fechas** en 7 campeonatos / 17 categorías — F1 (+F2, F3, F1
  Academy), Fórmula E 2025-26 y 2026-27, IndyCar (+Indy NXT), NASCAR (Cup, O'Reilly,
  Truck), WEC (Hypercar, LMGT3), WRC (+WRC2, WRC3, Junior WRC). Cada fila con fuente;
  cancelaciones por el conflicto en Medio Oriente documentadas en `FUENTES.md` (F1
  Bahréin→Sepang y Arabia fuera, WEC Qatar/Bahréin → Barcelona/Monza, WRC Arabia fuera).
  Investigado con 5 subagentes en paralelo + APIs (Jolpica, feed de NASCAR). **Re-verificar**:
  Catar/Abu Dhabi (F1 decide a mediados de octubre), FE 2026-27 (Jeddah), ratificación WRC.
- **Cero datos de prueba**: SeedData eliminado (shared/backend/app); el backend arranca
  vacío; la app sin red cae a caché o vacío. DB de dev reseteada (`down -v`) y cargada.
- **Modelo**: `Round.name/location/startDate` (7-9; status derivado AL LEER), `Circuit.
  country/trazadoCount` (4-5). Agenda: fechas de campeonato agrupadas por fin de semana
  (126 entradas en vez de 279). App: calendario primero con auto-scroll a la fecha
  actual, chips de categoría, insignias por siglas; catálogo de circuitos México primero.
- Ideas anotadas: **seguir campeonatos** (hoy la agenda trae TODOS a todos) y posiciones
  por ingesta automática (Jolpica para F1). Ojo: la cuenta del usuario usa un OMDAI provisional y
  "activo desde" vacío — valores provisionales a corregir en `data/privado/cuentas.csv`.

**Sesión 2026-09-24 (4ª): GP Ciudad de México 2026 real** ✅ (API + cargador; pendiente
que el usuario acomode posiciones y exportarlas):
- El usuario **pintó el trazado** "Gran Premio" de AHR (74 vértices, 4,315 m vs 4,304
  oficial). Yo creé las **90 posiciones** (`data/circuitos/posiciones/rodriguez/gran-premio.csv`, hoy en `data/privado/`):
  39 puestos **pre-ubicados georreferenciando el plano oficial** "Marshal Posts" (pdftotext
  -bbox de las etiquetas + ajuste de similitud ICP contra el trazado dibujado; ojo: el PDF
  parte "T13" en "T1"+"3"), la posición **sin mapa** "Coordinación de zona" y 50 activos
  formados junto a la recta (TH1-19, IFRT1-12, HIAB1-5, FB1-4 como HIAB, DR1-10) para que
  el usuario los arrastre. Flujo: acomodar en el editor → `--exportar-posiciones` → commit.
- **Roster** (`Track Personnel V1 2026.xlsx` de OMDAI) → `scripts/importar-roster.py` →
  `data/privado/eventos/2026/gp-mexico/roster.csv` (368 asignaciones con OMDAI; externos
  sin número ignorados por decisión del usuario). Mapeo versionado en
  `data/eventos/roles-omdai.csv` — confirmado por el usuario: **TSP = Track Safety
  Personnel**, **INT 6/7 = Bombero 1/2**, **DR = Driver Rider** (moto de traslado de
  pilotos, `AssetType.DRIVER_RIDER`), **FB = Flat Bed** (tipo HIAB). Evento
  "Gran Premio de la Ciudad de México 2026" (30 oct–1 nov, F1) cargado con 367 oficiales
  nuevos + el usuario (con su OMDAI real y su puesto en el roster).
- Modelo: `Puesto.onMap` (9) para posiciones sin mapa; roles nuevos Track Safety
  Personnel / Driver Rider / Coordinador de zona. Avisos del Excel: ID "1" en TSP de 11.2
  (esa persona no se cargó) y un OMDAI sin nombre en DR2 (se carga como "Oficial <número>").

**Sesión 2026-09-25: zoom de mapa, Explorar y agenda "Tu fin de semana"** ✅ (verificado
por curl + emulador; **sin APK nuevo** — el usuario lo pide al terminar):
- **Mapa del circuito con zoom** (`TrackMap`): pellizcar/arrastrar, doble toque 2.5x↔1x,
  botones +/−/ver completo, "Ubicar" acerca y centra; a zoom 1 un dedo no se consume.
- **Explorar del Home = tarjetas con motivo** (opción A de Claude Design): silueta de
  trazado / bandera a cuadros + conteo real del catálogo.
- **Carreras con id ESTABLE**: `Round.id` (`@ProtoNumber(10)`, columna `rounds.id`
  rellenada al arrancar); `replaceRounds` conserva ids por id del body → misma sede +
  nombre → misma sede y fecha a ≤45 días (verificado: reprogramar/renombrar/renumerar
  conserva; recargar `data/` deja los 279 idénticos). Carrera que desaparece = la
  planeación ligada queda sin vínculo. Admin web acarrea el id; docs/OpenAPI/MCP al día.
- **Planeación**: `TripItem.roundId` (9) y `endsAt` (10); reglas en `tripItemProblem`
  (un solo vínculo, carrera existente, fin ≥ inicio). La bitácora de un evento incluye lo
  ligado a sus carreras fundidas (`eventRoundIdsTx`).
- **Agenda con contexto** (`AgendaEntry` 11–20 + `AgendaRace`/`AgendaAssignment`): fines
  de semana `rnd-<carreraPrincipal>` con campeonato, circuito, rango y carreras
  (categoría, fecha N de M); eventos donde trabajas `evt-<id>` con resumen de asignación
  (puesto, rol, turno, compañeros), fundidos con su fin de semana (el F1 México desaparece
  como carrera suelta).
- **App — detalle C "Tu fin de semana"** (Claude Design, artifact `HH9qoPJgXEExXwTwCWH5kH`;
  copia en `docs/design/Agenda - detalle C/`): barra superior = tipo (EVENTO/CARRERA/
  TRANSPORTE…), encabezado con serie + fecha N de M (silueta del trazado si hay),
  tira de días (viaje/regreso ±3 días, categoría por día), asignación, línea de tiempo
  por día (carreras + planeación + notas/fotos; estancia del hospedaje y su salida) y
  Nota/Foto/Planear ligados a la entrada. La planeación personal se ve desplegada dentro
  de su fin de semana (Mapas, editar, eliminar con confirmación). El detalle relee la
  agenda viva (ya no se cierra tras editar). Editor: vínculo a evento/carrera (buscador)/
  convocatoria + llegada/salida. Listas: etiqueta "Trabajas" / serie (F1, NASCAR…) / tipo.
  Íconos de línea propios (`ui/components/LineIcon.kt`, trazos SVG vía `PathParser`).

**Sesión 2026-09-25 (2ª): Campeonato ≠ Temporada** ✅ (verificado por curl + JSON-RPC
del MCP + cargador + emulador): entidad nueva `Series` (API `/admin/series`, tabla
`series`) = CAMPEONATO (nombre único sin año + logo); la temporada sigue siendo
`Championship` en el API (ids intactos) con `seriesId`/`seasonLabel` (5/6) y
`startsOn`/`endsOn` derivados del calendario (7/8); `name`/`emblemUrl` se derivan del
campeonato. Migración al arrancar partió "Fórmula E 2025-26" → Fórmula E + 2025-26 y
movió los logos a kind `series` (respaldo previo en el scratchpad de la sesión). Admin
web: Campeonatos → Temporadas → Categorías; MCP: `*_campeonato` = serie + nuevas
`listar/guardar/borrar_temporada`, `subir_imagen tipo=series`; CSV: `campeonato` sin año
y `temporada` = etiqueta. App: una tarjeta por campeonato y selector de temporada en el
detalle (en curso → próxima → última). Las tarjetas de agenda (Home y Agenda) muestran el **logo del
campeonato** en vez del chip con su nombre (`AgendaEntry.championshipEmblemUrl`, 21; sin
logo = el texto de siempre; "Trabajas" no cambia).

**Sesión 2026-09-25 (3ª): trazados y logos de los 78 circuitos** ✅ (cargados en la DB de
dev y verificados en emulador): `data/circuitos/dibujos/<circuito>/<trazado>.geojson` +
`logos.csv`/`logos/` con soporte en el cargador (dibujo solo si cambió; logo solo si el
circuito no tiene — no pisa uno subido a mano; `--exportar-dibujos`, `--recargar-logos`).
85 trazados (79 con dibujo, de bacinger/f1-circuits MIT u OSM ODbL), 60 logos; investigado
con 6 subagentes. Faltantes en `data/circuitos/PENDIENTES.md`. La app muestra
"© OpenStreetMap" en el mapa (ODbL). Nombres de campeonato de los CSV alineados con el
admin (F1, FE, WEC, WRC). **Ojo**: el cargador completo re-escribe el oficial del usuario
desde `data/privado/cuentas.csv` — su nombre completo correcto
(confirmado por el usuario; el sistema tenía uno abreviado y se corrigió).
Además: **chats de puesto reales** — uno por posición (puesto o activo tripulado) con
asignaciones (`ChatsT.puestoId`; `ensurePuestoChats` al reemplazar asignaciones y al
arrancar); solo los ven/leen/escriben los asignados a ESA posición con el evento activo
(el de evento, solo los asignados). Probado en una copia de la DB (`CREATE DATABASE …
TEMPLATE el_puesto` + backend en :8082) para no activar eventos en la DB viva: activar
un evento notifica a TODOS los teléfonos conectados. Tarjetas de planeación con ícono y
editor con "Salida"/"Entrada".

**Catálogos rediseñados (opción A de Claude Design, canvas
`KX9pKJSD1o7euApRWKBA1K`; copia en `docs/design/Catalogos A/`)**: Circuitos = galería de
siluetas en 2 columnas (trazado principal protagonista, logo en la esquina, km/curvas,
"TRABAJAS" o próxima carrera ≤40 días desde la agenda, filtro por región). Campeonatos =
tarjeta por temporada vigente (estado, avance fecha a fecha, próxima fecha con la silueta
de su circuito, categorías; en curso primero). Datos DERIVADOS al leer para no pedir de a
uno: `Circuit.mainTrazado` (6, solo en `/circuits` de la app) y `Championship.roundsTotal/
roundsDone/nextRound/categoryNames` (9–12, de la categoría principal). `TrackSilhouette`
en `ui/components` dibuja cualquier `Trazado.path`.

**Cierre de la sesión 2026-09-25** (todo en GitHub; APK en `dist/` y `/descargas`):
- **Fixes**: el evento activo no aparecía en el Home (decodificar `Assignment?` nullable
  en la raíz truena en ProtoBuf y tumbaba el refresh ENTERO → usar tipo explícito no
  nulo); fotos de cámara giradas 90° (orientación EXIF perdida al re-comprimir →
  `ui/components/ImageOrientation.kt`, aplicado en bitácora y chat).
- **Estado de dev al cerrar**: el **GP de México sigue ACTIVO** (el usuario probaba los
  chats de puesto); su foto de bitácora "aquí programando" quedó girada (subida antes
  del fix — borrarla y retomarla, o girarla en el servidor).
- **Pendiente**: validar en el teléfono la foto con el APK nuevo; dibujos de circuitos
  por trazar a mano y logos faltantes (`data/circuitos/PENDIENTES.md`).

**Sesión 2026-09-25 (4ª): pendientes del teléfono + posiciones automáticas** ✅ (emulador +
curl + JSON-RPC del MCP + capturas de la admin web por CDP; APK nuevo en `/descargas`):
- **Modo evento**: empty state del **MbM** ("Aún no hay MbM · se actualiza en vivo") y en el
  tab Puesto **Ubicaciones y Asistencia al fondo** (la asistencia al último).
- **"Sin conexión" en vez de colgarse** (convención nueva, ver §6): `data/OfflineMiss.kt`
  (`trackMiss` — el repo marca en el contexto de la corrutina cuando una lectura no tuvo
  red NI caché) + `ui/components/Unavailable.kt` (`UnavailableScreen` pantalla completa,
  `UnavailableInline` por sección, `rememberReloader` recarga sola al volver la señal).
  Aplicado a perfil, historial, accesos, circuitos, campeonatos, convocatorias, chat y
  visor. `online` = red de Android (ConnectivityManager) Y backend; sin red las lecturas
  ni lo intentan, con red y backend caído, 1.5 s. `authedGet` ya no marca offline por un
  cuerpo que no decodifica (403/404).
- **Fix**: botones de zoom del mapa no respondían con el mapa acercado (el gesto hacía
  snapTo y cancelaba la animación del botón).
- **Posiciones automáticas**: `backend/StandingsIngest.kt` — job cada 15 min
  (`STANDINGS_INGEST_EVERY_MIN`, 0 = apagado) que por categoría persigue la última fecha
  terminada (reintentos 1 h/6 h/diario + pasada de confirmación ~3 días después), fuentes
  enchufables (`StandingsSource`); hoy solo **`jolpica-f1`** (F1, CC BY-NC-SA → la app
  muestra el crédito). Estado/config en `standings_ingest`; admin `/admin/standings-ingest`
  + `/admin/categories/{id}/standings-ingest[/run]`; admin web "Posiciones auto"; MCP
  ver/configurar/quitar/correr_ingesta_posiciones; versionado en
  `data/campeonatos/fuentes-posiciones.csv`. **Modelo**: el piloto se identifica por `ref`
  y el número es TEXTO (`numberText`: "007", "00"), porque en todas las series se
  comparte o cambia; posiciones por `driverRef`. `Category.standingsCredit/ThroughRound`
  derivados. Las otras 16 categorías: fuentes investigadas en `docs/fuentes-posiciones/`
  y resumidas en `docs/IDEAS.md` — **bloqueadas por decisión legal del usuario** (APIs
  internas cuyos términos prohíben la extracción automatizada).
- Arnés de la admin web sin chromedriver: `node cdp.mjs` sobre el Chromium headless de
  Playwright (`~/.cache/ms-playwright/chromium_headless_shell-*/`), CDP por el WebSocket
  nativo de Node 24 (script en el scratchpad de la sesión; recrear si hace falta).

**Sesión 2026-09-25 (5ª): tema 1 (offline) + empty states** ✅ (emulador: edición de
perfil/emergencia sin red → llegan solas al volver la señal; cerrar sesión con cambio
pendiente → aviso; tras cerrar sesión la DB local queda en 0 filas; re-login por token):
- **Regla de producto (tema 1)**: lo que el oficial produce en pista funciona SIN señal
  (checklist, asistencia, bitácora/planeación, mensajes, **perfil y emergencia** — ahora por
  outbox, gana la última); lo administrativo/social (crear/unirse/salir/archivar chats,
  imagen de chat, invitar, reportar, buscar oficiales, foto de perfil, consultar emergencia
  como jefe) exige red y **se desactiva con la razón a la vista** (`NeedsConnectionNote`).
- `ProfileStore` ya NO guarda override de perfil/emergencia (solo `onboardingDone`); el
  bug "nombre de otra cuenta" desaparece. **Cerrar sesión borra caché + cola**
  (`clearLocalData`) y avisa si hay cambios sin enviar.
- **Posiciones**: las 16 categorías sin F1 quedan DESCARTADAS (nice-to-have); la pestaña
  Posiciones solo aparece si la categoría tiene tabla. **Compartir bitácora**: en espera del
  feedback de los usuarios (`docs/IDEAS.md`).
- `EmptyState` (componente) en todas las pantallas/pestañas que podían quedar vacías.

**Sesión 2026-09-25 (6ª): seguridad + notificaciones del MbM + "asignado antes"** ✅
(curl con IP simulada por X-Forwarded-For, JSON-RPC del MCP, emulador con el proceso muerto):
- **Seguridad** (ver §5 y DEUDA-TECNICA): freno de fuerza bruta en `/admin` y en el enlace
  mágico, **fuga corregida** (el devLink salía si el SMTP fallaba), no arranca expuesto con
  secretos débiles, CORS por `CORS_ORIGINS`, cabeceras defensivas, backend en loopback y
  Postgres en 127.0.0.1. La master key se ROTÓ (64 hex, en `setenv`): el admin web pide
  volver a pegarla. El MCP usa la clave nombrada `mcp-claude-code`.
- **Barra fija "Evento en curso"** (`LiveEventBar.kt`): la notificación persistente del
  cronograma que estaba pospuesta — actividad EN CURSO con cuenta regresiva + la que sigue,
  re-pintada por alarma (funciona con la app cerrada y tras reinicio). **"Cambios de
  cronograma"**: aviso "Cambió el MbM" (admin:sessions del evento asignado). Ambos abren el
  Modo evento en el MbM (`EXTRA_EVENT_PAGE`). Push con la app cerrada (FCM) sigue pendiente.
- **"Asignado antes" DERIVADO** del historial del visor (puestos y activos); la columna
  capturada se eliminó.

**Sesión 2026-09-25 (7ª): "Descargar mis datos"** ✅ (curl + emulador con el selector de
Android; decisiones del usuario: JSON en ZIP, 1 descarga al día, sin export desde el admin):
- `GET /me/export` (`backend/DataExport.kt`): ZIP con `LEEME.txt` + un JSON por tema
  (cuenta, perfil, emergencia, eventos, asistencia, bitácora, mensajes PROPIOS, chats,
  reportes hechos, ubicación, invitaciones con sus correos, accesos a tu emergencia,
  descargas anteriores) + `fotos/` (perfil, bitácora, chat). Nada ajeno: de los chats solo
  lo que envió (verificado con un mensaje ajeno de prueba). Una cada 24 h
  (`data_exports`; 429 con Retry-After), se registra SOLO si se completó, aparece en el
  **Registro de accesos** (`EmergencyAccess.kind = "export"`) y **avisa por correo**
  (`EmailSender.sendExportNotice`). `GET /me/export/status` = ¿se puede hoy?
- App: Configuración → Privacidad y datos → "Descargar mis datos": aviso (lleva la
  emergencia) → el oficial ELIGE dónde guardar (`CreateDocument`, nunca Descargas en
  automático) → streaming directo al archivo; si falla, el archivo a medias se borra.
  Exige conexión (acción administrativa).

**Sesión 2026-09-25 (8ª): ajustes del Modo evento + "Ubicaciones en vivo"** ✅ (emulador con
dos compañeros de prueba compartiendo y el GPS simulado; datos de prueba borrados):
- Modo evento: encabezado "EVENTO ACTIVO" en ámbar con punto verde pulsante (sin el chip EN
  CURSO), rol de la ASIGNACIÓN de cada compañero (`PuestoMate.role`), checklist oculto si el
  puesto no tiene, "La registra tu jefe de puesto". El rol "Bombero 1" viene de `INT 6` en el
  roster de OMDAI (mapeo `roles-omdai.csv`, confirmado de nuevo por el usuario).
- **Ubicaciones en vivo** (Claude Design, canvas `4X3rmApBfP7W8VK9yEiAnZ`; copia en
  `docs/design/Ubicaciones en vivo/`; elegido: **A + pantalla completa de B**):
  - Pines con la **foto de perfil** (aro azul = te comparte, ámbar + "TÚ" = tú), anclados por
    la punta, tocables, con "Nombre · hace N s" al seleccionarlos (`PersonPinView`).
  - Tab Puesto (A): "Tu asignación" compacta, chip "N en vivo · Encuadrar" sobre el mapa
    (`TrackMap.fitKey`), botón de pantalla completa (ícono propio `LineIcon.FULLSCREEN`: el
    ⤢ del zoom ya es "ver completo"), tira de compañeros bajo el mapa (`LiveStrip`) y tu
    estado (`OwnSharingRow`, "Compartes tu ubicación con N · Pausar"). La sección
    "Ubicaciones" del fondo desaparece.
  - **Mapa en vivo** (B, `ui/event/LiveMapScreen.kt`, `Overlay.LiveMap`): mapa a pantalla
    completa que abre encuadrando a todos (`fitInsetTop/Bottom` descuentan barra y hoja),
    hoja inferior (vistazo con fotos; arrastrar/tocar = lista con rol · posición, "Ubicar",
    "Fuera del circuito"), "Puestos ✓" para ocultar puestos.
  - Home: fila "Ana, Luis y Rosa te comparten su ubicación · Mapa ›" en la tarjeta del
    evento (`SharingWithYouRow`). El aviso "X te comparte" abre el mapa en vivo si hay
    evento activo (sin evento: Configuración).
  - Backend: `LivePosition.role/position` (8/9) derivados de la asignación en el evento.
  - **Validado por el usuario en su teléfono** con dos compañeros SIMULADOS (cuentas
    temporales `sim-ubic-N@ejemplo.invalid` ligadas a oficiales reales del GP, login por
    token en `magic_tokens`, `POST /me/location` cada 15 s: uno recorriendo `trazado_geo`,
    otro quieto en un puesto con temblor). Limpieza = `DELETE /me/location` + borrar sus
    filas de location_shares/settings, tokens y cuentas.

**v1.0.0 — cerrada 2026-09-25** (tag `v1.0.0` LOCAL, sin push por decisión del usuario;
notas en **`CHANGELOG.md`**; APK final en `dist/` y `/descargas`, firmado con la llave de
debug versionada). La versión vive SOLO en `androidApp/build.gradle.kts`
(`versionName`/`versionCode`): Configuración la lee de `BuildConfig.VERSION_NAME` y
`scripts/build-apk.sh` escribe `dist/VERSION`, que la página `/descargas` muestra. Siguiente
versión: subir ambos campos + sección nueva en CHANGELOG.md + tag.

**Sesión 2026-09-25 (9ª, post-v1): imágenes offline + chats privados** ✅ (curl + WebSocket con
tres cuentas + emulador; **pendiente instalar en el teléfono**):
- **Imágenes guardadas en el teléfono** (causa del parpadeo: `image()` iba a la red primero,
  hasta 1.5 s, y pintaba iniciales mientras tanto). `ImageStore` = archivos con ETag y
  marca 404, tope 150 MB por uso, migra solo los blobs `img:` que vivían en `catalog`;
  revalidación ≤ cada 10 min por imagen (y tras cada `refresh()`); fotos de chat/bitácora
  nunca (id único). **Precarga** tras sincronizar (avatar propio, compañeros, imagen del
  evento, logos de agenda/campeonatos/circuitos, imágenes de chats, avatares y últimas 30
  fotos de cada conversación abierta). Sin red y sin copia, sirve la otra variante.
  Backend: **ETag/304** en `/images/...` + **candados**: `trip` solo su dueño, `chatmedia`/
  `chatimg` solo quien lee ese chat (404 si no). **En vivo**: kind `image` por el WS
  (avatar, imagen de chat; `admin:image` ya existía) → la app revalida YA lo que tenga.
- **Fix offline**: abrir la app sin señal mandaba a la pantalla de acceso (`/me` sin
  respuesta = sesión inválida). Ahora `AuthStore.lastStatus` + `SessionCheck`: solo un
  401/403 real saca.
- **Chats privados + ligados a evento** (decisiones en §1): `ChatType.PRIVATE` (al FINAL del
  enum: ordinal = wire), tabla `chat_invites`, `Chat.eventName/invitedBy` (13/14),
  `ChatMember.pending` (4), `CreateChatRequest.isPrivate/eventId/inviteeIds` (3-5),
  `POST /chats/{id}/event` (`SetChatEventRequest`); join/leave de un privado =
  aceptar/rechazar/salir con mensaje de sistema; `GET /chats/{id}/members` ahora con guard
  (`canListMembers`: quien lee el chat + el invitado). Aviso **`chat-invite` SOLO al
  invitado** por el WS general (OJO: su rama `else` reenvía a TODOS cualquier kind nuevo —
  todo kind dirigido necesita su rama); los SSE de evento/admin excluyen `chat*` e `image`.
  Borrar un evento solo se lleva sus chats EVENT/PUESTO (los grupos ligados pierden el
  vínculo). App: "Nuevo chat" con Privado/Público + evento + invitados; hub con
  **Invitaciones** / "Del evento activo" (incluye grupos ligados) / "Tus chats";
  notificación "Te invitaron…" → detalles de la invitación; Modo evento → tab Chat con
  los grupos ligados y "+ Nuevo chat para este evento". Export incluye invitaciones.
- **Búsqueda de oficiales** sin acentos y por palabras (`translate()` en SQL).
- Datos de prueba borrados al cerrar (chats y cuentas `sim-chat-*`). Probar un privado
  requiere DOS cuentas (hoy solo existe la del usuario).

**Sesión 2026-09-25 (10ª, en paralelo con la 9ª): campeonatos nacionales** ✅ (cargador +
API admin; investigado con 4 subagentes; sin cambio de app):
- **NASCAR México** (NASCAR México Series, NASCAR Challenge Series y Trucks México Series —
  esta "categoría invitada" se agregó a pedido del usuario —, 12 fechas cada una) y **México Racing Cup** (7 categorías con numeración
  PROPIA: TC2000/ST/Copa 1.8/STL 10, TCR México y F4 NACAM 7, Endurance Challenge 5; el sitio
  numera los EVENTOS 1–18, que no son rondas). En `data/campeonatos/2026/`; fuentes y dudas
  en `FUENTES.md`. 5 sedes nuevas (Potosino, Chiapas, Aguascalientes, Yucatán, óvalo temporal
  de Tulum) y 6 trazados nuevos (AHR Nacional y **Óvalo con Estadio — horario**; Puebla
  Óvalo, Internacional corto, NASCAR corto sin dibujo; Querétaro Óvalo). Pendientes en
  `data/circuitos/PENDIENTES.md`.
- **Calendario en orden CRONOLÓGICO** (`DomainRepository.rounds`: fecha, luego número): una
  fecha reprogramada conserva su número oficial (NASCAR México: la 9 va tras la 10).
- **Logos de campeonato versionados** (`data/campeonatos/logos.csv` + `logos/`; antes solo en
  Postgres): el cargador los sube si el campeonato no tiene.
- **Ojo al cargar**: el cargador completo también REEMPLAZA las asignaciones de los eventos con
  roster (y la cuenta privada). Para cargar solo catálogos, correrlo sobre una copia de
  `data/` sin `eventos/`, `privado/` ni `circuitos/posiciones/` (así se hizo).

**Sesión 2026-09-25 (11ª): caché primero en toda la app** ✅ (emulador: log de peticiones,
video cuadro a cuadro, dato cambiado en Postgres que llega solo, offline y regreso de
señal). Causa del parpadeo al reabrir un campeonato: TODA lectura de `catalog` iba a la red
primero (la caché solo era plan B sin señal) y el detalle encadenaba 3 efectos (temporada →
categorías → calendario) que además pintaban "Aún no hay calendario" falso entre pasos.
Ahora: `Reloader.track` (caché primero + revalidación + recarga dirigida por llave —
`OfflineMiss.onRead`/`reportRead`, `catalogChanges()`), `freshReads` en `Refreshable`, y el
detalle de campeonato carga en una sola pasada. Migradas: catálogos y detalles de
campeonatos/circuitos/convocatorias, perfil/historial/accesos, hub y conversación de chat,
participantes, invitaciones, Home (convocatorias y conteos de Explorar), Modo evento (chats,
bitácora, asistencia, mapa), mapa en vivo, detalle de actividad, detalle de agenda y hub de
Configuración. Lectura desde caché medida: 6–12 ms (antes, 4 viajes de red en cascada).
Continuación — **centralizado**: caché primero pasó a ser el DEFAULT de la puerta de
lectura (`reportRead`; la excepción explícita es `freshReads`) y hay puerta de escritura
(`storeCatalog`/`refetch`/`notifyChanged`) que avisa a las pantallas ante cualquier
cambio — verificado con una nota de bitácora creada y borrada (log `caché: cambió …`, tag
ElPuestoSync). Escrituras que no dejaban la caché al día y ahora sí: invitar
(`invitations`), exportar (`accesses:me`), asistencia al drenarse el outbox; al encolar
(asistencia, ubicación, mensajes) se avisa para que se vea lo superpuesto.

**Sesión 2026-09-25 (12ª): logros, fase 1 de 3** ✅ (diseño en Claude Design, canvas
`GYQJFrNpxTHBQUcgyWKrHR`, copia en `docs/design/Logros/`; verificado por curl y en emulador
contra una COPIA de la base con 8 eventos pasados de prueba — copia ya borrada; **pendiente
redesplegar el backend real e instalar en el teléfono**):
- **Modelo** (`shared/model/Achievements.kt`): `Achievements{stamps, patches, items}` y
  `AchievementRules` = la ÚNICA fuente de claves, umbrales, familias de rol, visibilidad
  (`isPublic`) y "¿es F1?" (Grandes Premios); backend y app la comparten.
- **Backend** (`AchievementsRepository`): `GET /officers/{id}/achievements` derivado al
  leer de `pastAssignmentsTx` (eventos terminados; un evento con TODOS sus días ausente no
  cuenta), pase de lista, campeonatos del evento, bitácora (fecha = prefijo del UUIDv7),
  mensajes e invitaciones activas. De otro oficial el SERVIDOR recorta a lo público. Tabla
  nueva **`checklist_completions(event_id, puesto_id, day)`**: el toggle que deja la
  plantilla completa HOY guarda la fila (desmarcar la quita); nunca se purga — alimenta
  "Puesto impecable" (el estado del checklist sí se purga a diario).
- **App**: caché `achievements:{id}` (caché primero; el perfil la lee en la misma pasada);
  sección **Logros** en el perfil (pasaporte con siluetas de México, parches con el logo
  real, recientes con prioridad a lo de pista; en el ajeno "Logros de pista"); pantallas
  **Pasaporte** (`Overlay.Passport`) y **Logros** (`Overlay.Achievements`, hoja de detalle
  propia; sin FAB de bitácora); catálogo con **sello ✓** + "Trabajaste en N" (sin chip
  TRABAJAS ni borde ámbar); detalle de circuito con **"Tu historia aquí"** ("Conoces N de M
  puestos" = el `assignedBefore` que ya existía, contado). Íconos nuevos en `LineIcon`.
- **Fases pendientes**: (2) **medalla nueva** (pantalla de celebración + aviso "Nueva
  medalla" al terminar un evento; lo "ya visto" se guarda en el teléfono y la primera vez
  todo cuenta como visto); (3) **recuerdo de hace un año** en el Home y **"Tu temporada"**
  anual ("Compartir como imagen" depende de la decisión pendiente de compartir bitácora).

**Sesión 2026-09-25 (13ª): página promocional + ubicación solo cerca del circuito** ✅
(compila; candado probado por HTTP contra una COPIA de la base en :8082 — dentro/margen/fuera/
hotel; **pendiente redesplegar el backend e instalar la app**):
- **`web/index.html`**: landing de UN solo archivo (CSS/JS en línea, logo en data URI, trazado
  real de AHR en SVG; solo Google Fonts por fuera). Mensajes: "la app de cabecera del oficial
  de pista mexicano" (NO "companion": decisión del usuario), "hecha por oficiales, para
  oficiales", privacidad (quién ve qué), sin señal, sistema de honor de las invitaciones.
  **Sin convocatorias** (decisión del usuario: no meter ruido con OMDAI). Nada de "cifrado",
  "gratis", "sin anuncios" ni iPhone/Play Store. Los botones apuntan a `/descargas` (aún no la
  sirve el backend). Vista previa: artifact `SsbycbAmeMsTCjidR5xHQu`.
- **`/descargas`** explica por qué no estamos en Google Play (costos y tiempos de revisión),
  enlaza el código en GitHub para auditarlo y lista los permisos (calcados del manifest;
  mantenerla al día). **OJO: el repo `cultome/el_puesto` es PRIVADO** (el enlace da 404) y
  antes de hacerlo público: `androidApp/debug.keystore` firma el APK (público = cualquiera
  firma una "actualización" falsa → llave de release fuera del repo) y CLAUDE.md/historial
  traen correos y nombres reales.

**Sesión 2026-09-25 (14ª): registro por honor — COMPLETO (fases 1, 2 y 3)** ✅ (43
comprobaciones por HTTP + JSON-RPC del MCP + capturas de la admin web por CDP, todo contra
una COPIA de la base en :8083 — `el_puesto_honor`, ya se puede borrar; **pendiente
redesplegar el backend real y correr el cargador** para cerrar el GP):
- **Modelo** (`shared/model/Participation.kt`): `OperationalRoles` (el catálogo de roles
  pasó a shared; `validRoles`/`CHIEF_ROLES` del backend lo usan), `Participation`,
  `SetParticipationRequest`, `RegistrationState` (OPEN/NOT_YET/CLOSED/ROSTERED) y
  `EventRegistration`. `Event.selfRegistration` (10, null al actualizar = conservar),
  `AgendaEntry.registrationEventId/registration` (22-23), `AgendaAssignment.declared` (5),
  `OfficerHistoryEntry.declared` (12).
- **Backend**: tabla `participations` (única por evento+oficial; `days` null = todos) y
  columna `events.self_registration` (default true). `ParticipationRepository`; app:
  `GET /events/{id}/registration`, `PUT|DELETE /events/{id}/participation` (rechazo = 409
  con motivo; borrar es idempotente). Se suma en `pastAssignmentsTx` (historial, logros con
  sus días, eventos en común — "mismo puesto" solo si hay puesto —, "asignado antes") y en
  la agenda ("Trabajas" con `declared`, fines de semana con el estado del registro, eventos
  abiertos sueltos como `evt-<id>`). Bus `participation` (el WS solo al titular). Export:
  `eventos.json` con `origen`. Admin: `selfRegistration`/`declaredCount` en
  `GET /admin/events`, `GET /admin/events/{id}/participations`,
  `DELETE /admin/participations/{id}`; web: casilla en el form (marcada por defecto),
  columna "Autoregistro", chip en el detalle y panel **"Registros por honor"** (quitar);
  MCP: `guardar_evento` con `selfRegistration`, `listar_registros_evento`, `quitar_registro`.
  Cargador: columna `autoregistro` en `data/eventos/eventos.csv` (GP = `no`).
- **Fase 2 — app, HECHA (misma sesión)** (emulador contra la copia en :8083: registro
  nuevo, editar, sin señal → "Sin enviar" → se envió solo a los ~2 s de volver la red,
  historial/logros y quitar): detalle de la agenda con "¿Trabajaste este evento?" (o "aún
  no abre" / "lo registra la organización") y, ya registrado, "Tu participación · registro
  por honor" → `RegisterParticipationScreen` (`Overlay.RegisterParticipation`): aviso del
  sistema de honor la primera vez, puesto (pin tocable — `TrackMap.onSelectItem` —,
  buscador o "no aparece / sin puesto fijo"), rol por familia y días; editar y quitar.
  Outbox kind `participation` (refId = evento, gana la última; superpuesto al leer la
  llave `registration:{evento}`), `syncAfterParticipation` (agenda + historial + logros) y
  precarga en `refresh()` de los eventos abiertos cercanos (registrarse en pista sin red).
  `EventRegistration` trae nombre/circuito/trazados/fin (5-8). Historial propio: "Lo
  registraste tú · Editar". Un registro por honor NO abre Modo evento ni chat de puesto.
  Formulario con `remember` (no saveable: el SaveableStateHolder del shell conserva el
  estado de pantallas cerradas).
- **Fase 3 — puestos propuestos, HECHA (misma sesión)** (28 comprobaciones por HTTP contra
  la copia + emulador + admin web por CDP: proponer "MP 42" tocando el mapa → cola del admin
  → aprobar → la app lo recibe en vivo sin "en revisión"): en el registro, "Mi puesto no
  aparece" → número/nombre + toque en el mapa (`TrackMap.onTapMap`, `GeoFrame.unproject` →
  lat/lon; sin dibujo del trazado, solo el número). Tabla `puesto_proposals` (las resueltas
  se conservan: quién propuso, quién revisó) + `participations.proposal_id` (excluyente con
  position_id). En revisión SOLO la ve quien la propuso: agenda/historial/export la marcan
  (`positionPending`); en eventos en común no se le muestra a otros. Editar el registro
  actualiza la pendiente; elegir otro puesto o quitar el registro la retira. Admin:
  `GET /admin/puesto-proposals` agrupado por trazado + etiqueta normalizada ("7" = "MP 7" =
  "Puesto 7") con candidatos cercanos (≤120 m o misma etiqueta), `POST .../approve|merge|
  reject` (dryRun + auditoría); sección web "Puestos propuestos" con mini mapa; MCP
  `listar_puestos_propuestos` + aprobar/fusionar/rechazar. Aprobar crea el puesto (número =
  el de la etiqueta si es entero, si no el siguiente libre) y mueve las participaciones.
  **Cargador**: `--exportar-posiciones` agrega al CSV los puestos creados por el sistema, y
  la carga se DETIENE si el sistema tiene puestos que el CSV no conoce (o
  `--archivar-sobrantes`). Datos de prueba solo en la copia `el_puesto_honor` (borrable).
- **Ojo producción (AWS, sesión 15ª)**: la base de prod arranca limpia + cargador, así que
  el GP ya nace con `autoregistro = no` desde `data/eventos/eventos.csv`.

**Sesión 2026-09-25 (15ª): producción en AWS — MONTADA y v1.1.0 PUBLICADA** ✅ (pasos 1–6 ejecutados y
verificados por HTTPS/API admin; guía completa en **`docs/DESPLIEGUE.md`**):
- **URLs**: API `https://api.elpuesto.app` (IP elástica, instancia
  `elpuesto-api` t4g.small, Amazon Linux 2023 arm64, sin SSH — todo por SSM); sitio
  `https://elpuesto.app` (CloudFront `d2d2n4qsf47m2l.cloudfront.net` → S3 privado con OAC;
  `www` y `/descargas` redirigen; 404 propio). Admin web en `https://admin.elpuesto.app/admin/ui/`
  (desde 2026-09-26; antes en el host del API).
- **Decisiones del usuario**: DNS se queda en **Namecheap** (registros a mano), región
  **`mx-central-1`**, Postgres en la misma instancia con **respaldo diario 03:30 CDMX a S3**
  (35 días + mensual ~13 meses), base **limpia + cargador** (no se copió la de dev), **llave de
  release nueva** (`~/.config/el-puesto/release.jks` + `release.properties`, fuera del repo;
  los teléfonos con la v1.0.0 desinstalan una vez) y APK con la **versión en el nombre**
  (`el-puesto-<versión>.apk` inmutable + `/version.json`).
- **Correo**: **Mailgun** (`smtp.mailgun.org`, `noreply@elpuesto.app`; SPF + DKIM `pdk1`/`pdk2`
  en Namecheap). Los MX del dominio ahora son de Mailgun → el reenvío de Namecheap ya no aplica.
- **Datos cargados**: 83 circuitos, 8 campeonatos, 368 oficiales, GP México con roster
  (autoregistro cerrado), la cuenta del usuario ACTIVE, ingesta jolpica-f1.
- **Piezas**: `infra/aws/1..6-*.sh` (idempotentes, sin archivo de estado), `infra/servidor/`
  (bootstrap, compose Postgres+Caddy, systemd, `activar.sh` con carpeta por versión + rollback
  por `/health`, respaldo/restauración), `scripts/desplegar-backend.sh` / `publicar-app.sh` /
  `publicar-sitio.sh` / `prod.sh` / `servidor.sh`; `web/descargas/` (plantilla) y `web/404.html`.
  Backend: `DOWNLOADS_URL` → `/descargas` redirige a la página estática. App:
  `assembleRelease` firma con la llave de release y apaga el tráfico en claro.
- Rol TSP renombrado a **"Panel de luz"** en todo el sistema (migración al arrancar): el
  cargador lo exigía por el cambio de `roles-omdai.csv`.
- **v1.1.0 publicada** (tag `v1.1.0` local; CHANGELOG al día): `https://elpuesto.app/descargas/`
  → `el-puesto-1.1.0.apk` (firma de release, API `https://api.elpuesto.app`, huella en la
  página). Verificado: descarga + SHA-256, respaldo manual + restauración en una base
  temporal (83 circuitos/368 oficiales/144 imágenes), **reinicio de la instancia** (todo
  vuelve solo en ~30 s), `/descargas` del API → 302 a la página nueva, WebSocket vía Caddy.
- **Despliegue automático** (GitHub Actions + OIDC, `infra/aws/7-github.sh`): push a `master`
  con cambios en backend/shared/Gradle → `desplegar-backend.yml` (desde 2026-09-26: compila
  SIN credenciales y despliega con el documento de SSM `ElPuesto-Activar`; la config del
  servidor `infra/servidor/` ya NO la despliega CI: `scripts/desplegar-servidor.sh`, a mano);
  con cambios en `web/` → `publicar-sitio.yml` (sin `/descargas/`, que publica
  `scripts/publicar-descargas.sh`). Rol `elpuesto-github-deploy` (solo `master` de
  cultome/el_puesto; sin acceso a respaldos ni Parameter Store; PROHIBIDO escribir APKs y
  version.json → **publicar la app sigue siendo manual**). Commits locales no despliegan.
  Versión nueva de la app en un paso: **`scripts/nueva-version.sh [patch|minor|major]`**
  (`--prueba` no toca nada; CHANGELOG en el editor → commit → push → espera el backend de
  Actions si aplica → publicar-app.sh → push del tag).
- **Pendiente**: DMARC (`_dmarc` TXT `v=DMARC1; p=none;`) en Namecheap; ~~la CLI entra como
  root~~ **HECHO 2026-09-26**: usuario propio de IAM Identity Center (§5); hacer
  público el repo (la landing y /descargas enlazan a GitHub, hoy da 404). ~~La app no lee
  `/version.json`~~ **HECHO 2026-09-26 (2ª)**: se actualiza desde la app.

**Sesión 2026-09-26: seguridad de la API — bloques 1 y 2** ✅ (commits LOCALES sin push: el
push a master despliega; 32 comprobaciones HTTP/WebSocket contra una copia de la base +
emulador con límites al 1%; guía operativa en `docs/DESPLIEGUE.md` → "Abuso y sesiones"):
- **Sesiones** (`Sessions.kt`): las rutas de datos exigen cuenta ACTIVE (`CuentaActiva`;
  `GET /me` exento) — antes suspender solo cambiaba la UI. Corte por cuenta
  (`accounts.sessions_revoked_at` vs `iat` del JWT, redondeado al segundo siguiente) y
  `AccountGate` (caché 30 s, se invalida al cambiar la cuenta). **Reuso de refresh token**
  fuera de 2 min de margen → cierra todas las sesiones + correo al titular + alerta.
  `POST /auth/logout`. Admin: `activeSessions`, `POST /admin/accounts/{email}/revoke-sessions`,
  `POST /admin/sessions/revoke-all` (scope keys); web + MCP (`cerrar_sesiones_cuenta`,
  `cerrar_todas_las_sesiones`; 69 tools). WS/SSE se cierran en ≤ 30 s; máx. 5 WS por oficial.
- **Abuso** (`Abuse.kt`): `RateLimits` por oficial y clase (y por IP en /auth/*) con 429 +
  Retry-After lanzado ANTES del handler (`Rejected` → StatusPages); `RATE_LIMIT_SCALE`.
  `BodyLimits` por Content-Length (256 KB / 15 MB fotos / 4 MB admin), `TextLimits`,
  búsqueda ≥ 2 caracteres, imágenes con dimensiones leídas antes de decodificar (> 100 MP
  = rechazo) y submuestreo a ≤ 4096 px, `statement_timeout` 15 s, enlace mágico con
  respuesta idéntica exista o no la cuenta (correo en segundo plano), log `Peticiones` y
  `SecurityMonitor` → `ALERT_EMAIL`.
- **Infra**: Caddy (20 MB, read_header 10 s, logs SIN `X-Admin-Key` — antes se registraba
  en claro — ni `token` de la query), journald 30 días (en `configurar.sh`),
  `8-presupuesto.sh`, `5-parametros.sh alertas CORREO`.
- **App** (va en la próxima versión, 1.1.1 con `scripts/nueva-version.sh`): 429/5xx ya no
  descartan la cola (`BusySignal` + reintento con Retry-After), refresh 429/5xx/sin red ≠
  sesión inválida, logout revoca en el servidor, sesión muerta → se borra lo local (también
  al volver a la app), pantalla "Tu cuenta está suspendida", "Revisa tu correo" neutral.
- **Pendiente para producción** (el usuario hará cambios antes de desplegar): push (despliega
  backend + Caddy + journald), `5-parametros.sh alertas CORREO` + `--solo-config` para las
  alertas del backend, publicar la 1.1.1. El **presupuesto de AWS ya lo configuró el usuario
  a mano** (avisos al correo de la cuenta): NO correr `8-presupuesto.sh` en esta cuenta
  (crearía un segundo presupuesto). Pendientes técnicos en `docs/DEUDA-TECNICA.md` → "Seguridad y abuso".

**Sesión 2026-09-26 (2ª): migraciones de la base local + actualizar la app en un toque** ✅
(emulador con builds temporales y un `version.json` servido en local; commits LOCALES; **va
en la próxima versión** — quien tenga la 1.1.0 actualiza a mano una última vez):
- **Migraciones SQLDelight** (antes el esquema local era SIEMPRE la versión 1: cambiar
  `ElPuesto.sq` dejaba a quien actualizaba —en vez de reinstalar— con tablas faltantes y la
  app tronando). `databases/1.db` = esquema de la v1.1.0; cambios en `N.sqm` (guía en
  `androidApp/src/main/sqldelight/README.md`); `preBuild` corre `verifySqlDelightMigration`
  (sin migración no hay APK). **`SafeMigrations`** (`data/Db.kt`): si una migración falla en
  un teléfono o la base es más nueva que la app, reconstruye vacía y **rescata la outbox**
  (la caché se llena sola). Probado: migración buena (datos intactos), migración que truena
  por datos (cola rescatada y enviada al volver la red) y bajar de versión.
- **Actualizar en un toque** (`AppUpdates.kt` + `UpdateInstallReceiver`): revisa
  `version.json` al volver a la app (cada 6 h como mucho; al abrir la pantalla, siempre) y
  guarda lo último en `el_puesto_updates` (el aviso sale sin señal). Aviso compacto en el
  **Inicio** (se cierra por 3 días; descargando/lista/error se quedan), fila **Configuración →
  Acerca de → Actualizaciones** y pantalla `AppUpdateScreen` (`Overlay.AppUpdate`): "Qué hay
  de nuevo" (notas de TODAS las versiones posteriores a la instalada), tamaño, permiso
  "Instalar apps desconocidas" explicado antes de mandar a Ajustes, progreso, errores con
  Reintentar. Descarga a `files/updates/` y verifica tamaño, SHA-256, paquete, versionCode y
  **firma** (mensaje claro si es otra llave, p. ej. un build de prueba) antes de abrir el
  instalador por **sesión de `PackageInstaller`** (base para la opción silenciosa). Solo acepta
  el APK del mismo host que el `version.json`. `MY_PACKAGE_REPLACED` re-programa
  recordatorios y barra de evento. Tras actualizar, El Puesto queda como su propio instalador.
- **Build**: `UPDATES_URL` por `-PupdatesUrl` (vacío = apagado; `publicar-app.sh` pasa el de
  producción). `version.json` ganó `novedades` (`scripts/novedades.py` saca «Para los
  oficiales» del CHANGELOG). `/descargas` lista el permiso nuevo. `MarkdownText` pasó a
  `ui/components/Markdown.kt`.

**Sesión 2026-09-26 (3ª): auditoría de seguridad completa y TODO atendido** ✅ (4 auditorías en
paralelo — permisos, inyección, flujos/abuso, app+infra —; 65 pruebas HTTP/WebSocket + 9 de
operación contra una COPIA de la base (`el_puesto_seg`, :8082), admin web por CDP sin
bloqueos de CSP, MCP por JSON-RPC y la app de punta a punta en el emulador; **aplicado en
producción la misma noche**, ver el último punto). Lo grave que había: XSS en `/auth/open` (mismo origen que el admin →
robo de la X-Admin-Key) y el WS general repartiendo a TODOS los teléfonos cada cambio del
admin con su detalle (correos de cuentas).
- **Web**: `WebSecurity.kt` (CSP por ruta en toda respuesta; la página puente solo acepta un
  UUID y su script va por hash; COOP/CORP; `ADMIN_HOST` = el admin solo en su host). Admin
  web con la clave en `sessionStorage` y Markdown sin fuga de atributos.
- **Tiempo real**: `StreamPolicy.kt` = LISTA BLANCA de avisos `admin:*` hacia los oficiales,
  sin detalle (salvo `active=` del evento); lo demás ya no sale por el WS (default deny); SSE
  del evento solo a asignados y con tope; bus con DROP_OLDEST por suscriptor.
- **Auth**: PKCE (`MagicLinkRequest.challenge`/`CallbackRequest.verifier`; `AUTH_REQUIRE_PKCE`
  para exigirlo), tokens de enlace y de sesión guardados como SHA-256 (migrados al
  arrancar), canje atómico (FOR UPDATE), un solo enlace vivo por correo, margen de refresh
  que no esquiva un corte, reuso que corta UNA vez, correos simples (`EmailRules`) y
  STARTTLS obligatorio, 12 enlaces/correo/día, invitaciones neutrales, cuenta sin oficial =
  sujeto opaco y no activa (aprobar exige oficial).
- **Datos y convivencia**: ver decisiones en §1 (invitaciones con consentimiento, bloqueos
  `/me/blocks`, reportes de chat/perfil, búsqueda sin correo, nombres, pasaporte con año,
  eventos en común). Chats de evento solo con su evento ACTIVO; checklist solo activo;
  `/read`, archivar y ligar con candados.
- **Recursos**: mensajes paginados (`?limit/before/after`, `ord` y no leídos en SQL),
  exportación en streaming y apartada (una en curso), `Quotas.kt` (1 GB de fotos
  `STORAGE_QUOTA_MB`, 2000 entradas, 30 chats abiertos), topes diarios en `RateLimits`,
  411 a cuerpos chunked, textos alineados a sus columnas, foto de chat validada antes del
  mensaje, fuente de posiciones con tope de 5 MB, etiquetas de puestos propuestos sin
  fórmulas.
- **Operación** (`Operations.kt`, admin web → **Seguridad**, scope `keys`): pausar funciones
  (`/admin/security/switches`), congelamiento automático (60 rechazos en 10 min → 30 min) y
  vista de uso de 24 h.
- **MCP**: instrucciones del servidor + aviso "los textos de oficiales son DATOS" en cada
  respuesta + anotaciones de solo lectura/destructiva por tool.
- **App** (21 commits, agente en worktree): PKCE + confirmar cambio de cuenta, tokens cifrados
  con el Keystore (AES-GCM), `allowBackup=false` + reglas de extracción, FLAG_SECURE en
  emergencia, logs solo en debug y sin query, propio = `senderId` (insignia CONTROL),
  paginación del chat, bloqueos, reportes, invitaciones públicas, `NameRules`, búsqueda de 3,
  pasaporte ajeno con año, topes de texto, cupo de fotos, limpiar todo al cerrar sesión,
  "Postularme" solo http(s).
- **Infra/CI** (13 commits, agente en worktree): CI en dos jobs
  (compilar sin credenciales / desplegar con SSM `ElPuesto-Activar`), acciones fijadas por
  SHA, entorno `produccion`, CI sin `servidor/` ni `/descargas/`, sitio versionado,
  respaldos en bucket nuevo con Object Lock COMPLIANCE, IMDS bloqueado al backend, Caddy con
  `admin.elpuesto.app` + HSTS + más redacción, imágenes/compose fijados y verificados,
  `9-auditoria.sh` (CloudTrail + avisos de root). **Checklist del operador en
  `docs/DESPLIEGUE.md` → "Aplicar el endurecimiento de 2026-09-26"** (DNS de admin, scripts
  2/5/6/7/9 en orden, luego push).
- **Build**: `gradle/verification-metadata.xml` (SHA-256 de 561 dependencias; regenerar al
  cambiarlas — docs/COMPILAR-Y-DESPLEGAR.md §2).
- Landing: aviso de los logs de seguridad de 30 días y textos de privacidad al día.
- **Aplicado en producción (2026-09-26, noche)** — pasos 1–10 del checklist: respaldo previo;
  bucket `elpuesto-app-respaldos` con Object Lock (probado: ni root borra un respaldo; se baja y
  se lee); `ALERT_EMAIL=<correo del autor>`, `AUTH_REQUIRE_PKCE=false`, `STORAGE_QUOTA_MB=1024`;
  documento `ElPuesto-Activar`, rol de la instancia reducido, IMDS bloqueado al backend,
  Postgres 16.15/Caddy 2.11.4/Compose 5.5.1 fijados (ya eran esas versiones); entorno
  `produccion` en GitHub (se creó en el repo privado, pero SIN revisor obligatorio: requiere
  plan de pago) y rol de CI reducido (simulado: solo `despliegues/`, el sitio y
  `ElPuesto-Activar`); push → CI desplegó backend `20260927-0123-9c1e3270b5` y el sitio; los
  refresh tokens vivos quedaron hasheados (las sesiones siguen); **`ADMIN_HOST` activo**: el
  admin vive SOLO en `https://admin.elpuesto.app/admin/ui/` (api da 404 en `/admin/*`; `prod.sh`
  ya usa ese host; hay que volver a pegar la clave en el navegador).
- **Mismo día, después**: **v1.1.2 publicada** (`scripts/nueva-version.sh`; tag `v1.1.2`) y
  **auditoría de la cuenta** (paso 12): usuario propio en IAM Identity Center (us-west-2, ver
  §5), CloudTrail `elpuesto-auditoria` (todas las regiones, validación de integridad, bucket
  `elpuesto-app-auditoria` de 365 días) y avisos de uso de root / login fallido / contraseña o MFA
  fallidos en el portal de Identity Center a `ALERT_EMAIL` (us-east-1, mx-central-1 y
  us-west-2). El backend además avisa a `ALERT_EMAIL` cuando una IP queda bloqueada por fuerza
  bruta contra la clave de admin (un error suelto NO manda correo).
- **Pendiente** (DEUDA-TECNICA → "Seguridad y abuso"): `AUTH_REQUIRE_PKCE=true` cuando la mayoría
  tenga la 1.1.2.

**Sesión 2026-09-26 (4ª): versión web — la app en el navegador** ✅ (rama `web`; verificado en
el emulador Android y en Chromium headless por CDP contra el backend local; **sin desplegar**):
- **Reestructura**: la app pasó a `app/` (Compose Multiplatform, común Android + wasm) con las
  interfaces de plataforma de §3; `androidApp` quedó con lo propio de Android. **Toolchain**:
  Kotlin **2.3.21** (los recursos de Compose 1.11 exigen stdlib 2.3.20 en wasm), Compose
  Multiplatform 1.11.1 + material3 1.9.0, **Ktor 3.3.3 en el cliente** (el backend sigue en
  Ktor 2), serialization 1.9.0, kotlinx-datetime **0.7.1-0.6.x-compat** (material3 web exige
  0.7.1; la variante compat conserva `kotlinx.datetime.Instant` para `shared`/backend). Node,
  Yarn y Binaryen se bajan por Gradle desde repositorios declarados en `settings.gradle.kts`
  (verificados en `verification-metadata.xml`). PKCE con SHA-256 en Kotlin puro (`Sha256`,
  pruebas en `app/src/commonTest`).
- **Backend** (subagente en worktree, 113 pruebas): cookie `ep_rt` para la web, boleto del
  WebSocket, enlace mágico `#auth=` para `client=web`, `/app/` con su CSP y `WEB_APP_DIR`/
  `WEB_APP_URL`; de paso, CORS reconocía por ajeno al propio host tras Caddy y Caddy pisaba la
  `Permissions-Policy` del backend (corregidos).
- **Verificado en la web**: acceso por enlace con PKCE, sesión que sobrevive a recargar, Inicio,
  Modo evento con mapa, "atrás" del navegador, chats en vivo (aviso de Control por el WS),
  enviar mensaje, catálogo y detalle de circuitos, perfil, configuración, **exportación**
  (ZIP íntegro), **foto a la bitácora** (selector de archivos → canvas → subida) y cerrar
  sesión (revoca el refresh). En Android (emulador): acceso con PKCE, Inicio, Modo evento,
  Chats, Perfil y Configuración sin regresiones.
- **Bienvenida una vez por cuenta** (pedido del usuario al probar): "Completar perfil" salía en
  cada inicio de sesión en un dispositivo nuevo (el "ya la hizo" vivía solo en el teléfono).
  Ahora `accounts.onboarded_at` (al crear la columna se marcaron las cuentas que ya habían
  entrado: tienen refresh tokens, que nunca se borran) + `GET/POST /me/onboarding`; terminarla
  u omitirla lo registra (sin señal, por el outbox `onboarding`). Verificado: cuenta existente
  en navegador limpio y en Android recién limpiado → Inicio directo; cuenta nueva → bienvenida
  una vez y en otro navegador → Inicio.
- **Publicada el 2026-09-26 en `https://app.elpuesto.app`** (pedido del usuario, opción "host
  propio"): `APP_HOST` en el backend (raíz → `/app/`, en otros hosts `/app` redirige, enlaces de
  acceso web ahí), la web viaja DENTRO de cada versión del backend (`releases/<v>/web`,
  `WEB_APP_DIR` fijo en `generar-env.sh`), CI la compila, Caddy la comprime (17.2 → 5.8 MB) y la
  landing/`/descargas` enlazan "Usar en el navegador". De paso: el Caddyfile montado como archivo
  no se recargaba tras `aws s3 sync` ("config is unchanged") → `configurar.sh` reinicia Caddy si
  cambió. Verificado en producción: redirecciones, CSP/HSTS/noindex, compresión, carga en
  navegador, sin errores en el backend.
- **Android 1.2.0 publicada** (tag `v1.2.0`, versionCode 5) tras validarla el usuario en su
  teléfono y la web en producción: la reestructura + la bienvenida por cuenta. Pendiente: lo
  de DEUDA-TECNICA → "App web". El arnés de CDP (`cdp.mjs`: nav/shot/click/type/wheel/pickfile/
  eval/logs sobre `chrome-headless-shell --remote-debugging-port=9333`) vive en el scratchpad
  de la sesión: recrearlo si hace falta.

**Sesión 2026-09-26 (5ª): el MbM en la pantalla de bloqueo (nivel 1)** ✅ (emulador con PIN,
con y sin "ocultar contenido sensible", contra una copia de la base, y **validado por el usuario
en su teléfono** con un APK de release apuntando a ngrok; va en la próxima versión de Android). La barra fija "Evento en curso" (`LiveEventBar`) es `VISIBILITY_PUBLIC` y trae
SOLO el MbM (decisión del usuario: sin puesto, rol ni nombre del evento — este además cortaba
el título colapsado), en el canal nuevo `evento_en_curso_2` (IMPORTANCE_DEFAULT sin sonido ni
vibración: el LOW era "silencioso" y muchos teléfonos los esconden en bloqueo; Android no deja
subir la importancia de un canal → id nuevo y el viejo se borra), con **grupo propio** (si no,
Android la agrupa con los avisos y pierde la cuenta regresiva) y expandida con las dos
actividades que siguen. El resto de notificaciones (`ChatNotifications.post`, parámetro
`Locked`) quedan PRIVATE con una versión pública genérica ("Mensaje nuevo", "Recordatorio"…);
"Cambió el MbM" es pública. Nivel 2 (Live Updates de Android 16) en `docs/IDEAS.md`.

**Sesión 2026-09-27: posiciones de Fórmula 2, Fórmula 3, NASCAR y WEC por sus sitios públicos** ✅ (probado por la API admin
contra una COPIA de la base, ya borrada; **en producción desde el 2026-09-27**, v1.3.0). Decisión del usuario: las posiciones se sacan aunque haya que leer las páginas
públicas, una categoría a la vez (ver §1 e IDEAS.md). Fuente **`fiaformula2-web`**
(`FiaSeriesSite` en `StandingsIngest.kt`, reutilizable para F3): lee el JSON que Next.js incrusta
en `/en/standings/{año}/drivers` (`NextFlight`) y, para número/equipo, la página de cada fecha
hacia atrás. Posición = ORDEN de la tabla (el sitio publicaba 2, 4, 6…); fecha cotejada por día;
fecha vieja = error (solo publica la vigente). `StandingsSource.fetch` recibe ahora el día de
carrera. Fila nueva en `data/campeonatos/fuentes-posiciones.csv`; notas en
`docs/fuentes-posiciones/f1-family.md`. Resultado tras Bakú (R12): 23 pilotos, Câmara 207.
**F3** = `fiaformula3-web`, otra instancia del mismo lector (temporada ya terminada: 34 pilotos,
Ugochukwu 159, fecha 9 confirmada). **NASCAR** = `nascar-feed` (`NascarFeeds`, parámetro = serie
1/2/3): www.nascar.com da 403 por el reto de Cloudflare, así que lee los JSON públicos de
cf.nascar.com (calendario, tabla, resultados por carrera para el equipo); tabla reflejada = los
`points_earned` coinciden con el resultado (≥ 90%). Cup 40 pilotos tras la fecha 29, O'Reilly 66
tras la 27, Truck 93 tras la 20. The Chase: los de arriba con puntos reiniciados, tal cual NASCAR.
**WEC** = `fiawec-web` (`FiaWecSite`, parámetro = clase `hypercar`/`lmgt3`): campeonato de PILOTOS de
cada clase (decisión del usuario; una fila por tripulación), HTML de `/en/season/{año}` leído con
**Jsoup** (dependencia nueva del backend, huellas regeneradas); nombres bien escritos desde la página
de cada piloto (en memoria, ~2 min la primera vez por clase). `SourceContext.rounds` = fechas de
nuestro calendario (WEC exige que coincida). **Ajustes del job**: "la fuente ya va en una fecha
posterior" (pasa horas tras cada carrera, porque el job espera a que acabe el día en UTC) ahora es
"aún no" y no error; la confirmación que falla se reintenta cada 6 h, no cada vuelta. Investigaciones
por subagentes en el scratchpad: Fórmula E (viable por la página), WRC e IndyCar.

**Sesión 2026-09-27 (2ª): fotos de pilotos** ✅ (probado contra una COPIA de la base en :8082 —
ya borrada— en el emulador y en la web por CDP; **en producción con la v1.3.0**). `Standing.photoUrl` (8,
derivado al leer de `drivers.photo`), kind de imagen **`driver`** (`ImageService.storeDriverPhoto`:
`full` con su proporción y `thumb` 192 px con la cara, transparencias sobre el carbón `#1E222B`) y
`DriverPhotos.ensure` en la ingesta: cada foto se baja UNA vez (id = huella de sus URLs; solo de
res.cloudinary.com, media.formula1.com y www.fiawec.com; tope de 16 MB para imágenes) ANTES de
escribir la tabla. Fuentes: **F1** = página pública de pilotos de formula1.com (empate por
nombre(3)+apellido(3) con Jolpica; 22 de 23, Tsunoda ya no está en la parrilla), **F2/F3** =
`driverAvatarImage` de los resultados (Cloudinary recorta la cara con `g_face`; 23 de 23), **WEC** =
la foto de la página de cada piloto (la miniatura se recorta de arriba; 23 de 26; una tripulación
usa la del primero). NASCAR sin fotos (sus imágenes están tras el reto de Cloudflare). App: fila
con foto circular (iniciales si no hay), pastilla `#33` junto al equipo y toque → `Overlay.ImageView`
(el visor usa el carbón con fotos de piloto). Reemplazos manuales conservan las fotos por `ref`.

**Sesión 2026-09-27 (3ª): México Racing Cup** ✅ (probado contra una COPIA de la base en :8082,
ya borrada): tabla `standings_readings` + `PUT/GET /admin/categories/{id}/standings-reading`
(tools MCP `guardar/ver_lectura_posiciones`) + fuente **`mexicoracingcup-img`** (publica la lectura
mientras la imagen vigente del sitio sea la leída; si sube otra, "aún no" con la URL que falta leer).
Las 8 tablas leídas por un subagente (validadas por suma) viven en
`data/campeonatos/2026/posiciones-imagen/` y el cargador las sube si cambiaron. ST Light se partió en
"Súper Turismos Light 1" y "Súper Turismos Light 2" (en cada base: RENOMBRAR la categoría vieja a
"… 1" ANTES del cargador, para conservar sus ids). `SourceContext.categoryId` nuevo.
**Producción (2026-09-27)**: **v1.3.0 publicada** (`scripts/nueva-version.sh minor` con las notas
redactadas a mano vía `VISUAL=<script que copia las notas>`); ST Light renombrada a "… 1" y el
cargador corrido SOLO con catálogos (copia de `data/` sin `eventos/`, `privado/` ni
`circuitos/posiciones/`, con `scripts/prod.sh`); primera carga manual de las 16 categorías con
fuente (WEC forzado a la fecha 6: Fuji se corrió ese día). Fotos: F1 22/23, F2 23/23, F3 33/34 (la de
Heuzenroeder da 404 en su sitio), WEC 23/26 y 21/26; NASCAR y la MRC sin fotos. La base de dev
(local) recibió lo mismo (ST Light renombrada + cargador de catálogos) el mismo día.

**Sesión 2026-09-27 (4ª): Fórmula E, WRC e IndyCar** ✅ (tres subagentes en worktrees, cada uno con su
copia de la base y su puerto; integrados por cherry-pick): fuentes **`formulae-web`**
(`FormulaESource.kt`: tabla del HTML + puntos por fecha del flight de Next, temporada N = año inicial
de la etiqueta − 2013, fotos de Contentful con la cara recortada), **`wrc-feed`** (`WrcSource.kt`:
JSON públicos de Red Bull que descarga wrc.com; parámetro wrc|wrc2|wrc3|jwrc; 0 puntos fuera; número y
equipo de la inscripción más reciente, en memoria; en WRC los puntos del rally deben sumar 130; fotos
solo de los de Rally1 y algunos más) e **`indycar-feed`** (`IndyCarSource.kt`: `/api/results/*` de
indycar.com/indynxt.com; parámetro indycar|indynxt; equipo = nombre CORTO de la página /standings,
si esa página falla la corrida es error; fotos de medio cuerpo, en Indy NXT con fondo blanco). Los
helpers comunes de las fuentes (`str/arr/obj`, `aheadOf`, `properCase`) pasaron a `internal`. Nota:
la NASCAR México y el separador "The Chase" quedan para después (decisión del usuario).
**En producción el mismo día** (push de `master` con los commits de la otra sesión — agenda "lo que
viene", íconos de la barra, imagen del chat/perfil en grande — que también llegaron a la web; cargador
solo de catálogos + primera carga): 24 categorías con posiciones automáticas y 0 errores — Fórmula E
2025-26 (20/20 fotos; 2026-27 en espera hasta diciembre), IndyCar 33/33 y Indy NXT 28/28 fotos, WRC
(16 de 32 fotos), WRC2 (3 de 58), WRC3 y Junior WRC sin fotos.
**Android 1.4.0 publicada** (tag `v1.4.0`, versionCode 8): imagen del chat y foto del perfil en
grande, agenda "lo que viene", íconos nuevos de la barra (de la otra sesión) y el arreglo de
volver a Posiciones al cerrar la foto de un piloto (el detalle del campeonato guarda con
`rememberSaveable` la categoría, la temporada, si hay tabla y el scroll de Posiciones — el scroll se
re-aplica cuando la tabla ya cargó, porque el esqueleto de carga lo recortaba).

**Sesión 2026-09-27 (4ª): contexto de chats + imágenes en grande** ✅ (copia de la base; emulador y
web por CDP): (1) **Puesto/rol solo dentro de un evento**: `senderContext` ya no cae al evento
ACTIVO en públicos/privados sin evento ligado (participantes y burbujas sin "P N · Rol"); el
fallback queda para los de evento/puesto. Ya en `origin/master` (despliega el backend). (2)
**Tocar para ver en grande**: `RemoteImageBox`/`RemoteAvatar` ganan `onOpen` (tocable solo si la
imagen existe; entrega la variante `full` a `Overlay.ImageView`); se usa en Detalles del chat y
en el encabezado del perfil. **Va en la próxima versión de la app** (commit local, sin push).

**Sesión 2026-09-27 (5ª): Agenda "lo que viene"** ✅ (diseñada en Claude Design, canvas
`MCgbnkFT2gsx6rJYQHdSuE`, copia en `docs/design/Agenda - lo que viene/`; probada en emulador y en
la web por CDP; **va en la próxima versión de la app**). Problema: la lista era todo el mes con lo
pasado incluido (13–19 fines de semana de campeonato por mes) y tocar un día no filtraba.
Decisiones del usuario: al entrar **ningún día seleccionado**, **sin filtro "Lo mío"**, carreras en
**filas compactas**. `AgendaScreen` (solo app, sin cambio de backend ni de wire): (A) sin día =
"Lo que viene" — mes actual desde hoy con "N anteriores este mes" desplegable (atenuadas), otro
mes completo ("Todo noviembre") con botón HOY junto a las flechas; cada entrada sale UNA vez bajo
el día en que empieza (lo que sigue en curso, bajo HOY) y al final "Siguiente · Octubre · N
actividades"; (B) día tocado = solo ese día, lo de varios días en CADA día que abarca (rango =
`startsOn/endsOn` o `at…endsAt`: "Trabajas · Día 1 de 3", "Hospedaje · Noche 2 de 4", entrada/
salida con hora, carreras con "DÍA 1/3" y las categorías que corren ese día), "✕ Todo el mes", y
día vacío con **"Planear algo este día"** (el editor abre con esa fecha: `EditTripItem.presetDate`;
el "+" con un día tocado hace lo mismo). Tarjetas para trabajas (logo del campeonato + chip de
posición · rol), planeación (azul, ícono por tipo) y convocatorias (verde); filas compactas para
fines de semana que no trabajas (logo, título sin el prefijo del campeonato). Calendario: toda la
celda es tocable, puntos por tipo (ámbar trabajas, azul planeación, verde convocatoria, gris
carreras; máx. 3), franja ámbar que une los días del evento que trabajas, días pasados atenuados.
Día tocado y anteriores con `rememberSaveable`: sobreviven a abrir un detalle, no a cambiar de
pestaña ni de mes. `LineIcon.CLOSE` nuevo.

**Pendiente registrado para después (no perder):**
- ~~Instalar en el teléfono lo de las sesiones 2026-08-02 (4ª, 5ª y 7ª)~~ **HECHO**: el
  APK de la v1.0.0 lo trae todo y el usuario lo usa en su teléfono.
- ~~Invitaciones~~ **HECHO 2026-08-01**: tabla `invitations(id, inviter_id, invitee_email,
  created_at)` + cuenta INVITED al invitar (`POST /invitations` autenticado; duplicado =
  Ack(ok=false) — el `Ack` ahora también vive en shared con @ProtoNumber). Primer login
  del invitado (magic link) → **PENDING_APPROVAL** automático → aprueba el admin. App:
  Configuración → "Invitar a un oficial" (`InvitationsScreen`: correo + lista de mis
  invitaciones con estatus vivo). Admin: columna "Invitada por" en Cuentas
  (`AdminAccountInfo.invitedBy`). Chat del evento en web: ver bloque de tiempo real.
- ~~Notificación persistente del cronograma~~ **HECHA 2026-09-25 (6ª)** (`LiveEventBar`). **Push con la app cerrada (FCM)** sigue pendiente: requiere un proyecto de Firebase del usuario.
- ~~Chat en tiempo real~~ **HECHO 2026-08-01 (WebSockets)**: `webSocket("/chats/stream")`
  autenticado (Bearer; plugin `ktor-server-websockets` con ping 25s) sobre el mismo
  `ChangeBus` (`sendMessage` emite `kind="chat"` + `chatId`; los streams SSE de eventos
  filtran ese kind). **Guard de visibilidad** `canSeeChat` (público = miembro;
  evento/puesto = asignado al evento activo): no se notifica lo que no se puede leer.
  App: `chatChanges(): Flow<chatId>` (`ktor-client-websockets` sobre el cliente de
  streams, reconexión + tryRefresh); la conversación abierta refetchea sus mensajes y el
  hub refresca previews. Verificado con python-websockets (visible notifica, no-miembro
  no).
- **Chat**: ~~moderación~~ **HECHA 2026-08-02 (5ª)** (reportar + silenciar local); **video en mensajes** (fase b: storage de archivos + streaming + ExoPlayer); **invitar oficiales a un chat** buscando por nombre/OMDAI ID/correo (acordado 2026-07-28; detalles y nota de privacidad en `docs/DEUDA-TECNICA.md`).
- ~~Auth: credenciales SMTP~~ **HECHO 2026-08-02 (5ª)**: `source ./setenv` (script del usuario, gitignored) antes de lanzar el backend; correo real verificado y acceso demo eliminado de la app.
- Ver `docs/DEUDA-TECNICA.md` para el pulido/detalles restantes (p. ej. escrituras de agenda y fotos por outbox).

**2026-09-27 (cierre): `CLAUDE.md` compactado** — esta bitácora salió de su §4 (1,307 líneas) y
en su lugar quedó un "Estado actual" condensado: versiones en producción, qué existe, tabla de
fuentes de posiciones, procedimientos y avisos vigentes y pendientes vivos. `CLAUDE.md` pasó de
1,547 líneas (160 KB) a ~330 (46 KB). De aquí en adelante: una entrada breve por sesión aquí y
actualizar la §4 de `CLAUDE.md` solo si cambia lo vigente.

**Sesión 2026-09-27 (6ª): mapas homologados** ✅ (diseñados en Claude Design, canvas
`MraNCNypGeRWcnECWnAtR9`, copia en `docs/design/Mapas homologados/`; probados en la web por CDP y en
el emulador contra una copia de la base; **publicada en Android 1.5.0**, la web y el admin con el
mismo despliegue). Problema: cada
mapa hacía lo suyo — Circuito filtraba por tipo con chips de selección única (Todos/Puestos/
Vehículos/Ambulancias), el tab Puesto no filtraba, el Mapa en vivo tenía un solo "Puestos ✓" que
ocultaba todo, el registro por honor nada; pantalla completa solo en el evento; el ⤢ de "ver
completo" se confundía con pantalla completa; y el Mapa en vivo siempre abría el PRIMER trazado
aunque en el tab Puesto se hubiera elegido otro. Decisiones del usuario: capas **Puestos · Rescate ·
Soporte · Médicos** (Safety Car y Track Sweeper a una capa nueva, "Soporte"), **cada una se prende
y apaga sola**, las vacías **no salen**, pantalla completa **también en el registro**, el admin
alineado; colores: se probaron banderas (blanca para rescate) y se eligió **puestos naranja de
overol, rescate amarillo**, soporte verde y médicos rojo; **color solo en borde y texto, relleno
para lo especial**; sin muestra de color en la barra (basta el color de la celda); pin "TÚ"
naranja. Implementación (solo app + admin, sin backend ni wire): paquete `app/…/ui/map`
(`TrackMap` movido de `CircuitScreens`, `MapLayers.kt` con `MapLayer`, `AssetType.layer()`,
`MapItem`/`Emphasis`, `trackMapItems`, `LayerBar`, `MapMemory` —capas por mapa y trazado elegido
del evento, en memoria—; `MapFullscreen.kt` con la barra de pantalla completa y
`TrackMapFullscreen`, que se dibuja ENCIMA de la pantalla para conservar lo capturado). Circuito:
barra de capas, lista con el marcador en miniatura y "Ubicar" (vuela; tocar en el mapa solo
elige), pantalla completa. Tab Puesto: barra, tu puesto relleno y el mapa abre sobre él; ⛶ abre el
Mapa en vivo con el MISMO trazado y capas. Mapa en vivo: barra de pantalla completa común, capas
arriba y el chip "N en vivo · Encuadrar". Registro: barra, pantalla completa con tocar-para-colocar
y "Listo"; al volver, la tarjeta se centra en lo elegido. La rueda del mouse acerca solo en
pantalla completa (en una tarjeta desplaza la página); el FAB de bitácora se oculta mientras hay
un mapa a pantalla completa; el crédito de OSM sube a la altura de los controles. Admin: colores
por capa en el editor OSM y en el detalle de evento (el estado va en el contador: ámbar con
oficiales, verde checklist completa) y leyenda nueva. Lección: el arnés de CDP (`cdp.mjs`) se
recreó en el scratchpad de la sesión; `Page.navigate` que solo cambia el hash no recarga la página
(el admin lee su clave al cargar: recargar tras guardarla).

**Sesión 2026-09-27 (7ª): preparar el repo para hacerlo público** ✅ Auditoría: gitleaks sobre
los 468 commits y el árbol (0 secretos); `setenv`, `data/privado/`, la llave de release y
`local.properties` nunca entraron a git; el CI solo corre con push a `master` o a mano (sin
`pull_request`) y el rol de AWS solo lo asume el entorno `produccion` (limitado a `master`).
Lo que sí había, y se corrigió: el nombre de una oficial real y números OMDAI de terceros (de
los avisos del Excel del roster), el nombre completo, OMDAI y puesto del autor, sus correos,
el usuario y portal de Identity Center y la IP del servidor en `CLAUDE.md`/`DESPLIEGUE.md`/este
historial, un nombre y un correo reales en dos mockups, una ruta con el home del autor en un
prototipo, y material de OMDAI versionado (las 90 posiciones del plano "Marshal Posts" del GP y
el mapeo de roles del "Track Personnel"). Decisiones: lo personal de operación va a
**`CLAUDE.local.md`** (gitignored; lo carga Claude Code igual que `CLAUDE.md`); el material de
OMDAI a `data/privado/` (`circuitos/posiciones/` y `eventos/roles-omdai.csv`) y el cargador lee
posiciones de ambas carpetas (`DIRS_POSICIONES`; un trazado vive en una sola) y el mapeo de
`MAPEO_ROLES` — `--solo-validar` idéntico antes y después (90 posiciones, 368 asignaciones);
atribución de OSM y de logos en `data/README.md`. El historial se **comprimió en un solo commit**
(autor = un correo de la cuenta de GitHub del autor, firmado con su llave SSH registrada ahí; `git
commit-tree` NO firma solo: `-S`) y `v1.5.0` apunta a él (lo usa `nueva-version.sh`). Respaldo del historial completo, fuera del repo:
`~/ws/el_puesto-historial-2026-09-27.bundle` (`git clone <bundle>` para consultarlo). Quedan
para el usuario: `androidApp/debug.keystore` sigue versionado (no afecta producción: los APK
publicados van firmados con la llave de release); y no hay LICENSE.

**Sesión 2026-09-27 (8ª): correo de contacto** ✅ Se pidió llevar a `hola@elpuesto.app` todas
las direcciones de contacto del sistema. Revisión: el código, la app y el sitio NO tenían
ninguna (solo remitentes: `noreply@elpuesto.app` en producción y el de desarrollo en `setenv`);
las únicas vivas están en AWS (`ALERT_EMAIL`, las suscripciones SNS de `9-auditoria.sh` y la
alerta de anomalías de costo) y el usuario decidió **dejarlas como están**. Ojo: las 3
suscripciones SNS de auditoría siguen en `PendingConfirmation` (esos avisos no le llegan a
nadie). Hecho: `hola@elpuesto.app` como contacto visible (`mailto:`) en el pie de la landing y
al final de `/descargas/` (verificado en headless a 1280 y 390 px, sin scroll horizontal).
