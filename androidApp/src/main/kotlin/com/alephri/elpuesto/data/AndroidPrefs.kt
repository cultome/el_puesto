package com.alephri.elpuesto.data

import android.content.Context
import com.alephri.elpuesto.model.NotificationPrefs

/**
 * Preferencias locales del oficial en SharedPreferences: onboarding ([ProfileStore]),
 * avisos/pausa/silenciados ([SettingsStore]) y el mapa de ids temporales del outbox.
 */
class AndroidPrefs(context: Context) : LocalPrefs {
    private val profile = ProfileStore(context)
    private val settings = SettingsStore(context)
    private val outbox = context.getSharedPreferences("el_puesto_outbox", Context.MODE_PRIVATE)

    override var onboardingDone: Boolean
        get() = profile.onboardingDone
        set(v) { profile.onboardingDone = v }

    override fun notificationPrefs(default: NotificationPrefs) = settings.notificationPrefs(default)
    override fun saveNotificationPrefs(p: NotificationPrefs) = settings.saveNotificationPrefs(p)
    override fun locationPausedEvent(): String? = settings.locationPausedEvent()
    override fun setLocationPausedEvent(eventId: String?) = settings.setLocationPausedEvent(eventId)
    override fun mutedChatIds(): Set<String> = settings.mutedChatIds()
    override fun setChatMuted(chatId: String, muted: Boolean) = settings.setChatMuted(chatId, muted)
    override fun clearUserData() = settings.clearUserData()

    override fun outboxRealId(tempId: String): String? = outbox.getString("map:$tempId", null)
    override fun mapOutboxId(tempId: String, real: String) = outbox.edit().putString("map:$tempId", real).apply()
    override fun clearOutboxIds() = outbox.edit().clear().apply()
}
