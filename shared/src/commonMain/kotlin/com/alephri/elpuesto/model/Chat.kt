package com.alephri.elpuesto.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * EVENT y PUESTO son restringidos (solo asignados). PUBLIC: cualquiera crea/se une.
 * PRIVATE: grupo con nombre por invitación — solo sus miembros lo ven, y el invitado
 * ACEPTA antes de entrar (no hay mensajería directa como función ni lista de amigos).
 * Los chats de evento se archivan (solo lectura) una semana después del evento.
 * Orden = número en el wire: los nuevos van SIEMPRE al final.
 */
@Serializable
enum class ChatType { EVENT, PUESTO, PUBLIC, PRIVATE }

@Serializable
data class Chat(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val type: ChatType,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val membersCount: Int = 0,
    @ProtoNumber(5) val lastPreview: String? = null,
    @ProtoNumber(6) val unread: Int = 0,
    @ProtoNumber(7) val archived: Boolean = false,
    @ProtoNumber(8) val archivedAt: Instant? = null,
    @ProtoNumber(9) val joined: Boolean = true, // públicos: si el usuario ya está unido
    @ProtoNumber(10) val description: String? = null, // públicos: descripción para explorar
    @ProtoNumber(11) val creatorId: String? = null, // públicos: quién lo creó (puede archivar)
    /**
     * Evento/puesto: a qué evento pertenecen. Públicos/privados: el evento al que su
     * creador los LIGÓ (opcional) — aparecen en el Modo evento de ese evento.
     */
    @ProtoNumber(12) val eventId: String? = null,
    /** Públicos/privados ligados: nombre del evento (derivado al leer). */
    @ProtoNumber(13) val eventName: String? = null,
    /**
     * Invitación PENDIENTE para ti (nombre de quien te invitó; derivado). Privados y, desde
     * 2026-09-26, también públicos: nadie entra a un chat sin aceptarlo.
     */
    @ProtoNumber(14) val invitedBy: String? = null,
)

/**
 * Alta de un chat (cualquiera crea; el creador queda unido). Privado: [inviteeIds]
 * reciben invitación (entran al aceptarla). [eventId]: ligarlo a un evento que trabajas.
 */
@Serializable
data class CreateChatRequest(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val description: String? = null,
    @ProtoNumber(3) val isPrivate: Boolean = false,
    @ProtoNumber(4) val eventId: String? = null,
    @ProtoNumber(5) val inviteeIds: List<String> = emptyList(),
)

/** Ligar (o desligar con null) un chat público/privado a un evento que trabajas. */
@Serializable
data class SetChatEventRequest(
    @ProtoNumber(1) val eventId: String? = null,
)

/** Participante de un chat con su contexto operativo en el evento del chat, derivado al leer. */
@Serializable
data class ChatMember(
    @ProtoNumber(1) val officer: Officer,
    @ProtoNumber(2) val puesto: String? = null, // "P 7"
    @ProtoNumber(3) val role: String? = null,
    /** Privados: invitado que aún no acepta (se lista aparte). */
    @ProtoNumber(4) val pending: Boolean = false,
)

/**
 * Invitar a un chat (público o privado): al invitado le llega una invitación que ACEPTA
 * (`POST /chats/{id}/join`) o rechaza (`POST /chats/{id}/leave`). Tras rechazar, nadie
 * puede volver a invitarlo a ESE chat; si bloqueó a quien invita, tampoco.
 */
@Serializable
data class AddChatMemberRequest(
    @ProtoNumber(1) val officerId: String,
)

/** Adjunto multimedia de un mensaje. VIDEO queda reservado para una fase posterior. */
@Serializable
enum class MessageMediaType { IMAGE, VIDEO }

@Serializable
data class Message(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val chatId: String,
    @ProtoNumber(3) val senderId: String? = null, // null en mensajes de sistema/Control
    @ProtoNumber(4) val senderName: String,
    @ProtoNumber(5) val text: String,
    @ProtoNumber(6) val at: Instant,
    @ProtoNumber(7) val system: Boolean = false,
    /** IMAGE: la imagen vive en /images/chatmedia/{messageId}/full; text = pie opcional. */
    @ProtoNumber(8) val mediaType: MessageMediaType? = null,
    /** Contexto operativo del remitente en el evento del chat, derivado al leer ("P 7"). */
    @ProtoNumber(9) val senderPuesto: String? = null,
    @ProtoNumber(10) val senderRole: String? = null,
)

/** Envío de un mensaje de texto. */
@Serializable
data class SendMessageRequest(
    @ProtoNumber(1) val text: String,
)

/**
 * Motivo de un reporte (moderación básica; el admin lo revisa). Sirve para reportar un
 * mensaje (`POST /chats/{id}/messages/{mid}/report`), un chat — nombre, imagen o
 * descripción — (`POST /chats/{id}/report`) y el perfil de un oficial — nombre o foto —
 * (`POST /officers/{id}/report`).
 */
@Serializable
data class ReportMessageRequest(
    @ProtoNumber(1) val reason: String? = null,
)
