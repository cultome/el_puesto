package com.alephri.elpuesto.ui

import com.alephri.elpuesto.data.freshReads
import com.alephri.elpuesto.ui.platform.AppIntents
import com.alephri.elpuesto.ui.platform.BackHandler
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.Auth
import com.alephri.elpuesto.data.LoginResult
import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.Chat
import com.alephri.elpuesto.ui.access.AccessScreen
import androidx.compose.runtime.collectAsState
import com.alephri.elpuesto.ui.agenda.AgendaEntryDetailScreen
import com.alephri.elpuesto.ui.agenda.AgendaScreen
import com.alephri.elpuesto.ui.agenda.EditTripItemScreen
import com.alephri.elpuesto.ui.agenda.NewNoteScreen
import com.alephri.elpuesto.ui.agenda.NewPhotoScreen
import com.alephri.elpuesto.ui.components.AddToBitacoraSheet
import com.alephri.elpuesto.ui.components.BitacoraFab
import com.alephri.elpuesto.ui.championships.ChampionshipCatalogScreen
import com.alephri.elpuesto.ui.championships.ChampionshipDetailScreen
import com.alephri.elpuesto.ui.chats.ArchivedChatsScreen
import com.alephri.elpuesto.ui.chats.ChatConversationScreen
import com.alephri.elpuesto.ui.chats.ChatDetailScreen
import com.alephri.elpuesto.ui.chats.ChatsHubScreen
import com.alephri.elpuesto.ui.chats.isPendingInvite
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.alephri.elpuesto.ui.components.ImageViewerScreen
import com.alephri.elpuesto.ui.chats.NewChatScreen
import com.alephri.elpuesto.ui.chats.PublicChatsScreen
import com.alephri.elpuesto.ui.circuits.CircuitCatalogScreen
import com.alephri.elpuesto.ui.circuits.CircuitDetailScreen
import com.alephri.elpuesto.ui.convocatorias.ConvocatoriaDetailScreen
import com.alephri.elpuesto.ui.convocatorias.ConvocatoriaListScreen
import com.alephri.elpuesto.ui.auth.LoadingScreen
import com.alephri.elpuesto.ui.auth.NotInvitedScreen
import com.alephri.elpuesto.ui.auth.SuspendedScreen
import com.alephri.elpuesto.ui.auth.PendingScreen
import com.alephri.elpuesto.ui.auth.SentScreen
import com.alephri.elpuesto.ui.components.BottomBar
import com.alephri.elpuesto.ui.event.ActivityDetailScreen
import com.alephri.elpuesto.ui.event.EventScreen
import com.alephri.elpuesto.ui.event.PastEventDetailScreen
import com.alephri.elpuesto.ui.home.HomeScreen
import com.alephri.elpuesto.ui.onboarding.CompletarPerfilScreen
import com.alephri.elpuesto.ui.profile.EditEmergencyScreen
import com.alephri.elpuesto.ui.profile.EditProfileScreen
import com.alephri.elpuesto.ui.profile.EmergencyAccessLogScreen
import com.alephri.elpuesto.ui.profile.OfficerProfileScreen
import com.alephri.elpuesto.ui.settings.LocationSharingScreen
import com.alephri.elpuesto.ui.settings.SettingsScreen
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch

private enum class Phase { LOADING, ACCESS, SENT, PENDING, NOT_INVITED, SUSPENDED, ONBOARDING, APP }

private fun AccountStatus?.toPhase(): Phase = when (this) {
    AccountStatus.ACTIVE -> Phase.APP
    AccountStatus.PENDING_APPROVAL -> Phase.PENDING
    AccountStatus.SUSPENDED -> Phase.SUSPENDED
    else -> Phase.NOT_INVITED
}

/**
 * Por qué no se pudo entrar con el enlace. El servidor explica el rechazo (p. ej. "Abre el
 * enlace en el teléfono donde lo pediste…"); sus mensajes técnicos en minúsculas no se
 * muestran tal cual.
 */
private fun loginErrorText(r: LoginResult): String = when (r) {
    is LoginResult.Rejected -> r.message?.takeIf { it.first().isUpperCase() }
        ?: "Este enlace ya se usó o venció. Pide uno nuevo."
    else -> "No pudimos completar el acceso. Revisa tu conexión y vuelve a abrir el enlace."
}

/** ACTIVE con onboarding pendiente → pantalla de Completar perfil; si no, el mapeo normal. */
private suspend fun AccountStatus?.resolvePhase(repo: AppRepository): Phase =
    if (this == AccountStatus.ACTIVE && repo.onboardingPending()) Phase.ONBOARDING else toPhase()

/**
 * Raíz de la app: gate de autenticación (magic link → JWT → estado de cuenta) y, una vez
 * activo, la navegación de las pantallas.
 */
@Composable
fun ElPuestoApp(appRepo: AppRepository, auth: Auth) {
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var accessSending by remember { mutableStateOf(false) }
    var accessError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val platform = LocalAppPlatform.current

    // Imagen que cambió (revalidación o subida propia): fuera de la memoria aunque ninguna
    // pantalla la esté mostrando, para que la próxima vez no salga la vieja.
    LaunchedEffect(appRepo) {
        appRepo.imageChanges().collect { com.alephri.elpuesto.ui.components.ImageMemory.invalidate(it) }
    }
    // Todo lo local del oficial fuera del teléfono (sin red): caché, cola, imágenes,
    // notificaciones en la barra, recordatorios programados y sus preferencias.
    suspend fun wipeLocal() {
        platform.location.stop()
        appRepo.clearLocalData()
        com.alephri.elpuesto.ui.components.ImageMemory.clear()
        platform.wipeSessionExtras()
    }
    // Fin de sesión: al cerrarla el oficial, o cuando el servidor ya no la reconoce (el
    // admin cerró sus sesiones —p. ej. teléfono perdido— o detectó un robo). Nada del
    // usuario se queda en el teléfono ni se envía después.
    suspend fun endSession() {
        // Deja de compartir y retira la posición ANTES de soltar el token.
        platform.location.stop()
        appRepo.clearLocation()
        wipeLocal()
        auth.logout()
    }

    // Aterriza en la fase del estado que dio el servidor. null = ya no reconoce la sesión:
    // se limpia todo. Suspendida: fuera los datos guardados (la sesión se conserva para
    // ver el estado si la reactivan).
    suspend fun applyStatus(st: AccountStatus?, hadSession: Boolean) {
        when {
            st == null -> { if (hadSession) endSession(); phase = Phase.ACCESS }
            st == AccountStatus.SUSPENDED -> {
                wipeLocal()
                phase = Phase.SUSPENDED
            }
            else -> phase = st.resolvePhase(appRepo)
        }
    }

    LaunchedEffect(Unit) {
        // Si el arranque trae un token de enlace mágico, el colector de abajo lo procesa.
        if (AppIntents.pendingAuthToken.value == null) {
            val hadSession = auth.hasSession()
            applyStatus(auth.currentStatus(), hadSession)
        }
    }
    // Al volver a la app con la sesión abierta se revisa de nuevo: una suspensión o un
    // "cerrar sesiones" del admin se aplica sin esperar a reabrirla. Sin señal no cambia
    // nada (currentStatus da el último estado conocido).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val before = phase
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && before in setOf(Phase.APP, Phase.PENDING, Phase.SUSPENDED)) {
                scope.launch {
                    val hadSession = auth.hasSession()
                    val st = auth.currentStatus()
                    // Solo si cambió algo (y nadie más movió la fase mientras tanto).
                    if (phase == before && (st == null || st.toPhase() != before)) applyStatus(st, hadSession)
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Enlace mágico del correo (deep link elpuesto://auth?token=…), en frío o con la app
    // abierta (p. ej. esperando en "Revisa tu correo"): completa el acceso. Con una sesión
    // ya abierta NO entra solo: un enlace ajeno (o de otra cuenta) pide confirmar antes,
    // porque cambiar de cuenta borra lo guardado en el teléfono.
    var switchToken by remember { mutableStateOf<String?>(null) }
    var switchPending by remember { mutableStateOf(0L) }
    var linkError by remember { mutableStateOf<String?>(null) }

    /**
     * Canjea [token] e instala la sesión nueva. [replacing] = había otra sesión abierta y el
     * oficial aceptó reemplazarla: se cierra SOLO si el enlace sirve (si no, sigue dentro).
     */
    suspend fun completeLogin(token: String, replacing: Boolean) {
        // Reemplazando, la app sigue a la vista mientras se canjea: si el enlace no sirve,
        // nada cambia.
        if (!replacing) phase = Phase.LOADING
        val previous = auth.lastOfficerId() ?: appRepo.cachedOfficerId()
        when (val r = auth.redeem(token)) {
            is LoginResult.Ok -> {
                phase = Phase.LOADING
                val sameAccount = previous != null && previous == r.officerId
                if (replacing) {
                    // Con la sesión VIEJA: deja de compartir, retira la posición y revoca su
                    // refresh (también corta el socket en vivo que se abrió con ella).
                    platform.location.stop()
                    appRepo.clearLocation()
                    auth.logout()
                }
                // Otra cuenta (o no se sabe cuál había): nada de la anterior se queda en el
                // teléfono ni se envía con la sesión nueva — se borra ANTES de sincronizar.
                if (!sameAccount && (replacing || previous != null)) {
                    wipeLocal()
                    appRepo.resetOnboarding()
                }
                auth.adopt(r)
                accessError = null
                applyStatus(r.status, hadSession = true)
            }
            else -> {
                if (replacing) {
                    // El enlace no sirvió: quien estaba dentro sigue dentro.
                    linkError = loginErrorText(r)
                    if (phase == Phase.LOADING) applyStatus(auth.currentStatus(), hadSession = true)
                } else {
                    accessError = loginErrorText(r)
                    phase = Phase.ACCESS
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        AppIntents.pendingAuthToken.collect { token ->
            // compareAndSet: el token lo procesa UNA sola vez quien lo tome primero.
            if (token != null && AppIntents.pendingAuthToken.compareAndSet(token, null)) {
                when {
                    !auth.hasSession() -> completeLogin(token, replacing = false)
                    // El mismo enlace con el que ya se entró (la página puente lo reabre sola
                    // y además se toca el botón): nada que hacer.
                    auth.isLastLink(token) -> {
                        if (phase == Phase.LOADING) applyStatus(auth.currentStatus(), hadSession = true)
                    }
                    else -> {
                        switchPending = appRepo.pendingSync().first()
                        switchToken = token
                    }
                }
            }
        }
    }

    // Insets globales: barra de estado arriba y teclado abajo (adjustResize + imePadding:
    // el contenido se encoge al abrir el teclado en vez de "panear" toda la ventana, así
    // los headers siguen visibles y los inputs quedan justo sobre el teclado).
    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
    when (phase) {
        Phase.LOADING -> LoadingScreen()
        Phase.ACCESS -> AccessScreen(sending = accessSending, error = accessError, onSend = { email ->
            scope.launch {
                accessSending = true
                accessError = null
                try {
                    val r = auth.sendMagicLink(email)
                    // El acceso llega SOLO por correo: el devLink de desarrollo se ignora.
                    phase = if (!r.sent) Phase.NOT_INVITED else Phase.SENT
                } catch (e: Throwable) {
                    // Error de red/servidor: mensaje visible y el usuario puede reintentar.
                    accessError = "No pudimos enviar el enlace. Revisa tu conexión e inténtalo de nuevo."
                } finally {
                    accessSending = false
                }
            }
        })
        Phase.SENT -> SentScreen(onOther = { phase = Phase.ACCESS })
        Phase.PENDING -> PendingScreen(onLogout = { scope.launch { endSession(); phase = Phase.ACCESS } })
        Phase.SUSPENDED -> SuspendedScreen(onLogout = { scope.launch { endSession(); phase = Phase.ACCESS } })
        Phase.NOT_INVITED -> NotInvitedScreen(onBack = { phase = Phase.ACCESS })
        Phase.ONBOARDING -> CompletarPerfilScreen(appRepo, onDone = { phase = Phase.APP })
        Phase.APP -> AppNav(appRepo, onLogout = {
            scope.launch {
                endSession()
                phase = Phase.ACCESS
            }
        })
    }
    switchToken?.let { token ->
        fun cancel() {
            // El enlace se ignora; si la app arrancaba con él, se revisa la sesión de siempre.
            switchToken = null
            if (phase == Phase.LOADING) scope.launch { applyStatus(auth.currentStatus(), hadSession = true) }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cancel() },
            containerColor = com.alephri.elpuesto.ui.theme.Panel,
            title = { Text("¿Entrar con otra cuenta?", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
            text = {
                val pendientes = when (switchPending) {
                    0L -> ""
                    1L -> ", incluido 1 cambio sin enviar"
                    else -> ", incluidos $switchPending cambios sin enviar"
                }
                Text(
                    "Abriste un enlace de acceso con tu sesión abierta. Se borrará lo guardado en este ${platform.deviceNoun}$pendientes.\n\nSi no pediste este enlace, cancela.",
                    fontFamily = PlexSansFamily, color = com.alephri.elpuesto.ui.theme.TextSub,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    switchToken = null
                    scope.launch { completeLogin(token, replacing = true) }
                }) { Text("Entrar con otra cuenta", color = com.alephri.elpuesto.ui.theme.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { cancel() }) {
                    Text("Cancelar", color = com.alephri.elpuesto.ui.theme.TextSub)
                }
            },
        )
    }
    linkError?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkError = null },
            containerColor = com.alephri.elpuesto.ui.theme.Panel,
            title = { Text("No se pudo usar el enlace", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
            text = { Text("$msg Sigues con tu sesión de antes.", fontFamily = PlexSansFamily, color = com.alephri.elpuesto.ui.theme.TextSub) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { linkError = null }) {
                    Text("Entendido", color = com.alephri.elpuesto.ui.theme.Amber, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
    com.alephri.elpuesto.ui.platform.ToastHost(Modifier.align(Alignment.BottomCenter))
    }
}

/** Pestañas de primer nivel del shell (barra inferior). El perfil se abre desde el avatar. */
private enum class Tab(val label: String) { INICIO("Inicio"), AGENDA("Agenda"), CHATS("Chats") }

/** Pantallas de detalle apiladas por encima del shell (con botón atrás). */
private sealed interface Overlay {
    data object Event : Overlay
    data class Profile(val officerId: String?) : Overlay // null = mi perfil
    data class Activity(val sessionId: String) : Overlay
    /** Evento pasado: otherName null = historial propio; si no, evento en común con ese oficial. */
    data class PastEvent(val entry: com.alephri.elpuesto.model.OfficerHistoryEntry, val otherName: String?) : Overlay
    /** "Ver todo" del historial / eventos en común (officerId null = propio). */
    data class HistoryList(val officerId: String?, val otherName: String?) : Overlay
    /** Pasaporte de circuitos / todos los logros (officerId null = los propios). */
    data class Passport(val officerId: String?) : Overlay
    data class Achievements(val officerId: String?) : Overlay
    data object EmergencyLog : Overlay
    data object EditProfile : Overlay
    data object EditEmergency : Overlay
    data object CircuitCatalog : Overlay
    data class CircuitDetail(val circuitId: String) : Overlay
    data object ChampionshipCatalog : Overlay
    /** Detalle de un campeonato: por [seriesId] (catálogo) o por una temporada ([seasonId]). */
    data class ChampionshipDetail(val seriesId: String? = null, val seasonId: String? = null) : Overlay
    data class ConvocatoriaList(val past: Boolean) : Overlay
    data class ConvocatoriaDetail(val id: String) : Overlay
    data object Settings : Overlay
    data object LocationSharing : Overlay
    /** Mapa en vivo a pantalla completa (ubicaciones compartidas del evento activo). */
    data object LiveMap : Overlay
    data object Invitations : Overlay
    /** Oficiales que bloqueé (Configuración → Privacidad y datos). */
    data object BlockedOfficers : Overlay
    /** Versión nueva de la app: qué cambió, descargar e instalar. */
    data object AppUpdate : Overlay
    data class AgendaDetail(val entry: AgendaEntry) : Overlay
    /** Registro por honor ("yo trabajé este evento"): alta, edición o quitar. */
    data class RegisterParticipation(val eventId: String) : Overlay
    /** Crear/editar planeación personal; popDetail=true cierra también el detalle (queda stale tras editar). */
    data class EditTripItem(
        val tripItemId: String?,
        val presetEventId: String?,
        val popDetail: Boolean,
        val presetKind: com.alephri.elpuesto.model.TripItemKind? = null,
        val presetRoundId: String? = null,
        /** Fecha inicial (el día tocado en la agenda). */
        val presetDate: kotlinx.datetime.LocalDate? = null,
    ) : Overlay
    /** Captura de bitácora (foto con la cámara / nota rápida) ligada al evento activo. */
    data class NewPhoto(val eventId: String?, val roundId: String? = null) : Overlay
    data class NewNote(val eventId: String?, val roundId: String? = null) : Overlay
    data class ChatConversation(val chat: Chat) : Overlay
    /** Detalles del chat: imagen, participantes y opciones (salir/archivar en públicos). */
    data class ChatDetail(val chat: Chat) : Overlay
    /** Visor de imagen a pantalla completa (fotos de chat). */
    data class ImageView(val path: String) : Overlay
    data object PublicChats : Overlay
    /** Nuevo chat: privado o público, opcionalmente ya ligado a un evento. */
    data class NewChat(val isPrivate: Boolean = false, val eventId: String? = null) : Overlay
    data object ArchivedChats : Overlay
}

/**
 * Navegación interna: un shell con barra inferior (4 pestañas de primer nivel) y una pila
 * de overlays para detalles. Sin dependencia de Navigation-Compose, consistente con el
 * estilo actual. Cada overlay maneja su propio `BackHandler`.
 */
@Composable
private fun AppNav(repo: AppRepository, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.INICIO) }
    val overlays = remember { mutableStateListOf<Overlay>() }
    fun pop() { if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex) }

    // Bitácora del evento activo: FAB global (en el shell) + sheet de captura.
    val eventUi by repo.event().collectAsState(initial = null)
    val activeEventId = eventUi?.event?.id
    var showBitacoraSheet by remember { mutableStateOf(false) }

    // Notificación tocada → navegar (frío o caliente): los destinos viajan por los
    // StateFlow de MainActivity; se aterriza en el tab correspondiente.
    LaunchedEffect(Unit) {
        AppIntents.pendingChatId.collect { id ->
            if (id == null) return@collect
            // Lo último del servidor: el chat puede ser nuevo (una invitación recién llegada).
            val chat = freshReads { repo.chats() }.firstOrNull { it.id == id }
            if (chat != null) {
                tab = Tab.CHATS
                overlays.removeAll { it is Overlay.ChatConversation || it is Overlay.ChatDetail }
                // Invitación pendiente (privado o público): sus detalles (quiénes están + Aceptar).
                overlays.add(
                    if (chat.isPendingInvite()) Overlay.ChatDetail(chat)
                    else Overlay.ChatConversation(chat),
                )
            }
            AppIntents.pendingChatId.value = null
        }
    }
    LaunchedEffect(Unit) {
        AppIntents.pendingOpenEvent.collect { open ->
            if (!open) return@collect
            tab = Tab.INICIO
            overlays.removeAll { it is Overlay.Event }
            overlays.add(Overlay.Event)
            AppIntents.pendingOpenEvent.value = false
        }
    }
    LaunchedEffect(Unit) {
        AppIntents.pendingConvocatoriaId.collect { id ->
            if (id == null) return@collect
            tab = Tab.INICIO
            overlays.add(Overlay.ConvocatoriaDetail(id))
            AppIntents.pendingConvocatoriaId.value = null
        }
    }
    LaunchedEffect(Unit) {
        AppIntents.pendingOpenLocation.collect { open ->
            if (!open) return@collect
            overlays.removeAll { it is Overlay.LocationSharing || it is Overlay.LiveMap }
            // Durante un evento lo útil es VERLO en el mapa; sin evento, la lista de permisos.
            overlays.add(if (repo.event().firstOrNull() != null) Overlay.LiveMap else Overlay.LocationSharing)
            AppIntents.pendingOpenLocation.value = false
        }
    }
    LaunchedEffect(Unit) {
        AppIntents.pendingAgendaId.collect { id ->
            if (id == null) return@collect
            tab = Tab.AGENDA
            repo.agenda().firstOrNull()?.firstOrNull { it.id == id }
                ?.let { overlays.add(Overlay.AgendaDetail(it)) }
            AppIntents.pendingAgendaId.value = null
        }
    }

    // Stream general de cambios (un socket): notificaciones del sistema para mensajes de
    // chat (si el chat no está abierto y el unread del server > 0), eventos activados y
    // convocatorias nuevas (gateadas por prefs).
    val platform = LocalAppPlatform.current
    val notifications = platform.notifications
    LaunchedEffect(Unit) {
        var myId: String? = null
        repo.changes().collect { c ->
            when {
                c.kind == "chat" && c.id != null -> {
                    val chatId = c.id
                    if (overlays.any { it is Overlay.ChatConversation && it.chat.id == chatId }) return@collect
                    if (!repo.notificationPrefs().chatMessages) return@collect
                    if (chatId in repo.mutedChatIds()) return@collect // chat silenciado (local)
                    // Decidir si notificar exige el estado ACTUAL (no leídos, último mensaje).
                    val chat = freshReads { repo.chats() }.firstOrNull { it.id == chatId } ?: return@collect
                    if (chat.unread <= 0) return@collect
                    if (myId == null) myId = repo.myOfficerId()
                    // Solo lo nuevo (después del último guardado; queda en la caché del chat).
                    val last = repo.newerMessages(chatId).lastOrNull() ?: return@collect
                    // Propio (por id; el eco de lo encolado también): no se notifica.
                    if ((last.senderId != null && last.senderId == myId) || last.id.startsWith("local-")) return@collect
                    // De alguien que bloqueé: tampoco.
                    if (last.senderId != null && repo.blocks().any { it.id == last.senderId }) return@collect
                    val body = if (last.mediaType != null) "📷 Foto" else last.text
                    notifications.chatMessage(chatId, chat.name, last.senderName, body)
                }
                // Una imagen cambió en el servidor (avatar, imagen de chat, logo/imagen subida
                // por el admin): la copia guardada se revalida ya y las pantallas se repintan.
                c.kind == "image" && c.id != null -> repo.refreshImage(c.id)
                c.kind == "admin:image" && c.id != null -> repo.refreshImage("/images/${c.id}/full")
                // Te invitaron a un chat, público o privado (solo te llega a ti): aviso que
                // abre la invitación.
                c.kind == "chat-invite" && c.id != null -> {
                    if (!repo.notificationPrefs().chatMessages) return@collect
                    notifications.chatInvite(c.id, c.label ?: "un chat", c.detail ?: "Un oficial")
                }
                c.kind == "admin:event" && c.action == "set-active" && c.detail == "active=true" && c.id != null ->
                    notifications.eventActive(c.id, c.label ?: "Evento")
                // Transparencia: alguien me agregó a su allowlist de ubicación.
                c.kind == "location-share" && c.action == "added" && c.id != null ->
                    notifications.locationShare(c.id, c.label ?: "Un oficial")
                // Control cambió el MbM del evento en el que estoy asignado (si no lo estoy
                // viendo ya en el Modo evento, donde se actualiza en vivo).
                c.kind == "admin:sessions" && c.id != null -> {
                    if (!repo.notificationPrefs().scheduleChanges) return@collect
                    if (overlays.any { it is Overlay.Event }) return@collect
                    val ev = repo.event().firstOrNull()?.event ?: return@collect
                    if (ev.id == c.id) notifications.mbmChanged(ev.id, ev.name)
                }
                c.kind == "admin:convocatoria" && c.action == "created" && c.id != null -> {
                    if (repo.notificationPrefs().newConvocatorias) {
                        notifications.convocatoria(c.id, c.label ?: "Convocatoria")
                    }
                }
            }
        }
    }

    // Compartir ubicación: el servicio se arranca/detiene SOLO con la app en primer plano
    // (Android no deja iniciar un servicio de ubicación desde segundo plano): al abrirla,
    // al volver a ella, al cambiar el evento activo y al tocar la configuración.
    val resumes = remember { kotlinx.coroutines.flow.MutableStateFlow(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes.value++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            repo.event().map { it?.event }.distinctUntilChanged { a, b -> a?.id == b?.id },
            platform.location.requests,
            resumes,
        ) { ev, _, _ -> ev }.collectLatest { ev ->
            // "Sin evento" puede ser transitorio mientras la caché se re-sincroniza: se
            // confirma 3 s antes de detener (collectLatest cancela si llega el evento).
            if (ev == null) kotlinx.coroutines.delay(3_000)
            platform.location.sync(repo, ev?.id, ev?.name)
        }
    }

    // Barra fija "evento en curso": se re-pinta al cambiar el evento activo o su MbM y al
    // volver a la app (la alarma de LiveEventBar la mantiene al día con la app cerrada).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            repo.event().map { it?.event?.id to it?.schedule }.distinctUntilChanged(),
            resumes,
        ) { a, _ -> a }.collectLatest { platform.syncLiveEventBar() }
    }

    // Reconexión: refrescar drena el outbox (escrituras hechas offline) y trae lo nuevo.
    LaunchedEffect(Unit) {
        var was = repo.online.value
        repo.online.collect { on ->
            if (on && !was) repo.refresh()
            was = on
        }
    }

    // Auto-refresh en tiempo real: cualquier mutación admin re-sincroniza la caché (la UI
    // reacciona sola por los Flows de SQLDelight); conflate + pausa colapsan ráfagas.
    LaunchedEffect(Unit) {
        repo.changes()
            .filter { it.kind.startsWith("admin:") && it.kind != "admin:chat" }
            .conflate()
            .collect {
                repo.refresh()
                kotlinx.coroutines.delay(1_500)
            }
    }

    // Preserva el estado (p. ej. el tab activo de Modo evento, scroll) de cada pantalla al
    // apilar/desapilar overlays, en vez de reiniciarlo al salir de composición.
    val stateHolder = rememberSaveableStateHolder()
    val top = overlays.lastOrNull()
    // Indicador de conexión GLOBAL (toda pantalla, tabs y overlays): offline en rojo,
    // regreso a línea en verde transitorio. Las pantallas ya no ponen el suyo.
    val online by repo.online.collectAsState()
    Column(Modifier.fillMaxSize()) {
    com.alephri.elpuesto.ui.components.ConnectionBanner(online)
    Box(Modifier.fillMaxSize().weight(1f)) {
    stateHolder.SaveableStateProvider(overlayKey(top)) {
    when (top) {
        null -> Shell(
            repo = repo,
            tab = tab,
            onTab = { tab = it },
            onOpenEvent = { overlays.add(Overlay.Event) },
            onOpenLiveMap = { overlays.add(Overlay.LiveMap) },
            onOpenSelfProfile = { overlays.add(Overlay.Profile(null)) },
            onOpenCircuitos = { overlays.add(Overlay.CircuitCatalog) },
            onOpenCircuito = { overlays.add(Overlay.CircuitDetail(it)) },
            onOpenCampeonatos = { overlays.add(Overlay.ChampionshipCatalog) },
            onOpenConvocatorias = { overlays.add(Overlay.ConvocatoriaList(past = false)) },
            onOpenConvocatoria = { overlays.add(Overlay.ConvocatoriaDetail(it)) },
            onOpenAgendaEntry = { overlays.add(Overlay.AgendaDetail(it)) },
            onAddAgendaEntry = { day -> overlays.add(Overlay.EditTripItem(null, null, popDetail = false, presetDate = day)) },
            onOpenChat = { overlays.add(Overlay.ChatConversation(it)) },
            onOpenPublicChats = { overlays.add(Overlay.PublicChats) },
            onOpenArchivedChats = { overlays.add(Overlay.ArchivedChats) },
            onNewChat = { overlays.add(Overlay.NewChat(isPrivate = true)) },
            onOpenChatDetails = { overlays.add(Overlay.ChatDetail(it)) },
            onOpenUpdate = { overlays.add(Overlay.AppUpdate) },
        )
        Overlay.Event -> EventScreen(
            repo,
            onBack = { pop() },
            onOpenProfile = { overlays.add(Overlay.Profile(it)) },
            onOpenActivity = { overlays.add(Overlay.Activity(it)) },
            onOpenChat = { overlays.add(Overlay.ChatConversation(it)) },
            onOpenCircuito = { overlays.add(Overlay.CircuitDetail(it)) },
            onOpenTripEditor = { id, evId -> overlays.add(Overlay.EditTripItem(id, evId, popDetail = false)) },
            onOpenLocationSettings = { overlays.add(Overlay.LocationSharing) },
            onOpenLiveMap = { overlays.add(Overlay.LiveMap) },
            onAddToBitacora = { showBitacoraSheet = true },
            onNewChat = { evId -> overlays.add(Overlay.NewChat(isPrivate = true, eventId = evId)) },
        )
        is Overlay.Profile -> OfficerProfileScreen(
            repo, top.officerId, onBack = { pop() },
            onEditProfile = { overlays.add(Overlay.EditProfile) },
            onEditEmergency = { overlays.add(Overlay.EditEmergency) },
            onOpenSettings = { overlays.add(Overlay.Settings) },
            onOpenHistory = { entry, otherName -> overlays.add(Overlay.PastEvent(entry, otherName)) },
            onOpenHistoryList = { id, otherName -> overlays.add(Overlay.HistoryList(id, otherName)) },
            onOpenPassport = { overlays.add(Overlay.Passport(it)) },
            onOpenAchievements = { overlays.add(Overlay.Achievements(it)) },
            onOpenImage = { overlays.add(Overlay.ImageView(it)) },
        )
        is Overlay.Passport -> com.alephri.elpuesto.ui.achievements.PassportScreen(
            repo, top.officerId, onBack = { pop() },
            onOpenCircuit = { overlays.add(Overlay.CircuitDetail(it)) },
        )
        is Overlay.Achievements -> com.alephri.elpuesto.ui.achievements.AchievementsScreen(
            repo, top.officerId, onBack = { pop() },
            onOpenPassport = { overlays.add(Overlay.Passport(top.officerId)) },
        )
        is Overlay.Activity -> ActivityDetailScreen(
            repo, top.sessionId, onBack = { pop() },
            onOpenChampionship = { overlays.add(Overlay.ChampionshipDetail(seasonId = it)) },
        )
        is Overlay.PastEvent -> PastEventDetailScreen(
            top.entry, top.otherName, onBack = { pop() },
            onOpenCircuit = { overlays.add(Overlay.CircuitDetail(it)) },
            // El detalle es una foto del historial: al editar se cierra y se abre el registro.
            onEditRegistration = { pop(); overlays.add(Overlay.RegisterParticipation(it)) },
        )
        is Overlay.HistoryList -> com.alephri.elpuesto.ui.profile.HistoryListScreen(
            repo, top.officerId, top.otherName, onBack = { pop() },
            onOpenHistory = { entry, otherName -> overlays.add(Overlay.PastEvent(entry, otherName)) },
        )
        Overlay.EmergencyLog -> EmergencyAccessLogScreen(repo, onBack = { pop() })
        Overlay.EditProfile -> EditProfileScreen(repo, onBack = { pop() })
        Overlay.EditEmergency -> EditEmergencyScreen(repo, onBack = { pop() })
        Overlay.CircuitCatalog -> CircuitCatalogScreen(
            repo,
            onBack = { pop() },
            onOpenCircuit = { overlays.add(Overlay.CircuitDetail(it)) },
        )
        is Overlay.CircuitDetail -> CircuitDetailScreen(
            repo, top.circuitId, onBack = { pop() },
            onOpenPassport = { overlays.add(Overlay.Passport(null)) },
        )
        Overlay.ChampionshipCatalog -> ChampionshipCatalogScreen(
            repo,
            onBack = { pop() },
            onOpenChampionship = { overlays.add(Overlay.ChampionshipDetail(seriesId = it)) },
        )
        is Overlay.ChampionshipDetail -> ChampionshipDetailScreen(
            repo,
            top.seriesId, top.seasonId,
            onBack = { pop() },
            onOpenCircuito = { overlays.add(Overlay.CircuitDetail(it)) },
            onOpenImage = { overlays.add(Overlay.ImageView(it)) },
        )
        is Overlay.ConvocatoriaList -> ConvocatoriaListScreen(
            repo,
            past = top.past,
            onBack = { pop() },
            onOpen = { overlays.add(Overlay.ConvocatoriaDetail(it)) },
            onOpenHistorial = if (top.past) null else ({ overlays.add(Overlay.ConvocatoriaList(past = true)) }),
        )
        is Overlay.ConvocatoriaDetail -> ConvocatoriaDetailScreen(
            repo, top.id, onBack = { pop() },
            onOpenCircuit = { overlays.add(Overlay.CircuitDetail(it)) },
        )
        Overlay.Settings -> SettingsScreen(
            repo,
            onBack = { pop() },
            onOpenLocationSharing = { overlays.add(Overlay.LocationSharing) },
            onOpenEmergency = { overlays.add(Overlay.EditEmergency) },
            onOpenAccessLog = { overlays.add(Overlay.EmergencyLog) },
            onEditProfile = { overlays.add(Overlay.EditProfile) },
            onOpenInvitations = { overlays.add(Overlay.Invitations) },
            onOpenBlocked = { overlays.add(Overlay.BlockedOfficers) },
            onOpenUpdates = { overlays.add(Overlay.AppUpdate) },
            onLogout = onLogout,
        )
        Overlay.LocationSharing -> LocationSharingScreen(repo, onBack = { pop() })
        Overlay.LiveMap -> com.alephri.elpuesto.ui.event.LiveMapScreen(
            repo, onBack = { pop() }, onOpenLocationSettings = { overlays.add(Overlay.LocationSharing) },
        )
        Overlay.Invitations -> com.alephri.elpuesto.ui.settings.InvitationsScreen(repo, onBack = { pop() })
        Overlay.BlockedOfficers -> com.alephri.elpuesto.ui.settings.BlockedOfficersScreen(
            repo, onBack = { pop() }, onOpenProfile = { overlays.add(Overlay.Profile(it)) },
        )
        Overlay.AppUpdate -> platform.updates?.Screen(onBack = { pop() }) ?: pop()
        is Overlay.AgendaDetail -> AgendaEntryDetailScreen(
            repo, top.entry, onBack = { pop() },
            // El detalle relee la agenda viva: tras editar se actualiza solo (no se cierra).
            onOpenEditor = { tripItemId, link, kind ->
                overlays.add(Overlay.EditTripItem(tripItemId, link.eventId, popDetail = false, presetKind = kind, presetRoundId = link.roundId))
            },
            onAddNote = { overlays.add(Overlay.NewNote(it.eventId, it.roundId)) },
            onAddPhoto = { overlays.add(Overlay.NewPhoto(it.eventId, it.roundId)) },
            onOpenEntry = { overlays.add(Overlay.AgendaDetail(it)) },
            onOpenConvocatoria = { overlays.add(Overlay.ConvocatoriaDetail(it)) },
            onOpenCircuit = { overlays.add(Overlay.CircuitDetail(it)) },
            onOpenChampionship = { overlays.add(Overlay.ChampionshipDetail(seasonId = it)) },
            onOpenEvent = { overlays.add(Overlay.Event) },
            onOpenChat = { overlays.add(Overlay.ChatConversation(it)) },
            onRegister = { overlays.add(Overlay.RegisterParticipation(it)) },
        )
        is Overlay.RegisterParticipation -> com.alephri.elpuesto.ui.agenda.RegisterParticipationScreen(
            repo, top.eventId, onBack = { pop() },
        )
        is Overlay.EditTripItem -> EditTripItemScreen(
            repo, top.tripItemId, top.presetEventId,
            onBack = { pop() },
            onDone = { pop(); if (top.popDetail) pop() },
            presetKind = top.presetKind,
            presetRoundId = top.presetRoundId,
            presetDate = top.presetDate,
        )
        is Overlay.NewPhoto -> NewPhotoScreen(repo, top.eventId, onBack = { pop() }, onDone = { pop() }, roundId = top.roundId)
        is Overlay.NewNote -> NewNoteScreen(repo, top.eventId, onBack = { pop() }, onDone = { pop() }, roundId = top.roundId)
        is Overlay.ChatConversation -> ChatConversationScreen(
            repo, top.chat, onBack = { pop() },
            onOpenDetails = { overlays.add(Overlay.ChatDetail(it)) },
            onOpenImage = { overlays.add(Overlay.ImageView(it)) },
            onOpenProfile = { overlays.add(Overlay.Profile(it)) },
        )
        is Overlay.ChatDetail -> ChatDetailScreen(
            repo, top.chat, onBack = { pop() },
            onOpenProfile = { overlays.add(Overlay.Profile(it)) },
            // Tras salir/archivar: cerrar detalles y conversación (de vuelta a la lista).
            onClosed = { pop(); pop() },
            // Invitación aceptada: de los detalles a la conversación.
            onOpenChat = { chat ->
                pop()
                overlays.removeAll { it is Overlay.ChatConversation && it.chat.id == chat.id }
                overlays.add(Overlay.ChatConversation(chat))
            },
            onOpenImage = { overlays.add(Overlay.ImageView(it)) },
        )
        is Overlay.ImageView -> ImageViewerScreen(repo, top.path, onBack = { pop() })
        Overlay.PublicChats -> PublicChatsScreen(
            repo,
            onOpenChat = { overlays.add(Overlay.ChatConversation(it)) },
            onBack = { pop() },
            onCreateChat = { overlays.add(Overlay.NewChat(isPrivate = false)) },
        )
        is Overlay.NewChat -> NewChatScreen(
            repo,
            // Creado → se abre la conversación en lugar del formulario.
            onCreated = { chat -> pop(); overlays.add(Overlay.ChatConversation(chat)) },
            onBack = { pop() },
            initialPrivate = top.isPrivate,
            presetEventId = top.eventId,
        )
        Overlay.ArchivedChats -> ArchivedChatsScreen(
            repo,
            onOpenChat = { overlays.add(Overlay.ChatConversation(it)) },
            onBack = { pop() },
        )
    }
    }

    // FAB global de bitácora: en el shell Y sobre los overlays mientras hay evento activo,
    // salvo lista negra (decisión 2026-08-02): pantallas con captura/entrada propia donde
    // estorbaría. En Modo evento el FAB vive dentro de la pestaña Bitácora.
    // Un mapa a pantalla completa ocupa las esquinas (sus controles): sin FAB encima.
    if (activeEventId != null && !showBitacoraSheet && bitacoraFabAllowed(top) && !com.alephri.elpuesto.ui.map.MapMemory.fullscreenOpen) {
        BitacoraFab(
            onClick = { showBitacoraSheet = true },
            // Sin bottom bar (overlays) el FAB baja a la altura estándar.
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = if (top == null) 96.dp else 28.dp),
        )
    }
    if (showBitacoraSheet) {
        AddToBitacoraSheet(
            onFoto = { showBitacoraSheet = false; overlays.add(Overlay.NewPhoto(activeEventId)) },
            onNota = { showBitacoraSheet = false; overlays.add(Overlay.NewNote(activeEventId)) },
            onPlan = { kind ->
                showBitacoraSheet = false
                overlays.add(Overlay.EditTripItem(null, activeEventId, popDetail = false, presetKind = kind))
            },
            onDismiss = { showBitacoraSheet = false },
        )
    }
    }
    }
}

/**
 * ¿El FAB de bitácora se muestra sobre esta pantalla? Lista negra: el Modo evento (tiene
 * su FAB en la pestaña Bitácora), el chat (composer/buscador propios), el visor de imagen
 * y los editores/capturas (taparía Guardar o duplicaría la cámara). El resto — consulta
 * (perfil, catálogos, convocatorias, agenda, ajustes) — sí lo muestra: capturar un
 * momento no debe requerir regresar al inicio.
 */
private fun bitacoraFabAllowed(top: Overlay?): Boolean = when (top) {
    null -> true
    Overlay.Event -> false
    is Overlay.ChatConversation, is Overlay.ChatDetail, is Overlay.NewChat -> false
    is Overlay.ImageView -> false
    Overlay.LiveMap -> false // la hoja inferior ocupa ese lugar
    is Overlay.EditTripItem, is Overlay.NewPhoto, is Overlay.NewNote -> false
    is Overlay.RegisterParticipation -> false // formulario: su botón ocupa el fondo
    Overlay.EditProfile, Overlay.EditEmergency, Overlay.Invitations -> false
    Overlay.AppUpdate -> false // su botón ocupa el fondo
    is Overlay.Achievements -> false // su hoja de detalle ocupa el fondo (el botón Listo)
    else -> true
}

/** Clave estable por pantalla para preservar su estado en el SaveableStateHolder. */
private fun overlayKey(top: Overlay?): String = when (top) {
    null -> "shell"
    Overlay.Event -> "event"
    is Overlay.Profile -> "profile:${top.officerId ?: "self"}"
    is Overlay.Activity -> "activity:${top.sessionId}"
    is Overlay.PastEvent -> "pastEvent:${top.entry.id}:${top.otherName}"
    is Overlay.HistoryList -> "historyList:${top.officerId}"
    is Overlay.Passport -> "passport:${top.officerId ?: "self"}"
    is Overlay.Achievements -> "achievements:${top.officerId ?: "self"}"
    Overlay.EmergencyLog -> "emergencyLog"
    Overlay.EditProfile -> "editProfile"
    Overlay.EditEmergency -> "editEmergency"
    Overlay.CircuitCatalog -> "circuitCatalog"
    is Overlay.CircuitDetail -> "circuit:${top.circuitId}"
    Overlay.ChampionshipCatalog -> "champCatalog"
    is Overlay.ChampionshipDetail -> "champ:${top.seriesId ?: "-"}:${top.seasonId ?: "-"}"
    is Overlay.ConvocatoriaList -> "convList:${top.past}"
    is Overlay.ConvocatoriaDetail -> "conv:${top.id}"
    Overlay.Settings -> "settings"
    Overlay.Invitations -> "invitations"
    Overlay.BlockedOfficers -> "blockedOfficers"
    Overlay.AppUpdate -> "appUpdate"
    Overlay.LocationSharing -> "locationSharing"
    Overlay.LiveMap -> "liveMap"
    is Overlay.AgendaDetail -> "agenda:${top.entry.id}"
    is Overlay.RegisterParticipation -> "register:${top.eventId}"
    is Overlay.EditTripItem -> "editTrip:${top.tripItemId ?: "new"}:${top.presetEventId ?: "none"}:${top.presetRoundId ?: "none"}:${top.presetKind ?: "any"}:${top.presetDate ?: "-"}"
    is Overlay.NewPhoto -> "newPhoto:${top.eventId ?: "none"}:${top.roundId ?: "none"}"
    is Overlay.NewNote -> "newNote:${top.eventId ?: "none"}:${top.roundId ?: "none"}"
    is Overlay.ChatConversation -> "chat:${top.chat.id}"
    is Overlay.ChatDetail -> "chatDetail:${top.chat.id}"
    is Overlay.ImageView -> "imageView:${top.path}"
    Overlay.PublicChats -> "publicChats"
    is Overlay.NewChat -> "newChat"
    Overlay.ArchivedChats -> "archivedChats"
}

@Composable
private fun Shell(
    repo: AppRepository,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenEvent: () -> Unit,
    onOpenLiveMap: () -> Unit,
    onOpenSelfProfile: () -> Unit,
    onOpenCircuitos: () -> Unit,
    onOpenCircuito: (String) -> Unit,
    onOpenCampeonatos: () -> Unit,
    onOpenConvocatorias: () -> Unit,
    onOpenConvocatoria: (String) -> Unit,
    onOpenAgendaEntry: (AgendaEntry) -> Unit,
    onAddAgendaEntry: (kotlinx.datetime.LocalDate?) -> Unit,
    onOpenChat: (Chat) -> Unit,
    onOpenPublicChats: () -> Unit,
    onOpenArchivedChats: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChatDetails: (Chat) -> Unit,
    onOpenUpdate: () -> Unit,
) {
    // Back en una pestaña secundaria regresa a Inicio (convención Android).
    if (tab != Tab.INICIO) BackHandler { onTab(Tab.INICIO) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.INICIO -> HomeScreen(
                    repo,
                    onOpenEvent = onOpenEvent,
                    onOpenLiveMap = onOpenLiveMap,
                    onOpenProfile = onOpenSelfProfile,
                    onOpenAgenda = { onTab(Tab.AGENDA) },
                    onOpenCircuitos = onOpenCircuitos,
                    onOpenCircuito = onOpenCircuito,
                    onOpenCampeonatos = onOpenCampeonatos,
                    onOpenConvocatorias = onOpenConvocatorias,
                    onOpenConvocatoria = onOpenConvocatoria,
                    onOpenAgendaEntry = onOpenAgendaEntry,
                    onOpenUpdate = onOpenUpdate,
                )
                Tab.AGENDA -> AgendaScreen(repo, onOpenEntry = onOpenAgendaEntry, onAddEntry = onAddAgendaEntry)
                Tab.CHATS -> ChatsHubScreen(
                    repo,
                    onOpenChat = onOpenChat,
                    onOpenPublic = onOpenPublicChats,
                    onOpenArchived = onOpenArchivedChats,
                    onNewChat = onNewChat,
                    onOpenDetails = onOpenChatDetails,
                )
            }
        }
        BottomBar(selected = tab.label, onSelect = { label -> Tab.values().firstOrNull { it.label == label }?.let(onTab) })
    }
}

@Composable
private fun TabPlaceholder(title: String, body: String) {
    Column(Modifier.fillMaxSize().background(screenBackground()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextHi)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        Spacer(Modifier.weight(1f))
    }
}
