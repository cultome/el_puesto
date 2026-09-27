#!/usr/bin/env python3
"""
Convierte el "Track Personnel" de un evento (Excel de OMDAI) en CSV privados.

Formato de entrada (una hoja): fila 1 = encabezados `POST, TIPO` y luego bloques de 3
columnas por rol `<ROL>, ID, NAME` (CPM, COM, BF, YF, TSP, INT 1..7, OP). Una fila por
posición (puesto o activo). Particularidades que se corrigen:
- Excel convierte puestos como "3.2" en fechas (3 de febrero): se recuperan como día.mes.
- Los puestos enteros vienen como 1.0 → "1"; el 0 → "0".
- Nombres con mayúsculas y acentos rotos ("CéSAR GóMEZ") → "César Gómez".
- La fila "COORDINADORES DE ZONA" (ZC) se nombra "Coordinación de zona", como su posición.
- Solo se toman personas con número OMDAI (externos sin número se ignoran, decisión
  2026-09-24). Vacantes, "#N/D", "N/A", "TBC"/"TBD" no generan filas.

Salida (en data/privado/, fuera de git):
- roster.csv: posicion, tipo_origen, rol_origen, omdai_id, nombre
- avisos.txt: anomalías encontradas en el archivo (para revisarlas con quien lo publica)

Requiere openpyxl (`pip install openpyxl`).

    python3 scripts/importar-roster.py "<archivo.xlsx>" data/privado/eventos/2026/gp-mexico
"""
import csv
import datetime
import sys
from pathlib import Path

import openpyxl

PARTICULAS = {"de", "del", "la", "las", "los", "y", "da", "dos", "van", "von"}
VACIOS = {None, "", "#N/D", "#N/A", "N/A", "None", "TBC", "TBD"}


def posicion(v):
    if isinstance(v, datetime.datetime):  # "3.2" que Excel volvió 3-feb
        return f"{v.day}.{v.month}"
    if isinstance(v, float):
        return str(int(v)) if v.is_integer() else f"{v:g}"
    return str(v).strip()


def nombre_propio(n):
    palabras = str(n).strip().lower().split()
    return " ".join(p if (i and p in PARTICULAS) else p[:1].upper() + p[1:] for i, p in enumerate(palabras))


def main(entrada, salida):
    ws = openpyxl.load_workbook(entrada, data_only=True).worksheets[0]
    filas = list(ws.iter_rows(values_only=True))
    enc = filas[0]
    if str(enc[0]).strip().upper() != "POST" or str(enc[1]).strip().upper() != "TIPO":
        sys.exit("formato inesperado: la fila 1 debe empezar con POST, TIPO")
    bloques = [(i, str(enc[i]).strip()) for i in range(2, len(enc) - 2, 3)
               if enc[i] and str(enc[i + 1]).strip().upper() == "ID"]
    roster, avisos, vistos = [], [], {}
    for n, f in enumerate(filas[1:], start=2):
        pos = posicion(f[0])
        if f[0] is None or pos.upper().startswith("TOTAL"):
            continue
        tipo = str(f[1]).strip() if f[1] else ""
        if tipo.upper().startswith("COORDINADORES DE ZONA"):
            pos = "Coordinación de zona"  # misma etiqueta que la posición sin mapa
        for i, rol in bloques:
            idv, nom = f[i + 1], f[i + 2]
            nom = None if nom in VACIOS or str(nom).strip() in VACIOS else str(nom).strip()
            if isinstance(idv, (int, float)) and idv > 1:
                omdai = int(idv)
                if nom is None:
                    avisos.append(f"fila {n} · {pos} · {rol}: OMDAI {omdai} sin nombre (se carga con nombre vacío)")
                if omdai in vistos:
                    avisos.append(f"fila {n} · {pos} · {rol}: OMDAI {omdai} repetido (también en {vistos[omdai]})")
                vistos[omdai] = f"{pos} {rol}"
                roster.append([pos, tipo, rol, omdai, nombre_propio(nom) if nom else ""])
            elif isinstance(idv, (int, float)) and idv == 1 and nom:
                avisos.append(f"fila {n} · {pos} · {rol}: '{nom}' con ID 1 (inválido) — no se carga")
            elif nom:
                pass  # externo sin número OMDAI: se ignora (decisión 2026-09-24)
    salida = Path(salida)
    salida.mkdir(parents=True, exist_ok=True)
    with open(salida / "roster.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["posicion", "tipo_origen", "rol_origen", "omdai_id", "nombre"])
        w.writerows(roster)
    (salida / "avisos.txt").write_text("\n".join(avisos) + ("\n" if avisos else ""), encoding="utf-8")
    print(f"{len(roster)} asignaciones con número OMDAI → {salida / 'roster.csv'}; {len(avisos)} avisos")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
