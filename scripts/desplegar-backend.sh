#!/usr/bin/env bash
# Despliega el backend (con la app web adentro: releases/<versión>/web) a PRODUCCIÓN desde un
# commit (default HEAD) — nunca desde el working tree. Son dos mitades, que GitHub Actions corre en jobs separados
# (.github/workflows/desplegar-backend.yml) y a mano van seguidas:
#   construir  installDist en un worktree temporal del commit → backend-<versión>.tgz. Sin AWS:
#              en CI corre sin credenciales (Gradle y sus plugins nunca las ven).
#   activar    sube el .tgz a s3://…/despliegues/ y lo activa con el documento de SSM
#              ElPuesto-Activar (activar.sh: carpeta por versión + symlink + /health, con regreso
#              automático si falla). Sin Gradle. Es lo único que el rol de CI puede correr ahí.
# La configuración del SERVIDOR (infra/servidor/: Caddy, compose, systemd, los scripts que corren
# como root) NO viaja aquí: es scripts/desplegar-servidor.sh, a mano y con tus credenciales.
#
#   scripts/desplegar-backend.sh [ref]                   construye y activa (a mano)
#   scripts/desplegar-backend.sh --construir DIR [ref]   solo construye: DIR/backend-<versión>.tgz + DIR/version
#   scripts/desplegar-backend.sh --activar DIR           solo sube y activa lo que dejó --construir
#   scripts/desplegar-backend.sh --anterior              regresa a la versión previa (a mano)
#   scripts/desplegar-backend.sh --solo-config [ref]     = scripts/desplegar-servidor.sh [ref]
. "$(dirname "$0")/../infra/aws/comun.sh"

modo=todo
case "${1:-}" in
--anterior)
    solo_operador
    ssm_ejecutar "bash /opt/el-puesto/servidor/activar.sh --anterior" "backend: versión anterior"
    exit 0 ;;
--solo-config)
    shift
    exec "$RAIZ/scripts/desplegar-servidor.sh" "$@" ;;
--construir)
    modo=construir; salida="${2:?uso: --construir DIR [ref]}"; ref="${3:-HEAD}" ;;
--activar)
    modo=activar; salida="${2:?uso: --activar DIR}" ;;
-*)
    falla "opción desconocida: $1 (ver el encabezado del script)" ;;
*)
    ref="${1:-HEAD}" ;;
esac

tmp="$(mktemp -d)"; wt=""
trap '[ -z "$wt" ] || git -C "$RAIZ" worktree remove --force "$wt"; rm -rf "$tmp"' EXIT

# ——— construir ———
if [ "$modo" != activar ]; then
    completo="$(git -C "$RAIZ" rev-parse "$ref^{commit}")"
    sha="${completo:0:10}"
    version="$(date -u +%Y%m%d-%H%M)-$sha"
    [[ "$version" =~ $PATRON_VERSION ]] || falla "versión mal formada: $version"
    [ -z "$(git -C "$RAIZ" status --porcelain)" ] || echo "ojo: hay cambios sin commitear; NO van en el despliegue ($sha)"
    [ "$modo" = construir ] || salida="$tmp/salida"
    mkdir -p "$salida"

    wt="$tmp/el_puesto"
    git -C "$RAIZ" worktree add --detach --quiet "$wt" "$completo"
    cp "$RAIZ/local.properties" "$wt/" 2> /dev/null || true   # Gradle configura también :androidApp

    paso "Compilando el backend y la app web @ $sha"
    (cd "$wt" && "$GRADLE" :backend:installDist :webApp:wasmJsBrowserDistribution --console=plain -q)
    # La app web viaja DENTRO de la versión del backend (releases/<versión>/web, WEB_APP_DIR):
    # se activa y se regresa junto con él. Sin mapas de fuentes (.map).
    mkdir -p "$wt/backend/build/install/backend/web"
    cp -R "$wt/webApp/build/dist/wasmJs/productionExecutable/." "$wt/backend/build/install/backend/web/"
    find "$wt/backend/build/install/backend/web" -name '*.map' -delete
    [ -f "$wt/backend/build/install/backend/web/index.html" ] || falla "la app web no quedó en el paquete"
    tar -czf "$salida/backend-$version.tgz" --owner=0 --group=0 --numeric-owner \
        -C "$wt/backend/build/install" backend
    echo "$version" > "$salida/version"
    echo "✓ backend-$version.tgz ($(du -h "$salida/backend-$version.tgz" | cut -f1))"
    [ "$modo" = construir ] && exit 0

    # A mano: avisar si el servidor no tiene la configuración de este commit (CI no la aplica).
    servidor_al_dia "$completo" ||
        echo "ojo: infra/servidor/ de este commit no es lo que tiene el servidor; si el backend lo necesita, antes: scripts/desplegar-servidor.sh $sha"
fi

# ——— activar ———
version="$(cat "$salida/version")"
[[ "$version" =~ $PATRON_VERSION ]] || falla "versión inválida en $salida/version: $version"
tgz="$salida/backend-$version.tgz"
[ -f "$tgz" ] || falla "no encontré $tgz"

paso "Subiendo backend-$version.tgz"
aws_r s3 cp "$tgz" "s3://$BUCKET_OPS/despliegues/backend-$version.tgz" --only-show-errors

paso "Activando en la instancia ($DOC_ACTIVAR)"
ssm_activar "$version"

paso "https://$API_DOMINIO/health"
curl -fsS --max-time 15 "https://$API_DOMINIO/health" && echo ||
    echo "aún no responde por https (¿registro A de api o certificado de Caddy en curso?)"
