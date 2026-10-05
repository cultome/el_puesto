package com.alephri.elpuesto.ui.settings

import kotlinx.datetime.todayIn
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import com.alephri.elpuesto.ui.platform.rememberBatteryOptimization
import com.alephri.elpuesto.ui.platform.rememberExportSaver
import com.alephri.elpuesto.ui.platform.rememberLocationPermissionRequest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.NeedsConnectionNote
import com.alephri.elpuesto.ui.components.UnavailableInline
import com.alephri.elpuesto.ui.components.rememberOnline
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.SectionHeader
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.LocationSharing
import com.alephri.elpuesto.model.NotificationPrefs
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.ui.components.Avatar
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch

// ————————————————————— Configuración (CF-1) —————————————————————

@Composable
fun SettingsScreen(
    repo: AppRepository,
    onBack: () -> Unit,
    onOpenLocationSharing: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenAccessLog: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenInvitations: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val platform = LocalAppPlatform.current
    var officer by remember { mutableStateOf<Officer?>(null) }
    var location by remember { mutableStateOf<LocationSharing?>(null) }
    var notif by remember { mutableStateOf<NotificationPrefs?>(null) }
    var accessCount by remember { mutableStateOf(0) }
    var blockedCount by remember { mutableStateOf<Int?>(null) }
    val pending by repo.pendingSync().collectAsState(initial = 0L)
    val online = rememberOnline(repo)
    // "Descargar mis datos": el oficial elige DÓNDE guardar (selector de Android; nunca a
    // Descargas en automático: lleva su información de emergencia) y el ZIP se escribe
    // directo ahí en streaming. Si falla, el archivo a medias se borra.
    var export by remember { mutableStateOf<ExportUi>(ExportUi.Idle) }
    val exportSaver = rememberExportSaver(
        onStart = { export = ExportUi.Working },
        onResult = { result ->
            if (result == null) {
                export = ExportUi.Idle
            } else {
                scope.launch {
                    // La descarga aparece en el Registro de accesos: refrescar su contador.
                    if (result is com.alephri.elpuesto.data.ExportResult.Ok) accessCount = repo.emergencyAccesses().size
                    export = when (result) {
                        is com.alephri.elpuesto.data.ExportResult.Ok -> ExportUi.Done(result.bytes)
                        is com.alephri.elpuesto.data.ExportResult.TooSoon -> ExportUi.Info(result.message)
                        is com.alephri.elpuesto.data.ExportResult.Failed -> ExportUi.Error(result.message)
                    }
                }
            }
        },
        write = { sink -> repo.exportMyData(sink) },
    )
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    val hubReloader = rememberReloader(repo)
    LaunchedEffect(Unit) { notif = repo.notificationPrefs() }
    LaunchedEffect(hubReloader.key) {
        hubReloader.track {
            officer = repo.profile(null)?.officer
            location = repo.locationSharing()
            accessCount = repo.emergencyAccesses().size
            blockedCount = repo.blocks().size
        }
    }

    fun updateNotif(new: NotificationPrefs) {
        notif = new
        scope.launch {
            repo.setNotificationPrefs(new)
            platform.syncLiveEventBar() // la barra fija aparece/desaparece ya
        }
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CONFIGURACIÓN", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Section("Ubicación")
            val loc = location
            if (loc == null) {
                SkeletonBox(Modifier.fillMaxWidth().height(64.dp), corner = 16.dp)
            } else {
                Card {
                    NavRow("Compartir ubicación", "Con oficiales que elijas, durante eventos", trailing = if (loc.enabled) "Activado" else "Desactivado", onClick = onOpenLocationSharing)
                }
            }

            val n = notif
            if (!platform.notifications.supported) {
                // Sin notificaciones del sistema (web): no se muestran interruptores inertes.
            } else if (n == null) {
                Section("Notificaciones")
                SkeletonBox(Modifier.fillMaxWidth().height(220.dp), corner = 16.dp)
            } else {
                Section("Notificaciones")
                Card {
                    ToggleRow("Convocatorias nuevas", n.newConvocatorias) { updateNotif(n.copy(newConvocatorias = it)) }
                    Sep()
                    ToggleRow("Cambios de cronograma", n.scheduleChanges) { updateNotif(n.copy(scheduleChanges = it)) }
                    Sep()
                    ToggleRow("Mensajes de chat", n.chatMessages) { updateNotif(n.copy(chatMessages = it)) }
                    Sep()
                    ToggleRow("Evento en curso", n.liveEventBar, subtitle = "Actividad actual del MbM, también con el teléfono bloqueado") { updateNotif(n.copy(liveEventBar = it)) }
                }
            }

            Section("Privacidad y datos")
            Card {
                NavRow("Información de emergencia", onClick = onOpenEmergency)
                Sep()
                NavRow("Registro de accesos", "Quién vio tus datos y cuándo", trailing = "$accessCount", onClick = onOpenAccessLog)
                Sep()
                NavRow("Oficiales bloqueados", "No pueden invitarte ni compartirte su ubicación", trailing = blockedCount?.toString(), onClick = onOpenBlocked)
                Sep()
                NavRow("Descargar mis datos", "Una copia en ZIP de todo lo tuyo · 1 vez al día", enabled = online && export !is ExportUi.Working) {
                    scope.launch {
                        export = ExportUi.Checking
                        val st = repo.exportStatus()
                        export = when {
                            st == null -> ExportUi.Error("Sin conexión con el servidor. Intenta de nuevo.")
                            !st.available -> ExportUi.Info("Ya descargaste tus datos hoy. Podrás volver a hacerlo después del ${st.nextAt?.let(::fechaHora) ?: "mañana"}.")
                            else -> ExportUi.Confirm
                        }
                    }
                }
            }
            if (!online) NeedsConnectionNote("Descargar tus datos necesita conexión.")
            ExportStatusLine(export)

            Section("Cuenta")
            Card {
                InfoRow("# OMDAI ID", officer?.omdaiId?.toString() ?: "—")
                Sep()
                InfoRow("Idioma", "Español")
                Sep()
                NavRow("Editar perfil", onClick = onEditProfile)
                Sep()
                NavRow("Invitar a un oficial", "El registro es por invitación entre pares", onClick = onOpenInvitations)
            }

            // Sin Google Play: la app se actualiza desde aquí (o desde el aviso del Inicio).
            platform.updates?.let { updates ->
                Section("Acerca de")
                Card {
                    NavRow("Actualizaciones", updates.settingsSubtitle(), trailing = "v${platform.versionName}", onClick = onOpenUpdates)
                }
            }

            Spacer(Modifier.height(18.dp))
            // Cerrar sesión borra lo local (caché + cola): si hay cambios sin enviar, se avisa.
            DangerRow("Cerrar sesión") { if (pending > 0) confirmLogout = true else onLogout() }
            // "Dar de baja mi cuenta" se quitó (2026-08-01): sin backend ni política de
            // datos aún; anotado como feature futuro en docs/IDEAS.md.

            Spacer(Modifier.height(22.dp))
            Text("El puesto · v${platform.versionName}", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
        if (export is ExportUi.Confirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { export = ExportUi.Idle },
                containerColor = Panel,
                title = { Text("Descargar mis datos", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
                text = {
                    Text(
                        "Tu copia incluye tu información de emergencia, tu bitácora con fotos y los mensajes que enviaste. " +
                            "Guárdala en un lugar seguro. Puedes descargarla una vez al día y te avisaremos por correo.",
                        fontFamily = PlexSansFamily, color = TextSub,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        export = ExportUi.Idle
                        val hoy = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.of("America/Mexico_City"))
                        exportSaver("el-puesto-mis-datos-$hoy.zip")
                    }) { Text(if (platform.exportChoosesLocation) "Elegir dónde guardar" else "Descargar", color = Amber, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { export = ExportUi.Idle }) { Text("Cancelar", color = TextSub) }
                },
            )
        }
        if (confirmLogout) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmLogout = false },
                containerColor = Panel,
                title = { Text(if (pending == 1L) "Tienes 1 cambio sin enviar" else "Tienes $pending cambios sin enviar", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
                text = {
                    Text(
                        "Se enviarán solos cuando vuelvas a tener señal. Si cierras sesión ahora, se pierden.",
                        fontFamily = PlexSansFamily, color = TextSub,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmLogout = false; onLogout() }) {
                        Text("Cerrar sesión", color = Danger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmLogout = false }) { Text("Cancelar", color = TextSub) }
                },
            )
        }
    }
}


// ————————————————————— Compartir ubicación (CF-2) —————————————————————

@Composable
fun LocationSharingScreen(repo: AppRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val location = LocalAppPlatform.current.location
    var ls by remember { mutableStateOf<LocationSharing?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    // Se re-evalúa al volver de Ajustes (permiso / batería) con el ciclo de vida.
    var resumeTick by remember { mutableStateOf(0) }
    BackHandler { onBack() }
    val reloader = rememberReloader(repo)
    suspend fun reload() { ls = reloader.track { repo.locationSharing() }.value }
    LaunchedEffect(reloader.key) { reload() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun setEnabled(on: Boolean) {
        ls = ls?.copy(enabled = on)
        scope.launch {
            repo.setLocationSharingEnabled(on)
            location.requestSync()
            reload()
        }
    }
    // Encender pide el permiso de ubicación "mientras se usa la app" (nunca en segundo plano).
    val requestPermission = rememberLocationPermissionRequest { granted ->
        if (granted) { permissionDenied = false; setEnabled(true) } else permissionDenied = true
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("UBICACIÓN", onBack)
        val state = ls
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text("Compartir ubicación", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextHi)
            Spacer(Modifier.height(6.dp))
            Text(
                "Comparte tu ubicación con oficiales que elijas para encontrarse durante un evento. Tú decides con quién y cuándo.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            Spacer(Modifier.height(16.dp))
            if (state == null) {
                // Cargando: skeleton (toggle + dos secciones), sin estados vacíos prematuros.
                SkeletonBox(Modifier.fillMaxWidth().height(64.dp), corner = 16.dp)
                Spacer(Modifier.height(24.dp))
                SkeletonRows(2, 56.dp)
                Spacer(Modifier.height(14.dp))
                SkeletonRows(1, 56.dp)
            } else {
                Card {
                    ToggleRow(
                        "Compartir mi ubicación", state.enabled,
                        subtitle = "Solo durante el evento activo, con quienes elijas que también estén asignados.",
                    ) { on ->
                        when {
                            !on -> setEnabled(false)
                            // Sin transmisión propia (web) no hace falta permiso del dispositivo.
                            !location.supported || location.hasPermission() -> setEnabled(true)
                            else -> requestPermission()
                        }
                    }
                }
                if (permissionDenied) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Sin permiso de ubicación no se puede compartir. Puedes darlo en los ajustes del teléfono.",
                        fontFamily = PlexSansFamily, fontSize = 12.sp, color = Danger,
                    )
                }
                // Ahorro de batería: varios fabricantes detienen servicios aunque estén en
                // primer plano. Solo se sugiere (la lista de Ajustes no requiere permiso).
                val battery = rememberBatteryOptimization(resumeTick)
                if (state.enabled && battery != null && battery.restricted) {
                    Spacer(Modifier.height(10.dp))
                    Card {
                        NavRow(
                            "Evitar que el ahorro de batería la detenga",
                            "Excluye a El puesto de la optimización de batería",
                            onClick = battery.openSettings,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Es transparente: las personas con las que compartes reciben un aviso y saben que pueden verte, y tú ves a quién le compartes. " +
                        if (location.supported) "Mientras compartes verás una notificación fija para pausar cuando quieras."
                        else "Desde el navegador no se envía tu ubicación: se comparte desde la app de Android. Aquí eliges con quién.",
                    fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
                )

                Section("Compartes con")
                state.sharesWith.forEach { o ->
                    OfficerRow(repo, o, "Te ve durante eventos", onRemove = {
                        scope.launch { repo.removeLocationShare(o.id); reload() }
                    })
                }
                if (state.sharesWith.isEmpty()) Text("No compartes con nadie.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
                AddOfficerSection(repo, current = state.sharesWith.map { it.id }.toSet()) { officer ->
                    scope.launch { repo.addLocationShare(officer); reload() }
                }

                Section("Te comparten")
                // "Mutuo" solo si TAMBIÉN le compartes (entonces aparece arriba, con su ✕
                // para revocar tu dirección); si no, atajo para corresponder. La dirección
                // de ellos hacia ti no se revoca aquí: es su decisión, como la tuya la tuya.
                // Sí puedes OCULTARLOS de tu mapa (vista propia).
                val sharingIds = state.sharesWith.map { it.id }.toSet()
                state.sharedWithYou.forEach { o ->
                    val hidden = o.id in state.hiddenIds
                    val mutual = o.id in sharingIds
                    OfficerRow(
                        repo, o,
                        when {
                            hidden -> "Oculto de tu mapa"
                            mutual -> "Compartes mutuamente"
                            else -> "Comparte su ubicación contigo"
                        },
                        null,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!mutual) LinkText("Compartir también") { scope.launch { repo.addLocationShare(o); reload() } }
                                LinkText(if (hidden) "Mostrar" else "Ocultar", color = if (hidden) Amber else TextSub) {
                                    scope.launch { repo.setLocationHidden(o.id, !hidden); reload() }
                                }
                            }
                        },
                    )
                }
                if (state.sharedWithYou.isEmpty()) Text("Nadie te comparte su ubicación.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkText(text: String, color: androidx.compose.ui.graphics.Color = Amber, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * "+ Agregar oficial" de la allowlist: botón que despliega un buscador inline por nombre u
 * OMDAI ID (desde 3 letras o números, hasta 10 resultados — OfficerSearchRules; sin
 * correos). Tocar un resultado lo agrega.
 */
@Composable
private fun AddOfficerSection(repo: AppRepository, current: Set<String>, onAdd: (Officer) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Officer>?>(null) } // null = aún sin buscar
    var offline by remember { mutableStateOf(false) }

    // Debounce de tecleo: busca 350ms después de la última letra (cancelable).
    LaunchedEffect(query) {
        val q = query.trim()
        if (!com.alephri.elpuesto.data.OfficerSearchRules.ready(q)) { results = null; searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(350)
        val found = repo.searchOfficers(q)
        offline = !repo.online.value
        results = found
        searching = false
    }

    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (open) Amber else BorderStrong, RoundedCornerShape(12.dp))
            .clickable {
                open = !open
                if (!open) { query = ""; results = null }
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (open) "Cancelar" else "+ Agregar oficial",
            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber,
        )
    }
    if (!open) return

    if (!rememberOnline(repo)) {
        Spacer(Modifier.height(6.dp))
        NeedsConnectionNote("Buscar oficiales necesita conexión.")
        return
    }
    Spacer(Modifier.height(10.dp))
    AppTextField(value = query, onValueChange = { query = it }, placeholder = com.alephri.elpuesto.data.OfficerSearchRules.PLACEHOLDER, maxLength = com.alephri.elpuesto.data.TextLimits.SEARCH)
    if (!com.alephri.elpuesto.data.OfficerSearchRules.ready(query)) {
        Spacer(Modifier.height(6.dp))
        Text(com.alephri.elpuesto.data.OfficerSearchRules.HINT, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint)
    }
    Spacer(Modifier.height(8.dp))
    val visible = results?.filter { it.id !in current }
    when {
        searching -> SkeletonRows(2, 48.dp)
        results == null -> {} // sin búsqueda todavía
        visible.isNullOrEmpty() && offline ->
            Text("Sin conexión: la búsqueda necesita internet.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        visible.isNullOrEmpty() ->
            Text("Sin resultados.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        else -> visible.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onAdd(o)
                        open = false; query = ""; results = null
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteAvatar(repo, o.avatarUrl, initials(o.displayName), size = 36.dp, bg = Panel, textColor = TextPrimary)
                Column(Modifier.weight(1f)) {
                    Text(o.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                    Text("# ${o.omdaiId}", fontFamily = PlexMonoFamily, fontSize = 11.5.sp, color = TextMut)
                }
                Text("Agregar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Amber)
            }
        }
    }
}

@Composable
private fun OfficerRow(
    repo: AppRepository,
    o: Officer,
    subtitle: String,
    onRemove: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(repo, o.avatarUrl, initials(o.displayName), size = 40.dp, bg = Panel, textColor = TextPrimary)
        Column(Modifier.weight(1f)) {
            Text(o.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            Text(subtitle, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
        }
        when {
            onRemove != null -> Box(
                Modifier.size(28.dp).clip(CircleShape).border(1.dp, BorderStrong, CircleShape).clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) { Text("×", color = TextSub, fontSize = 16.sp) }
            trailing != null -> trailing()
            else -> Tag("Mutuo", container = Live.copy(alpha = 0.14f), contentColor = Live, border = Live.copy(alpha = 0.28f))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

// ————————————————————— Piezas reutilizables —————————————————————

@Composable
fun Section(title: String) {
    Text(
        title.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
        color = Amber, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PanelElevA).border(1.dp, BorderStrong, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
    ) { content() }
}

@Composable
internal fun Sep() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
internal fun NavRow(title: String, subtitle: String? = null, trailing: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled) { onClick() }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            subtitle?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut) }
        }
        trailing?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextSub) }
        Text("  ›", color = TextFaint, fontSize = 18.sp)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(value, fontFamily = PlexMonoFamily, fontSize = 12.5.sp, color = TextSub)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            subtitle?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut) }
        }
        AppSwitch(checked, onChange)
    }
}

@Composable
private fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(if (checked) Amber else Panel)
            .border(1.dp, if (checked) Amber else BorderStrong, RoundedCornerShape(50)).clickable { onChange(!checked) }.padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) OnAmber else TextMut))
    }
}

@Composable
private fun DangerRow(title: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, BorderStrong, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Danger) }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(34.dp))
    }
}


// ————————————————————— Oficiales bloqueados —————————————————————

/**
 * Oficiales que bloqueaste (caché primero): tocar uno abre su perfil; "Desbloquear" exige
 * conexión (acción administrativa, no va por la cola).
 */
@Composable
fun BlockedOfficersScreen(repo: AppRepository, onBack: () -> Unit, onOpenProfile: (String) -> Unit = {}) {
    var list by remember { mutableStateOf<List<Officer>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val online = rememberOnline(repo)
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    LaunchedEffect(reloader.key) {
        val t = reloader.track { repo.blocks() }
        list = t.value
        missing = t.missed && t.value.isEmpty()
    }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("BLOQUEADOS", onBack)
        Refreshable(onRefresh = { list = repo.blocks() }, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Quien está aquí no puede invitarte a chats ni agregarte a su lista para compartir ubicación, y sus mensajes se ven ocultos en los chats donde coinciden.",
                    fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                )
                if (!online) NeedsConnectionNote("Desbloquear necesita conexión.")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger)
                }
                Spacer(Modifier.height(10.dp))
                val l = list
                when {
                    l == null -> SkeletonRows(3, 56.dp)
                    missing -> UnavailableInline(repo, reloader::retry)
                    l.isEmpty() -> com.alephri.elpuesto.ui.components.EmptyState(
                        com.alephri.elpuesto.ui.components.LineIcon.LOCK, "No has bloqueado a nadie",
                        "Desde el perfil de un oficial, en ⋯, puedes bloquearlo si te molesta.", compact = true,
                    )
                    else -> l.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenProfile(o.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RemoteAvatar(repo, o.avatarUrl, initials(o.displayName), size = 40.dp, bg = Panel, textColor = TextPrimary)
                            Column(Modifier.weight(1f)) {
                                Text(o.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                                Text("# ${o.omdaiId}", fontFamily = PlexMonoFamily, fontSize = 11.5.sp, color = TextMut)
                            }
                            LinkText(
                                if (busyId == o.id) "Desbloqueando…" else "Desbloquear",
                                color = if (online && busyId == null) Amber else TextFaint,
                            ) {
                                if (!online || busyId != null) return@LinkText
                                busyId = o.id
                                error = null
                                scope.launch {
                                    val problem = repo.setBlocked(o.id, false)
                                    busyId = null
                                    if (problem == null) list = repo.blocks()
                                    else error = "No se pudo desbloquear a ${o.displayName}: $problem"
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ————————————————————— Invitaciones (registro por invitación entre pares) —————————————————————

@Composable
fun InvitationsScreen(repo: AppRepository, onBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var invitations by remember { mutableStateOf<List<com.alephri.elpuesto.model.Invitation>?>(null) }
    var invitationsMissing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val online = rememberOnline(repo)
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    LaunchedEffect(reloader.key) {
        val t = reloader.track { repo.myInvitations() }
        invitations = t.value
        invitationsMissing = t.missed && t.value.isEmpty()
    }

    fun statusLabel(s: com.alephri.elpuesto.model.AccountStatus) = when (s) {
        com.alephri.elpuesto.model.AccountStatus.INVITED -> "Invitación enviada"
        com.alephri.elpuesto.model.AccountStatus.PENDING_APPROVAL -> "Esperando aprobación"
        com.alephri.elpuesto.model.AccountStatus.ACTIVE -> "Activo"
        com.alephri.elpuesto.model.AccountStatus.SUSPENDED -> "Suspendido"
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                "INVITAR", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(34.dp))
        }
        Refreshable(onRefresh = { invitations = repo.myInvitations() }, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Invita a otro oficial con su correo: le llegará un mensaje con cómo instalar la app y entrar. Quedará registrado que tú lo invitaste; al entrar, su cuenta esperará la aprobación del administrador.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            Spacer(Modifier.height(14.dp))
            AppTextField(value = email, onValueChange = { email = it; error = null; sent = false }, placeholder = "correo@ejemplo.mx", maxLength = com.alephri.elpuesto.data.TextLimits.EMAIL)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = Danger)
            }
            if (sent) {
                Spacer(Modifier.height(8.dp))
                Text("Invitación enviada ✓", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Live)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                if (sending) "Enviando…" else "Enviar invitación",
                onClick = {
                    scope.launch {
                        sending = true
                        val err = repo.invite(email.trim())
                        sending = false
                        if (err == null) { sent = true; email = ""; invitations = repo.myInvitations() } else error = err
                    }
                },
                enabled = online && !sending && email.contains("@"),
            )
            if (!online) NeedsConnectionNote("Enviar invitaciones necesita conexión.")

            Spacer(Modifier.height(24.dp))
            SectionHeader("Tus invitaciones")
            val list = invitations
            when {
                list == null -> SkeletonRows(2, 56.dp)
                invitationsMissing -> UnavailableInline(repo, reloader::retry)
                list.isEmpty() -> Text("Aún no has invitado a nadie.", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
                else -> list.forEach { inv ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(inv.inviteeEmail, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                            val d = inv.createdAt.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
                            Text("${d.dayOfMonth}/${d.monthNumber}/${d.year}", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)
                        }
                        Tag(statusLabel(inv.status))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                }
            }
            if (list?.any { it.status == com.alephri.elpuesto.model.AccountStatus.INVITED } == true) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "¿No le llegó el correo? Que revise su spam, o vuelve a invitarlo con el mismo correo: se reenvía una vez al día como máximo.",
                    fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextFaint,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

/** Estado de "Descargar mis datos" en Configuración. */
private sealed interface ExportUi {
    data object Idle : ExportUi
    data object Checking : ExportUi
    data object Confirm : ExportUi
    data object Working : ExportUi
    data class Done(val bytes: Long) : ExportUi
    data class Info(val message: String) : ExportUi
    data class Error(val message: String) : ExportUi
}

@Composable
private fun ExportStatusLine(state: ExportUi) {
    val (text, color) = when (state) {
        ExportUi.Checking -> "Revisando…" to TextMut
        ExportUi.Working -> "Preparando y guardando tu copia…" to TextMut
        is ExportUi.Done -> "Listo: tu copia se guardó (${megabytes(state.bytes)} MB)." to Live
        is ExportUi.Info -> state.message to TextMut
        is ExportUi.Error -> state.message to Danger
        else -> return
    }
    Text(text, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = color, modifier = Modifier.padding(top = 8.dp))
}

private val MESES_EXPORT = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** "26 sep, 10:05" en la zona del cronograma. */
private fun fechaHora(i: kotlinx.datetime.Instant): String {
    val d = i.toLocalDateTime(kotlinx.datetime.TimeZone.of("America/Mexico_City"))
    return "${d.dayOfMonth} ${MESES_EXPORT[d.monthNumber - 1]}, ${d.hour.toString().padStart(2, '0')}:${d.minute.toString().padStart(2, '0')}"
}

/** Megabytes con un decimal ("3.4"), sin depender del formato de la plataforma. */
private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10}"
}
