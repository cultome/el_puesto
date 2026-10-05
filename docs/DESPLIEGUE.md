# Producción en AWS

```
                      ┌─────────────── CloudFront ───────────────┐
elpuesto.app  ──────▶ │ función: www→apex, carpeta→index.html     │ ──▶ S3 elpuesto-app-web (privado, OAC, versionado)
www.elpuesto.app      │ caché según Cache-Control de cada objeto  │      index.html, 404.html, descargas/,
                      └───────────────────────────────────────────┘      el-puesto-<versión>.apk, version.json

api.elpuesto.app   ─┐
app.elpuesto.app   ─┤  (app web de los oficiales: /app/ + la API que usa)
admin.elpuesto.app ─┴▶ IP elástica ─▶ EC2 t4g.small (mx-central-1, Amazon Linux 2023 arm64)
                                       ├─ Caddy 2.11.4 (docker, red del host): TLS Let's Encrypt → 127.0.0.1:8080
                                       ├─ backend Ktor (systemd el-puesto, usuario elpuesto, /opt/el-puesto/current)
                                       │    └─ app web en current/web (viaja en cada versión del backend)
                                       │    └─ sin acceso al IMDS (nftables + IPAddressDeny)
                                       ├─ Postgres 16.15 (docker, 127.0.0.1:5432)
                                       └─ timer 03:30 CDMX: pg_dump → S3 elpuesto-app-respaldos (Object Lock)

CloudTrail (todas las regiones) → S3 elpuesto-app-auditoria · root / login fallido → EventBridge → SNS → correo
```

- **Región** `mx-central-1` (Querétaro). CloudFront y su certificado (ACM) son globales: el
  certificado vive en `us-east-1`.
- **DNS en Namecheap** (no en Route 53, para no romper su reenvío de correo): los registros se
  capturan a mano una sola vez (tabla abajo).
- **Sin SSH**: la instancia no abre el puerto 22. Todo se opera con SSM Run Command
  (`scripts/servidor.sh`) o Session Manager.
- **Configuración y secretos** en Parameter Store (`/elpuesto/prod/*`); el servidor los baja a
  `/etc/el-puesto/env` (0600) al desplegar. Nada de secretos en el repo ni en los scripts.
- **El API NO pasa por CloudFront**: el freno de fuerza bruta lee la IP real del
  `X-Forwarded-For` solo cuando la conexión llega de loopback (Caddy en la misma máquina), y
  el WebSocket/SSE no sufren el corte por inactividad de CloudFront.
- **Admin en su propio host**: `https://admin.elpuesto.app/admin/ui/` (y la API `/admin/*`).
  Caddy solo deja pasar `/admin/*` y `/health` ahí; con `ADMIN_HOST` puesto, el backend deja de
  servir `/admin/*` en `api` (antes de eso, sigue en ambos: la transición).
- **App web en su propio host**: `https://app.elpuesto.app` (la raíz lleva a `/app/`). Es la
  misma app que Android (Kotlin/Wasm) y ese host atiende también la API que usa: mismo origen,
  así la cookie de sesión (`ep_rt`, HttpOnly) y el WebSocket no necesitan CORS, y su
  almacenamiento del navegador no lo comparte ninguna otra página nuestra. Con `APP_HOST`
  puesto, en `api` `/app` redirige ahí. Sus archivos van **dentro de cada versión del
  backend** (`releases/<versión>/web`): se activan y se regresan con él. Caddy comprime solo
  esos estáticos (~17 MB → ~6 MB).
- Todo se despliega **desde un commit** (worktree temporal o `git archive`), nunca del working tree.
- **CI tiene poder acotado** (ver [Qué puede CI y qué no](#qué-puede-ci-y-qué-no)): compila sin
  credenciales y solo puede activar versiones del backend y subir la landing. Lo que corre como
  root en el servidor, la página de descargas, la app y los respaldos son del operador.

Archivos: `infra/aws/` (montaje, pasos 1–9, `documento-activar.json`), `infra/servidor/` (lo que
corre en la instancia), `scripts/desplegar-backend.sh`, `scripts/desplegar-servidor.sh`,
`scripts/publicar-app.sh`, `scripts/publicar-descargas.sh`, `scripts/publicar-sitio.sh`,
`scripts/restaurar-respaldo.sh`, `scripts/prod.sh`, `scripts/servidor.sh`.

> **¿Ya tienes producción montada con la versión anterior de estos scripts?** Sigue
> [Aplicar el endurecimiento de 2026-09-26](#aplicar-el-endurecimiento-de-2026-09-26-a-la-cuenta-existente).

## Montaje inicial (una sola vez)

Requisitos locales: AWS CLI v2 con un perfil con permisos de administrador (por defecto
`elpuesto`; otro con `AWS_PROFILE=…`), `jq`, `python3`, `gh` con sesión, y el JDK/SDK/Gradle de
CLAUDE.md §5. En la cuenta de producción `elpuesto` es un usuario de **IAM Identity Center**
(no root): ver "Acceso de operador" abajo.

| Paso | Comando | Después |
|---|---|---|
| 1 | `infra/aws/1-cuenta.sh` | habilita `mx-central-1` (minutos) y valida el tipo de instancia |
| 2 | `infra/aws/2-buckets.sh` | buckets privados (sitio, operación y respaldos con Object Lock), cifrado, solo HTTPS |
| 3 | `infra/aws/3-certificado.sh` | captura los 2 CNAME `_…` en Namecheap; re-correr hasta `ISSUED` |
| 4 | `infra/aws/4-cloudfront.sh` | captura el ALIAS `@` y el CNAME `www` |
| 5 | `infra/aws/5-parametros.sh` y `infra/aws/5-parametros.sh smtp ARCHIVO` | config + secretos generados; SMTP desde un archivo `KEY=VALOR` (bórralo después) |
| 6 | `infra/aws/6-servidor.sh` | captura los A `api` y `admin`; el bootstrap tarda ~5 min. Crea también el documento de SSM `ElPuesto-Activar` |
| 7 | `scripts/desplegar-backend.sh` | primer backend; Caddy saca los certificados solo |
| 8 | `scripts/prod.sh python3 scripts/cargar-datos.py --dry-run` y luego sin `--dry-run` | datos reales desde `data/` (incluye `data/privado/`) |
| 9 | `scripts/publicar-sitio.sh` y `scripts/publicar-app.sh` | landing + primera versión en `/descargas/` |
| 10 | ver **Respaldos** | correr un respaldo a mano y probar restaurarlo una vez |
| 11 | `infra/aws/7-github.sh` (o `REVISOR=si infra/aws/7-github.sh`) | entorno `produccion` en GitHub + OIDC + rol + variable del repo |
| 12 | `infra/aws/8-presupuesto.sh CORREO [LIMITE_USD=30]` | presupuesto mensual (AWS Budgets, gratis). **En la cuenta actual NO**: ya se configuró a mano |
| 13 | `infra/aws/5-parametros.sh alertas CORREO` y `scripts/desplegar-servidor.sh` | a quién le llegan las alertas de seguridad del backend (`ALERT_EMAIL`) |
| 14 | `infra/aws/9-auditoria.sh` (con un perfil que NO sea root) | CloudTrail + avisos de uso de root y de inicios de sesión fallidos; confirma los 2 correos de SNS |
| 15 | `infra/aws/5-parametros.sh opcion ADMIN_HOST admin.elpuesto.app` y `scripts/desplegar-servidor.sh` | cuando `https://admin.elpuesto.app/health` ya responda: `/admin/*` solo en ese host |

### Registros en Namecheap (Domain List → elpuesto.app → Advanced DNS)

| Tipo | Host | Valor | Lo da |
|---|---|---|---|
| CNAME | `_xxxx` | `_yyyy.acm-validations.aws.` | paso 3 (uno por `@` y otro por `www`; **no borrarlos**: ACM renueva con ellos) |
| ALIAS | `@` | `dxxxx.cloudfront.net` | paso 4 (antes, borrar el *URL Redirect* de estacionamiento) |
| CNAME | `www` | `dxxxx.cloudfront.net` | paso 4 (reemplaza el CNAME a `parkingpage.namecheap.com`) |
| A | `api` | IP elástica (la que imprime el paso 6) | paso 6 |
| A | `admin` | la misma IP elástica | paso 6 (Caddy saca el certificado de `admin` en cuanto resuelve; mientras, lo reintenta sin afectar a `api`) |
| A | `app` | la misma IP elástica | app web (igual que `admin`: el certificado sale solo en cuanto resuelve) |
| TXT/CNAME | según el proveedor | SPF/DKIM del correo | el proveedor SMTP |

Correo: **Mailgun** (`smtp.mailgun.org`, remitente `noreply@elpuesto.app`) con SPF
`v=spf1 include:mailgun.org ~all`, DKIM `pdk1`/`pdk2._domainkey` (CNAME a Mailgun), MX de
Mailgun y CNAME `email` de tracking. Un dominio solo puede tener UN SPF: cualquier otro
proveedor se agrega como `include:` en ese mismo TXT. DMARC (2026-10-04): TXT con Host `_dmarc`
(en Namecheap el Host va SIN el dominio: `_dmarc.elpuesto.app` crea `_dmarc.elpuesto.app.elpuesto.app`),
`v=DMARC1; p=none;` con reportes `rua`/`ruf` a Mailgun y OnDMARC (el que propone Mailgun).

## Operación

### Qué puede CI y qué no
El modelo: **quien hace push a `master` puede cambiar el código del backend y la landing**
(eso es desplegar), pero CI no puede tocar lo que corre como root, la app que instala la gente
ni los respaldos.

| | GitHub Actions (rol `elpuesto-github-deploy`) | Operador (tu perfil de AWS) |
|---|---|---|
| Compilar el backend | job `construir`, **sin credenciales** (solo lee el repo) | `desplegar-backend.sh` |
| Subir el paquete a `despliegues/` y activarlo | sí, **solo** con el documento de SSM `ElPuesto-Activar` (parámetro = versión con patrón estricto → `activar.sh`) | sí |
| Comandos arbitrarios como root (`AWS-RunShellScript`) | **no** | `scripts/servidor.sh`, `desplegar-servidor.sh`, `--anterior` |
| `infra/servidor/` (Caddy, compose, systemd, scripts de root) | **no** (Deny en `servidor/*`) | `scripts/desplegar-servidor.sh` |
| Landing (`index.html`, `404.html`, imágenes) | sí | `publicar-sitio.sh` |
| `/descargas/` (página con la huella del APK), APKs, `version.json`, `.well-known/` | **no** (Deny) | `publicar-app.sh`, `publicar-descargas.sh` |
| Respaldos | **no** (Deny) | `restaurar-respaldo.sh` (leer); nadie puede borrarlos antes de caducar |
| Parameter Store (secretos) | **no** | `5-parametros.sh`, `prod.sh` |

- El rol solo lo asume un job del **entorno `produccion`** de `cultome/el_puesto` (el `sub` del
  token OIDC es `repo:cultome@<id>/el_puesto@<id>:environment:produccion`, con los IDs inmutables),
  y el entorno solo acepta la rama **`master`**. Con `REVISOR=si infra/aws/7-github.sh` además
  exige tu aprobación en cada despliegue — en un repo **privado** eso (y los propios entornos)
  requiere GitHub Pro/Team; en uno público es gratis. Sin plan que lo permita, 7-github.sh se
  detiene sin tocar el rol y lo dice.
- Todas las acciones de terceros van **fijadas por SHA de commit** (con la versión en un
  comentario); el wrapper de Gradle verifica la suma SHA-256 de la distribución y
  `setup-gradle` valida el `gradle-wrapper.jar`. Para actualizar una acción: resolver el SHA del
  tag nuevo (`gh api repos/<dueño>/<acción>/commits/<tag> --jq .sha`) y cambiar ambos.
- Riesgo que queda (y es inherente a desplegar automáticamente): un push malicioso a `master`
  puede desplegar código de backend, que corre como `elpuesto` con los secretos de la app
  (base, JWT, SMTP). No obtiene root, ni las credenciales de la instancia (IMDS bloqueado), ni
  acceso a respaldos o Parameter Store. La aprobación del entorno (si el plan la permite) es la
  mitigación.

### Despliegue automático (GitHub Actions)
Un **push a `master`** despliega solo lo que cambió (pestaña *Actions* del repo):

| Workflow | Se dispara con cambios en | Hace |
|---|---|---|
| `.github/workflows/desplegar-backend.yml` | `backend/`, `shared/`, Gradle, `infra/aws/comun.sh`, `scripts/desplegar-backend.sh` | `construir` (sin credenciales): `desplegar-backend.sh --construir` → artefacto; `desplegar` (entorno `produccion`): `desplegar-backend.sh --activar` |
| `.github/workflows/publicar-sitio.yml` | `web/`, `scripts/publicar-sitio.sh` | `publicar-sitio.sh <commit>` (solo la landing: nunca `descargas/`) |

Ambos se pueden correr a mano (*Run workflow*; el entorno solo acepta `master`). Credenciales
por **OIDC** (`infra/aws/7-github.sh`, variable del repo `AWS_ROLE_ARN`; ninguna clave guardada
en GitHub). **`infra/servidor/` ya no se despliega por CI**: tras cambiarlo, corre
`scripts/desplegar-servidor.sh` (`scripts/desplegar-servidor.sh --revisar` dice si el servidor
ya tiene la de HEAD; `nueva-version.sh` también lo avisa). Los commits locales no despliegan
nada hasta el push.

### Backend
```sh
scripts/desplegar-backend.sh            # HEAD (o un ref: tag, sha): construye y activa
scripts/desplegar-backend.sh --anterior # rollback a la versión previa
```
Cada versión queda en `/opt/el-puesto/releases/<AAAAMMDD-HHMM>-<sha10>` y `current` apunta a la
activa (se conservan 5); trae la app web en `web/` (`--construir` compila también
`:webApp:wasmJsBrowserDistribution`, sin mapas de fuentes) y `generar-env.sh` fija
`WEB_APP_DIR=/opt/el-puesto/current/web`. Si la nueva no contesta `/health` en 90 s, `activar.sh` regresa sola a
la anterior. `activar.sh` rechaza versiones con otro formato y paquetes con enlaces o rutas fuera
de `backend/`, y desempaca sin conservar dueño ni permisos. Las migraciones de esquema corren
al arrancar (como en dev): un rollback de código NO deshace columnas nuevas, así que los
cambios de esquema deben seguir siendo aditivos.

### Servidor (lo que corre como root)
```sh
scripts/desplegar-servidor.sh           # infra/servidor/ de HEAD (o un ref) + Parameter Store → configurar.sh
scripts/desplegar-servidor.sh --revisar # ¿el servidor ya tiene infra/servidor/ de HEAD?
```
Sube `infra/servidor/` a `s3://elpuesto-app-ops/servidor/` y, por SSM, la instancia lo baja y
corre `configurar.sh`: env desde Parameter Store (reinicia el backend si cambió), unidades de
systemd, firewall del IMDS, docker compose fijado y verificado, Postgres + Caddy, timer del
respaldo. Cambiar la imagen de Postgres o de Caddy recrea su contenedor (segundos sin API).
`scripts/desplegar-backend.sh --solo-config` es el mismo comando (alias).

**IMDS**: el backend no usa AWS, así que ni el usuario `elpuesto` (tabla `inet elpuesto_imds`
de nftables, cargada por `el-puesto-imds.service`) ni el servicio `el-puesto`
(`IPAddressDeny`) llegan a `169.254.169.254`: un fallo en el backend no da las credenciales del
rol de la instancia. `configurar.sh` lo comprueba ("IMDS bloqueado para el usuario elpuesto").

### App y sitio
**Versión nueva de la app, todo en uno: `scripts/nueva-version.sh [patch|minor|major|X.Y.Z]`**
(`--prueba` para ver sin tocar nada). Revisa rama/GitHub/llave/sesiones, avisa si el servidor no
tiene `infra/servidor/` de HEAD, calcula la versión y el versionCode, arma el borrador del
CHANGELOG con los commits desde el último tag y lo abre en tu editor, commitea
`release: El Puesto X.Y.Z`, hace push, **espera** el despliegue del backend si el push lo
dispara (los dos jobs; si el entorno pide aprobación, apruébala en Actions; si falla, la app no
se publica), publica el APK y sube el tag.

Por dentro son estos pasos (también sirven a mano):
1. Subir `versionName` y `versionCode` en `androidApp/build.gradle.kts`, sección nueva en
   `CHANGELOG.md`, commit.
2. `scripts/publicar-app.sh` → APK de release firmado, subido **inmutable** como
   `descargas/el-puesto-<versión>.apk`, `version.json` actualizado, tag `v<versión>` local,
   `/descargas/` republicada (`publicar-descargas.sh`: enlace y huella nuevos) y landing.

Los APK anteriores se conservan en S3 (su URL sigue viva). Solo la landing:
`scripts/publicar-sitio.sh` (o push de `web/`). **Solo los textos de `/descargas/`** (p. ej. la
lista de permisos): `scripts/publicar-descargas.sh` — CI no la publica aunque cambie
`web/descargas/index.html`. El bucket del sitio tiene versionado (90 días): una sobrescritura
mala se deshace copiando la versión anterior
(`aws s3api list-object-versions --bucket elpuesto-app-web --prefix <clave>` y
`aws s3api copy-object --copy-source 'elpuesto-app-web/<clave>?versionId=<id>' …`).

**Vista previa al compartir** (WhatsApp, Telegram, X, Slack…): la dan las etiquetas Open
Graph/Twitter de `web/index.html` y `web/descargas/index.html` con la imagen `web/og.png`
(1200×630, <300 KB). La imagen se genera con `scripts/generar-og.sh` (plantilla
`scripts/og/tarjeta.html` + trazado y logo tomados de la landing; Chromium de Playwright) y se
commitea. El JSON-LD (schema.org: `WebSite` + `MobileApplication`) es para buscadores; su
`softwareVersion` lo rellenan `publicar-sitio.sh` y `publicar-descargas.sh`. Tras cambiar la
imagen, las apps que ya guardaron el preview tardan en refrescarlo (Facebook/WhatsApp:
*Sharing Debugger* de Meta).

`/version.json` (1 min de caché) es la fuente de "cuál es la última versión":
`{versionName, versionCode, apk, sha256, bytes, publicada, commit, novedades}` —
`novedades` = las viñetas «Para los oficiales» de las últimas 5 versiones del CHANGELOG
(`scripts/novedades.py`). **La app lo lee** (`AppUpdates`, compilada con
`-PupdatesUrl=https://elpuesto.app/version.json`, que pasa `publicar-app.sh`): a lo mucho cada
6 h avisa en el Inicio y en Configuración → Actualizaciones, descarga el APK, lo verifica
(tamaño, SHA-256, paquete, versión y firma) y abre el instalador de Android, que pide
confirmar con un toque (el primer uso pide además "Instalar apps desconocidas" para El
Puesto). Solo acepta un APK del mismo host que el `version.json`. Publicar una versión
= los teléfonos se enteran solos; no hay que avisar por otro lado.

**Base local del teléfono**: se actualiza encima, así que cambiar
`androidApp/src/main/sqldelight/.../ElPuesto.sq` exige su migración `N.sqm` (el build falla
si falta; guía en `androidApp/src/main/sqldelight/README.md`).

### Llave de release
`~/.config/el-puesto/release.jks` + `release.properties` (contraseña), **fuera del repo**.
Huella SHA-256 del certificado: `2f6837d5…ac54` (completa en `infra/aws/comun.sh`;
`publicar-app.sh` rechaza un APK firmado con otra). **Respaldar ambos archivos** en el gestor
de contraseñas: sin ellos no se pueden publicar actualizaciones (Android no deja actualizar
una app con otra firma; los teléfonos tendrían que desinstalar). Los teléfonos con la v1.0.0
(firmada con la llave de debug) desinstalan una vez.

### Datos y admin
- Admin web: **`https://admin.elpuesto.app/admin/ui/`** (`ADMIN_HOST` activo desde 2026-09-26:
  `api.elpuesto.app` ya no sirve `/admin/*`) con la clave de
  `aws ssm get-parameter --name /elpuesto/prod/ADMIN_API_KEY --with-decryption --region mx-central-1`
  (o, mejor, una clave nombrada creada desde ahí). La clave se guarda por origen: al cambiar de
  host hay que volver a pegarla.
- Cargador contra producción: `scripts/prod.sh python3 scripts/cargar-datos.py …` (la clave se
  lee de Parameter Store en el momento; no se guarda en disco). `prod.sh` exporta
  `EL_PUESTO_API=https://admin.elpuesto.app` y, si ese host aún no responde, usa
  `https://api.elpuesto.app` con un aviso; forzar uno: `ADMIN_API_BASE=https://… scripts/prod.sh …`.
  OJO: el cargador completo REEMPLAZA asignaciones de eventos con roster.
- El MCP (`.mcp.json` → `adminMcp/run.sh`) toma `EL_PUESTO_API` y `EL_PUESTO_ADMIN_KEY` de
  `./setenv` y apunta a dev. Para usarlo contra producción: clave nombrada aparte y, **tras
  activar `ADMIN_HOST`, `EL_PUESTO_API=https://admin.elpuesto.app`** (en `api` el backend ya
  responde 404 en `/admin/*`).
- `ADMIN_HOST`: `infra/aws/5-parametros.sh opcion ADMIN_HOST admin.elpuesto.app` +
  `scripts/desplegar-servidor.sh` (para volver atrás: `opcion ADMIN_HOST --quitar`). Ponerlo
  solo cuando `curl https://admin.elpuesto.app/health` ya conteste y el backend desplegado ya
  entienda `ADMIN_HOST`.

### Logs y diagnóstico
El backend escribe una línea por petición (logger `Peticiones`: método, ruta SIN query,
status, latencia y quién — `oficial:<id>` con sesión, `ip:<ip>` sin ella) y las alertas en
el logger `Seguridad`. journald guarda 500 MB / **30 días** como máximo (`configurar.sh`).
Caddy NO registra `X-Admin-Key` ni, de la query, `token` (enlace del correo), `t` (stream del
admin), `q` (búsqueda de oficiales) ni `text` (pie de foto): los reemplaza por `REDACTADO`.
```sh
scripts/servidor.sh 'journalctl -u el-puesto -n 100 --no-pager'
scripts/servidor.sh 'journalctl -u el-puesto --since -1h | grep -E "ALERTA|límite"'   # abusos
scripts/servidor.sh 'journalctl -u el-puesto --since -1h | grep Peticiones | grep oficial:<id>'
scripts/servidor.sh 'docker logs --tail 50 el-puesto-caddy'
scripts/servidor.sh 'systemctl status el-puesto --no-pager; df -h /; free -m'
scripts/servidor.sh 'nft list table inet elpuesto_imds'                                 # firewall del IMDS
aws ssm start-session --target <instance-id> --region mx-central-1   # consola (session-manager-plugin)
```

### Respaldos
- Diario a las **03:30 hora de CDMX** (`el-puesto-respaldo.timer`; si la instancia estaba
  apagada, corre al arrancar): `pg_dump --format=custom`, validado con `pg_restore --list`, a
  `s3://elpuesto-app-respaldos/diarios/` (35 días) y, el día 1, también a `mensuales/`
  (400 días, ~13 meses). Las imágenes viven en Postgres: el dump es TODO.
- **Inmutables**: el bucket tiene Object Lock y cada respaldo se sube con retención
  **COMPLIANCE** hasta su fecha de caducidad: nadie (ni la instancia, ni CI, ni root de la
  cuenta) puede borrarlo, sobrescribirlo ni acortar su retención. La instancia **solo escribe**
  (ni lee ni borra). Retención por defecto del bucket: 35 días (si algo se sube sin retención).
  Ojo: por lo mismo, un archivo subido por error ahí se queda (y se cobra) hasta que caduque.
- Cifrado en reposo SSE-S3 (AES-256, llaves de AWS). Opción: SSE-KMS con una llave propia
  (~US$1/mes + peticiones) si se quiere separar quién puede descifrar; hoy no aporta mucho
  porque el acceso ya está acotado por IAM y Object Lock.
- Los respaldos anteriores a este esquema siguen en `s3://elpuesto-app-ops/respaldos/` hasta
  caducar solos (35 días / ~13 meses); se restauran con su ruta `s3://` completa.
- A mano: `scripts/servidor.sh 'systemctl start el-puesto-respaldo && journalctl -u el-puesto-respaldo -n 5 --no-pager'`
- **Listar / restaurar** (desde tu máquina, con tus credenciales):
  ```sh
  scripts/restaurar-respaldo.sh                                        # lista
  scripts/restaurar-respaldo.sh --bajar diarios/<archivo> [local.dump] # a tu máquina
  CONFIRMAR=si scripts/restaurar-respaldo.sh diarios/<archivo>         # DESTRUCTIVO: producción
  ```
  Restaurar arma un enlace firmado de 15 minutos y se lo pasa por SSM a `restaurar.sh`, que lo
  descarga, lo valida, respalda lo actual (queda inmutable), detiene el backend, restaura y lo
  arranca. El enlace queda en el historial de comandos de SSM pero caduca solo.
- A una base local: `scripts/restaurar-respaldo.sh --bajar …` y
  `pg_restore -d <base> --no-owner <archivo>`.
- Comprobar la retención de uno:
  `aws s3api get-object-retention --bucket elpuesto-app-respaldos --key diarios/<archivo> --region mx-central-1`.

### Auditoría de la cuenta
`infra/aws/9-auditoria.sh [CORREO]` (default: `ALERT_EMAIL`): CloudTrail `elpuesto-auditoria`
(todas las regiones, eventos de administración, validación de integridad) a
`s3://elpuesto-app-auditoria/` (privado, solo HTTPS, 365 días) y avisos por correo
(EventBridge → SNS, en `us-east-1`, `mx-central-1` y `us-west-2`) cuando se usa la cuenta **root**,
falla un inicio de sesión en la consola o falla una contraseña o un código MFA en el portal de
IAM Identity Center (`us-west-2`, evento `CredentialVerification` = `Failure`). Se niega a correr como root (cada comando tuyo dispararía un
correo): primero crea un administrador en IAM Identity Center. Validar la integridad de los
registros: `aws cloudtrail validate-logs --trail-arn <arn> --start-time <fecha>`.

### Acceso de operador (IAM Identity Center)

La CLI y la consola se usan con un usuario de **IAM Identity Center**, no con root (desde el
2026-09-26 cada uso de root manda un correo de `9-auditoria.sh`). La instancia de Identity Center
de la cuenta vive en **`us-west-2`** (compartida con otros proyectos de la cuenta: no tocar sus
usuarios ni su permission set `PowerUserAccess`); el portal de acceso (`https://d-…awsapps.com/start`) lo muestra la consola de Identity Center.
- Tu usuario con el permission set **`AdministratorAccess`** (sesiones de 4 h) asignado solo
  a esta cuenta. La instancia **bloquea a quien no tiene MFA**: un usuario nuevo necesita que un
  administrador le registre el MFA en la consola (Users → usuario → MFA devices → Register) antes
  de poner su contraseña.
- `~/.aws/config`: `[sso-session elpuesto]` (start URL de arriba, `sso_region = us-west-2`) y
  `[profile elpuesto]` (`sso_role_name = AdministratorAccess`, `region = mx-central-1`). Iniciar
  sesión: `aws sso login --profile elpuesto` (dura 4 h; los scripts lo usan sin cambios).
- Root queda en `[profile elpuesto-root]` (`aws login`) SOLO para emergencias.

### Abuso y sesiones
- **Límites de uso por oficial** (`RateLimits` en `Abuse.kt`, 429 + `Retry-After`) y por IP en
  las rutas públicas de sesión. Holgados a propósito (la v1.1.0 de la app DESCARTA lo que
  recibe 429; la 1.1.1 lo reintenta). `RATE_LIMIT_SCALE` en Parameter Store los multiplica
  (0 = apagados) sin redesplegar: `infra/aws/5-parametros.sh opcion RATE_LIMIT_SCALE <n>` y
  `scripts/desplegar-servidor.sh` (reinicia el backend). Subirlos o bajarlos es seguro; los
  contadores viven en memoria (un reinicio los vacía).
- **Alertas** (`ALERT_EMAIL`): picos de 429/401/403/5xx en 5 min, reuso de un refresh token,
  congelamientos y **fuerza bruta contra la clave de admin** (una IP que falla más de 10 veces en
  15 min queda bloqueada 15 min y avisa). Un error suelto no manda correo. Como mucho un correo
  por hora por tipo.
- **Suspender** una cuenta (admin web → Cuentas, o `guardar_cuenta` en el MCP) corta su API
  al momento. **"Cerrar sesiones"** (acción de la fila, `cerrar_sesiones_cuenta`) la saca de
  todos sus teléfonos sin suspenderla (teléfono perdido); al abrir la app, lo guardado en
  el teléfono se borra.
- **Emergencia** (se filtró `JWT_SECRET`…): admin web → Claves y auditoría → "Cerrar todas
  las sesiones" (o `POST /admin/sessions/revoke-all`). Todos vuelven a entrar con su
  correo. Después rota el secreto filtrado. (Los tokens se guardan hasheados desde
  2026-09-26: una copia de la base o de un respaldo ya no sirve para entrar.)
- **Durante un abuso, sin redesplegar** (admin web → **Seguridad**, scope `keys`):
  - **Pausar una función** para todos (enlaces de acceso, subidas, mensajes, chats,
    invitaciones, ubicación, registros por honor, exportaciones): la app recibe "pausado"
    con tu motivo y reintenta después sin perder su cola. Reanudar es el mismo botón.
  - **Congelados**: quien choca 60 veces con los límites en 10 min queda 30 min sin servicio
    (llega alerta a `ALERT_EMAIL`); "descongelar" lo libera antes.
  - **Uso (24 h)**: quién hace más peticiones y quién recibe más 429. Los tres viven en
    memoria (las pausas sí se guardan en la base) y se reinician con el backend.
- **Cupos por oficial**: 1 GB de fotos (`STORAGE_QUOTA_MB` en Parameter Store), 2 000 entradas
  de bitácora y 30 chats abiertos; al llegar, la app muestra el motivo (409).

### Secretos y ajustes
- Cambiar un valor: `infra/aws/5-parametros.sh` no pisa secretos existentes; para rotar,
  `aws ssm put-parameter --overwrite …` y `scripts/desplegar-servidor.sh`.
- Ajustes opcionales del backend (`5-parametros.sh opcion CLAVE VALOR|--quitar`, luego
  `desplegar-servidor.sh`): `ADMIN_HOST` (ver **Datos y admin**), `AUTH_REQUIRE_PKCE`
  (`false` hasta que casi todos los teléfonos tengan la app que manda PKCE; entonces `true`),
  `STORAGE_QUOTA_MB` (cuota de imágenes por oficial, 1024), `RATE_LIMIT_SCALE`,
  `STANDINGS_INGEST_EVERY_MIN`. Sin el parámetro, el backend usa su default.
- App web de los oficiales: `APP_HOST` (`5-parametros.sh opcion APP_HOST app.elpuesto.app`):
  la app web solo en ese host, su raíz lleva a `/app/`, en otros hosts `/app` redirige y los
  enlaces de acceso que pide la web apuntan ahí (`WEB_APP_URL` los forzaría a otra dirección).
  `WEB_APP_DIR` lo pone `generar-env.sh` (`/opt/el-puesto/current/web`); sin carpeta, `/app`
  da 404 sin afectar lo demás. El host del admin nunca la sirve.
- Rotar `JWT_SECRET` cierra la sesión de TODOS (tendrán que pedir otro enlace).
- `DB_PASSWORD` solo aplica al crear el volumen de Postgres: rotarlo exige además
  `ALTER USER el_puesto PASSWORD '…'` dentro del contenedor.

### Versiones fijadas (cadena de suministro)
| Qué | Dónde | Cómo actualizar |
|---|---|---|
| Acciones de GitHub | `.github/workflows/*.yml` (SHA + `# vX.Y.Z`) | `gh api repos/<dueño>/<acción>/commits/<tag> --jq .sha` |
| Distribución de Gradle | `gradle/wrapper/gradle-wrapper.properties` (`distributionSha256Sum`) | la suma de `https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256` |
| Postgres 16.x y Caddy 2.x | `infra/servidor/docker-compose.yml` (etiqueta + digest) | `curl -s https://hub.docker.com/v2/repositories/library/<imagen>/tags/<etiqueta> \| jq -r .digest`; aplicar con `desplegar-servidor.sh`. Postgres: nunca cambiar de mayor sin migrar |
| docker compose | `infra/servidor/configurar.sh` (versión + SHA-256 por arquitectura) | el `.sha256` de la release en GitHub |

### Costo aproximado (mensual)
EC2 t4g.small ~US$13–15 · disco gp3 30 GB ~US$3 · IPv4 pública ~US$3.6 · S3 + CloudFront
centavos a pocos dólares (el versionado del sitio y los respaldos inmutables suman centavos) ·
CloudTrail/EventBridge/SNS ~0 · Parameter Store estándar gratis. Total ~US$20–25. El
presupuesto (`8-presupuesto.sh`, o el configurado a mano) avisa si algo se dispara.

## Publicar la app web (app.elpuesto.app) por primera vez

En orden (cada paso se puede verificar antes del siguiente):

1. **DNS** (Namecheap): `A` `app` → la IP elástica. Verificar por DoH (el resolvedor local
   guarda NXDOMAIN): `curl -H 'accept: application/dns-json' 'https://dns.nextdns.io/dns-query?name=app.elpuesto.app'`.
2. **Parámetro**: `infra/aws/5-parametros.sh opcion APP_HOST app.elpuesto.app`.
3. **Servidor**: `scripts/desplegar-servidor.sh` (Caddy con el bloque de `app`, env regenerado
   con `APP_HOST` y `WEB_APP_DIR`). Caddy saca el certificado en cuanto resuelve:
   `curl https://app.elpuesto.app/health`. Hasta el siguiente paso `/app` da 404 (el backend
   activo aún no trae la web).
4. **Código**: push a `master` (CI construye backend + web y activa; si `/health` no responde,
   regresa sola a la versión anterior). Verificar: `https://app.elpuesto.app` → `/app/`,
   acceso con enlace real, recarga (la sesión sigue), chat en vivo, foto, exportación,
   `https://api.elpuesto.app/app/` → redirige, admin sin cambios y la app Android instalada.
5. **Landing**: sale sola con el mismo push (`publicar-sitio.yml`); `/descargas/` se publica con
   `scripts/publicar-descargas.sh` (o al publicar la siguiente versión de la app).

Regreso: `scripts/desplegar-backend.sh --anterior` (backend y web juntos); quitar
`APP_HOST` (`5-parametros.sh opcion APP_HOST --quitar` + `desplegar-servidor.sh`) deja la web en
cualquier host como antes.

## Aplicar el endurecimiento de 2026-09-26 a la cuenta existente

> **Aplicado en producción el 2026-09-26**: pasos 1–10 (backend `20260927-0123-9c1e3270b5`) y 12
> (usuario propio en IAM Identity Center; CloudTrail y avisos a `ALERT_EMAIL`). Falta el 11
> (PKCE obligatorio, cuando casi todos tengan la 1.1.2, publicada ese mismo día). Se deja la guía
> como referencia para montar otra cuenta o repetirlo.

Producción se montó con la versión anterior de estos scripts (CI con `AWS-RunShellScript`,
respaldos en `elpuesto-app-ops`, sin host de admin…). Para dejarla como describe esta guía, en
este orden y **en una sola sesión** (entre los pasos 4 y 5 el respaldo nocturno fallaría, y
entre el 7 y el 8 CI no podría desplegar). Todo con tu perfil de administrador; nada de esto
borra datos.

1. **Integrar sin push**: estos cambios en `master` local, sin `git push` todavía (el push es
   el paso 8: con el workflow nuevo y la confianza vieja, o al revés, CI falla). Si el backend
   de la misma rama trae cambios, revisa que compile.
2. **DNS** (Namecheap → Advanced DNS): `A` · Host `admin` · Value = la IP elástica. Inofensivo antes
   de tiempo: hasta el paso 5 Caddy no conoce ese host.
3. **`infra/aws/2-buckets.sh`** — crea `elpuesto-app-respaldos` con Object Lock (COMPLIANCE, 35
   días por defecto; **no se puede apagar ni acortar**), activa el versionado del sitio y pone
   SSE-S3 explícito + política "solo HTTPS" en los 3 buckets (en el del sitio conserva la
   sentencia de CloudFront). No toca objetos.
4. **`infra/aws/5-parametros.sh`** — reescribe la config de siempre y crea
   `AUTH_REQUIRE_PKCE=false` y `STORAGE_QUOTA_MB=1024` si no existen. Aún no se aplica.
5. **`infra/aws/6-servidor.sh`** y enseguida **`scripts/desplegar-servidor.sh`**:
   - 6-servidor: sube `infra/servidor/` de HEAD a S3, crea el documento de SSM
     `ElPuesto-Activar`, cambia el rol de la instancia (respaldos: solo escribir en el bucket
     nuevo; ya no lee ni escribe `ops/respaldos/`; `ListBucket` solo en `servidor/`). No
     recrea la instancia (la IP elástica se re-asocia a la misma).
   - desplegar-servidor: la instancia aplica `activar.sh` endurecido, respaldos al bucket nuevo,
     firewall del IMDS (debe decir "IMDS bloqueado para el usuario elpuesto"), docker compose
     v5.5.1 verificado, **Postgres 16.15 y Caddy 2.11.4 fijados (recrea ambos contenedores:
     ~10–30 s sin API)**, Caddy con el sitio `admin`, encabezados y logs redactados, y el env
     con los ajustes nuevos (reinicia el backend si cambió). El `IPAddressDeny` de
     `el-puesto.service` entra en el siguiente reinicio del backend (el paso 8 lo hace).
6. **Probar los respaldos**:
   `scripts/servidor.sh 'systemctl start el-puesto-respaldo && journalctl -u el-puesto-respaldo -n 5 --no-pager'`,
   luego `scripts/restaurar-respaldo.sh` (debe aparecer en `diarios/`) y
   `aws s3api get-object-retention --bucket elpuesto-app-respaldos --key diarios/<archivo> --region mx-central-1`
   (`Mode: COMPLIANCE`). Opcional: `scripts/restaurar-respaldo.sh --bajar diarios/<archivo>` y
   restaurarlo en una base local.
7. **`infra/aws/7-github.sh`** (o `REVISOR=si infra/aws/7-github.sh` si tu plan de GitHub lo
   permite) — crea el entorno `produccion` (solo `master`), cambia la confianza OIDC a SOLO ese
   entorno y la política del rol (sin `AWS-RunShellScript`, sin `servidor/`, sin `descargas/`
   ni `version.json`, sin respaldos). Si GitHub no deja crear el entorno (repo privado en plan
   Free), se detiene sin tocar el rol: publica el repo (ya estaba pendiente) o sube de plan, y
   mientras despliega a mano. Desde aquí, el workflow VIEJO ya no puede asumir el rol.
8. **`git push`** a `master` — corre *Desplegar backend* (construir → desplegar; aprueba si lo
   pide) y *Publicar sitio*. Verifica `curl https://api.elpuesto.app/health` y que el sitio
   siga bien. `/descargas/` no cambia (ya estaba publicada; desde ahora solo la toca
   `publicar-descargas.sh`).
9. **Comprobar lo que CI ya no puede** (esperado: `explicitDeny` o `implicitDeny`):
   ```sh
   aws iam simulate-principal-policy --policy-source-arn "$(aws iam get-role --role-name elpuesto-github-deploy --query Role.Arn --output text)" \
     --action-names s3:PutObject --resource-arns arn:aws:s3:::elpuesto-app-ops/servidor/x \
     arn:aws:s3:::elpuesto-app-web/descargas/index.html arn:aws:s3:::elpuesto-app-web/version.json \
     --query 'EvaluationResults[].[EvalResourceName,EvalDecision]' --output table
   aws iam simulate-principal-policy --policy-source-arn "$(aws iam get-role --role-name elpuesto-github-deploy --query Role.Arn --output text)" \
     --action-names ssm:SendCommand --resource-arns arn:aws:ssm:mx-central-1::document/AWS-RunShellScript \
     --query 'EvaluationResults[].[EvalResourceName,EvalDecision]' --output table
   ```
10. **Host de admin**: cuando `curl https://admin.elpuesto.app/health` conteste (certificado de
    Caddy listo) **y** el backend desplegado ya entienda `ADMIN_HOST`:
    `infra/aws/5-parametros.sh opcion ADMIN_HOST admin.elpuesto.app` y
    `scripts/desplegar-servidor.sh`. Desde entonces: admin web en
    `https://admin.elpuesto.app/admin/ui/` (pega otra vez la clave), `prod.sh` usa ese host solo,
    y el MCP contra producción necesita `EL_PUESTO_API=https://admin.elpuesto.app`. Volver
    atrás: `opcion ADMIN_HOST --quitar` + `desplegar-servidor.sh`.
11. **PKCE obligatorio**, más adelante (cuando casi todos los teléfonos tengan la app nueva):
    `infra/aws/5-parametros.sh opcion AUTH_REQUIRE_PKCE true` + `scripts/desplegar-servidor.sh`.
12. **Auditoría**: crea un administrador en IAM Identity Center (pendiente desde el montaje),
    `aws configure sso` para un perfil nuevo y, con él,
    `AWS_PROFILE=<perfil> infra/aws/9-auditoria.sh` (usa `ALERT_EMAIL`; confirma los 3 correos
    de suscripción de SNS, uno por región). A partir de ahí, usa ese perfil y deja root solo para emergencias:
    cada uso de root manda un correo.

`4-cloudfront.sh` no hace falta re-correrlo (2-buckets.sh conserva su sentencia; si lo corres,
ya escribe la política junto con la "solo HTTPS"). `8-presupuesto.sh` NO se corre en esta
cuenta (el presupuesto ya existe, configurado a mano).
