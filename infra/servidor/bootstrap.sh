#!/bin/bash
# Prepara la instancia (Amazon Linux 2023, arm64) para El Puesto. Lo corre el user-data al
# crearla; es idempotente (se puede volver a correr). Deja listos Docker (Postgres + Caddy),
# Java 21, el usuario de servicio, swap y las unidades de systemd; el backend lo instala
# activar.sh en el primer despliegue.
set -euo pipefail
DIR=/opt/el-puesto

echo "== paquetes"
dnf install -y docker java-21-amazon-corretto-headless jq nftables
# Docker Compose (plugin, no viene en los repos de AL2023): lo instala configurar.sh, fijado a
# una versión y verificado contra su SHA-256.
systemctl enable --now docker

echo "== usuario y carpetas"
id elpuesto > /dev/null 2>&1 || useradd --system --home-dir "$DIR" --shell /sbin/nologin elpuesto
mkdir -p "$DIR/releases" /etc/el-puesto
install -d -m 700 /var/backups/el-puesto
chmod 700 /etc/el-puesto

echo "== swap (2 GB: colchón para la JVM + Postgres en 2 GB de RAM)"
if ! swapon --show | grep -q /swapfile; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

bash "$DIR/servidor/configurar.sh"
echo "== bootstrap listo"
