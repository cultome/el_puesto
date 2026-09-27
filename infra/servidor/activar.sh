#!/bin/bash
# Instala y activa una versión del backend (la sube scripts/desplegar-backend.sh a
# s3://elpuesto-app-ops/despliegues/backend-<versión>.tgz). Cada versión vive en su carpeta
# releases/<versión> y `current` apunta a la activa: nunca se sobrescriben jars bajo una JVM
# viva, y si la nueva no contesta /health en 90 s se regresa sola a la anterior.
#   activar.sh <versión>     instala (si hace falta) y activa (documento de SSM ElPuesto-Activar)
#   activar.sh --anterior    regresa a la versión previa (rollback manual, solo el operador)
# Corre como root y el paquete lo arma CI: la versión se valida con el mismo patrón que el
# documento de SSM y el .tgz solo puede traer archivos y carpetas bajo backend/, que se
# desempacan como root (sin conservar dueño ni permisos del paquete).
set -euo pipefail
DIR=/opt/el-puesto
BUCKET=elpuesto-app-ops
REGION=mx-central-1
PATRON='^[0-9]{8}-[0-9]{4}-[0-9a-f]{10}$'

salud() {
    for _ in $(seq 90); do
        curl -fsS http://127.0.0.1:8080/health > /dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}

apuntar() { # cambio atómico del symlink
    ln -sfn "$1" "$DIR/current.nuevo"
    mv -T "$DIR/current.nuevo" "$DIR/current"
}

# ¿El paquete trae solo archivos normales y carpetas, todos bajo backend/ y sin `..`? (Nada de
# enlaces, dispositivos ni rutas que escapen de la carpeta de la versión.)
paquete_sano() { # paquete_sano TGZ → 0 si se puede desempacar
    local nombres detalle malo=0
    nombres="$(mktemp)"; detalle="$(mktemp)"
    if ! tar -tzf "$1" > "$nombres" || ! tar -tvzf "$1" > "$detalle"; then
        echo "el paquete no se puede leer" >&2; malo=1
    fi
    awk 'substr($1, 1, 1) !~ /^[-d]$/ {x = 1} END {exit !x}' "$detalle" &&
        { echo "el paquete trae enlaces o archivos especiales" >&2; malo=1; }
    grep -Evq '^backend/' "$nombres" && { echo "el paquete trae rutas fuera de backend/" >&2; malo=1; }
    grep -Eq '(^|/)\.\.(/|$)' "$nombres" && { echo "el paquete trae rutas con .." >&2; malo=1; }
    [ -s "$nombres" ] || { echo "el paquete está vacío" >&2; malo=1; }
    rm -f "$nombres" "$detalle"
    return "$malo"
}

previa="$(readlink "$DIR/current" 2>/dev/null || true)"

if [ "${1:-}" = --anterior ]; then
    destino="$(ls -1dt "$DIR"/releases/* | grep -v '\.tmp$' | grep -vx "$previa" | head -n 1 || true)"
    [ -n "$destino" ] || { echo "no hay versión anterior" >&2; exit 1; }
else
    version="${1:?uso: activar.sh <versión> | --anterior}"
    [[ "$version" =~ $PATRON ]] || { echo "versión inválida: $version" >&2; exit 2; }
    destino="$DIR/releases/$version"
    if [ ! -x "$destino/bin/backend" ]; then
        tgz="$(mktemp)"
        aws s3 cp "s3://$BUCKET/despliegues/backend-$version.tgz" "$tgz" --region "$REGION" --only-show-errors
        paquete_sano "$tgz" || { rm -f "$tgz"; echo "✗ backend-$version.tgz rechazado" >&2; exit 2; }
        rm -rf "$destino.tmp"; mkdir -p "$destino.tmp"
        tar -xzf "$tgz" -C "$destino.tmp" --strip-components=1 --no-same-owner --no-same-permissions
        rm -f "$tgz"
        mv -T "$destino.tmp" "$destino"
    fi
    touch "$destino"   # orden por fecha = orden de activación (para --anterior)
fi

bash "$DIR/servidor/configurar.sh" --sin-reiniciar
apuntar "$destino"
systemctl restart el-puesto
if salud; then
    echo "✓ activa: $(basename "$destino")"
else
    echo "✗ $(basename "$destino") no respondió /health; últimas líneas:" >&2
    journalctl -u el-puesto -n 40 --no-pager >&2 || true
    if [ -n "$previa" ] && [ "$previa" != "$destino" ]; then
        apuntar "$previa"
        systemctl restart el-puesto
        salud && echo "↩ se regresó a $(basename "$previa")" >&2
    fi
    exit 1
fi

# Conservar las 5 versiones más recientes (nunca la activa).
activa="$(readlink "$DIR/current")"
ls -1dt "$DIR"/releases/* | tail -n +6 | { grep -vx "$activa" || true; } | xargs -r rm -rf
