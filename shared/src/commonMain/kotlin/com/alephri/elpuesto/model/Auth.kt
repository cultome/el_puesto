package com.alephri.elpuesto.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Solicitud de enlace mágico. [challenge] liga el enlace al teléfono que lo pidió (estilo
 * PKCE): `challenge = base64url_sin_relleno(SHA-256(verifier))`, donde `verifier` son 32
 * bytes aleatorios en base64url sin relleno (43 caracteres, `[A-Za-z0-9_-]`) que la app
 * guarda y presenta al canjear ([CallbackRequest.verifier]). Así un enlace interceptado o
 * reenviado no sirve en otro teléfono. null = app anterior (se acepta mientras el servidor
 * no exija PKCE: `AUTH_REQUIRE_PKCE`).
 *
 * [client] = quién pide el enlace: `"web"` = la app web (el enlace del correo abre
 * `<WEB_APP_URL>#auth=<token>` en el navegador); null = la app Android (`elpuesto://`).
 */
@Serializable
data class MagicLinkRequest(
    @ProtoNumber(1) val email: String,
    @ProtoNumber(2) val challenge: String? = null,
    @ProtoNumber(3) val client: String? = null,
)

/**
 * Respuesta al pedir el enlace. En producción solo `sent`. En dev, `devLink` trae el enlace
 * (deep link) para completar el flujo sin correo real. `reason` explica un rechazo.
 */
@Serializable
data class MagicLinkResponse(
    @ProtoNumber(1) val sent: Boolean,
    @ProtoNumber(2) val devLink: String? = null,
    @ProtoNumber(3) val reason: String? = null, // p.ej. "not_invited"
)

/** Canje del token del enlace mágico por una sesión ([verifier]: ver [MagicLinkRequest]). */
@Serializable
data class CallbackRequest(
    @ProtoNumber(1) val token: String,
    @ProtoNumber(2) val verifier: String? = null,
)

/**
 * Resultado de autenticación. `jwt` = **access token** (corto). `refreshToken` (largo)
 * permite renovar el access sin re-login; `accessExpiresInSec` orienta la renovación
 * proactiva. Los campos nuevos son nullable por compatibilidad protobuf. A la app web
 * (`X-El-Puesto-Client: web`) el refresh NO le llega aquí (null): va en la cookie `ep_rt`
 * (HttpOnly) y su access token vive solo en memoria.
 */
@Serializable
data class AuthResult(
    @ProtoNumber(1) val jwt: String,
    @ProtoNumber(2) val status: AccountStatus,
    @ProtoNumber(3) val refreshToken: String? = null,
    @ProtoNumber(4) val accessExpiresInSec: Int? = null,
)

/**
 * Canje de un refresh token por una sesión nueva (access + refresh rotado). La app web
 * manda `RefreshRequest("")` con la cabecera `X-El-Puesto-Client: web`: su refresh token
 * vive en la cookie `ep_rt` (HttpOnly) y el servidor ignora el del cuerpo.
 */
@Serializable
data class RefreshRequest(
    @ProtoNumber(1) val refreshToken: String,
)

/**
 * Boleto para abrir el WebSocket general desde el navegador (`POST /stream/ticket` →
 * `WS /stream/web?ticket=…`): el WebSocket del navegador no puede mandar `Authorization`.
 * Un solo uso, vence en 60 s y queda ligado a la sesión de quien lo pidió.
 */
@Serializable
data class StreamTicket(
    @ProtoNumber(1) val ticket: String,
)

/**
 * ¿La cuenta ya pasó por la bienvenida "Completar perfil"? Se muestra una sola vez por cuenta
 * (en cualquier dispositivo): `GET /me/onboarding`; al terminarla u omitirla, `POST`.
 */
@Serializable
data class OnboardingState(@ProtoNumber(1) val done: Boolean = false)

/** Invitación entre pares: un oficial registrado invita a otro por correo. */
@Serializable
data class InviteRequest(
    @ProtoNumber(1) val email: String,
)

/** Respuesta simple ok/mensaje (mismos campos que el Ack del backend). */
@Serializable
data class Ack(
    @ProtoNumber(1) val ok: Boolean = true,
    @ProtoNumber(2) val message: String? = null,
)
