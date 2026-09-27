# Configuración compartida de la infraestructura de PRODUCCIÓN (AWS). Se incluye con `.`
# desde los scripts de infra/aws/ y scripts/. Sin archivo de estado: cada script busca sus
# recursos por nombre/etiqueta (idempotentes: correrlos dos veces no duplica nada).
# shellcheck shell=bash disable=SC2034  # las constantes las usan los scripts que lo incluyen
set -euo pipefail

# Perfil de la AWS CLI (sobrescribible: AWS_PROFILE=otro infra/aws/...). En GitHub Actions no
# hay perfil: llegan credenciales temporales por env (OIDC, configure-aws-credentials).
if [ -z "${AWS_ACCESS_KEY_ID:-}" ]; then
    export AWS_PROFILE="${AWS_PROFILE:-elpuesto}"
fi
export AWS_PAGER=""

REGION=mx-central-1                 # México (Querétaro): EC2, S3, Parameter Store
DOMINIO=elpuesto.app                # sitio + descargas (CloudFront → S3)
API_DOMINIO=api.elpuesto.app        # API de la app (EC2 → Caddy → backend)
ADMIN_DOMINIO=admin.elpuesto.app    # admin web + API /admin/* (misma instancia; backend: ADMIN_HOST)
BUCKET_WEB=elpuesto-app-web         # sitio estático + APKs (privado; solo CloudFront lo lee; versionado)
BUCKET_OPS=elpuesto-app-ops         # despliegues/ y servidor/ (privado; respaldos/ = los de antes, caducan solos)
BUCKET_RESPALDOS=elpuesto-app-respaldos  # respaldos de Postgres, INMUTABLES (Object Lock COMPLIANCE)
BUCKET_AUDITORIA=elpuesto-app-auditoria  # CloudTrail de la cuenta (9-auditoria.sh)
PARAM_PREFIX=/elpuesto/prod         # Parameter Store: config y secretos del backend
NOMBRE=elpuesto-api                 # etiqueta Name de la instancia, SG, rol e IP
TIPO_INSTANCIA="${TIPO_INSTANCIA:-t4g.small}"

# Documento de SSM que activa una versión del backend (6-servidor.sh lo crea con
# infra/aws/documento-activar.json). Es lo ÚNICO que el rol de GitHub Actions puede correr en la
# instancia; su parámetro solo acepta versiones con este formato (el mismo que arma
# desplegar-backend.sh y revisa activar.sh): <AAAAMMDD>-<HHMM>-<sha de 10>.
DOC_ACTIVAR=ElPuesto-Activar
PATRON_VERSION='^[0-9]{8}-[0-9]{4}-[0-9a-f]{10}$'

# Huella SHA-256 del certificado de la llave de RELEASE (~/.config/el-puesto/release.jks).
# publicar-app.sh se niega a subir un APK firmado con otra llave.
FIRMA_RELEASE_SHA256=2f6837d5ba35604804fbbf56df2f46eac7bfd07846711afeb01435b282e6ac54

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.11+10}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
GRADLE="${GRADLE:-$HOME/gradle-dist/gradle-8.10.2/bin/gradle}"

aws_r() { aws --region "$REGION" "$@"; }
paso() { printf '\n\033[1;33m▸ %s\033[0m\n' "$*"; }
falla() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

cuenta_id() { aws sts get-caller-identity --query Account --output text; }

# Scripts que solo corre el operador con SUS credenciales (tocan lo que corre como root en el
# servidor, la página de descargas o los respaldos). El rol de CI tampoco podría: esto solo da un
# error más claro.
solo_operador() {
    [ -z "${GITHUB_ACTIONS:-}" ] || falla "$(basename "$0") es solo para el operador, no para GitHub Actions"
}

# Instancia del API (la más reciente viva con la etiqueta Name=$NOMBRE).
instancia_id() {
    aws_r ec2 describe-instances \
        --filters "Name=tag:Name,Values=$NOMBRE" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
        --query 'sort_by(Reservations[].Instances[], &LaunchTime)[-1].InstanceId' --output text
}

# Distribución de CloudFront del sitio (la que tiene el alias $DOMINIO).
distribucion_id() {
    aws cloudfront list-distributions \
        --query "DistributionList.Items[?Aliases.Items && contains(Aliases.Items, '$DOMINIO')].Id | [0]" --output text
}

# Manda un documento de SSM (Run Command: sin SSH ni puerto 22) a la instancia, espera y muestra
# su salida. Falla si el comando falla.
ssm_documento() { # ssm_documento DOCUMENTO PARAMETROS_JSON COMENTARIO
    local doc="$1" params="$2" comentario="$3" id cmd estado
    id="$(instancia_id)"
    [ "$id" != "None" ] || falla "no hay instancia $NOMBRE"
    cmd="$(aws_r ssm send-command --instance-ids "$id" --document-name "$doc" \
        --comment "${comentario:0:100}" --parameters "$params" \
        --query Command.CommandId --output text)"
    while :; do
        sleep 3
        estado="$(aws_r ssm get-command-invocation --command-id "$cmd" --instance-id "$id" \
            --query Status --output text 2>/dev/null || echo Pending)"
        case "$estado" in Pending|InProgress|Delayed) ;; *) break ;; esac
    done
    aws_r ssm get-command-invocation --command-id "$cmd" --instance-id "$id" \
        --query '[StandardOutputContent, StandardErrorContent]' --output text
    [ "$estado" = Success ] || falla "el comando terminó en $estado (id $cmd)"
}

# Corre un comando cualquiera como root en la instancia (AWS-RunShellScript). SOLO con las
# credenciales del operador: el rol de GitHub Actions no puede usar este documento.
ssm_ejecutar() { # ssm_ejecutar COMANDO [COMENTARIO]
    ssm_documento AWS-RunShellScript \
        "$(jq -n --arg c "$1" '{commands: [$c], executionTimeout: ["1800"]}')" "${2:-el-puesto}"
}

# Activa en la instancia una versión del backend ya subida a despliegues/ (documento
# $DOC_ACTIVAR → activar.sh). Es lo que usa el despliegue automático.
ssm_activar() { # ssm_activar VERSION
    [[ "$1" =~ $PATRON_VERSION ]] || falla "versión inválida: $1"
    ssm_documento "$DOC_ACTIVAR" "$(jq -n --arg v "$1" '{version: [$v]}')" "backend $1"
}

# infra/servidor/ de un commit en un directorio temporal (mtime = ahora: así `aws s3 sync` sube
# también los archivos que cambiaron sin cambiar de tamaño). Imprime la ruta; el llamador la borra.
servidor_de() { # servidor_de REF
    local tmp; tmp="$(mktemp -d)"
    git -C "$RAIZ" archive "$1" infra/servidor | tar -x -m -C "$tmp"
    echo "$tmp"
}

# Sube infra/servidor/ de un commit a s3://$BUCKET_OPS/servidor/: lo que la instancia baja y
# corre COMO ROOT (configurar.sh, activar.sh, Caddy, systemd…). Solo el operador: el rol de
# GitHub Actions no puede escribir ahí.
subir_servidor() { # subir_servidor REF
    local tmp; tmp="$(servidor_de "$1")"
    aws_r s3 sync "$tmp/infra/servidor/" "s3://$BUCKET_OPS/servidor/" --delete --only-show-errors
    rm -rf "$tmp"
}

# ¿Lo que hay en s3://$BUCKET_OPS/servidor/ (lo último que se aplicó) es infra/servidor/ de este
# commit? Compara MD5 contra el ETag (archivos chicos, subidos en una sola parte).
servidor_al_dia() { # servidor_al_dia REF → 0 si está al día
    local tmp locales remotos
    tmp="$(servidor_de "$1")"
    locales="$(cd "$tmp/infra/servidor" && find . -type f -printf '%P\n' | sort | while read -r f; do
        printf '%s %s\n' "$f" "$(md5sum < "$f" | cut -d' ' -f1)"; done)"
    rm -rf "$tmp"
    remotos="$(aws_r s3api list-objects-v2 --bucket "$BUCKET_OPS" --prefix servidor/ \
        --query 'Contents[].[Key, ETag]' --output text 2>/dev/null |
        sed -e 's#^servidor/##' -e 's/"//g' | awk 'NF == 2 {print $1 " " $2}' | sort || true)"
    [ "$locales" = "$remotos" ]
}

# Base de la API admin en producción (scripts/prod.sh → cargador, curl a /admin/*). La admin vive
# en https://$ADMIN_DOMINIO: con ADMIN_HOST puesto, el backend la sirve SOLO ahí (api responde
# 404 en /admin/*). Mientras ese host no responda (registro DNS pendiente, certificado de Caddy
# en curso) se usa https://$API_DOMINIO, que la sirve hasta que se active ADMIN_HOST.
# Forzar una: ADMIN_API_BASE=https://… scripts/prod.sh …
admin_api_base() {
    if [ -n "${ADMIN_API_BASE:-}" ]; then
        echo "${ADMIN_API_BASE%/}"
    elif curl -fsS --max-time 5 -o /dev/null "https://$ADMIN_DOMINIO/health" 2> /dev/null; then
        echo "https://$ADMIN_DOMINIO"
    else
        echo "ojo: https://$ADMIN_DOMINIO no responde; uso https://$API_DOMINIO (transición; ADMIN_API_BASE=… para forzar)" >&2
        echo "https://$API_DOMINIO"
    fi
}

# Parámetro de Parameter Store sin pasar el valor por la línea de comandos (no queda en `ps`).
poner_parametro() { # poner_parametro NOMBRE VALOR String|SecureString [sobrescribir=true]
    local tmp; tmp="$(mktemp)"; chmod 600 "$tmp"
    jq -n --arg n "$PARAM_PREFIX/$1" --arg v "$2" --arg t "$3" --argjson o "${4:-true}" \
        '{Name: $n, Value: $v, Type: $t, Overwrite: $o}' > "$tmp"
    aws_r ssm put-parameter --cli-input-json "file://$tmp" > /dev/null
    rm -f "$tmp"
}
existe_parametro() { aws_r ssm get-parameter --name "$PARAM_PREFIX/$1" > /dev/null 2>&1; }
leer_parametro() {
    aws_r ssm get-parameter --name "$PARAM_PREFIX/$1" --with-decryption --query Parameter.Value --output text
}

# Bucket PRIVADO (bloqueo de acceso público completo, sin ACLs) con cifrado en reposo explícito
# SSE-S3 (AES-256 con llaves de AWS, sin costo; SSE-KMS es opción, ver docs/DESPLIEGUE.md).
# Con --object-lock se crea con Object Lock (también se puede activar después sobre un bucket
# versionado: ver 2-buckets.sh).
crear_bucket() { # crear_bucket BUCKET [--object-lock]
    local b="$1" err extra=()
    [ "${2:-}" != --object-lock ] || extra=(--object-lock-enabled-for-bucket)
    if err="$(aws_r s3api head-bucket --bucket "$b" 2>&1)"; then
        echo "✓ $b ya existe"
    elif grep -q "404\|Not Found" <<<"$err"; then
        aws_r s3api create-bucket --bucket "$b" --create-bucket-configuration "LocationConstraint=$REGION" \
            "${extra[@]}" > /dev/null
        echo "✓ $b creado"
    else
        falla "$b: $err (¿el nombre ya es de otra cuenta? cámbialo en comun.sh)"
    fi
    aws_r s3api put-public-access-block --bucket "$b" --public-access-block-configuration \
        BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
    aws_r s3api put-bucket-ownership-controls --bucket "$b" \
        --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'
    aws_r s3api put-bucket-encryption --bucket "$b" --server-side-encryption-configuration \
        '{"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}'
}

# Política del bucket = "SoloTLS" (niega cualquier acceso que no llegue por HTTPS, a todos)
# + las sentencias dadas (arreglo JSON). Sin sentencias, conserva las que el bucket ya tenga
# (p. ej. la de CloudFront del paso 4) y solo rehace la SoloTLS.
politica_bucket() { # politica_bucket BUCKET [SENTENCIAS_JSON]
    local b="$1" sentencias="${2:-}"
    if [ -z "$sentencias" ]; then
        sentencias="$(aws_r s3api get-bucket-policy --bucket "$b" --query Policy --output text 2>/dev/null |
            jq -c '[.Statement[] | select(.Sid != "SoloTLS")]' 2>/dev/null || true)"
        [ -n "$sentencias" ] || sentencias='[]'
    fi
    aws_r s3api put-bucket-policy --bucket "$b" --policy "$(jq -n --arg b "$b" --argjson sentencias "$sentencias" '{
      Version: "2012-10-17",
      Statement: ([{Sid: "SoloTLS", Effect: "Deny", Principal: "*", Action: "s3:*",
                    Resource: [("arn:aws:s3:::" + $b), ("arn:aws:s3:::" + $b + "/*")],
                    Condition: {Bool: {"aws:SecureTransport": "false"}}}] + $sentencias)}')"
}
