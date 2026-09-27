#!/bin/bash
# Respaldo de Postgres a S3 (lo corre el timer el-puesto-respaldo, 03:30 hora de CDMX).
# Las imágenes viven en Postgres (tabla images), así que este dump respalda TODO el sistema.
#   s3://elpuesto-app-respaldos/diarios/    retención y caducidad 35 días
#   s3://elpuesto-app-respaldos/mensuales/  el del día 1 de cada mes, 400 días (~13 meses)
# El bucket tiene Object Lock: cada respaldo se sube con retención COMPLIANCE hasta su fecha de
# caducidad y nadie puede borrarlo ni sobrescribirlo antes (tampoco esta instancia, que solo
# puede escribir: ni leer ni borrar). Mantener los días iguales a infra/aws/2-buckets.sh.
# Localmente quedan los 3 más recientes en /var/backups/el-puesto (disco cifrado, 0700 root).
# A mano: systemctl start el-puesto-respaldo && journalctl -u el-puesto-respaldo -n 20
set -euo pipefail
BUCKET=elpuesto-app-respaldos
REGION=mx-central-1
DIR=/var/backups/el-puesto
PG=el-puesto-postgres
DIAS_DIARIOS=35
DIAS_MENSUALES=400

nombre="el_puesto-$(date -u +%Y%m%dT%H%M%SZ).dump"
destino="$DIR/$nombre"
docker exec "$PG" pg_dump -U el_puesto -d el_puesto --format=custom > "$destino.tmp"
# Un dump que pg_restore no puede leer no es respaldo.
docker exec -i "$PG" pg_restore --list < "$destino.tmp" > /dev/null
mv "$destino.tmp" "$destino"

# put-object (una sola petición, hasta 5 GB) para poder fijar la retención al subir; Object
# Lock exige una suma de verificación en la petición (SHA-256, la calcula la CLI).
subir() { # subir PREFIJO DIAS
    aws s3api put-object --bucket "$BUCKET" --key "$1/$nombre" --body "$destino" --region "$REGION" \
        --checksum-algorithm SHA256 --server-side-encryption AES256 \
        --object-lock-mode COMPLIANCE \
        --object-lock-retain-until-date "$(date -u -d "+$2 days" +%Y-%m-%dT%H:%M:%SZ)" > /dev/null
}
subir diarios "$DIAS_DIARIOS"
if [ "$(TZ=America/Mexico_City date +%d)" = 01 ]; then
    subir mensuales "$DIAS_MENSUALES"
fi

ls -1t "$DIR"/*.dump | tail -n +4 | xargs -r rm -f
echo "respaldo OK: $nombre ($(du -h "$destino" | cut -f1))"
