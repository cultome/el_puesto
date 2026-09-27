# data/privado — datos personales (NO versionados)

Esta carpeta está en `.gitignore`: aquí viven los datos de personas (correos, nombres,
OMDAI ID). Mismo formato CSV que el resto de `data/` y mismo script de carga.

## `cuentas.csv`

Encabezados: `correo,omdai_id,nombre,area,estatus,activo_desde`

- `correo`: el de acceso (magic link). Nunca se muestra en la app.
- `omdai_id`: número de oficial (llave del oficial en el sistema).
- `nombre`: nombre para mostrar.
- `area`: INTERVENCION | COMUNICACION | RECOVERY | ESCRUTINIO | MEDICO (o vacío).
- `estatus`: ACTIVE (default) | PENDING_APPROVAL | INVITED | SUSPENDED.
- `activo_desde`: año (opcional).

## Otros archivos privados

- `eventos/<año>/<evento>/roster.csv` — roster de un evento (sale de
  `scripts/importar-roster.py`); `data/eventos/eventos.csv` apunta a él.
- `eventos/roles-omdai.csv` — mapeo de las columnas del "Track Personnel" de OMDAI a los roles
  de la app (`posicion,rol_origen,rol,nota`).
- `circuitos/posiciones/<circuito>/<trazado>.csv` — posiciones de puestos y activos tomadas de
  un plano que no es público (hoy `rodriguez/gran-premio.csv`, del "Marshal Posts" de OMDAI).
  Mismo formato que `data/circuitos/posiciones/` (ver `data/README.md`).

Respaldo: nada de esto está en git; respáldalo aparte (lo ya cargado sigue en producción).
