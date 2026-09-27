package com.alephri.elpuesto.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import com.alephri.elpuesto.web.resources.Res
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import com.alephri.elpuesto.DebugLog
import com.alephri.elpuesto.data.Auth
import com.alephri.elpuesto.data.HttpRepository
import com.alephri.elpuesto.data.OfflineRepository
import com.alephri.elpuesto.data.RepoPlatform
import com.alephri.elpuesto.ui.ElPuestoApp
import com.alephri.elpuesto.ui.platform.AppIntents
import com.alephri.elpuesto.ui.platform.AppPlatform
import com.alephri.elpuesto.ui.platform.AppUpdatesUi
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import com.alephri.elpuesto.ui.platform.LocationPlatform
import com.alephri.elpuesto.ui.platform.NoLocation
import com.alephri.elpuesto.ui.platform.NoNotifications
import com.alephri.elpuesto.ui.platform.NoReminders
import com.alephri.elpuesto.ui.platform.Reminders
import com.alephri.elpuesto.ui.platform.SystemNotifications
import com.alephri.elpuesto.ui.theme.ElPuestoTheme
import com.alephri.elpuesto.ui.theme.SurfaceTop
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Zonas horarias para kotlinx-datetime en el navegador (sin esto solo existen UTC y la
// del sistema, y `America/Mexico_City` truena).
@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule : JsAny

private val jsJodaTz = JsJodaTimeZoneModule

private fun jsOrigin(): String = js("window.location.origin")
private fun jsHash(): String = js("window.location.hash")
private fun jsClearHash(): Unit = js("{ history.replaceState(null, '', window.location.pathname + window.location.search); }")
private fun jsOnHashChange(cb: () -> Unit): Unit = js("{ window.addEventListener('hashchange', () => cb()); }")
private fun jsOnline(): Boolean = js("navigator.onLine")
private fun jsOnOnlineChange(cb: (Boolean) -> Unit): Unit =
    js("{ window.addEventListener('online', () => cb(true)); window.addEventListener('offline', () => cb(false)); }")
private fun jsOnStorage(cb: (String?, String?) -> Unit): Unit =
    js("{ window.addEventListener('storage', (e) => cb(e.key, e.newValue)); }")
private fun jsReload(): Unit = js("{ window.location.reload(); }")
private fun jsRemoveLoading(): Unit = js("{ const e = document.getElementById('cargando'); if (e) e.remove(); }")
private fun jsIsDev(): Boolean = js("window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'")

/**
 * Enlace mágico: el correo abre `/app/#auth=<token>` (en el FRAGMENTO: no llega al servidor
 * ni a sus logs). Se toma una vez y se borra de la barra de direcciones.
 */
private fun consumeAuthFragment() {
    val hash = jsHash()
    if (!hash.startsWith("#auth=")) return
    val token = Auth.validToken(hash.removePrefix("#auth="))
    jsClearHash()
    if (token != null) AppIntents.pendingAuthToken.value = token
}

/** Red del dispositivo según el navegador (navigator.onLine + eventos online/offline). */
private class WebRepoPlatform : RepoPlatform {
    private val net = MutableStateFlow(jsOnline())
    init { jsOnOnlineChange { net.value = it } }
    override val hasNetwork: StateFlow<Boolean> = net.asStateFlow()
}

/**
 * La web no tiene servicios en segundo plano: sin notificaciones del sistema, sin
 * recordatorios programados, sin transmitir la ubicación propia y sin actualizaciones
 * (se actualiza sola al recargar). La UI oculta lo que no aplica.
 */
private object WebAppPlatform : AppPlatform {
    override val versionName: String = "$WEB_VERSION (web)"
    override val deviceNoun: String = "navegador"
    override val exportChoosesLocation: Boolean = false
    override val notifications: SystemNotifications = NoNotifications
    override val reminders: Reminders = NoReminders
    override val location: LocationPlatform = NoLocation
    override val updates: AppUpdatesUi? = null
}

@OptIn(ExperimentalComposeUiApi::class, org.jetbrains.compose.resources.ExperimentalResourceApi::class)
fun main() {
    jsJodaTz // carga el módulo de zonas horarias
    DebugLog.enabled = jsIsDev()
    DebugLog.sink = { tag, msg -> println("$tag: $msg") }

    consumeAuthFragment()
    jsOnHashChange { consumeAuthFragment() }

    val store = WebAuthStore()
    // Mismo origen que el API (el backend sirve /app/): sin CORS; el WebSocket y la cookie
    // del refresh van al mismo host.
    val remote = HttpRepository(
        baseUrl = jsOrigin(),
        accessToken = { store.jwt },
        // El refresh viaja solo en la cookie HttpOnly: "" = "renueva con la cookie".
        refreshToken = { if (store.hasSession) "" else null },
        onRefreshed = { access, _ -> store.save(access, null) },
        webClient = true,
    )
    val repo = OfflineRepository(
        remote = remote,
        db = MemoryLocalDb(),
        images = MemoryImageCache(),
        prefs = WebPrefs(),
        platform = WebRepoPlatform(),
    )
    val auth = Auth(store, remote)

    // Se entró desde OTRA pestaña (la del enlace del correo) mientras esta esperaba en
    // "Revisa tu correo": recargar toma la sesión nueva (la cookie ya es de todas).
    jsOnStorage { key, value ->
        if (key == "elpuesto.session" && value == "1" && store.jwt == null) jsReload()
    }

    ComposeViewport(document.getElementById("app")!!) {
        // Fuente de respaldo: la de Compose en la web no trae flechas, ✓ ✕ ✎ ⚙ ni triángulos
        // (salían como cuadros). Precargada, Skia la usa para esos glifos; tiene que estar
        // lista ANTES de dibujar (el texto ya medido no se vuelve a medir): hasta entonces
        // se queda la pantalla "Cargando…" del HTML.
        val fonts = LocalFontFamilyResolver.current
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(fonts) {
            runCatching {
                val bytes = Res.readBytes("files/dejavu_sans.ttf")
                fonts.preload(FontFamily(Font("DejaVuSans", bytes)))
            }
            ready = true
            jsRemoveLoading()
        }
        if (ready) {
            CompositionLocalProvider(LocalAppPlatform provides WebAppPlatform) {
                ElPuestoTheme {
                    // La app está diseñada para teléfono: en pantallas anchas va en una
                    // columna centrada (lo demás, fondo) en vez de estirarse.
                    Box(Modifier.fillMaxSize().background(Color(0xFF12151C)), contentAlignment = Alignment.TopCenter) {
                        Surface(Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth().background(SurfaceTop)) {
                            ElPuestoApp(repo, auth)
                        }
                    }
                }
            }
        }
    }
}
