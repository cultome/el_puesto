# El Puesto

App Android para **oficiales de pista** del automovilismo mexicano, hecha por oficiales para
oficiales: asignación en pista, compañeros de puesto, cronograma en vivo (MbM), chats, agenda
y planeación de viaje, catálogos de circuitos y campeonatos, convocatorias, logros y registro
por honor. Funciona sin señal en la pista y es privacy-first (no es una red social).

- Sitio y descarga: **https://elpuesto.app** · API: `https://api.elpuesto.app`
- Dirección visual: **"Paddock nocturno"** (oscuro, acento ámbar).

## Estructura

```
shared/       Modelo de dominio y contratos (Kotlin Multiplatform, protobuf)
androidApp/   App Android (Jetpack Compose, offline-first con SQLDelight + outbox)
backend/      API Ktor + Postgres, admin web (/admin/ui/) y API admin (/admin/*)
adminMcp/     Servidor MCP para que agentes administren los datos
data/         Datos reales versionados (CSV) + scripts/cargar-datos.py
web/          Sitio estático elpuesto.app
infra/        Producción en AWS (EC2 + CloudFront/S3)
```

## Empezar

```sh
docker compose up -d
./gradlew :backend:installDist && backend/build/install/backend/bin/backend
./gradlew :androidApp:assembleDebug
```

Requiere JDK 21, Android SDK 35 (`local.properties` con `sdk.dir`) y Docker.

## Documentación

- [`docs/COMPILAR-Y-DESPLEGAR.md`](docs/COMPILAR-Y-DESPLEGAR.md): compilar, correr en local,
  desplegar (push a `master` = despliegue) y publicar versiones de la app.
- [`docs/DESPLIEGUE.md`](docs/DESPLIEGUE.md): infraestructura de producción en AWS.
- [`CLAUDE.md`](CLAUDE.md): contexto del producto, decisiones y convenciones (memoria del proyecto).
- [`CHANGELOG.md`](CHANGELOG.md): versiones publicadas.
