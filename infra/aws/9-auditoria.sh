#!/usr/bin/env bash
# Paso 9: rastro de auditoría de la CUENTA de AWS (quién hizo qué, desde la consola, la CLI o
# la API) y avisos inmediatos de lo más delicado:
#   - CloudTrail "elpuesto-auditoria": eventos de administración de TODAS las regiones (incluye
#     los globales: IAM, STS, CloudFront, inicios de sesión en la consola) con validación de
#     integridad (resúmenes firmados cada hora), a un bucket propio $BUCKET_AUDITORIA: privado,
#     cifrado, solo HTTPS, versionado y con caducidad de 365 días.
#   - EventBridge → SNS → correo, en us-east-1 (inicios de sesión y servicios globales),
#     $REGION (donde vive todo) y $REGION_SSO (IAM Identity Center): aviso cuando se usa la cuenta
#     ROOT (consola o API), cuando falla un inicio de sesión en la consola (root o usuario de IAM)
#     y cuando falla una contraseña o un código MFA en el portal de IAM Identity Center (evento
#     CredentialVerification = Failure; solo existe en $REGION_SSO). El correo pide confirmar la
#     suscripción UNA vez por región.
# Costo ≈ 0: la primera copia de los eventos de administración es gratis en CloudTrail; el
# bucket guarda pocos MB al mes (centavos); EventBridge no cobra los eventos de servicios de AWS
# y SNS regala 1,000 correos al mes.
#
#   9-auditoria.sh [CORREO]    (default: ALERT_EMAIL de Parameter Store, ver 5-parametros.sh alertas)
#
# Ojo: mientras la CLI entre como root, cada comando tuyo dispararía un correo. Primero crea un
# administrador en IAM Identity Center y usa ese perfil (docs/DESPLIEGUE.md); el script se niega
# a correr como root salvo con ACEPTO_CORREOS_ROOT=si. Idempotente.
. "$(dirname "$0")/comun.sh"
CUENTA="$(cuenta_id)"
TRAIL=elpuesto-auditoria
TEMA=elpuesto-alertas-cuenta
# Región de la instancia de IAM Identity Center de la cuenta (docs/DESPLIEGUE.md → Acceso de
# operador): ahí se registran los inicios de sesión del portal.
REGION_SSO=us-west-2
REGIONES_ALERTA=(us-east-1 "$REGION" "$REGION_SSO")
DIAS_AUDITORIA=365

correo="${1:-}"
[ -n "$correo" ] || correo="$(leer_parametro ALERT_EMAIL 2> /dev/null || true)"
[ -n "$correo" ] || falla "uso: 9-auditoria.sh CORREO (o define ALERT_EMAIL: 5-parametros.sh alertas CORREO)"

# Id del directorio de Identity Center (va en el aviso para identificar al usuario; no se
# versiona: se lee de la cuenta).
ALMACEN_SSO="$(aws --region "$REGION_SSO" sso-admin list-instances --query 'Instances[0].IdentityStoreId' --output text)"
[ -n "$ALMACEN_SSO" ] && [ "$ALMACEN_SSO" != None ] ||
    falla "no encontré la instancia de IAM Identity Center en $REGION_SSO (docs/DESPLIEGUE.md → Acceso de operador)"

llamante="$(aws sts get-caller-identity --query Arn --output text)"
if [[ "$llamante" == *":root" ]] && [ "${ACEPTO_CORREOS_ROOT:-}" != si ]; then
    falla "estás usando la cuenta ROOT ($llamante): con la alerta de uso de root, cada comando
  tuyo mandaría un correo. Crea un administrador en IAM Identity Center, usa ese perfil
  (AWS_PROFILE) y vuelve a correrlo. Para correrlo así de todos modos: ACEPTO_CORREOS_ROOT=si"
fi

paso "Bucket $BUCKET_AUDITORIA (privado, solo HTTPS, $DIAS_AUDITORIA días)"
crear_bucket "$BUCKET_AUDITORIA"
aws_r s3api put-bucket-versioning --bucket "$BUCKET_AUDITORIA" --versioning-configuration Status=Enabled
aws_r s3api put-bucket-lifecycle-configuration --bucket "$BUCKET_AUDITORIA" --lifecycle-configuration "{
  \"Rules\": [
    {\"ID\": \"auditoria\", \"Status\": \"Enabled\", \"Filter\": {},
     \"Expiration\": {\"Days\": $DIAS_AUDITORIA}, \"NoncurrentVersionExpiration\": {\"NoncurrentDays\": 30}},
    {\"ID\": \"multipart\", \"Status\": \"Enabled\", \"Filter\": {},
     \"AbortIncompleteMultipartUpload\": {\"DaysAfterInitiation\": 7}}
  ]}" > /dev/null
trail_arn="arn:aws:cloudtrail:$REGION:$CUENTA:trail/$TRAIL"
politica_bucket "$BUCKET_AUDITORIA" "$(jq -n --arg b "arn:aws:s3:::$BUCKET_AUDITORIA" \
    --arg trail "$trail_arn" --arg cuenta "$CUENTA" '[
  {Sid: "CloudTrailAcl", Effect: "Allow", Principal: {Service: "cloudtrail.amazonaws.com"},
   Action: "s3:GetBucketAcl", Resource: $b, Condition: {StringEquals: {"aws:SourceArn": $trail}}},
  {Sid: "CloudTrailEscribe", Effect: "Allow", Principal: {Service: "cloudtrail.amazonaws.com"},
   Action: "s3:PutObject", Resource: ($b + "/AWSLogs/" + $cuenta + "/*"),
   Condition: {StringEquals: {"s3:x-amz-acl": "bucket-owner-full-control", "aws:SourceArn": $trail}}}]')"
echo "✓"

paso "CloudTrail $TRAIL (todas las regiones, eventos de administración, validación de integridad)"
if [ "$(aws_r cloudtrail describe-trails --trail-name-list "$TRAIL" --query 'trailList[0].Name' --output text)" = "$TRAIL" ]; then
    aws_r cloudtrail update-trail --name "$TRAIL" --s3-bucket-name "$BUCKET_AUDITORIA" \
        --is-multi-region-trail --include-global-service-events --enable-log-file-validation > /dev/null
else
    aws_r cloudtrail create-trail --name "$TRAIL" --s3-bucket-name "$BUCKET_AUDITORIA" \
        --is-multi-region-trail --include-global-service-events --enable-log-file-validation \
        --tags-list "Key=Name,Value=$NOMBRE" > /dev/null
fi
aws_r cloudtrail start-logging --name "$TRAIL"
echo "✓ $trail_arn"

# Una regla de EventBridge con su destino (el tema de SNS) y un texto legible para el correo.
regla() { # regla REGION TEMA NOMBRE DESCRIPCION PATRON_JSON TRANSFORMADOR_JSON
    local r="$1" tema="$2" fallidos
    aws --region "$r" events put-rule --name "$3" --description "$4" --event-pattern "$5" --state ENABLED > /dev/null
    fallidos="$(aws --region "$r" events put-targets --rule "$3" \
        --targets "$(jq -n --arg t "$tema" --argjson x "$6" '[{Id: "correo", Arn: $t, InputTransformer: $x}]')" \
        --query FailedEntryCount --output text)"
    [ "${fallidos:-0}" = 0 ] || falla "la regla $3 en $r no aceptó su destino"
    echo "✓ regla $3"
}

PATRON_ROOT='{"detail-type": ["AWS API Call via CloudTrail", "AWS Console Sign In via CloudTrail"],
               "detail": {"userIdentity": {"type": ["Root"]}}}'
TEXTO_ROOT='{"InputPathsMap": {"tipo": "$.detail-type", "evento": "$.detail.eventName",
                               "ip": "$.detail.sourceIPAddress", "region": "$.region", "hora": "$.time"},
             "InputTemplate": "\"El Puesto / AWS: se usó la cuenta ROOT. <tipo>: <evento> desde <ip> (región <region>, <hora> UTC). Si no fuiste tú: cambia la contraseña y la MFA de root y revisa CloudTrail (bucket elpuesto-app-auditoria).\""}'
PATRON_LOGIN='{"detail-type": ["AWS Console Sign In via CloudTrail"],
               "detail": {"eventName": ["ConsoleLogin"], "responseElements": {"ConsoleLogin": ["Failure"]}}}'
TEXTO_LOGIN='{"InputPathsMap": {"tipo": "$.detail.userIdentity.type", "quien": "$.detail.userIdentity.arn",
                                "ip": "$.detail.sourceIPAddress", "error": "$.detail.errorMessage", "hora": "$.time"},
              "InputTemplate": "\"El Puesto / AWS: falló un inicio de sesión en la consola (<tipo> <quien>) desde <ip> a las <hora> UTC: <error>. Si se repite, revisa CloudTrail y la MFA.\""}'
PATRON_SSO='{"detail-type": ["AWS Service Event via CloudTrail"],
             "detail": {"eventSource": ["signin.amazonaws.com"], "eventName": ["CredentialVerification"],
                        "serviceEventDetails": {"CredentialVerification": ["Failure"]}}}'
TEXTO_SSO='{"InputPathsMap": {"usuario": "$.detail.userIdentity.onBehalfOf.userId",
                              "credencial": "$.detail.additionalEventData.CredentialType",
                              "ip": "$.detail.sourceIPAddress", "hora": "$.time"},
            "InputTemplate": "\"El Puesto / AWS: falló un inicio de sesión en el portal de IAM Identity Center (credencial <credencial>, usuario <usuario>) desde <ip> a las <hora> UTC. Quién es: aws identitystore describe-user --region '"$REGION_SSO"' --identity-store-id '"$ALMACEN_SSO"' --user-id <usuario>. Si no fuiste tú y se repite, revisa CloudTrail y la MFA del usuario.\""}'

for r in "${REGIONES_ALERTA[@]}"; do
    paso "Avisos por correo en $r → $correo"
    tema="$(aws --region "$r" sns create-topic --name "$TEMA" --query TopicArn --output text)"
    aws --region "$r" sns set-topic-attributes --topic-arn "$tema" --attribute-name Policy \
        --attribute-value "$(jq -n --arg t "$tema" --arg reglas "arn:aws:events:$r:$CUENTA:rule/elpuesto-*" '{
      Version: "2012-10-17",
      Statement: [{Sid: "EventBridge", Effect: "Allow", Principal: {Service: "events.amazonaws.com"},
        Action: "sns:Publish", Resource: $t, Condition: {ArnLike: {"aws:SourceArn": $reglas}}}]}')"
    if [ -z "$(aws --region "$r" sns list-subscriptions-by-topic --topic-arn "$tema" \
            --query "Subscriptions[?Endpoint=='$correo'].SubscriptionArn" --output text)" ]; then
        aws --region "$r" sns subscribe --topic-arn "$tema" --protocol email --notification-endpoint "$correo" > /dev/null
        echo "→ AWS mandó a $correo un correo para confirmar la suscripción: ábrelo y confirma"
    else
        echo "✓ $correo ya está suscrito"
    fi
    regla "$r" "$tema" elpuesto-uso-root "El Puesto: aviso cuando se usa la cuenta root" "$PATRON_ROOT" "$TEXTO_ROOT"
    regla "$r" "$tema" elpuesto-login-fallido "El Puesto: aviso de inicio de sesión fallido en la consola" "$PATRON_LOGIN" "$TEXTO_LOGIN"
    if [ "$r" = "$REGION_SSO" ]; then
        regla "$r" "$tema" elpuesto-sso-fallido "El Puesto: aviso de contraseña o MFA fallidos en IAM Identity Center" "$PATRON_SSO" "$TEXTO_SSO"
    fi
done

echo
echo "Listo. Prueba: inicia sesión en la consola con una contraseña equivocada y espera el correo"
echo "(unos minutos). Los registros quedan en s3://$BUCKET_AUDITORIA/AWSLogs/$CUENTA/."
