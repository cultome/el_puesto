#!/usr/bin/env bash
# Paso 3: certificado TLS de CloudFront para elpuesto.app + www (ACM; CloudFront solo acepta
# certificados de us-east-1). Validación por DNS: imprime los CNAME que hay que capturar en
# Namecheap (Advanced DNS). Volver a correrlo muestra el estado; ACM lo renueva solo mientras
# esos CNAME sigan ahí (no borrarlos).
. "$(dirname "$0")/comun.sh"
ACM=(aws acm --region us-east-1)

paso "Certificado $DOMINIO + www.$DOMINIO (us-east-1)"
arn="$("${ACM[@]}" list-certificates --certificate-statuses PENDING_VALIDATION ISSUED \
    --query "CertificateSummaryList[?DomainName=='$DOMINIO'].CertificateArn | [0]" --output text)"
if [ "$arn" = None ]; then
    arn="$("${ACM[@]}" request-certificate --domain-name "$DOMINIO" \
        --subject-alternative-names "www.$DOMINIO" --validation-method DNS \
        --idempotency-token elpuestositio --query CertificateArn --output text)"
    echo "solicitado; esperando los registros de validación…"
    sleep 15
fi
echo "$arn"

estado="$("${ACM[@]}" describe-certificate --certificate-arn "$arn" --query Certificate.Status --output text)"
echo "estado: $estado"
if [ "$estado" != ISSUED ]; then
    echo
    echo "Captura en Namecheap → Domain List → $DOMINIO → Advanced DNS → Add new record:"
    "${ACM[@]}" describe-certificate --certificate-arn "$arn" \
        --query 'Certificate.DomainValidationOptions[].ResourceRecord.[Name, Value]' --output text |
        sort -u | while read -r nombre valor; do
            host="${nombre%.$DOMINIO.}"
            printf '  CNAME Record   Host: %-40s Value: %s\n' "$host" "$valor"
        done
    echo
    echo "Cuando el estado sea ISSUED (minutos a ~1 h tras capturarlos), sigue con 4-cloudfront.sh."
fi
