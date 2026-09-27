package com.alephri.elpuesto.data

import android.content.Context
import com.alephri.elpuesto.model.NotificationPrefs

/**
 * Preferencias de configuración locales (notificaciones, pausa de ubicación). Provisional:
 * se guardan en SharedPreferences y se combinan con los defaults de la semilla, hasta que
 * exista un endpoint real de configuración por usuario.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("el_puesto_settings", Context.MODE_PRIVATE)

    fun notificationPrefs(default: NotificationPrefs) = NotificationPrefs(
        newConvocatorias = prefs.getBoolean("n_conv", default.newConvocatorias),
        scheduleChanges = prefs.getBoolean("n_sched", default.scheduleChanges),
        chatMessages = prefs.getBoolean("n_chat", default.chatMessages),
        liveEventBar = prefs.getBoolean("n_bar", default.liveEventBar),
    )

    fun saveNotificationPrefs(p: NotificationPrefs) {
        prefs.edit()
            .putBoolean("n_conv", p.newConvocatorias)
            .putBoolean("n_sched", p.scheduleChanges)
            .putBoolean("n_chat", p.chatMessages)
            .putBoolean("n_bar", p.liveEventBar)
            .apply()
    }

    // Pausa de compartir ubicación (botón "Pausar" de la notificación): vale para ESE
    // evento; el siguiente evento vuelve a arrancar solo. La allowlist vive en el backend.
    fun locationPausedEvent(): String? = prefs.getString("loc_paused_event", null)
    fun setLocationPausedEvent(eventId: String?) =
        prefs.edit().apply { if (eventId == null) remove("loc_paused_event") else putString("loc_paused_event", eventId) }.apply()

    /**
     * Fin de sesión: todo lo de aquí es del oficial (avisos elegidos, pausa de ubicación,
     * chats silenciados) y no pasa al siguiente. Lo del teléfono (actualizaciones) vive en
     * otro archivo.
     */
    fun clearUserData() { prefs.edit().clear().apply() }

    // Chats silenciados (solo local, privacy-first): no disparan notificación del sistema.
    fun mutedChatIds(): Set<String> = prefs.getStringSet("muted_chats", emptySet()) ?: emptySet()
    fun setChatMuted(chatId: String, muted: Boolean) {
        prefs.edit()
            .putStringSet("muted_chats", if (muted) mutedChatIds() + chatId else mutedChatIds() - chatId)
            .apply()
    }
}
