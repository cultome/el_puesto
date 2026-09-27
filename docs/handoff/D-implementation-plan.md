# D · Plan de implementación

Objetivo: llevar el diseño 1a a una v1 usable por oficiales de pista.
Stack (ya decidido): **Android Kotlin + Compose**, **KMP `shared`** (protobuf), backend
**Ktor + Postgres** en VPS, **offline-first** (SQLDelight + outbox), **magic link + JWT**.
Admin en **web** (aparte). Ver `A/B/C`.

## Alcance v1 (recordatorio)
Modo evento · Perfil + emergencia · Circuitos · Campeonatos · Convocatorias · Agenda ·
Chats · Onboarding/acceso · Configuración (incl. compartir ubicación).

---

## Milestones

### M0 · Cimientos (1 sprint)
- Repo/monorepo: `androidApp`, `shared`, `backend`, `adminWeb`. Gradle KMP, CI.
- **Tema Compose** desde `A` (colores, tipografía descargable, componentes base: botón, campo, chip, fila, bottom nav, toggle, sheet).
- `shared`: entidades + contratos de `B` (protobuf con `@ProtoNumber`).
- Backend: esqueleto Ktor + Postgres + migraciones; healthcheck; deploy al VPS.
- Infra: correo (magic link), FCM, object storage (avatares/mapas), secretos.
- **Salida:** app corre, tema aplicado, "hello contract" end-to-end.

### M1 · Rebanada vertical (auth → home → modo evento lectura)
- **Auth/onboarding**: magic link + deep link; invitación entre pares + aprobación admin; gate por `AccountStatus`; completar perfil.
- **Offline core**: SQLDelight + delta pull + outbox + banner de conexión.
- **Home** (evento activo) + **Modo evento** (Puesto/Cronograma en solo lectura) con datos de un evento y asignación creados a mano por admin.
- **Salida:** un oficial entra, ve su asignación, compañeros y cronograma, **offline**. Valida arquitectura completa.

### M2 · Modo evento completo + Perfil
- Checklist del puesto (escritura vía outbox), estados en vivo del cronograma.
- **Chat de evento y de puesto** (WS realtime) — reutiliza para M5.
- **Perfil** (mío/público) + **info de emergencia** (cifrada) + **registro de accesos** + gate del jefe de puesto durante evento activo.
- **Notificación persistente** del evento en curso.

### M3 · Catálogos
- **Circuitos**: selector de trazado, mapa (integrar assets oficiales + coords), filtro por tipo, lista con scroll, "Asignado antes". Offline.
- **Campeonatos**: lectura + **endpoint de ingesta** (sistema externo) y captura en admin.

### M4 · Agenda + Convocatorias
- **Agenda**: calendario, agenda del día, **viajes** (planeación personal) + recordatorios.
- **Convocatorias**: lista abiertas + historial + detalle (Markdown) + recordatorio de cierre; "Postularme" abre URL externa.

### M5 · Chats completo
- Hub, **públicos** (crear/unirse/explorar), **moderación** (reportar), **archivado** (solo lectura a la semana, job de archivado + retención).

### M6 · Configuración + Compartir ubicación
- Ajustes, notificaciones (preferencias), privacidad/datos (descargar mis datos, cerrar sesión, baja).
- **Compartir ubicación**: opt-in, allowlist, transparencia, activo solo en eventos; publicación/consumo de ubicación en tiempo real (WS) acotado al evento.

### M7 · Admin web (en paralelo desde M1)
Crear eventos, dar de alta puestos, **asignar oficiales a mano**, re-postear convocatorias,
captura/ingesta de campeonatos, aprobar cuentas, moderación. Es requisito para poblar datos
de los milestones de la app.

### Endurecimiento
Accesibilidad, rendimiento offline, seguridad (cifrado emergencia, auditoría, JWT rotation),
pruebas de campo con conectividad pobre, store release.

---

## Trabajo transversal (todo el proyecto)
- **Offline-first** en cada feature (no como fase).
- **Push** (FCM) y notificación persistente.
- **Privacidad/seguridad**: cifrado de emergencia, registro de accesos, minimización de datos.
- **Observabilidad**: logs/errores backend y cliente.

## Riesgos / decisiones que desbloquean
1. **Assets de mapas de trazado + coordenadas** (bloquea Circuitos "real"). — fuente oficial
2. **Contrato de ingesta** de campeonatos y convocatorias. — sistema externo
3. **Catálogo de Áreas** y **validación de OMDAI ID**. — fuente oficial
4. **Proveedor de correo** (magic link) y **hosting**. — equipo
5. **Realtime** (WS Ktor) y **retención** de chats archivados. — equipo
6. **Stack del admin web**. — equipo

## Sugerencia de arranque inmediato
Paralelizar: **(1)** tema Compose (M0), **(2)** contrato `shared` + backend esqueleto (M0),
**(3)** admin mínimo para crear evento+asignación (habilita M1). Con eso, M1 (la rebanada
vertical) valida todo el enfoque antes de invertir en el resto de módulos.
