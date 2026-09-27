package com.alephri.elpuesto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizaciones de la app, que no vive en Google Play. Revisa el `version.json` que publica
 * `scripts/publicar-app.sh`; si hay una versión nueva, el oficial la descarga desde la app, se
 * verifica (tamaño, SHA-256, paquete, versión y firma) y se le entrega al instalador de Android,
 * que pide confirmar con un toque. Nada se instala sin que el oficial lo acepte.
 *
 * Solo funciona en builds con `UPDATES_URL` (el de release lo trae; uno de debug no podría
 * instalarse encima de uno de release porque la firma es otra).
 */
object AppUpdates {

    /** Lo que publica `scripts/publicar-app.sh` en /version.json. */
    @Serializable
    data class Release(
        val versionName: String,
        val versionCode: Int,
        val apk: String,
        val sha256: String,
        val bytes: Long,
        val publicada: String? = null,
        /** Notas "Para los oficiales" del CHANGELOG de las últimas versiones, la más nueva primero. */
        val novedades: List<Notes> = emptyList(),
    )

    @Serializable
    data class Notes(val version: String, val notas: String)

    sealed interface State {
        /** No se sabe todavía (sin revisar, o la revisión falló sin haber nada guardado). */
        data object Unknown : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        /** [progress] de 0 a 1. */
        data class Downloading(val release: Release, val progress: Float) : State
        /** El APK ya está descargado y verificado: falta que el oficial confirme la instalación. */
        data class Ready(val release: Release) : State
        data class Failed(val release: Release, val message: String) : State
    }

    val enabled: Boolean get() = BuildConfig.UPDATES_URL.isNotBlank()

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _checking = MutableStateFlow(false)
    /** Hay una revisión en curso (la pantalla de actualizaciones muestra que está buscando). */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** La app está en primer plano: al terminar la descarga se abre la instalación sola. */
    @Volatile var foreground = false
    @Volatile private var installWhenReady = false

    private const val TAG = "ElPuestoUpdates"
    private const val PREFS = "el_puesto_updates"
    private const val KEY_RELEASE = "release"
    private const val KEY_LAST_CHECK = "lastCheckAt"
    private const val KEY_DISMISSED_CODE = "dismissedCode"
    private const val KEY_DISMISSED_AT = "dismissedAt"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val DISMISS_FOR_MS = 3 * 24 * 60 * 60 * 1000L
    private const val ACTION_INSTALL_RESULT = "com.alephri.elpuesto.UPDATE_INSTALL_RESULT"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    @Volatile private var loaded = false

    // ——— Revisar ———

    /** Al volver a la app: revisa si ya tocaba (a lo mucho cada 6 h). */
    fun onForeground(context: Context) {
        foreground = true
        if (enabled) scope.launch { check(context.applicationContext, force = false) }
    }

    fun onBackground() { foreground = false }

    /** Revisa /version.json. [force] ignora el freno de 6 h (la pantalla de actualizaciones). */
    suspend fun check(context: Context, force: Boolean): Unit = withContext(Dispatchers.IO) { checkNow(context, force) }

    private fun checkNow(context: Context, force: Boolean) {
        if (!enabled) return
        restore(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return
        if (!checkLock.tryLock()) return // ya hay una revisión en curso
        _checking.value = true
        try {
            val release = fetchRelease()
            prefs.edit().putLong(KEY_LAST_CHECK, now).putString(KEY_RELEASE, json.encodeToString(release)).apply()
            settle(context, release)
            Log.i(TAG, "publicada ${release.versionName} (${release.versionCode}); instalada ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        } catch (e: Exception) {
            // Sin red (o el archivo no cuadra): se queda lo que ya se sabía.
            Log.w(TAG, "no se pudo revisar si hay versión nueva: $e")
        } finally {
            _checking.value = false
            checkLock.unlock()
        }
    }

    private fun fetchRelease(): Release {
        val conn = (URL(BuildConfig.UPDATES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val release = json.decodeFromString<Release>(conn.inputStream.bufferedReader().use { it.readText() })
            validate(release)?.let { throw IOException(it) }
            return release
        } finally {
            conn.disconnect()
        }
    }

    /** El APK debe venir del mismo sitio que el version.json y por https (salvo pruebas en debug). */
    private fun validate(r: Release): String? {
        val source = URL(BuildConfig.UPDATES_URL)
        val apk = runCatching { URL(r.apk) }.getOrNull() ?: return "URL del APK inválida"
        return when {
            r.versionCode <= 0 || r.bytes <= 0 -> "versión o tamaño inválidos"
            !r.sha256.matches(Regex("[0-9a-fA-F]{64}")) -> "huella SHA-256 inválida"
            apk.host != source.host || apk.protocol != source.protocol -> "el APK no viene de ${source.host}"
            apk.protocol != "https" && !BuildConfig.DEBUG -> "el APK no viene por https"
            else -> null
        }
    }

    /** Lo guardado de la revisión anterior: el aviso sale al instante, también sin señal. */
    private fun restore(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val saved = prefs(context).getString(KEY_RELEASE, null)
                ?.let { runCatching { json.decodeFromString<Release>(it) }.getOrNull() }
            if (saved != null) settle(context, saved) else cleanup(context, keep = null)
        }
    }

    private fun settle(context: Context, release: Release) {
        val newer = release.versionCode > BuildConfig.VERSION_CODE
        cleanup(context, keep = release.takeIf { newer })
        val current = _state.value
        _state.value = when {
            !newer -> State.UpToDate
            // Una descarga o un error de ESTA versión se respetan (no se reinicia el flujo); el
            // error se queda con los datos recién leídos, por si se corrigió el version.json.
            current is State.Downloading && current.release.versionCode == release.versionCode -> current
            current is State.Failed && current.release.versionCode == release.versionCode -> State.Failed(release, current.message)
            apkFile(context, release).let { it.isFile && it.length() == release.bytes } -> State.Ready(release)
            else -> State.Available(release)
        }
    }

    /** Borra descargas que ya no sirven (versiones instaladas o reemplazadas). */
    private fun cleanup(context: Context, keep: Release?) {
        val keepName = keep?.let { apkFile(context, it).name }
        updatesDir(context).listFiles()?.forEach { f -> if (f.name != keepName) f.delete() }
    }

    // ——— Aviso del Home ———

    /** El oficial cerró el aviso: no vuelve a salir para esta versión en 3 días. */
    fun dismiss(context: Context, release: Release) {
        prefs(context).edit()
            .putInt(KEY_DISMISSED_CODE, release.versionCode)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isDismissed(context: Context, release: Release): Boolean {
        val p = prefs(context)
        return p.getInt(KEY_DISMISSED_CODE, 0) == release.versionCode &&
            System.currentTimeMillis() - p.getLong(KEY_DISMISSED_AT, 0) < DISMISS_FOR_MS
    }

    // ——— Permiso de instalar ———

    /** ¿Android ya le permite a El Puesto instalar apps (en la práctica: actualizarse)? */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Ajuste "Instalar apps desconocidas" de ESTA app (se pide una sola vez). */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    // ——— Descargar e instalar ———

    /** El siguiente paso según el estado: descargar (y luego instalar) o instalar lo descargado. */
    fun update(context: Context) {
        val app = context.applicationContext
        when (val s = _state.value) {
            is State.Ready -> install(app, s.release)
            is State.Available -> download(app, s.release)
            is State.Failed -> {
                val f = apkFile(app, s.release)
                if (f.isFile && f.length() == s.release.bytes) install(app, s.release) else download(app, s.release)
            }
            else -> Unit
        }
    }

    private fun download(context: Context, release: Release) {
        if (downloadJob?.isActive == true) return
        installWhenReady = true
        downloadJob = scope.launch {
            _state.value = State.Downloading(release, 0f)
            val dir = updatesDir(context).apply { mkdirs() }
            val part = File(dir, apkFile(context, release).name + ".part")
            try {
                // El APK + la copia que hace el instalador + margen.
                val needed = release.bytes * 2 + 20L * 1024 * 1024
                if (dir.usableSpace < needed) {
                    fail(release, "No hay espacio suficiente: libera ${(needed - dir.usableSpace) / (1024 * 1024) + 1} MB e intenta de nuevo.")
                    return@launch
                }
                val digest = fetchApk(release, part)
                if (digest != release.sha256.lowercase()) {
                    part.delete()
                    fail(release, "La descarga llegó dañada. Intenta de nuevo.")
                    return@launch
                }
                checkArchive(context, part, release)?.let { problem ->
                    part.delete()
                    fail(release, problem)
                    return@launch
                }
                val target = apkFile(context, release)
                if (!part.renameTo(target)) throw IOException("no se pudo guardar el APK")
                _state.value = State.Ready(release)
                Log.i(TAG, "descargada y verificada la ${release.versionName}")
                if (installWhenReady && foreground) install(context, release)
            } catch (e: Exception) {
                Log.w(TAG, "falló la descarga", e)
                part.delete()
                fail(release, "Se cortó la descarga. Revisa tu conexión e intenta de nuevo.")
            }
        }
    }

    /** Baja el APK a [part] y regresa su SHA-256 (hex). */
    private fun fetchApk(release: Release, part: File): String {
        val conn = (URL(release.apk).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val md = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastStep = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > release.bytes) throw IOException("el APK es más grande de lo anunciado")
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        val step = (read * 100 / release.bytes).toInt()
                        if (step != lastStep) {
                            lastStep = step
                            _state.value = State.Downloading(release, read.toFloat() / release.bytes)
                        }
                    }
                }
            }
            if (read != release.bytes) throw IOException("descarga incompleta ($read de ${release.bytes})")
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Antes de molestar al oficial con el instalador: que sea El Puesto, la versión anunciada y
     * firmada por la misma llave que la instalada (Android lo exige; así el error es claro).
     */
    private fun checkArchive(context: Context, apk: File, release: Release): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "El archivo descargado no es una app válida."
        if (archive.packageName != context.packageName) return "El archivo descargado no es El Puesto."
        if (versionCode(archive) != release.versionCode.toLong()) return "El archivo descargado no es la versión anunciada."
        val installed = signers(pm.getPackageInfo(context.packageName, flags))
        val incoming = signers(archive)
        if (installed.isNotEmpty() && incoming.isNotEmpty() && installed.intersect(incoming).isEmpty()) {
            return "Esta versión está firmada con otra llave que la app que tienes (p. ej. una de prueba). " +
                "Desinstala El Puesto y descárgalo de elpuesto.app."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Huellas SHA-256 de los certificados (incluye el historial si la llave rotó). */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        return certs.map { c ->
            MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Sesión del instalador de Android; él le pide al oficial confirmar (ver [onInstallResult]). */
    private fun install(context: Context, release: Release) {
        installWhenReady = false
        scope.launch {
            val file = apkFile(context, release)
            try {
                val installer = context.packageManager.packageInstaller
                // Sesiones que quedaron a medias (proceso muerto, instalación cancelada).
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                    // Mutable: el instalador le agrega el resultado (y la pantalla de confirmar).
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
                }
                _state.value = State.Ready(release)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo iniciar la instalación", e)
                fail(release, "Android no pudo preparar la instalación. Intenta de nuevo.")
            }
        }
    }

    /** Respuesta del instalador de Android (vía [UpdateInstallReceiver]). */
    internal fun onInstallResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        restore(app)
        val release = when (val s = _state.value) {
            is State.Ready -> s.release
            is State.Failed -> s.release
            is State.Available -> s.release
            else -> null
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(TAG, "instalador: estado $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                try {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "no se pudo abrir la confirmación", e)
                }
            }
            // Éxito: Android reemplaza la app (este proceso termina) y MY_PACKAGE_REPLACED hace el resto.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // El oficial canceló: todo sigue listo para cuando quiera.
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { _state.value = State.Ready(it) }
            else -> release?.let { r ->
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "Esta versión no se puede instalar encima de la que tienes. Desinstala El Puesto y descárgalo de elpuesto.app."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio suficiente en el teléfono para instalarla."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Esta versión no es compatible con tu teléfono."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android bloqueó la instalación."
                    else -> "Android no pudo instalar la actualización. Intenta de nuevo."
                }
                // Archivo dañado o de otra firma: la próxima vez se vuelve a descargar.
                if (status == PackageInstaller.STATUS_FAILURE_INVALID || status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    apkFile(app, r).delete()
                }
                fail(r, message)
            }
        }
    }

    private fun fail(release: Release, message: String) {
        installWhenReady = false
        _state.value = State.Failed(release, message)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun updatesDir(context: Context) = File(context.filesDir, "updates")
    private fun apkFile(context: Context, r: Release) = File(updatesDir(context), "el-puesto-${r.versionCode}.apk")

    // ——— Utilidades para la UI ———

    /** Notas de las versiones más nuevas que la instalada (si te saltaste varias, todas). */
    fun notesSinceInstalled(release: Release): List<Notes> =
        release.novedades.filter { newer(it.version, BuildConfig.VERSION_NAME) }

    /** "1.10.0" > "1.9.2" (numérico por partes). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun sizeLabel(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
}

/** Recibe la respuesta del instalador de Android a la sesión de [AppUpdates]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdates.onInstallResult(context, intent)
}
