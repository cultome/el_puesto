#!/usr/bin/env bash
# Genera web/og.png (1200×630): la imagen de la vista previa al compartir un enlace de
# elpuesto.app (Open Graph / Twitter Card). Toma el trazado del AHR y el logo de
# web/index.html (una sola fuente), rellena scripts/og/tarjeta.html y la fotografía con el
# Chromium sin cabeza de Playwright (~/.cache/ms-playwright). Se commitea el resultado; publicar
# no depende de Chromium. Volver a correrlo si cambia la marca o el trazado de la landing.
set -euo pipefail
cd "$(dirname "$0")/.."

chrome="$(ls -d "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell 2> /dev/null | sort -V | tail -n 1)"
[ -x "$chrome" ] || { echo "no encontré chrome-headless-shell de Playwright" >&2; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

python3 - "$tmp/tarjeta.html" <<'PY'
import re, sys
landing = open("web/index.html", encoding="utf-8").read()
d = re.search(r'<path id="ahr" d="([^"]+)"', landing).group(1)
posts = re.search(r'(<g id="ahr-posts">.*?</g>)', landing, re.S).group(1)
logo = re.search(r'<a class="brand"[^>]*>\s*<img src="(data:image/png;base64,[^"]+)"', landing).group(1)
# El coche, quieto en la recta principal (un punto del propio trazado).
x, y = map(float, re.findall(r"[-\d.]+", d.split("L")[2])[:2])
html = open("scripts/og/tarjeta.html", encoding="utf-8").read()
for k, v in {"AHR_D": d, "AHR_POSTS": posts, "LOGO": logo, "CAR_X": f"{x:.1f}", "CAR_Y": f"{y:.1f}"}.items():
    html = html.replace("{{" + k + "}}", v)
assert "{{" not in html
open(sys.argv[1], "w", encoding="utf-8").write(html)
PY

"$chrome" --headless --hide-scrollbars --force-device-scale-factor=1 --window-size=1200,630 \
    --virtual-time-budget=10000 --screenshot="$tmp/og.png" "file://$tmp/tarjeta.html" 2> /dev/null
# PNG de paleta: nítido y ligero (WhatsApp ignora imágenes de más de ~300 KB).
magick "$tmp/og.png" -strip -colors 256 PNG8:web/og.png
echo "web/og.png: $(magick identify -format '%wx%h' web/og.png), $(( $(stat -c %s web/og.png) / 1024 )) KB"
