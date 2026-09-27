package com.alephri.elpuesto

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.alephri.elpuesto.model.Session
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Lo que va del MbM según el RELOJ (misma regla que el backend): una actividad está en
 * curso desde su inicio hasta su fin — su duración o, sin duración, el inicio de la
 * siguiente del MISMO día (la última sin duración termina con su día).
 */
internal object MbmClock {
    /** Zona del cronograma (la misma que corta el día del checklist en el backend). */
    val ZONA: ZoneId = ZoneId.of("America/Mexico_City")

    /** [upcoming] = las que aún no empiezan, en orden ([next] es la primera). */
    data class State(
        val current: Session?,
        val currentEnd: LocalDateTime?,
        val upcoming: List<Session>,
        val nextChange: LocalDateTime?,
    ) {
        val next: Session? get() = upcoming.firstOrNull()
    }

    private fun startOf(s: Session): LocalDateTime =
        LocalDateTime.of(java.time.LocalDate.parse(s.day.toString()), java.time.LocalTime.parse(s.time.toString()))

    fun state(schedule: List<Session>, now: LocalDateTime): State {
        val sorted = schedule.sortedWith(compareBy({ it.day.toString() }, { it.time.toString() }))
        val ends = sorted.mapIndexed { i, s ->
            val start = startOf(s)
            s.endsInMin?.let { start.plusMinutes(it.toLong()) }
                ?: sorted.getOrNull(i + 1)?.takeIf { it.day == s.day }?.let(::startOf)
                ?: start.toLocalDate().plusDays(1).atStartOfDay()
        }
        val i = sorted.indices.lastOrNull { !now.isBefore(startOf(sorted[it])) && now.isBefore(ends[it]) }
        val current = i?.let { sorted[it] }
        val upcoming = sorted.filter { startOf(it).isAfter(now) }
        // Próximo momento en que cambia lo que se muestra: fin de la actual o inicio de la siguiente.
        val change = listOfNotNull(i?.let { ends[it] }, upcoming.firstOrNull()?.let(::startOf)).minOrNull()
        return State(current, i?.let { ends[it] }, upcoming, change)
    }
}

/**
 * "Evento en curso · barra fija con la actividad actual" (Configuración → Notificaciones):
 * notificación fija con la actividad EN CURSO del MbM del evento activo (cuenta regresiva
 * a su fin) y la que sigue. Se re-pinta sola con una alarma en cada cambio de actividad,
 * así que funciona con la app cerrada; toca → Modo evento en el MbM. Se quita al apagar la
 * opción, sin evento activo o cuando el MbM ya terminó.
 *
 * Pensada para consultarse CON EL TELÉFONO BLOQUEADO: pública (solo trae el MbM — nunca
 * puesto, rol ni nada del oficial) y en un canal que no es "silencioso"
 * (muchos teléfonos esconden las silenciosas en la pantalla de bloqueo), sin sonido ni
 * vibración. Expandida (se puede sin desbloquear) muestra además las dos que siguen.
 */
object LiveEventBar {
    /**
     * `_2`: el canal original era IMPORTANCE_LOW (silencioso) y Android no deja que la app
     * suba la importancia de un canal existente → canal nuevo y el viejo se borra.
     */
    private const val CHANNEL = "evento_en_curso_2"
    private const val OLD_CHANNEL = "evento_en_curso"
    private const val NOTIF_ID = 4_2_42
    private const val ALARM_REQ = 4_2_43

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Recalcula y pinta (o quita) la barra según la caché local; re-programa su alarma. */
    suspend fun sync(context: Context) {
        val repo = AppGraph.get(context).repo
        val ui = if (repo.notificationPrefs().liveEventBar) repo.event().firstOrNull() else null
        val now = LocalDateTime.now(MbmClock.ZONA)
        val st = ui?.takeIf { it.schedule.isNotEmpty() }?.let { MbmClock.state(it.schedule, now) }
        if (ui == null || st == null || (st.current == null && st.next == null) || !allowed(context)) {
            cancel(context)
            return
        }
        ensureChannel(context)
        val title = st.current?.let { "EN CURSO · ${it.time} ${it.name}" } ?: st.next!!.let { "SIGUE · ${label(it, now)} ${it.name}" }
        val text = if (st.current != null) {
            st.next?.let { "Sigue: ${label(it, now)} ${it.name}" } ?: "Última actividad del MbM"
        } else {
            st.next!!.category.ifBlank { "Próxima actividad del MbM" }
        }
        // Expandida: las dos que vienen después de la siguiente, una por renglón.
        val later = st.upcoming.drop(1).take(2).joinToString("") { "\nDespués: ${label(it, now)} ${it.name}" }
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_EVENT, true)
            .putExtra(MainActivity.EXTRA_EVENT_PAGE, 1) // MbM
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Grupo propio: sin él, Android la agrupa con los demás avisos de la app y en la
            // pantalla de bloqueo queda como un renglón más, sin la cuenta regresiva.
            .setGroup(CHANNEL)
            .setContentIntent(pending)
            // Sin el nombre del evento (subText/summaryText): comparte renglón con el título y
            // lo cortaba ("EN CURSO · 23:00 Pr…"); solo hay un evento activo.
            .setStyle(Notification.BigTextStyle().bigText(text + later))
        // Cuenta regresiva nativa al fin de la actividad en curso.
        st.currentEnd?.takeIf { st.current != null }?.let { end ->
            b.setWhen(end.atZone(MbmClock.ZONA).toInstant().toEpochMilli())
                .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        manager(context).notify(NOTIF_ID, b.build())
        st.nextChange?.let { scheduleAt(context, it.atZone(MbmClock.ZONA).toInstant().toEpochMilli() + 1_000) }
    }

    fun cancel(context: Context) {
        manager(context).cancel(NOTIF_ID)
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    /** "14:30" si es hoy; "sáb 14:30" si es otro día. */
    private fun label(s: Session, now: LocalDateTime): String {
        val day = java.time.LocalDate.parse(s.day.toString())
        if (day == now.toLocalDate()) return "${s.time}"
        val dias = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
        return "${dias[day.dayOfWeek.value - 1]} ${s.time}"
    }

    private fun ensureChannel(context: Context) {
        val nm = manager(context)
        if (nm.getNotificationChannel(OLD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Evento en curso", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Barra fija con la actividad actual del MbM, visible con el teléfono bloqueado"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, ALARM_REQ, Intent(context, LiveEventBarReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Inexacta a propósito (sin permiso de alarmas exactas): la cuenta regresiva es exacta. */
    private fun scheduleAt(context: Context, atMillis: Long) =
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context))

    /** Para receptores: corre [sync] fuera del hilo principal manteniendo vivo el broadcast. */
    internal fun syncAsync(context: Context, done: () -> Unit = {}) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { sync(context.applicationContext) } finally { done() }
        }
    }
}

/** Alarma de la barra: la actividad cambió → re-pintar y programar la siguiente. */
class LiveEventBarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        LiveEventBar.syncAsync(context) { pending.finish() }
    }
}
