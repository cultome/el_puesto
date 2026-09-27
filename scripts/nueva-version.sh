#!/usr/bin/env bash
# Saca una versión NUEVA de la app de punta a punta (en tu máquina: la llave de release no sale
# de aquí):
#   1. revisa que todo esté en orden: rama master al día con GitHub, llave de release, sesiones
#      de AWS (perfil elpuesto) y de GitHub (gh),
#   2. calcula la versión (patch/minor/major o una explícita) y versionCode + 1,
#   3. arma el borrador del CHANGELOG con los commits desde el último tag y lo abre en tu editor,
#   4. commitea "release: El Puesto X.Y.Z" (CHANGELOG.md + androidApp/build.gradle.kts),
#   5. hace push a master y, si lo que se sube trae cambios de backend, ESPERA a que GitHub
#      Actions lo despliegue (la app nueva puede depender de él; si falla, la app NO se publica),
#   6. publica la app (scripts/publicar-app.sh: APK firmado el-puesto-X.Y.Z.apk, link de descarga
#      nuevo, version.json, tag) y sube el tag a GitHub.
#
# Uso: scripts/nueva-version.sh [patch|minor|major|X.Y.Z] [--prueba] [--sin-editar] [--si]
#   patch (default) 1.1.0 → 1.1.1 · minor → 1.2.0 · major → 2.0.0
#   --prueba      solo muestra la versión, las revisiones y el borrador; no toca nada
#   --sin-editar  usa el borrador del CHANGELOG tal cual (no abre el editor)
#   --si          no pide confirmación
. "$(dirname "$0")/../infra/aws/comun.sh"
cd "$RAIZ"

# Mismas rutas que dispara .github/workflows/desplegar-backend.yml (mantenerlas iguales).
# infra/servidor/ ya no: CI no la aplica (scripts/desplegar-servidor.sh, a mano).
RUTAS_BACKEND=(backend shared app webApp kotlin-js-store infra/aws/comun.sh scripts/desplegar-backend.sh
    gradle build.gradle.kts settings.gradle.kts gradle.properties .github/workflows/desplegar-backend.yml)
KTS=androidApp/build.gradle.kts

nivel=patch; prueba=0; editar=1; confirmar=1
for a in "$@"; do
    case "$a" in
        patch|minor|major) nivel="$a" ;;
        [0-9]*.[0-9]*.[0-9]*) nivel="$a" ;;
        --prueba) prueba=1 ;;
        --sin-editar) editar=0 ;;
        --si) confirmar=0 ;;
        *) falla "argumento desconocido: $a (ver el encabezado del script)" ;;
    esac
done

# ——— Versión ———
actual="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$KTS")"
codigo="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$KTS")"
IFS=. read -r ma mi pa <<< "$actual"
case "$nivel" in
    patch) nueva="$ma.$mi.$((pa + 1))" ;;
    minor) nueva="$ma.$((mi + 1)).0" ;;
    major) nueva="$((ma + 1)).0.0" ;;
    *) nueva="$nivel" ;;
esac
nuevo_codigo=$((codigo + 1))
[ "$(printf '%s\n%s\n' "$actual" "$nueva" | sort -V | tail -n 1)" = "$nueva" ] && [ "$nueva" != "$actual" ] ||
    falla "la versión nueva ($nueva) debe ser mayor que la actual ($actual)"
ultimo="$(git describe --tags --abbrev=0 --match 'v*' 2> /dev/null || true)"
rango="${ultimo:+$ultimo..}HEAD"

# ——— Revisiones ———
paso "Revisiones"
problemas=()
[ "$(git symbolic-ref --short HEAD)" = master ] || problemas+=("no estás en la rama master")
git rev-parse -q --verify "refs/tags/v$nueva" > /dev/null && problemas+=("el tag v$nueva ya existe")
if git fetch -q origin master 2> /dev/null; then
    git merge-base --is-ancestor origin/master HEAD || problemas+=("tu master está atrás de GitHub (haz git pull)")
else
    problemas+=("no pude consultar GitHub (git fetch)")
fi
[ -f "$HOME/.config/el-puesto/release.properties" ] || problemas+=("falta la llave de release en ~/.config/el-puesto/")
aws sts get-caller-identity > /dev/null 2>&1 || problemas+=("la sesión de AWS (perfil $AWS_PROFILE) no es válida: vuelve a iniciar sesión")
gh auth status > /dev/null 2>&1 || problemas+=("gh no tiene sesión de GitHub (gh auth login)")
sucio="$(git status --porcelain)"
pendientes="$(git rev-list --count origin/master..HEAD 2> /dev/null || echo '?')"
if git diff --quiet origin/master..HEAD -- "${RUTAS_BACKEND[@]}" 2> /dev/null; then
    backend="sin cambios de backend: no hay despliegue que esperar"
    esperar_backend=0
else
    backend="el push trae cambios de backend: se espera su despliegue antes de publicar la app"
    esperar_backend=1
fi
if [ ${#problemas[@]} -gt 0 ]; then
    servidor="sin revisar"
elif servidor_al_dia HEAD; then
    servidor="infra/servidor/ de HEAD ya está aplicado"
else
    servidor="ojo: infra/servidor/ de HEAD NO está aplicado en producción (CI no lo hace); si el backend lo necesita, corre antes scripts/desplegar-servidor.sh"
fi
for p in "${problemas[@]}"; do echo "✗ $p"; done
[ ${#problemas[@]} -eq 0 ] && echo "✓ todo en orden"

# ——— Borrador del CHANGELOG ———
MESES=(enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre)
fecha="$(TZ=America/Mexico_City date +%-d) de ${MESES[$(( $(TZ=America/Mexico_City date +%-m) - 1 ))]} de $(TZ=America/Mexico_City date +%Y)"
borrador="$(mktemp --suffix=.md)"; trap 'rm -f "$borrador"' EXIT
{
    echo "%% Notas de la versión $nueva. Edítalas en español para los oficiales (qué cambia para"
    echo "%% ellos, no el detalle técnico). Las líneas que empiezan con %% se ignoran. Si dejas el"
    echo "%% archivo vacío, no se publica nada."
    echo "## v$nueva — $fecha"
    echo
    git log --reverse --format='%s' "$rango" | python3 -c '
import re, sys
grupos = {"Para los oficiales": [], "Administración": [], "Correcciones": [], "Otros cambios": []}
for s in sys.stdin.read().splitlines():
    m = re.match(r"(\w+)(?:\(([^)]*)\))?!?:\s*(.+)", s)
    tipo, alcance, texto = (m.group(1), m.group(2) or "", m.group(3)) if m else ("", "", s)
    if tipo in ("docs", "chore", "release", "test", "ci") or s.startswith("Merge"):
        continue
    texto = texto[:1].upper() + texto[1:]
    if tipo == "fix":
        grupos["Correcciones"].append(texto)
    elif alcance == "app":
        grupos["Para los oficiales"].append(texto)
    elif alcance == "admin":
        grupos["Administración"].append(texto)
    else:
        grupos["Otros cambios"].append(texto)
vacio = True
for titulo, items in grupos.items():
    if items:
        vacio = False
        print(f"### {titulo}\n")
        print("\n".join(f"- {t}" for t in items))
        print()
if vacio:
    print("- (describe los cambios de esta versión)\n")
'
} > "$borrador"

echo
echo "Versión:  $actual → $nueva (versionCode $codigo → $nuevo_codigo)"
echo "Commits:  $(git rev-list --count "$rango") desde ${ultimo:-el inicio} · $pendientes sin subir a GitHub"
echo "Backend:  $backend"
echo "Servidor: $servidor"
[ -n "$sucio" ] && echo "Ojo:      hay cambios sin commitear; NO van en la versión"

if [ "$prueba" = 1 ]; then
    echo; echo "——— Borrador del CHANGELOG ———"; grep -v '^%%' "$borrador"
    echo "(--prueba: no se tocó nada)"
    exit 0
fi
[ ${#problemas[@]} -eq 0 ] || falla "corrige lo de arriba y vuelve a correrlo"

if [ "$editar" = 1 ]; then
    "${VISUAL:-${EDITOR:-nano}}" "$borrador"
fi
notas="$(grep -v '^%%' "$borrador" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
[ -n "$(tr -d '[:space:]' <<< "$notas")" ] || falla "el CHANGELOG quedó vacío: no se publicó nada"

if [ "$confirmar" = 1 ]; then
    echo; echo "$notas"; echo
    read -r -p "¿Publicar El Puesto $nueva? [s/N] " r
    [[ "$r" =~ ^[sS]$ ]] || { echo "Cancelado: no se tocó nada."; exit 0; }
fi

# ——— 4. Commit de la versión ———
paso "Commit release: El Puesto $nueva"
NOTAS="$notas" python3 - <<'PY'
import os, re
ruta = "CHANGELOG.md"
texto = open(ruta, encoding="utf-8").read()
notas = os.environ["NOTAS"].strip() + "\n\n"
m = re.search(r"^## ", texto, re.M)
texto = texto[:m.start()] + notas + texto[m.start():] if m else texto.rstrip() + "\n\n" + notas
open(ruta, "w", encoding="utf-8").write(texto)
PY
sed -i -e "s/^\( *versionCode = \)[0-9]*/\1$nuevo_codigo/" -e "s/^\( *versionName = \)\"[^\"]*\"/\1\"$nueva\"/" "$KTS"
git commit -q -m "release: El Puesto $nueva" -- CHANGELOG.md "$KTS"
sha="$(git rev-parse HEAD)"
echo "✓ ${sha:0:10}"

# ——— 5. Push y despliegue del backend ———
paso "Push a GitHub"
git push -q origin master
echo "✓ master en GitHub"
if [ "$esperar_backend" = 1 ]; then
    paso "Esperando el despliegue del backend (GitHub Actions)"
    run=""
    for _ in $(seq 30); do
        run="$(gh run list --workflow desplegar-backend.yml --commit "$sha" --limit 1 --json databaseId -q '.[0].databaseId' 2> /dev/null || true)"
        [ -n "$run" ] && break
        sleep 4
    done
    [ -n "$run" ] || falla "GitHub no arrancó el despliegue del backend; la app NO se publicó (revisa la pestaña Actions y luego: scripts/publicar-app.sh $sha)"
    # El run tiene dos jobs (construir → desplegar); watch espera a ambos. Si el entorno
    # "produccion" exige aprobación, el job desplegar queda en espera hasta que la des.
    echo "si GitHub pide aprobar el entorno produccion: gh run view $run --web"
    if ! gh run watch "$run" --exit-status --interval 10 > /dev/null; then
        falla "el despliegue del backend falló; la app NO se publicó. Detalle: gh run view $run --log-failed · al arreglarlo: scripts/publicar-app.sh $sha"
    fi
    echo "✓ backend desplegado"
fi

# ——— 6. App ———
paso "Publicando la app"
"$RAIZ/scripts/publicar-app.sh" "$sha" || falla "la publicación de la app falló (el commit ya está en GitHub): corrige y vuelve a correr scripts/publicar-app.sh $sha"
git push -q origin "v$nueva"
echo
echo "✓ El Puesto $nueva publicada: https://$DOMINIO/descargas/ (tag v$nueva en GitHub)"
