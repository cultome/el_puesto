#!/usr/bin/env bash
# Paso 6: la instancia del API.
#   - Rol de IAM con lo MÍNIMO: SSM (Run Command/Session Manager, sin SSH), leer
#     $PARAM_PREFIX/*, leer servidor/ y despliegues/, y SOLO ESCRIBIR respaldos en
#     $BUCKET_RESPALDOS (ni leerlos ni borrarlos; Object Lock, ver 2-buckets.sh).
#   - Security group: 80 y 443 (TCP, y UDP para HTTP/3). Sin puerto 22.
#   - Amazon Linux 2023 arm64 (Graviton), disco gp3 cifrado, IMDSv2, protección contra
#     terminación, IP elástica (la del registro A de api.elpuesto.app).
#   - user-data: baja infra/servidor/ de S3 y corre bootstrap.sh (Docker, Java 21, Postgres,
#     Caddy, servicios y respaldo diario). El backend llega con scripts/desplegar-backend.sh.
#   - Documento de SSM $DOC_ACTIVAR (documento-activar.json): lo único que el despliegue
#     automático (rol de 7-github.sh) puede correr en la instancia.
# Re-correrlo en producción es seguro: no recrea la instancia; sube infra/servidor/ de HEAD a S3
# (NO lo aplica: eso es scripts/desplegar-servidor.sh) y pone al día rol, SG, documento e IP.
. "$(dirname "$0")/comun.sh"
CUENTA="$(cuenta_id)"
ROL="$NOMBRE-ec2"

paso "Subir infra/servidor/ (HEAD) a s3://$BUCKET_OPS/servidor/"
[ -z "$(git -C "$RAIZ" status --porcelain -- infra/servidor)" ] ||
    echo "ojo: hay cambios sin commitear en infra/servidor/; NO se suben"
subir_servidor HEAD
echo "✓"

paso "Documento de SSM $DOC_ACTIVAR"
contenido="file://$(cd "$(dirname "$0")" && pwd)/documento-activar.json"
if aws_r ssm describe-document --name "$DOC_ACTIVAR" > /dev/null 2>&1; then
    if nueva="$(aws_r ssm update-document --name "$DOC_ACTIVAR" --content "$contenido" --document-format JSON \
            --document-version '$LATEST' --query DocumentDescription.DocumentVersion --output text 2>&1)"; then
        aws_r ssm update-document-default-version --name "$DOC_ACTIVAR" --document-version "$nueva" > /dev/null
        echo "✓ actualizado (versión $nueva)"
    elif grep -q DuplicateDocumentContent <<<"$nueva"; then
        echo "✓ sin cambios"
    else
        falla "$nueva"
    fi
else
    aws_r ssm create-document --name "$DOC_ACTIVAR" --document-type Command --document-format JSON \
        --content "$contenido" --tags "Key=Name,Value=$NOMBRE" > /dev/null
    echo "✓ creado"
fi

paso "Rol de IAM $ROL"
if ! aws iam get-role --role-name "$ROL" > /dev/null 2>&1; then
    aws iam create-role --role-name "$ROL" --description "El Puesto: instancia del API" \
        --assume-role-policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
            "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}' > /dev/null
fi
aws iam attach-role-policy --role-name "$ROL" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
# Respaldos: SOLO escribir (con su retención de Object Lock). Ni leer ni borrar: un intruso en la
# instancia no puede llevarse ni destruir los respaldos; restaurar lo hace el operador con un
# enlace firmado (scripts/restaurar-respaldo.sh).
aws iam put-role-policy --role-name "$ROL" --policy-name el-puesto --policy-document "$(jq -n \
    --arg ops "arn:aws:s3:::$BUCKET_OPS" --arg resp "arn:aws:s3:::$BUCKET_RESPALDOS" \
    --arg params "arn:aws:ssm:$REGION:$CUENTA:parameter${PARAM_PREFIX}" '{
  Version: "2012-10-17",
  Statement: [
    {Sid: "Leer", Effect: "Allow", Action: "s3:GetObject",
     Resource: [($ops + "/servidor/*"), ($ops + "/despliegues/*")]},
    {Sid: "Listar", Effect: "Allow", Action: "s3:ListBucket", Resource: $ops,
     Condition: {StringLike: {"s3:prefix": ["servidor/", "servidor/*"]}}},
    {Sid: "Respaldar", Effect: "Allow", Action: ["s3:PutObject", "s3:PutObjectRetention"],
     Resource: [($resp + "/diarios/*"), ($resp + "/mensuales/*")]},
    {Sid: "Config", Effect: "Allow", Action: ["ssm:GetParametersByPath", "ssm:GetParameter", "ssm:GetParameters"],
     Resource: [$params, ($params + "/*")]}
  ]}')"
if ! aws iam get-instance-profile --instance-profile-name "$ROL" > /dev/null 2>&1; then
    aws iam create-instance-profile --instance-profile-name "$ROL" > /dev/null
    aws iam add-role-to-instance-profile --instance-profile-name "$ROL" --role-name "$ROL"
    echo "esperando a que IAM propague el perfil…"; sleep 15
fi
echo "✓"

paso "Security group"
vpc="$(aws_r ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)"
sg="$(aws_r ec2 describe-security-groups --filters "Name=group-name,Values=$NOMBRE" "Name=vpc-id,Values=$vpc" \
    --query 'SecurityGroups[0].GroupId' --output text)"
if [ "$sg" = None ]; then
    sg="$(aws_r ec2 create-security-group --group-name "$NOMBRE" --vpc-id "$vpc" \
        --description "El Puesto API: HTTP/HTTPS publicos, sin SSH" --query GroupId --output text)"
    aws_r ec2 authorize-security-group-ingress --group-id "$sg" --ip-permissions \
        'IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}]' \
        'IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]' \
        'IpProtocol=udp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]' > /dev/null
    aws_r ec2 create-tags --resources "$sg" --tags "Key=Name,Value=$NOMBRE"
fi
echo "✓ $sg"

paso "Instancia ($TIPO_INSTANCIA)"
id="$(instancia_id)"
if [ "$id" = None ]; then
    ami="$(aws_r ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
        --query Parameter.Value --output text)"
    datos="$(mktemp)"
    cat > "$datos" <<EOF
#!/bin/bash
set -euxo pipefail
mkdir -p /opt/el-puesto/servidor
aws s3 sync s3://$BUCKET_OPS/servidor/ /opt/el-puesto/servidor/ --region $REGION
bash /opt/el-puesto/servidor/bootstrap.sh
EOF
    id="$(aws_r ec2 run-instances --image-id "$ami" --instance-type "$TIPO_INSTANCIA" \
        --iam-instance-profile "Name=$ROL" --security-group-ids "$sg" \
        --metadata-options HttpTokens=required,HttpEndpoint=enabled,HttpPutResponseHopLimit=1 \
        --block-device-mappings '[{"DeviceName": "/dev/xvda", "Ebs": {"VolumeSize": 30, "VolumeType": "gp3", "Encrypted": true, "DeleteOnTermination": true}}]' \
        --disable-api-termination \
        --user-data "file://$datos" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NOMBRE}]" \
                             "ResourceType=volume,Tags=[{Key=Name,Value=$NOMBRE}]" \
        --query 'Instances[0].InstanceId' --output text)"
    rm -f "$datos"
    echo "creada $id; esperando a que arranque…"
    aws_r ec2 wait instance-running --instance-ids "$id"
fi
echo "✓ $id"

paso "IP elástica"
alloc="$(aws_r ec2 describe-addresses --filters "Name=tag:Name,Values=$NOMBRE" --query 'Addresses[0].AllocationId' --output text)"
if [ "$alloc" = None ]; then
    alloc="$(aws_r ec2 allocate-address --domain vpc \
        --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$NOMBRE}]" --query AllocationId --output text)"
fi
aws_r ec2 associate-address --allocation-id "$alloc" --instance-id "$id" --allow-reassociation > /dev/null
ip="$(aws_r ec2 describe-addresses --allocation-ids "$alloc" --query 'Addresses[0].PublicIp' --output text)"
echo "✓ $ip"

echo
echo "DNS en Namecheap (Advanced DNS):"
printf '  A Record   Host: api     Value: %s\n' "$ip"
printf '  A Record   Host: admin   Value: %s   (admin web y API /admin/*)\n' "$ip"
echo
echo "El bootstrap tarda ~3-5 min. Estado: scripts/servidor.sh 'tail -n 30 /var/log/cloud-init-output.log'"
