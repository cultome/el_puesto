#!/usr/bin/env bash
# Respaldos de producción desde TU máquina, con tus credenciales. La instancia solo puede
# ESCRIBIR respaldos (Object Lock: nadie los borra ni sobrescribe antes de que caduquen); leerlos
# es cosa del operador:
#   scripts/restaurar-respaldo.sh                          lista los respaldos más recientes
#   scripts/restaurar-respaldo.sh --bajar CLAVE [ARCHIVO]  lo baja a tu máquina (para una base
#                                                          local: pg_restore -d <base> --no-owner ARCHIVO)
#   CONFIRMAR=si scripts/restaurar-respaldo.sh CLAVE       DESTRUCTIVO: restaura PRODUCCIÓN con ese
#                                                          respaldo (antes respalda lo actual)
# CLAVE: diarios/el_puesto-….dump o mensuales/… del bucket de respaldos, o una ruta s3:// completa
# (p. ej. los de antes, en s3://elpuesto-app-ops/respaldos/…, que caducan solos).
# Para restaurar arma un enlace firmado de 15 minutos y se lo pasa a restaurar.sh en la instancia
# por SSM (el enlace queda en el historial de comandos de SSM, pero caduca solo).
. "$(dirname "$0")/../infra/aws/comun.sh"
solo_operador

uri() { case "$1" in s3://*) echo "$1" ;; *) echo "s3://$BUCKET_RESPALDOS/${1#/}" ;; esac; }

case "${1:-}" in
"")
    paso "Respaldos en s3://$BUCKET_RESPALDOS/ (inmutables)"
    aws_r s3 ls "s3://$BUCKET_RESPALDOS/" --recursive | sort | tail -n 40
    viejos="$(aws_r s3 ls "s3://$BUCKET_OPS/respaldos/" --recursive 2> /dev/null | sort | tail -n 10 || true)"
    if [ -n "$viejos" ]; then
        paso "Los de antes en s3://$BUCKET_OPS/respaldos/ (caducan solos; usa la ruta s3:// completa)"
        echo "$viejos"
    fi ;;
--bajar)
    origen="$(uri "${2:?uso: --bajar CLAVE [ARCHIVO]}")"
    destino="${3:-$(basename "$origen")}"
    aws_r s3 cp "$origen" "$destino" --only-show-errors
    echo "✓ $destino (a una base local: pg_restore -d <base> --no-owner $destino)" ;;
-*)
    falla "opción desconocida: $1 (ver el encabezado del script)" ;;
*)
    origen="$(uri "$1")"
    [ "${CONFIRMAR:-}" = si ] ||
        falla "Esto BORRA la base de PRODUCCIÓN y la reemplaza con $origen. Repite con CONFIRMAR=si"
    aws_r s3 ls "$origen" > /dev/null || falla "no existe $origen"
    url="$(aws_r s3 presign "$origen" --expires-in 900)"
    paso "Restaurando $origen en la instancia (antes respalda lo actual)"
    ssm_ejecutar "CONFIRMAR=si bash /opt/el-puesto/servidor/restaurar.sh --url $(printf %q "$url")" "restaurar respaldo"
    echo "✓ restaurado $origen" ;;
esac
