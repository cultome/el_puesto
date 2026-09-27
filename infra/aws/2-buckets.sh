#!/usr/bin/env bash
# Paso 2: buckets de S3, todos PRIVADOS (bloqueo de acceso público completo, sin ACLs), con
# cifrado en reposo explícito (SSE-S3) y política "solo HTTPS".
#   - $BUCKET_WEB: sitio y APKs; solo CloudFront lo lee (esa parte de la política la pone el
#     paso 4; aquí se conserva). Con versionado: las versiones anteriores se guardan 90 días.
#   - $BUCKET_OPS: despliegues/ (paquetes del backend) y servidor/ (infra/servidor/), con
#     versionado. respaldos/ = los respaldos de ANTES de $BUCKET_RESPALDOS: nadie escribe ahí
#     ya y se borran solos (35 días / ~13 meses).
#   - $BUCKET_RESPALDOS: respaldos de Postgres INMUTABLES. Object Lock en modo COMPLIANCE: cada
#     respaldo lleva su retención desde que se sube (respaldar.sh: diarios 35 días, mensuales
#     400) y NADIE puede borrarlo ni sobrescribirlo antes, ni root de la cuenta. La instancia
#     solo puede escribir (ni leer ni borrar); restaurar lo hace el operador
#     (scripts/restaurar-respaldo.sh). Retención por defecto del bucket: 35 días (red de
#     seguridad si algo se sube sin retención).
# Idempotente: re-correrlo pone todo como aquí dice.
. "$(dirname "$0")/comun.sh"

# Retención y caducidad de los respaldos (días). Mantener iguales a respaldar.sh.
DIAS_DIARIOS=35
DIAS_MENSUALES=400

paso "Buckets"
crear_bucket "$BUCKET_WEB"
crear_bucket "$BUCKET_OPS"
crear_bucket "$BUCKET_RESPALDOS" --object-lock

paso "Versionado de $BUCKET_WEB (una sobrescritura mala del sitio se deshace restaurando la versión anterior)"
aws_r s3api put-bucket-versioning --bucket "$BUCKET_WEB" --versioning-configuration Status=Enabled
aws_r s3api put-bucket-lifecycle-configuration --bucket "$BUCKET_WEB" --lifecycle-configuration '{
  "Rules": [
    {"ID": "versiones-anteriores", "Status": "Enabled", "Filter": {},
     "NoncurrentVersionExpiration": {"NoncurrentDays": 90},
     "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}}
  ]}' > /dev/null

paso "Versionado y ciclo de vida de $BUCKET_OPS"
aws_r s3api put-bucket-versioning --bucket "$BUCKET_OPS" --versioning-configuration Status=Enabled
aws_r s3api put-bucket-lifecycle-configuration --bucket "$BUCKET_OPS" --lifecycle-configuration '{
  "Rules": [
    {"ID": "respaldos-diarios", "Status": "Enabled", "Filter": {"Prefix": "respaldos/diarios/"},
     "Expiration": {"Days": 35}, "NoncurrentVersionExpiration": {"NoncurrentDays": 35}},
    {"ID": "respaldos-mensuales", "Status": "Enabled", "Filter": {"Prefix": "respaldos/mensuales/"},
     "Expiration": {"Days": 400}, "NoncurrentVersionExpiration": {"NoncurrentDays": 35}},
    {"ID": "despliegues", "Status": "Enabled", "Filter": {"Prefix": "despliegues/"},
     "Expiration": {"Days": 120}, "NoncurrentVersionExpiration": {"NoncurrentDays": 7}},
    {"ID": "servidor", "Status": "Enabled", "Filter": {"Prefix": "servidor/"},
     "NoncurrentVersionExpiration": {"NoncurrentDays": 30}},
    {"ID": "multipart", "Status": "Enabled", "Filter": {},
     "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}}
  ]}' > /dev/null

paso "Respaldos inmutables en $BUCKET_RESPALDOS (Object Lock COMPLIANCE)"
aws_r s3api put-bucket-versioning --bucket "$BUCKET_RESPALDOS" --versioning-configuration Status=Enabled
aws_r s3api put-object-lock-configuration --bucket "$BUCKET_RESPALDOS" --object-lock-configuration \
    "{\"ObjectLockEnabled\": \"Enabled\", \"Rule\": {\"DefaultRetention\": {\"Mode\": \"COMPLIANCE\", \"Days\": $DIAS_DIARIOS}}}"
# Al caducar, S3 pone un marcador de borrado y la versión se borra al día siguiente, ya fuera de
# su retención (Object Lock impide borrarla antes, aunque lo pida el ciclo de vida; lo mismo si
# alguien sube otra versión con la misma clave: la original sobrevive hasta su fecha).
aws_r s3api put-bucket-lifecycle-configuration --bucket "$BUCKET_RESPALDOS" --lifecycle-configuration "{
  \"Rules\": [
    {\"ID\": \"diarios\", \"Status\": \"Enabled\", \"Filter\": {\"Prefix\": \"diarios/\"},
     \"Expiration\": {\"Days\": $DIAS_DIARIOS}, \"NoncurrentVersionExpiration\": {\"NoncurrentDays\": 1}},
    {\"ID\": \"mensuales\", \"Status\": \"Enabled\", \"Filter\": {\"Prefix\": \"mensuales/\"},
     \"Expiration\": {\"Days\": $DIAS_MENSUALES}, \"NoncurrentVersionExpiration\": {\"NoncurrentDays\": 1}},
    {\"ID\": \"multipart\", \"Status\": \"Enabled\", \"Filter\": {},
     \"AbortIncompleteMultipartUpload\": {\"DaysAfterInitiation\": 1}}
  ]}" > /dev/null

paso "Solo HTTPS (política de los buckets)"
politica_bucket "$BUCKET_OPS" '[]'
politica_bucket "$BUCKET_RESPALDOS" '[]'
politica_bucket "$BUCKET_WEB"   # conserva la sentencia de CloudFront del paso 4
echo "✓ listo"
