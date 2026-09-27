#!/usr/bin/env bash
# Publica una versión de la app en https://elpuesto.app/descargas/ desde un commit (default
# HEAD). Antes: subir versionName y versionCode en androidApp/build.gradle.kts, sección nueva
# en CHANGELOG.md y commit. El script:
#   1. arma el APK de RELEASE en un worktree temporal contra https://api.elpuesto.app,
#   2. verifica que lo firme la llave de release (huella en infra/aws/comun.sh),
#   3. lo sube INMUTABLE como descargas/el-puesto-<versión>.apk (se niega a pisar una versión
#      ya publicada o a publicar un versionCode que no sea mayor),
#   4. actualiza /version.json (lo que la página y la app leen para saber cuál es la última; lleva
#      las novedades «Para los oficiales» del CHANGELOG que la app muestra al ofrecer actualizar),
#   5. etiqueta el commit v<versión> (local) y republica /descargas/ (enlace y huella nuevos,
#      publicar-descargas.sh) y la landing (publicar-sitio.sh).
# Deja una copia en dist/ para instalar por adb. Solo a mano: el rol de CI no puede escribir
# APKs, version.json ni descargas/.
. "$(dirname "$0")/../infra/aws/comun.sh"
solo_operador

ref="${1:-HEAD}"
sha="$(git -C "$RAIZ" rev-parse --short=10 "$ref^{commit}")"
[ -f "$HOME/.config/el-puesto/release.properties" ] || falla "falta la llave de release (~/.config/el-puesto/release.properties)"

gradle_kts="$(git -C "$RAIZ" show "$sha:androidApp/build.gradle.kts")"
version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' <<<"$gradle_kts")"
codigo="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' <<<"$gradle_kts")"
apk="el-puesto-$version.apk"
[ -n "$version" ] && [ -n "$codigo" ] || falla "no pude leer versionName/versionCode"

paso "El Puesto $version (versionCode $codigo) @ $sha"
if aws_r s3api head-object --bucket "$BUCKET_WEB" --key "descargas/$apk" > /dev/null 2>&1; then
    falla "la $version ya está publicada: sube versionName y versionCode"
fi
publicado="$(aws_r s3 cp "s3://$BUCKET_WEB/version.json" - 2> /dev/null | jq -r '.versionCode // 0' || echo 0)"
[ "$codigo" -gt "${publicado:-0}" ] || falla "versionCode $codigo debe ser mayor que el publicado ($publicado)"
if git -C "$RAIZ" rev-parse -q --verify "refs/tags/v$version" > /dev/null; then
    [ "$(git -C "$RAIZ" rev-parse "v$version^{commit}" | cut -c1-10)" = "$sha" ] || falla "el tag v$version ya existe en otro commit"
fi

tmp="$(mktemp -d)"; wt="$tmp/el_puesto"
git -C "$RAIZ" worktree add --detach --quiet "$wt" "$sha"
trap 'git -C "$RAIZ" worktree remove --force "$wt"; rm -rf "$tmp"' EXIT
cp "$RAIZ/local.properties" "$wt/" 2> /dev/null || true

paso "Compilando el APK de release (API https://$API_DOMINIO)"
(cd "$wt" && "$GRADLE" :androidApp:assembleRelease -PapiBaseUrl="https://$API_DOMINIO" \
    -PupdatesUrl="https://$DOMINIO/version.json" --console=plain -q)
archivo="$wt/androidApp/build/outputs/apk/release/androidApp-release.apk"
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n 1)"
huella="$("$apksigner" verify --print-certs "$archivo" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ "$huella" = "$FIRMA_RELEASE_SHA256" ] || falla "el APK no está firmado con la llave de release (huella: ${huella:-ninguna})"
sha256="$(sha256sum "$archivo" | cut -d' ' -f1)"
bytes="$(stat -c %s "$archivo")"
mkdir -p "$RAIZ/dist" && cp "$archivo" "$RAIZ/dist/$apk"

paso "Subiendo descargas/$apk"
aws_r s3 cp "$archivo" "s3://$BUCKET_WEB/descargas/$apk" --only-show-errors \
    --content-type application/vnd.android.package-archive \
    --cache-control "public, max-age=31536000, immutable" \
    --content-disposition "attachment; filename=\"$apk\""
novedades="$(git -C "$RAIZ" show "$sha:CHANGELOG.md" | "$RAIZ/scripts/novedades.py")"
jq -n --arg v "$version" --argjson c "$codigo" --arg url "https://$DOMINIO/descargas/$apk" \
    --arg sha "$sha256" --argjson bytes "$bytes" --arg fecha "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg commit "$sha" \
    --argjson novedades "$novedades" \
    '{versionName: $v, versionCode: $c, apk: $url, sha256: $sha, bytes: $bytes, publicada: $fecha, commit: $commit,
      novedades: $novedades}' \
    > "$tmp/version.json"
aws_r s3 cp "$tmp/version.json" "s3://$BUCKET_WEB/version.json" --only-show-errors \
    --content-type application/json --cache-control "public, max-age=60"
cat "$tmp/version.json"

git -C "$RAIZ" rev-parse -q --verify "refs/tags/v$version" > /dev/null ||
    git -C "$RAIZ" tag -a "v$version" -m "El Puesto $version" "$sha"

"$RAIZ/scripts/publicar-descargas.sh" "$ref"
"$RAIZ/scripts/publicar-sitio.sh" "$ref"
echo "✓ publicada: https://$DOMINIO/descargas/"
