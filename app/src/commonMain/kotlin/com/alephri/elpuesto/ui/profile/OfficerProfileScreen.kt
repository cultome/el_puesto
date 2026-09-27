package com.alephri.elpuesto.ui.profile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.data.EmergencyView
import com.alephri.elpuesto.data.ProfileUi
import com.alephri.elpuesto.model.EmergencyInfo
import com.alephri.elpuesto.model.OfficerHistoryEntry
import com.alephri.elpuesto.ui.components.rememberOnline
import com.alephri.elpuesto.ui.components.NeedsConnectionNote
import com.alephri.elpuesto.ui.components.Avatar
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.UnavailableScreen
import com.alephri.elpuesto.ui.components.rememberReloader
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.components.TopBarIconButton
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
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
import kotlinx.datetime.LocalDate

/**
 * Perfil de un oficial. Dos variantes según [ProfileUi.isSelf]:
 *  - **propio**: emergencia visible + registro de accesos + historial de eventos.
 *  - **otro**: sin acciones sociales; emergencia bloqueada (auditable) + eventos en común.
 *
 * [officerId] null = perfil propio. [onBack] null = modo pestaña (sin flecha atrás,
 * la barra inferior la pone el shell); no-null = detalle apilado con botón atrás.
 */
@Composable
fun OfficerProfileScreen(
    repo: AppRepository,
    officerId: String?,
    onBack: (() -> Unit)? = null,
    onEditProfile: () -> Unit = {},
    onEditEmergency: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Abre un evento pasado; otherName null = historial propio, si no = evento en común. */
    onOpenHistory: (OfficerHistoryEntry, String?) -> Unit = { _, _ -> },
    /** "Ver todo": lista completa (officerId null = propia; otherName = el oficial visto). */
    onOpenHistoryList: (officerId: String?, otherName: String?) -> Unit = { _, _ -> },
    /** Pasaporte / todos los logros (officerId null = los propios). */
    onOpenPassport: (officerId: String?) -> Unit = {},
    onOpenAchievements: (officerId: String?) -> Unit = {},
    /** Tocar la foto: verla en grande (recibe la ruta de la variante `full`). */
    onOpenImage: (String) -> Unit = {},
) {
    var profile by remember(officerId) { mutableStateOf<ProfileUi?>(null) }
    // Catálogo de circuitos: las siluetas de México del pasaporte (caché primero).
    var circuits by remember { mutableStateOf<List<com.alephri.elpuesto.model.Circuit>>(emptyList()) }
    var unavailable by remember(officerId) { mutableStateOf(false) }
    // ¿Lo bloqueé? (perfil ajeno; de la lista de bloqueados, caché primero).
    var blocked by remember(officerId) { mutableStateOf(false) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(officerId, reloader.key) {
        unavailable = false
        val t = reloader.track {
            Triple(repo.profile(officerId), repo.circuits(), if (officerId != null) repo.blocks() else emptyList())
        }
        profile = t.value.first
        circuits = t.value.second
        blocked = officerId != null && t.value.third.any { it.id == officerId }
        // Sin red ni caché: pantalla "sin conexión" en vez de skeleton eterno.
        unavailable = t.value.first == null && t.missed
        // El perfil no hace peticiones (datos locales); sondeamos /health para que el
        // banner de conexión refleje el estado real y no el último conocido.
        repo.checkConnectivity()
    }
    if (unavailable) {
        UnavailableScreen(repo, "Oficial", onBack, reloader::retry)
        return
    }
    if (onBack != null) BackHandler { onBack() }
    // Perfil ajeno: menú "⋯" (reportar el perfil, bloquear) y sus hojas/confirmaciones.
    var menuOpen by remember(officerId) { mutableStateOf(false) }
    var reportingProfile by remember(officerId) { mutableStateOf(false) }
    // Confirmación pendiente: true = bloquear, false = desbloquear.
    var confirmBlock by remember(officerId) { mutableStateOf<Boolean?>(null) }
    var blockBusy by remember(officerId) { mutableStateOf(false) }
    val online = rememberOnline(repo)
    val scope = rememberCoroutineScope()
    // Resultado de la última acción del menú: (texto, ¿error?).
    var notice by remember(officerId) { mutableStateOf<Pair<String, Boolean>?>(null) }
    BackHandler(enabled = menuOpen || reportingProfile) { menuOpen = false; reportingProfile = false }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(screenBackground())) {
        val p = profile
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) BackButton(onBack) else Spacer(Modifier.size(42.dp))
            Text(
                if (p?.isSelf == true) "PERFIL" else "OFICIAL",
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = TextHi,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (p?.isSelf == true) {
                // Mi perfil: engrane (Configuración) + Editar.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBarIconButton("⚙", onOpenSettings)
                    Text(
                        "Editar", color = Amber, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
                            .clickable { onEditProfile() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            } else if (p != null) {
                TopBarIconButton("⋯") { menuOpen = true }
            } else {
                Spacer(Modifier.size(42.dp))
            }
        }

        if (p == null) {
            // Cargando: skeleton estructural (header, stats, tarjeta, historial).
            Column(Modifier.weight(1f).padding(horizontal = 22.dp).padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SkeletonBox(Modifier.size(72.dp), corner = 36.dp)
                    Column {
                        SkeletonBox(Modifier.size(width = 160.dp, height = 20.dp))
                        Spacer(Modifier.height(8.dp))
                        SkeletonBox(Modifier.size(width = 100.dp, height = 12.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(84.dp), corner = 16.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(220.dp), corner = 18.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonRows(3, 56.dp)
            }
        } else {
            Refreshable(
                onRefresh = { profile = repo.profile(officerId); circuits = repo.circuits(); repo.checkConnectivity() },
                modifier = Modifier.weight(1f),
            ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            ) {
                Header(p, repo, onOpenImage)
                if (!p.isSelf && blocked) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Lo bloqueaste: no puede invitarte a chats ni agregarte para compartir ubicación, y sus mensajes se ven ocultos.",
                        fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut,
                    )
                }
                notice?.let { (text, isError) ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                        color = if (isError) com.alephri.elpuesto.ui.theme.Danger else com.alephri.elpuesto.ui.theme.Live,
                    )
                }
                Spacer(Modifier.height(20.dp))
                StatsRow(p)
                // Logros (gamificación): del otro oficial el backend ya recortó a lo público.
                com.alephri.elpuesto.ui.achievements.ProfileAchievementsSection(
                    repo, p.achievements, circuits, p.isSelf,
                    onOpenPassport = { onOpenPassport(if (p.isSelf) null else p.officer.id) },
                    onOpenAll = { onOpenAchievements(if (p.isSelf) null else p.officer.id) },
                )
                Spacer(Modifier.height(8.dp))
                if (p.isSelf) EmergencySelf(p.emergency, onEditEmergency) else EmergencyOther(repo, p.officer)
                // Derivados de las asignaciones de eventos terminados: el propio ve su
                // historial; de otro oficial SOLO los eventos donde coincidieron.
                val otherName = if (p.isSelf) null else p.officer.displayName
                val more = p.history.size > HISTORY_PREVIEW
                SectionHeader(
                    if (p.isSelf) "Historial de eventos" else "Eventos en común",
                    trailing = if (more) "Ver todo (${p.history.size})" else null,
                    onTrailingClick = if (more) ({ onOpenHistoryList(officerId, otherName) }) else null,
                )
                if (p.history.isEmpty()) {
                    Text(
                        if (p.isSelf) "Aún no tienes eventos terminados." else "Aún no han coincidido en un evento.",
                        fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    p.history.take(HISTORY_PREVIEW).forEach { h -> HistoryRow(h, otherName) { onOpenHistory(h, otherName) } }
                }
                Spacer(Modifier.height(28.dp))
            }
            }
        }
    }
    val shown = profile
    if (menuOpen && shown != null && !shown.isSelf) {
        com.alephri.elpuesto.ui.components.BottomSheet(onDismiss = { menuOpen = false }) {
            com.alephri.elpuesto.ui.components.SheetTitle(shown.officer.displayName)
            com.alephri.elpuesto.ui.components.SheetOption("Reportar perfil") {
                menuOpen = false
                reportingProfile = true
            }
            com.alephri.elpuesto.ui.components.SheetOption(
                if (blocked) "Desbloquear" else "Bloquear",
                color = if (blocked) TextHi else com.alephri.elpuesto.ui.theme.Danger,
                enabled = online && !blockBusy,
            ) {
                menuOpen = false
                confirmBlock = !blocked
            }
            if (!online) NeedsConnectionNote("Bloquear o desbloquear necesita conexión.", Modifier.padding(horizontal = 28.dp))
        }
    }
    val target = confirmBlock
    if (target != null && shown != null) {
        val name = shown.officer.displayName
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmBlock = null },
            containerColor = Panel,
            title = { Text(if (target) "¿Bloquear a $name?" else "¿Desbloquear a $name?", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, color = TextHi) },
            text = {
                Text(
                    if (target) {
                        "Ya no podrá invitarte a ningún chat ni agregarte a su lista para compartir ubicación, y no te llegarán avisos suyos. " +
                            "En los chats donde coinciden, sus mensajes se verán ocultos (puedes abrirlos). " +
                            "Lo desbloqueas cuando quieras desde aquí o en Configuración → Privacidad y datos."
                    } else {
                        "Podrá volver a invitarte a chats y a agregarte a su lista para compartir ubicación, y verás sus mensajes."
                    },
                    fontFamily = PlexSansFamily, color = TextSub,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmBlock = null
                    blockBusy = true
                    scope.launch {
                        val problem = repo.setBlocked(shown.officer.id, target)
                        blockBusy = false
                        if (problem == null) {
                            blocked = target
                            notice = (if (target) "Bloqueaste a $name." else "Desbloqueaste a $name.") to false
                        } else {
                            notice = "No se pudo ${if (target) "bloquear" else "desbloquear"}: $problem" to true
                        }
                    }
                }) {
                    Text(if (target) "Bloquear" else "Desbloquear", color = if (target) com.alephri.elpuesto.ui.theme.Danger else Amber, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmBlock = null }) { Text("Cancelar", color = TextSub) }
            },
        )
    }
    if (reportingProfile && shown != null) {
        com.alephri.elpuesto.ui.components.ReportSheet(
            repo, "Reportar perfil",
            subject = "${shown.officer.displayName} · OMDAI #${shown.officer.omdaiId}",
            note = "Reporta un nombre o una foto inapropiados. Queda registrado y lo revisa el administrador; ${shown.officer.displayName.substringBefore(' ')} no sabrá quién lo reportó.",
            onDismiss = { reportingProfile = false },
            submit = { reason -> repo.reportOfficer(shown.officer.id, reason) },
            onResult = { problem ->
                reportingProfile = false
                notice = if (problem == null) "Perfil reportado. Gracias por avisar." to false
                else "No se pudo reportar: $problem" to true
            },
        )
    }
    }
}

@Composable
private fun Header(p: ProfileUi, repo: com.alephri.elpuesto.data.AppRepository, onOpenImage: (String) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RemoteAvatar(repo, p.officer.avatarUrl, initials(p.officer.displayName), size = 72.dp, onOpen = onOpenImage)
        Column {
            Text(p.officer.displayName, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = TextHi)
            Spacer(Modifier.height(4.dp))
            Text("OMDAI #${p.officer.omdaiId}", fontFamily = PlexMonoFamily, fontSize = 12.5.sp, color = TextSub, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(8.dp))
            p.officer.assignedArea?.let { Tag(it.display()) }
        }
    }
}

@Composable
private fun StatsRow(p: ProfileUi) {
    val s = p.officer.stats
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(16.dp)).padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("${s.events}", "Eventos")
        // En el perfil ajeno, "Juntos" (eventos en común) sustituye a la temporada.
        if (p.isSelf) Stat("${s.thisSeason}", "Esta temporada") else Stat("${s.together ?: 0}", "Juntos")
        Stat(s.activeSince?.toString() ?: "—", "Activo desde")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Amber)
        Spacer(Modifier.height(3.dp))
        Text(label, fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut)
    }
}

@Composable
private fun EmergencySelf(info: EmergencyInfo?, onEditEmergency: () -> Unit) {
    // Con la emergencia en pantalla: sin capturas ni miniatura en "recientes".
    com.alephri.elpuesto.ui.components.SecureWindow()
    SectionHeader("Información de emergencia")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PanelElevA)
            .border(1.dp, BorderStrong, RoundedCornerShape(18.dp)).padding(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelChip("PRIVADA")
            LabelChip("SOLO EMERGENCIAS")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Visible para tu jefe de puesto solo durante un evento activo. Cada acceso queda registrado; puedes revisarlo en Configuración.",
            fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
        )
        Spacer(Modifier.height(16.dp))
        EmergencyField("Contacto", listOfNotNull(info?.contactName, info?.contactPhone).joinToString("  ·  ").ifBlank { "—" })
        EmergencyField("Tipo de sangre", info?.bloodType ?: "—")
        EmergencyField("Alergias", info?.allergies ?: "—")
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
                .clickable { onEditEmergency() }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Editar información de emergencia", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Amber)
        }
    }
}

@Composable
private fun EmergencyField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut, modifier = Modifier.weight(1f))
        Text(value, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
    }
}

/** Estado de la consulta de emergencia ajena (jefe de puesto). */
private sealed interface EmergencyQuery {
    data object Idle : EmergencyQuery
    data object Loading : EmergencyQuery
    data class Result(val view: EmergencyView) : EmergencyQuery
}

/**
 * Emergencia de OTRO oficial. Por defecto bloqueada; el jefe del puesto del oficial puede
 * consultarla durante un evento activo. Cada consulta va al backend, queda registrada y
 * es visible para el titular (privacy-first) — por eso no se consulta sola al abrir.
 */
@Composable
private fun EmergencyOther(repo: AppRepository, officer: com.alephri.elpuesto.model.Officer) {
    val scope = rememberCoroutineScope()
    var query by remember(officer.id) { mutableStateOf<EmergencyQuery>(EmergencyQuery.Idle) }
    SectionHeader("Información de emergencia")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(18.dp),
    ) {
        val granted = (query as? EmergencyQuery.Result)?.view as? EmergencyView.Granted
        // La emergencia ajena concedida: sin capturas ni miniatura en "recientes".
        com.alephri.elpuesto.ui.components.SecureWindow(enabled = granted != null)
        if (granted != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.alephri.elpuesto.ui.components.LineIconView(com.alephri.elpuesto.ui.components.LineIcon.LOCK, Amber, size = 16.dp)
                Text("Acceso registrado", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Amber)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${officer.displayName} verá esta consulta en su registro de accesos.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            Spacer(Modifier.height(12.dp))
            val info = granted.info
            EmergencyField("Contacto", listOfNotNull(info.contactName, info.contactPhone).joinToString("  ·  ").ifBlank { "—" })
            EmergencyField("Tipo de sangre", info.bloodType ?: "—")
            EmergencyField("Alergias", info.allergies ?: "—")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.alephri.elpuesto.ui.components.LineIconView(com.alephri.elpuesto.ui.components.LineIcon.LOCK, TextSub, size = 16.dp)
                Text("Privada", fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextSub)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Solo el jefe de puesto puede consultarla durante un evento activo. Cada acceso queda registrado y es visible para el titular.",
                fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
            )
            when ((query as? EmergencyQuery.Result)?.view) {
                EmergencyView.Forbidden -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No autorizado: no eres jefe del puesto de ${officer.displayName} en un evento activo.",
                        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = TextSub,
                    )
                }
                EmergencyView.Unavailable -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Sin conexión con el servidor. Intenta de nuevo.",
                        fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = TextSub,
                    )
                }
                else -> {}
            }
            Spacer(Modifier.height(14.dp))
            val loading = query is EmergencyQuery.Loading
            // Siempre en línea: el acceso se audita en el servidor y nunca se cachea.
            val online = rememberOnline(repo)
            if (!online) NeedsConnectionNote("Consultarla necesita conexión (cada acceso se registra).")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
                    .clickable(enabled = online && !loading) {
                        query = EmergencyQuery.Loading
                        scope.launch { query = EmergencyQuery.Result(repo.officerEmergency(officer.id)) }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (loading) "Consultando…" else "Consultar como jefe de puesto",
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (online) Amber else TextFaint,
                )
            }
        }
    }
}

/** Filas visibles en el perfil antes de "Ver todo". */
private const val HISTORY_PREVIEW = 5

/**
 * Fila de un evento pasado. Propio: posición · rol · circuito. En común ([otherName]):
 * la posición de cada uno y el chip "Mismo puesto" cuando fueron compañeros.
 */
@Composable
internal fun HistoryRow(h: OfficerHistoryEntry, otherName: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            monthYear(h.date),
            fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Amber,
            letterSpacing = 0.5.sp, modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(h.eventName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            val sub = if (otherName == null) {
                listOfNotNull(h.positionLabel?.let { if (h.positionPending) "$it (en revisión)" else it }, h.roleLabel, h.location).joinToString(" · ")
            } else {
                "Tú ${h.positionLabel?.let { if (h.positionPending) "$it (en revisión)" else it } ?: "—"} · ${otherName.substringBefore(' ')} ${h.otherPosition ?: "—"}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sub, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (otherName != null && h.samePosition) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "MISMO PUESTO", fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp, color = Amber, letterSpacing = 0.6.sp, maxLines = 1,
                    )
                }
            }
        }
        Text("›", color = TextFaint, fontSize = 22.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun LabelChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, color = TextMut, letterSpacing = 0.8.sp)
    }
}

private val MESES = listOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC")
internal fun monthYear(d: LocalDate): String = "${MESES[d.monthNumber - 1]} ${d.year}"
