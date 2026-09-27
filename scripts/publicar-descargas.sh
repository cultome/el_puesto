#!/usr/bin/env bash
# Publica https://elpuesto.app/descargas/ (la plantilla web/descargas/index.html de un commit,
# default HEAD) rellenada con /version.json: versión, fecha, tamaño, enlace y huella SHA-256 del
# APK publicado. Sube solo descargas/index.html e invalida /descargas/*.
#
# SOLO a mano, con TUS credenciales: el rol de GitHub Actions tiene prohibido escribir
# descargas/ (y los APKs y version.json), para que un CI comprometido no pueda cambiar el enlace
# ni la huella con la que la gente verifica lo que instala. publicar-app.sh lo corre solo al
# publicar una versión; a mano, tras cambiar el texto de la página (p. ej. la lista de permisos).
#   scripts/publicar-descargas.sh [ref]
. "$(dirname "$0")/../infra/aws/comun.sh"
solo_operador

ref="${1:-HEAD}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git -C "$RAIZ" archive "$ref" web/descargas/index.html | tar -x -C "$tmp"
aws_r s3 cp "s3://$BUCKET_WEB/version.json" "$tmp/version.json" --only-show-errors 2> /dev/null ||
    echo '{}' > "$tmp/version.json"

python3 - "$tmp/web/descargas/index.html" "$tmp/version.json" <<'PY'
import json, re, sys
from datetime import datetime, timedelta, timezone
ruta, vj = sys.argv[1], json.load(open(sys.argv[2]))
MESES = "enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre".split()

pagina = open(ruta, encoding="utf-8").read()
if vj.get("apk"):
    # Hora de CDMX (UTC-6 todo el año desde 2022: México quitó el horario de verano).
    f = datetime.fromisoformat(vj["publicada"].replace("Z", "+00:00")).astimezone(timezone(timedelta(hours=-6)))
    valores = {
        "VERSION": vj["versionName"],
        "FECHA": f"{f.day} de {MESES[f.month - 1]} de {f.year}",
        "MB": f"{vj['bytes'] / 1_048_576:.1f}",
        "APK_URL": "/descargas/" + vj["apk"].rsplit("/", 1)[1],
        "APK_NOMBRE": vj["apk"].rsplit("/", 1)[1],
        "SHA256": vj["sha256"],
        "APK_URL_ABS": vj["apk"],
        "FECHA_ISO": f.date().isoformat(),
    }
    for k, v in valores.items():
        pagina = pagina.replace("{{" + k + "}}", v)
else:
    pagina = re.sub(r"<!--descarga-->.*?<!--/descarga-->",
                    '<p class="meta">Pronto: aún no hay una versión publicada.</p>', pagina, flags=re.S)
    pagina = re.sub(r"<!--ld-->.*?<!--/ld-->", "", pagina, flags=re.S)
    pagina = pagina.replace("{{APK_NOMBRE}}", "el-puesto.apk")
if "{{" in pagina:
    sys.exit("quedaron marcadores sin rellenar en descargas/index.html")
open(ruta, "w", encoding="utf-8").write(pagina)
PY

paso "Subiendo descargas/index.html"
aws_r s3 cp "$tmp/web/descargas/index.html" "s3://$BUCKET_WEB/descargas/index.html" --only-show-errors \
    --content-type "text/html; charset=utf-8" --cache-control "public, max-age=300"

dist="$(distribucion_id)"
[ "$dist" != None ] || falla "no encontré la distribución de CloudFront de $DOMINIO"
aws cloudfront create-invalidation --distribution-id "$dist" --paths '/descargas/*' --query Invalidation.Id --output text > /dev/null
echo "✓ https://$DOMINIO/descargas/ (la caché de CloudFront se refresca en ~1 min)"
