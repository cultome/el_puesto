#!/usr/bin/env bash
# Paso 4: CloudFront para el sitio (elpuesto.app + www) sobre el bucket privado $BUCKET_WEB
# (Origin Access Control: solo esta distribución lee el bucket). Requiere el certificado del
# paso 3 en ISSUED. Al final imprime los registros DNS para Namecheap.
#
# Caché: la política administrada CachingOptimized respeta el Cache-Control de cada objeto,
# que ponen los scripts de publicación (HTML 5 min, version.json 1 min, APK 1 año inmutable).
. "$(dirname "$0")/comun.sh"
cd "$(dirname "$0")"

FUNCION=elpuesto-sitio
OAC_NOMBRE=elpuesto-web
CACHING_OPTIMIZED=658327ea-f89d-4fab-a63d-7e88639e58f6   # política administrada de AWS
SECURITY_HEADERS=67f7725c-6f97-4210-82d7-5512b31e9d03    # HSTS, nosniff, frame-options…

paso "Función $FUNCION"
config='{"Comment": "El Puesto: www a apex e index.html de carpetas", "Runtime": "cloudfront-js-2.0"}'
if etag="$(aws cloudfront describe-function --name "$FUNCION" --query ETag --output text 2>/dev/null)"; then
    etag="$(aws cloudfront update-function --name "$FUNCION" --if-match "$etag" \
        --function-config "$config" --function-code fileb://sitio-funcion.js --query ETag --output text)"
else
    etag="$(aws cloudfront create-function --name "$FUNCION" \
        --function-config "$config" --function-code fileb://sitio-funcion.js --query ETag --output text)"
fi
aws cloudfront publish-function --name "$FUNCION" --if-match "$etag" > /dev/null
funcion_arn="$(aws cloudfront describe-function --name "$FUNCION" --stage LIVE \
    --query FunctionSummary.FunctionMetadata.FunctionARN --output text)"
echo "✓ $funcion_arn"

paso "Origin Access Control"
oac="$(aws cloudfront list-origin-access-controls \
    --query "OriginAccessControlList.Items[?Name=='$OAC_NOMBRE'].Id | [0]" --output text)"
if [ "$oac" = None ]; then
    oac="$(aws cloudfront create-origin-access-control --origin-access-control-config \
        "Name=$OAC_NOMBRE,Description=El Puesto sitio,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" \
        --query OriginAccessControl.Id --output text)"
fi
echo "✓ $oac"

paso "Certificado"
cert="$(aws acm list-certificates --region us-east-1 --certificate-statuses ISSUED \
    --query "CertificateSummaryList[?DomainName=='$DOMINIO'].CertificateArn | [0]" --output text)"
[ "$cert" != None ] || falla "el certificado de $DOMINIO aún no está ISSUED (corre 3-certificado.sh)"
echo "✓ $cert"

paso "Distribución"
dist="$(distribucion_id)"
if [ "$dist" = None ]; then
    tmp="$(mktemp)"
    jq -n --arg ref "elpuesto-sitio-$(date +%s)" --arg dom "$DOMINIO" \
        --arg origen "$BUCKET_WEB.s3.$REGION.amazonaws.com" --arg oac "$oac" --arg fn "$funcion_arn" \
        --arg cert "$cert" --arg cache "$CACHING_OPTIMIZED" --arg headers "$SECURITY_HEADERS" '{
      CallerReference: $ref,
      Comment: "El Puesto: sitio y descargas",
      Enabled: true,
      Aliases: {Quantity: 2, Items: [$dom, ("www." + $dom)]},
      DefaultRootObject: "index.html",
      Origins: {Quantity: 1, Items: [{
        Id: "s3-web", DomainName: $origen, OriginAccessControlId: $oac,
        S3OriginConfig: {OriginAccessIdentity: ""}}]},
      DefaultCacheBehavior: {
        TargetOriginId: "s3-web",
        ViewerProtocolPolicy: "redirect-to-https",
        AllowedMethods: {Quantity: 2, Items: ["GET", "HEAD"],
                         CachedMethods: {Quantity: 2, Items: ["GET", "HEAD"]}},
        Compress: true,
        CachePolicyId: $cache,
        ResponseHeadersPolicyId: $headers,
        FunctionAssociations: {Quantity: 1, Items: [{FunctionARN: $fn, EventType: "viewer-request"}]}},
      CustomErrorResponses: {Quantity: 2, Items: [
        {ErrorCode: 403, ResponsePagePath: "/404.html", ResponseCode: "404", ErrorCachingMinTTL: 60},
        {ErrorCode: 404, ResponsePagePath: "/404.html", ResponseCode: "404", ErrorCachingMinTTL: 60}]},
      PriceClass: "PriceClass_100",
      HttpVersion: "http2and3",
      IsIPV6Enabled: true,
      ViewerCertificate: {ACMCertificateArn: $cert, SSLSupportMethod: "sni-only",
                          MinimumProtocolVersion: "TLSv1.2_2021"}
    }' > "$tmp"
    dist="$(aws cloudfront create-distribution --distribution-config "file://$tmp" --query Distribution.Id --output text)"
    rm -f "$tmp"
    echo "✓ creada $dist (tarda ~5 min en desplegarse)"
else
    echo "✓ ya existe $dist"
fi
dominio_cf="$(aws cloudfront get-distribution --id "$dist" --query Distribution.DomainName --output text)"

paso "Política del bucket $BUCKET_WEB (solo esta distribución lee; solo HTTPS)"
politica_bucket "$BUCKET_WEB" "$(jq -n \
    --arg b "$BUCKET_WEB" --arg src "arn:aws:cloudfront::$(cuenta_id):distribution/$dist" '[
  {Sid: "SoloCloudFront", Effect: "Allow",
    Principal: {Service: "cloudfront.amazonaws.com"},
    Action: "s3:GetObject", Resource: ("arn:aws:s3:::" + $b + "/*"),
    Condition: {StringEquals: {"AWS:SourceArn": $src}}}]')"
echo "✓"

echo
echo "DNS en Namecheap (Advanced DNS): borra el CNAME 'www' → parkingpage y el URL Redirect de '@', y agrega:"
printf '  ALIAS Record   Host: %-6s Value: %s\n' "@" "$dominio_cf"
printf '  CNAME Record   Host: %-6s Value: %s\n' "www" "$dominio_cf"
