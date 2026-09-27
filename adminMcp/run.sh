#!/bin/sh
# Lanza el servidor MCP de administración (stdio). Requiere haber corrido
# `gradle :adminMcp:installDist`. Config por env: EL_PUESTO_API, EL_PUESTO_ADMIN_KEY.
DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$DIR/build/install/adminMcp/bin/adminMcp"
if [ ! -x "$BIN" ]; then
  echo "adminMcp no está compilado; corre: gradle :adminMcp:installDist" >&2
  exit 1
fi
[ -z "$JAVA_HOME" ] && [ -d "$HOME/.jdks/jdk-21.0.11+10" ] && export JAVA_HOME="$HOME/.jdks/jdk-21.0.11+10"
# La clave NO vive en .mcp.json (versionado): se lee de ./setenv (fish, gitignored), donde
# está EL_PUESTO_ADMIN_KEY = clave NOMBRADA del MCP (auditable y revocable, sin scope keys).
ROOT="$(cd "$DIR/.." && pwd)"
if [ -z "$EL_PUESTO_ADMIN_KEY" ] && [ -f "$ROOT/setenv" ] && command -v fish >/dev/null 2>&1; then
  EL_PUESTO_ADMIN_KEY="$(fish -c "source '$ROOT/setenv'; echo \$EL_PUESTO_ADMIN_KEY")"
  [ -z "$EL_PUESTO_API" ] && EL_PUESTO_API="$(fish -c "source '$ROOT/setenv'; echo \$EL_PUESTO_API")"
  export EL_PUESTO_ADMIN_KEY EL_PUESTO_API
fi
exec "$BIN"
