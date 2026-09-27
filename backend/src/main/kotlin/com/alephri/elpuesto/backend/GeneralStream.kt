package com.alephri.elpuesto.backend

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch

/**
 * Sesión del tiempo real GENERAL: reenvía los cambios del bus que [principal] puede ver —
 * chats (con guard de visibilidad), su ubicación compartida, invitaciones y registros
 * dirigidos a él y los avisos del admin de la lista blanca ([StreamPolicy]) — para que la
 * app refresque sola y pueda notificar (patrón notificar-y-refetch).
 *
 * La MISMA sesión para la app Android (`WS /stream`, Bearer) y la web
 * (`WS /stream/web?ticket=…`, boleto de un solo uso: ver [StreamTickets]): mismo tope de
 * conexiones ([WsLimits]), mismo corte al cerrarse la sesión ([closeWhenRevoked]).
 */
suspend fun DefaultWebSocketServerSession.generalStream(principal: JWTPrincipal) {
    val officerId = principal.subject!!
    if (!WsLimits.open(officerId)) {
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "demasiadas conexiones"))
        return
    }
    val guard = closeWhenRevoked(principal)
    val json = kotlinx.serialization.json.Json { encodeDefaults = false }
    // Ubicación: quién me comparte y puedo ver AHORA. Se recalcula ante cambios
    // de allowlist/config/eventos (raros) y como red de seguridad cada 60 s;
    // así cada posición (frecuente) se filtra en memoria.
    var visible = LocationRepository.visibleSenders(officerId)
    var visibleAt = System.currentTimeMillis()
    // Cambios de allowlist/config/eventos marcan "sucio" y se recalcula cuando
    // llega la siguiente posición (no una consulta por socket por cada cambio).
    var dirty = false
    fun recompute() {
        visible = LocationRepository.visibleSenders(officerId)
        visibleAt = System.currentTimeMillis()
        dirty = false
    }
    // Visibilidad de chats por socket (30 s): un mensaje no dispara una consulta
    // por cada teléfono conectado.
    val chatVisible = HashMap<String, Pair<Boolean, Long>>()
    fun canSee(chatId: String): Boolean {
        val now = System.currentTimeMillis()
        chatVisible[chatId]?.takeIf { now - it.second < 30_000 }?.let { return it.first }
        val v = DomainRepository.canSeeChat(officerId, chatId)
        if (chatVisible.size > 500) chatVisible.clear()
        chatVisible[chatId] = v to now
        return v
    }
    val notifier = launch {
        ChangeBus.flow.collect { c ->
            val msg = when {
                c.kind == "location" -> {
                    val sender = c.id ?: return@collect
                    if (dirty || System.currentTimeMillis() - visibleAt > 60_000) recompute()
                    val info = visible[sender] ?: return@collect
                    if (c.action == "removed") {
                        StreamChange(kind = "location", id = sender, action = "removed")
                    } else {
                        val p = LocationHub.get(sender)?.takeIf { it.eventId == info.eventId } ?: return@collect
                        StreamChange(
                            kind = "location", id = sender, action = "updated", label = info.officer.displayName,
                            lat = p.lat, lon = p.lon, accuracyM = p.accuracyM, at = p.at.toString(),
                        )
                    }
                }
                // Alguien me agregó/quitó de su allowlist: aviso (added) y la app
                // recarga su foto de posiciones. Solo al target.
                c.kind == "location-share" -> {
                    if (c.id != officerId) return@collect
                    dirty = true
                    StreamChange(
                        kind = "location-share", id = c.detail, action = c.action,
                        label = c.detail?.let { DomainRepository.officer(it)?.displayName },
                    )
                }
                // Config de alguien (interruptor / ocultos): solo recalcular; al
                // propio le avisa para que recargue su foto.
                c.kind == "location-sharing" -> {
                    dirty = true
                    if (c.id != officerId) return@collect
                    StreamChange(kind = "location-sharing", id = officerId)
                }
                c.kind == "chat" -> {
                    val chatId = c.chatId ?: return@collect
                    if (!canSee(chatId)) return@collect
                    StreamChange(kind = "chat", id = chatId)
                }
                // Una imagen cambió (avatar, imagen de chat): la app revalida su
                // copia. La de un chat, solo a quien puede verlo.
                c.kind == "image" -> {
                    if (c.chatId != null && !canSee(c.chatId)) return@collect
                    StreamChange(kind = "image", id = c.id)
                }
                // Invitación a un chat privado: SOLO al invitado (label = chat,
                // detail = quién invita). Sin esta rama caería al `else` (a todos).
                c.kind == "chat-invite" -> {
                    if (c.id != officerId) return@collect
                    val chatId = c.chatId ?: return@collect
                    StreamChange(kind = "chat-invite", id = chatId, detail = c.detail, label = DomainRepository.chatName(chatId))
                }
                // Registro por honor: solo a su titular (sus otros dispositivos
                // releen la agenda); nadie más necesita saber quién se registró.
                c.kind == "participation" -> {
                    if (c.id == null || c.id != officerId) return@collect
                    StreamChange(kind = "participation", id = c.eventId)
                }
                // Avisos del admin: LISTA BLANCA (StreamPolicy) — nunca cuentas, claves,
                // reportes ni el detalle del audit (salvo el de "evento en curso").
                c.kind.startsWith("admin:") -> {
                    // Eventos/asignaciones pueden cambiar quién se ve con quién.
                    dirty = true
                    chatVisible.clear()
                    if (!StreamPolicy.adminVisible(c)) return@collect
                    StreamChange(
                        kind = c.kind, id = c.id, action = c.action, detail = StreamPolicy.adminDetail(c),
                        label = when (c.kind) {
                            "admin:event" -> c.id?.let(DomainRepository::eventName)
                            "admin:convocatoria" -> c.id?.let(DomainRepository::convocatoriaName)
                            else -> null
                        },
                    )
                }
                // Todo lo demás (checklist, pase de lista, MbM de un evento…) viaja por
                // el SSE del evento, solo a sus asignados: aquí no sale a nadie.
                else -> return@collect
            }
            send(Frame.Text(json.encodeToString(StreamChange.serializer(), msg)))
        }
    }
    try {
        for (frame in incoming) { /* canal solo de salida; leer detecta el cierre */ }
    } finally {
        notifier.cancel()
        guard.cancel()
        WsLimits.close(officerId)
    }
}
