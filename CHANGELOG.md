# Cambios

## v1.5.0 — 27 de septiembre de 2026

### Para los oficiales

- **Los mapas funcionan igual en toda la app**: en Circuito, en tu puesto del Modo evento, en
  el Mapa en vivo y en el registro por honor tienes los mismos controles en las mismas
  esquinas, y todos se abren a pantalla completa.
- **Capas que prendes y apagas**: Puestos, Rescate (HIAB, IFRT, Telehandler, Driver Rider),
  Soporte (Safety Car y barredora) y Médicos, cada una con su color: puestos naranja, rescate
  amarillo, soporte verde y médicos rojo. Lo que ocultas sigue oculto al pasar a pantalla
  completa.
- **Toca un puesto o un vehículo en el mapa** para ver qué es. Tu puesto va resaltado y
  siempre a la vista.
- El Mapa en vivo abre el mismo trazado que elegiste en tu puesto.
- En el registro por honor puedes elegir o colocar tu puesto en el mapa a pantalla completa.

### Administración

- El editor del mapa y el detalle de evento usan los mismos colores por capa que la app; el
  estado del puesto (con oficiales, checklist completa) va en el contador.

## v1.4.0 — 27 de septiembre de 2026

### Para los oficiales

- **Toca una foto para verla en grande**: la imagen de un chat (en sus detalles) y la foto de
  un oficial (en su perfil) se abren en grande, como las de los pilotos.
- **Agenda "lo que viene"**: toca un día del calendario para ver solo lo de ese día.
- **Íconos nuevos en la barra inferior**, con motivo de pista.
- Al cerrar la foto de un piloto vuelves a la tabla de posiciones donde estabas, no al
  calendario.

### Administración

- Fuentes nuevas de posiciones automáticas: `formulae-web` (Fórmula E), `wrc-feed`
  (parámetro = wrc|wrc2|wrc3|jwrc) e `indycar-feed` (parámetro = indycar|indynxt), con fotos
  de los pilotos donde el sitio las publica.
- Los chats públicos y privados que no están ligados a un evento ya no muestran el puesto ni
  el rol de sus participantes.

## v1.3.0 — 27 de septiembre de 2026

### Para los oficiales

- **Fotos de los pilotos en Posiciones**: cada piloto trae su foto (en F1, F2, F3 y WEC);
  toca su fila para verla en grande. El número de coche ahora va en una pastilla junto a su
  equipo.
- **Más campeonatos con su tabla de posiciones**: Fórmula 2, Fórmula 3, NASCAR (Cup,
  O'Reilly y Truck), WEC (Hypercar y LMGT3) y la **México Racing Cup** (con Súper Turismos
  Light 1 y 2 por separado). Se actualizan solas al terminar cada fecha; en la México Racing
  Cup, cuando el campeonato publica su tabla, que a veces llega con semanas de atraso (arriba
  de la tabla dice hasta qué fecha llega).

### Administración

- Fuentes nuevas de posiciones automáticas: `fiaformula2-web`, `fiaformula3-web`,
  `nascar-feed` (parámetro = serie), `fiawec-web` (parámetro = clase) y `mexicoracingcup-img`
  (tabla leída de la imagen del sitio: `PUT /admin/categories/{id}/standings-reading`, tools
  MCP `guardar_lectura_posiciones`/`ver_lectura_posiciones`; si el sitio sube otra imagen, la
  ingesta avisa que falta leerla).
- Fotos de pilotos: kind de imagen `driver`, descargadas una sola vez por la ingesta; los
  reemplazos manuales de pilotos o posiciones las conservan.
- El job de posiciones ya no marca error cuando la fuente va una fecha adelante (se guarda al
  terminar ese día) y la confirmación que falla se reintenta cada 6 h.

## v1.2.1 — 26 de septiembre de 2026

### Para los oficiales

- **El MbM sin desbloquear el teléfono**: la barra "Evento en curso" ahora se ve completa en
  la pantalla de bloqueo — la actividad en curso con su cuenta regresiva, la que sigue y, si
  la expandes, las dos siguientes. Solo muestra el MbM: nada de tu puesto, tu rol ni tus
  datos.
- Si en tu teléfono ocultas el contenido sensible en la pantalla de bloqueo, las demás
  notificaciones (mensajes, recordatorios, avisos) muestran un título general, como
  "Mensaje nuevo", en vez de solo "El Puesto", sin revelar lo que dicen.

## v1.2.0 — 26 de septiembre de 2026

### Para los oficiales

- **El Puesto también en el navegador**: entra a **https://app.elpuesto.app** desde una
  computadora o un iPhone con tu misma cuenta. Lo que el navegador no puede hacer
  (notificaciones, compartir tu ubicación, recordatorios, la barra del evento) sigue siendo
  solo de Android.
- La bienvenida "Completar perfil" sale **una sola vez por cuenta**: si ya la hiciste, al
  entrar en otro teléfono (o en el navegador) vas directo al Inicio.
- Por dentro, la app se reorganizó para compartir todo con la versión web. Si notas algo
  raro, avísanos.

### Producción

- La app web se publica en `app.elpuesto.app` junto con cada versión del backend (misma
  versión, mismo rollback), comprimida y con su propia política de contenido (CSP); la sesión
  del navegador guarda el refresh en una cookie HttpOnly y el tiempo real entra con un boleto
  de un solo uso.
- Aviso por correo cuando una IP queda bloqueada por probar claves de admin y avisos de
  contraseña o MFA fallidos en IAM Identity Center.
- Un cambio del Caddyfile ahora sí llega a Caddy al aplicar la configuración del servidor.

## v1.1.2 — 26 de septiembre de 2026

### Para los oficiales

- **Entrar es más seguro**: el enlace de acceso solo funciona en el teléfono donde lo pediste.
  Si abres el enlace de otra cuenta, la app te pregunta antes de cambiar y borra lo guardado
  de la anterior.
- **Nadie te mete a un chat sin que aceptes**: invitar a un chat público ahora también es una
  invitación. Si la rechazas o sales del chat, no te pueden volver a invitar a ese chat.
- **Bloquear a un oficial** desde su perfil: ya no puede invitarte a chats ni compartirte su
  ubicación, y sus mensajes se ven colapsados. Lo deshaces en Configuración → Oficiales
  bloqueados.
- **Reportar un chat o un perfil**, además de un mensaje.
- **Buscar oficiales** por nombre (desde 3 letras) o por número OMDAI; ya no por correo.
- En el perfil de otro oficial, su pasaporte muestra solo el año de cada circuito.
- Los chats cargan por partes: abren más rápido y los mensajes anteriores aparecen al subir.
- Si algo no se puede guardar (cupo de fotos lleno, demasiados chats abiertos, un texto muy
  largo), la app te dice por qué en vez de culpar a tu conexión.
- Tu sesión se guarda cifrada en el teléfono y no viaja en los respaldos de Android; la
  información de emergencia no se puede capturar en pantalla.

### Administración

- El admin web vive en su propio dominio: **https://admin.elpuesto.app/admin/ui/** (la clave se
  pega en cada sesión del navegador).
- Moderación de reportes de chats y perfiles, además de mensajes.
- Sección **Seguridad**: pausar funciones (enlaces, subidas, mensajes, chats…) sin redesplegar,
  ver y liberar a quien quedó congelado por abuso y el uso de las últimas 24 horas.
- El MCP marca qué herramientas borran y trata los textos de los oficiales como datos.

### Producción

- Auditoría de seguridad completa: página puente sin inyección, política de contenido (CSP)
  en todas las respuestas, el tiempo real reparte solo avisos permitidos y sin datos ajenos,
  tokens guardados como hash, cupos y topes diarios, exportación de datos en streaming y
  congelamiento automático de quien abusa.
- Respaldos inmutables (nadie los borra antes de tiempo), el backend sin acceso a las
  credenciales del servidor, CI que compila sin credenciales y solo puede activar versiones,
  y dependencias e imágenes verificadas por su huella SHA-256.

## v1.1.1 — 25 de septiembre de 2026

### Para los oficiales

- **Actualiza desde la app**: cuando haya una versión nueva, un aviso en el Inicio (y en
  Configuración → Actualizaciones) te dice qué cambió; con un toque se descarga y Android te
  pide confirmar. La primera vez, Android te pide permitir que El Puesto instale
  actualizaciones. Esta es la última vez que tienes que actualizar desde elpuesto.app.
- **Tus cambios no se pierden**: si el servidor está saturado, lo que capturaste espera en tu
  teléfono y se envía solo después. Tampoco se pierde al actualizar la app.
- Si falla la señal al renovar tu sesión, **ya no te saca** a la pantalla de acceso.
- **Tu sesión, bajo control**: al cerrar sesión se cierra también en el servidor y se borra lo
  guardado en el teléfono. Si pierdes tu teléfono, un administrador puede cerrar tus sesiones
  y lo guardado se borra en cuanto la app vuelve a abrirse.
- Si tu cuenta se suspende, la app te lo dice con claridad.

### Administración

- Cerrar las sesiones de una cuenta, o las de todos en una emergencia, desde el admin web o el
  MCP; Cuentas muestra las sesiones activas.
- Suspender una cuenta corta su acceso al momento.

### Producción

- Límites de uso contra el abuso de la API (por oficial y por IP), topes de tamaño y de texto,
  y protección contra imágenes maliciosas.
- El enlace de acceso ya no revela si un correo tiene cuenta.
- Registro de quién hace cada petición (30 días) y alertas de seguridad por correo.
- Caddy con tope de tamaño, logs sin claves ni tokens y retención de 30 días.
- La base del teléfono se migra sola al actualizar la app; si algo falla, se reconstruye sin
  perder lo pendiente de enviar.
- Despliegue automático del backend y del sitio desde GitHub, y versión nueva de la app en un
  paso (`scripts/nueva-version.sh`).
- Vista previa al compartir el enlace del sitio.

## v1.1.0 — 25 de septiembre de 2026

Primera versión **en producción**: la app se descarga de **elpuesto.app** y habla con
**api.elpuesto.app**.

> **Al actualizar desde la v1.0.0**: esta versión está firmada con la llave definitiva, así
> que Android no la deja instalar encima de la anterior. **Desinstala la v1.0.0** y luego
> instala esta. Tus datos viven en el servidor; lo único que se pierde son cambios que no
> se hayan enviado.

### Para los oficiales

- **Registro por honor**: desde la agenda puedes registrar que trabajaste un evento, aunque
  no estés en el roster. Indicas tu posición, tu rol y los días, y cuenta para tu historial,
  tus logros y tus eventos en común. Si tu puesto no aparece en el mapa, lo propones
  tocando el mapa y un administrador lo revisa.
- **Logros**: pasaporte de circuitos, medallas (días en pista, versatilidad, antigüedad),
  parches de campeonato y sellos de primeras veces, en tu perfil. En el catálogo de
  circuitos, un sello ✓ marca dónde ya trabajaste, y el detalle del circuito cuenta tu
  historia ahí. Sin rankings ni comparaciones.
- **Chats privados**: grupos con nombre por invitación (el invitado acepta antes de
  entrar). Un chat público o privado se puede ligar a un evento que trabajas y aparece en
  su Modo evento.
- **Más rápida y sin parpadeos**: las pantallas abren al instante con lo guardado en el
  teléfono y se actualizan solas. Las fotos y logos se guardan en el teléfono y se ven sin
  señal.
- **Compartir ubicación solo cerca del circuito**: fuera de la pista y medio kilómetro
  alrededor, el teléfono deja de enviarla.
- **Campeonatos nacionales**: NASCAR México (tres series) y México Racing Cup (siete
  categorías), con sus sedes. Los calendarios salen en orden cronológico.
- Tocar el avatar de un mensaje abre el perfil de quien lo mandó, y la búsqueda de
  oficiales ignora acentos.
- El rol TSP del roster se llama **Panel de luz**.

### Administración

- Autoregistro por evento (abierto por defecto) y revisión de los registros por honor.
- Cola de **puestos propuestos**: aprobar, fusionar con uno existente o rechazar.
- Logos de campeonato versionados en `data/`.

### Producción

- Sitio y descargas en **elpuesto.app** (CloudFront + S3). Cada versión del APK se publica
  con su número en el nombre (`el-puesto-1.1.0.apk`) y su huella SHA-256.
- API en **api.elpuesto.app** (AWS, región México), con respaldo diario de la base.
- APK firmado con una llave de release que vive fuera del repositorio.

## v1.0.0 — 25 de septiembre de 2026

Primera versión de **El Puesto**: la app Android para oficiales de pista del
automovilismo mexicano, con su backend y sus herramientas de administración.

### Para los oficiales (app Android)

- **Acceso sin contraseñas**: enlace mágico por correo. El registro es por invitación
  entre oficiales y la cuenta la aprueba un administrador.
- **Inicio**: el evento en curso con tu asignación, tu agenda de los próximos 15 días,
  las convocatorias abiertas y Explorar (circuitos y campeonatos).
- **Modo evento**:
  - **Puesto**: tu asignación, el mapa del trazado con tu puesto resaltado, tus
    compañeros con su rol, el checklist del día, el pase de lista del jefe de puesto
    y la asistencia.
  - **MbM**: el cronograma en vivo con la actividad en curso.
  - **Chat**: los chats del evento y de tu puesto.
  - **Bitácora**: fotos, notas y planeación del viaje.
  - La **barra fija "Evento en curso"** muestra la cuenta regresiva de la actividad en
    curso, y un aviso te dice cuando cambia el MbM.
- **Ubicaciones en vivo**: son opcionales y solo funcionan durante un evento. Tú decides
  a quién le compartes tu ubicación. Tus compañeros aparecen con su foto sobre el mapa
  del trazado, y hay un mapa en vivo a pantalla completa.
- **Chats**: públicos, del evento y del puesto, con fotos y tiempo real. Puedes invitar
  a otros oficiales, reportar mensajes, silenciar un chat y ver los no leídos.
- **Agenda**:
  - Un calendario con tus eventos, las carreras del calendario y las convocatorias.
  - El detalle "Tu fin de semana" junta tus días, tu asignación y tu planeación de
    viaje (transporte, hospedaje, recordatorios, notas y fotos).
- **Circuitos**: 78 circuitos con sus trazados dibujados y el mapa de puestos y
  activos, con zoom. Marca los puestos donde ya te asignaron antes.
- **Campeonatos**: 7 campeonatos 2026 con sus temporadas, categorías y calendario. Las
  posiciones de F1 se actualizan solas después de cada fecha.
- **Convocatorias**: las abiertas y el historial, con sus indicaciones. Puedes pedir un
  recordatorio antes del cierre.
- **Perfil**:
  - Tu historial de eventos y los eventos que tienes en común con otro oficial.
  - Tu información de emergencia es privada: solo la ve tu jefe de puesto durante un
    evento activo, y cada consulta queda registrada y la puedes ver.
- **Privacidad**: puedes **descargar todos tus datos** (un ZIP en JSON, una vez al día).
  No es una red social: no hay amigos, seguidores ni mensajes directos.
- **Sin señal en pista**:
  - Lo que capturas funciona sin conexión y se envía solo cuando vuelve la señal: el
    checklist, la asistencia, la bitácora, los mensajes, tu perfil y tu emergencia.
  - Si abres algo que no está guardado en el teléfono, la app te dice que no hay
    conexión en vez de quedarse cargando.

### Administración

- **API administrativa JSON** (`/admin/*`):
  - Claves con permisos por área, auditoría de cada cambio y validación sin escribir
    (`dryRun`).
  - Documentación en `/admin/docs` y en `/admin/openapi.json`.
- **Servidor MCP** (`adminMcp/`) para que agentes de IA administren los datos.
- **Administración web** (`/admin/ui/`):
  - Cuentas, oficiales, eventos (con un centro de control en vivo: mapa, cobertura, MbM,
    chat como "Control", checklist y asistencia), circuitos (con editor del trazado
    sobre OpenStreetMap), campeonatos, convocatorias y moderación.
  - Se pueden configurar las fuentes de las posiciones automáticas.
- **Datos reales versionados** en `data/`: CSV con su fuente, que se cargan con
  `scripts/cargar-datos.py`.

### Técnico

- Android (Jetpack Compose) con caché local SQLDelight y cola de envío (outbox).
- Backend Ktor con Postgres. El protocolo es protobuf, con JSON para la administración.
  El tiempo real va por WebSocket y SSE.
- Seguridad:
  - Freno de fuerza bruta en la administración y en el enlace mágico.
  - El backend no arranca expuesto si los secretos son débiles.
  - Solo escucha en loopback, con CORS por lista y cabeceras defensivas.

### Pendiente para después de v1

- Notificaciones push con la app cerrada (FCM). Hoy los avisos llegan mientras la app
  está viva.
- Posiciones automáticas de las series que no son F1.
- Video en los chats.
- Compartir elementos de la bitácora (a la espera de la opinión de los usuarios).
- Dar de baja la cuenta.
