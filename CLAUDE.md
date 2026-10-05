# El puesto — memoria del proyecto

App Android para **oficiales de pista** (deporte motor mexicano). Sin afiliación con ninguna organización; "OMDAI ID" se conserva solo como nombre del campo de número de oficial. Los ayuda
antes y durante los eventos: asignación en pista, compañeros de puesto, cronograma, chats,
catálogos de circuitos y campeonatos, convocatorias, agenda y planeación de viaje.

Este archivo es el **contexto persistente** entre sesiones. Complementos:
`docs/handoff/` (A tema · B datos/API · C pantallas · D plan), `docs/design/` (mockups),
`docs/DEUDA-TECNICA.md` (detalles/pulido diferidos, para no atorar lo importante) y
`docs/IDEAS.md` (**features grandes anotados para el futuro**; revisar al planear una sesión) y
**`docs/HISTORIAL.md`** (bitácora de todas las sesiones: el porqué de cada decisión).
**Compilar/correr/desplegar en OTRA máquina** (sin las rutas de §5): `docs/COMPILAR-Y-DESPLEGAR.md`
(también enlazada desde `AGENTS.md` y el README); producción en AWS: `docs/DESPLIEGUE.md`.

---

## 1. Decisiones de producto (fuente de verdad)

- **Plataformas:** app de oficiales = **Android + web** (decisión 2026-09-26: la MISMA app, compilada también al navegador con Kotlin/Wasm y servida en `/app/`; lo que el navegador no puede —servicios, notificaciones, ubicación propia, recordatorios, actualizaciones— se **oculta**, nunca se muestra inerte); interfaces de **admin = web** (aparte). Idioma **solo español (MX)**.
- **NO es una red social — privacy-first (principio central).** Lo "social" (chat, perfiles) existe **solo** al servicio de la actividad en pista: **sin amigos/conexiones, sin mensajería directa, sin grafo social**. Los **chats privados** (2026-09-25) no rompen esto: son grupos con nombre por invitación que el invitado ACEPTA, sin botón de "mensaje" en los perfiles ni lista de amigos. Acceso a datos privados (emergencia) **registrado y auditable**, conocido por ambas partes.
- **Navegación conectada:** todo elemento que representa un objeto lleva a su detalle (listas de oficiales → perfil; circuitos/campeonatos → su detalle), salvo que haya una acción más apropiada.
- **Registro:** por **invitación entre pares** (un oficial registrado invita a otro; se registra quién invita a quién; el invitado recibe un **correo con cómo instalar y entrar** — 2026-10-04: solo si la invitación creó su cuenta y aún no entra, reenviable una vez al día) **+ aprobación manual del admin**. Estados: invited → pending_approval → active → suspended. **Actualizar tus datos NO requiere aprobación** (la aprobación es a nivel cuenta).
- **Login:** **magic link por email** con deep link a la app (sin contraseñas). No passkeys.
- **Datos mínimos del oficial:** OMDAI ID (número) + display name + email (email privado, solo auth/notificaciones) + avatar opcional (usado en todo el sistema).
- **Perfil:** una sola **"Área asignada"** a la vez (**Intervención, Comunicación, Recovery, Escrutinio, Médico** — corregidas por el usuario 2026-08-01; distinto del rol operativo de la asignación en un evento). **Sin "certificaciones"** (no hay fuente autoritativa). Info de **emergencia** = contacto + tipo de sangre + alergias (**sin seguro**), **privada pero NO cifrada** (decisión 2026-09-25: no lo amerita; la protección es el acceso restringido y auditado — la app no debe prometer cifrado), acceso restringido (solo el jefe de puesto durante un evento activo) y **cada acceso queda registrado**. Historial de eventos.
- **Modo evento:** se "entra" desde el Home; navegación interna por segmento (Puesto / Cronograma / Chat). Incluye asignación, compañeros + jefe de puesto, checklist por puesto, cronograma con estado en vivo, chat de evento y de puesto. **El GPS/compartir ubicación NO vive aquí** → va en Configuración.
- **Circuitos (independientes de los eventos):** un autódromo tiene **varios trazados/configuraciones (hasta ~12)**; distancia, curvas y **puestos** dependen del trazado → elegir el trazado es la primera decisión. El mapa muestra **solo posiciones** de puestos y activos (**grúas, ambulancias**…), con **capas** (ver "Mapas") y lista con scroll bajo un mapa fijo. Escala objetivo: **~50 puestos + ~docena de activos**. No hay pantalla de detalle de puesto. Marca "Asignado antes" desde el historial. Offline.
- **Mapas (homologados, decisión 2026-09-27, canvas `docs/design/Mapas homologados/`):** el MISMO mapa en Circuito, tab Puesto, Mapa en vivo y Registro por honor. **Capas** Puestos · Rescate (HIAB, IFRT, Telehandler, Driver Rider) · Soporte (Safety Car, Track Sweeper) · Médicos (ambulancia) que se prenden y apagan **solas** (barra con conteo bajo el mapa en la tarjeta, arriba en pantalla completa; **las vacías no salen**); lo que ocultas se conserva al pasar a pantalla completa (solo en memoria). Colores de pista: **puestos naranja de overol, rescate amarillo, soporte verde, médicos rojo**; el azul queda para las personas. **El color va en el borde y el texto del marcador; el relleno solo para lo especial**: tu puesto (con halo, siempre visible aunque apagues su capa, y el pin "TÚ" en su naranja), lo que tocas (dice qué es) y "asignado antes" en Circuito. Controles en las mismas esquinas: pantalla completa arriba a la derecha (salir, en el mismo lugar), zoom abajo a la derecha (ver todo · acercar · alejar), © OSM abajo a la izquierda; en la web la rueda del mouse acerca en pantalla completa. El admin usa los mismos colores por capa (la abreviatura dice el tipo).
- **Campeonatos:** solo lectura. **Campeonato** (la serie: Fórmula 1, Fórmula E…; dueño del logo) → **temporadas** ("2026", "2025-26"; decisión 2026-09-25) → categorías; de la categoría dependen posiciones (leaderboard), calendario de fechas y pilotos. Nombres en el API: campeonato = `series`, temporada = `championship` (los `championshipId` de categorías/eventos/agenda son ids de TEMPORADA). Datos por **API de ingesta (sistema externo empuja)** + captura de admin. **Todos los oficiales ven todos los campeonatos** (decisión 2026-09-25: no hay "seguir"). **Fotos de pilotos** (2026-09-27): miniatura en Posiciones (el número de coche pasa a una pastilla junto al equipo) y, al tocar la fila, la foto en grande; más adelante, una pantalla de detalle del piloto (`docs/IDEAS.md`). **Posiciones automáticas** (2026-09-25): al terminar cada fecha, un job del backend jala la tabla SOLO de la categoría afectada de su fuente externa. **Decisión 2026-09-27**: se obtienen aunque haya que **leer las páginas públicas** de cada campeonato (se revierte el "descartado"); una categoría a la vez, sin llaves de API. Si la página tiene un reto anti-bots (NASCAR: Cloudflare) **no se evade**: se usan los archivos públicos que esa página descarga (sin llave ni reto), con el visto bueno del usuario. **México Racing Cup** (2026-09-27): su tabla es una IMAGEN; se publica la LECTURA guardada (posición impresa, puntos enteros), ST Light = 2 categorías (Light 1 y Light 2) y TCR = solo "Campeonato General (Sprint)".
- **Convocatorias:** solo consulta (re-publicadas de un sistema externo; postulación **fuera de la app**). Campos que sí tenemos: nombre/fecha/lugar del evento, **fin de inscripciones**, **cupo** (número único) e **indicaciones generales** (texto libre Markdown). Historial de pasadas en pantalla aparte.
- **Agenda:** combina eventos y convocatorias (del sistema) con **planeación de viaje personal** (transporte/hospedaje/recordatorios) ligada a un evento. **Decisiones 2026-09-25**: los eventos operativos aparecen SOLO si tienes asignación o registro por honor ("Trabajas") y se funden con su fin de semana del calendario (los eventos con autoregistro que no caen en ningún fin de semana también aparecen, para poder registrarse); la planeación (y notas/fotos) se liga a UNA cosa: evento que trabajas, **carrera del calendario** (aunque no la trabajes) o convocatoria; hora de fin opcional (llegada del transporte / salida del hospedaje).
- **Chats:** públicos (cualquiera crea/se une), **privados** (grupo con nombre de 2+ por invitación; el invitado **acepta** antes de entrar; solo sus miembros lo ven y no aparecen en Explorar — decisión 2026-09-25), de evento y de puesto (restringidos). Un público o privado se puede **ligar a un evento que trabajas** (lo cambia solo su creador): aparece en el Modo evento de ese evento y **sigue vivo** cuando el evento termina. Los de evento se **archivan a la semana** (solo lectura, en lugar separado). Moderación básica (reportar; también en privados, y la UI lo dice).
- **Compartir ubicación (Configuración):** opt-in, transparente, activo **solo durante eventos**, mediante una **lista de permisos que el usuario controla** (a quién comparte / quién le comparte). No es un grafo social. **Decisiones 2026-09-24**: la ve SOLO la allowlist del emisor (ni Control ni el jefe por su rol); allowlist **permanente filtrada por evento** (solo fluye entre asignados al evento activo); el receptor recibe **aviso** y puede **ocultar** (vista propia, no revoca); pins en el **"Mapa del trazado" del tab Puesto** + lista ("fuera del circuito" sin pin); solo la **última posición, en memoria** (>2 min atenuada, >10 min fuera, purga al desactivar el evento); ~15 s; sin encolar offline; servicio en primer plano con notificación "Pausar" arrancado con la app en uso (**sin** ubicación en segundo plano). **Solo cerca del circuito** (2026-09-25): zona = caja de los trazados del evento (su dibujo; sin dibujo, sus posiciones) + 500 m (`GeoFence` en shared, viaja en `LocationSharing.fence`); fuera de ella el teléfono NO envía (y retira la última) y el servidor descarta lo que llegue (segundo candado); circuito sin mapa = no se comparte.
- **Logros (gamificación, decisiones 2026-09-25)**: incentivar que el oficial vuelva a la app sin romper "no es red social". **Sin rankings ni comparaciones** entre oficiales. Todo se **DERIVA al leer** de datos existentes (nada se captura). Formas: **medalla** (niveles bronce/plata/oro), **hexágono** (hito o contador), **parche** (campeonato) y **sello** (primera vez). **Visibilidad**: pasaporte, campeonatos y carrera en pista se ven en el perfil ajeno; **operación y primeras veces, solo el titular**. Umbrales: Pasaporte México 3/5/8 circuitos · Días en pista 10/50/100 (el día marcado ausente no cuenta) · Versatilidad 2/4/6 **familias de rol** (banderas, intervención, bomberos, comunicación, unidades, jefatura) · Antigüedad 5/10/20 años **solo de «Activo desde»** (sin dato = sin medalla). Primeras veces **sin ubicación ni checklist** (no premiar prender el GPS ni palomear; el checklist cuenta EN EQUIPO: "Puesto impecable"). Catálogo de circuitos: **sello ✓** (solo sí/no) en vez del chip TRABAJAS. **Arranque en cero aceptado** (sin cargar rosters históricos); lo nutre el **registro por honor** (abajo).
- **Registro por honor (decisiones 2026-09-25)**: la app es **bitácora personal, no sistema de control** — el oficial declara "yo trabajé este evento" sin depender del admin. **Bandera por evento** `selfRegistration`, **PERMITIDO por defecto** (se cierra en los pocos con roster completo, p. ej. el GP). Solo en **eventos que creó el admin** (no se crean eventos desde la app); se ofrece desde la **agenda** (fin de semana del calendario fundido con el evento, o el evento suelto si no cae en ninguno), a partir del **primer día** del evento y sin límite hacia atrás. Preguntas: **posición** (opcional: "no aparece / sin puesto fijo"), **rol** del catálogo (incluidos los de **jefe**: sistema de honor, confiar en el oficial) y **días**; al registrarse, **mensaje del sistema de honor**. Vive **APARTE de las asignaciones** para **no heredar permisos** (Modo evento, chats, emergencia, pase de lista y ubicación salen solo del roster); cuenta **igual** para historial, pasaporte, logros (también públicos), eventos en común y agenda; si el roster incluye al oficial, **el roster manda**. El admin ve los registros y puede quitar uno. **Puestos aportados** (fase 3): si el trazado no trae su puesto, el oficial lo coloca en el mapa con su número y queda **pendiente de verificación del admin** (solo él lo ve) antes de publicarse al resto.
- **Seguridad de la API (decisión 2026-09-26)**: para frenar el abuso se registra quién hace
  cada petición (el oficial, o la IP sin sesión) por **30 días**; el principio de privacidad
  no aplica a esa telemetría de seguridad (no es dato sensible del oficial). Bloque 3
  (CloudFront + WAF delante del API) **pospuesto por costo**.
- **Offline-first** (crítico por la mala conectividad en pista) y **notificaciones** (incluida una **persistente** con la actividad actual del cronograma).
- **Actualizaciones de la app (decisión 2026-09-26)**: sin Google Play, la app se actualiza
  sola **con confirmación** ("opción 2"): lee `https://elpuesto.app/version.json`, avisa en el
  Inicio y en Configuración, descarga y verifica el APK y **Android pide confirmar** con un
  toque. La actualización totalmente silenciosa (Android 12+) se dejó para después
  (`docs/DEUDA-TECNICA.md` → "Actualizaciones de la app"): instalar cierra la app y no debe
  pasar a media actividad en pista.
- **Seguridad y convivencia (decisiones 2026-09-26, auditoría completa)** — "la app lo más
  segura posible":
  - **Nadie entra a un grupo sin aceptar**: invitar a un chat PÚBLICO también es invitación
    (revierte la "libre unión" de 2026-08-02); quien rechaza o sale no vuelve a ser invitado
    a ese chat.
  - **Bloquear** a otro oficial: ya no puede invitarte ni compartirte su ubicación (sin aviso);
    sus mensajes se ven colapsados. Reportar mensajes, **chats** (nombre/imagen/descripción) y
    **perfiles**.
  - **Búsqueda de oficiales sin correo** (revierte "correo por igualdad exacta" de
    2026-07-28): nombre (3+ letras) u OMDAI ID (3+ dígitos), máx. 10.
  - Nombres reservados/invisibles prohibidos (`NameRules` en shared: ni "Tú" ni "Control"…).
  - El enlace mágico **solo sirve en el teléfono que lo pidió** (PKCE); cambiar de cuenta pide
    confirmación y borra lo local.
  - El admin vive en **su propio host** (`admin.elpuesto.app`) y su clave en `sessionStorage`
    (se pega en cada sesión del navegador).
  - Pasaporte/logros ajenos solo con el **año**; un registro por honor no destapa el rol ni
    el puesto de otros en "eventos en común".

## 2. Diseño

- Dirección visual elegida: **"1a Paddock nocturno"** — oscuro (no negro): carbón `#1E222B`/`#191C24`, panel `#242833`, borde `#333949`; acento **ámbar `#F2B134`** (motivo bandera a cuadros); verde `#3FDD85` (EN CURSO), rojo `#F0524D` (error), azul `#7FB7F0` (viajes). Tipografía: **Archivo** (display) + **IBM Plex Sans** (cuerpo) + **IBM Plex Mono** (labels/horas).
- **Logo real (2026-08-01)**: corredor con casco en ámbar (fuente del usuario; copia en
  `docs/design/logo-source.png`, PNG 732×1024 transparente — el SVG original era un raster
  incrustado, no vectorial). Generado con ImageMagick: **adaptive icon** de la app (fondo
  carbón `#1E222B` + figura a 60dp; `mipmap-anydpi-v26` + foregrounds por densidad +
  `monochrome` para temas Android 13), marca en `app/src/commonMain/composeResources/drawable/logo_mark.png`
  (la usa `AccessScreen` en Android y web; antes `drawable-nodpi`), `adminweb/logo.png` + `favicon.png` (la clase `.brand-flag` ahora pinta
  el logo) y `docs/design/playstore-icon-512.png` (sin transparencia, para Play Store).
- Mockups en **Claude Design**, proyecto `9813816d-ac58-4f5b-9657-86ed29c10dcd`; copia versionada en **`docs/design/`**. Tokens en `docs/handoff/A-compose-theme.md`.

## 3. Arquitectura

Monorepo Gradle KMP:
- **`shared/`** — modelo de dominio + contratos (`@Serializable` + `@ProtoNumber`). Targets Android + JVM (lo reusa el backend) + wasmJs (la web). **Sin datos de prueba** (SeedData se eliminó 2026-09-24).
- **`app/`** (desde 2026-09-26) — la app de los oficiales **común a Android y web** (Compose Multiplatform 1.11, targets android + wasmJs): TODAS las pantallas (`ui/`), el tema, `OfflineRepository`, `HttpRepository` (Ktor 3) y `Auth`. Lo de cada plataforma entra por interfaces: `LocalDb`/`ImageCache`/`LocalPrefs`/`RepoPlatform` (repositorio), `AuthStore` (sesión) y `AppPlatform` por `LocalAppPlatform` (notificaciones, recordatorios, ubicación, barra del evento, actualizaciones — `supported = false` = la UI lo oculta); lo que solo necesita el SDK va por `expect/actual` en `ui/platform/PlatformUi.kt` (atrás, cámara/galería/recorte, enlaces, avisos, exportación, selectores de fecha/hora, decodificar imágenes). `commonMain` NO puede usar `android.*` ni `java.*`.
- **`data/`** — **datos REALES versionados** (CSV auditables: `circuitos/circuitos.csv`, `campeonatos/<año>/*.csv` + `FUENTES.md`; formato en `data/README.md`) que se suben con **`scripts/cargar-datos.py`** por la API admin (idempotente por llave natural, `--dry-run`, `--solo-validar`). `data/privado/` (cuentas/oficiales) está en `.gitignore`.
- **`backend/`** — Ktor + Netty. Sirve `shared` sobre **protobuf** (y JSON para debug/externos). **Todo en Postgres** (Exposed + Hikari): auth (cuentas/tokens) y **dominio en tablas normalizadas** (`DomainTables`/`DomainRepository`, fechas como ISO-8601); **arranca vacío** (sin siembra: los datos llegan por la API admin desde `data/`). Además la **API administrativa JSON** (`AdminRoutes`/`AdminRepository`/`AdminSupport`, ver abajo).
- **`androidApp/`** — la app Android sobre `app/`: `MainActivity`, servicios (ubicación, barra fija, recordatorios, notificaciones), actualizaciones, `SqlDelightLocalDb` (el `LocalDb` de Android: **SQLDelight** con migraciones, fuente de verdad de la UI), `KeystoreAuthStore`, `AndroidPrefs`, `ImageStore`, `AndroidAppPlatform`; todo se arma en `AppGraph`. **Offline-first** con **outbox**; cliente Ktor con **protobuf + Bearer JWT**.
- **`webApp/`** — la app web (Kotlin/Wasm) sobre `app/`: `Main.kt` (monta la app, fuente de respaldo, columna de 560 dp, enlace `#auth=`) y `WebStorage.kt` (caché/cola en memoria, `WebAuthStore`, `WebPrefs`). Compila a estáticos que el backend sirve en `/app/` (`WEB_APP_DIR`).
- **`adminMcp/`** — servidor **MCP por stdio** para que agentes de AI administren los datos: capa delgada (JVM puro, sin deps de red salvo `java.net.http`) con 71 tools en español sobre la API `/admin/*`. JSON-RPC implementado a mano (el SDK oficial exige Ktor 3 y el monorepo está en Ktor 2). Registrado en **`.mcp.json`** del repo (`./adminMcp/run.sh`).
- **Admin web (humanos)** — SPA **vanilla** (HTML/CSS/JS, sin npm ni build) en `backend/src/main/resources/adminweb/`, servida por el mismo backend en **`/admin/ui/`** (mismo origen → sin CORS). Login = pegar la X-Admin-Key (sessionStorage — se olvida al cerrar la pestaña —, validada con `/admin/whoami`; en producción en su host propio `admin.elpuesto.app`, env `ADMIN_HOST`); el nav se filtra por scopes. Tema Paddock nocturno. Secciones: cuentas (aprobar), oficiales (+avatar), eventos (+MbM/checklist/asignaciones/compañeros bulk, activo), circuitos→trazados→puestos/activos, campeonatos→categorías→posiciones/calendario/pilotos (+logo), convocatorias, agenda global, claves+auditoría (secreto mostrado una sola vez).

**Interfaz administrativa (decisión 2026-07-28): API-first, agentes como consumidores primarios.**
Una sola API JSON `/admin/*` (misma fuente de verdad para agentes vía MCP, el futuro admin
web y curl). **IDs de entidad = UUIDv7 asignados SIEMPRE por el servidor (decisión
2026-07-31)**: crear = `POST /admin/<entidad>` sin `id` (mandarlo = 400; la respuesta trae
el UUID), actualizar = `PUT /{id}` con id existente (404 si no existe — ya no crea);
cuentas siguen con upsert por **email**. Los IDs nunca los elige el usuario y **no se
muestran en ninguna UI** salvo razón clara (auditoría, hash de URL del admin web). Generador
en `backend/Ids.kt` (`uuidv7()`). **Bulk = reemplazo total** (standings/rounds/drivers/
puestos/assets/sessions/checklist/assignments/mates; los ids de fila los genera el backend),
**`?dryRun=true`** para validar sin
escribir, errores accionables `{error, field, hint}`, borrados con cascada manual y rechazo
si hay referencias. Auth por **`X-Admin-Key`**: master key (env `ADMIN_API_KEY`) o **keys
nombradas con scopes** (tabla `admin_api_keys`, SHA-256; se crean/revocan por la API; el
nombre identifica al agente). **Toda mutación queda auditada** (`admin_audit`: actor,
acción, entidad, detalle; `GET /admin/audit`). Documentación para agentes en
**`GET /admin/docs`** (markdown embebido: `backend/src/main/resources/admin-api.md` —
mantenerlo al cambiar la API). Logos/avatares por el pipeline de imágenes
(`POST /admin/images/{championship|circuit|avatar}/{id}`, actualiza `emblemUrl`/`avatarUrl`).

**App web (decisiones 2026-09-26)** — misma app que Android (`app/`), cliente `webClient`:
- **Sesión**: access token SOLO en memoria; el refresh vive en la cookie **`ep_rt` HttpOnly**
  (`Path=/auth`, `SameSite=Strict`, `Secure` salvo dev) que pone el backend cuando la petición
  lleva `X-El-Puesto-Client: web` (anti-CSRF: cabecera propia + `Origin` del mismo host). Al
  recargar, `/me` → 401 → refresh por cookie. En `localStorage` solo lo no secreto (marca de
  sesión, último estado) y el **verificador PKCE** (el correo se abre en OTRA pestaña; la que
  esperaba recarga sola por el evento `storage`). Enlace del correo: `<WEB_APP_URL>#auth=<token>`
  (fragmento: no llega al servidor); `MagicLinkRequest.client = "web"`.
- **Caché y cola SOLO en memoria** (`MemoryLocalDb`): privacidad en computadoras compartidas;
  recargar vuelve a pedir todo. Imágenes en memoria (60 MB).
- **Tiempo real**: el WebSocket del navegador no manda Bearer → `POST /stream/ticket` (boleto
  de un solo uso, 60 s) → `WS /stream/web?ticket=` (misma sesión que `/stream`).
- **Mismo origen** que el API (`/app/` en el backend): sin CORS; CSP propia de `/app`
  (`script-src 'self' 'wasm-unsafe-eval'`, sin `unsafe-eval` → solo la distribución de
  producción de webpack). El host del admin no sirve `/app`.
- **UI**: "atrás" del navegador y Escape = gesto atrás (guarda en `history`); fotos por
  `<input type=file>` + canvas (EXIF, escala, recorte al centro); exportación = descarga del
  ZIP armado en memoria; fechas/horas con los selectores de Material 3; zonas horarias con
  `@js-joda/timezone`; **DejaVu Sans precargada ANTES de dibujar** (la fuente de Compose en la
  web no trae flechas/✓/✕/⚙: texto medido antes no se re-mide). Sin emoji de color (ver
  DEUDA-TECNICA → "App web").

Decisiones técnicas clave:
- **Wire = protobuf** (kotlinx.serialization, sin `.proto`/codegen; ambos extremos comparten `shared`). Ojo: protobuf no tiene null → **`ProtoBuf { encodeDefaults = false }`** en ambos lados y **todo campo nullable con default `= null`**.
- **Cliente HTTP (app):** **NO** usa el ContentNegotiation de Ktor (corrompe el binario por `charset=UTF-8`). En `HttpRepository` se manda `Accept: application/protobuf` explícito y se decodifica desde **bytes crudos**. Ver `docs/DEUDA-TECNICA.md` y la memoria del proyecto.
- **Auth:** magic link → **access token (15m) + refresh token (60d, en Postgres, rotación de un solo uso)**; secreto y TTLs desde config (env). Las rutas de datos exigen Bearer; la app renueva el access **transparente** ante 401 (manual) y reintenta. Estados de cuenta gatean la UI.
- **Offline:** SQLDelight (entidades como blobs protobuf; checklist en columnas) + `refresh()` sincroniza + outbox drenado best-effort. **Imágenes** en archivos (`ImageStore`, filesDir/images: bytes + ETag + marca "no existe"), lo guardado PRIMERO y revalidación con 304 en segundo plano; memoria de bitmaps en `ImageMemory` (ver §6). **Catálogos** (circuitos/campeonatos/convocatorias, y todo lo que vive en la tabla genérica `catalog(key,data)`: perfiles, historial, chats, mensajes, trazados/puestos, asistencia, bitácora…) **caché primero con revalidación, centralizado en el repositorio** (2026-09-25, 11ª): TODA lectura cacheable pasa por UNA puerta (`OfflineRepository.cachedValue`): lo guardado sale al instante y la red revalida en segundo plano (≤1 vez cada 10 s por llave; `refresh()` limpia ese freno). TODA escritura a la caché pasa por UNA puerta (`storeCatalog`, o `refetch` tras una escritura en el servidor) que, si el dato cambió, avisa por `catalogChanges()`; las pantallas que leyeron esa llave con `Reloader.track` releen solas (revalidación, acción propia, outbox drenado, aviso en vivo — todo llega igual). Red primero solo sin nada guardado o dentro de `freshReads { }` (jalar para refrescar, recargas por avisos en vivo WS/SSE, decidir si notificar): red → caché → **vacío** (nunca datos inventados; `HttpRepository` es estricto y lanza sin red). Sin caché por decisión de producto: emergencia consultada por el jefe (auditada), ubicaciones vivas (solo memoria), búsqueda de oficiales y exportación. No se hizo en un interceptor HTTP: una petición tiene UNA respuesta y no puede entregarle a la pantalla la segunda (la fresca); además la caché guarda objetos del dominio con lo encolado superpuesto, por encima de HTTP.

## 4. Estado actual (2026-09-27)

La bitácora sesión por sesión (qué se decidió y por qué, verificaciones, lecciones) está en
**`docs/HISTORIAL.md`**. Aquí va lo vigente. Al cerrar una sesión: una entrada breve en
`docs/HISTORIAL.md` y actualizar ESTA sección solo si cambió lo vigente.

**Versiones en producción**: Android **1.5.0** (versionCode 9; tag `v1.5.0`); web
`https://app.elpuesto.app` (viaja con cada despliegue del backend); API `https://api.elpuesto.app`;
admin `https://admin.elpuesto.app/admin/ui/`; sitio y descargas `https://elpuesto.app`.

**Lo que existe (todo en producción salvo que se diga):**
- **App** (Android + web, `app/`): shell Inicio/Agenda/Chats + pila de overlays (`ElPuestoApp.kt`,
  `Overlay.*`); Home (evento activo, agenda próxima, Explorar); **Modo evento** (Puesto con mapa del
  trazado y ubicaciones en vivo, MbM en vivo, Chat, Bitácora; checklist diario por puesto, pase de
  lista del jefe); **Agenda** ("lo que viene", tocar un día filtra, detalle "Tu fin de semana",
  planeación de viaje, notas y fotos, registro por honor); **Chats** (públicos, privados con
  invitación, de evento y de puesto; tiempo real por WS; moderación, bloqueos, reportes; tocar
  imágenes las abre en grande); **Perfil** (emergencia auditada, historial DERIVADO, eventos en
  común, logros fase 1, pasaporte); **Circuitos** (galería, mapa con zoom, "Tu historia aquí");
  **Campeonatos** (campeonato → temporadas → categorías; Calendario y Posiciones con foto del
  piloto, número en pastilla y foto en grande); Convocatorias; Configuración (compartir
  ubicación, notificaciones, descargar mis datos, invitar, bloqueados, actualizaciones).
  Offline-first (outbox + caché primero, ver §3/§6), notificaciones locales (chat, avisos,
  recordatorios, barra "Evento en curso" visible en bloqueo) y actualización desde la app.
- **Backend**: Postgres (esquema al arrancar, sin datos de prueba), API de la app en protobuf, API
  admin JSON + `admin-api.md` + `admin-openapi.json` (mantener ambos a mano), MCP (`adminMcp`, 71
  tools), admin web; tiempo real (`ChangeBus` → WS `/stream` con `StreamPolicy`, SSE de evento y
  admin); imágenes (`ImageService`, kinds avatar/chat/trip/event/circuit/series/trazado/**driver**);
  seguridad (`Sessions`, `Abuse`/`RateLimits`/`Quotas`, `WebSecurity`, `Operations`, PKCE); export
  de datos; logros; registro por honor y puestos propuestos; ingesta de posiciones (abajo).
- **Datos** (`data/`, cargados en producción): 83 circuitos (96 trazados, 88 con dibujo), calendarios
  2026 de F1 (+F2, F3, F1 Academy), Fórmula E (2025-26 y 2026-27), IndyCar (+Indy NXT), NASCAR
  (Cup, O'Reilly, Truck), WEC (Hypercar, LMGT3), WRC (+WRC2, WRC3, Junior WRC), NASCAR México
  (3) y México Racing Cup (8, con Súper Turismos Light 1 y 2); el GP de México 2026 con roster
  (privado). Faltantes de dibujos/logos: `data/circuitos/PENDIENTES.md`.

**Posiciones automáticas** (`backend/StandingsIngest.kt` + un archivo por fuente; job cada 15 min
que persigue la última fecha terminada en UTC, reintenta y confirma ~3 días después; "la fuente
ya va adelante" = "aún no"). 24 categorías configuradas en `data/campeonatos/fuentes-posiciones.csv`;
investigación y rarezas de cada una en `docs/fuentes-posiciones/`:

| Fuente | Categorías | Notas |
|---|---|---|
| `jolpica-f1` | F1 | API pública; fotos de formula1.com |
| `fiaformula2-web` / `fiaformula3-web` | F2, F3 | JSON que Next incrusta en su página (`FiaSeriesSite`); fotos con cara (Cloudinary) |
| `formulae-web` | Fórmula E (2 temporadas) | HTML + flight de Next; temporada N = año inicial − 2013 |
| `nascar-feed` (param 1/2/3) | Cup, O'Reilly, Truck | JSON de cf.nascar.com (www.nascar.com tiene reto: no se evade); sin fotos |
| `fiawec-web` (param hypercar/lmgt3) | Hypercar, LMGT3 | HTML con Jsoup; campeonato de PILOTOS, una fila por tripulación |
| `wrc-feed` (param wrc/wrc2/wrc3/jwrc) | 4 del WRC | JSON de Red Bull; 0 puntos fuera; en WRC el rally debe sumar 130 |
| `indycar-feed` (param indycar/indynxt) | IndyCar, Indy NXT | `/api/results/*`; equipo corto de /standings (si falla, error) |
| `mexicoracingcup-img` (param página `33-…`) | 8 de la MRC | tabla = IMAGEN: se publica la LECTURA guardada (`PUT /admin/categories/{id}/standings-reading`, versionadas en `data/campeonatos/2026/posiciones-imagen/`); si el sitio sube otra imagen, la ingesta avisa que falta leerla |

Sin fuente: F1 Academy, NASCAR México (tabla en imagen detrás de un reto: necesita la pantalla
de subir imagen) y la temporada de F. E 2026-27 hasta que empiece. Fotos de pilotos: se bajan UNA
vez (`DriverPhotos`, lista blanca de hosts) y se sirven como kind `driver`.

**Procedimientos y avisos vigentes** (lecciones; el detalle en `docs/HISTORIAL.md`):
- **Probar contra una COPIA de la base**: `CREATE DATABASE el_puesto_x TEMPLATE el_puesto` (falla si
  alguien está conectado a `el_puesto`: crea las copias ANTES de arrancar el backend local) y
  backend en otro puerto con `set -gx DB_URL …/el_puesto_x` y `set -e SMTP_HOST` (si no, manda
  correos reales). Activar un evento en una base real avisa a TODOS los teléfonos conectados.
- **Cargador en producción** (`scripts/prod.sh`): para catálogos, correrlo sobre una copia de
  `data/` SIN `eventos/` ni `privado/` (el completo reemplaza asignaciones
  de eventos con roster y re-escribe la cuenta privada); siempre `--dry-run` antes. Renombrar
  una categoría (o sede) = PUT por la API ANTES del cargador, para conservar ids (las carreras
  tienen id estable y la planeación de los oficiales se liga a ellas).
- **Versión nueva de la app**: `scripts/nueva-version.sh [patch|minor]` (push + espera el despliegue
  + APK + tag). Notas redactadas a mano sin editor interactivo: `VISUAL=<script que copia tu
  archivo de notas sobre "$1">` y `--si`. La sesión de AWS (SSO, 4 h) debe estar viva.
- **Push a `master` despliega** backend + web (GitHub Actions). Si otra sesión trabaja en el mismo
  repo, sus commits locales anteriores a los tuyos SUBEN con el push: revisa
  `git log origin/master..HEAD` y commitea solo tus archivos.
- **Subagentes en paralelo**: `isolation: worktree`, cada uno con su archivo, su copia de la base y
  su puerto; integrar por cherry-pick (los choques típicos son una línea de lista).
- **Repo PÚBLICO** (`github.com/cultome/el_puesto`, desde 2026-09-27: recreado con el historial
  comprimido en un solo commit; lo que un push publica ya no se puede retirar): en git NADA de
  personas (correos, nombres de oficiales, números OMDAI, puestos en rosters) ni material de
  OMDAI (posiciones del plano "Marshal Posts", mapeo de roles): va en `data/privado/`. Lo
  personal de operación (cuentas, usuario y portal de AWS, IP) vive en **`CLAUDE.local.md`**
  (gitignored). Antes de cada push: `git grep` de correos y nombres reales. Commits firmados
  (SSH, `commit.gpgsign`); `git commit-tree` solo firma con `-S`.

**Pendientes vivos** (lo menor, en `docs/DEUDA-TECNICA.md`; lo grande, en `docs/IDEAS.md`):
- Posiciones: NASCAR México (pantalla "Importar posiciones desde una imagen"); lectura AUTOMÁTICA
  de las imágenes de la MRC (llave de la API de Anthropic); separador "The Chase" en NASCAR;
  pantalla de detalle del piloto; F1 Academy.
- Push con la app cerrada (FCM; requiere proyecto de Firebase del usuario); video en chats.
- Logros fases 2 (medalla nueva) y 3 (recuerdo de hace un año, "Tu temporada"); compartir
  bitácora (espera feedback de usuarios); Live Updates de Android 16 para el MbM.
- Operación: `AUTH_REQUIRE_PKCE=true` cuando la mayoría tenga ≥ 1.1.2; DMARC en Namecheap.

## 5. Build & run (entorno de esta máquina — CachyOS)

- **JDK 21**: `~/.jdks/jdk-21.0.11+10` (Temurin, tarball de adoptium). **Android SDK**: `~/Android/Sdk` (platform 35, build-tools 34/35, emulator, system image `android-35;google_apis;x86_64`; **usamos SDK 35 + AGP 8.7.2**). AVD `Pixel_10` creado con el perfil `pixel_9` (el cmdline-tools no trae Pixel 10). Montaje de máquina nueva (2026-09-24): bajar JDK y `gradle-8.10.2-bin.zip` a esas rutas, `sdkmanager --install` de lo anterior, `avdmanager create avd`, `local.properties` con `sdk.dir`.
- **Gradle**: el wrapper existe pero su **descarga del distribution es inestable en esta red**; hay un gradle standalone en **`~/gradle-dist/gradle-8.10.2/bin/gradle`** (usar ese). `local.properties` (sdk.dir) NO se versiona.
- Compilar app:  `JAVA_HOME=~/.jdks/jdk-21.0.11+10 ANDROID_HOME=~/Android/Sdk ~/gradle-dist/gradle-8.10.2/bin/gradle :androidApp:assembleDebug`
- Compilar la web: `… gradle :webApp:wasmJsBrowserDistribution` (producción; la de desarrollo usa `eval` y la CSP la bloquea) → correr el backend con `WEB_APP_DIR=$PWD/webApp/build/dist/wasmJs/productionExecutable` → `http://localhost:8080/app/`. Los archivos se leen del disco en cada petición: recompilar no exige reiniciar el backend. Probar en navegador: Chromium headless de Playwright (`~/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell --remote-debugging-port=9333`) + CDP por el WebSocket nativo de Node 24; `Page.setInterceptFileChooserDialog` + `DOM.setFileInputFiles` para las fotos. Las descargas no se guardan en el headless: interceptar `URL.createObjectURL`.
- **Postgres (auth)**: `docker compose up -d` levanta un Postgres dedicado en **`:55433`** (db/usuario/pass `el_puesto`/`el_puesto`/`el_puesto_dev`). El backend crea el esquema al arrancar (sin datos). Debe estar arriba **antes** del backend. **Base desde cero**: `docker compose down -v && docker compose up -d` → arrancar backend → `fish -c 'source ./setenv; python3 scripts/cargar-datos.py'`.
- Correr backend:  `~/gradle-dist/gradle-8.10.2/bin/gradle :backend:installDist` → `JAVA_HOME=~/.jdks/jdk-21.0.11+10 backend/build/install/backend/bin/backend` (puerto 8080). Config por env (defaults de dev): `PORT` (8080; cambiarlo si otro proyecto local ocupa el puerto — la app entonces se compila con `-PapiBaseUrl=http://10.0.2.2:<puerto>` y el MCP con `EL_PUESTO_API`), `DB_URL`/`DB_USER`/`DB_PASSWORD`, `JWT_SECRET`, `ACCESS_TTL_MIN` (15), `REFRESH_TTL_DAYS` (60), `ADMIN_API_KEY`, y SMTP (`SMTP_HOST`/`SMTP_PORT`/`SMTP_USER`/`SMTP_PASSWORD`/`SMTP_FROM`) — viven en **`./setenv`** (fish, gitignored): `fish -c 'source ./setenv; backend/build/install/backend/bin/backend'`; sin `SMTP_HOST` no envía correo (usa `devLink`, que la app ya ignora). **OJO al redesplegar**: matar el proceso viejo ANTES de `installDist` (sobrescribir los jars bajo una JVM viva da `ClassNotFoundException` fantasma).
- **MCP admin**: `gradle :adminMcp:installDist` → lo lanza Claude Code vía `.mcp.json` (`./adminMcp/run.sh`, que toma `EL_PUESTO_API` y `EL_PUESTO_ADMIN_KEY` de `./setenv`; `.mcp.json` NO lleva secretos). La clave del MCP es NOMBRADA (`mcp-claude-code`, todos los scopes menos `keys`), no la maestra.
- **Seguridad (2026-09-25)**: expuesto (`PUBLIC_BASE_URL` definido) el backend **no arranca** con `JWT_SECRET`/`ADMIN_API_KEY` de desarrollo o de <32 caracteres (`openssl rand -hex 32`). `HOST=127.0.0.1` en setenv (solo loopback: ngrok y el emulador llegan igual) y Postgres publicado solo en `127.0.0.1:55433`. Freno de fuerza bruta en `/admin` y en el enlace mágico (en memoria: se limpia al reiniciar el backend). Para probar el bloqueo sin bloquearte: `X-Forwarded-For` falso desde localhost. Probar a mano: pipe de JSON-RPC por stdin (ver `initialize`/`tools/list`). La API admin también se usa directo por curl con `X-Admin-Key` (docs en `GET /admin/docs`).
- Emulador (headless):  `~/Android/Sdk/emulator/emulator -avd Pixel_10 -no-window -gpu swangle_indirect` (**no** `swiftshader_indirect`: segfault del emulador al dibujar degradados animados, p. ej. la bandera roja de "Necesitas una invitación" — 2026-09-24); instalar `adb install -r`; la app usa `http://10.0.2.2:8080` (host desde el emulador). Al automatizar input: **el teclado tapa el botón de enviar** → ocultarlo (`adb shell input keyevent 4`) antes de tocar.
- **Procesos y pruebas**: matar el backend con `kill $(lsof -t -sTCP:LISTEN -i:<puerto>)` (sin `-sTCP:LISTEN` mata también al EMULADOR, que tiene conexiones a ese puerto; un `pkill -f` con el patrón del comando se mata a sí mismo). `am force-stop` BORRA las notificaciones de la app: para simular proceso muerto conservándolas, `am kill`. Arnés de la web/admin: Chromium headless de Playwright + CDP (`cdp.mjs`: nav/shot/click/type/wheel/pickfile/eval/logs); vive en el scratchpad de alguna sesión anterior (`/tmp/claude-1000/*/scratchpad/cdp.mjs`) o se recrea.
- **Teléfono físico + ngrok**: dominio FIJO del usuario **`sensibly-glad-dodo.ngrok-free.app`** (no cambia al reiniciar): `ngrok http --url=sensibly-glad-dodo.ngrok-free.app 8081` (o el puerto del backend) → compilar con `-PapiBaseUrl=https://sensibly-glad-dodo.ngrok-free.app` → `adb -s <serial> install -r`. **`scripts/build-apk.sh`** compila contra ese dominio y deja **`dist/el-puesto.apk`** (gitignored); el backend lo sirve en **`/descargas`** (página pública con el botón + `/descargas/el-puesto.apk`, siempre el APK MÁS RECIENTE de `dist/`, env `DOWNLOADS_DIR`) para instalar en teléfonos SIN modo desarrollador (sin adb: descargar → permitir "instalar apps de este origen"). El User-Agent de Ktor no dispara la página de advertencia de ngrok.
- **Ya no hay cuentas demo** (2026-09-24). Las cuentas reales salen de `data/privado/cuentas.csv` (gitignored) vía el cargador (cuáles: `CLAUDE.local.md`). Probar en emulador con SMTP activo: insertar un token en `magic_tokens` (psql) y abrir `elpuesto://auth?token=…` por adb. **Desde 2026-09-26 la tabla guarda el SHA-256 del token** (`encode(sha256(convert_to('<uuid>','UTF8')),'hex')`) y el **reto PKCE** de la app: pide el enlace DESDE la app, copia el `challenge` que quedó en su fila y reemplázala por la de un token tuyo con ese mismo reto (la app presenta su verificador); sin reto solo funciona mientras `AUTH_REQUIRE_PKCE` no sea `true`.
- **Sesión**: access token de 15 min + refresh de 60 días con rotación de un solo uso; la app renueva sola ante 401 (ver §3). Para pruebas con curl, el access vence a los 15 min: re-pedir el magic link.
- **Producción (AWS)**: ver **`docs/DESPLIEGUE.md`** — `https://elpuesto.app` (CloudFront → S3:
  landing + `/descargas/` + APKs versionados) y `https://api.elpuesto.app` (EC2 `mx-central-1` →
  Caddy → backend systemd + Postgres docker). Perfil de CLI `elpuesto` (`AWS_PROFILE`) = usuario
  **de IAM Identity Center** con `AdministratorAccess` (usuario y portal en `CLAUDE.local.md`;
  región del SSO `us-west-2`): **`aws sso login --profile
  elpuesto`** (sesión de 4 h). Root vive en `elpuesto-root` SOLO para emergencias: cada uso manda
  un correo (9-auditoria.sh). Deploy
  siempre desde un commit: `scripts/desplegar-backend.sh`, `scripts/publicar-app.sh`
  (subir versionName/versionCode antes), `scripts/publicar-sitio.sh`; comandos en el servidor
  por SSM (`scripts/servidor.sh '…'`, sin SSH); cargador contra prod con `scripts/prod.sh`.
  `scripts/build-apk.sh` sigue siendo el APK de DEBUG para ngrok. **Probar en el teléfono SIN
  desinstalar la app de producción**: `assembleRelease -PapiBaseUrl=https://<ngrok>` (sin
  `-PupdatesUrl`) firma con la llave de release → se instala ENCIMA (misma firma); se sirve
  copiándolo a `dist/el-puesto.apk` (`/descargas` del backend local). Pide volver a entrar;
  para regresar, reinstalar el APK publicado en `elpuesto.app/descargas` encima. Borrar
  después el APK de `dist/` (es de release y apunta a ngrok). Ojo: la conexión en vivo se
  cierra en cuanto la app deja la pantalla — un aviso de prueba solo llega con la app abierta. **Ojo red local**: bloquea
  DNS externo y su resolvedor (100.64.100.1) guarda NXDOMAIN hasta 1 h — verificar registros
  nuevos por DoH (`curl -H 'accept: application/dns-json' 'https://dns.nextdns.io/dns-query?name=…'`)
  y pegarle al API con `curl --resolve api.elpuesto.app:443:<IP elástica>` (la IP: DoH o `CLAUDE.local.md`).
- **Puerto**: si otro proyecto ocupa el 8080, `PORT=8081 backend/build/install/backend/bin/backend` y compilar la app con `-PapiBaseUrl=http://10.0.2.2:8081` (emulador). La firma de debug es **`androidApp/debug.keystore` versionado** (misma firma en toda máquina → `adb install -r` sin desinstalar).

## 6. Convenciones

- Commits **locales** (sin remoto/push salvo que se pida). Mensajes terminan con la línea `Co-Authored-By: Claude ...`.
- Un commit por cambio lógico; no commitear código que no compila.
- Verificar el build (y correr si aplica) antes de dar algo por hecho.
- **UX de carga (toda pantalla, actual y futura):** estado `null` = cargando (no lista vacía); **skeleton por sección** (`SkeletonBox`/`SkeletonRows`) que se quita solo cuando los datos de ESA sección llegaron; nunca renderizar estados vacíos/contadores mientras carga. **Sincronización de caché en UNA transacción** (los Flows de SQLDelight emiten por cada cambio de tabla; borrar→reinsertar sin transacción causa parpadeo). Logs: tags `ElPuestoHttp` y `ElPuestoSync`.
- **Caché primero (toda lectura) — la política vive en el repositorio, no en las
  pantallas**: toda lectura nueva cacheable va por `cachedValue`/`cachedCatalog` y toda
  escritura a `catalog` por `storeCatalog`/`refetch` (nunca `q.putCatalog` suelto fuera
  de una transacción; dentro de una, `notifyChanged` después del commit). Lo que se
  superpone al leer (outbox) también llama `notifyChanged(key)` al encolar.
- **Pantallas que muestran datos**: `val reloader = rememberReloader(repo)` +
  `LaunchedEffect(…, reloader.key) { reloader.track { … } }` — `track` suscribe la
  pantalla a las llaves que leyó; `reloader.key` sube cuando cambian (o al volver la
  señal). Una recarga NO debe resetear estado visual (scroll, selección, "cargando"):
  solo al cambiar de objeto (ver `roundsFor`/`contentFor`/`mapFor`). `freshReads { }`
  SOLO donde lo último del servidor es el punto: avisos en vivo (WS/SSE), `Refreshable`
  (ya lo envuelve), decidir si notificar. Los editores leen caché primero una vez (sin
  `track`: una recarga pisaría lo que se captura). Cargas encadenadas en efectos
  separados causan parpadeo (vacío falso entre pasos): preferir UNA pasada.
- **Sin conexión (toda pantalla que abre un recurso)**: `reloader.track { … }` (o
  `trackMiss` fuera de pantallas) dice si `missed`; si no hay dato, mostrar
  `UnavailableScreen` (o `UnavailableInline` para una sección): nunca dejar un skeleton
  eterno ni un vacío engañoso ("Sin resultados") cuando el dato solo falta en la caché.
- **Vacíos**: toda pantalla o pestaña que puede quedar vacía usa `EmptyState` (glifo +
  qué pasa + qué hacer; `compact` en pestañas/secciones) — nunca pantalla en blanco ni un
  texto suelto. Solo se muestra con los datos YA cargados (null = cargando).
- **Acciones que exigen red** (las que no van por el outbox): `rememberOnline(repo)` para
  desactivarlas y `NeedsConnectionNote("… necesita conexión.")` junto a ellas. Todo lo
  nuevo que el oficial capture en pista debe ir por el outbox, no exigir red.
- **Identidad = `AppRepository.myOfficerId()`** (caché → red, null si no se sabe; NUNCA
  cae a la semilla). Lección 2026-08-02: `profile(null)` caía a `SeedData.self` (carlos)
  y la tabla `officer` acumulaba una fila por cuenta usada en el teléfono (`LIMIT 1`
  devolvía cualquiera) → las burbujas propias salían a la izquierda en teléfonos con otra
  cuenta. El refresh ahora hace `clearOfficers()` antes de escribir al autenticado.
- **Imágenes del backend**: siempre por `RemoteImageBox`/`RemoteAvatar`,
  `rememberRemoteImage` (estado Loading/Ready/Missing/Unavailable) o `loadImageBitmap`
  (dentro de otra carga) — nunca `repo.image()` + `BitmapFactory` a mano: esos pasan por
  memoria → teléfono → red, decodifican fuera del hilo principal y se repintan solos ante
  `imageChanges()`. Nada de `key(version)` para forzar recargas.
- **Código común (desde 2026-09-26)**: pantallas y datos nuevos van en `app/src/commonMain` (sirven
  a Android Y web). Nada de `android.*`/`java.*` ahí: lo que necesite la plataforma entra por
  `AppPlatform` (servicios de `androidApp`) o por un `expect/actual` en `ui/platform/`, y lo que
  una plataforma no tenga se declara `supported = false` y la UI lo OCULTA. Tiempo con
  `kotlinx.datetime`/`nowMs()`, ids con `kotlin.uuid.Uuid`, Base64 con `kotlin.io.encoding`,
  mapas concurrentes con `SafeMap`/`SafeSet`, avisos con `rememberToast()`. Probar los cambios
  de UI en ambas: emulador y la web (distribución de producción).
- **Seguridad por defecto (toda función nueva, lección de la auditoría 2026-09-26):**
  - Toda ruta nueva decide QUIÉN puede (candado en el repositorio, no en la app), cae en una
    clase de `RateLimits` que tenga sentido y, si guarda algo del oficial, tiene cupo.
  - Todo `kind` nuevo del `ChangeBus` que deba llegar a los teléfonos necesita su rama en el
    WS `/stream` o entrar a `StreamPolicy`; por omisión NO sale (nunca reenviar `detail`).
  - Texto que escribe un oficial: tope ≤ su columna (`TextLimits`), sin invisibles
    (`NameRules.hasHiddenChars`) si es un nombre, y en el admin web SIEMPRE por `h()`/
    `textContent` (nunca `innerHTML` con datos). Correos: `EmailRules`.
  - Nada que dependa del correo como identificador visible; ids opacos.
  - Dependencias nuevas: regenerar `gradle/verification-metadata.xml` y revisar el diff.
  - Notificación nueva: decidir qué se ve con el teléfono bloqueado (`Locked` en
    `ChatNotifications`); por omisión, solo un título genérico. Público entero, solo el MbM.
- **Base local con migraciones:** cambiar `ElPuesto.sq` = agregar `N.sqm` + regenerar
  `databases/(N+1).db` (`README.md` junto al `.sq`); el build lo verifica. Columnas nuevas
  `NOT NULL` con `DEFAULT`. Los teléfonos se actualizan encima: nunca asumir base limpia.
- **Caché local tolerante a cambios de wire:** los blobs protobuf de SQLDelight se leen
  con `decodeOrNull` (Db.kt) — un blob escrito con un esquema anterior cuenta como cache
  miss (la UI cae a semilla/carga y el sync lo reescribe), NUNCA truena la app. La red
  sigue con decode estricto. (Lección 2026-08-01: cambiar `Assignment.role` de enum a
  String dejó la app en crash-loop con la caché vieja.)
- **Mapas de trazado**: siempre `ui/map` — `TrackMap` + `LayerBar` (+ `TrackMapFullscreen` dentro de la pantalla, o el Mapa en vivo en el evento), marcadores con `trackMapItems` y capas por `AssetType.layer()` (un tipo nuevo = una línea ahí y en `ASSET_TYPES` del admin). Nunca un mapa o filtro propio por pantalla: la homologación es la decisión.
- **UI:** pantallas con tabs → swipe horizontal (`HorizontalPager` sincronizado). Barras superiores con `BackButton`/`TopBarIconButton` compartidos (42dp); los insets de status bar **y de teclado** son globales (`statusBarsPadding().imePadding()` en la raíz + `windowSoftInputMode=adjustResize`: el contenido se encoge al abrir el teclado, nunca "panea"). Pantallas con contenido pegado al fondo y sin bottom bar (p. ej. el composer del chat) agregan su propio `navigationBarsPadding()`.
