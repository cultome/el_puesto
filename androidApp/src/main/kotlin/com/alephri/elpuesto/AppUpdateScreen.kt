package com.alephri.elpuesto.ui.settings

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alephri.elpuesto.AppUpdates
import com.alephri.elpuesto.BuildConfig
import com.alephri.elpuesto.ui.components.EmptyState
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.MarkdownText
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Versión nueva de la app: qué cambió, cuánto pesa y el botón que la descarga y abre el
 * instalador de Android (el oficial confirma ahí). Al abrirla se revisa de nuevo.
 */
@Composable
fun AppUpdateScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdates.state.collectAsState()
    val checking by AppUpdates.checking.collectAsState()
    // El permiso de instalar se da en los ajustes de Android: se relee al volver de ahí.
    var canInstall by remember { mutableStateOf(AppUpdates.canInstall(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) canInstall = AppUpdates.canInstall(context)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { AppUpdates.check(context.applicationContext, force = true) }
    fun recheck() { scope.launch { AppUpdates.check(context.applicationContext, force = true) } }

    val release = when (val s = state) {
        is AppUpdates.State.Available -> s.release
        is AppUpdates.State.Downloading -> s.release
        is AppUpdates.State.Ready -> s.release
        is AppUpdates.State.Failed -> s.release
        else -> null
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("ACTUALIZACIONES", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            when {
                release != null -> ReleaseDetails(release)
                state == AppUpdates.State.UpToDate -> EmptyState(
                    LineIcon.CHECK, "Estás al día",
                    "Tienes El Puesto ${BuildConfig.VERSION_NAME}, la versión más reciente.",
                    tint = Live,
                    actionLabel = if (checking) "Buscando…" else "Buscar de nuevo",
                    onAction = { if (!checking) recheck() },
                )
                checking -> {
                    SkeletonBox(Modifier.fillMaxWidth().height(28.dp))
                    Spacer(Modifier.height(10.dp))
                    SkeletonBox(Modifier.fillMaxWidth().height(120.dp), corner = 16.dp)
                }
                else -> EmptyState(
                    LineIcon.WIFI_OFF, "No pudimos revisar",
                    "Revisa tu conexión: la app busca sus versiones nuevas en elpuesto.app.",
                    tint = DangerHi,
                    actionLabel = "Buscar de nuevo",
                    onAction = { recheck() },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        if (release != null) {
            UpdateActions(state, release, canInstall, onAllow = {
                context.startActivity(AppUpdates.installPermissionIntent(context))
            }, onUpdate = { AppUpdates.update(context) })
        }
    }
}

@Composable
private fun ReleaseDetails(release: AppUpdates.Release) {
    Text("VERSIÓN NUEVA", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = Amber, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(6.dp))
    Text("El Puesto ${release.versionName}", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = TextHi)
    Spacer(Modifier.height(4.dp))
    val published = release.publicada?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { " · publicada el ${fecha(it)}" } ?: ""
    Text(
        "Tienes la ${BuildConfig.VERSION_NAME} · ${AppUpdates.sizeLabel(release.bytes)}$published",
        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
    )
    val notes = AppUpdates.notesSinceInstalled(release)
    if (notes.isNotEmpty()) {
        Section("Qué hay de nuevo")
        Card {
            Column(Modifier.padding(vertical = 12.dp)) {
                notes.forEachIndexed { i, n ->
                    // Si te saltaste versiones, cada una con su título.
                    if (notes.size > 1) {
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Text("Versión ${n.version}", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Amber)
                        Spacer(Modifier.height(4.dp))
                    }
                    MarkdownText(n.notas)
                }
            }
        }
    }
}

/** Pie fijo con el siguiente paso: permitir, descargar, instalar o reintentar. */
@Composable
private fun UpdateActions(
    state: AppUpdates.State,
    release: AppUpdates.Release,
    canInstall: Boolean,
    onAllow: () -> Unit,
    onUpdate: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state is AppUpdates.State.Downloading -> DownloadProgress(state.progress, release.bytes)
            !canInstall -> {
                Note(
                    LineIcon.SHIELD,
                    "Android te pedirá permitir que El Puesto instale actualizaciones. Es solo la primera vez, " +
                        "y la app lo usa únicamente para actualizarse a sí misma.",
                )
                PrimaryButton("Permitir en Android", onAllow)
            }
            else -> {
                if (state is AppUpdates.State.Failed) Note(LineIcon.ALERT, state.message, tint = DangerHi)
                Note(
                    LineIcon.DOWNLOAD,
                    "La app se cerrará un momento para instalarse. Lo que tengas sin enviar se queda guardado y se envía después.",
                )
                PrimaryButton(
                    when (state) {
                        is AppUpdates.State.Ready -> "Instalar"
                        is AppUpdates.State.Failed -> "Reintentar"
                        else -> "Descargar e instalar · ${AppUpdates.sizeLabel(release.bytes)}"
                    },
                    onUpdate,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float, bytes: Long) {
    Text(
        "Descargando… ${(progress * 100).toInt()} % · ${AppUpdates.sizeLabel((bytes * progress).toLong())} de ${AppUpdates.sizeLabel(bytes)}",
        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub,
    )
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Border)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Amber))
    }
    Text(
        "Al terminar, Android te pedirá confirmar la instalación.",
        fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut,
    )
}

@Composable
private fun Note(icon: LineIcon, text: String, tint: androidx.compose.ui.graphics.Color = TextSub) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIconView(icon, tint, size = 18.dp)
        Text(text, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub, modifier = Modifier.weight(1f))
    }
}

private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

private fun fecha(i: Instant): String {
    val d = i.toLocalDateTime(TimeZone.of("America/Mexico_City")).date
    return "${d.dayOfMonth} ${MESES[d.monthNumber - 1]} ${d.year}"
}
