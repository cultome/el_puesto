package com.alephri.elpuesto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.freshReads
import com.alephri.elpuesto.model.GeoFence
import com.alephri.elpuesto.model.LocationUpdate
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compartir ubicación en vivo (decisiones 2026-09-24, docs/IDEAS.md): servicio en PRIMER
 * PLANO tipo location, con notificación fija "Compartiendo ubicación · Pausar", que sube
 * la posición ~cada 15 s mientras el evento está activo. Solo lo arranca la app en uso
 * ([LocationSharingController]); no se pide ubicación en segundo plano. El servidor guarda
 * únicamente la última posición y decide si aún toca transmitir (si no, el servicio se
 * detiene solo). Sin conexión la posición se descarta: una vieja no le sirve a nadie.
 * Solo se comparte CERCA DEL CIRCUITO ([GeoFence], decisión 2026-09-25): fuera de la zona
 * del evento no se envía nada (y se retira la última), sin mapa del circuito tampoco; el
 * servidor descarta lo que llegue de fuera como segundo candado.
 */
class LocationShareService : Service() {

    companion object {
        private const val ACTION_START = "com.alephri.elpuesto.location.START"
        private const val ACTION_PAUSE = "com.alephri.elpuesto.location.PAUSE"
        private const val EXTRA_EVENT_ID = "eventId"
        private const val EXTRA_EVENT_NAME = "eventName"
        private const val NOTIF_ID = 42_001
        private const val CHANNEL = "ubicacion"
        private const val INTERVAL_MS = 15_000L
        private const val FENCE_REFRESH_MS = 60_000L

        private val _running = MutableStateFlow(false)
        /** ¿Está transmitiendo? (la UI muestra "Compartiendo" / "En pausa"). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _own = MutableStateFlow<LocationUpdate?>(null)
        /** Mi última posición (pin "Tú" en el mapa del evento). Solo mientras transmite. */
        val ownPosition: StateFlow<LocationUpdate?> = _own.asStateFlow()

        private val _zone = MutableStateFlow(Zone.UNKNOWN)
        /** Dónde estoy respecto a la zona del circuito (la UI explica por qué no se comparte). */
        val zone: StateFlow<Zone> = _zone.asStateFlow()

        fun start(context: Context, eventId: String, eventName: String) {
            val i = Intent(context, LocationShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_EVENT_NAME, eventName)
            ContextCompat.startForegroundService(context, i)
        }

        /** Detiene y retira mi posición del servidor (onDestroy). */
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationShareService::class.java))
        }

        fun hasPermission(context: Context): Boolean =
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo: AppRepository by lazy { AppGraph.get(this).repo }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sending = AtomicBoolean(false)
    private var requesting = false
    private var eventId: String? = null
    private var eventName = "Evento en curso"
    /** false cuando el servidor ya no acepta posiciones (no hay nada que retirar). */
    private var clearOnDestroy = true
    /** ¿El servidor tiene una posición mía? (al salir de la zona hay que retirarla). */
    private var published = false
    private var fence: GeoFence? = null
    private var fenceKnown = false
    private var fenceReadAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val u = LocationUpdate(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f)
            _own.value = u
            // Una subida a la vez: con mala señal no se apilan envíos (el siguiente fix manda).
            if (!sending.compareAndSet(false, true)) return
            scope.launch {
                try {
                    val f = currentFence()
                    val zone = when {
                        f != null && f.contains(u.lat, u.lon) -> Zone.INSIDE
                        f != null -> Zone.OUTSIDE
                        fenceKnown -> Zone.NO_MAP
                        else -> Zone.UNKNOWN // sin poder confirmar la zona, no se envía
                    }
                    setZone(zone)
                    if (zone != Zone.INSIDE) {
                        // Lejos del circuito no sale nada del teléfono; lo último enviado se
                        // retira para que nadie te vea "congelado" en la salida.
                        if (published) {
                            repo.clearLocation()
                            published = false
                        }
                        return@launch
                    }
                    when (repo.sendLocation(u)) {
                        false -> {
                            com.alephri.elpuesto.logd("ElPuestoLoc") { "el servidor ya no acepta posiciones; se detiene" }
                            clearOnDestroy = false
                            stopSelf()
                        }
                        true -> published = true
                        null -> Unit // sin conexión: se descarta, el siguiente fix manda
                    }
                } finally {
                    sending.set(false)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            // "Pausar" de la notificación: vale para este evento (no se reanuda solo).
            repo.setLocationPaused(intent.getStringExtra(EXTRA_EVENT_ID) ?: eventId, paused = true)
            stopSelf()
            return START_NOT_STICKY
        }
        val newEvent = intent?.getStringExtra(EXTRA_EVENT_ID) ?: eventId
        if (newEvent != eventId) {
            fence = null
            fenceKnown = false
        }
        eventId = newEvent
        eventName = intent?.getStringExtra(EXTRA_EVENT_NAME) ?: eventName
        // startForegroundService exige promover a primer plano de inmediato.
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (!hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!requesting) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
                .build()
            try {
                fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                requesting = true
            } catch (e: SecurityException) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        _running.value = true
        // Si el sistema mata el proceso no se revive solo: la app lo re-arranca al abrirse.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (requesting) fused.removeLocationUpdates(callback)
        requesting = false
        _running.value = false
        _own.value = null
        _zone.value = Zone.UNKNOWN
        scope.cancel()
        // El pin desaparece YA para quien me veía (sin esperar el TTL del servidor).
        if (clearOnDestroy) {
            val r = repo
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { r.clearLocation() }
        }
        super.onDestroy()
    }

    /**
     * Zona del evento, releída cada [FENCE_REFRESH_MS]. La primera vez, directo del servidor
     * (la caché podría ser de antes de activarse el evento); sin respuesta queda "sin saber".
     */
    private suspend fun currentFence(): GeoFence? {
        val now = System.currentTimeMillis()
        if (fenceKnown && now - fenceReadAt < FENCE_REFRESH_MS) return fence
        val sharing = try {
            if (fenceKnown) repo.locationSharing() else freshReads { repo.locationSharing() }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        } ?: return fence
        fence = sharing.fence?.takeIf { it.eventId == eventId }
        fenceKnown = true
        fenceReadAt = now
        return fence
    }

    private fun setZone(zone: Zone) {
        if (_zone.value == zone) return
        _zone.value = zone
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ubicación compartida", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras compartes tu ubicación durante un evento"
            },
        )
        val open = PendingIntent.getActivity(
            this, NOTIF_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_EVENT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, NOTIF_ID + 1,
            Intent(this, LocationShareService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (_zone.value) {
            Zone.OUTSIDE -> "Estás fuera del circuito" to "No se comparte tu ubicación; vuelve a compartirse al regresar"
            Zone.NO_MAP -> "No se comparte tu ubicación" to "Este circuito aún no tiene mapa"
            else -> "Compartiendo tu ubicación" to "$eventName · solo con los oficiales que elegiste"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Pausar", pause).build())
            .build()
    }
}

/** Posición respecto a la zona del circuito del evento ([GeoFence]). */
/** La zona respecto al circuito (común con la UI). */
typealias Zone = com.alephri.elpuesto.ui.platform.LocationZone

/**
 * Decide si el servicio debe correr: evento activo con asignación + interruptor encendido
 * + permiso + sin pausa manual en ese evento. Se llama con la app en PRIMER PLANO (al
 * abrirla, al cambiar el evento activo y al tocar la configuración), que es cuando Android
 * permite iniciar un servicio de ubicación.
 */
object LocationSharingController {
    private val _requests = MutableStateFlow(0)
    /** Cambia cuando algo pide re-evaluar (configuración, reanudar). */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    fun requestSync() { _requests.value++ }

    suspend fun sync(context: Context, repo: AppRepository, eventId: String?, eventName: String?) {
        val shouldRun = eventId != null &&
            LocationShareService.hasPermission(context) &&
            !repo.locationPausedFor(eventId) &&
            repo.locationSharing().enabled
        val running = LocationShareService.running.value
        com.alephri.elpuesto.logd("ElPuestoLoc") { "sync: evento=$eventId debe=$shouldRun corre=$running" }
        when {
            shouldRun && !running -> LocationShareService.start(context, eventId!!, eventName ?: "Evento en curso")
            !shouldRun && running -> LocationShareService.stop(context)
        }
    }
}
