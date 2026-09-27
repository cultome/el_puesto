#!/usr/bin/env bash
# Corre un comando contra PRODUCCIÓN: exporta EL_PUESTO_API (la base de la API admin) y
# EL_PUESTO_ADMIN_KEY leída de Parameter Store en el momento (la clave no se guarda en disco).
#   scripts/prod.sh python3 scripts/cargar-datos.py --dry-run
#   scripts/prod.sh sh -c 'curl -s -H "X-Admin-Key: $EL_PUESTO_ADMIN_KEY" $EL_PUESTO_API/admin/whoami'
# EL_PUESTO_API = https://admin.elpuesto.app (donde vive /admin/* cuando ADMIN_HOST está puesto);
# mientras ese host no responda, https://api.elpuesto.app. Forzar una:
#   ADMIN_API_BASE=https://api.elpuesto.app scripts/prod.sh …
. "$(dirname "$0")/../infra/aws/comun.sh"
[ $# -gt 0 ] || falla "uso: scripts/prod.sh <comando> [args…]"
EL_PUESTO_API="$(admin_api_base)"
export EL_PUESTO_API
EL_PUESTO_ADMIN_KEY="$(leer_parametro ADMIN_API_KEY)"
export EL_PUESTO_ADMIN_KEY
exec "$@"
