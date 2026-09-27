package com.alephri.elpuesto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alephri.elpuesto.model.AgendaEntry
import com.alephri.elpuesto.model.AgendaKind
import com.alephri.elpuesto.model.Convocatoria
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Recordatorios locales programados (sin backend): recordatorios de agenda con hora y
 * "Recordarme antes del cierre" de convocatorias. AlarmManager (inexacto, sin permiso
 * especial) + store en SharedPreferences para sobrevivir reinicios (BootReceiver) y
 * re-programarse al abrir la app. Tocar la notificación navega al detalle.
 */
object Reminders {

    @Serializable
    data class Stored(
        val id: String, // "agenda:<entryId>" | "conv:<convocatoriaId>"
        val at: Long, // epoch millis
        val title: String,
        val text: String,
        val agendaId: String? = null,
        val convocatoriaId: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(context: Context) =
        context.getSharedPreferences("el_puesto_reminders", Context.MODE_PRIVATE)

    private fun load(context: Context): List<Stored> =
        prefs(context).getString("reminders", null)?.let {
            runCatching { json.decodeFromString<List<Stored>>(it) }.getOrNull()
        } ?: emptyList()

    private fun save(context: Context, list: List<Stored>) =
        prefs(context).edit().putString("reminders", json.encodeToString(list)).apply()

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra("reminderId", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alarm(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun scheduleAlarm(context: Context, r: Stored) =
        alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(context, r.id))

    /** Programa (o re-programa) un recordatorio y lo persiste en el store. */
    private fun upsert(context: Context, r: Stored) {
        save(context, load(context).filterNot { it.id == r.id } + r)
        scheduleAlarm(context, r)
    }

    private fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
        alarm(context).cancel(pending(context, id))
    }

    // —— Agenda: recordatorios personales (kind REMINDER) con hora ——

    /**
     * Sincroniza los recordatorios programados con la agenda recién sincronizada: los
     * REMINDER personales futuros con hora quedan programados; los que ya no existen (o
     * cambiaron de hora) se corrigen. Idempotente — se llama tras cada sync de agenda.
     */
    fun syncAgenda(context: Context, agenda: List<AgendaEntry>) {
        val now = System.currentTimeMillis()
        val wanted = agenda
            .filter { it.personal && it.kind == AgendaKind.REMINDER && it.at != null }
            .map {
                Stored(
                    id = "agenda:${it.id}", at = it.at!!.toEpochMilliseconds(),
                    title = "Recordatorio", text = it.title, agendaId = it.id,
                )
            }
            .filter { it.at > now }
            .associateBy { it.id }
        val current = load(context).filter { it.agendaId != null }.associateBy { it.id }
        (current.keys - wanted.keys).forEach { remove(context, it) }
        wanted.values.filter { current[it.id] != it }.forEach { upsert(context, it) }
    }

    // —— Convocatorias: "Recordarme antes del cierre" (un día antes; si falta menos, ya) ——

    fun isConvocatoriaSet(context: Context, id: String): Boolean =
        load(context).any { it.id == "conv:$id" }

    fun setConvocatoria(context: Context, c: Convocatoria) {
        val close = c.registrationCloseAt.toEpochMilliseconds()
        val at = maxOf(close - 24 * 60 * 60 * 1000L, System.currentTimeMillis() + 60_000)
        if (at >= close) return // ya cerró: nada que recordar
        upsert(
            context,
            Stored(
                id = "conv:${c.id}", at = at, title = "Cierre de inscripciones",
                text = "«${c.eventName}» cierra pronto — postúlate en el sistema externo.",
                convocatoriaId = c.id,
            ),
        )
    }

    fun cancelConvocatoria(context: Context, id: String) = remove(context, "conv:$id")

    /**
     * Fin de sesión (o cambio de cuenta): cancela TODAS las alarmas programadas y olvida el
     * store — los recordatorios de un oficial no deben sonarle al siguiente.
     */
    fun cancelAll(context: Context) {
        load(context).forEach { alarm(context).cancel(pending(context, it.id)) }
        prefs(context).edit().clear().apply()
    }

    // —— Re-programación (boot, apertura de la app) ——

    /**
     * Re-programa todo el store: los futuros con alarma fresca; los vencidos (el teléfono
     * estaba apagado / la app muerta a la hora) se notifican de una vez y se retiran.
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        load(context).forEach { r ->
            if (r.at <= now) fire(context, r.id) else scheduleAlarm(context, r)
        }
    }

    /** Notifica el recordatorio [id] y lo retira del store. */
    internal fun fire(context: Context, id: String) {
        val r = load(context).firstOrNull { it.id == id } ?: return
        ChatNotifications.notifyReminder(context, r.id, r.title, r.text, r.agendaId, r.convocatoriaId)
        save(context, load(context).filterNot { it.id == id })
    }
}

/** Suena la alarma de un recordatorio → notificación del sistema. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("reminderId")?.let { Reminders.fire(context, it) }
    }
}

/** Las alarmas no sobreviven el reinicio del teléfono: re-programar desde el store. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rescheduleAll(context)
            // La barra fija del evento en curso tampoco sobrevive el reinicio (ni la actualización).
            val pending = goAsync()
            LiveEventBar.syncAsync(context) { pending.finish() }
        }
    }
}
