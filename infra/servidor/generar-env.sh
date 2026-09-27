#!/bin/bash
# Escribe /etc/el-puesto/env (EnvironmentFile del backend) y /etc/el-puesto/compose.env
# (solo DB_PASSWORD, para Postgres) desde Parameter Store (/elpuesto/prod/*), ambos 0600 root.
# Sale con 1 si el env del backend cambió (0 si quedó igual): configurar.sh decide reiniciar.
set -euo pipefail
PREFIX=/elpuesto/prod
REGION=mx-central-1
tmp="$(mktemp)"; tmp2="$(mktemp)"
trap 'rm -f "$tmp" "$tmp2"' EXIT

aws ssm get-parameters-by-path --path "$PREFIX" --recursive --with-decryption --region "$REGION" --output json |
    python3 -c '
import json, sys
params = sorted(json.load(sys.stdin)["Parameters"], key=lambda p: p["Name"])
if not params:
    sys.exit("no hay parámetros en Parameter Store (corre infra/aws/5-parametros.sh)")
for p in params:
    clave = p["Name"].rsplit("/", 1)[1]
    valor = p["Value"].replace("\\", "\\\\").replace("\"", "\\\"")
    print(f"{clave}=\"{valor}\"")
' > "$tmp"
# La app web viaja dentro de cada versión del backend (releases/<versión>/web; ver
# scripts/desplegar-backend.sh): su carpeta es fija salvo que Parameter Store diga otra.
grep -q '^WEB_APP_DIR=' "$tmp" || echo 'WEB_APP_DIR="/opt/el-puesto/current/web"' >> "$tmp"
grep '^DB_PASSWORD=' "$tmp" > "$tmp2" || { echo "falta DB_PASSWORD" >&2; exit 2; }

install -m 600 "$tmp2" /etc/el-puesto/compose.env
if cmp -s "$tmp" /etc/el-puesto/env; then
    exit 0
fi
install -m 600 "$tmp" /etc/el-puesto/env
echo "env actualizado: $(cut -d= -f1 /etc/el-puesto/env | tr '\n' ' ')"
exit 1
