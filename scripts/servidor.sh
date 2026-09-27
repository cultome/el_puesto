#!/usr/bin/env bash
# Corre un comando (como root) en la instancia de producción por SSM, sin SSH:
#   scripts/servidor.sh 'journalctl -u el-puesto -n 50 --no-pager'
#   scripts/servidor.sh 'systemctl list-timers el-puesto-respaldo'
#   scripts/servidor.sh 'systemctl start el-puesto-respaldo && journalctl -u el-puesto-respaldo -n 5 --no-pager'
# Consola interactiva: aws ssm start-session --target <id> (requiere session-manager-plugin).
. "$(dirname "$0")/../infra/aws/comun.sh"
[ $# -gt 0 ] || falla "uso: scripts/servidor.sh '<comando>'"
ssm_ejecutar "$*" "manual"
