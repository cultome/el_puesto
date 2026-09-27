#!/usr/bin/env bash
# Aplica la configuración del SERVIDOR de producción desde un commit (default HEAD): sube
# infra/servidor/ a s3://elpuesto-app-ops/servidor/ y, por SSM, la instancia la baja y corre
# configurar.sh (env desde Parameter Store, systemd, Postgres + Caddy, firewall del IMDS, timer
# del respaldo). Reinicia el backend solo si su env cambió.
#
# SOLO a mano, con TUS credenciales: esos archivos corren como root en la instancia, así que
# GitHub Actions ya no puede tocarlos (su rol no escribe servidor/ ni usa AWS-RunShellScript).
# Úsalo tras cambiar infra/servidor/ o Parameter Store (5-parametros.sh). El despliegue del
# backend (CI o desplegar-backend.sh) NO lo hace por ti.
#   scripts/desplegar-servidor.sh [ref]
#   scripts/desplegar-servidor.sh --revisar [ref]   solo dice si el servidor ya tiene ese commit
. "$(dirname "$0")/../infra/aws/comun.sh"
solo_operador

revisar=0
[ "${1:-}" != --revisar ] || { revisar=1; shift; }
ref="${1:-HEAD}"
sha="$(git -C "$RAIZ" rev-parse --short=10 "$ref^{commit}")"
[ -z "$(git -C "$RAIZ" status --porcelain -- infra/servidor)" ] ||
    echo "ojo: hay cambios sin commitear en infra/servidor/; NO van ($sha)"

if [ "$revisar" = 1 ]; then
    if servidor_al_dia "$sha"; then echo "✓ el servidor tiene infra/servidor/ de $sha"
    else echo "✗ el servidor NO tiene infra/servidor/ de $sha (aplícalo: scripts/desplegar-servidor.sh $sha)"; exit 1; fi
    exit 0
fi

paso "Subiendo infra/servidor/ @ $sha a s3://$BUCKET_OPS/servidor/"
subir_servidor "$sha"

paso "Aplicando en la instancia (configurar.sh)"
ssm_ejecutar "aws s3 sync s3://$BUCKET_OPS/servidor/ /opt/el-puesto/servidor/ --delete --region $REGION --only-show-errors && bash /opt/el-puesto/servidor/configurar.sh" "servidor $sha"
