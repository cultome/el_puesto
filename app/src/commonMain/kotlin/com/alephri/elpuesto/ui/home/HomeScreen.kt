package com.alephri.elpuesto.ui.home

import com.alephri.elpuesto.ui.components.rememberReloader
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.Assignment
import com.alephri.elpuesto.model.Convocatoria
import com.alephri.elpuesto.model.Event
import com.alephri.elpuesto.model.Session
import com.alephri.elpuesto.ui.components.AmberTag
import com.alephri.elpuesto.ui.components.Avatar
import com.alephri.elpuesto.ui.components.LiveChip
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.RemoteAvatar
import com.alephri.elpuesto.ui.components.ImageLoad
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.rememberRemoteImage
import com.alephri.elpuesto.ui.components.SectionHeader
import com.alephri.elpuesto.ui.components.Tag
import com.alephri.elpuesto.ui.components.Refreshable
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.format.initials
import com.alephri.elpuesto.ui.agenda.AgendaKindTag
import com.alephri.elpuesto.ui.format.relativeDateLabel
import com.alephri.elpuesto.ui.format.remainingMin
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelElevA
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub
import com.alephri.elpuesto.ui.theme.Travel
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

private val MX = TimeZone.of("America/Mexico_City")

/**
 * Saludos del Home (arriba del nombre): se elige uno al azar POR APERTURA de la app
 * (val de archivo = una vez por proceso), con sabor de pista y en forma neutra.
 */
private val GREETINGS = listOf(
    "Buen día,",
    "Qué bueno verte por aquí,",
    "Ya te extrañábamos,",
    "Qué gusto tenerte de vuelta,",
    "De regreso a la acción,",
    "La pista te esperaba,",
    "Hoy pinta bien,",
    "Arrancamos motores,",
    "Qué bueno tenerte aquí,",
    "Todo en orden por aquí,",
    "Banderas arriba,",
    "Otra vez en el paddock,",
    "El paddock no es lo mismo sin ti,",
    "Semáforo en verde,",
    "Qué gusto saludarte,",
    "A darle con todo,",
    "La afición te saluda,",
    "Encendiendo motores,",
    "Un gusto verte de nuevo,",
    "Aquí empieza la jornada,",
    "Vuelta de reconocimiento,",
    "Pit stop completado,",
    "Ya estás en la parrilla,",
    "Hoy toca dar el cien,",
    "El equipo te esperaba,",
    "Qué bueno que volviste,",
    "Buen ritmo el de hoy,",
    "Directo a la pole,",
    "Nos da gusto verte,",
    "La jornada es tuya,",
)
private val sessionGreeting = GREETINGS.random()

/** Aviso de versión nueva de la app (solo donde la app se actualiza sola: Android). */
@Composable
private fun UpdateBanner(onOpen: () -> Unit) {
    com.alephri.elpuesto.ui.platform.LocalAppPlatform.current.updates?.HomeBanner(onOpen)
}

@Composable
fun HomeScreen(
    repo: AppRepository,
    onOpenEvent: () -> Unit,
    onOpenLiveMap: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenAgenda: () -> Unit = {},
    onOpenCircuitos: () -> Unit = {},
    onOpenCircuito: (String) -> Unit = {},
    onOpenCampeonatos: () -> Unit = {},
    onOpenConvocatorias: () -> Unit = {},
    onOpenConvocatoria: (String) -> Unit = {},
    onOpenAgendaEntry: (AgendaEntry) -> Unit = {},
    onOpenUpdate: () -> Unit = {},
) {
    val ui by repo.home().collectAsState(initial = null)
    var convocatorias by remember { mutableStateOf<List<Convocatoria>?>(null) }
    var refreshed by remember { mutableStateOf(false) }
    // Conteos de Explorar (null = cargando: la tarjeta no muestra número todavía).
    var circuitCount by remember { mutableStateOf<Int?>(null) }
    var championshipSeasons by remember { mutableStateOf<List<Int>?>(null) }
    var seriesCount by remember { mutableStateOf<Int?>(null) }
    // Caché primero (Reloader.track): convocatorias y conteos salen al instante y la red
    // los revalida; jalar para refrescar sí va a la red (Refreshable).
    val reloader = rememberReloader(repo)
    suspend fun loadConvocatorias() { convocatorias = reloader.track { repo.convocatorias(past = false) }.value }
    suspend fun loadExplore() {
        reloader.track {
            circuitCount = repo.circuits().size
            championshipSeasons = repo.championships().map { it.season }
            seriesCount = repo.series().size
        }
    }
    LaunchedEffect(reloader.key) {
        // En paralelo: cada fuente marca listo su sección de forma independiente.
        launch { loadConvocatorias() }
        launch { loadExplore() }
    }
    LaunchedEffect(Unit) { repo.refresh(); refreshed = true }

    val s = ui
    // Readiness por sección: el skeleton de una sección se quita cuando SU dato está listo
    // (presente en caché, o el refresh ya terminó aunque haya venido vacío). Sin reacomodo escalonado.
    val agendaReady = s != null && (s.agenda.isNotEmpty() || refreshed)

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Refreshable(
            onRefresh = { repo.refresh(); loadConvocatorias(); loadExplore() },
            modifier = Modifier.weight(1f),
        ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))
            // Saludo + avatar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(sessionGreeting, fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextMut)
                    Spacer(Modifier.height(4.dp))
                    if (s != null) {
                        Text(s.me.displayName, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextHi)
                    } else {
                        SkeletonBox(Modifier.size(width = 150.dp, height = 22.dp))
                    }
                }
                if (s != null) {
                    RemoteAvatar(repo, s.me.avatarUrl, initials(s.me.displayName), modifier = Modifier.clip(CircleShape).clickable { onOpenProfile() }, size = 44.dp)
                } else {
                    SkeletonBox(Modifier.size(44.dp), corner = 22.dp)
                }
            }

            // Versión nueva de la app (no vive en Google Play): aviso compacto arriba de todo.
            UpdateBanner(onOpenUpdate)

            // Evento activo: casi nunca hay uno, así que no se reserva lugar con skeleton (causaba
            // un parpadeo). Manda la caché: si ya sabe de un evento activo, la tarjeta sale de
            // inmediato; si el refresh trae uno nuevo, aparece al llegar.
            if (s != null && s.event != null && s.assignment != null) {
                Spacer(Modifier.height(20.dp))
                EventHero(repo, s.event, s.live, s.assignment, s.circuit, onOpenEvent, onOpenCircuito, onOpenLiveMap)
            }

            // Convocatorias abiertas (nunca las de cierre ya vencido, por si el flag no se actualizó).
            // Como el evento activo: lo normal es que no haya, así que no se reserva lugar con
            // skeleton (parpadeaba); la sección aparece solo cuando llegan y hay alguna.
            val now = Clock.System.now()
            val convs = convocatorias?.filter { it.registrationCloseAt > now }
            if (!convs.isNullOrEmpty()) {
                SectionHeader("Convocatorias abiertas", trailing = "Ver todas", onTrailingClick = onOpenConvocatorias)
                convs.take(2).forEach { ConvocatoriaMini(it) { onOpenConvocatoria(it.id) } }
            }

            // Tu agenda: solo lo de hoy a 15 días adelante (lo pasado y lo sin fecha, al calendario)
            SectionHeader("Tu agenda", trailing = "Ver calendario", onTrailingClick = onOpenAgenda)
            if (agendaReady && s != null) {
                val today = now.toLocalDateTime(MX).date
                val upcoming = s.agenda.filter { e ->
                    val d = e.at?.toLocalDateTime(MX)?.date ?: return@filter false
                    d >= today && today.daysUntil(d) <= 15
                }.sortedBy { it.at }
                if (upcoming.isEmpty()) {
                    Text(
                        "Sin actividades en los próximos 15 días.",
                        fontFamily = PlexSansFamily, fontSize = 12.5.sp, color = TextMut,
                    )
                } else {
                    upcoming.take(3).forEach { entry -> AgendaRow(repo, entry) { onOpenAgendaEntry(entry) } }
                }
            } else {
                repeat(3) { SkeletonBox(Modifier.fillMaxWidth().height(56.dp), corner = 14.dp); Spacer(Modifier.height(10.dp)) }
            }

            // Explorar: tarjetas con motivo (silueta de trazado / bandera a cuadros) y conteo.
            SectionHeader("Explorar")
            val year = Clock.System.now().toLocalDateTime(MX).year
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExploreTile(
                    "Circuitos", "Trazados y puestos", circuitCount,
                    ExploreMotif.TRACK, Modifier.weight(1f), onClick = onOpenCircuitos,
                )
                ExploreTile(
                    "Campeonatos",
                    if (championshipSeasons?.contains(year) == true) "Calendarios $year" else "Calendarios",
                    seriesCount,
                    ExploreMotif.CHECKERED, Modifier.weight(1f), onClick = onOpenCampeonatos,
                )
            }
            Spacer(Modifier.height(22.dp))
        }
        }
    }
}

@Composable
private fun ConvocatoriaMini(c: Convocatoria, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
            .border(1.dp, Border, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.height(34.dp).clip(RoundedCornerShape(3.dp)).background(Live).padding(horizontal = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(c.eventName, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            Text("${c.location} · ${c.eventDate}", fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
        }
        Tag("Cupo ${c.cupo}", container = Live.copy(alpha = 0.14f), contentColor = Live, border = Live.copy(alpha = 0.28f))
    }
}

private enum class ExploreMotif { TRACK, CHECKERED }

/**
 * Silueta decorativa de trazado (Autódromo Hermanos Rodríguez) en coordenadas 0..100 × 15..85;
 * solo motivo gráfico de la tarjeta de Circuitos, no datos.
 */
private val TRACK_MOTIF = floatArrayOf(
    11.7f, 17.6f, 40.3f, 21.5f, 53.7f, 23.0f, 76.0f, 25.9f, 90.8f, 28.3f, 92.7f, 29.7f, 92.1f, 34.0f,
    94.6f, 35.7f, 94.9f, 37.8f, 93.4f, 42.9f, 91.3f, 47.1f, 81.9f, 62.8f, 74.8f, 73.8f, 75.2f, 75.2f,
    77.9f, 76.6f, 78.2f, 77.7f, 72.1f, 82.3f, 71.0f, 82.0f, 70.2f, 80.4f, 71.7f, 68.8f, 72.6f, 61.9f,
    70.5f, 60.0f, 68.0f, 58.6f, 66.7f, 56.7f, 65.0f, 53.4f, 62.3f, 51.9f, 53.8f, 50.7f, 52.2f, 48.8f,
    51.5f, 46.0f, 49.4f, 43.9f, 42.8f, 40.4f, 39.4f, 39.3f, 18.1f, 36.1f, 17.1f, 35.1f, 16.6f, 28.1f,
    16.2f, 25.6f, 15.1f, 24.8f, 13.7f, 26.1f, 12.4f, 27.0f, 10.1f, 26.1f, 6.1f, 25.6f, 5.0f, 24.6f,
    5.6f, 22.1f, 7.6f, 19.3f, 10.5f, 17.7f,
)

@Composable
private fun ExploreTile(
    title: String,
    subtitle: String,
    count: Int?,
    motif: ExploreMotif,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(150.dp)
            .clip(shape)
            .background(Brush.verticalGradient(0f to PanelElevA, 0.7f to Panel))
            .border(1.dp, BorderStrong, shape)
            .clickable { onClick() },
    ) {
        when (motif) {
            ExploreMotif.TRACK -> Canvas(
                Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = 10.dp).size(width = 120.dp, height = 84.dp),
            ) {
                val k = size.width / 100f
                val path = Path().apply {
                    moveTo(TRACK_MOTIF[0] * k, (TRACK_MOTIF[1] - 15f) * k)
                    for (i in 2 until TRACK_MOTIF.size step 2) lineTo(TRACK_MOTIF[i] * k, (TRACK_MOTIF[i + 1] - 15f) * k)
                    close()
                }
                drawPath(path, BorderStrong, style = Stroke(width = 8f * k, join = StrokeJoin.Round))
                drawPath(path, Amber, style = Stroke(width = 2.2f * k, join = StrokeJoin.Round))
            }
            ExploreMotif.CHECKERED -> Canvas(
                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(96.dp).alpha(0.9f),
            ) {
                // Bandera a cuadros que se desvanece en diagonal hacia la esquina superior derecha.
                val cell = size.width / 8f
                for (y in 0 until 8) for (x in 0 until 8) {
                    if (x + y >= 6 && (x + y) % 2 == 0) {
                        drawRect(Amber, topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp)) {
            if (count != null && count > 0) {
                Text(
                    count.toString(), fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold,
                    fontSize = 30.sp, lineHeight = 30.sp, color = Amber,
                )
            } else if (count == null) {
                SkeletonBox(Modifier.size(width = 40.dp, height = 26.dp), corner = 6.dp)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextHi)
            Text(subtitle, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextSub)
        }
    }
}

private val MONTH_ABBR = listOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC")

/** "22–26 OCT" (mismo mes) o "30 SEP – 2 OCT" (cruza de mes), desde las fechas reales. */
private fun eventDatesLabel(event: Event): String {
    val s = event.startsOn
    val e = event.endsOn
    return when {
        s == e -> "${s.dayOfMonth} ${MONTH_ABBR[s.monthNumber - 1]}"
        s.monthNumber == e.monthNumber -> "${s.dayOfMonth}–${e.dayOfMonth} ${MONTH_ABBR[e.monthNumber - 1]}"
        else -> "${s.dayOfMonth} ${MONTH_ABBR[s.monthNumber - 1]} – ${e.dayOfMonth} ${MONTH_ABBR[e.monthNumber - 1]}"
    }
}

@Composable
private fun EventHero(repo: AppRepository, event: Event, live: Session?, a: Assignment, circuit: String, onOpenEvent: () -> Unit, onOpenCircuito: (String) -> Unit, onOpenLiveMap: () -> Unit) {
    // Imagen del evento (opcional): banner arriba de la card solo si el evento tiene una.
    val banner = (rememberRemoteImage(repo, "/images/event/${event.id}/full") as? ImageLoad.Ready)?.bitmap
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(PanelElevA)
            .border(1.dp, BorderStrong, RoundedCornerShape(22.dp)),
    ) {
    banner?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp))
    }
    Column(Modifier.padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LiveChip()
            Text(eventDatesLabel(event), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMut)
        }
        Spacer(Modifier.height(13.dp))
        Text(event.name, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, color = TextHi)
        Spacer(Modifier.height(6.dp))
        // Navegación conectada: el nombre del circuito lleva a su detalle.
        Text(
            "$circuit ›", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextSub,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenCircuito(event.circuitId) }.padding(vertical = 2.dp),
        )

        if (live != null) {
            Spacer(Modifier.height(15.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(13.dp)).padding(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ahora: ${live.name}", fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextPrimary)
                    live.remainingMin()?.let {
                        Text("termina en $it min", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Amber)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(0.64f).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Amber))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Nombre del puesto = label del trazado ("MP 1"); "Puesto N" solo como legado.
            AmberTag(a.puestoLabel.ifBlank { "Puesto ${a.puestoNumber}" })
            Tag(a.role)
        }
        Spacer(Modifier.height(16.dp))
        // Quienes te comparten su ubicación ahora (diseño "Ubicaciones en vivo" A) → mapa.
        com.alephri.elpuesto.ui.event.SharingWithYouRow(repo, event.id, onOpenLiveMap)
        PrimaryButton("Entrar al evento →", onClick = onOpenEvent)
    }
    }
}

@Composable
private fun AgendaRow(repo: AppRepository, entry: AgendaEntry, onClick: () -> Unit) {
    val accent = when (entry.kind) {
        AgendaKind.EVENT -> Amber
        AgendaKind.TRIP -> Travel
        AgendaKind.CONVOCATORIA -> Live
        AgendaKind.REMINDER -> TextMut
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.height(34.dp).clip(RoundedCornerShape(3.dp)).background(accent).padding(horizontal = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            // Lugar + fecha relativa ("dentro de 8 días") para ubicarse sin abrir el calendario.
            val meta = listOfNotNull(entry.location, entry.relativeDateLabel()).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
        }
        AgendaKindTag(repo, entry, accent)
    }
}
