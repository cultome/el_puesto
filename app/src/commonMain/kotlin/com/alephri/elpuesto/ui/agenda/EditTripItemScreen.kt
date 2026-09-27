package com.alephri.elpuesto.ui.agenda

import com.alephri.elpuesto.ui.platform.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.datetime.toDeprecatedInstant
import kotlinx.datetime.todayIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.data.AppRepository
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.TripItem
import com.alephri.elpuesto.model.TripItemKind
import com.alephri.elpuesto.ui.components.AppTextField
import com.alephri.elpuesto.ui.components.BackButton
import com.alephri.elpuesto.ui.components.PrimaryButton
import com.alephri.elpuesto.ui.components.ImageLoad
import com.alephri.elpuesto.ui.components.SkeletonBox
import com.alephri.elpuesto.ui.components.rememberRemoteImage
import com.alephri.elpuesto.ui.components.SkeletonRows
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.screenBackground
import kotlinx.coroutines.flow.first
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.ui.format.isRace
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Crear/editar un ítem de planeación personal (Transporte / Hospedaje / Recordatorio),
 * opcionalmente ligado a un evento de la agenda. Al guardar se persiste en el backend
 * y la agenda local se resincroniza (los Flows actualizan el calendario solos).
 *
 * [tripItemId] null = crear. [presetEventId]/[presetRoundId] preseleccionan el vínculo
 * (evento que trabajas o carrera del calendario, p. ej. al venir de "Planear" en el
 * detalle de una entrada). Un ítem se liga a UNA cosa: evento, carrera o convocatoria.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTripItemScreen(
    repo: AppRepository,
    tripItemId: String?,
    presetEventId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    presetKind: TripItemKind? = null,
    presetRoundId: String? = null,
    presetDate: LocalDate? = null,
) {
    val scope = rememberCoroutineScope()
    val editing = tripItemId != null
    var loaded by remember { mutableStateOf(!editing) }
    var kind by remember { mutableStateOf(presetKind ?: TripItemKind.TRANSPORT) }
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(presetDate) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var eventId by remember { mutableStateOf(presetEventId) }
    var convocatoriaId by remember { mutableStateOf<String?>(null) }
    var roundId by remember { mutableStateOf(presetRoundId) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var endTime by remember { mutableStateOf<LocalTime?>(null) }
    val pickDate = com.alephri.elpuesto.ui.platform.rememberDatePicker { date = it }
    val pickTime = com.alephri.elpuesto.ui.platform.rememberTimePicker { time = it }
    val pickEndDate = com.alephri.elpuesto.ui.platform.rememberDatePicker { endDate = it }
    val pickEndTime = com.alephri.elpuesto.ui.platform.rememberTimePicker { endTime = it }
    var raceQuery by remember { mutableStateOf("") }
    var races by remember { mutableStateOf<List<AgendaEntry>>(emptyList()) }
    var agendaAll by remember { mutableStateOf<List<AgendaEntry>>(emptyList()) }
    var eventOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var convOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        // Eventos de la agenda (los que trabajas) y carreras del calendario como vínculos.
        agendaAll = repo.agenda().first()
        eventOptions = agendaAll
            .filter { it.kind == AgendaKind.EVENT && it.eventId != null }
            .map { it.eventId!! to it.title }
            .distinctBy { it.first }
        races = agendaAll.filter { it.isRace }.sortedBy { it.at }
        // Convocatorias abiertas como vínculo alternativo (la planeación de una postulación).
        convOptions = repo.convocatorias(past = false).map { it.id to it.eventName }
        if (editing) {
            val item = repo.myTripItems().firstOrNull { it.id == tripItemId }
            if (item != null) {
                kind = item.kind
                title = item.title
                detail = item.detail.orEmpty()
                eventId = item.eventId
                convocatoriaId = item.convocatoriaId
                roundId = item.roundId
                item.endsAt?.toLocalDateTime(MX)?.let { dt ->
                    endDate = dt.date
                    endTime = dt.time
                }
                item.at?.toLocalDateTime(MX)?.let { dt ->
                    date = dt.date
                    time = dt.time
                }
            }
            loaded = true
        }
    }

    // Las fotos de bitácora solo permiten cambiar el pie y eliminarse: la imagen, la hora
    // y el evento son el momento capturado, no se reescriben.
    val photoOnly = kind == TripItemKind.PHOTO

    Column(Modifier.fillMaxSize().background(screenBackground())) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                if (photoOnly) "EDITAR FOTO" else if (editing) "EDITAR PLANEACIÓN" else "AGREGAR A LA AGENDA",
                fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                color = TextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(42.dp))
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(16.dp))

            if (!loaded) {
                // Editando: no pintar el formulario genérico mientras llega el ítem
                // (evita el flasheo antes de saber si es foto/nota/planeación).
                SkeletonBox(Modifier.fillMaxWidth().height(160.dp), corner = 16.dp)
                Spacer(Modifier.height(18.dp))
                SkeletonRows(3, 52.dp)
            } else {

            if (photoOnly) {
                // Foto de contexto (solo lectura) + pie editable; nada más.
                TripPhotoPreview(repo, tripItemId!!)
                FieldLabel("Pie de foto")
                AppTextField(value = title, onValueChange = { title = it }, placeholder = placeholderFor(kind), maxLength = com.alephri.elpuesto.data.TextLimits.TRIP_TITLE, showCounter = true)
                Text(
                    "De una foto solo puedes cambiar el pie o eliminarla; la imagen, la hora y el evento quedan como se capturaron.",
                    fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
                )
            } else {
            FieldLabel("Tipo")
            if (kind == TripItemKind.NOTE) {
                // Momentos de la bitácora: el tipo no se cambia, solo su texto/fecha/vínculo.
                Row { KindChip("Nota", true) {} }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip("Transporte", kind == TripItemKind.TRANSPORT) { kind = TripItemKind.TRANSPORT }
                    KindChip("Hospedaje", kind == TripItemKind.LODGING) { kind = TripItemKind.LODGING }
                    KindChip("Recordatorio", kind == TripItemKind.REMINDER) { kind = TripItemKind.REMINDER }
                }
            }

            FieldLabel("Título")
            AppTextField(value = title, onValueChange = { title = it }, placeholder = placeholderFor(kind), maxLength = com.alephri.elpuesto.data.TextLimits.TRIP_TITLE, showCounter = true)

            FieldLabel("Detalle (opcional)")
            AppTextField(value = detail, onValueChange = { detail = it }, placeholder = "Notas, confirmación, dirección…", maxLength = com.alephri.elpuesto.data.TextLimits.TRIP_DETAIL, showCounter = true)

            FieldLabel(
                when (kind) {
                    TripItemKind.TRANSPORT -> "Salida"
                    TripItemKind.LODGING -> "Entrada"
                    else -> "Cuándo"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerChip(date?.let(::dateLabel) ?: "Elegir fecha") {
                    pickDate(date ?: kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault()))
                }
                PickerChip(time?.let { hhmm(it) } ?: "Hora", enabled = date != null) {
                    pickTime(time ?: LocalTime(9, 0))
                }
                if (date != null) {
                    PickerChip("Quitar") { date = null; time = null }
                }
            }
            Text(
                "Sin fecha aparece en la agenda bajo \"Sin fecha\".",
                fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
            )

            if (kind == TripItemKind.TRANSPORT || kind == TripItemKind.LODGING) {
                FieldLabel(if (kind == TripItemKind.TRANSPORT) "Llegada (opcional)" else "Salida (opcional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerChip(endDate?.let(::dateLabel) ?: "Elegir fecha", enabled = date != null) {
                        pickEndDate(endDate ?: date ?: return@PickerChip)
                    }
                    PickerChip(endTime?.let { hhmm(it) } ?: "Hora", enabled = endDate != null) {
                        pickEndTime(endTime ?: LocalTime(12, 0))
                    }
                    if (endDate != null) {
                        PickerChip("Quitar") { endDate = null; endTime = null }
                    }
                }
                Text(
                    if (kind == TripItemKind.TRANSPORT) "A qué hora llegas a tu destino." else "Cuándo dejas el hospedaje (cuenta las noches).",
                    fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
                )
            }

            FieldLabel("Vinculado a")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindChip("Sin vínculo", eventId == null && roundId == null && convocatoriaId == null) {
                    eventId = null; roundId = null; convocatoriaId = null
                }
                eventOptions.forEach { (id, name) ->
                    // Evento, carrera y convocatoria son vínculos excluyentes: elegir uno limpia los demás.
                    KindChip(name, eventId == id) { eventId = id; roundId = null; convocatoriaId = null }
                }
                roundId?.let { rid ->
                    val label = agendaAll.firstOrNull { e -> e.roundId == rid || e.races.any { it.roundId == rid } }?.title ?: "Carrera del calendario"
                    KindChip(label, true) {}
                }
            }
            Text(
                "Lo vinculado aparece en la línea de tiempo de ese fin de semana.",
                fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
            )

            FieldLabel("Carrera del calendario")
            if (roundId != null) {
                PickerChip("Quitar carrera") { roundId = null }
            } else {
                AppTextField(value = raceQuery, onValueChange = { raceQuery = it }, placeholder = "Buscar: Kansas, Mónaco, NASCAR…", maxLength = com.alephri.elpuesto.data.TextLimits.SEARCH)
                val today = kotlinx.datetime.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault()).toString()
                val q = raceQuery.trim().lowercase()
                val shown = if (q.isEmpty()) {
                    races.filter { (it.endsOn?.toString() ?: "") >= today }.take(5)
                } else {
                    races.filter { q in it.title.lowercase() || q in it.location.orEmpty().lowercase() }
                        .sortedBy { (it.endsOn?.toString() ?: "") < today }.take(8)
                }
                Spacer(Modifier.height(8.dp))
                shown.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                            .border(1.dp, Border, RoundedCornerShape(12.dp))
                            .clickable { roundId = e.roundId; eventId = null; convocatoriaId = null; raceQuery = "" }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                            Text(
                                listOfNotNull(e.startsOn?.let(::dateLabel), e.location).joinToString(" · "),
                                fontFamily = PlexSansFamily, fontSize = 11.5.sp, color = TextMut,
                            )
                        }
                        Text("Vincular", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Amber)
                    }
                }
                if (q.isNotEmpty() && shown.isEmpty()) {
                    Text("Sin carreras que coincidan.", fontFamily = PlexSansFamily, fontSize = 12.sp, color = TextMut)
                }
            }

            if (convOptions.isNotEmpty()) {
                FieldLabel("Convocatoria vinculada")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip("Sin convocatoria", convocatoriaId == null) { convocatoriaId = null }
                    convOptions.forEach { (id, name) ->
                        KindChip(name, convocatoriaId == id) { convocatoriaId = id; eventId = null; roundId = null }
                    }
                }
                Text(
                    "El detalle de la entrada llevará a la convocatoria.",
                    fontFamily = PlexSansFamily, fontSize = 11.sp, color = TextMut, modifier = Modifier.padding(top = 6.dp),
                )
            }
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Danger)
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = if (saving) "Guardando…" else "Guardar",
                enabled = loaded && !saving && title.isNotBlank(),
                onClick = {
                    error = null
                    val at = date?.let { d -> LocalDateTime(d, time ?: LocalTime(9, 0)).toInstant(MX).toDeprecatedInstant() }
                    val endsAt = if (kind == TripItemKind.TRANSPORT || kind == TripItemKind.LODGING) {
                        endDate?.let { d -> LocalDateTime(d, endTime ?: LocalTime(12, 0)).toInstant(MX).toDeprecatedInstant() }
                    } else null
                    if (endsAt != null && (at == null || endsAt < at)) {
                        error = if (at == null) "Elige primero la fecha de inicio." else "El fin no puede ser antes del inicio."
                        return@PrimaryButton
                    }
                    saving = true
                    val item = TripItem(
                        id = tripItemId ?: "",
                        eventId = eventId,
                        kind = kind,
                        title = title.trim(),
                        detail = detail.trim().ifBlank { null },
                        at = at,
                        personal = true,
                        convocatoriaId = convocatoriaId,
                        roundId = roundId,
                        endsAt = endsAt,
                    )
                    scope.launch {
                        repo.takeUploadProblem() // un motivo viejo no es de este guardado
                        val ok = if (editing) repo.updateTripItem(item) else repo.createTripItem(item) != null
                        saving = false
                        if (ok) onDone() else error = repo.takeUploadProblem() ?: "No se pudo guardar. Revisa tu conexión e intenta de nuevo."
                    }
                },
            )
            if (editing) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable(enabled = !saving) {
                            saving = true
                            error = null
                            scope.launch {
                                val ok = repo.deleteTripItem(TripItem(id = tripItemId!!, eventId = eventId, kind = kind, title = title))
                                saving = false
                                if (ok) onDone() else error = "No se pudo eliminar. Revisa tu conexión e intenta de nuevo."
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Eliminar", fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Danger)
                }
            }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** La foto del ítem (solo lectura) como contexto al editar el pie. */
@Composable
private fun TripPhotoPreview(repo: AppRepository, tripItemId: String) {
    val img = rememberRemoteImage(repo, "/images/trip/$tripItemId/full")
    Spacer(Modifier.height(4.dp))
    when (img) {
        is ImageLoad.Ready -> Image(
            bitmap = img.bitmap, contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Border, RoundedCornerShape(16.dp)),
        )
        ImageLoad.Loading -> SkeletonBox(Modifier.fillMaxWidth().height(160.dp), corner = 16.dp)
        else -> Box(
            Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
                .border(1.dp, Border, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Foto sin conexión", fontFamily = PlexMonoFamily, fontSize = 11.sp, color = TextMut)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(), fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
        color = TextMut, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, top = 18.dp),
    )
}

@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (on) Amber else Panel)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = if (on) OnAmber else TextPrimary,
        )
    }
}

@Composable
private fun PickerChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Panel)
            .border(1.dp, BorderStrong, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label, fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (enabled) Amber else TextMut,
        )
    }
}

private fun placeholderFor(kind: TripItemKind) = when (kind) {
    TripItemKind.TRANSPORT -> "p. ej. Vuelo a Monterrey"
    TripItemKind.LODGING -> "p. ej. Hotel cerca del autódromo"
    TripItemKind.REMINDER -> "p. ej. Confirmar asistencia"
    TripItemKind.PHOTO -> "Pie de foto"
    TripItemKind.NOTE -> "¿Qué pasó?"
}

private val MX = TimeZone.of("America/Mexico_City")
private val MESES = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private fun dateLabel(d: LocalDate) = "${d.dayOfMonth} ${MESES[d.monthNumber - 1]} ${d.year}"
private fun hhmm(t: LocalTime) = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
