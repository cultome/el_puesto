package com.alephri.elpuesto.backend

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Envío del enlace mágico por correo (SMTP). Si SMTP no está configurado ([Config.emailEnabled]
 * = false), no envía nada y el llamador cae al `devLink` (modo desarrollo).
 */
object EmailSender {
    private val log = LoggerFactory.getLogger(EmailSender::class.java)

    /**
     * Envía el enlace mágico. [appLink] es el esquema directo (elpuesto://auth?token=…);
     * [buttonLink] es lo que enlaza el botón del correo (la página puente https cuando
     * hay PUBLIC_BASE_URL — Gmail y similares QUITAN los links de esquema propio).
     * [web] = lo pidió la app web: ambos enlaces son la app web (`…#auth=<token>`) y el texto
     * habla del navegador donde se pidió (el reto PKCE lo liga a ESE navegador).
     * Devuelve true si el correo se envió realmente.
     */
    fun sendMagicLink(to: String, appLink: String, buttonLink: String = appLink, web: Boolean = false): Boolean {
        val donde = if (web) "en el navegador donde lo pediste" else "desde tu teléfono"
        val soloAhi = if (web) " y solo funciona en el navegador donde lo pediste" else ""
        val plain = """
            Hola,

            Abre este enlace $donde para entrar a El Puesto:

            $buttonLink

            El enlace vence en 15 minutos$soloAhi. Si no lo solicitaste, ignora este correo.

            — El Puesto
        """.trimIndent()
        return send(to, "Tu acceso a El Puesto", plain, magicLinkHtml(buttonLink, appLink, web))
    }

    /**
     * Aviso de seguridad: se descargó una copia de los datos del oficial. Si no fue él
     * (p. ej. alguien tomó su teléfono), sabe que debe cerrar sesión y avisar.
     */
    fun sendExportNotice(to: String, whenText: String): Boolean {
        val plain = """
            Hola,

            Se descargó una copia de tus datos de El Puesto el $whenText desde la app.

            Si fuiste tú, no tienes que hacer nada. Si no reconoces esta descarga, cierra
            sesión en tu teléfono y avisa al administrador.

            — El Puesto
        """.trimIndent()
        return send(to, "Se descargaron tus datos de El Puesto", plain, noticeHtml(
            "Se descargaron tus datos",
            "Se descargó una copia de tus datos de El Puesto el <b style=\"color:#E8EAF0;\">$whenText</b> desde la app.",
            "Si fuiste tú, no tienes que hacer nada. Si no reconoces esta descarga, cierra sesión en tu teléfono y avisa al administrador.",
        ))
    }

    /**
     * Aviso de seguridad: la sesión se usó desde dos lugares (un refresh token ya canjeado
     * volvió a llegar) y se cerraron todas. Quien tenga el teléfono vuelve a entrar con su
     * correo; quien robó la sesión, no.
     */
    fun sendSessionsClosedNotice(to: String): Boolean {
        val plain = """
            Hola,

            Cerramos todas tus sesiones de El Puesto: detectamos que tu sesión se usó desde
            dos lugares distintos, lo que puede significar que alguien más la tiene.

            Vuelve a entrar con tu correo desde la app. Si no reconoces lo que pasó, avisa al
            administrador.

            — El Puesto
        """.trimIndent()
        return send(to, "Cerramos tus sesiones de El Puesto", plain, noticeHtml(
            "Cerramos tus sesiones",
            "Detectamos que tu sesión de El Puesto se usó desde <b style=\"color:#E8EAF0;\">dos lugares distintos</b>, lo que puede significar que alguien más la tiene. Por seguridad cerramos todas.",
            "Vuelve a entrar con tu correo desde la app. Si no reconoces lo que pasó, avisa al administrador.",
        ))
    }

    /** Alerta para el administrador (picos de errores, reuso de sesiones): ver SecurityMonitor. */
    fun sendSecurityAlert(to: String, title: String, lines: List<String>): Boolean {
        val plain = (listOf("Alerta de seguridad de El Puesto: $title", "") + lines + listOf("", "— El Puesto")).joinToString("\n")
        val esc = { t: String -> t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        return send(to, "[El Puesto] $title", plain, noticeHtml(
            esc(title),
            lines.joinToString("<br>") { esc(it) },
            "Revisa el log del backend (journalctl -u el-puesto) para el detalle. Esta alerta se repite como mucho una vez por hora.",
        ))
    }

    /** Envío multipart (texto + HTML). false si no hay SMTP o si falló. */
    private fun send(to: String, subject: String, plain: String, html: String): Boolean {
        if (!Config.emailEnabled) return false
        // UNA dirección simple: sin nombre visible ni listas ("a@x, b@y") que alguien haya
        // colado al invitar (el correo sale firmado por nuestro dominio).
        if (!EmailRules.isValid(to)) {
            log.warn("correo no enviado: destinatario inválido")
            return false
        }
        return try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                // Sin STARTTLS no se manda (nadie en medio puede degradar la conexión).
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.host", Config.smtpHost)
                put("mail.smtp.port", Config.smtpPort.toString())
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(Config.smtpUser, Config.smtpPassword)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(Config.smtpFrom, "El Puesto"))
                setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(to, true)))
                this.subject = subject
                // multipart/alternative: texto plano + HTML (el cliente muestra el mejor).
                setContent(
                    MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply { setText(plain, "UTF-8") })
                        addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
                    },
                )
            }
            Transport.send(msg)
            true
        } catch (e: Exception) {
            log.error("No se pudo enviar el correo '$subject' a $to", e)
            false
        }
    }

    /** Aviso simple con el mismo marco del correo de acceso (sin botón). */
    private fun noticeHtml(title: String, bodyHtml: String, footHtml: String): String = """
        <!doctype html>
        <html lang="es">
        <body style="margin:0;padding:0;background-color:#12151C;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#12151C;padding:32px 12px;">
            <tr><td align="center">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background-color:#1E222B;border:1px solid #333949;border-radius:18px;">
                <tr><td style="padding:28px 30px 0 30px;">
                  <div style="font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:800;letter-spacing:2px;color:#FFFFFF;">EL&nbsp;PUESTO</div>
                  <div style="font-family:'Courier New',monospace;font-size:11px;letter-spacing:4px;color:#F2B134;padding-top:4px;">OFICIALES&nbsp;DE&nbsp;PISTA</div>
                </td></tr>
                <tr><td style="padding:22px 30px 0 30px;"><div style="height:1px;background-color:#333949;font-size:0;">&nbsp;</div></td></tr>
                <tr><td style="padding:24px 30px 26px 30px;font-family:Arial,Helvetica,sans-serif;color:#E8EAF0;">
                  <div style="font-size:19px;font-weight:700;">$title</div>
                  <div style="font-size:14px;line-height:22px;color:#9AA3B5;padding-top:10px;">$bodyHtml</div>
                  <div style="font-size:12px;line-height:18px;color:#6B7385;padding-top:14px;">$footHtml</div>
                  <div style="font-size:11px;color:#4E5566;padding-top:18px;">— El Puesto</div>
                </td></tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
    """.trimIndent()

    /**
     * Correo con el tema "Paddock nocturno" (carbón + ámbar). Solo estilos inline y
     * layout con tablas — es lo único que respetan los clientes de correo — y sin
     * imágenes remotas (las bloquean por default): la marca va en texto.
     */
    private fun magicLinkHtml(buttonLink: String, appLink: String, web: Boolean = false): String = """
        <!doctype html>
        <html lang="es">
        <body style="margin:0;padding:0;background-color:#12151C;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#12151C;padding:32px 12px;">
            <tr><td align="center">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background-color:#1E222B;border:1px solid #333949;border-radius:18px;">
                <tr><td style="padding:28px 30px 0 30px;">
                  <div style="font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:800;letter-spacing:2px;color:#FFFFFF;">EL&nbsp;PUESTO</div>
                  <div style="font-family:'Courier New',monospace;font-size:11px;letter-spacing:4px;color:#F2B134;padding-top:4px;">OFICIALES&nbsp;DE&nbsp;PISTA</div>
                </td></tr>
                <tr><td style="padding:22px 30px 0 30px;">
                  <div style="height:1px;background-color:#333949;font-size:0;">&nbsp;</div>
                </td></tr>
                <tr><td style="padding:24px 30px 0 30px;font-family:Arial,Helvetica,sans-serif;color:#E8EAF0;">
                  <div style="font-size:19px;font-weight:700;">Tu enlace de acceso está listo</div>
                  <div style="font-size:14px;line-height:22px;color:#9AA3B5;padding-top:10px;">
                    ${if (web) "Toca el botón para entrar a El Puesto <b style=\"color:#E8EAF0;\">en el navegador donde lo pediste</b>." else "Toca el botón <b style=\"color:#E8EAF0;\">desde tu teléfono</b> para entrar a El Puesto."}
                    Sin contraseñas: este enlace es tu acceso.
                  </div>
                </td></tr>
                <tr><td align="center" style="padding:26px 30px 6px 30px;">
                  <table role="presentation" cellpadding="0" cellspacing="0" width="100%">
                    <tr><td align="center" bgcolor="#F2B134" style="border-radius:14px;">
                      <a href="$buttonLink" style="display:block;padding:15px 24px;font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:800;color:#1E222B;text-decoration:none;">Entrar a El Puesto&nbsp;&nbsp;→</a>
                    </td></tr>
                  </table>
                </td></tr>
                <tr><td style="padding:14px 30px 0 30px;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:18px;color:#6B7385;">
                  El enlace vence en <b style="color:#9AA3B5;">15 minutos</b> y solo funciona una vez${if (web) ", en el navegador donde lo pediste" else ""}.
                  Si no solicitaste este acceso, ignora este correo.
                </td></tr>
                <tr><td style="padding:20px 30px 0 30px;">
                  <div style="height:1px;background-color:#333949;font-size:0;">&nbsp;</div>
                </td></tr>
                <tr><td style="padding:16px 30px 26px 30px;font-family:Arial,Helvetica,sans-serif;font-size:11px;line-height:17px;color:#6B7385;">
                  ${if (web) "¿El botón no funciona? Copia este enlace en el navegador donde lo pediste:" else "¿El botón no abre la app? Copia este enlace en el navegador de tu teléfono:"}<br>
                  <span style="font-family:'Courier New',monospace;font-size:11px;color:#7FB7F0;word-break:break-all;">$appLink</span>
                  <div style="padding-top:14px;color:#4E5566;">— El Puesto</div>
                </td></tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
    """.trimIndent()
}
