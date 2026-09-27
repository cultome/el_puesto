# Base local de la app (SQLDelight)

`com/alephri/elpuesto/db/ElPuesto.sq` es el esquema ACTUAL. Los teléfonos se actualizan
encima de la versión anterior (no reinstalan), así que la base que ya tienen se **migra**:

- `databases/N.db` — foto del esquema de la versión N (se versiona; la usa la verificación).
- `com/alephri/elpuesto/db/N.sqm` — migración de la versión N a la N+1.

La versión de la base = número de migraciones + 1 (hoy **1**: la de la v1.1.0, sin `.sqm`).

## Cambiar el esquema

1. Edita `ElPuesto.sq` (tablas, columnas, índices).
2. Crea `com/alephri/elpuesto/db/N.sqm` (N = versión actual) con los `ALTER TABLE` /
   `CREATE TABLE` / `CREATE INDEX` que llevan una base de la versión N a la nueva.
   Columnas nuevas `NOT NULL` necesitan `DEFAULT` (las filas viejas no traen valor).
3. Guarda la foto de la versión nueva:
   `gradle :androidApp:generateDebugDatabaseSchema` → `databases/(N+1).db`.
4. Compila: `preBuild` corre `verifySqlDelightMigration`, que aplica las migraciones sobre
   cada `N.db` y exige que el resultado sea idéntico a `ElPuesto.sq`. Si falta el `.sqm` o
   no cuadra, el build falla.

Si aun así una migración falla en un teléfono (por los datos que tenía), `SafeMigrations`
(`data/Db.kt`) reconstruye la base vacía y rescata la cola de cambios sin enviar: la caché
se vuelve a llenar sola en el siguiente sync.
