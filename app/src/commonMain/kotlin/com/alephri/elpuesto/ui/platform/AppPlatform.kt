package com.alephri.elpuesto.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.LocationUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que las pantallas (comunes a Android y web) necesitan de la plataforma y que vive
 * fuera del módulo común: servicios y notificaciones de Android, recordatorios, la barra
 * del evento en curso y las actualizaciones de la app. Cada plataforma da su versión por
 * [LocalAppPlatform]; lo que una plataforma no tiene se declara como no disponible
 * (`supported = false`) y la UI lo oculta en vez de mostrar algo que no funciona.
 */
interface AppPlatform {
    /** Versión instalada, p. ej. "1.1.2" (Configuración → Acerca de). */
    val versionName: String

    /** Nombre del dispositivo en los textos ("teléfono" / "navegador"). */
    val deviceNoun: String

    /**
     * "Descargar mis datos": ¿el oficial elige dónde guardar (Android) o el navegador lo
     * descarga directo (web)?
     */
    val exportChoosesLocation: Boolean get() = true

    val notifications: SystemNotifications
    val reminders: Reminders
    val location: LocationPlatform

    /** Barra fija "evento en curso" (notificación persistente). Web: no existe. */
    suspend fun syncLiveEventBar() {}

    /**
     * Fin de sesión o cuenta suspendida: fuera todo lo del oficial que la plataforma tenga
     * fuera del repositorio (servicio de ubicación, barra, recordatorios, notificaciones).
     */
    suspend fun wipeSessionExtras() {}

    /** Actualizaciones de la app desde la propia app; null = la plataforma no las tiene. */
    val updates: AppUpdatesUi?
}

/** Notificaciones del sistema (Android). La web aún no tiene: su sección se oculta. */
interface SystemNotifications {
    val supported: Boolean get() = true
    fun chatMessage(chatId: String, chatName: String, sender: String?, body: String) {}
    fun chatInvite(chatId: String, chatName: String, inviter: String) {}
    fun eventActive(eventId: String, eventName: String) {}
    fun locationShare(officerId: String, name: String) {}
    fun mbmChanged(eventId: String, eventName: String) {}
    fun convocatoria(id: String, name: String) {}
    /** Retira las notificaciones de ese chat (se abrió la conversación). */
    fun cancelChat(chatId: String) {}
}

/** Recordatorios locales programados ("Recordarme antes del cierre"). */
interface Reminders {
    val supported: Boolean
    fun isConvocatoriaSet(id: String): Boolean = false
    fun setConvocatoria(c: Convocatoria) {}
    fun cancelConvocatoria(id: String) {}
}

/** Dónde está el oficial respecto a la zona del circuito (solo cerca se comparte). */
enum class LocationZone { UNKNOWN, INSIDE, OUTSIDE, NO_MAP }

/**
 * Compartir MI ubicación (servicio en primer plano en Android). La web no la transmite
 * ([supported] = false): ahí se ve el mapa en vivo de quienes te comparten y se administra
 * la lista de permisos, pero la ubicación propia sale solo de la app de Android.
 */
interface LocationPlatform {
    val supported: Boolean
    val running: StateFlow<Boolean>
    val zone: StateFlow<LocationZone>
    val ownPosition: StateFlow<LocationUpdate?>
    /** Sube cuando algo pide re-evaluar si el servicio debe correr (p. ej. la configuración). */
    val requests: StateFlow<Int>
    fun requestSync() {}
    fun hasPermission(): Boolean = false
    fun stop() {}
    suspend fun sync(repo: AppRepository, eventId: String?, eventName: String?) {}
}

/** Pantallas y avisos de "versión nueva de la app" (solo Android: la web se actualiza sola). */
interface AppUpdatesUi {
    /** Aviso compacto en el Inicio (se oculta solo si no hay nada que decir). */
    @Composable fun HomeBanner(onOpen: () -> Unit)
    /** Subtítulo de la fila "Actualizaciones" en Configuración. */
    @Composable fun settingsSubtitle(): String
    @Composable fun Screen(onBack: () -> Unit)
}

val LocalAppPlatform = staticCompositionLocalOf<AppPlatform> { error("Falta LocalAppPlatform") }

/**
 * Destinos que llegan de fuera de la UI: el enlace mágico (deep link / fragmento de la URL)
 * y el toque de una notificación. Se consumen una vez (quien los toma los vuelve a null).
 */
object AppIntents {
    val pendingAuthToken = MutableStateFlow<String?>(null)
    val pendingChatId = MutableStateFlow<String?>(null)
    val pendingOpenEvent = MutableStateFlow(false)
    val pendingConvocatoriaId = MutableStateFlow<String?>(null)
    val pendingOpenLocation = MutableStateFlow(false)
    val pendingAgendaId = MutableStateFlow<String?>(null)
    /** Página del Modo evento a mostrar al abrirlo (p. ej. el MbM desde su notificación). */
    val pendingEventPage = MutableStateFlow<Int?>(null)
}

/** Plataforma sin servicios (base de la web y de pruebas). */
object NoNotifications : SystemNotifications { override val supported = false }
object NoReminders : Reminders { override val supported = false }
object NoLocation : LocationPlatform {
    override val supported = false
    override val running: StateFlow<Boolean> = MutableStateFlow(false)
    override val zone: StateFlow<LocationZone> = MutableStateFlow(LocationZone.UNKNOWN)
    override val ownPosition: StateFlow<LocationUpdate?> = MutableStateFlow(null)
    override val requests: StateFlow<Int> = MutableStateFlow(0)
}
