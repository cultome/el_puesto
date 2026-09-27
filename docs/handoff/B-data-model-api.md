# B · Modelo de datos y contrato de API

Contratos del módulo KMP `shared` (kotlinx.serialization + protobuf con `@ProtoNumber`
explícito, wire no legible por diseño) y la API del backend Ktor. La app es
**offline-first**: casi todo se cachea en SQLDelight y se sincroniza; el backend es la
fuente de verdad.

> Convenciones: IDs opacos (`String`, ULID/UUID). Fechas en ISO-8601 UTC (`Instant`).
> Todo el contenido de UI ya está en español. Los `enum` viajan como su nombre.

---

## 1. Entidades (sketch en Kotlin)

### Identidad y cuenta
```kotlin
enum class Area { BANDERAS, INTERVENCION, COMUNICACION, CRONOMETRAJE, RESCATE }  // catálogo canónico pendiente de la fuente oficial
enum class AccountStatus { INVITED, PENDING_APPROVAL, ACTIVE, SUSPENDED }
enum class SystemRole { OFICIAL, COORDINADOR, ADMIN }

data class Officer(
  val id: String,
  val omdaiId: Int,              // número; ¿validado contra fuente? → decisión pendiente
  val displayName: String,
  val avatarUrl: String?,        // avatar global, opcional
  val assignedArea: Area?,       // UNA sola área a la vez ("Área asignada")
  val activeSince: Int?,         // año
  val systemRole: SystemRole,
  val stats: OfficerStats,       // eventos, temporada, etc. (derivado)
)

data class EmergencyInfo(       // privada (sin cifrado, decisión 2026-09-25), acceso restringido + auditado
  val contactName: String?, val contactPhone: String?,
  val bloodType: String?, val allergies: String?,   // SIN póliza de seguro
)
data class EmergencyAccess(     // registro de accesos (mutuamente visible)
  val id: String, val viewerId: String, val viewerName: String,
  val at: Instant, val eventId: String?,
)

data class Invitation(          // invitación entre pares (grafo)
  val id: String, val inviterId: String,
  val inviteeEmail: String, val status: AccountStatus, val createdAt: Instant,
)
```

### Eventos y asignación
```kotlin
enum class EventStatus { UPCOMING, LIVE, FINISHED }
data class Event(
  val id: String, val name: String,
  val startsOn: LocalDate, val endsOn: LocalDate,
  val circuitId: String, val trazadoId: String,   // el trazado en uso en el evento
  val status: EventStatus,
)
data class Assignment(           // creada manualmente por admin (v1)
  val id: String, val eventId: String, val officerId: String,
  val role: Area, val puestoId: String, val puestoNumber: Int,
  val shift: String?,
)
data class PuestoMate(val officerId: String, val displayName: String,
  val area: Area, val isChief: Boolean)            // fila → perfil del oficial
data class Session(              // cronograma
  val id: String, val eventId: String, val day: LocalDate, val time: LocalTime,
  val category: String, val name: String, val status: EventStatus, val endsInMin: Int?)
data class ChecklistItem(val id: String, val text: String, val done: Boolean)  // por puesto/día
```

### Circuitos (INDEPENDIENTES del evento)
```kotlin
enum class AssetType { GRUA, AMBULANCIA }
data class Circuit(val id: String, val name: String, val location: String)     // autódromo
data class Trazado(                                     // un autódromo → varios trazados
  val id: String, val circuitId: String, val name: String,
  val lengthM: Int, val curves: Int, val direction: String,                   // define TODO lo de abajo
  val mapUrl: String?,                                  // asset oficial (SVG/imagen) — pendiente
)
data class MapPoint(val x: Float, val y: Float)          // coords normalizadas 0..1 sobre el mapa
data class Puesto(val id: String, val trazadoId: String, val number: Int,
  val label: String?, val point: MapPoint,
  val assignedBefore: Boolean)                           // "Asignado antes" viene del historial del usuario
data class TrackAsset(val id: String, val trazadoId: String, val type: AssetType,
  val label: String, val point: MapPoint)
```

### Campeonatos (solo lectura; ingesta externa + captura admin)
```kotlin
data class Championship(val id: String, val name: String, val season: Int, val emblemUrl: String?)
data class Category(val id: String, val championshipId: String, val name: String)
data class Standing(val pos: Int, val driverNumber: Int, val driverName: String,
  val team: String, val points: Int)
data class Round(val number: Int, val date: LocalDate, val circuitName: String,
  val status: EventStatus, val winner: String?)
data class Driver(val number: Int, val name: String, val team: String)   // fila → detalle piloto (pendiente)
```

### Convocatorias (solo consulta; re-publicadas de un sistema externo)
```kotlin
enum class ConvocatoriaStatus { OPEN, CLOSED }
data class Convocatoria(
  val id: String, val eventName: String, val eventDate: String, val location: String,
  val registrationCloseAt: Instant, val cupo: Int,        // un número total
  val indicacionesMarkdown: String,                       // texto libre (Markdown ligero)
  val status: ConvocatoriaStatus,
  val externalApplyUrl: String?,                          // "Postularme ↗" abre esto
  val participated: Boolean = false,                      // para el historial
)
```

### Agenda y viaje
```kotlin
enum class AgendaKind { EVENT, CONVOCATORIA, TRIP, REMINDER }   // EVENT/CONVOCATORIA del sistema; TRIP/REMINDER del usuario
enum class TripItemKind { TRANSPORT, LODGING, REMINDER }
data class AgendaEntry(val id: String, val kind: AgendaKind, val title: String,
  val at: Instant?, val allDay: Boolean, val location: String?, val eventId: String?)
data class TripItem(val id: String, val eventId: String?, val kind: TripItemKind,
  val title: String, val detail: String?, val at: Instant?)     // planeación personal
```

### Chats
```kotlin
enum class ChatType { EVENT, PUESTO, PUBLIC }
data class Chat(val id: String, val type: ChatType, val name: String,
  val membersCount: Int, val lastPreview: String?, val unread: Int,
  val archived: Boolean, val archivedAt: Instant?)               // evento → solo lectura 1 semana después
data class Message(val id: String, val chatId: String, val senderId: String?,
  val senderName: String, val text: String, val at: Instant, val system: Boolean)
```

### Compartir ubicación (Configuración)
```kotlin
data class LocationSharing(
  val enabled: Boolean,                       // opt-in maestro
  val sharesWith: List<Officer>,              // allowlist que TÚ controlas
  val sharedWithYou: List<Officer>,           // quién te comparte
)                                             // activo solo durante eventos activos; transparente
```

---

## 2. API del backend (Ktor)

Auth: `Authorization: Bearer <JWT corto>`. Errores: problema+json. Paginación por cursor.

### Auth / onboarding
```
POST /auth/magic-link            { email }                → 200 (envía enlace)
GET  /auth/callback?token=...                             → sesión (deep link a la app)
POST /invitations                { email }                → crea invitación (oficial activo)
POST /admin/invitations/{id}/approve                      → ADMIN aprueba cuenta
GET  /me                                                  → Officer + AccountStatus
PATCH /me                        { displayName, avatar, assignedArea }   // sin aprobación
GET  /me/emergency  ·  PUT /me/emergency                  → EmergencyInfo
GET  /me/emergency/access-log                             → List<EmergencyAccess>
```
> Estados de cuenta gobiernan el gate de la UI (invited→pending→active).

### Evento
```
GET /events?scope=mine|open
GET /events/{id}                         → Event + circuito/trazado
GET /events/{id}/assignment/me           → Assignment
GET /events/{id}/mates                   → List<PuestoMate>
GET /events/{id}/schedule?day=           → List<Session>
GET /events/{id}/checklist               ·  PATCH item {done}
```

### Circuitos
```
GET /circuits                            → List<Circuit> (+ nº configuraciones)
GET /circuits/{id}                       → Circuit + List<Trazado>
GET /trazados/{id}                       → Trazado + puestos[] + assets[]   (para el mapa)
```

### Campeonatos (lectura) + INGESTA
```
GET /championships · /championships/{id} · /categories/{id}/standings|rounds|drivers
POST /ingest/championships               (sistema externo, API key) — upsert campeonatos/categorías/pilotos/posiciones/fechas
```
> Mismo modelo lo escribe también un **panel de admin**. Congelar este contrato temprano.

### Convocatorias · Agenda · Chats · Ubicación
```
GET /convocatorias?status=open|past · GET /convocatorias/{id}
GET /agenda?from=&to=  ·  POST/PATCH/DELETE /trips (items personales)
GET /chats · GET /chats/{id}/messages · POST /chats/{id}/messages
WS  /chats/{id}/stream                    (tiempo real)
POST /chats (público) · POST /chats/{id}/join · POST /messages/{id}/report (moderación)
GET/PUT /me/location-sharing              (enabled + allowlist)
GET/PUT /me/notifications                 (preferencias push)
POST /push/register                       (token FCM)
```

### Sincronización offline
- Lectura: endpoints devuelven `updatedAt`/`etag`; cliente hace **delta pull** por recurso.
- Escritura del usuario (checklist, trips, mensajes, toggles): **outbox** local → reintento;
  el backend es idempotente por `clientMutationId`.
- Estado de conexión explícito en UI (banner/degradado, como en el acceso "sin conexión").

---

## 3. Pendientes que afectan el contrato

1. **Catálogo de `Area`** canónico (fuente oficial).
2. **¿OMDAI ID validado** contra una fuente, o libre?
3. **Contrato de ingesta** de campeonatos y de convocatorias (formato del sistema externo).
4. **Mapas de trazado**: formato del asset + de dónde salen las coords de puestos/activos.
5. **Retención** de chats archivados (solo lectura a la semana → ¿cuánto se conservan?).
6. **Moderación**: modelo de reportes y quién administra chats públicos.
