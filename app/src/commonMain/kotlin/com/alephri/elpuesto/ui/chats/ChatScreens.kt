package com.alephri.elpuesto.ui.chats

import com.alephri.elpuesto.data.freshReads
import com.alephri.elpuesto.ui.platform.BackHandler
import com.alephri.elpuesto.ui.components.LineIcon
import com.alephri.elpuesto.ui.components.LineIconView
import com.alephri.elpuesto.ui.components.EmptyState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import com.alephri.elpuesto.ui.platform.decodeImage
import com.alephri.elpuesto.ui.platform.rememberCameraCapture
import com.alephri.elpuesto.ui.platform.rememberGalleryPicker
import com.alephri.elpuesto.ui.platform.rememberSquareImagePicker
import com.alephri.elpuesto.ui.platform.rememberToast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.Chat
import com.alephri.elpuesto.model.ChatMember
import com.alephri.elpuesto.model.ChatType
import com.alephri.elpuesto.model.Message
import com.alephri.elpuesto.model.MessageMediaType
import com.alephri.elpuesto.model.Officer
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.Avatar
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.CameraGlyph
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.ImageLoad
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.rememberRemoteImage
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.UnavailableInline
import com.alephri.elpuesto.ui.components.NeedsConnectionNote
import com.alephri.elpuesto.ui.components.rememberOnline
import com.alephri.elpuesto.ui.components.rememberReloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.alephri.elpuesto.ui.theme.Travel
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.todayIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import com.alephri.elpuesto.ui.components.Refreshable

// ————————————————————— Hub (CT-1) —————————————————————

@Composable
fun ChatsHubScreen(
    repo: AppRepository,
    onOpenChat: (Chat) -> Unit,
    onOpenPublic: () -> Unit,
    onOpenArchived: () -> Unit,
    onNewChat: () -> Unit = onOpenPublic,
    onOpenDetails: (Chat) -> Unit = {},
) {
    var chats by remember { mutableStateOf<List<Chat>?>(null) }
    var query by remember { mutableStateOf("") }
    var mutedIds by remember { mutableStateOf(emptySet<String>()) }
    // Caché primero: la lista sale al instante y la red la revalida (ver Reloader.track).
    val reloader = rememberReloader(repo)
    LaunchedEffect(reloader.key) { mutedIds = repo.mutedChatIds(); chats = reloader.track { repo.chats() }.value }
    // Tiempo real: cualquier mensaje nuevo refresca la lista (previews/no leídos). Un aviso
    // en vivo significa "el servidor ya tiene algo nuevo": esas recargas van a la red.
    LaunchedEffect(Unit) { repo.chatChanges().collect { chats = freshReads { repo.chats() } } }
    // Los chats de evento/puesto dependen del evento ACTIVO: cualquier mutación admin
    // (p. ej. desactivar el evento) refetchea la lista para que la sección desaparezca sola.
    // Una invitación a un privado también (aparece arriba, en "Invitaciones").
    LaunchedEffect(Unit) {
        repo.changes().filter { it.kind.startsWith("admin:") || it.kind == "chat-invite" }.conflate()
            .collect { chats = freshReads { repo.chats() } }
    }
    val scope = rememberCoroutineScope()
    val online = rememberOnline(repo)
    var inviteBusy by remember { mutableStateOf(false) }
    fun answerInvite(chat: Chat, accept: Boolean) {
        inviteBusy = true
        scope.launch {
            val ok = repo.setChatJoined(chat.id, accept)
            val all = repo.chats()
            chats = all
            inviteBusy = false
            // Aceptar lleva directo a la conversación.
            if (ok && accept) all.firstOrNull { it.id == chat.id }?.let(onOpenChat)
        }
    }

    val q = query.trim()
    fun match(c: Chat) = q.isBlank() || c.name.contains(q, true) || (c.lastPreview?.contains(q, true) == true)

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 22.dp, end = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi)
            IconChip("+", onNewChat) // "+" crea un chat (privado o público)
        }
        Refreshable(onRefresh = { mutedIds = repo.mutedChatIds(); chats = repo.chats() }, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            AppTextField(value = query, onValueChange = { query = it }, placeholder = "Buscar en tus chats…", maxLength = com.alephri.elpuesto.data.TextLimits.SEARCH)

            if (chats == null) {
                Spacer(Modifier.height(16.dp))
                SkeletonRows(5, 60.dp)
            }
            chats?.let { all ->
                // El evento activo = el del chat de evento vivo (solo se lista el del activo).
                val activeEventId = all.firstOrNull { !it.archived && it.type == ChatType.EVENT }?.eventId
                val invitaciones = all.filter { it.isPendingInvite() }
                // Del evento activo: el del evento, el de tu puesto y los grupos ligados a él.
                val delEvento = eventChats(all, activeEventId).filter(::match)
                val tuyos = all.filter { !it.archived && it.isGroup() && it.joined && it !in delEvento && match(it) }
                val archived = all.filter { it.archived }

                if (invitaciones.isNotEmpty()) {
                    Section("Invitaciones")
                    if (!online) NeedsConnectionNote("Aceptar o rechazar una invitación necesita conexión.")
                    invitaciones.forEach { c ->
                        InviteRow(
                            repo, c, enabled = online && !inviteBusy,
                            onOpen = { onOpenDetails(c) },
                            onAccept = { answerInvite(c, true) },
                            onDecline = { answerInvite(c, false) },
                        )
                    }
                }
                if (q.isNotBlank() && delEvento.isEmpty() && tuyos.isEmpty()) {
                    EmptyState(LineIcon.SEARCH, "Sin resultados", "Ninguno de tus chats coincide con “$q”.", compact = true)
                }
                if (delEvento.isNotEmpty()) {
                    Section("Del evento activo")
                    delEvento.forEach { ChatRow(repo, it, muted = it.id in mutedIds, showEvent = false) { onOpenChat(it) } }
                }
                Section("Tus chats")
                tuyos.forEach { ChatRow(repo, it, muted = it.id in mutedIds) { onOpenChat(it) } }
                if (tuyos.isEmpty() && q.isBlank()) {
                    Text(
                        "Aún no estás en ningún chat privado ni público. Crea uno con + o explora los públicos.",
                        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut, modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                ExploreRow("Explorar chats públicos", "Cualquiera crea y se une", onOpenPublic)

                if (archived.isNotEmpty()) {
                    Section("Archivados")
                    ExploreRow("Chats de eventos pasados", "Solo lectura · ${archived.size} conversaciones", onOpenArchived)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

@Composable
private fun ChatRow(repo: AppRepository, chat: Chat, muted: Boolean = false, showEvent: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatAvatar(repo, chat)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(chat.name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f, fill = false))
                if (muted) {
                    Tag("Silenciado", container = Panel, contentColor = TextFaint, border = Border)
                }
                ChatTypeTag(chat.type)
            }
            // Grupo ligado a un evento: se dice a cuál (en su Modo evento también aparece).
            if (showEvent) chat.eventName?.takeIf { chat.isGroup() }?.let {
                Text("Evento · $it", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = Amber.copy(alpha = 0.85f), maxLines = 1)
            }
            chat.lastPreview?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut, maxLines = 1) }
        }
        // Punto de no leídos (sin contador: con la indicación basta); gris si está silenciado.
        if (chat.unread > 0) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (muted) BorderStrong else Amber))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun ChatAvatar(repo: AppRepository, chat: Chat) {
    val (label, bg, fg) = when (chat.type) {
        ChatType.PUBLIC -> Triple("#", Panel, Amber)
        ChatType.PRIVATE -> Triple(initials(chat.name), PanelElevA, TextSub)
        ChatType.EVENT -> Triple(initials(chat.name), Panel, TextPrimary)
        ChatType.PUESTO -> Triple(chatAvatarLabel(chat), Amber, OnAmber)
    }
    // Imagen del chat por convención; si no tiene, cae al placeholder por tipo.
    RemoteAvatar(repo, chatImageUrl(chat), label, size = 42.dp, bg = bg, textColor = fg)
}

@Composable
internal fun ChatTypeTag(type: ChatType) {
    val (text, color) = when (type) {
        ChatType.EVENT -> "Evento" to Amber
        ChatType.PUESTO -> "Puesto" to Travel
        ChatType.PUBLIC -> "Público" to Live
        ChatType.PRIVATE -> "Privado" to TextSub
    }
    Tag(text, container = color.copy(alpha = 0.14f), contentColor = color, border = color.copy(alpha = 0.28f))
}

@Composable
private fun ExploreRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            Text(subtitle, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
        }
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
}

/** Invitación pendiente a un chat (público o privado): quién invita, y Aceptar / Rechazar. */
@Composable
private fun InviteRow(repo: AppRepository, chat: Chat, enabled: Boolean, onOpen: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // Tocar la fila abre los detalles: quiénes están, para decidir.
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChatAvatar(repo, chat)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chat.name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f, fill = false))
                    ChatTypeTag(chat.type)
                }
                Text(
                    "${chat.invitedBy ?: "Alguien"} te invitó · ${chat.membersCount} ${if (chat.membersCount == 1) "miembro" else "miembros"}",
                    fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
                )
                chat.eventName?.let {
                    Text("Evento · $it", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = Amber.copy(alpha = 0.85f), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (enabled) Amber else Panel)
                    .clickable(enabled = enabled) { onAccept() }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Aceptar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (enabled) OnAmber else TextFaint) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, BorderStrong, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onDecline() }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Rechazar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (enabled) TextSub else TextFaint) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

/** Públicos y privados: grupos por membresía (evento/puesto se derivan de asignaciones). */
internal fun Chat.isGroup() = type == ChatType.PUBLIC || type == ChatType.PRIVATE

/**
 * Invitación PENDIENTE para mí: a un privado, o a un público que alguien me mandó
 * ([Chat.invitedBy]; desde 2026-09-26 nadie entra a un chat sin aceptarlo).
 */
internal fun Chat.isPendingInvite() =
    !archived && !joined && (type == ChatType.PRIVATE || (type == ChatType.PUBLIC && invitedBy != null))

/**
 * Chats del Modo evento de [eventId]: el del evento, el de tu puesto y los grupos
 * (públicos/privados) de los que eres miembro que alguien LIGÓ a ese evento.
 */
internal fun eventChats(all: List<Chat>, eventId: String?): List<Chat> = all.filter { c ->
    !c.archived && when (c.type) {
        ChatType.EVENT, ChatType.PUESTO -> c.eventId == null || c.eventId == eventId
        else -> c.joined && c.eventId != null && c.eventId == eventId
    }
}

// ————————————————————— Explorar públicos (CT-2) —————————————————————

@Composable
fun PublicChatsScreen(
    repo: AppRepository,
    onOpenChat: (Chat) -> Unit,
    onBack: () -> Unit,
    onCreateChat: () -> Unit = {},
) {
    var chats by remember { mutableStateOf<List<Chat>?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    val reloader = rememberReloader(repo)
    LaunchedEffect(reloader.key) { chats = reloader.track { repo.chats() }.value.filter { it.type == ChatType.PUBLIC && !it.archived } }
    val online = rememberOnline(repo)

    val q = query.trim()
    val visible = chats.orEmpty().filter { q.isBlank() || it.name.contains(q, true) || (it.description?.contains(q, true) == true) }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("CHATS PÚBLICOS", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, BorderStrong, RoundedCornerShape(14.dp))
                    .clickable(enabled = online) { onCreateChat() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ Crear un chat público", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = if (online) Amber else TextFaint)
            }
            if (!online) NeedsConnectionNote("Crear un chat o unirte a uno necesita conexión.")
            Spacer(Modifier.height(12.dp))
            AppTextField(value = query, onValueChange = { query = it }, placeholder = "Buscar chats públicos…", maxLength = com.alephri.elpuesto.data.TextLimits.SEARCH)
            Spacer(Modifier.height(14.dp))
            if (chats == null) SkeletonRows(4, 60.dp)
            else if (visible.isEmpty()) {
                if (q.isNotBlank()) EmptyState(LineIcon.SEARCH, "Sin resultados", "Ningún chat público coincide con “$q”.", compact = true)
                else EmptyState(LineIcon.CHAT, "Aún no hay chats públicos", "Crea el primero: cualquier oficial puede unirse.", compact = true)
            }
            visible.forEach { c ->
                PublicRow(c, c.joined, canToggle = online, onOpen = { onOpenChat(c) }) {
                    // Unirse/salir persistente: el backend guarda la membresía y se recarga la lista.
                    scope.launch {
                        if (repo.setChatJoined(c.id, !c.joined)) {
                            chats = repo.chats().filter { it.type == ChatType.PUBLIC && !it.archived }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ————————————————————— Crear chat (privado o público) —————————————————————

/**
 * Nuevo chat. PRIVADO: grupo con nombre por invitación (cada invitado acepta antes de
 * entrar; no aparece en Explorar). PÚBLICO: cualquiera lo encuentra y se une. Ambos se
 * pueden ligar a un evento que trabajas (aparecen en su Modo evento).
 */
@Composable
fun NewChatScreen(
    repo: AppRepository,
    onCreated: (Chat) -> Unit,
    onBack: () -> Unit,
    initialPrivate: Boolean = false,
    presetEventId: String? = null,
) {
    var isPrivate by remember { mutableStateOf(initialPrivate) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var eventId by remember { mutableStateOf(presetEventId) }
    val invitees = remember { mutableStateListOf<Officer>() }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Motivo del último intento fallido (del servidor si lo dio: cupo de chats, nombre…).
    var error by remember { mutableStateOf<String?>(null) }
    val myEvents = rememberMyEvents(repo)
    val online = rememberOnline(repo)
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    val toast = rememberToast()
    // Imagen del chat (opcional): elegir + recorte cuadrado (≤1024 px); se sube al crear.
    val pickImage = rememberSquareImagePicker(maxSide = 1024) { bytes ->
        if (bytes != null) {
            scope.launch {
                imageBytes = bytes
                preview = decodeImage(bytes)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("NUEVO CHAT", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatKindCard(
                    "Privado", "Solo quienes invites", isPrivate, Modifier.weight(1f), onClick = { isPrivate = true },
                ) { LineIconView(LineIcon.LOCK, if (isPrivate) Amber else TextMut, size = 18.dp) }
                ChatKindCard(
                    "Público", "Cualquiera se une", !isPrivate, Modifier.weight(1f), onClick = { isPrivate = false },
                ) { Text("#", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (!isPrivate) Amber else TextMut) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (isPrivate) "Solo lo ven las personas que invites, y cada una acepta la invitación antes de entrar. No aparece en Explorar."
                else "Cualquier oficial puede encontrarlo en Explorar y unirse.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val p = preview
                if (p != null) {
                    androidx.compose.foundation.Image(
                        bitmap = p, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)),
                    )
                } else if (isPrivate) {
                    Avatar(initials(name.ifBlank { "?" }), size = 56.dp, bg = PanelElevA, textColor = TextSub)
                } else {
                    Avatar("#", size = 56.dp, bg = Panel, textColor = Amber)
                }
                Column {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(12.dp))
                            .clickable {
                                pickImage()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(if (preview == null) "Imagen del chat" else "Cambiar imagen", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Opcional", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut)
                }
            }
            Spacer(Modifier.height(18.dp))
            FormLabel("NOMBRE")
            AppTextField(value = name, onValueChange = { name = it }, placeholder = if (isPrivate) "p. ej. Grúa 3 · GP México" else "p. ej. Banderas MX", maxLength = com.alephri.elpuesto.model.NameRules.CHAT_NAME_MAX)
            // Mismas reglas que el servidor (NameRules): se avisa antes de crear.
            val nameProblem = com.alephri.elpuesto.model.NameRules.chatNameProblem(name)
            if (nameProblem != null && name.isNotEmpty()) com.alephri.elpuesto.ui.profile.NameProblem(nameProblem)
            Spacer(Modifier.height(16.dp))
            FormLabel("DESCRIPCIÓN (OPCIONAL)")
            AppTextField(value = description, onValueChange = { description = it }, placeholder = "De qué trata este chat…", maxLength = com.alephri.elpuesto.data.TextLimits.CHAT_DESCRIPTION, showCounter = true)
            Spacer(Modifier.height(18.dp))
            FormLabel("EVENTO (OPCIONAL)")
            Text(
                "Ligado a un evento que trabajas, el chat aparece también en su Modo evento.",
                fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
            )
            Spacer(Modifier.height(6.dp))
            EventPicker(myEvents, eventId) { eventId = it }
            if (isPrivate) {
                Spacer(Modifier.height(18.dp))
                FormLabel(if (invitees.isEmpty()) "INVITAR" else "INVITAR (${invitees.size})")
                invitees.toList().forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RemoteAvatar(repo, o.avatarUrl, initials(o.displayName), size = 32.dp, bg = Panel, textColor = TextPrimary)
                        Text(o.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "✕", color = TextMut, fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { invitees.remove(o) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (online) {
                    OfficerSearch(repo, exclude = invitees.map { it.id }.toSet(), actionLabel = "Agregar") { invitees.add(it) }
                    Spacer(Modifier.height(4.dp))
                    Text("Les llega una invitación; entran cuando la aceptan.", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint)
                }
            }
            error?.let { why ->
                Spacer(Modifier.height(14.dp))
                Text(
                    why,
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (!online) NeedsConnectionNote("Crear un chat necesita conexión.")
            PrimaryButton(
                text = if (saving) "Creando…" else "Crear chat",
                enabled = online && !saving && nameProblem == null && (!isPrivate || invitees.isNotEmpty()),
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        repo.takeUploadProblem() // un motivo viejo no es de este intento
                        val created = repo.createChat(
                            com.alephri.elpuesto.model.NameRules.normalize(name), description.trim().ifBlank { null },
                            isPrivate = isPrivate, eventId = eventId,
                            inviteeIds = if (isPrivate) invitees.map { it.id } else emptyList(),
                        )
                        // La imagen es secundaria: si su subida falla, el chat ya quedó creado
                        // (se avisa por qué, p. ej. cupo de fotos lleno).
                        if (created != null) imageBytes?.let {
                            if (!repo.uploadChatImage(created.id, it)) {
                                val why = repo.takeUploadProblem() ?: "No se pudo subir la imagen del chat."
                                toast(why)
                            }
                        }
                        saving = false
                        if (created != null) onCreated(created)
                        else error = repo.takeUploadProblem() ?: "No se pudo crear el chat. Revisa tu conexión e intenta de nuevo."
                    }
                },
            )
            if (isPrivate && invitees.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Invita al menos a un oficial.", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)
            }
            Spacer(Modifier.height(12.dp))
            // Transparencia: la moderación también alcanza a los privados.
            Text(
                "Cualquier miembro puede reportar un mensaje; los reportes los revisa el administrador (también en los chats privados).",
                fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextMut, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
}

/** Tarjeta para elegir el tipo de chat (Privado / Público). */
@Composable
private fun ChatKindCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Amber.copy(alpha = 0.10f) else Panel)
            .border(1.dp, if (selected) Amber else Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            glyph()
            Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) TextHi else TextSub)
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
    }
}

/**
 * Eventos que trabajas, en curso o por venir (id, nombre): a los que puedes ligar un chat.
 * Salen del evento activo y de la agenda (entradas "Trabajas"); null = cargando.
 */
@Composable
internal fun rememberMyEvents(repo: AppRepository): List<Pair<String, String>>? {
    val events by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, repo) {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val active = repo.event().firstOrNull()?.event
        val fromAgenda = repo.agenda().firstOrNull().orEmpty()
            .filter { it.assigned && it.eventId != null && (it.endsOn ?: it.startsOn ?: today) >= today }
            .map { it.eventId!! to it.title }
        value = (listOfNotNull(active?.let { it.id to it.name }) + fromAgenda).distinctBy { it.first }
    }
    return events
}

/** Selector de evento para ligar un chat ("Ninguno" + tus eventos). */
@Composable
private fun EventPicker(events: List<Pair<String, String>>?, selected: String?, onSelect: (String?) -> Unit) {
    when {
        events == null -> SkeletonRows(1, 44.dp)
        events.isEmpty() && selected == null ->
            Text("No tienes eventos asignados en curso o por venir.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        else -> (listOf<Pair<String?, String>>(null to "Ninguno") + events).forEach { (id, label) ->
            val on = id == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(id) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).border(2.dp, if (on) Amber else BorderStrong, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (on) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Amber)) }
                Text(label, fontFamily = PlexSansFamily, fontSize = 13.5.sp, color = if (on) TextHi else TextSub, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PublicRow(chat: Chat, joined: Boolean, canToggle: Boolean, onOpen: () -> Unit, onToggleJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { onOpen() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar("#", size = 42.dp, bg = Panel, textColor = Amber)
        Column(Modifier.weight(1f)) {
            Text(chat.name, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            chat.description?.let { Text(it, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut) }
            Text("${chat.membersCount} miembros", fontFamily = PlexMonoFamily, fontSize = 10.5.sp, color = TextFaint)
        }
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (joined) Panel else Amber)
                .border(1.dp, if (joined) BorderStrong else Amber, RoundedCornerShape(10.dp))
                .alpha(if (canToggle) 1f else 0.4f).clickable(enabled = canToggle) { onToggleJoin() }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (joined) "Unido" else "Unirme", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (joined) TextSub else OnAmber)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

// ————————————————————— Archivados —————————————————————

@Composable
fun ArchivedChatsScreen(repo: AppRepository, onOpenChat: (Chat) -> Unit, onBack: () -> Unit) {
    var chats by remember { mutableStateOf<List<Chat>?>(null) } // null = cargando
    BackHandler { onBack() }
    val reloader = rememberReloader(repo)
    LaunchedEffect(reloader.key) { chats = reloader.track { repo.chats() }.value.filter { it.archived } }
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("ARCHIVADOS", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text("Chats de eventos pasados · solo lectura", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
            Spacer(Modifier.height(12.dp))
            val list = chats
            if (list == null) SkeletonRows(3, 60.dp)
            else if (list.isEmpty()) {
                EmptyState(LineIcon.ARCHIVE, "Sin chats archivados", "Los chats de un evento se archivan una semana después y quedan aquí para consulta.")
            }
            list?.forEach { ChatRow(repo, it) { onOpenChat(it) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ————————————————————— Conversación (CT-3 / CT-4) —————————————————————

@Composable
fun ChatConversationScreen(
    repo: AppRepository,
    chat: Chat,
    onBack: () -> Unit,
    onOpenDetails: (Chat) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    /** Avatar de un mensaje → perfil del remitente (null = el propio). */
    onOpenProfile: (String?) -> Unit = {},
) {
    val notifications = LocalAppPlatform.current.notifications
    val scope = rememberCoroutineScope()
    var messages by remember(chat.id) { mutableStateOf<List<Message>?>(null) }
    var draft by remember { mutableStateOf("") }
    var myId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendingMedia by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // Moderación: menú por long-press en una burbuja → reportar / silenciar el chat.
    var menuFor by remember(chat.id) { mutableStateOf<Message?>(null) }
    var reportFor by remember(chat.id) { mutableStateOf<Message?>(null) }
    var muted by remember(chat.id) { mutableStateOf(false) }
    // Oficiales que bloqueé: sus mensajes se colapsan en una línea ("Ver" los muestra).
    var blockedIds by remember { mutableStateOf(emptySet<String>()) }
    val revealed = remember(chat.id) { mutableStateListOf<String>() }
    // Sin red ni caché de ESTE chat: aviso en lugar de la conversación (el composer sigue:
    // lo que escribas se encola y sale al volver la señal).
    var msgsMissing by remember(chat.id) { mutableStateOf(false) }
    // Paginación: la caché trae la cola del chat; "Cargar mensajes anteriores" pide a la
    // red la página previa y la antepone (lo mostrado solo crece: ver mergeShown).
    var hasOlder by remember(chat.id) { mutableStateOf(false) }
    var loadingOlder by remember(chat.id) { mutableStateOf(false) }
    var olderFailed by remember(chat.id) { mutableStateOf(false) }
    // Ya se llegó al primer mensaje del chat: no hay más atrás.
    var olderExhausted by remember(chat.id) { mutableStateOf(false) }
    // Al anteponer, se conserva lo que el oficial veía: (maxValue, value) previos.
    var keepAnchor by remember(chat.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val reloader = rememberReloader(repo)
    val scroll = rememberScrollState()
    BackHandler {
        when {
            reportFor != null -> reportFor = null
            menuFor != null -> menuFor = null
            showAttach -> showAttach = false
            else -> onBack()
        }
    }
    // Tiempo real (WebSocket): los mensajes de ESTE chat llegan solos (y quedan leídos:
    // la conversación está abierta).
    LaunchedEffect(chat.id) {
        repo.chatChanges().collect { changed ->
            if (changed == chat.id) {
                // Solo lo nuevo (después del último guardado), no todo el historial.
                messages = mergeShown(messages, repo.newerMessages(chat.id))
                repo.markChatRead(chat.id)
            }
        }
    }
    LaunchedEffect(chat.id, reloader.key) {
        myId = repo.myOfficerId()
        muted = chat.id in repo.mutedChatIds()
        val t = reloader.track { repo.messages(chat.id).also { blockedIds = repo.blocks().map { b -> b.id }.toSet() } }
        messages = mergeShown(messages, t.value)
        // Una página llena = puede haber más atrás (si no, es todo el historial).
        if (!olderExhausted && t.value.count { !it.id.startsWith("local-") } >= CHAT_PAGE) hasOlder = true
        msgsMissing = t.missed && t.value.isEmpty()
        // Abrir la conversación marca leído y retira la notificación del sistema.
        repo.markChatRead(chat.id)
        notifications.cancelChat(chat.id)
    }
    // Conversación: anclada al último mensaje (al abrir, al enviar y al llegar uno nuevo);
    // al anteponer anteriores, se queda donde estaba el oficial.
    LaunchedEffect(messages?.lastOrNull()?.id) { if (keepAnchor == null) scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(messages?.firstOrNull()?.id) {
        val (oldMax, oldValue) = keepAnchor ?: return@LaunchedEffect
        // Espera a que el contenido antepuesto se mida y compensa lo que creció arriba.
        kotlinx.coroutines.withTimeoutOrNull(1_000) { androidx.compose.runtime.snapshotFlow { scroll.maxValue }.first { it != oldMax } }
        scroll.scrollTo(oldValue + (scroll.maxValue - oldMax))
        keepAnchor = null
    }
    fun loadOlder() {
        val oldest = messages?.firstOrNull { !it.id.startsWith("local-") }?.id ?: return
        loadingOlder = true
        olderFailed = false
        scope.launch {
            val page = repo.olderMessages(chat.id, oldest)
            loadingOlder = false
            if (page == null) { olderFailed = true; return@launch }
            if (page.size < CHAT_PAGE) { hasOlder = false; olderExhausted = true }
            if (page.isNotEmpty()) {
                keepAnchor = scroll.maxValue to scroll.value
                messages = mergeShown(messages, page)
            }
        }
    }

    // Foto por cámara o galería → JPEG escalado (lado mayor ≤ 1600 px) → mensaje.
    fun dispatchMedia(bytes: ByteArray?) {
        if (bytes == null) return // canceló
        sendingMedia = true
        error = null
        scope.launch {
            val sent = repo.sendMediaMessage(chat.id, "", bytes)
            sendingMedia = false
            if (sent == null) error = repo.takeUploadProblem() ?: "No se pudo enviar la foto. Revisa tu conexión."
            else messages = mergeShown(messages, repo.messages(chat.id))
        }
    }
    val camera = rememberCameraCapture(maxSide = 1600) { dispatchMedia(it) }
    val gallery = rememberGalleryPicker(maxSide = 1600) { dispatchMedia(it) }

    Box(Modifier.fillMaxSize()) {
    // navigationBarsPadding: el composer no se mete bajo la barra de gestos de Android.
    Column(Modifier.fillMaxSize().background(screenBackground()).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            // El header abre los detalles del chat (imagen, participantes, opciones).
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenDetails(chat) }.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Text(chat.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextHi, maxLines = 1)
                val sub = when {
                    chat.archived -> "Archivado · toca para ver detalles"
                    chat.membersCount == 1 -> "1 participante · toca para ver detalles"
                    chat.membersCount > 1 -> "${chat.membersCount} participantes · toca para ver detalles"
                    else -> "Toca para ver detalles"
                }
                Text(sub, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
            }
            Box(Modifier.clip(RoundedCornerShape(21.dp)).clickable { onOpenDetails(chat) }) {
                RemoteAvatar(
                    repo, chatImageUrl(chat), chatInitial(chat),
                    size = 42.dp, bg = Panel, textColor = Amber,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))

        if (chat.type == ChatType.PRIVATE && !chat.archived) {
            Text("Chat privado · solo sus miembros lo ven. Mantén presionado un mensaje para reportarlo.", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), textAlign = TextAlign.Center)
        }
        if (chat.type == ChatType.PUBLIC && !chat.archived) {
            Text("Chat moderado · mantén presionado un mensaje para reportarlo.", fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), textAlign = TextAlign.Center)
        }

        val msgs = messages
        if (msgs == null) {
            Column(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 12.dp)) { SkeletonRows(4, 52.dp) }
        } else if (msgsMissing && msgs.isEmpty()) {
            Column(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 12.dp)) { UnavailableInline(repo, reloader::retry) }
        } else {
            Refreshable(onRefresh = { messages = mergeShown(messages, repo.messages(chat.id)) }, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 8.dp)) {
                // Separador de día (estilo WhatsApp): la fecha vive aquí, no en cada mensaje.
                var lastDay: kotlinx.datetime.LocalDate? = null
                if (msgs.isEmpty()) {
                    EmptyState(
                        LineIcon.CHAT, "Aún no hay mensajes",
                        if (chat.archived) "Este chat se archivó sin mensajes." else "Escribe el primero.", compact = true,
                    )
                }
                if (hasOlder && msgs.isNotEmpty()) {
                    OlderMessagesButton(repo, loadingOlder, olderFailed, onClick = ::loadOlder)
                }
                msgs.forEach { m ->
                    val d = m.at.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
                    if (d != lastDay) { lastDay = d; DaySeparator(d) }
                    if (m.senderId != null && m.senderId in blockedIds && m.id !in revealed) {
                        BlockedMessageLine { revealed.add(m.id) }
                    } else {
                        MessageBubble(repo, m, myId, onOpenImage, onOpenProfile, onLongPress = { menuFor = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            }
        }

        error?.let {
            Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = Danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), textAlign = TextAlign.Center)
        }
        info?.let {
            Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = Live, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), textAlign = TextAlign.Center)
        }
        if (sendingMedia) {
            Text("Enviando foto…", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center)
        }

        if (chat.archived) {
            Box(Modifier.fillMaxWidth().background(Panel).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("No puedes escribir en un chat archivado", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Adjuntar foto (cámara/galería)
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(PanelElevA)
                        .border(1.dp, BorderStrong, RoundedCornerShape(14.dp))
                        .clickable(enabled = !sendingMedia) { showAttach = true },
                    contentAlignment = Alignment.Center,
                ) { CameraGlyph(if (sendingMedia) TextFaint else TextSub, Modifier.size(width = 22.dp, height = 20.dp)) }
                Box(Modifier.weight(1f)) {
                    AppTextField(value = draft, onValueChange = { draft = it }, placeholder = "Mensaje…", maxLength = com.alephri.elpuesto.data.TextLimits.MESSAGE, showCounter = true)
                }
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (draft.isBlank() || sending) BorderStrong else Amber)
                        .clickable(enabled = draft.isNotBlank() && !sending) {
                            sending = true
                            error = null
                            scope.launch {
                                val sent = repo.sendMessage(chat.id, draft.trim())
                                sending = false
                                if (sent == null) {
                                    error = "No se pudo enviar. Revisa tu conexión."
                                } else {
                                    draft = ""
                                    messages = mergeShown(messages, repo.messages(chat.id))
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("↑", color = OnAmber, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            }
        }
    }

    // Mini sheet: origen de la foto.
    if (showAttach) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xA00A0C10)).clickable { showAttach = false }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF20242E))
                    .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(enabled = false) {}
                    .navigationBarsPadding().padding(bottom = 18.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(androidx.compose.ui.graphics.Color(0xFF3F4756)))
                Text("Enviar una foto", fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
                AttachOption("Tomar con la cámara") {
                    showAttach = false
                    camera()
                }
                AttachOption("Elegir de la galería") {
                    showAttach = false
                    gallery()
                }
            }
        }
    }

    // Sheet de moderación (long-press en una burbuja): reportar / silenciar el chat.
    menuFor?.let { m ->
        val own = m.isOwn(myId)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xA00A0C10)).clickable { menuFor = null }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF20242E))
                    .border(1.dp, BorderStrong, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(enabled = false) {}
                    .navigationBarsPadding().padding(bottom = 18.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(androidx.compose.ui.graphics.Color(0xFF3F4756)))
                Text(
                    if (own) "Tu mensaje" else "Mensaje de ${m.senderName}",
                    fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = TextHi,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
                if (!own && !m.isFromControl() && !chat.archived) {
                    AttachOption("Reportar mensaje") {
                        menuFor = null
                        reportFor = m
                    }
                }
                AttachOption(if (muted) "Reactivar notificaciones del chat" else "Silenciar chat") {
                    menuFor = null
                    val newMuted = !muted
                    scope.launch {
                        repo.setChatMuted(chat.id, newMuted)
                        muted = newMuted
                        info = if (newMuted) "Chat silenciado: no te notificará en este dispositivo." else "Notificaciones del chat reactivadas."
                    }
                }
            }
        }
    }

    // Sheet de reporte: motivo opcional → POST al backend (lo revisa el admin).
    reportFor?.let { m ->
        com.alephri.elpuesto.ui.components.ReportSheet(
            repo, "Reportar mensaje",
            subject = (if (m.mediaType != null) "📷 Foto" else "“${m.text.take(120)}”") + " — ${m.senderName}",
            note = "El reporte queda registrado y lo revisa el administrador. El mensaje no se elimina.",
            onDismiss = { reportFor = null },
            submit = { reason -> repo.reportMessage(chat.id, m.id, reason) },
            onResult = { problem ->
                reportFor = null
                if (problem == null) {
                    error = null
                    info = "Mensaje reportado. Gracias por avisar."
                } else {
                    info = null
                    error = "No se pudo reportar: $problem"
                }
            },
        )
    }
    }
}

@Composable
private fun AttachOption(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = TextHi, modifier = Modifier.weight(1f))
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    repo: AppRepository,
    m: Message,
    myId: String?,
    onOpenImage: (String) -> Unit,
    onOpenProfile: (String?) -> Unit,
    onLongPress: (Message) -> Unit = {},
) {
    if (m.system) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(m.text, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint)
        }
        return
    }
    // Propio SOLO por id (nunca por nombre: un oficial llamado "Tú" se vería como uno
    // mismo); el eco local de lo encolado también es propio.
    val own = m.isOwn(myId)
    // Control (el admin durante el evento) = sin oficial y no de sistema: lo marca el
    // SERVIDOR, así que nadie se hace pasar por él poniéndose ese nombre.
    val control = !own && m.isFromControl()
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        if (!own) {
            val ctx = listOfNotNull(m.senderPuesto, m.senderRole).joinToString(" · ")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 36.dp, bottom = 2.dp),
            ) {
                Text(m.senderName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = if (control) Amber else TextSub)
                if (control) {
                    Text(
                        "CONTROL", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = OnAmber,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Amber).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                } else if (ctx.isNotEmpty()) {
                    Text(ctx, fontFamily = PlexMonoFamily, fontSize = 9.5.sp, color = Amber.copy(alpha = 0.85f))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!own) SenderAvatar(repo, m) { onOpenProfile(m.senderId) }
            val isImage = m.mediaType == MessageMediaType.IMAGE
            Column(
                Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            own -> Amber
                            control -> Amber.copy(alpha = 0.12f).compositeOver(PanelElevA)
                            else -> PanelElevA
                        },
                    )
                    .border(if (control) 1.5.dp else 1.dp, if (own || control) Amber else BorderStrong, RoundedCornerShape(14.dp))
                    // Long-press = menú de moderación (reportar / silenciar el chat).
                    .combinedClickable(onClick = {}, onLongClick = { onLongPress(m) })
                    .padding(if (isImage) 4.dp else 0.dp)
                    .padding(horizontal = if (isImage) 0.dp else 12.dp, vertical = if (isImage) 0.dp else 9.dp),
            ) {
                if (isImage) {
                    ChatImageThumb(repo, m.id, onLongClick = { onLongPress(m) }) { onOpenImage("/images/chatmedia/${m.id}/full") }
                }
                if (m.text.isNotBlank()) {
                    Text(
                        m.text, fontFamily = PlexSansFamily, fontSize = 13.5.sp,
                        color = if (own) OnAmber else TextPrimary,
                        modifier = if (isImage) Modifier.padding(horizontal = 8.dp, vertical = 6.dp) else Modifier,
                    )
                }
            }
            if (own) SenderAvatar(repo, m) { onOpenProfile(null) }
        }
        Text(hhmm(m.at), fontFamily = PlexMonoFamily, fontSize = 9.sp, color = TextFaint, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

/** Página de mensajes que se pide al servidor (al abrir y hacia atrás). */
private const val CHAT_PAGE = 100

/**
 * Lo que la conversación muestra, unido con lo que acaba de llegar ([incoming]: la cola de
 * la caché, una página anterior o lo nuevo): sin repetidos y en orden. Lo ya mostrado no
 * se pierde (así una recarga de la cola no borra las páginas anteriores ya cargadas),
 * salvo los ecos locales ("local-…") que ya no vengan: esos mensajes ya se enviaron.
 */
private fun mergeShown(shown: List<Message>?, incoming: List<Message>): List<Message> {
    if (shown == null) return incoming
    val incomingIds = incoming.map { it.id }.toSet()
    val keep = shown.filter { !it.id.startsWith("local-") || it.id in incomingIds }
    return (keep + incoming).distinctBy { it.id }
        // Los del servidor por hora; los encolados sin señal, siempre al final.
        .sortedWith(compareBy<Message>({ it.id.startsWith("local-") }, { it.at }, { it.id }))
}

/** "Cargar mensajes anteriores": pide a la red la página previa (exige conexión). */
@Composable
private fun OlderMessagesButton(repo: AppRepository, loading: Boolean, failed: Boolean, onClick: () -> Unit) {
    val online = rememberOnline(repo)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (loading) "Cargando…" else "Cargar mensajes anteriores",
            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
            color = if (online && !loading) Amber else TextFaint,
            modifier = Modifier.clip(RoundedCornerShape(100.dp)).background(Panel)
                .border(1.dp, Border, RoundedCornerShape(100.dp))
                .clickable(enabled = online && !loading) { onClick() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
        when {
            !online -> NeedsConnectionNote("Ver mensajes anteriores necesita conexión.")
            failed -> Text("No se pudieron cargar. Intenta de nuevo.", fontFamily = PlexSansFamily, fontSize = 11.sp, color = Danger, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Mensaje de un oficial que bloqueaste: una línea tenue; "Ver" lo muestra. */
@Composable
private fun BlockedMessageLine(onReveal: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { onReveal() }
            .padding(horizontal = 36.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LineIconView(LineIcon.LOCK, TextFaint, size = 12.dp)
        Text("Mensaje de un oficial bloqueado", fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint)
        Text("· Ver", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = TextMut)
    }
}

/** Separador de día entre mensajes (Hoy / Ayer / fecha), estilo WhatsApp. */
@Composable
private fun DaySeparator(d: kotlinx.datetime.LocalDate) {
    val hoy = kotlinx.datetime.Clock.System.now()
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    val label = when (d) {
        hoy -> "Hoy"
        hoy.minus(kotlinx.datetime.DatePeriod(days = 1)) -> "Ayer"
        else -> "${d.dayOfMonth} de ${meses[d.monthNumber - 1]}" + (if (d.year != hoy.year) " ${d.year}" else "")
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = TextFaint,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Panel)
                .border(1.dp, Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Avatar del remitente (a ambos lados: también en los mensajes propios). Tocarlo abre su
 * perfil; los mensajes sin oficial (Control) no llevan a ningún lado.
 */
@Composable
private fun SenderAvatar(repo: AppRepository, m: Message, onClick: () -> Unit) {
    val sender = m.senderId
    if (sender != null) {
        Box(Modifier.clip(CircleShape).clickable(onClick = onClick)) {
            RemoteAvatar(repo, "/images/avatar/$sender/full", initials(m.senderName), size = 26.dp, bg = Panel, textColor = TextPrimary)
        }
    } else if (m.isFromControl()) {
        // Control: bandera en ámbar (no las iniciales de un nombre que cualquiera podría usar).
        Box(Modifier.size(26.dp).clip(CircleShape).background(Amber), contentAlignment = Alignment.Center) {
            LineIconView(LineIcon.FLAG, OnAmber, size = 15.dp)
        }
    } else {
        Avatar(initials(m.senderName), size = 26.dp, bg = Panel, textColor = TextPrimary)
    }
}

/** Mensaje propio: por id del remitente, o el eco local de uno encolado sin señal. */
private fun Message.isOwn(myId: String?): Boolean =
    (myId != null && senderId == myId) || id.startsWith("local-")

/** Mensaje de Control (admin): sin oficial y sin ser de sistema — lo decide el servidor. */
private fun Message.isFromControl(): Boolean = senderId == null && !system && !id.startsWith("local-")

/** Miniatura de la imagen del mensaje; toca para verla completa (long-press = moderación). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatImageThumb(repo: AppRepository, messageId: String, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    when (val img = rememberRemoteImage(repo, "/images/chatmedia/$messageId/full")) {
        is ImageLoad.Ready -> androidx.compose.foundation.Image(
            bitmap = img.bitmap, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 230.dp, height = 170.dp).clip(RoundedCornerShape(11.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        )
        ImageLoad.Loading -> SkeletonBox(Modifier.size(width = 230.dp, height = 170.dp), corner = 11.dp)
        else -> Box(
            Modifier.size(width = 230.dp, height = 90.dp).clip(RoundedCornerShape(11.dp)).background(Panel),
            contentAlignment = Alignment.Center,
        ) { Text("Foto sin conexión", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextFaint) }
    }
}

// ————————————————————— Detalles del chat —————————————————————

@Composable
fun ChatDetailScreen(
    repo: AppRepository,
    chat: Chat,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit = {},
    onClosed: () -> Unit = {},
    onOpenChat: (Chat) -> Unit = {},
    /** Tocar la imagen del chat: verla en grande (ruta de la variante `full`). */
    onOpenImage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var members by remember(chat.id) { mutableStateOf<List<ChatMember>?>(null) }
    var myId by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var membersMissing by remember(chat.id) { mutableStateOf(false) }
    // Evento ligado (públicos/privados): el creador lo cambia aquí.
    var linkedEventId by remember(chat.id) { mutableStateOf(chat.eventId) }
    var linkedEventName by remember(chat.id) { mutableStateOf(chat.eventName) }
    var pickingEvent by remember { mutableStateOf(false) }
    var reportingChat by remember(chat.id) { mutableStateOf(false) }
    val myEvents = rememberMyEvents(repo)
    val reloader = rememberReloader(repo)
    BackHandler { onBack() }
    BackHandler(enabled = reportingChat) { reportingChat = false }
    LaunchedEffect(chat.id, reloader.key) {
        myId = repo.profile(null)?.officer?.id
        val t = reloader.track { repo.chatMembers(chat.id) }
        members = t.value
        membersMissing = t.missed && t.value.isEmpty()
    }
    val isGroup = chat.isGroup()
    // Invitación pendiente (privado o público): ves quiénes están y decides.
    val pendingInvite = chat.isPendingInvite()
    val isCreator = isGroup && myId != null && myId == chat.creatorId
    // La imagen la puede cambiar cualquier miembro de un grupo (público/privado) o
    // cualquiera de la posición en uno de puesto (solo ellos lo ven); archivar solo el creador.
    val canEditImage = !chat.archived && ((isGroup && chat.joined) || chat.type == ChatType.PUESTO)

    // Imagen del chat: elegir + recorte cuadrado (≤1024 px) → subir (se repinta sola al guardarse).
    val pickImage = rememberSquareImagePicker(maxSide = 1024) { bytes ->
        if (bytes != null) {
            uploading = true
            scope.launch {
                if (!repo.uploadChatImage(chat.id, bytes)) {
                    error = repo.takeUploadProblem() ?: "No se pudo subir la imagen."
                }
                uploading = false
            }
        }
    }

    val online = rememberOnline(repo)
    val joinedMembers = members?.filter { !it.pending }
    val pendingMembers = members?.filter { it.pending }.orEmpty()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        TopBar("DETALLES DEL CHAT", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            RemoteAvatar(repo, chatImageUrl(chat), chatInitial(chat), size = 96.dp, bg = Panel, textColor = Amber, onOpen = onOpenImage)
            Spacer(Modifier.height(14.dp))
            Text(chat.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, color = TextHi, textAlign = TextAlign.Center)
            chat.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatTypeTag(chat.type)
                joinedMembers?.let { Text(if (it.size == 1) "1 participante" else "${it.size} participantes", fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut) }
            }
            if (chat.type == ChatType.PRIVATE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chat privado: solo sus miembros lo ven. Se entra por invitación.",
                    fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint, textAlign = TextAlign.Center,
                )
            }
            if (pendingInvite) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Amber.copy(alpha = 0.08f))
                        .border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp),
                ) {
                    Text(
                        "${chat.invitedBy ?: "Alguien"} te invitó a este chat. " +
                            if (chat.type == ChatType.PRIVATE) "Verás la conversación cuando aceptes." else "Entras al chat cuando aceptes.",
                        fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextPrimary,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!online) NeedsConnectionNote("Aceptar o rechazar una invitación necesita conexión.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (online && !busy) Amber else Panel)
                                .clickable(enabled = online && !busy) {
                                    busy = true
                                    scope.launch {
                                        if (repo.setChatJoined(chat.id, true)) {
                                            val updated = repo.chats().firstOrNull { it.id == chat.id } ?: chat.copy(joined = true, invitedBy = null)
                                            onOpenChat(updated)
                                        } else { busy = false; error = "No se pudo aceptar la invitación." }
                                    }
                                }.padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aceptar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = if (online) OnAmber else TextFaint) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, BorderStrong, RoundedCornerShape(10.dp))
                                .clickable(enabled = online && !busy) {
                                    busy = true
                                    scope.launch {
                                        if (repo.setChatJoined(chat.id, false)) onBack() else { busy = false; error = "No se pudo rechazar la invitación." }
                                    }
                                }.padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Rechazar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = if (online) TextSub else TextFaint) }
                    }
                }
            }
            if (canEditImage) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(enabled = online && !uploading) {
                            pickImage()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(if (uploading) "Subiendo…" else "Cambiar imagen", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (online) Amber else TextFaint)
                }
            }

            Column(Modifier.fillMaxWidth()) {
                // Evento ligado (grupos): aparece en su Modo evento. El creador lo cambia.
                if (isGroup && !chat.archived && (linkedEventName != null || isCreator)) {
                    Section("Evento")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            linkedEventName ?: "Sin evento",
                            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            color = if (linkedEventName != null) TextPrimary else TextMut, modifier = Modifier.weight(1f),
                        )
                        if (isCreator) {
                            Text(
                                if (pickingEvent) "Cancelar" else "Cambiar",
                                fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = if (online) Amber else TextFaint,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = online) { pickingEvent = !pickingEvent }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Text(
                        "Ligado a un evento, el chat aparece también en su Modo evento.",
                        fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextFaint,
                    )
                    if (pickingEvent) {
                        Spacer(Modifier.height(6.dp))
                        EventPicker(myEvents, linkedEventId) { id ->
                            scope.launch {
                                if (repo.setChatEvent(chat.id, id)) {
                                    linkedEventId = id
                                    linkedEventName = id?.let { e -> myEvents?.firstOrNull { it.first == e }?.second }
                                    pickingEvent = false
                                    error = null
                                } else {
                                    error = "No se pudo cambiar el evento. Revisa tu conexión."
                                }
                            }
                        }
                    }
                }

                Section("Participantes")
                val list = joinedMembers
                if (list == null) {
                    SkeletonRows(3, 52.dp)
                } else if (membersMissing) {
                    UnavailableInline(repo, reloader::retry)
                } else if (list.isEmpty()) {
                    Text("Sin participantes registrados.", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
                } else {
                    list.forEach { m -> MemberRow(repo, chat, m) { onOpenProfile(m.officer.id) } }
                }

                // Invitados que aún no aceptan (transparencia para el grupo).
                if (pendingMembers.isNotEmpty()) {
                    Section("Invitaciones pendientes")
                    pendingMembers.forEach { m -> MemberRow(repo, chat, m) { onOpenProfile(m.officer.id) } }
                }

                // Invitar oficiales (grupos donde eres miembro): públicos y privados por igual,
                // le llega una invitación que acepta o rechaza (nadie entra sin aceptar).
                if (isGroup && chat.joined && !chat.archived) {
                    InviteOfficerSection(
                        repo,
                        current = members.orEmpty().map { it.officer.id }.toSet(),
                        note = "Le llegará una invitación; entra al chat cuando la acepte.",
                        onInvite = { o ->
                            scope.launch {
                                val problem = repo.addChatMember(chat.id, o.id)
                                if (problem == null) {
                                    error = null
                                    info = "Invitación enviada a ${o.displayName}."
                                    members = repo.chatMembers(chat.id)
                                } else {
                                    info = null
                                    error = if (problem == "sin conexión") "No se pudo invitar a ${o.displayName}. Revisa tu conexión."
                                        else "No se pudo invitar a ${o.displayName}: $problem"
                                }
                            }
                        },
                    )
                }

                error?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger)
                }
                info?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Live)
                }

                Spacer(Modifier.height(22.dp))
                if (isGroup && !chat.archived && !pendingInvite) {
                    // Archivar, salir, invitar y cambiar la imagen exigen conexión (no van por la cola).
                    if (!online) NeedsConnectionNote("Estas opciones necesitan conexión.")
                    if (isCreator) {
                        OptionButton("Archivar chat", Danger, enabled = online && !busy) {
                            busy = true
                            scope.launch {
                                if (repo.archiveChat(chat.id)) onClosed() else { busy = false; error = "No se pudo archivar." }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (chat.joined) {
                        OptionButton("Salir del chat", Danger, enabled = online && !busy) {
                            busy = true
                            scope.launch {
                                if (repo.setChatJoined(chat.id, false)) onClosed() else { busy = false; error = "No se pudo salir del chat." }
                            }
                        }
                    }
                }
                // Reportar el chat (nombre, imagen o descripción): cualquier tipo, también una
                // invitación. Exige conexión.
                Spacer(Modifier.height(10.dp))
                OptionButton("Reportar chat", TextSub, enabled = online) { reportingChat = true }
                if (!online && !(isGroup && !chat.archived && !pendingInvite)) NeedsConnectionNote("Reportar necesita conexión.")
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    if (reportingChat) {
        com.alephri.elpuesto.ui.components.ReportSheet(
            repo, "Reportar chat",
            subject = chat.name,
            note = "Reporta un nombre, imagen o descripción inapropiados. Queda registrado y lo revisa el administrador.",
            onDismiss = { reportingChat = false },
            submit = { reason -> repo.reportChat(chat.id, reason) },
            onResult = { problem ->
                reportingChat = false
                if (problem == null) {
                    error = null
                    info = "Chat reportado. Gracias por avisar."
                } else {
                    info = null
                    error = "No se pudo reportar: $problem"
                }
            },
        )
    }
    }
}

/** Participante (o invitado pendiente) con su contexto operativo; toca → perfil. */
@Composable
private fun MemberRow(repo: AppRepository, chat: Chat, m: ChatMember, onClick: () -> Unit) {
    val o = m.officer
    // En el chat del puesto el puesto es obvio → solo el rol; en los demás "P N · Rol".
    val ctx = if (chat.type == ChatType.PUESTO) m.role.orEmpty()
    else listOfNotNull(m.puesto, m.role).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp).alpha(if (m.pending) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(repo, "/images/avatar/${o.id}/full", initials(o.displayName), size = 40.dp, bg = Panel, textColor = TextPrimary)
        // Rol DEBAJO del nombre (en la misma línea, nombres o roles largos partían el renglón).
        Column(Modifier.weight(1f)) {
            Text(o.displayName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            if (ctx.isNotEmpty()) {
                Text(ctx, fontFamily = PlexMonoFamily, fontSize = 11.sp, color = Amber.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (m.pending) Tag("Pendiente", container = Panel, contentColor = TextMut, border = Border)
        else Text("›", color = TextFaint, fontSize = 22.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

/** "+ Invitar oficial": despliega el buscador; tocar un resultado invita ([note] explica qué pasa). */
@Composable
private fun InviteOfficerSection(repo: AppRepository, current: Set<String>, note: String, onInvite: (Officer) -> Unit) {
    val online = rememberOnline(repo)
    var open by remember { mutableStateOf(false) }

    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (open) Amber else BorderStrong, RoundedCornerShape(12.dp))
            .clickable(enabled = online || open) { open = !open }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (open) "Cancelar" else "+ Invitar oficial",
            fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (online || open) Amber else TextFaint,
        )
    }
    if (!open) return
    if (!online) { NeedsConnectionNote("Invitar oficiales necesita conexión."); return }
    Spacer(Modifier.height(10.dp))
    OfficerSearch(repo, exclude = current, actionLabel = "Invitar") { o -> onInvite(o); open = false }
    Spacer(Modifier.height(6.dp))
    Text(note, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint)
}

/**
 * Buscador de oficiales por nombre u OMDAI ID (desde 3 letras o números, hasta 10
 * resultados — [com.alephri.elpuesto.data.OfficerSearchRules]). Tocar un resultado lo
 * elige; [exclude] = ya elegidos/miembros.
 */
@Composable
private fun OfficerSearch(repo: AppRepository, exclude: Set<String>, actionLabel: String, onPick: (Officer) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Officer>?>(null) } // null = aún sin buscar

    // Debounce de tecleo: busca 350ms después de la última letra (cancelable).
    LaunchedEffect(query) {
        val q = query.trim()
        if (!com.alephri.elpuesto.data.OfficerSearchRules.ready(q)) { results = null; searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(350)
        results = repo.searchOfficers(q)
        searching = false
    }

    AppTextField(value = query, onValueChange = { query = it }, placeholder = com.alephri.elpuesto.data.OfficerSearchRules.PLACEHOLDER, maxLength = com.alephri.elpuesto.data.TextLimits.SEARCH)
    if (!com.alephri.elpuesto.data.OfficerSearchRules.ready(query)) {
        Spacer(Modifier.height(6.dp))
        Text(com.alephri.elpuesto.data.OfficerSearchRules.HINT, fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextFaint)
    }
    Spacer(Modifier.height(8.dp))
    val visible = results?.filter { it.id !in exclude }
    when {
        searching -> SkeletonRows(2, 48.dp)
        results == null -> {} // sin búsqueda todavía
        visible.isNullOrEmpty() ->
            Text("Sin resultados (o ya están en el chat).", fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut)
        else -> visible.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onPick(o)
                        query = ""; results = null
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
                Text(actionLabel, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Amber)
            }
        }
    }
}

@Composable
private fun OptionButton(text: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = color)
    }
}

private fun chatInitial(chat: Chat): String = if (chat.type == ChatType.PUBLIC) "#" else chatAvatarLabel(chat)

/**
 * Texto del avatar de un chat sin imagen: en los de PUESTO, la etiqueta de la posición
 * ("Puesto 11.7" → 11.7, "TH3", "IFRT12"); en los demás, las iniciales del nombre.
 */
internal fun chatAvatarLabel(chat: Chat): String =
    if (chat.type == ChatType.PUESTO) chat.name.removePrefix("Puesto ").replace(" ", "").take(6)
    else initials(chat.name)

/** Imagen del chat: los de evento muestran la imagen del EVENTO; el resto la suya propia. */
internal fun chatImageUrl(chat: Chat): String =
    if (chat.type == ChatType.EVENT && chat.eventId != null) "/images/event/${chat.eventId}/full"
    else "/images/chatimg/${chat.id}/full"

// ————————————————————— Piezas —————————————————————

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = Amber, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

@Composable
private fun IconChip(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(11.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Amber, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(42.dp))
    }
}

private val MX = TimeZone.of("America/Mexico_City")
private fun hhmm(at: Instant): String {
    val dt = at.toLocalDateTime(MX)
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
