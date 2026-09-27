package com.alephri.elpuesto.data

import android.content.Context

/**
 * Preferencias locales del perfil propio. Hoy solo el flag de onboarding: la edición del
 * perfil y de la emergencia viaja por el OUTBOX (se superpone mientras está pendiente),
 * ya no como override local permanente.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("el_puesto_profile", Context.MODE_PRIVATE)

    init {
        // Legado (override local provisional, hasta 2026-09-25): se pintaba SIEMPRE encima
        // de lo del servidor (p. ej. el nombre de otra cuenta). Se limpia una vez.
        if (prefs.contains("displayName") || prefs.contains("emgSet")) {
            prefs.edit()
                .remove("displayName").remove("area")
                .remove("emgContactName").remove("emgContactPhone").remove("emgBloodType").remove("emgAllergies")
                .remove("emgSet")
                .apply()
        }
    }

    /** Onboarding (Completar perfil) ya realizado/omitido. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) { prefs.edit().putBoolean("onboardingDone", v).apply() }
}
