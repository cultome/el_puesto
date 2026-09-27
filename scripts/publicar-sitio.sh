#!/usr/bin/env bash
# Publica el sitio (web/ de un commit, default HEAD) en https://elpuesto.app: rellena la versión
# de la landing con /version.json (la última versión publicada por publicar-app.sh), sube a S3 e
# invalida la caché de CloudFront. Lo corre GitHub Actions en cada push que toca web/.
#
# NO toca /descargas/ (la página con la huella SHA-256 del APK y los APKs) ni version.json: esos
# los publica el operador (scripts/publicar-descargas.sh, que también corre publicar-app.sh). El
# rol de CI tiene prohibido escribirlos, así que un CI comprometido no puede cambiar el enlace
# ni la huella que la gente usa para verificar el APK.
#   scripts/publicar-sitio.sh [ref]
. "$(dirname "$0")/../infra/aws/comun.sh"

ref="${1:-HEAD}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git -C "$RAIZ" archive "$ref" web | tar -x -C "$tmp"
rm -rf "$tmp/web/descargas" "$tmp/web/.well-known" "$tmp/web/version.json"
aws_r s3 cp "s3://$BUCKET_WEB/version.json" "$tmp/version.json" --only-show-errors 2> /dev/null ||
    echo '{}' > "$tmp/version.json"

python3 - "$tmp/web" "$tmp/version.json" <<'PY'
import json, re, sys
web, vj = sys.argv[1], json.load(open(sys.argv[2]))
landing = open(f"{web}/index.html", encoding="utf-8").read()
if vj.get("versionName"):
    landing = re.sub(r"<!--v-->.*?<!--/v-->", vj["versionName"], landing)
    landing = re.sub(r'("softwareVersion":\s*")[^"]*(")', lambda m: m.group(1) + vj["versionName"] + m.group(2), landing)
open(f"{web}/index.html", "w", encoding="utf-8").write(landing)
PY

paso "Subiendo el sitio a s3://$BUCKET_WEB (sin descargas/)"
aws_r s3 cp "$tmp/web/" "s3://$BUCKET_WEB/" --recursive --only-show-errors --cache-control "public, max-age=300"

dist="$(distribucion_id)"
[ "$dist" != None ] || falla "no encontré la distribución de CloudFront de $DOMINIO"
aws cloudfront create-invalidation --distribution-id "$dist" --paths '/*' --query Invalidation.Id --output text > /dev/null
echo "✓ https://$DOMINIO (la caché de CloudFront se refresca en ~1 min)"
