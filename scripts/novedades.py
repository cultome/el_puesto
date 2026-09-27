#!/usr/bin/env python3
"""Novedades de la app para /version.json, sacadas del CHANGELOG.

Lee un CHANGELOG.md por stdin e imprime un arreglo JSON con las últimas versiones, la más
nueva primero: [{"version": "1.2.0", "notas": "- …\\n- …"}]. De cada versión solo toma la
sección «### Para los oficiales» (lo de administración o producción no le importa al
oficial). Las viñetas partidas en varias líneas se juntan en una sola: la app pinta una
línea por viñeta.

Uso: git show HEAD:CHANGELOG.md | scripts/novedades.py [máximo de versiones, default 5]
"""
import json
import re
import sys

maximo = int(sys.argv[1]) if len(sys.argv) > 1 else 5
texto = sys.stdin.read()

novedades = []
for seccion in re.split(r"^## v", texto, flags=re.M)[1:]:
    version = seccion.split()[0]
    m = re.search(r"^### Para los oficiales[^\n]*\n(.*?)(?=^##|\Z)", seccion, flags=re.M | re.S)
    if not m:
        continue
    vinetas = []
    for linea in m.group(1).splitlines():
        if linea.lstrip().startswith("- "):  # las anidadas quedan como viñetas propias
            vinetas.append(linea.lstrip()[2:].strip())
        elif linea.startswith("  ") and linea.strip() and vinetas:
            vinetas[-1] += " " + linea.strip()
    if vinetas:
        novedades.append({"version": version, "notas": "\n".join("- " + v for v in vinetas)})
    if len(novedades) >= maximo:
        break

json.dump(novedades, sys.stdout, ensure_ascii=False)
print()
