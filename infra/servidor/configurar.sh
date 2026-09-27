#!/bin/bash
# Aplica la configuración del servidor (idempotente; lo corren bootstrap.sh,
# scripts/desplegar-servidor.sh y activar.sh en cada despliegue del backend): env desde
# Parameter Store, unidades de systemd, firewall del IMDS, Postgres + Caddy (docker compose) y
# el timer del respaldo diario. Si el env cambió y el backend corre, lo reinicia — salvo con
# --sin-reiniciar (activar.sh reinicia por su cuenta).
set -euo pipefail
S=/opt/el-puesto/servidor
chmod +x "$S"/*.sh

cambio_env=0
bash "$S/generar-env.sh" || cambio_env=$?
[ "$cambio_env" -le 1 ] || exit "$cambio_env"

# journald: 500 MB y 30 días como máximo. Los logs llevan quién hizo cada petición (el
# oficial o la IP): sirven para investigar abusos, no se guardan más tiempo.
jconf=$'[Journal]\nSystemMaxUse=500M\nMaxRetentionSec=30day\n'
if [ "$(cat /etc/systemd/journald.conf.d/el-puesto.conf 2> /dev/null)"$'\n' != "$jconf" ]; then
    mkdir -p /etc/systemd/journald.conf.d
    printf '%s' "$jconf" > /etc/systemd/journald.conf.d/el-puesto.conf
    systemctl restart systemd-journald
    echo "journald: 500 MB / 30 días"
fi

install -m 644 "$S/el-puesto.service" "$S/el-puesto-respaldo.service" "$S/el-puesto-respaldo.timer" \
    "$S/el-puesto-imds.service" /etc/systemd/system/
systemctl daemon-reload

# Firewall: el usuario del backend (elpuesto) no llega a los metadatos de EC2 (IMDS), donde
# están las credenciales del rol de la instancia (imds.nft). Tabla propia de nftables cargada por
# su unidad al arrancar; no toca las reglas de Docker. Reiniciarla re-aplica imds.nft.
command -v nft > /dev/null || dnf install -y nftables
systemctl enable el-puesto-imds.service > /dev/null 2>&1
systemctl restart el-puesto-imds.service
if ! command -v setpriv > /dev/null; then
    echo "(sin setpriv: no se comprobó el bloqueo del IMDS)"
elif setpriv --reuid=elpuesto --regid=elpuesto --clear-groups \
        curl -fsS -m 3 -o /dev/null -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token 2> /dev/null; then
    echo "✗ OJO: el usuario elpuesto todavía llega al IMDS (revisa: nft list table inet elpuesto_imds)" >&2
else
    echo "IMDS bloqueado para el usuario elpuesto"
fi

# Docker Compose (plugin; no viene en los repos de AL2023): versión FIJA, verificada contra su
# SHA-256 (tomada del .sha256 oficial de la release). Se (re)instala solo si el binario no es
# exactamente ese. Actualizar = cambiar versión y sumas (una por arquitectura).
COMPOSE_VERSION=v5.5.1
case "$(uname -m)" in
    aarch64) COMPOSE_SHA256=732e3a84c1a0f67256ce80bc2598a24546b10ca05f9faa97efceb1171ece2ef7 ;;
    x86_64)  COMPOSE_SHA256=db1889184726840f75c4f9c001048430d4f25b3be3cb084d3ddd762bc0aed576 ;;
    *) echo "arquitectura sin docker compose fijado: $(uname -m)" >&2; exit 1 ;;
esac
plugin=/usr/local/lib/docker/cli-plugins/docker-compose
if [ "$(sha256sum "$plugin" 2> /dev/null | cut -d' ' -f1)" != "$COMPOSE_SHA256" ]; then
    mkdir -p "$(dirname "$plugin")"
    curl -fsSL --proto '=https' -o "$plugin.nuevo" \
        "https://github.com/docker/compose/releases/download/$COMPOSE_VERSION/docker-compose-linux-$(uname -m)"
    if ! echo "$COMPOSE_SHA256  $plugin.nuevo" | sha256sum -c --quiet; then
        rm -f "$plugin.nuevo"
        echo "✗ docker compose $COMPOSE_VERSION no coincide con su SHA-256: no se instaló" >&2
        exit 1
    fi
    chmod 755 "$plugin.nuevo"
    mv -f "$plugin.nuevo" "$plugin"
    echo "docker compose $COMPOSE_VERSION instalado"
fi

docker compose -f "$S/docker-compose.yml" --env-file /etc/el-puesto/compose.env up -d --remove-orphans
# El Caddyfile está montado como ARCHIVO: `aws s3 sync` lo reemplaza (otro inodo) y el
# contenedor sigue viendo el anterior — `caddy reload` respondía "config is unchanged" y un
# bloque nuevo (app.elpuesto.app) no llegaba. Si el que ve el contenedor difiere, se reinicia
# (segundos; los WebSockets se reconectan solos); si es el mismo, basta recargar.
caddy_host="$(md5sum < "$S/Caddyfile" | cut -d' ' -f1)"
caddy_visto="$(docker exec el-puesto-caddy md5sum /etc/caddy/Caddyfile 2> /dev/null | cut -d' ' -f1 || true)"
if [ -n "$caddy_visto" ] && [ "$caddy_host" != "$caddy_visto" ]; then
    echo "Caddyfile cambió → reiniciando Caddy"
    docker restart el-puesto-caddy > /dev/null
else
    docker exec el-puesto-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile 2> /dev/null || true
fi

systemctl enable el-puesto.service > /dev/null 2>&1
systemctl enable --now el-puesto-respaldo.timer

if [ "$cambio_env" = 1 ] && [ "${1:-}" != --sin-reiniciar ]; then
    echo "env cambió → reiniciando el backend"
    systemctl try-restart el-puesto
fi
echo "== configuración aplicada"
