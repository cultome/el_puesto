#!/usr/bin/env bash
# Paso 8: presupuesto mensual con aviso por correo (AWS Budgets; los dos primeros son gratis).
# Protege del abuso que busca que gastemos: tráfico o descargas del APK a lo loco disparan la
# transferencia de CloudFront. Avisa al pasar el 80% REAL del límite y cuando el PRONÓSTICO del
# mes lo rebasa. Idempotente: correrlo de nuevo actualiza el límite y los avisos.
#
#   8-presupuesto.sh CORREO [LIMITE_USD=30]
. "$(dirname "$0")/comun.sh"
CORREO="${1:?uso: 8-presupuesto.sh CORREO [LIMITE_USD]}"
LIMITE="${2:-30}"
NOMBRE_PRESUPUESTO=elpuesto-mensual
CUENTA="$(cuenta_id)"

presupuesto="$(jq -n --arg n "$NOMBRE_PRESUPUESTO" --arg l "$LIMITE" '{
    BudgetName: $n, BudgetType: "COST", TimeUnit: "MONTHLY",
    BudgetLimit: {Amount: $l, Unit: "USD"}
}')"
avisos() { # notificaciones con su suscriptor (formato de create-budget)
    jq -n --arg c "$CORREO" '[
        {Notification: {NotificationType: "ACTUAL", ComparisonOperator: "GREATER_THAN", Threshold: 80, ThresholdType: "PERCENTAGE"},
         Subscribers: [{SubscriptionType: "EMAIL", Address: $c}]},
        {Notification: {NotificationType: "FORECASTED", ComparisonOperator: "GREATER_THAN", Threshold: 100, ThresholdType: "PERCENTAGE"},
         Subscribers: [{SubscriptionType: "EMAIL", Address: $c}]}
    ]'
}

paso "Presupuesto $NOMBRE_PRESUPUESTO ($LIMITE USD/mes → $CORREO)"
if aws budgets describe-budget --account-id "$CUENTA" --budget-name "$NOMBRE_PRESUPUESTO" > /dev/null 2>&1; then
    aws budgets update-budget --account-id "$CUENTA" --new-budget "$presupuesto"
    # Los avisos se recrean para que el correo y los umbrales queden como en este script.
    for n in $(aws budgets describe-notifications-for-budget --account-id "$CUENTA" --budget-name "$NOMBRE_PRESUPUESTO" \
        --query 'Notifications[].[NotificationType,ComparisonOperator,Threshold,ThresholdType]' --output text | tr '\t' ','); do
        IFS=, read -r tipo op umbral tt <<< "$n"
        aws budgets delete-notification --account-id "$CUENTA" --budget-name "$NOMBRE_PRESUPUESTO" \
            --notification "NotificationType=$tipo,ComparisonOperator=$op,Threshold=$umbral,ThresholdType=$tt"
    done
    avisos | jq -c '.[]' | while read -r a; do
        aws budgets create-notification --account-id "$CUENTA" --budget-name "$NOMBRE_PRESUPUESTO" \
            --notification "$(jq -c .Notification <<< "$a")" --subscribers "$(jq -c .Subscribers <<< "$a")"
    done
else
    aws budgets create-budget --account-id "$CUENTA" --budget "$presupuesto" \
        --notifications-with-subscribers "$(avisos)"
fi
echo "✓ avisos: 80% real y 100% pronosticado de $LIMITE USD"
