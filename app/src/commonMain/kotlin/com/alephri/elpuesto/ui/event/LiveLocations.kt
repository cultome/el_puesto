package com.alephri.elpuesto.ui.event

import com.alephri.elpuesto.ui.components.rememberReloader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.platform.LocalAppPlatform
import com.alephri.elpuesto.ui.platform.LocationZone
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.LivePosition
import com.alephri.elpuesto.model.LocationUpdate
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.Trazado
import com.alephri.elpuesto.ui.map.PersonPin
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// Antigüedad de una posición: >2 min = señal vieja (atenuada), >10 min = ya no se muestra
// (el servidor la purga igual a los 10 min; esto cubre la ventana sin red).
private const val STALE_SEC = 2 * 60L
private const val GONE_SEC = 10 * 60L

/**
 * Posiciones vivas visibles en el evento: foto inicial por red + actualizaciones del WS
 * (`location`), y recarga completa cuando cambia la allowlist o la config (`location-share`
 * / `location-sharing`). Cada 60 s se recarga como red de seguridad (reconexiones).
 */
@Composable
internal fun rememberLivePositions(repo: AppRepository, eventId: String): State<Map<String, LivePosition>> {
    val state = remember(eventId) { mutableStateOf<Map<String, LivePosition>>(emptyMap()) }
    LaunchedEffect(eventId) {
        while (true) {
            state.value = repo.eventLocations(eventId).associateBy { it.officerId }
            kotlinx.coroutines.delay(60_000)
        }
    }
    LaunchedEffect(eventId) {
        repo.changes().collect { c ->
            when (c.kind) {
                "location" -> {
                    val id = c.id ?: return@collect
                    if (c.action == "removed") {
                        state.value = state.value - id
                    } else if (c.lat != null && c.lon != null) {
                        val prev = state.value[id]
                        state.value = state.value + (
                            id to LivePosition(
                                officerId = id, displayName = c.label ?: prev?.displayName ?: "Oficial",
                                avatarUrl = prev?.avatarUrl, lat = c.lat, lon = c.lon,
                                accuracyM = c.accuracyM ?: 0f, at = c.at ?: Clock.System.now().toString(),
                                role = prev?.role, position = prev?.position,
                            )
                            )
                        // Alguien nuevo en el mapa: la foto completa trae su foto, rol y posición.
                        if (prev == null) {
                            val fresh = repo.eventLocations(eventId).associateBy { it.officerId }
                            if (fresh.isNotEmpty()) state.value = fresh
                        }
                    }
                }
                "location-share", "location-sharing" ->
                    state.value = repo.eventLocations(eventId).associateBy { it.officerId }
            }
        }
    }
    return state
}

/** Reloj de la sección: re-evalúa antigüedades cada 15 s. */
@Composable
internal fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            now = Clock.System.now()
        }
    }
    return now
}

internal fun ageSec(p: LivePosition, now: Instant): Long =
    runCatching { (now - Instant.parse(p.at)).inWholeSeconds }.getOrDefault(0L).coerceAtLeast(0L)

/** Posiciones que aún se muestran (menos de 10 min), más recientes primero. */
internal fun visiblePositions(live: Map<String, LivePosition>, now: Instant): List<LivePosition> =
    live.values.filter { ageSec(it, now) < GONE_SEC }.sortedBy { ageSec(it, now) }

/** Pins del mapa: quienes me comparten + yo (si estoy transmitiendo), con su foto. */
internal fun personPins(
    positions: List<LivePosition>,
    own: LocationUpdate?,
    trazado: Trazado?,
    now: Instant,
    myId: String? = null,
): List<PersonPin> {
    val frame = trazado?.geoFrame ?: return emptyList()
    return positions.map {
        val age = ageSec(it, now)
        PersonPin(
            it.officerId, initials(it.displayName), frame.project(it.lat, it.lon), stale = age >= STALE_SEC,
            avatarUrl = thumb(it.avatarUrl ?: "/images/avatar/${it.officerId}/full"),
            label = "${firstAndLast(it.displayName)} · ${agoText(age)}",
        )
    } + listOfNotNull(
        own?.let {
            PersonPin(
                myId ?: "self", "TÚ", frame.project(it.lat, it.lon), stale = false, self = true,
                avatarUrl = myId?.let { id -> "/images/avatar/$id/thumb" }, label = "Tú",
            )
        },
    )
}

/** La variante chica de la foto: los pines y la tira son miniaturas. */
private fun thumb(url: String): String = url.replace("/full", "/thumb")

/** "Ana Sofía Ruiz Pérez" → "Ana Ruiz" (etiquetas cortas sobre el mapa). */
internal fun firstAndLast(name: String): String {
    val parts = name.trim().split(Regex("\\s+"))
    return if (parts.size <= 2) name.trim() else "${parts[0]} ${parts[parts.size - 2]}"
}

/** Estado de una persona para las listas: antigüedad, señal vieja y si cae fuera del mapa. */
internal data class LiveState(val ago: String, val stale: Boolean, val outside: Boolean, val accuracy: String?)

internal fun liveState(p: LivePosition, now: Instant, trazado: Trazado?): LiveState {
    val age = ageSec(p, now)
    val point = trazado?.geoFrame?.project(p.lat, p.lon)
    return LiveState(
        ago = agoText(age), stale = age >= STALE_SEC,
        outside = point == null || !point.inside(),
        accuracy = p.accuracyM.takeIf { it > 0f && age < STALE_SEC }?.let { "±${it.toInt()} m" },
    )
}

internal fun MapPoint.inside() = x in 0f..1f && y in 0f..1f

internal fun agoText(sec: Long): String = when {
    sec < 60 -> "hace ${sec.coerceAtLeast(1)} s"
    else -> "hace ${sec / 60} min"
}

/**
 * Mi transmisión: estado (compartiendo con N / en pausa / falta permiso / no compartes) y
 * su acción (Pausar, Reanudar, Configurar). Va bajo el mapa del tab Puesto y en la hoja del
 * mapa en vivo.
 */
@Composable
internal fun OwnSharingRow(
    repo: AppRepository,
    eventId: String,
    eventName: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val location = LocalAppPlatform.current.location
    val scope = rememberCoroutineScope()
    val running by location.running.collectAsState()
    val zone by location.zone.collectAsState()
    val requests by location.requests.collectAsState()
    var sharing by remember { mutableStateOf<com.alephri.elpuesto.model.LocationSharing?>(null) }
    var paused by remember(eventId) { mutableStateOf(repo.locationPausedFor(eventId)) }
    val reloader = rememberReloader(repo)
    LaunchedEffect(requests, running, reloader.key) {
        sharing = reloader.track { repo.locationSharing() }.value
        paused = repo.locationPausedFor(eventId)
    }
    val enabled = sharing?.enabled
    val count = sharing?.sharesWith?.size ?: 0
    val permission = location.hasPermission()
    val (dot, title, action) = when {
        enabled == null -> Triple(TextFaint, "Revisando…", null)
        // Sin transmisión propia (web): la ubicación sale solo de la app de Android.
        !location.supported -> Triple(TextFaint, if (enabled == true) "Tu ubicación se comparte desde la app de Android" else "No compartes tu ubicación", "Configurar")
        // Solo se comparte cerca del circuito: el servicio sigue y retoma al volver.
        running && zone == LocationZone.OUTSIDE -> Triple(Amber, "Fuera del circuito · no se comparte", "Pausar")
        running && zone == LocationZone.NO_MAP -> Triple(Amber, "Circuito sin mapa · no se comparte", "Pausar")
        running -> Triple(Live, if (count > 0) "Compartes tu ubicación con $count" else "Compartiendo tu ubicación", "Pausar")
        enabled == false -> Triple(TextFaint, "No compartes tu ubicación", "Configurar")
        !permission -> Triple(com.alephri.elpuesto.ui.theme.Danger, "Falta el permiso de ubicación", "Configurar")
        paused -> Triple(Amber, "Ubicación en pausa", "Reanudar")
        else -> Triple(TextFaint, "Iniciando…", null)
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (dot == Live) Live.copy(alpha = 0.08f) else PanelElevA)
            .border(1.dp, if (dot == Live) Live.copy(alpha = 0.3f) else BorderStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        action?.let { label ->
            Text(
                label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Amber,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    when (label) {
                        "Pausar" -> {
                            repo.setLocationPaused(eventId, paused = true)
                            paused = true
                            location.stop()
                        }
                        "Reanudar" -> {
                            repo.setLocationPaused(eventId, paused = false)
                            paused = false
                            scope.launch { location.sync(repo, eventId, eventName) }
                        }
                        else -> onOpenSettings()
                    }
                }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Tira bajo el mapa del tab Puesto: quienes te comparten su ubicación (foto, nombre,
 * antigüedad); tocar a alguien lo centra en el mapa. Vacía = no se dibuja.
 */
@Composable
internal fun LiveStrip(
    repo: AppRepository,
    positions: List<LivePosition>,
    now: Instant,
    trazado: Trazado?,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    if (positions.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        positions.forEach { p ->
            val st = liveState(p, now, trazado)
            val sel = p.officerId == selectedId
            Column(
                Modifier.width(112.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) PanelElevA else Panel)
                    .border(1.dp, if (sel) Travel else BorderStrong, RoundedCornerShape(14.dp))
                    .clickable { onSelect(if (sel) null else p.officerId) }
                    .padding(10.dp)
                    .alpha(if (st.stale) 0.6f else 1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Travel).padding(2.dp)) {
                    RemoteAvatar(repo, thumb(p.avatarUrl ?: "/images/avatar/${p.officerId}/full"), initials(p.displayName), size = 32.dp, bg = Panel, textColor = Travel)
                }
                Text(
                    firstAndLast(p.displayName), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = TextPrimary,
                )
                Text(
                    if (st.outside) "Fuera del circuito" else st.ago, maxLines = 1,
                    fontFamily = PlexMonoFamily, fontSize = 10.sp, color = if (st.stale || st.outside) TextMut else Live,
                )
            }
        }
    }
}

/**
 * Tarjeta del evento en el Home: "Ana, Luis y Rosa te comparten su ubicación · Mapa ›"
 * con sus fotos apiladas; lleva al mapa en vivo. Nadie compartiendo = no se dibuja.
 */
@Composable
fun SharingWithYouRow(repo: AppRepository, eventId: String, onOpen: () -> Unit) {
    val live by rememberLivePositions(repo, eventId)
    val now = rememberNow()
    val positions = visiblePositions(live, now)
    if (positions.isEmpty()) return
    val names = positions.map { firstAndLast(it.displayName).substringBefore(' ') }
    val who = when (names.size) {
        1 -> "${names[0]} te comparte su ubicación"
        2 -> "${names[0]} y ${names[1]} te comparten su ubicación"
        3 -> "${names[0]}, ${names[1]} y ${names[2]} te comparten su ubicación"
        else -> "${names[0]}, ${names[1]} y ${names.size - 2} más te comparten su ubicación"
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(Travel.copy(alpha = 0.08f)).border(1.dp, Travel.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable { onOpen() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            positions.take(3).forEachIndexed { i, p ->
                Box(Modifier.padding(start = (i * 18).dp).size(28.dp).clip(CircleShape).background(PanelElevA).padding(2.dp)) {
                    RemoteAvatar(repo, thumb(p.avatarUrl ?: "/images/avatar/${p.officerId}/full"), initials(p.displayName), size = 24.dp, bg = Panel, textColor = Travel)
                }
            }
        }
        Text(who, fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextPrimary, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Text("Mapa ›", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Travel)
    }
}
