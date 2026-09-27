#!/usr/bin/env bash
# Paso 5: configuración y secretos del backend en Parameter Store ($PARAM_PREFIX/<VARIABLE>).
# El servidor los lee al desplegar (servidor/generar-env.sh → /etc/el-puesto/env); nunca
# viven en el repo ni en la instancia fuera de ese archivo (0600, root).
#
#   5-parametros.sh               config + secretos (los secretos se generan UNA vez; nunca se pisan)
#   5-parametros.sh smtp ARCHIVO  credenciales de correo desde un archivo KEY=VALOR
#                                 (SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM)
#   5-parametros.sh alertas CORREO  a quién le llegan las alertas de seguridad del backend
#                                 (ALERT_EMAIL: picos de errores/límites, reuso de sesiones)
#   5-parametros.sh opcion CLAVE VALOR     un ajuste opcional del backend (lista abajo)
#   5-parametros.sh opcion CLAVE --quitar  lo borra: el backend usa su default
#
# Ajustes opcionales (sin el parámetro, el backend usa su default seguro):
#   ADMIN_HOST                 host donde se sirve /admin/* (p. ej. admin.elpuesto.app); sin él,
#                              en cualquier host (la transición). Ponerlo cuando el registro A de
#                              admin y su certificado ya funcionen (docs/DESPLIEGUE.md).
#   APP_HOST                   host propio de la app web (p. ej. app.elpuesto.app): `/app/` solo ahí,
#                              la raíz lleva a ella y los enlaces de acceso de la web apuntan ahí.
#                              Ponerlo cuando https://app.elpuesto.app/health ya responda.
#   AUTH_REQUIRE_PKCE          true|false (false hasta que casi todos los teléfonos tengan la app
#                              que manda PKCE en el enlace mágico)
#   STORAGE_QUOTA_MB           cuota de imágenes por oficial, en MB (1024)
#   RATE_LIMIT_SCALE           multiplica los límites de uso (1; 0 = apagados)
#   STANDINGS_INGEST_EVERY_MIN minutos entre pasadas de la ingesta de posiciones (15; 0 = apagada)
# AUTH_REQUIRE_PKCE y STORAGE_QUOTA_MB se crean con su default la primera vez (para que se vean
# en Parameter Store); después este script no los pisa: se cambian con «opcion».
#
# Tras cambiar algo, aplicarlo en el servidor: scripts/desplegar-servidor.sh
. "$(dirname "$0")/comun.sh"

OPCIONES=" ADMIN_HOST APP_HOST AUTH_REQUIRE_PKCE STORAGE_QUOTA_MB RATE_LIMIT_SCALE STANDINGS_INGEST_EVERY_MIN "
valida_opcion() { # valida_opcion CLAVE VALOR → falla si no es válida
    case "$1" in
        ADMIN_HOST|APP_HOST) [[ "$2" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] ||
                        falla "$1 debe ser un nombre de host (p. ej. admin.elpuesto.app), sin https:// ni /" ;;
        AUTH_REQUIRE_PKCE) [[ "$2" =~ ^(true|false)$ ]] || falla "AUTH_REQUIRE_PKCE: true o false" ;;
        STORAGE_QUOTA_MB|STANDINGS_INGEST_EVERY_MIN) [[ "$2" =~ ^[0-9]+$ ]] || falla "$1: un entero" ;;
        RATE_LIMIT_SCALE) [[ "$2" =~ ^[0-9]+(\.[0-9]+)?$ ]] || falla "RATE_LIMIT_SCALE: un número (1 = normal, 0 = apagados)" ;;
    esac
}

if [ "${1:-}" = opcion ]; then
    clave="${2:?uso: 5-parametros.sh opcion CLAVE VALOR|--quitar}"
    valor="${3:?uso: 5-parametros.sh opcion CLAVE VALOR|--quitar}"
    [[ "$OPCIONES" == *" $clave "* ]] || falla "ajuste desconocido: $clave (válidos:$OPCIONES)"
    if [ "$valor" = --quitar ]; then
        aws_r ssm delete-parameter --name "$PARAM_PREFIX/$clave" 2> /dev/null || true
        echo "✓ $clave quitado (el backend usa su default)"
    else
        valida_opcion "$clave" "$valor"
        poner_parametro "$clave" "$valor" String
        echo "✓ $clave=$valor"
    fi
    echo "Aplícalo en el servidor: scripts/desplegar-servidor.sh (reinicia el backend si el env cambió)"
    exit 0
fi

if [ "${1:-}" = smtp ]; then
    archivo="${2:?uso: 5-parametros.sh smtp ARCHIVO}"
    paso "SMTP desde $archivo"
    while IFS= read -r linea || [ -n "$linea" ]; do
        case "$linea" in ''|'#'*) continue ;; esac
        clave="${linea%%=*}"; valor="${linea#*=}"
        case "$clave" in
            SMTP_PASSWORD) poner_parametro "$clave" "$valor" SecureString ;;
            SMTP_HOST|SMTP_PORT|SMTP_USER|SMTP_FROM) poner_parametro "$clave" "$valor" String ;;
            *) falla "clave desconocida: $clave" ;;
        esac
        echo "✓ $clave"
    done < "$archivo"
    exit 0
fi

if [ "${1:-}" = alertas ]; then
    correo="${2:?uso: 5-parametros.sh alertas CORREO}"
    paso "Alertas de seguridad → $correo"
    poner_parametro ALERT_EMAIL "$correo" String
    echo "✓ ALERT_EMAIL"
    exit 0
fi

paso "Configuración ($PARAM_PREFIX)"
while read -r clave valor; do
    poner_parametro "$clave" "$valor" String
    echo "✓ $clave=$valor"
done <<EOF
PORT 8080
HOST 127.0.0.1
DB_URL jdbc:postgresql://127.0.0.1:5432/el_puesto
DB_USER el_puesto
PUBLIC_BASE_URL https://$API_DOMINIO
DOWNLOADS_URL https://$DOMINIO/descargas/
JAVA_OPTS -Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError
EOF

paso "Secretos (se generan solo si no existen)"
for clave in JWT_SECRET ADMIN_API_KEY DB_PASSWORD; do
    if existe_parametro "$clave"; then
        echo "✓ $clave ya existe (no se toca)"
    else
        poner_parametro "$clave" "$(openssl rand -hex 32)" SecureString false
        echo "✓ $clave generado"
    fi
done

paso "Ajustes opcionales (se crean con su default solo si no existen; se cambian con «opcion»)"
for par in "AUTH_REQUIRE_PKCE false" "STORAGE_QUOTA_MB 1024"; do
    read -r clave valor <<< "$par"
    if existe_parametro "$clave"; then
        echo "✓ $clave=$(leer_parametro "$clave") (no se toca)"
    else
        poner_parametro "$clave" "$valor" String false
        echo "✓ $clave=$valor"
    fi
done
existe_parametro ADMIN_HOST && echo "✓ ADMIN_HOST=$(leer_parametro ADMIN_HOST)" ||
    echo "· ADMIN_HOST sin poner: /admin/* se sirve en cualquier host (transición)"
existe_parametro APP_HOST && echo "✓ APP_HOST=$(leer_parametro APP_HOST)" ||
    echo "· APP_HOST sin poner: la app web /app/ se sirve en cualquier host"

existe_parametro SMTP_HOST || echo "
Falta el correo: 5-parametros.sh smtp ARCHIVO (sin SMTP el backend no manda enlaces de acceso)."
