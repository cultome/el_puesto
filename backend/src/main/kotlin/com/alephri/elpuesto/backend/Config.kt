package com.alephri.elpuesto.backend

/**
 * Configuración desde variables de entorno con defaults de DEV. En producción se
 * inyectan por env (el secreto JWT y las credenciales de DB NO deben quedar en código).
 */
object Config {
    private fun env(key: String, default: String): String = System.getenv(key) ?: default

    /** Puerto HTTP (8080 por defecto; cambiarlo si otro proyecto local lo ocupa). */
    val port = env("PORT", "8080").toInt()

    /**
     * Interfaz donde escucha. Detrás del túnel (ngrok) basta loopback: `HOST=127.0.0.1`
     * deja fuera a la red local (el emulador llega por 10.0.2.2 = loopback del host).
     */
    val host = env("HOST", "0.0.0.0")

    /** Orígenes con CORS (coma; "https://x.com"). Vacío = sin CORS (el admin web es mismo origen). */
    val corsOrigins: List<String> = env("CORS_ORIGINS", "").split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    val dbUrl = env("DB_URL", "jdbc:postgresql://localhost:55433/el_puesto")
    val dbUser = env("DB_USER", "el_puesto")
    val dbPassword = env("DB_PASSWORD", "el_puesto_dev")

    val jwtSecret = env("JWT_SECRET", "dev-secret-el-puesto-change-me")
    val accessTtlMinutes = env("ACCESS_TTL_MIN", "15").toLong()
    val refreshTtlDays = env("REFRESH_TTL_DAYS", "60").toLong()

    // Correo (magic link). Si SMTP_HOST está vacío, no se envía correo (modo dev: devLink).
    val smtpHost: String? = System.getenv("SMTP_HOST")?.takeIf { it.isNotBlank() }
    val smtpPort = env("SMTP_PORT", "587").toInt()
    val smtpUser = env("SMTP_USER", "")
    val smtpPassword = env("SMTP_PASSWORD", "")
    val smtpFrom = env("SMTP_FROM", "no-reply@elpuesto.example")
    val emailEnabled: Boolean get() = smtpHost != null

    /**
     * Base pública del backend como la ve el navegador del usuario (p. ej. el túnel
     * ngrok). Si está definida, el botón del correo apunta a la página puente
     * `GET /auth/open?token=…` (los clientes de correo tipo Gmail QUITAN los links con
     * esquema propio como elpuesto://; un https intermedio siempre es clickeable).
     * Sin definir, el correo enlaza el esquema directo.
     */
    val publicBaseUrl: String? = System.getenv("PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    // Auth de admin: clave estática para el endpoint de aprobación (la usa el admin web).
    val adminApiKey = env("ADMIN_API_KEY", "dev-admin-key-change-me")

    // Carpeta con los APKs armados (`dist/` del repo): /descargas sirve el más reciente
    // para instalar en teléfonos sin modo desarrollador. Relativa al directorio de arranque.
    val downloadsDir = env("DOWNLOADS_DIR", "dist")

    /**
     * Página pública de descargas (producción: https://elpuesto.app/descargas/, servida por
     * CloudFront desde S3). Si está definida, `/descargas` del backend redirige ahí en vez de
     * servir [downloadsDir] — quedan vivos los enlaces viejos que apuntaban al API.
     */
    val downloadsUrl: String? = System.getenv("DOWNLOADS_URL")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Ingesta automática de posiciones: cada cuántos minutos revisa qué categorías tienen
     * una fecha recién terminada (0 = apagada). El job decide por categoría si toca pedir
     * a su fuente (reintentos con espera creciente; ver StandingsIngest).
     */
    val standingsEveryMin = env("STANDINGS_INGEST_EVERY_MIN", "15").toLong()

    /**
     * Correo que recibe las alertas de seguridad (picos de 429/401/403/5xx, reuso de un
     * refresh token). Vacío = solo quedan en el log. Ver SecurityMonitor.
     */
    val alertEmail: String? = System.getenv("ALERT_EMAIL")?.trim()?.takeIf { it.isNotBlank() }

    /** Multiplica los límites de uso por oficial (RateLimits): 1 = normal, 0 = apagados. */
    val rateLimitScale: Double = env("RATE_LIMIT_SCALE", "1").toDouble()

    /**
     * Host propio del admin (p. ej. `admin.elpuesto.app`). Definido: `/admin/…` SOLO se
     * sirve en ese host y ese host no sirve nada más (el admin web guarda su clave en el
     * navegador: ninguna otra página de nuestro dominio debe compartir su origen). Vacío =
     * transición: el admin se sirve en cualquier host.
     */
    val adminHost: String? = System.getenv("ADMIN_HOST")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * Host propio de la app web (p. ej. `app.elpuesto.app`). Definido: la app web `/app/` SOLO
     * se sirve ahí (en otros hosts `/app` redirige), la raíz `/` lleva a `/app/`, y el mismo host
     * atiende la API que la app usa (mismo origen: cookie de sesión y WebSocket sin CORS). Así
     * su almacenamiento del navegador no lo comparte ninguna otra página nuestra. Vacío = la
     * app web se sirve en cualquier host (desarrollo).
     */
    val appHost: String? = System.getenv("APP_HOST")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * Exigir que cada enlace mágico se canjee en el teléfono que lo pidió (PKCE). false =
     * transición: se aceptan también los enlaces que pidieron apps anteriores (sin reto).
     */
    val authRequirePkce: Boolean = env("AUTH_REQUIRE_PKCE", "false").toBoolean()

    /** Cupo de fotos por oficial (bitácora + chat), en MB. */
    val storageQuotaMb: Long = env("STORAGE_QUOTA_MB", "1024").toLong()

    /**
     * Carpeta con la app web compilada (Compose/Wasm: `index.html`, `.mjs`/`.js`, `.wasm`,
     * recursos). Definida: el backend la sirve en `/app/` (WebApp.kt). Vacía = sin app web
     * (`/app` da 404).
     */
    val webAppDir: String? = System.getenv("WEB_APP_DIR")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Dirección de la app web como la abre el navegador del oficial: el enlace mágico que
     * pide la web lleva `<WEB_APP_URL>#auth=<token>` (en el FRAGMENTO: el token no llega a
     * ningún servidor ni a los logs). Default: `https://${APP_HOST}/app/` o, sin host propio,
     * `${PUBLIC_BASE_URL}/app/`. null = sin URL pública (desarrollo: el devLink es relativo,
     * `/app/#auth=…`).
     */
    val webAppUrl: String? = (
        System.getenv("WEB_APP_URL")?.trim()?.takeIf { it.isNotBlank() }
            ?: appHost?.let { "https://$it/app/" }
            ?: publicBaseUrl?.let { "$it/app/" }
        )
        ?.also {
            require(it.startsWith("https://") || it.startsWith("http://")) { "WEB_APP_URL debe empezar con https:// (o http:// en local)" }
            require('#' !in it && '?' !in it) { "WEB_APP_URL no lleva # ni ?: el token va en el fragmento" }
        }
}
