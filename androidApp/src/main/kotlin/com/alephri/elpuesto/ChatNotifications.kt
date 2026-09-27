package com.alephri.elpuesto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Notificaciones del sistema para mensajes de chat (estilo WhatsApp): una por chat
 * (id = hash del chatId), disparadas por el WebSocket mientras la app vive; abrir la
 * conversación la retira. Push real (app cerrada) = FCM, pendiente.
 */
object ChatNotifications {
    private const val CHANNEL_MENSAJES = "mensajes"
    private const val CHANNEL_AVISOS = "avisos"
    private const val CHANNEL_RECORDATORIOS = "recordatorios"

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MENSAJES, "Mensajes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mensajes nuevos en tus chats"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AVISOS, "Avisos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Eventos en curso, convocatorias nuevas y ubicación compartida contigo"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDATORIOS, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Recordatorios de tu agenda y cierres de convocatoria"
            },
        )
    }

    /**
     * Qué se ve con el teléfono BLOQUEADO si el oficial pidió ocultar el contenido sensible
     * (si no, Android muestra la notificación completa, como en cualquier app). En bloqueo
     * solo el MbM se ve entero, por decisión de producto: lo demás (mensajes, nombres,
     * agenda) puede decir de más a quien tome el teléfono.
     */
    private sealed interface Locked {
        /** Nada privado: se ve completa. */
        data object Full : Locked

        /** Solo este título genérico, sin el texto. */
        data class Only(val title: String) : Locked
    }

    /** ¿Podemos notificar? (Android 13+ exige el permiso runtime; sin él, notify() lanza). */
    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Notificación con intent explícito a [MainActivity]: SINGLE_TOP entrega onNewIntent
     * si la app vive (sin recrearla) y arranque en frío si no; los extras dicen a dónde
     * navegar. requestCode = notifId para que cada notificación conserve SU intent.
     */
    private fun post(
        context: Context,
        channel: String,
        notifId: Int,
        title: String,
        text: String,
        locked: Locked,
        extras: android.content.Intent.() -> android.content.Intent,
    ) {
        if (!allowed(context)) return
        ensureChannels(context)
        val launch = android.content.Intent(context, MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .extras()
        val pending = PendingIntent.getActivity(
            context, notifId, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
        when (locked) {
            Locked.Full -> b.setVisibility(Notification.VISIBILITY_PUBLIC)
            is Locked.Only -> b.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(
                Notification.Builder(context, channel)
                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                    .setContentTitle(locked.title)
                    .build(),
            )
        }
        val n = b.build()
        manager(context).notify(notifId, n)
    }

    fun notify(context: Context, chatId: String, chatName: String, sender: String, text: String) =
        post(context, CHANNEL_MENSAJES, chatId.hashCode(), chatName, "$sender: $text", Locked.Only("Mensaje nuevo")) {
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        }

    /** Invitación a un chat (público o privado): tocar abre sus detalles (quiénes están + Aceptar). */
    fun notifyChatInvite(context: Context, chatId: String, chatName: String, inviter: String) =
        post(
            context, CHANNEL_MENSAJES, ("invite:$chatId").hashCode(), "Te invitaron a un chat",
            "$inviter te invitó a «$chatName»", Locked.Only("Te invitaron a un chat"),
        ) {
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        }

    /** Evento activado por el admin: tocar abre el Modo evento. */
    fun notifyEventoActivo(context: Context, eventId: String, name: String) =
        post(context, CHANNEL_AVISOS, ("evento:$eventId").hashCode(), "Evento en curso", name, Locked.Only("Evento en curso")) {
            putExtra(MainActivity.EXTRA_OPEN_EVENT, true)
        }

    /**
     * Control cambió el MbM del evento activo: tocar abre el Modo evento en el MbM. Id fijo
     * por evento: varias ediciones seguidas reemplazan el aviso en vez de apilarse.
     */
    fun notifyMbmCambio(context: Context, eventId: String, eventName: String) =
        post(
            context, CHANNEL_AVISOS, ("mbm:$eventId").hashCode(), "Cambió el MbM",
            "$eventName · Control actualizó el minuto a minuto", Locked.Full,
        ) {
            putExtra(MainActivity.EXTRA_OPEN_EVENT, true)
            putExtra(MainActivity.EXTRA_EVENT_PAGE, 1)
        }

    /** Convocatoria publicada: tocar abre su detalle. */
    fun notifyConvocatoria(context: Context, id: String, name: String) =
        post(context, CHANNEL_AVISOS, ("convocatoria:$id").hashCode(), "Convocatoria nueva", name, Locked.Only("Convocatoria nueva")) {
            putExtra(MainActivity.EXTRA_CONVOCATORIA_ID, id)
        }

    /** Recordatorio programado (agenda o convocatoria): tocar abre su detalle. */
    fun notifyReminder(
        context: Context,
        reminderId: String,
        title: String,
        text: String,
        agendaId: String?,
        convocatoriaId: String?,
    ) = post(context, CHANNEL_RECORDATORIOS, reminderId.hashCode(), title, text, Locked.Only("Recordatorio")) {
        agendaId?.let { putExtra(MainActivity.EXTRA_AGENDA_ID, it) }
        convocatoriaId?.let { putExtra(MainActivity.EXTRA_CONVOCATORIA_ID, it) }
        this
    }

    /** Transparencia: alguien empezó a compartirte su ubicación (tocar abre la config). */
    fun notifyLocationShare(context: Context, ownerId: String, name: String) =
        post(
            context, CHANNEL_AVISOS, ("locshare:$ownerId").hashCode(),
            "Te comparten su ubicación", "$name podrá mostrarte dónde está durante los eventos",
            Locked.Only("Te comparten su ubicación"),
        ) {
            putExtra(MainActivity.EXTRA_OPEN_LOCATION, true)
        }

    /**
     * Una foto que esperaba señal (bitácora o chat) no se subió: el servidor la rechazó
     * (p. ej. cupo de fotos lleno). Se avisa con su motivo; tocar abre la app.
     */
    fun notifyUploadRejected(context: Context, reason: String) =
        post(
            context, CHANNEL_AVISOS, "upload-rejected".hashCode(), "No se subió una foto", reason,
            Locked.Only("No se subió una foto"),
        ) { this }

    fun cancel(context: Context, chatId: String) = manager(context).cancel(chatId.hashCode())

    /** Fin de sesión: ninguna notificación del oficial que se va se queda en la barra. */
    fun cancelAll(context: Context) = manager(context).cancelAll()
}
