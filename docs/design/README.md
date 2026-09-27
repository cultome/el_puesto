# Diseños (mockups) — El puesto

Copia versionada de los mockups de **Claude Design** (proyecto
`9813816d-ac58-4f5b-9657-86ed29c10dcd`), dirección visual **"1a Paddock nocturno"**.
Son la referencia visual de la UI; la implementación vive en `androidApp/`.

## Cómo verlos

Son archivos **Design Components** (`.dc.html`) que se renderizan con `support.js`
(incluido aquí). Cada uno muestra las pantallas como mockups de teléfono en un lienzo.

- Ábrelos en un navegador (necesitan servirse por HTTP para que cargue `./support.js`):
  ```
  cd docs/design && python3 -m http.server 8000
  # luego visita http://localhost:8000/ y abre cada .dc.html
  ```
- O edítalos/actualízalos en el proyecto de Claude Design (fuente canónica).

> Se les removió el runtime de preview inyectado por el servidor; por lo demás son la
> fuente original. Si actualizas un diseño en Claude Design, vuelve a exportarlo aquí.

## Índice de pantallas

| Archivo | Módulo |
|---|---|
| `Acceso - direcciones.dc.html` | Comparativa de 3 direcciones visuales (se eligió 1a) |
| `Acceso 1a - flujo.dc.html` | Acceso: bienvenida, enlace enviado, pendiente, sin invitación, sin conexión |
| `Completar perfil.dc.html` | Onboarding: identidad + emergencia (opcional) |
| `Home.dc.html` | Pantalla principal (evento activo, agenda, catálogos) |
| `Modo evento.dc.html` | Puesto · Cronograma · Chat |
| `Perfil.dc.html` | Mi perfil · perfil de otro oficial |
| `Circuitos.dc.html` | Catálogo · detalle con trazado + mapa · selector de trazado |
| `Campeonatos.dc.html` | Catálogo · posiciones · calendario · pilotos |
| `Agenda.dc.html` | Calendario · viaje · agregar |
| `Convocatorias.dc.html` | Lista · detalle (Markdown) · historial |
| `Chats.dc.html` | Hub · públicos · conversación · archivado |
| `Configuracion.dc.html` | Ajustes · compartir ubicación |
| `Agenda - detalle C/` | Detalle de entrada de agenda, opción C "Tu fin de semana" (elegida 2026-09-25): evento que trabajas, carrera del calendario, transporte, hospedaje, recordatorio. Canvas completo con las opciones A/B/C: artifact `HH9qoPJgXEExXwTwCWH5kH` |
| `Home - Explorar (tarjetas).dc.html` | Explorar del Home, opción A "Tarjetas con motivo" (elegida 2026-09-25) |
| `Logros/` | Gamificación (2026-09-25): lenguaje visual de insignias, marca «trabajaste aquí» del catálogo (opción A), detalle de circuito «Tu historia aquí», perfil con logros, pasaporte, todos los logros, detalle, perfil ajeno, y los momentos (medalla nueva, recuerdo, tu temporada). Canvas: artifact `GYQJFrNpxTHBQUcgyWKrHR` |
| `Agenda - lo que viene/` | Agenda (2026-09-27): al entrar, "lo que viene" (mes actual desde hoy, anteriores colapsadas, "Siguiente mes"); tocar un día filtra la lista a ese día (lo de varios días en cada día que abarca); carreras del calendario en filas compactas; puntos por tipo y franja de "Trabajas" en el calendario. `Main.dc.html` es el componente interactivo; los otros tableros lo importan con otra escena. Canvas: artifact `MCgbnkFT2gsx6rJYQHdSuE` |
| `Iconos bottom bar/` | Íconos de la barra inferior (2026-09-27): los actuales frente a tres direcciones — A «Línea limpia», B «Pista», C «Paddock» — y `Main.dc.html` para combinarlas. Elegida: C con el calendario a cuadros de B (box de pits · calendario a cuadros · radio). Canvas: artifact `PGep6uo27CbhUwxmjV531F` |
| `Mapas homologados/` | Mapas (2026-09-27): el MISMO mapa en Circuito, tab Puesto, Mapa en vivo y Registro por honor. Capas Puestos · Rescate · Soporte · Médicos que se prenden y apagan solas (barra bajo el mapa en la tarjeta, arriba en pantalla completa; las vacías no salen), marcadores en contorno con el color de su capa (naranja de overol, amarillo, verde, rojo) y relleno solo para lo especial (tu puesto, lo elegido, «asignado antes»), controles en las mismas esquinas. Elegida: A (barra); B (botón con panel) descartada. `Main.dc.html` = hoja del sistema. Canvas: artifact `MraNCNypGeRWcnECWnAtR9` |
