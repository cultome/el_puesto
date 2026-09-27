#!/bin/bash
# DESTRUCTIVO: reemplaza la base de producción con un respaldo. Detiene el backend mientras
# restaura y, antes de pisar nada, respalda lo que hay (por si el elegido era el equivocado).
# La instancia NO puede leer los respaldos (su rol solo escribe en el bucket de respaldos): se
# lo pasa el operador desde su máquina con scripts/restaurar-respaldo.sh, que arma un enlace
# firmado de 15 minutos. Uso (en el servidor, como root):
#   CONFIRMAR=si restaurar.sh --url 'https://elpuesto-app-respaldos.s3.….amazonaws.com/diarios/…'
#   CONFIRMAR=si restaurar.sh --archivo /var/backups/el-puesto/el_puesto-20261001T093000Z.dump
set -euo pipefail
PG=el-puesto-postgres
archivo=/var/backups/el-puesto/restaurar.dump

[ "${CONFIRMAR:-}" = si ] || { echo "Esto BORRA la base actual. Repite con CONFIRMAR=si" >&2; exit 1; }
case "${1:-}" in
--url)
    url="${2:?falta el enlace}"
    [[ "$url" =~ ^https://[a-z0-9.-]+\.amazonaws\.com/ ]] || { echo "el enlace debe ser https://…amazonaws.com/" >&2; exit 1; }
    trap 'rm -f "$archivo"' EXIT
    curl -fsS --proto '=https' --retry 3 -o "$archivo" "$url" ;;
--archivo)
    trap 'rm -f "$archivo"' EXIT
    cp -- "${2:?falta el archivo}" "$archivo" ;;
*)
    echo "uso: CONFIRMAR=si restaurar.sh --url ENLACE | --archivo RUTA (lo arma scripts/restaurar-respaldo.sh)" >&2
    exit 1 ;;
esac
docker exec -i "$PG" pg_restore --list < "$archivo" > /dev/null

# Antes de pisar nada, un respaldo de lo que hay ahora (queda inmutable en el bucket).
bash /opt/el-puesto/servidor/respaldar.sh

systemctl stop el-puesto
docker exec -i "$PG" pg_restore -U el_puesto -d el_puesto --clean --if-exists --no-owner --single-transaction < "$archivo"
systemctl start el-puesto
echo "restaurado ($(du -h "$archivo" | cut -f1))"
