#!/usr/bin/env bash
# Paso 1: verifica la sesión de la CLI, habilita la región mx-central-1 (es de las que se
# activan a mano), asegura la VPC por defecto y confirma que el tipo de instancia exista ahí.
. "$(dirname "$0")/comun.sh"

paso "Sesión de AWS (perfil $AWS_PROFILE)"
aws sts get-caller-identity --output table

paso "Región $REGION"
estado="$(aws account get-region-opt-status --region-name "$REGION" --query RegionOptStatus --output text)"
echo "estado: $estado"
if [ "$estado" = DISABLED ]; then
    aws account enable-region --region-name "$REGION"
    estado=ENABLING
fi
while [ "$estado" = ENABLING ]; do
    echo "habilitando… (tarda unos minutos)"; sleep 20
    estado="$(aws account get-region-opt-status --region-name "$REGION" --query RegionOptStatus --output text)"
done
case "$estado" in ENABLED|ENABLED_BY_DEFAULT) echo "✓ región lista" ;; *) falla "región en estado $estado" ;; esac

paso "VPC por defecto"
vpc="$(aws_r ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)"
if [ "$vpc" = None ]; then
    vpc="$(aws_r ec2 create-default-vpc --query Vpc.VpcId --output text)"
fi
echo "✓ $vpc"

paso "Tipo de instancia $TIPO_INSTANCIA en $REGION"
if [ -n "$(aws_r ec2 describe-instance-type-offerings --location-type region \
        --filters "Name=instance-type,Values=$TIPO_INSTANCIA" --query 'InstanceTypeOfferings[0].InstanceType' --output text | grep -v None)" ]; then
    echo "✓ disponible"
else
    echo "✗ no disponible. Graviton que sí hay:"
    aws_r ec2 describe-instance-type-offerings --location-type region \
        --filters 'Name=instance-type,Values=t4g.*,m7g.medium,m6g.medium,c7g.medium' \
        --query 'InstanceTypeOfferings[].InstanceType' --output text
    falla "elige otro con TIPO_INSTANCIA=…"
fi
