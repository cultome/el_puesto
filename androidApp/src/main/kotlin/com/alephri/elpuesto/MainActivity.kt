package com.alephri.elpuesto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.alephri.elpuesto.data.Auth
import androidx.compose.runtime.CompositionLocalProvider
import com.alephri.elpuesto.ui.ElPuestoApp
import com.alephri.elpuesto.ui.platform.AppIntents
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import com.alephri.elpuesto.ui.theme.ElPuestoTheme
import com.alephri.elpuesto.ui.theme.SurfaceTop

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_OPEN_EVENT = "openEvent"
        const val EXTRA_EVENT_PAGE = "eventPage"
        const val EXTRA_CONVOCATORIA_ID = "convocatoriaId"
        const val EXTRA_AGENDA_ID = "agendaId"
        const val EXTRA_OPEN_LOCATION = "openLocation"

        private fun consume(intent: android.content.Intent?) {
            intent ?: return
            tokenFromIntent(intent)?.let { AppIntents.pendingAuthToken.value = it }
            intent.getStringExtra(EXTRA_CHAT_ID)?.let { AppIntents.pendingChatId.value = it }
            if (intent.getBooleanExtra(EXTRA_OPEN_EVENT, false)) AppIntents.pendingOpenEvent.value = true
            intent.getIntExtra(EXTRA_EVENT_PAGE, -1).takeIf { it >= 0 }?.let { AppIntents.pendingEventPage.value = it }
            intent.getStringExtra(EXTRA_CONVOCATORIA_ID)?.let { AppIntents.pendingConvocatoriaId.value = it }
            intent.getStringExtra(EXTRA_AGENDA_ID)?.let { AppIntents.pendingAgendaId.value = it }
            if (intent.getBooleanExtra(EXTRA_OPEN_LOCATION, false)) AppIntents.pendingOpenLocation.value = true
        }

        /**
         * Token del enlace mágico SOLO si el intent es exactamente `elpuesto://auth?token=<uuid>`
         * (acción VIEW, sin ruta): cualquier otra forma se ignora.
         */
        private fun tokenFromIntent(intent: android.content.Intent): String? {
            if (intent.action != android.content.Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "elpuesto" || uri.host != "auth") return null
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
            return Auth.validToken(runCatching { uri.getQueryParameter("token") }.getOrNull())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consume(intent)

        // Notificaciones de chat: Android 13+ exige pedir el permiso en runtime.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Un force-stop o una actualización de la app tiran las alarmas: re-programar.
        Reminders.rescheduleAll(applicationContext)

        val graph = AppGraph.get(applicationContext)
        val appRepo = graph.repo
        val auth = graph.auth

        setContent {
            CompositionLocalProvider(LocalAppPlatform provides graph.platform) {
                ElPuestoTheme {
                    Surface(Modifier.fillMaxSize().background(SurfaceTop)) {
                        ElPuestoApp(appRepo, auth)
                    }
                }
            }
        }
    }

    // ¿Hay versión nueva? (a lo mucho cada 6 h; el aviso sale en el Home y en Configuración).
    override fun onStart() {
        super.onStart()
        AppUpdates.onForeground(applicationContext)
    }

    override fun onStop() {
        AppUpdates.onBackground()
        super.onStop()
    }

    // Notificación tocada con la app viva (FLAG_ACTIVITY_SINGLE_TOP): no recrea la
    // activity, solo entrega el intent nuevo.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }
}
