package com.alephri.elpuesto.ui.components

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.Tracked
import com.alephri.elpuesto.data.trackLoad
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Llave de recarga de una pantalla: sube sola cuando la conexión REGRESA, cuando algo que
 * la pantalla leyó con [track] cambió (en el servidor o en el teléfono), y con [retry]
 * (que antes sondea la red, para que la lectura no tome el atajo "sin señal"). Úsala como
 * llave del LaunchedEffect de la carga: lo que no se pudo mostrar se carga solo al volver.
 */
@Stable
class Reloader internal constructor(
    private val tick: MutableIntState,
    private val watched: com.alephri.elpuesto.data.SafeSet<String>,
    private val onRetry: () -> Unit,
) {
    val key: Int get() = tick.intValue
    fun retry() = onRetry()

    /**
     * Carga de pantalla suscrita: las lecturas ya son CACHÉ PRIMERO (lo guardado al
     * instante, la red revalida en segundo plano); [track] además anota qué llaves leyó
     * esta pantalla para que [key] suba cuando CUALQUIERA cambie — revalidación, una
     * escritura propia, el outbox al drenarse. Como [trackMiss], dice si alguna lectura
     * no tuvo red ni caché.
     */
    suspend fun <T> track(block: suspend () -> T): Tracked<T> = trackLoad({ watched.add(it) }, block)
}

@Composable
fun rememberReloader(repo: AppRepository): Reloader {
    val online by repo.online.collectAsState()
    val tick = remember { mutableIntStateOf(0) }
    val watched = remember { com.alephri.elpuesto.data.SafeSet<String>() }
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) wasOffline = true
        else if (wasOffline) { wasOffline = false; tick.intValue++ }
    }
    // La revalidación trajo algo distinto de lo que esta pantalla mostró: releer (la
    // ráfaga de varias llaves a la vez se junta en una sola recarga).
    LaunchedEffect(Unit) {
        repo.catalogChanges().filter { it in watched }.collectLatest {
            delay(150)
            tick.intValue++
        }
    }
    val scope = rememberCoroutineScope()
    return remember { Reloader(tick, watched) { scope.launch { repo.checkConnectivity(); tick.intValue++ } } }
}

/**
 * Pantalla completa para un recurso que NO se puede mostrar: no hay red y no está en la
 * caché del teléfono (o, con red, el servidor no respondió). Reemplaza al skeleton que
 * antes se quedaba girando para siempre. Se recarga sola al volver la conexión.
 */
@Composable
fun UnavailableScreen(repo: AppRepository, title: String, onBack: (() -> Unit)?, onRetry: () -> Unit) {
    val online by repo.online.collectAsState()
    if (onBack != null) BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(onBack) else Spacer(Modifier.size(42.dp))
            Text(
                title.uppercase(),
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            UnavailableGlyph(online, 96.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                if (online) "No se pudo cargar" else "Sin conexión",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextHi,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (online) "El servidor no respondió. Intenta de nuevo en un momento."
                else "Esto aún no está guardado en tu ${com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.deviceNoun}, así que no se puede mostrar sin señal. " +
                    "Se cargará solo en cuanto vuelvas a tener conexión.",
                fontFamily = PlexSansFamily, fontSize = 13.5.sp, color = TextMut,
                textAlign = TextAlign.Center, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(22.dp))
            RetryPill(onRetry)
            if (!online) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Lo que ya abriste antes sí se puede ver sin conexión.",
                    fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(64.dp)) // centro óptico un poco arriba
        }
    }
}

/** Variante en línea para UNA sección de una pantalla que sí se pudo mostrar. */
@Composable
fun UnavailableInline(repo: AppRepository, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val online by repo.online.collectAsState()
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UnavailableGlyph(online, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                if (online) "No se pudo cargar" else "Sin conexión",
                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextHi,
            )
            Text(
                if (online) "El servidor no respondió." else "Esta sección aún no está guardada en tu ${com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.deviceNoun}.",
                fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
            )
        }
        Text(
            "REINTENTAR", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
            color = Amber, letterSpacing = 0.8.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRetry() }.padding(6.dp),
        )
    }
}

@Composable
private fun UnavailableGlyph(online: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 2))
            .background(Danger.copy(alpha = 0.10f).compositeOver(Panel))
            .border(1.dp, Danger.copy(alpha = 0.35f), RoundedCornerShape(size / 2)),
        contentAlignment = Alignment.Center,
    ) {
        if (online) {
            Text("!", color = DangerHi, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp)
        } else {
            LineIconView(LineIcon.WIFI_OFF, DangerHi, size = size * 0.42f, strokeWidth = if (size > 48.dp) 1.8f else 2f)
        }
    }
}

@Composable
private fun RetryPill(onRetry: () -> Unit) {
    Text(
        "REINTENTAR",
        fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        color = Amber, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(100.dp))
            .background(Amber.copy(alpha = 0.10f))
            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
            .clickable { onRetry() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * Aviso para una acción que SOLO funciona en línea (crear/unirse a chats, invitar,
 * reportar, cambiar fotos, consultar emergencia…): se muestra ANTES de intentarlo, junto a
 * la acción desactivada — no un error después. Lo que el oficial produce en pista
 * (checklist, asistencia, bitácora, mensajes, perfil) sí va por la cola y no lo necesita.
 */
@Composable
fun NeedsConnectionNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LineIconView(LineIcon.WIFI_OFF, DangerHi, size = 14.dp)
        Text(text, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
    }
}

/** ¿En línea? (red del teléfono Y backend): gatea las acciones que exigen conexión. */
@Composable
fun rememberOnline(repo: AppRepository): Boolean {
    val online by repo.online.collectAsState()
    return online
}
