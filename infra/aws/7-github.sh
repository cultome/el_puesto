#!/usr/bin/env bash
# Paso 7: despliegue automático desde GitHub Actions (.github/workflows/) sin claves guardadas.
#   - Entorno "produccion" en GitHub, solo para la rama master. Los jobs que tocan AWS lo
#     declaran (`environment: produccion`); con REVISOR=si además exige que apruebes cada
#     despliegue (en un repo PRIVADO eso requiere un plan de pago; en uno público es gratis).
#   - Proveedor OIDC de GitHub en IAM: AWS confía en los tokens que GitHub firma por job.
#   - Rol $ROL: SOLO lo asume un job del entorno produccion de $REPO. Permisos justos para
#     construir nada (eso va sin credenciales) y:
#       · backend: subir el paquete a despliegues/ y activarlo con el documento de SSM
#         ElPuesto-Activar en la instancia (NO AWS-RunShellScript, NO escribir servidor/),
#       · sitio: subir la landing a S3 e invalidar CloudFront.
#     Tiene PROHIBIDO escribir descargas/ (la página, con la huella del APK, y los APKs),
#     .well-known/ y version.json: publicar la app sigue siendo manual (la llave de release no
#     sale de la máquina del autor). Tampoco toca respaldos ni Parameter Store.
#   - Variable AWS_ROLE_ARN del repo (gh CLI) que los workflows usan.
# Requiere el documento de SSM (6-servidor.sh) y la distribución de CloudFront (4-cloudfront.sh).
#   infra/aws/7-github.sh            (REVISOR=si infra/aws/7-github.sh para exigir tu aprobación)
. "$(dirname "$0")/comun.sh"
REPO=cultome/el_puesto
ROL=elpuesto-github-deploy
ENTORNO=produccion
CUENTA="$(cuenta_id)"
OIDC="arn:aws:iam::$CUENTA:oidc-provider/token.actions.githubusercontent.com"

aws_r ssm describe-document --name "$DOC_ACTIVAR" > /dev/null 2>&1 ||
    falla "no existe el documento de SSM $DOC_ACTIVAR (corre antes 6-servidor.sh)"

paso "Entorno $ENTORNO en github.com/$REPO (solo la rama master)"
# PUT reemplaza la configuración completa: se conservan los revisores que ya tenga. Si el entorno
# aún no existe (repo nuevo), gh escribe el 404 en stdout: solo vale la salida de un éxito.
revisores="$(gh api "repos/$REPO/environments/$ENTORNO" \
    --jq '[.protection_rules[]? | select(.type == "required_reviewers") | .reviewers[] | {type, id: .reviewer.id}]' \
    2> /dev/null)" || revisores='[]'
[ -n "$revisores" ] || revisores='[]'
if [ "${REVISOR:-}" = si ]; then
    dueno_id="$(gh api "users/${REPO%%/*}" --jq .id)"
    revisores="$(jq -c --argjson id "$dueno_id" '. + [{type: "User", id: $id}] | unique' <<<"$revisores")"
fi
cuerpo() { # cuerpo REVISORES_JSON (prevent_self_review=false: tú mismo apruebas lo que subes)
    jq -n --argjson r "$1" '{deployment_branch_policy: {protected_branches: false, custom_branch_policies: true}}
        + (if ($r | length) > 0 then {reviewers: $r, prevent_self_review: false} else {} end)'
}
poner_entorno() { { cuerpo "$1" | gh api -X PUT "repos/$REPO/environments/$ENTORNO" --input - > /dev/null; } 2>&1; }
if ! err="$(poner_entorno "$revisores")"; then
    if [ "$revisores" != '[]' ] && err="$(poner_entorno '[]')"; then
        echo "ojo: GitHub no aceptó revisores obligatorios (en un repo privado requieren un plan de pago);"
        echo "     el entorno queda restringido a master pero SIN aprobación manual."
        revisores='[]'
    else
        falla "GitHub no dejó crear/configurar el entorno $ENTORNO: $err
  En un repo PRIVADO los entornos requieren GitHub Pro/Team (o hacer público el repo, que ya
  estaba en pendientes). No se tocó la confianza del rol: los workflows con
  'environment: $ENTORNO' no podrán desplegar hasta que esto pase; mientras, despliega a mano
  (scripts/desplegar-backend.sh, scripts/publicar-sitio.sh)."
    fi
fi
# Ramas permitidas: exactamente master (se quitan las demás).
politicas="repos/$REPO/environments/$ENTORNO/deployment-branch-policies"
gh api "$politicas" --jq '.branch_policies[] | "\(.id) \(.name) \(.type // "branch")"' |
    while read -r pid pnombre ptipo; do
        [ "$pnombre $ptipo" = "master branch" ] || gh api -X DELETE "$politicas/$pid" > /dev/null
    done
ramas="$(gh api "$politicas" --jq '.branch_policies[].name')"
grep -qx master <<<"$ramas" || gh api -X POST "$politicas" -f name=master -f type=branch > /dev/null
[ "$(gh api "repos/$REPO/environments/$ENTORNO" --jq '.deployment_branch_policy.custom_branch_policies')" = true ] &&
    [ "$(gh api "$politicas" --jq '[.branch_policies[].name] | join(",")')" = master ] ||
    falla "el entorno $ENTORNO no quedó restringido a master: revísalo en Settings → Environments (no se tocó el rol)"
echo "✓ solo master · revisores: $(jq -r 'if length == 0 then "ninguno" else (map(.id | tostring) | join(", ")) end' <<<"$revisores")"

paso "Proveedor OIDC de GitHub"
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC" > /dev/null 2>&1; then
    aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
        --client-id-list sts.amazonaws.com \
        --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd > /dev/null
fi
echo "✓ $OIDC"

# GitHub firma el `sub` con subjects INMUTABLES (repo:dueño@ID/repo@ID:…): un repo renombrado o
# recreado con el mismo nombre no califica. El prefijo real se lee de la API del repo. Un job con
# `environment:` lleva en el sub el entorno (no la rama): la rama la garantiza la regla del entorno.
prefijo="$(gh api "repos/$REPO/actions/oidc/customization/sub" --jq '.sub_claim_prefix // empty' 2> /dev/null || true)"
prefijo="${prefijo:-repo:$REPO}"

paso "Rol $ROL (solo $prefijo:environment:$ENTORNO)"
confianza="$(jq -n --arg oidc "$OIDC" --arg sub "$prefijo:environment:$ENTORNO" '{
  Version: "2012-10-17",
  Statement: [{Effect: "Allow", Principal: {Federated: $oidc}, Action: "sts:AssumeRoleWithWebIdentity",
    Condition: {StringEquals: {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                               "token.actions.githubusercontent.com:sub": $sub}}}]}')"
if aws iam get-role --role-name "$ROL" > /dev/null 2>&1; then
    aws iam update-assume-role-policy --role-name "$ROL" --policy-document "$confianza"
else
    aws iam create-role --role-name "$ROL" --description "El Puesto: despliegue desde GitHub Actions" \
        --max-session-duration 3600 --assume-role-policy-document "$confianza" > /dev/null
fi

dist="$(distribucion_id)"
[ "$dist" != None ] || falla "no encontré la distribución de CloudFront (paso 4)"
aws iam put-role-policy --role-name "$ROL" --policy-name despliegue --policy-document "$(jq -n \
    --arg ops "arn:aws:s3:::$BUCKET_OPS" --arg web "arn:aws:s3:::$BUCKET_WEB" \
    --arg resp "arn:aws:s3:::$BUCKET_RESPALDOS" \
    --arg dist "arn:aws:cloudfront::$CUENTA:distribution/$dist" \
    --arg instancias "arn:aws:ec2:$REGION:$CUENTA:instance/*" \
    --arg doc "arn:aws:ssm:$REGION:$CUENTA:document/$DOC_ACTIVAR" --arg nombre "$NOMBRE" '{
  Version: "2012-10-17",
  Statement: [
    {Sid: "Buscar", Effect: "Allow", Action: ["ec2:DescribeInstances", "cloudfront:ListDistributions"], Resource: "*"},
    {Sid: "Paquetes", Effect: "Allow", Action: ["s3:PutObject", "s3:AbortMultipartUpload"],
     Resource: ($ops + "/despliegues/*")},
    {Sid: "NuncaElServidor", Effect: "Deny", Action: "s3:*",
     Resource: [($ops + "/servidor/*"), ($ops + "/respaldos/*"), $resp, ($resp + "/*")]},
    {Sid: "Sitio", Effect: "Allow", Action: ["s3:PutObject", "s3:GetObject"], Resource: ($web + "/*")},
    {Sid: "NuncaLaApp", Effect: "Deny", Action: ["s3:PutObject*", "s3:DeleteObject*"],
     Resource: [($web + "/descargas/*"), ($web + "/.well-known/*"), ($web + "/version.json")]},
    {Sid: "Invalidar", Effect: "Allow", Action: "cloudfront:CreateInvalidation", Resource: $dist},
    {Sid: "Instancia", Effect: "Allow", Action: "ssm:SendCommand", Resource: $instancias,
     Condition: {StringEquals: {"ssm:resourceTag/Name": $nombre}}},
    {Sid: "SoloActivar", Effect: "Allow", Action: "ssm:SendCommand", Resource: $doc},
    {Sid: "Resultado", Effect: "Allow", Action: ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"], Resource: "*"}
  ]}')"
rol_arn="$(aws iam get-role --role-name "$ROL" --query Role.Arn --output text)"
echo "✓ $rol_arn"

paso "Variable AWS_ROLE_ARN en github.com/$REPO"
gh variable set AWS_ROLE_ARN --repo "$REPO" --body "$rol_arn"
echo "✓"
