# Compilar, correr y desplegar El Puesto

Guía para quien clona el repo (persona o agente de IA) en una máquina nueva. Detalle de la
infraestructura de producción: [`DESPLIEGUE.md`](DESPLIEGUE.md). Contexto de producto y
decisiones: [`../CLAUDE.md`](../CLAUDE.md).

## 1. Qué hay en el repo

| Carpeta | Qué es |
|---|---|
| `shared/` | Modelo de dominio y contratos (Kotlin Multiplatform: Android + JVM + wasm). El wire es **protobuf**. |
| `app/` | La app de los oficiales COMÚN a Android y web (Compose Multiplatform): pantallas, repositorio offline-first, cliente HTTP. |
| `androidApp/` | La app Android: actividad, servicios, notificaciones, SQLDelight, Keystore y actualizaciones, sobre `app/`. |
| `webApp/` | La app web (Kotlin/Wasm) sobre `app/`: compila a archivos estáticos que el backend sirve en `/app/`. |
| `backend/` | API Ktor + Postgres. Sirve también la admin web (`/admin/ui/`) y la API admin (`/admin/*`, docs en `/admin/docs`). |
| `adminMcp/` | Servidor MCP (stdio) para que agentes administren datos por la API admin. |
| `data/` | Datos reales versionados (CSV de circuitos, campeonatos, eventos) + `scripts/cargar-datos.py`. `data/privado/` (personas) NO está en git. |
| `web/` | Sitio `elpuesto.app` (landing + `/descargas/`), estático. |
| `infra/` | Producción en AWS: `infra/aws/` (montaje, pasos 1–9) e `infra/servidor/` (lo que corre en la instancia). |
| `scripts/` | Carga de datos, despliegue y publicación (ver §5). |
| `.github/workflows/` | CI/CD: despliegue automático del backend y del sitio. |

## 2. Requisitos

| Para | Necesitas |
|---|---|
| Compilar la app y el backend | **JDK 21**, **Android SDK** (platform 35, build-tools 35) y `local.properties` con `sdk.dir=/ruta/al/Android/Sdk` (no se versiona). Gradle: el wrapper (`./gradlew`, 8.10.2; verifica la suma SHA-256 de la distribución que baja). |
| Backend local | **Docker** (Postgres 16 por `docker-compose.yml`). |
| Cargar datos | **Python 3** (sin dependencias externas). |
| Compilar la app web | Nada extra: Gradle baja Node.js, Yarn y Binaryen como dependencias verificadas (repositorios en `settings.gradle.kts`) y los paquetes npm quedan fijados en `kotlin-js-store/wasm/yarn.lock`. |
| Desplegar a mano / publicar la app | **AWS CLI v2** con acceso a la cuenta (perfil `elpuesto`), **`gh`** con sesión, `jq`; para la app, además la **llave de release** (§6). |

Gradle configura TODOS los módulos (incluida la app), así que aun para compilar solo el
backend hace falta el Android SDK y `local.properties`.

**Dependencias verificadas** (`gradle/verification-metadata.xml`): Gradle comprueba la huella
SHA-256 de CADA librería y plugin que baja; si una no coincide (dependencia alterada en un
repositorio) el build falla con "Dependency verification failed". Al **agregar o subir de
versión** una dependencia (o compilar por primera vez en otro sistema operativo, p. ej. el
`aapt2` de macOS), regenera y revisa el diff antes de commitearlo:

```sh
# Con una caché de Gradle VACÍA (como la de CI): con la tuya caliente, Gradle no pide algunos
# .pom/.module de BOMs y padres, no los registra, y CI falla (pasó el 2026-09-26).
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 \
  :backend:installDist :adminMcp:installDist :androidApp:assembleDebug :androidApp:assembleRelease \
  :app:testDebugUnitTest :webApp:wasmJsBrowserDistribution
git diff gradle/verification-metadata.xml   # solo lo que esperabas que cambiara
# Comprobar como CI (estricto, caché vacía otra vez):
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew :backend:installDist --no-configuration-cache
```

## 3. Compilar la app

```sh
./gradlew :androidApp:assembleDebug                                    # API = http://10.0.2.2:8080 (backend local visto desde el emulador)
./gradlew :androidApp:assembleDebug -PapiBaseUrl=https://mi-tunel.example  # teléfono físico contra un backend expuesto (p. ej. ngrok)
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

- La URL del API se **compila dentro** del APK (`BuildConfig.API_BASE_URL`, propiedad
  `-PapiBaseUrl`). No hay selector en la app.
- **Debug** se firma con `androidApp/debug.keystore` (versionado a propósito: misma firma en
  toda máquina → `adb install -r` sin desinstalar). Permite http en claro (para `10.0.2.2`).
- **Release** (`:androidApp:assembleRelease`) se firma con la llave de release, que NO está en
  el repo (§6). Sin ella el APK sale sin firmar y no sirve para distribuir. Release solo habla
  https. Una app debug y una release no se pueden instalar una encima de la otra (firmas
  distintas, mismo `applicationId`): hay que desinstalar.
- Emulador sin ventana: `emulator -avd <avd> -no-window -gpu swangle_indirect` (con
  `swiftshader_indirect` el emulador truena con algunos degradados).

## 3b. Compilar y probar la app web

```sh
./gradlew :webApp:wasmJsBrowserDistribution     # → webApp/build/dist/wasmJs/productionExecutable/
WEB_APP_DIR="$PWD/webApp/build/dist/wasmJs/productionExecutable" backend/build/install/backend/bin/backend
# → http://localhost:8080/app/  (mismo origen que el API: sin CORS; cookie y WebSocket al mismo host)
```

- Usa la distribución de **producción**: la de desarrollo (`wasmJsBrowserDevelopmentExecutableDistribution`)
  trae `eval` de webpack y la CSP de `/app` (sin `unsafe-eval`) la bloquea, a propósito.
- Mismo código que Android (`app/`); lo propio del navegador está en `webApp/` (sesión, caché en
  memoria, Main) y en los `wasmJsMain` de `app/` (fotos por `<input type=file>`, "atrás" con el
  historial, descargas). Ver CLAUDE.md §3 → "App web".
- Entrar en local sin correo: pide el enlace DESDE la página (deja el reto PKCE del navegador),
  cambia el hash del token en `magic_tokens` por el de uno tuyo con ese mismo reto (como en
  Android) y abre `http://localhost:8080/app/#auth=<token>` en el MISMO navegador.
- La versión visible sale de `-PwebVersion=…` (default `0.1.0`).

## 4. Correr el backend en local

```sh
docker compose up -d                          # Postgres en 127.0.0.1:55433 (el_puesto/el_puesto/el_puesto_dev)
./gradlew :backend:installDist
backend/build/install/backend/bin/backend     # http://localhost:8080 — crea el esquema vacío al arrancar
```

- Configuración por variables de entorno con defaults de desarrollo (`backend/.../Config.kt`):
  `PORT`, `DB_URL`/`DB_USER`/`DB_PASSWORD`, `JWT_SECRET`, `ADMIN_API_KEY`
  (dev: `dev-admin-key-change-me`), `SMTP_*`, `PUBLIC_BASE_URL`. Con `PUBLIC_BASE_URL`
  definido el backend se niega a arrancar con secretos de desarrollo.
- **App web** (opcional): `WEB_APP_DIR` = carpeta con la app web compilada (`index.html`,
  `.mjs`/`.js`, `.wasm`); el backend la sirve en `/app/` (sin la variable, `/app` da 404).
  `WEB_APP_URL` = dirección de la app web en los enlaces mágicos que pide la web
  (`<WEB_APP_URL>#auth=<token>`; default `${PUBLIC_BASE_URL}/app/`). En local sin
  `PUBLIC_BASE_URL` el `devLink` de la web sale relativo: `/app/#auth=…`.
- **Redesplegar en local**: detén el backend ANTES de `installDist` (sobrescribir los jars bajo
  una JVM viva da `ClassNotFoundException`). Mátalo por puerto (`lsof -t -i:8080`), no con
  `pkill -f`.
- **Datos**: la base arranca vacía. Carga los catálogos reales:
  ```sh
  EL_PUESTO_API=http://localhost:8080 EL_PUESTO_ADMIN_KEY=dev-admin-key-change-me python3 scripts/cargar-datos.py --dry-run
  EL_PUESTO_API=http://localhost:8080 EL_PUESTO_ADMIN_KEY=dev-admin-key-change-me python3 scripts/cargar-datos.py
  ```
  (dos corridas la primera vez: dibujos, posiciones y eventos dependen de trazados que se crean
  en la primera). Formato de los CSV en `data/README.md`.
- **Tu cuenta**: crea `data/privado/cuentas.csv` con tu correo (formato en
  `data/privado/LEEME.md`) y vuelve a correr el cargador (crea tu oficial y tu cuenta ACTIVE),
  o por la API admin: `PUT /admin/accounts/{email}` con `{officerId, status}` (el oficial debe
  existir; ver `/admin/docs`).
- **Entrar sin correo**: sin `SMTP_HOST` ni `PUBLIC_BASE_URL`, el enlace mágico viene en la
  respuesta (`devLink`); ábrelo en el emulador:
  ```sh
  curl -s -X POST localhost:8080/auth/magic-link -H 'Content-Type: application/json' -d '{"email":"tu@correo"}'
  adb shell am start -a android.intent.action.VIEW -d 'elpuesto://auth?token=…'
  ```
- Admin web local: `http://localhost:8080/admin/ui/` con la `ADMIN_API_KEY`. El MCP
  (`.mcp.json` → `adminMcp/run.sh`) necesita `./gradlew :adminMcp:installDist` y las variables
  `EL_PUESTO_API` / `EL_PUESTO_ADMIN_KEY`.

## 5. Desplegar a producción

Producción: sitio `https://elpuesto.app` (CloudFront + S3), API `https://api.elpuesto.app`
(EC2 en `mx-central-1`), admin web `https://admin.elpuesto.app/admin/ui/` (SOLO ahí: con
`ADMIN_HOST` activo, `api.elpuesto.app` responde 404 en `/admin/*`).

### Automático: push a `master`

| El push cambia… | GitHub Actions | Hace |
|---|---|---|
| `backend/`, `shared/`, Gradle | *Desplegar backend* | job `construir` (sin credenciales) compila; job `desplegar` (entorno `produccion`) sube el paquete y lo activa con el documento de SSM `ElPuesto-Activar`; si la versión nueva no contesta `/health`, regresa sola a la anterior |
| `web/` | *Publicar sitio* | sube la landing e invalida CloudFront (nunca `/descargas/`) |
| `infra/servidor/`, `web/descargas/`, lo demás (app, docs, `data/`) | — | nada: el servidor y la página de descargas se publican a mano (abajo) |

**Hacer push a `master` ES desplegar.** Credenciales por OIDC (sin claves en GitHub); el
rol solo lo asume un job del entorno `produccion`, que solo acepta `master` (y puede exigir
aprobación). CI **no** puede correr comandos como root, tocar `infra/servidor/`, la página de
descargas, los APKs, `version.json`, los respaldos ni Parameter Store (detalle en
[`DESPLIEGUE.md`](DESPLIEGUE.md#qué-puede-ci-y-qué-no)). Las acciones van fijadas por SHA.
Ambos workflows también corren a mano (*Actions → Run workflow*). Los commits locales no
despliegan nada.

### A mano (requiere acceso a la cuenta de AWS)

| Qué | Comando |
|---|---|
| Backend | `scripts/desplegar-backend.sh [ref]` · rollback: `--anterior` |
| Servidor (`infra/servidor/`, Parameter Store; corre como root) | `scripts/desplegar-servidor.sh [ref]` · ¿al día?: `--revisar` |
| Sitio (landing) | `scripts/publicar-sitio.sh [ref]` |
| Página `/descargas/` (textos, permisos) | `scripts/publicar-descargas.sh [ref]` |
| Respaldos: listar, bajar, restaurar | `scripts/restaurar-respaldo.sh` · `--bajar CLAVE` · `CONFIRMAR=si … CLAVE` |
| Ajustes del backend (`ADMIN_HOST`, `AUTH_REQUIRE_PKCE`, `STORAGE_QUOTA_MB`…) | `infra/aws/5-parametros.sh opcion CLAVE VALOR` y luego `scripts/desplegar-servidor.sh` |
| Datos de `data/` a producción | `scripts/prod.sh python3 scripts/cargar-datos.py --dry-run` (y luego sin `--dry-run`; usa `https://admin.elpuesto.app` o, si aún no responde, `api`) |
| Comandos en el servidor (sin SSH) | `scripts/servidor.sh 'journalctl -u el-puesto -n 50 --no-pager'` |
| Imagen de vista previa (`web/og.png`) | `scripts/generar-og.sh` (Chromium de Playwright) |

Todo despliega desde un **commit** (worktree temporal o `git archive`), nunca desde cambios
sin commitear.

## 6. Publicar una versión de la app

La app NO se publica por CI: se firma con la **llave de release**
(`~/.config/el-puesto/release.jks` + `release.properties`), que solo tiene el mantenedor y
nunca va al repo ni a GitHub.

```sh
scripts/nueva-version.sh --prueba   # qué haría, sin tocar nada
scripts/nueva-version.sh            # patch (o minor | major | X.Y.Z)
```

Revisa todo (y avisa si el servidor no tiene `infra/servidor/` de HEAD), arma el CHANGELOG
(se edita en `$EDITOR`), commitea, hace push, espera el despliegue del backend si aplica (los
dos jobs; si el entorno pide aprobación, apruébala en Actions), publica
`el-puesto-X.Y.Z.apk` en `https://elpuesto.app/descargas/` (link y huella nuevos +
`version.json`) y sube el tag. Sin la llave,
`scripts/publicar-app.sh` se niega a publicar (verifica la huella del certificado). Los
teléfonos se enteran solos: la app lee `version.json` y ofrece actualizar con un toque.

Probar el actualizador en el emulador (sin tocar producción): servir un `version.json` y un
APK de debug con `versionCode` mayor (`python3 -m http.server 8099`) y compilar la app con
`-PupdatesUrl=http://10.0.2.2:8099/version.json`; sin esa propiedad un build de debug no
revisa actualizaciones (no podría instalar uno de release: otra firma).

Cambiar la base local de la app (`ElPuesto.sq`) exige una migración `N.sqm`: ver
`androidApp/src/main/sqldelight/README.md` (el build falla si falta).

## 7. Reglas para agentes

- **Pregunta antes de tocar producción.** En particular: el cargador contra producción
  (REEMPLAZA las asignaciones de los eventos con roster), marcar un evento como activo (avisa
  a TODOS los teléfonos conectados), publicar la app (queda pública para los oficiales),
  `restaurar-respaldo.sh` (borra la base), `desplegar-servidor.sh` (cambia lo que corre como
  root y puede recrear Postgres/Caddy), los scripts de `infra/aws/` y cualquier `push` a
  `master` (despliega).
- **Nunca** commitees ni imprimas secretos: `setenv`, `data/privado/`, la llave de release,
  la `ADMIN_API_KEY` (léela con `scripts/prod.sh`, que la toma de Parameter Store al vuelo).
- Protobuf no tiene null: todo campo nullable del modelo lleva `= null` y los números de
  `@ProtoNumber` no se reutilizan (un teléfono con caché vieja debe seguir funcionando).
- Mantén al día `backend/src/main/resources/admin-api.md` y `admin-openapi.json` al cambiar la
  API admin, y la lista de permisos de `web/descargas/index.html` al cambiar el manifest
  (se publica con `scripts/publicar-descargas.sh`, no por CI).
- Al tocar `.github/workflows/`, fija toda acción por SHA de commit (`# vX.Y.Z` al lado) y
  mantén iguales las rutas de `desplegar-backend.yml` y `RUTAS_BACKEND` en
  `scripts/nueva-version.sh`. Un job que necesite AWS declara `environment: produccion`; el
  que compila no lleva `id-token`.
- Verifica compilando (y corriendo si aplica) antes de dar algo por hecho; un commit por
  cambio lógico.
