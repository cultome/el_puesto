package com.alephri.elpuesto.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Mientras esta pieza está en pantalla, la ventana lleva FLAG_SECURE: sin capturas ni
 * grabación de pantalla y con la miniatura de "recientes" en blanco. Para la información
 * de emergencia (la propia al verla o editarla, y la ajena que consulta el jefe de
 * puesto). Con contador por actividad: si dos piezas la piden a la vez, la bandera se
 * quita cuando sale la última.
 */
@Composable
actual fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity == null || !enabled) return@DisposableEffect onDispose {}
        SecureFlag.acquire(activity)
        onDispose { SecureFlag.release(activity) }
    }
}

private object SecureFlag {
    private val holders = java.util.WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) {
        val n = (holders[activity] ?: 0) + 1
        holders[activity] = n
        if (n == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        val n = (holders[activity] ?: 1) - 1
        if (n <= 0) {
            holders.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[activity] = n
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
