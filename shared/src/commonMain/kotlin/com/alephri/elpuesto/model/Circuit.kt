package com.alephri.elpuesto.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Activo en pista (además de los puestos de oficiales). HIAB era "GRUA" (renombrado;
 * migración de datos en el backend). Agregar un tipo nuevo = agregarlo AL FINAL (el
 * orden es el wire format de protobuf) + su presentación en la admin web (ASSET_TYPES).
 */
@Serializable
enum class AssetType { HIAB, AMBULANCIA, IFRT, TELEHANDLER, TRACK_SWEEPER, SAFETY_CAR, DRIVER_RIDER }

/** Autódromo. Independiente de los eventos. */
@Serializable
data class Circuit(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val location: String,
    @ProtoNumber(4) val country: String? = null, // en español; la app pone México primero
    // Derivado al leer (nº de trazados vivos): el catálogo no pide los trazados uno por uno.
    @ProtoNumber(5) val trazadoCount: Int = 0,
    /**
     * Derivado al leer, solo en el catálogo de la app: el trazado principal (el primero) con
     * su silueta, longitud y curvas — la galería lo dibuja sin pedir los trazados uno por uno.
     */
    @ProtoNumber(6) val mainTrazado: Trazado? = null,
)

/**
 * Configuración/trazado de un autódromo. Un autódromo tiene VARIOS trazados; de cada
 * trazado dependen distancia, curvas y los puestos.
 */
@Serializable
data class Trazado(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val circuitId: String,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val lengthM: Int, // longitud en METROS (entero; la UI la muestra en km)
    @ProtoNumber(5) val curves: Int,
    @ProtoNumber(6) val direction: String, // p.ej. "Horario"
    @ProtoNumber(7) val mapUrl: String? = null, // asset oficial (SVG/imagen). TODO(fuente oficial).
    // Silueta del trazado dibujada en el admin (sobre OSM) y normalizada 0..1 con la
    // MISMA referencia que los puestos/activos; vacía = sin dibujo aún.
    @ProtoNumber(8) val path: List<MapPoint> = emptyList(),
    // Marco geo del dibujo (null = sin dibujo): permite proyectar lat/lon VIVAS (compartir
    // ubicación) con exactamente la misma referencia que la silueta y los puestos.
    @ProtoNumber(9) val geoFrame: GeoFrame? = null,
)

/**
 * Proyección Web Mercator del trazado ajustada a 0..1 (aspecto preservado, margen 5%,
 * norte arriba): centro ([cx], [cy]) en radianes Mercator y escala. La calcula el backend
 * a partir del dibujo geo; [project] es la ÚNICA implementación (backend y app).
 */
@Serializable
data class GeoFrame(
    @ProtoNumber(1) val cx: Double,
    @ProtoNumber(2) val cy: Double,
    @ProtoNumber(3) val scale: Double,
) {
    /** lat/lon → punto normalizado SIN recortar (fuera de 0..1 = fuera del mapa). */
    fun project(lat: Double, lon: Double): MapPoint {
        val rad = kotlin.math.PI / 180.0
        return MapPoint(
            x = (0.5 + (lon * rad - cx) * scale).toFloat(),
            y = (0.5 - (kotlin.math.asinh(kotlin.math.tan(lat * rad)) - cy) * scale).toFloat(),
        )
    }

    /** Punto normalizado → lat/lon (inversa de [project]): un toque en el mapa a coordenadas. */
    fun unproject(p: MapPoint): Pair<Double, Double> {
        val rad = kotlin.math.PI / 180.0
        val lon = (cx + (p.x - 0.5) / scale) / rad
        val lat = kotlin.math.atan(kotlin.math.sinh(cy - (p.y - 0.5) / scale)) / rad
        return lat to lon
    }

    companion object {
        /** Marco de un dibujo (pares lat/lon); null si no alcanza a definir un área. */
        fun of(latLons: List<Pair<Double, Double>>): GeoFrame? {
            if (latLons.size < 2) return null
            val rad = kotlin.math.PI / 180.0
            val xs = latLons.map { it.second * rad }
            val ys = latLons.map { kotlin.math.asinh(kotlin.math.tan(it.first * rad)) }
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()).takeIf { it > 0.0 } ?: return null
            return GeoFrame(cx = (xs.min() + xs.max()) / 2, cy = (ys.min() + ys.max()) / 2, scale = 0.9 / span)
        }
    }
}

/** Coordenada normalizada 0..1 sobre el mapa del trazado. */
@Serializable
data class MapPoint(
    @ProtoNumber(1) val x: Float,
    @ProtoNumber(2) val y: Float,
)

@Serializable
data class Puesto(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val trazadoId: String,
    @ProtoNumber(3) val number: Int,
    // Identificador mostrado del puesto ("MP 5"); null = derivar de number.
    @ProtoNumber(4) val label: String? = null,
    // Derivada de lat/lon con la referencia del trazado (la consume la UI actual).
    @ProtoNumber(5) val point: MapPoint = MapPoint(0.5f, 0.5f),
    // "Asignado antes": DERIVADO al leer del historial de QUIEN consulta (asignaciones en
    // eventos terminados); no se captura. En la API admin siempre false (no hay visor).
    @ProtoNumber(6) val assignedBefore: Boolean = false,
    // Coordenadas ABSOLUTAS (fuente de verdad, capturadas sobre OSM en el admin).
    @ProtoNumber(7) val lat: Double? = null,
    @ProtoNumber(8) val lon: Double? = null,
    // false = posición asignable SIN lugar en el mapa (p. ej. "Coordinación de zona"): no
    // se dibuja ni necesita coordenadas.
    @ProtoNumber(9) val onMap: Boolean = true,
)

@Serializable
data class TrackAsset(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val trazadoId: String,
    @ProtoNumber(3) val type: AssetType,
    @ProtoNumber(4) val label: String,
    @ProtoNumber(5) val point: MapPoint = MapPoint(0.5f, 0.5f),
    @ProtoNumber(6) val lat: Double? = null,
    @ProtoNumber(7) val lon: Double? = null,
    /** Como en Puesto: tripulaste este activo en un evento terminado (derivado del visor). */
    @ProtoNumber(8) val assignedBefore: Boolean = false,
)
