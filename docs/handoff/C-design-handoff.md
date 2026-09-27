# C · Handoff de diseño — pantalla por pantalla

Mockups en Claude Design: **`https://claude.ai/design/p/9813816d-ac58-4f5b-9657-86ed29c10dcd`**
(dirección visual **1a "Paddock nocturno"**; ver `A-compose-theme.md` para tokens,
`B-data-model-api.md` para datos). Solo Android; el admin es web (aparte).

**Navegación global:** barra inferior de 4 pestañas — **Inicio · Agenda · Chats · Perfil**.
El resto (Modo evento, Circuitos, Campeonatos, Convocatorias, Configuración) se alcanza
desde el Home o desde Perfil→ajustes.
**Principio de navegación conectada:** todo ítem que representa un objeto lleva a su detalle
(listas de oficiales → perfil; circuitos, campeonatos, pilotos → su detalle), salvo que haya
una acción más apropiada.

---

## Acceso · `Acceso 1a - flujo.dc.html`
Flujo de entrada por **magic link** (sin contraseña). Estados:
- **Bienvenida** → campo correo + "Enviar enlace de acceso".
- **Enlace enviado** → revisa correo, reenviar (contador), usar otro correo.
- **Pendiente de aprobación** → tras registro; chip "Invitado por X" + stepper Invitación→Revisión→Acceso. Tono tranquilizador.
- **Sin invitación** → registro solo por invitación; cómo conseguir una.
- **Sin conexión** → error al enviar; el acceso requiere internet.

Datos: solo OMDAI ID + display name + correo (privado). Gate por `AccountStatus`.

## Completar perfil · `Completar perfil.dc.html`
Onboarding tras la primera aprobación (2 pasos, progreso):
1. **Identidad** — avatar (opcional), nombre para mostrar, OMDAI ID, **Área asignada (una sola)**.
2. **Emergencia (opcional)** — contacto, tipo de sangre, alergias (**sin seguro**); nota de privacidad/auditoría. Botón **"Finalizar"** (actualizar datos NO requiere aprobación) / "Omitir por ahora".

## Home · `Home.dc.html`
Hub tras iniciar sesión:
- Barra: saludo + avatar + campana.
- **Evento en curso (héroe)**: pill EN CURSO, circuito, bloque **"Ahora: <sesión> · termina en N min"** (= notificación persistente), asignación (Puesto·Área), **"Entrar al evento →"**.
- **Convocatorias abiertas** (scroll horizontal), **Tu agenda** (evento+viaje), **Explorar** (Circuitos, Campeonatos).

## Modo evento · `Modo evento.dc.html`
Se "entra" desde el Home; navegación interna por **segmento de 3** (no la barra inferior); botón atrás para salir.
- **Puesto**: asignación grande, **compañeros** (fila→perfil, jefe marcado), **checklist del puesto** (progreso). *(GPS NO vive aquí.)*
- **Cronograma**: selector de día + sesiones con estado Terminada/En curso(termina en N min)/Próxima.
- **Chat**: sub-switch **Evento / Mi puesto**, mensajes, mensaje destacado de **Control**, composer.

## Perfil · `Perfil.dc.html`
- **Mi perfil**: avatar+cámara, OMDAI ID, **Área asignada (una)**, stats, **info de emergencia visible** (candado, "Privada", fila **"Registro de accesos"**), historial. Barra inferior (Perfil activo), acceso a **ajustes** (→ Configuración).
- **Perfil de otro oficial** (desde listas): contexto de trabajo (áreas, stats, **eventos en común**), **emergencia BLOQUEADA** (explica que es auditable). **Sin acciones sociales** (no red social).

## Circuitos · `Circuitos.dc.html`
Catálogo **independiente de los eventos**.
- **Catálogo**: autódromos + nº de configuraciones.
- **Detalle**: **selector de trazado prominente primero** (define km/curvas/puestos; hasta ~12 vía hoja). **Mapa fijo** con posiciones de **puestos + grúas + ambulancias**, leyenda, **filtro por tipo** y **lista con scroll** bajo el mapa (mapa nunca se pierde). Escala ~50 puestos + docena de activos. Puestos donde estuviste → **"Asignado antes"** (historial). Sin pantalla de detalle de puesto. Disponible **offline**.

## Campeonatos · `Campeonatos.dc.html`
Solo lectura (API/admin).
- **Catálogo** de campeonatos (temporada, nº categorías).
- **Detalle**: **selector de categoría** (define los datos) + pestañas **Posiciones** (leaderboard, podio), **Calendario** (fechas + ganador/estado), **Pilotos** (parrilla → detalle piloto). Nota de fuente ("actualizado hoy").

## Agenda · `Agenda.dc.html`
Pestaña. Combina lo del sistema con tu planeación personal.
- **Calendario** mensual con puntos por tipo (evento/viaje/convocatoria) + agenda del día. FAB "+".
- **Viaje**: planeación **ligada a un evento** (Transporte/Hospedaje/En pista/Recordatorios como notas estructuradas).
- **Agregar**: hoja que aclara — eventos y convocatorias llegan solos del sistema; aquí agregas **solo planeación personal**.

## Convocatorias · `Convocatorias.dc.html`
Solo consulta (re-publicadas de un sistema externo; postulación externa).
- **Lista (abiertas)** con foco en abiertas + botón **"Historial"** (pantalla aparte).
- **Detalle**: datos que **sí** tenemos (evento, fecha, lugar, **fin de inscripciones**, **cupo** único) + **indicaciones generales en Markdown**. CTA **"Postularme ↗"** (sistema externo) + "Recordarme antes del cierre" (interno).
- **Historial**: pasadas (Cerrada / "Participaste").

## Chats · `Chats.dc.html`
Pestaña. Lo "social" solo al servicio de la actividad.
- **Hub**: Del evento (restringidos), Públicos (unidos), acceso a **Archivados**, "+".
- **Públicos**: **crear** y **unirse** (cualquiera).
- **Conversación**: barra de **moderación** (reportar), menú, composer.
- **Archivado**: chat de evento **solo lectura** (se archiva 1 semana después), composer deshabilitado.

## Configuración · `Configuracion.dc.html`
Desde Perfil→ajustes.
- **Lista**: Ubicación, Notificaciones (incl. **persistente "Evento en curso"**), Privacidad y datos (emergencia, **registro de accesos**, descargar datos), Cuenta (correo privado, OMDAI ID, idioma Español, editar perfil), cerrar sesión / dar de baja.
- **Compartir ubicación**: **opt-in** (toggle maestro), **transparente**, **solo durante eventos**, **allowlist** "Compartes con" (agregar/quitar) + "Te comparten". No es grafo social.

---

## Reglas transversales para implementar
- **Offline-first**: trazados, asignación, cronograma, emergencia, checklists funcionan sin conexión; estado de conexión explícito.
- **Notificaciones**: convocatorias, cambios de cronograma, mensajes, y la **persistente** del evento en curso.
- **Privacidad**: emergencia privada (sin cifrado, decisión 2026-09-25), acceso restringido y **auditado** (ambas partes lo saben).
- **Español MX** único idioma. Accesibilidad para uso en pausas/sol.

## Pendientes de diseñar (secundarios)
Registro de accesos (pantalla), detalle de piloto, flujo "agregar oficial" a ubicación,
estados de detalle de convocatoria pasada, y las **interfaces web de admin**.
