package com.alephri.elpuesto.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.model.AssetType
import com.alephri.elpuesto.model.MapPoint
import com.alephri.elpuesto.model.Puesto
import com.alephri.elpuesto.model.TrackAsset
import com.alephri.elpuesto.ui.format.display
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.FlagYellow
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.MapPuesto
import com.alephri.elpuesto.ui.theme.MapPuestoFill
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.SurfaceTop
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextMut

/**
 * Capas del mapa de un trazado (decisión 2026-09-27, canvas "Mapas homologados"): las MISMAS
 * en todos los mapas de la app (Circuito, tab Puesto, Mapa en vivo, Registro por honor).
 * Cada una se prende y apaga sola; [color] va en el borde y el texto del marcador y [fill]
 * solo en lo especial (tu puesto, lo elegido).
 */
enum class MapLayer(val label: String, val color: Color, val fill: Color) {
    /** Naranja de los overoles de los oficiales. */
    PUESTOS("Puestos", MapPuesto, MapPuestoFill),
    /** Amarillo de la bandera de peligro: ahí trabaja un vehículo de intervención. */
    RESCATE("Rescate", FlagYellow, FlagYellow),
    /** Verde de pista libre: el Safety Car y la barredora la devuelven a verde. */
    SOPORTE("Soporte", Live, Live),
    /** Rojo de máxima alarma (el del texto, más claro, se lee sobre el fondo oscuro). */
    MEDICOS("Médicos", DangerHi, Danger),
}

/** A qué capa pertenece cada tipo de activo. Un tipo nuevo del enum = una línea aquí. */
fun AssetType.layer(): MapLayer = when (this) {
    AssetType.HIAB, AssetType.IFRT, AssetType.TELEHANDLER, AssetType.DRIVER_RIDER -> MapLayer.RESCATE
    AssetType.SAFETY_CAR, AssetType.TRACK_SWEEPER -> MapLayer.SOPORTE
    AssetType.AMBULANCIA -> MapLayer.MEDICOS
}

/** Lo que se rellena en el mapa (lo demás va solo en contorno). */
enum class Emphasis {
    NONE,
    /** Tu posición en el evento o la que elegiste (registro): relleno con halo, siempre visible. */
    MINE,
    /** Circuito: donde ya trabajaste ("Asignado antes"). */
    BEFORE,
}

/** Una posición sobre el mapa: un puesto o un activo (grúa, IFRT, ambulancia…). */
data class MapItem(
    val id: String,
    /** Lo que dice el marcador ("MP 1", "0.2", "TH1"…). */
    val label: String,
    val point: MapPoint,
    val layer: MapLayer,
    /** Qué es, para la lista y el globo al tocarlo ("Puesto", "Telehandler"…). */
    val typeName: String,
    val emphasis: Emphasis = Emphasis.NONE,
) {
    /** El globo al tocarlo: "Puesto 7", "IFRT4 · Rescate". */
    val callout: String
        get() = when {
            emphasis == Emphasis.MINE -> "$label · Tu puesto"
            layer == MapLayer.PUESTOS -> if (emphasis == Emphasis.BEFORE) "Puesto $label · asignado antes" else "Puesto $label"
            else -> "$label · ${layer.label}"
        }
}

/**
 * Los marcadores de un trazado: sus puestos con lugar en el mapa y sus activos.
 * [emphasis] decide qué se rellena (tu puesto en el evento, "asignado antes" en Circuito…).
 */
fun trackMapItems(
    puestos: List<Puesto>,
    assets: List<TrackAsset>,
    emphasis: (id: String, assignedBefore: Boolean) -> Emphasis = { _, _ -> Emphasis.NONE },
): List<MapItem> =
    // El nombre visible del puesto es su label del trazado ("MP 1"); el número = respaldo.
    puestos.filter { it.onMap }.map {
        MapItem(it.id, it.label ?: "${it.number}", it.point, MapLayer.PUESTOS, "Puesto", emphasis(it.id, it.assignedBefore))
    } + assets.map {
        MapItem(it.id, it.label, it.point, it.type.layer(), it.type.display(), emphasis(it.id, it.assignedBefore))
    }

/**
 * Capas visibles (y trazado elegido) de cada mapa, compartidas entre la tarjeta y su pantalla
 * completa: lo que ocultas en una sigue oculto en la otra. Solo en memoria (se olvida al
 * cerrar la app); la llave separa los mapas ("circuitos", "evento:<id>", "registro").
 */
object MapMemory {
    private val layers = mutableStateMapOf<String, Set<MapLayer>>()
    private val trazados = mutableStateMapOf<String, String>()

    fun layers(key: String): Set<MapLayer> = layers[key] ?: MapLayer.entries.toSet()
    fun toggle(key: String, layer: MapLayer) {
        val now = layers(key)
        layers[key] = if (layer in now) now - layer else now + layer
    }
    fun trazado(key: String): String? = trazados[key]
    fun setTrazado(key: String, id: String) { trazados[key] = id }

    /** Hay un mapa a pantalla completa DENTRO de una pantalla (el shell oculta el FAB). */
    var fullscreenOpen by mutableStateOf(false)
        internal set
}

/** Visible con estas capas: su capa está prendida, o es tu puesto (siempre a la vista). */
fun MapItem.visibleWith(layers: Set<MapLayer>) = layer in layers || emphasis == Emphasis.MINE

/**
 * Barra de capas: una celda por capa CON elementos en este trazado (las vacías no salen),
 * con su conteo. Prendida = borde y texto del color de la capa (la misma regla que los
 * marcadores); apagada = gris. Bajo el mapa en la tarjeta; arriba, sobre el mapa, en
 * pantalla completa ([overlay]).
 */
@Composable
fun LayerBar(items: List<MapItem>, visible: Set<MapLayer>, onToggle: (MapLayer) -> Unit, modifier: Modifier = Modifier, overlay: Boolean = false) {
    val counts = MapLayer.entries.associateWith { l -> items.count { it.layer == l } }.filterValues { it > 0 }
    if (counts.isEmpty()) return
    val outer = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth()
            .then(if (overlay) Modifier.clip(outer).background(SurfaceTop.copy(alpha = 0.94f)).border(1.dp, Border, outer).padding(6.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        counts.forEach { (layer, n) ->
            val on = layer in visible
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.weight(1f).height(52.dp).clip(shape)
                    .background(if (on) layer.color.copy(alpha = 0.1f) else Color.Transparent)
                    .border(if (on) 1.5.dp else 1.dp, if (on) layer.color else Border, shape)
                    .toggleable(value = on, role = Role.Switch) { onToggle(layer) }
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    layer.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                    color = if (on) layer.color else TextMut,
                )
                Text(
                    "$n", fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                    color = if (on) layer.color.copy(alpha = 0.75f) else TextFaint,
                )
            }
        }
    }
}
