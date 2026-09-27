package com.alephri.elpuesto.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Compartir ubicación: opt-in, transparente, activo solo durante eventos activos.
 * Es una lista de permisos que el usuario controla (allowlist), NO un grafo social.
 */
@Serializable
data class LocationSharing(
    @ProtoNumber(1) val enabled: Boolean = false,
    @ProtoNumber(2) val sharesWith: List<Officer> = emptyList(), // a quién le comparto
    @ProtoNumber(3) val sharedWithYou: List<Officer> = emptyList(), // quién me comparte
    // De [sharedWithYou], a quiénes oculté de MI mapa (vista propia; no revoca al emisor).
    @ProtoNumber(4) val hiddenIds: List<String> = emptyList(),
    // Zona del evento en el que transmitirías AHORA (interruptor encendido + asignado al
    // evento activo); null = no transmites o el circuito aún no tiene mapa (no se comparte).
    @ProtoNumber(5) val fence: GeoFence? = null,
)

/**
 * Zona donde se puede compartir ubicación durante un evento (decisión 2026-09-25: "solo te
 * ven cerca del circuito"): la caja lat/lon de sus trazados (vértices del dibujo y posiciones
 * con coordenadas) ampliada [MARGIN_M], que cubre paddock, torres y estacionamientos pero no
 * el hotel. Fuera de ella el teléfono no envía y el servidor descarta. Backend y app usan
 * ESTA implementación.
 */
@Serializable
data class GeoFence(
    @ProtoNumber(1) val eventId: String,
    @ProtoNumber(2) val minLat: Double,
    @ProtoNumber(3) val minLon: Double,
    @ProtoNumber(4) val maxLat: Double,
    @ProtoNumber(5) val maxLon: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        const val MARGIN_M = 500.0
        private const val M_PER_DEG_LAT = 111_320.0

        /** Caja de [latLons] (pares lat/lon) más [marginM] por lado; null sin puntos. */
        fun around(eventId: String, latLons: List<Pair<Double, Double>>, marginM: Double = MARGIN_M): GeoFence? {
            if (latLons.isEmpty()) return null
            val minLat = latLons.minOf { it.first }
            val maxLat = latLons.maxOf { it.first }
            val dLat = marginM / M_PER_DEG_LAT
            val cosLat = kotlin.math.cos((minLat + maxLat) / 2 * kotlin.math.PI / 180.0).coerceAtLeast(0.01)
            val dLon = marginM / (M_PER_DEG_LAT * cosLat)
            return GeoFence(
                eventId = eventId,
                minLat = minLat - dLat,
                minLon = latLons.minOf { it.second } - dLon,
                maxLat = maxLat + dLat,
                maxLon = latLons.maxOf { it.second } + dLon,
            )
        }
    }
}

@Serializable
data class SetLocationEnabledRequest(@ProtoNumber(1) val enabled: Boolean = false)

/** Posición que la app sube mientras comparte (solo se guarda la ÚLTIMA, en memoria). */
@Serializable
data class LocationUpdate(
    @ProtoNumber(1) val lat: Double,
    @ProtoNumber(2) val lon: Double,
    @ProtoNumber(3) val accuracyM: Float = 0f,
)

/** Última posición de un oficial que me comparte (y que puedo ver en este evento). */
@Serializable
data class LivePosition(
    @ProtoNumber(1) val officerId: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val avatarUrl: String? = null,
    @ProtoNumber(4) val lat: Double,
    @ProtoNumber(5) val lon: Double,
    @ProtoNumber(6) val accuracyM: Float = 0f,
    @ProtoNumber(7) val at: String, // ISO-8601 (instante del servidor al recibirla)
    // Contexto de quien comparte en ESTE evento (derivado de su asignación al leer).
    @ProtoNumber(8) val role: String? = null,
    @ProtoNumber(9) val position: String? = null,
)

@Serializable
data class NotificationPrefs(
    @ProtoNumber(1) val newConvocatorias: Boolean = true,
    @ProtoNumber(2) val scheduleChanges: Boolean = true,
    @ProtoNumber(3) val chatMessages: Boolean = true,
    @ProtoNumber(4) val liveEventBar: Boolean = true, // notificación persistente del evento
)
