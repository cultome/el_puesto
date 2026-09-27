package com.alephri.elpuesto

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.RepoPlatform
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.LocationUpdate
import com.alephri.elpuesto.ui.platform.AppPlatform
import com.alephri.elpuesto.ui.platform.AppUpdatesUi
import com.alephri.elpuesto.ui.platform.LocationPlatform
import com.alephri.elpuesto.ui.platform.LocationZone
import com.alephri.elpuesto.ui.platform.SystemNotifications
import com.alephri.elpuesto.ui.settings.AppUpdateScreen
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.text.font.FontWeight

/**
 * Lo que el repositorio común necesita de Android: red del teléfono (ConnectivityManager),
 * recordatorios locales al sincronizar la agenda y el aviso de una foto rechazada.
 */
class AndroidRepoPlatform(context: Context) : RepoPlatform {
    private val app = context.applicationContext

    override val hasNetwork: StateFlow<Boolean> = MutableStateFlow(true).also { flag ->
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm != null) {
            flag.value = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { flag.value = true }
                override fun onLost(network: android.net.Network) { flag.value = false }
            })
        }
    }.asStateFlow()

    override fun onAgendaSynced(agenda: List<AgendaEntry>) = Reminders.syncAgenda(app, agenda)
    override fun onUploadRejected(message: String) = ChatNotifications.notifyUploadRejected(app, message)
    override val migratesLegacyImages: Boolean get() = true
}

/**
 * La plataforma para las pantallas comunes, en Android: notificaciones del sistema,
 * recordatorios (AlarmManager), servicio de ubicación, barra fija del evento y
 * actualizaciones de la app.
 */
class AndroidAppPlatform(context: Context) : AppPlatform {
    private val app = context.applicationContext

    override val versionName: String = BuildConfig.VERSION_NAME
    override val deviceNoun: String = "teléfono"

    override val notifications: SystemNotifications = object : SystemNotifications {
        override fun chatMessage(chatId: String, chatName: String, sender: String?, body: String) =
            ChatNotifications.notify(app, chatId, chatName, sender ?: "", body)
        override fun chatInvite(chatId: String, chatName: String, inviter: String) =
            ChatNotifications.notifyChatInvite(app, chatId, chatName, inviter)
        override fun eventActive(eventId: String, eventName: String) = ChatNotifications.notifyEventoActivo(app, eventId, eventName)
        override fun locationShare(officerId: String, name: String) = ChatNotifications.notifyLocationShare(app, officerId, name)
        override fun mbmChanged(eventId: String, eventName: String) = ChatNotifications.notifyMbmCambio(app, eventId, eventName)
        override fun convocatoria(id: String, name: String) = ChatNotifications.notifyConvocatoria(app, id, name)
        override fun cancelChat(chatId: String) = ChatNotifications.cancel(app, chatId)
    }

    override val reminders: com.alephri.elpuesto.ui.platform.Reminders = object : com.alephri.elpuesto.ui.platform.Reminders {
        override val supported = true
        override fun isConvocatoriaSet(id: String) = Reminders.isConvocatoriaSet(app, id)
        override fun setConvocatoria(c: Convocatoria) = Reminders.setConvocatoria(app, c)
        override fun cancelConvocatoria(id: String) = Reminders.cancelConvocatoria(app, id)
    }

    override val location: LocationPlatform = object : LocationPlatform {
        override val supported = true
        override val running: StateFlow<Boolean> = LocationShareService.running
        override val zone: StateFlow<LocationZone> = LocationShareService.zone
        override val ownPosition: StateFlow<LocationUpdate?> = LocationShareService.ownPosition
        override val requests: StateFlow<Int> = LocationSharingController.requests
        override fun requestSync() = LocationSharingController.requestSync()
        override fun hasPermission() = LocationShareService.hasPermission(app)
        override fun stop() = LocationShareService.stop(app)
        override suspend fun sync(repo: AppRepository, eventId: String?, eventName: String?) =
            LocationSharingController.sync(app, repo, eventId, eventName)
    }

    override suspend fun syncLiveEventBar() = LiveEventBar.sync(app)

    override suspend fun wipeSessionExtras() {
        LiveEventBar.cancel(app)
        Reminders.cancelAll(app)
        ChatNotifications.cancelAll(app)
    }

    override val updates: AppUpdatesUi? = if (AppUpdates.enabled) AndroidUpdatesUi() else null
}

/** Actualizaciones desde la app (sin Google Play): aviso del Inicio, fila y pantalla. */
private class AndroidUpdatesUi : AppUpdatesUi {
    /**
     * Aviso de versión nueva (ver [AppUpdates]): abre la pantalla de actualizaciones. "Hay
     * versión nueva" se puede cerrar (vuelve en 3 días); descargando, lista para instalar o
     * con error se queda, porque ya es algo que el oficial empezó.
     */
    @Composable
    override fun HomeBanner(onOpen: () -> Unit) {

        val context = androidx.compose.ui.platform.LocalContext.current
        val state by com.alephri.elpuesto.AppUpdates.state.collectAsState()
        var dismissTick by remember { mutableStateOf(0) }
        val s = state
        val release = when (s) {
            is com.alephri.elpuesto.AppUpdates.State.Available -> s.release
            is com.alephri.elpuesto.AppUpdates.State.Downloading -> s.release
            is com.alephri.elpuesto.AppUpdates.State.Ready -> s.release
            is com.alephri.elpuesto.AppUpdates.State.Failed -> s.release
            else -> return
        }
        val dismissible = s is com.alephri.elpuesto.AppUpdates.State.Available
        val dismissed = remember(release.versionCode, dismissTick) { com.alephri.elpuesto.AppUpdates.isDismissed(context, release) }
        if (dismissible && dismissed) return
        val v = release.versionName
        val (title, body) = when (s) {
            is com.alephri.elpuesto.AppUpdates.State.Downloading -> "Descargando la $v" to "${(s.progress * 100).toInt()} % · al terminar, Android te pide confirmar"
            is com.alephri.elpuesto.AppUpdates.State.Ready -> "La $v está lista" to "Toca para instalarla"
            is com.alephri.elpuesto.AppUpdates.State.Failed -> "No se pudo actualizar a la $v" to "Toca para reintentar"
            else -> "Hay una versión nueva · $v" to "Toca para ver qué cambió y actualizar"
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
                .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable { onOpen() }.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Amber.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                com.alephri.elpuesto.ui.components.LineIconView(com.alephri.elpuesto.ui.components.LineIcon.DOWNLOAD, Amber, size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextHi)
                Text(body, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
            }
            if (dismissible) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        com.alephri.elpuesto.AppUpdates.dismiss(context, release)
                        dismissTick++
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = TextMut, fontSize = 14.sp) }
            } else {
                Text("›", color = TextMut, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }

    @Composable
    override fun settingsSubtitle(): String {
        val s by AppUpdates.state.collectAsState()
        return when (val st = s) {
            AppUpdates.State.Unknown -> "Buscar versiones nuevas"
            AppUpdates.State.UpToDate -> "Estás al día"
            is AppUpdates.State.Available -> "Hay una versión nueva: ${st.release.versionName}"
            is AppUpdates.State.Downloading -> "Descargando la ${st.release.versionName}…"
            is AppUpdates.State.Ready -> "La ${st.release.versionName} está lista para instalar"
            is AppUpdates.State.Failed -> "No se pudo actualizar a la ${st.release.versionName}"
        }
    }

    @Composable
    override fun Screen(onBack: () -> Unit) = AppUpdateScreen(onBack)
}
