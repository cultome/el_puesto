# A · Tema Compose — "Paddock nocturno" (dirección 1a)

Tokens y componentes extraídos de los mockups de Claude Design
(proyecto `9813816d-ac58-4f5b-9657-86ed29c10dcd`). Tema **oscuro (no negro)**,
temática de automovilismo, acento ámbar, colores de banderas como semánticos.

Paquete sugerido: `com.alephri.elpuesto.ui.theme`.

---

## 1. Color

```kotlin
package com.alephri.elpuesto.ui.theme

import androidx.compose.ui.graphics.Color

// —— Superficies (fondo carbón, no negro) ——
val Bg          = Color(0xFF191C24) // fondo base / barra inferior
val Surface     = Color(0xFF1E222B) // superficie media
val SurfaceTop  = Color(0xFF2A2F3A) // tope del gradiente radial del header
val Panel        = Color(0xFF242833) // tarjetas, campos, filas
val PanelAlt     = Color(0xFF20242E) // paneles sutiles / hojas
val PanelElevA   = Color(0xFF2C3141) // tarjeta destacada (gradiente con…)
val PanelElevB   = Color(0xFF232735) // …este segundo tono

// —— Bordes / divisores ——
val Border       = Color(0xFF333949) // borde de componente
val BorderStrong = Color(0xFF3A4152) // borde/tono más marcado
val Divider      = Color(0xFF262B36) // divisor entre filas

// —— Acento (ámbar) ——
val Amber        = Color(0xFFF2B134) // primario
val AmberHi      = Color(0xFFF6BB45) // gradiente botón (inicio)
val AmberLo      = Color(0xFFE79A1F) // gradiente botón (fin)
val OnAmber      = Color(0xFF1C1405) // texto sobre ámbar

// —— Texto ——
val TextHi   = Color(0xFFFFFFFF)
val Text     = Color(0xFFECEEF2) // principal
val TextSub  = Color(0xFFAAB0BD) // secundario
val TextMut  = Color(0xFF8F96A3) // atenuado
val TextFaint= Color(0xFF6F7683) // muy tenue / captions
val Placeholder = Color(0xFF6B7280)

// —— Semánticos (banderas / estado) ——
val Live   = Color(0xFF3FDD85) // verde: EN CURSO / go / éxito
val Danger = Color(0xFFF0524D) // rojo: error / roja
val DangerHi = Color(0xFFFF7A75) // rojo claro (texto destructivo, urgencia)
val Travel = Color(0xFF7FB7F0) // azul: viajes / grúas
val FlagYellow = Color(0xFFFFD23F) // amarillo bandera
```

**Uso en Material 3 (`darkColorScheme`):** `primary = Amber`, `onPrimary = OnAmber`,
`background = Bg`, `surface = Surface`, `onSurface = Text`, `outline = Border`,
`error = Danger`. Los demás (Live, Travel, etc.) van como **extension colors**
(objeto `ElPuestoColors` provisto por `CompositionLocal`), porque no encajan en el
esquema de Material.

> Fondo de pantalla: gradiente radial `radial-gradient(120% 40% at 50% 0%, SurfaceTop, Surface 42%, Bg)`.
> En Compose: `Brush.radialGradient(...)` en el contenedor raíz de cada pantalla.

---

## 2. Tipografía

Tres familias (Google Fonts). En Android: **downloadable fonts** (`com.google.android.gms:play-services-fonts`) o empaquetadas en `res/font`.

- **Archivo** — display y títulos (700/800/900). Números grandes, wordmark, headings.
- **IBM Plex Sans** — cuerpo y UI (400/500/600).
- **IBM Plex Mono** — etiquetas, horas, IDs, código de fecha (500/600).

```kotlin
// Escala usada en los mockups (sp)
Display   = Archivo   ExtraBold 25–34   // títulos de pantalla, números hero
TitleLg   = Archivo   Bold 18–23        // nombres de evento/campeonato
Section   = Archivo   Bold 15–16        // encabezados de sección (h3)
Body      = IBM Plex Sans 400/600 13.5–16
Label     = IBM Plex Sans 600 12.5 (UPPERCASE, letterSpacing 0.3–0.5)
Caption   = IBM Plex Sans 400 11–12
Mono      = IBM Plex Mono 500/600 10–13 (letterSpacing ~1 en labels)
```

Encabezados de sección "kicker" (ámbar, mono, mayúsculas, ~11sp, tracking 1) se usan
en catálogos y detalles: `SEC` → `Color=Amber, IBM Plex Mono, uppercase`.

---

## 3. Espaciado, formas, elevación

```kotlin
object Dp {
  val pagePadding = 22.dp     // padding horizontal estándar de pantalla
  val gapS = 8.dp; val gapM = 12.dp; val gapL = 16.dp; val gapXL = 22.dp
  val rowV = 12.dp            // padding vertical de fila de lista
}
object Radius {
  val field = 14.dp; val card = 16.dp; val cardLg = 18.dp
  val button = 14.dp; val sheet = 24.dp; val pill = 100.dp
  val icon = 10.dp..13.dp     // contenedores de icono
}
```

- **Sombra** solo en el "device"/elementos flotantes (FAB, botón primario): sombra ámbar
  suave `0 12dp 26dp -12dp rgba(Amber, .6)`. Tarjetas: sin sombra, se separan por color/borde.
- **Divisores** internos de grupo: `Divider` 1dp; grupos = `Panel` + `Border` 1dp + `clip(Radius.card)`.

---

## 4. Componentes (recetas)

Todos existen ya en los mockups; catálogo mínimo para Compose:

| Componente | Specs |
|---|---|
| **Botón primario** | alto 54–56dp, `Radius.button`, fondo `Brush.linearGradient(AmberHi→AmberLo)`, texto `OnAmber` 700 15sp, sombra ámbar. |
| **Botón ghost** | transparente, borde `BorderStrong` 1.5dp, texto `Text`. |
| **Campo de texto** | alto 56dp, `Panel`, borde `Border` 1.5dp (foco → `Amber` .6), texto 16sp; label ámbar/mut arriba; helper `TextFaint` abajo. |
| **Chip / tag** | pill; neutro (`PanelAlt`+`Border`, `TextSub`) o seleccionado (`Amber@14%` bg, `Amber` texto, check). |
| **Segmented** | contenedor `PanelAlt`+`Border` `Radius.field`, item activo `Amber` sólido con `OnAmber`. |
| **Toggle (switch)** | 46×28dp, off `BorderStrong`, on `Amber`, knob 22dp (`TextSub`/`OnAmber`). |
| **Fila de lista** | icono 34–46dp `Radius.icon` (tint neutro o `Amber@13%`), título 14sp `Text`, subtítulo 12sp `TextMut`, trailing: valor/toggle/chevron `›`. |
| **Avatar** | círculo; foto o iniciales sobre gradiente ámbar/colores; badge cámara para editar. Es el **avatar global** del sistema. |
| **Status pill "EN CURSO"** | `Live` + punto con halo; para errores usa `Danger`. |
| **Bottom nav** | 4 items (Inicio·Agenda·Chats·Perfil), `Bg`, borde superior `Divider`, activo `Amber`. |
| **Burbuja de chat** | recibida `Panel`+`Border` radio 5/15/15/15; propia `Amber@13%` radio 15/5/15/15; nombre ámbar 11.5sp. |
| **Hoja inferior (sheet)** | `PanelAlt`, `Radius.sheet` arriba, grip; scrim `rgba(10,12,16,.62)`. |
| **FAB** | 56dp `Radius.cardLg`, gradiente ámbar, sombra. |
| **Mapa de circuito** | placeholder por ahora (grid + loop + pines). Ver contrato: puestos/activos con coords normalizadas sobre el mapa oficial (SVG/imagen). |

**Iconografía:** en los mockups se dibujó con CSS por rapidez. Para producción usar un set
consistente (p. ej. **Lucide**/Material Symbols outline) con el mismo peso; los pines de
bandera/tipos mantienen sus colores semánticos.

---

## 5. Accesibilidad / campo

- Alto contraste y tipografía amplia (uso a pleno sol, en pausas).
- Objetivo táctil ≥ 44dp.
- El vistazo clave es el **cronograma / "termina en N min"** — que sea legible y grande.
- Español mexicano, única localización.
