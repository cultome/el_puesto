package com.alephri.elpuesto.web

import com.alephri.elpuesto.data.AuthStore
import com.alephri.elpuesto.data.ChecklistRow
import com.alephri.elpuesto.data.ImageCache
import com.alephri.elpuesto.data.LocalDb
import com.alephri.elpuesto.data.LocalPrefs
import com.alephri.elpuesto.data.OutboxRow
import com.alephri.elpuesto.data.nowMs
import com.alephri.elpuesto.model.AccountStatus
import com.alephri.elpuesto.model.NotificationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// —————————————————————— localStorage ——————————————————————

private fun lsGet(key: String): String? = js("localStorage.getItem(key)")
private fun lsSet(key: String, value: String): Unit = js("{ localStorage.setItem(key, value); }")
private fun lsRemove(key: String): Unit = js("{ localStorage.removeItem(key); }")

/** localStorage con prefijo; si el navegador lo bloquea (modo privado estricto), no truena. */
internal object Local {
    private const val PREFIX = "elpuesto."
    fun get(key: String): String? = runCatching { lsGet(PREFIX + key) }.getOrNull()
    fun set(key: String, value: String?) {
        runCatching { if (value == null) lsRemove(PREFIX + key) else lsSet(PREFIX + key, value) }
    }
}

// —————————————————————— Caché local: en MEMORIA ——————————————————————

/**
 * Caché + cola de envíos de la web, SOLO en memoria (vive lo que vive la pestaña): una
 * computadora del autódromo puede ser compartida y en el navegador no queda nada del
 * oficial (emergencia, mensajes, bitácora) al cerrar. Recargar la página vuelve a pedir
 * todo a la red; lo encolado sin señal se pierde si se cierra la pestaña antes de enviarlo.
 *
 * Mismo contrato que `ElPuesto.sq`: los Flows emiten tras cada cambio de su tabla y,
 * dentro de [transaction], una sola vez al terminar.
 */
class MemoryLocalDb : LocalDb {
    private enum class T { OFFICER, EVENT, ASSIGNMENT, MATE, SESSION, AGENDA, CIRCUIT, CHECKLIST, OUTBOX }

    private class EventRow(val data: ByteArray, var active: Boolean)
    private class Ordered(val key: String, val ord: Long, val data: ByteArray)

    private var officer: Pair<String, ByteArray>? = null
    private val events = LinkedHashMap<String, EventRow>()
    private val assignments = HashMap<String, ByteArray>()
    private val mates = HashMap<String, MutableList<Ordered>>()
    private val sessions = LinkedHashMap<String, Triple<String, Long, ByteArray>>() // id → (evento, ord, data)
    private val agenda = LinkedHashMap<String, Pair<Long, ByteArray>>()
    private val circuits = HashMap<String, String>()
    private val checklist = LinkedHashMap<String, Pair<String, ChecklistRow>>() // id → (evento, fila)
    private val checklistOrd = HashMap<String, Long>()
    private val outbox = mutableListOf<OutboxRow>()
    private var outboxSeq = 0L
    private val catalog = HashMap<String, ByteArray>()

    private val versions = T.entries.associateWith { MutableStateFlow(0L) }
    private var txDepth = 0
    private val dirty = mutableSetOf<T>()

    private fun changed(t: T) {
        if (txDepth > 0) dirty += t else versions.getValue(t).value++
    }

    private fun <R> watch(t: T, read: () -> R): Flow<R> = versions.getValue(t).map { read() }

    override fun <R> transaction(block: () -> R): R {
        txDepth++
        try {
            return block()
        } finally {
            txDepth--
            if (txDepth == 0) {
                dirty.forEach { versions.getValue(it).value++ }
                dirty.clear()
            }
        }
    }

    override fun selectOfficer(): ByteArray? = officer?.second
    override fun officerFlow(): Flow<ByteArray?> = watch(T.OFFICER) { officer?.second }
    override fun upsertOfficer(id: String, data: ByteArray) { officer = id to data; changed(T.OFFICER) }
    override fun clearOfficers() { officer = null; changed(T.OFFICER) }

    override fun clearActiveEvent() { events.values.forEach { it.active = false }; changed(T.EVENT) }
    override fun upsertEvent(id: String, data: ByteArray, active: Long) { events[id] = EventRow(data, active != 0L); changed(T.EVENT) }
    override fun selectActiveEvent(): ByteArray? = events.values.firstOrNull { it.active }?.data
    override fun activeEventFlow(): Flow<ByteArray?> = watch(T.EVENT) { selectActiveEvent() }

    override fun upsertAssignment(eventId: String, data: ByteArray) { assignments[eventId] = data; changed(T.ASSIGNMENT) }
    override fun selectAssignment(eventId: String): ByteArray? = assignments[eventId]
    override fun assignmentFlow(eventId: String): Flow<ByteArray?> = watch(T.ASSIGNMENT) { assignments[eventId] }

    override fun deleteMates(eventId: String) { mates.remove(eventId); changed(T.MATE) }
    override fun insertMate(eventId: String, officerId: String, ord: Long, data: ByteArray) {
        val list = mates.getOrPut(eventId) { mutableListOf() }
        list.removeAll { it.key == officerId }
        list += Ordered(officerId, ord, data)
        changed(T.MATE)
    }
    override fun matesFlow(eventId: String): Flow<List<ByteArray>> =
        watch(T.MATE) { mates[eventId].orEmpty().sortedBy { it.ord }.map { it.data } }

    override fun deleteSessions(eventId: String) { sessions.entries.removeAll { it.value.first == eventId }; changed(T.SESSION) }
    override fun insertSession(id: String, eventId: String, ord: Long, data: ByteArray) { sessions[id] = Triple(eventId, ord, data); changed(T.SESSION) }
    override fun selectSessions(eventId: String): List<ByteArray> =
        sessions.values.filter { it.first == eventId }.sortedBy { it.second }.map { it.third }
    override fun sessionsFlow(eventId: String): Flow<List<ByteArray>> = watch(T.SESSION) { selectSessions(eventId) }

    override fun deleteAgenda() { agenda.clear(); changed(T.AGENDA) }
    override fun insertAgenda(id: String, ord: Long, data: ByteArray) { agenda[id] = ord to data; changed(T.AGENDA) }
    override fun selectAgenda(): List<ByteArray> = agenda.values.sortedBy { it.first }.map { it.second }
    override fun agendaFlow(): Flow<List<ByteArray>> = watch(T.AGENDA) { selectAgenda() }

    override fun upsertCircuit(id: String, name: String) { circuits[id] = name; changed(T.CIRCUIT) }
    override fun circuitNameFlow(id: String): Flow<String?> = watch(T.CIRCUIT) { circuits[id] }

    override fun deleteChecklist(eventId: String) { checklist.entries.removeAll { it.value.first == eventId }; changed(T.CHECKLIST) }
    override fun insertChecklist(id: String, eventId: String, ord: Long, text: String, done: Long) {
        checklist[id] = eventId to ChecklistRow(id, text, done)
        checklistOrd[id] = ord
        changed(T.CHECKLIST)
    }
    override fun selectChecklist(eventId: String): List<ChecklistRow> =
        checklist.values.filter { it.first == eventId }.sortedBy { checklistOrd[it.second.id] ?: 0L }.map { it.second }
    override fun checklistFlow(eventId: String): Flow<List<ChecklistRow>> = watch(T.CHECKLIST) { selectChecklist(eventId) }
    override fun setChecklistDone(done: Long, id: String) {
        val (ev, row) = checklist[id] ?: return
        checklist[id] = ev to row.copy(done = done)
        changed(T.CHECKLIST)
    }

    override fun enqueue(kind: String, refId: String, payload: String, createdAt: Long) {
        outbox += OutboxRow(++outboxSeq, kind, refId, payload, createdAt)
        changed(T.OUTBOX)
    }
    override fun selectOutbox(): List<OutboxRow> = outbox.toList()
    override fun outboxFlow(): Flow<List<OutboxRow>> = watch(T.OUTBOX) { outbox.toList() }
    override fun countOutboxFlow(): Flow<Long> = watch(T.OUTBOX) { outbox.size.toLong() }
    override fun deleteOutbox(seq: Long) { if (outbox.removeAll { it.seq == seq }) changed(T.OUTBOX) }

    override fun putCatalog(key: String, data: ByteArray) { catalog[key] = data }
    override fun getCatalog(key: String): ByteArray? = catalog[key]
    override fun catalogKeys(prefix: String): List<String> = catalog.keys.filter { it.startsWith(prefix) }
    override fun deleteCatalog(key: String) { catalog.remove(key) }

    override fun wipeAll() = transaction {
        officer = null; events.clear(); assignments.clear(); mates.clear(); sessions.clear()
        agenda.clear(); circuits.clear(); checklist.clear(); checklistOrd.clear(); outbox.clear(); catalog.clear()
        T.entries.forEach(::changed)
    }
}

/** Imágenes en memoria (lo que dura la pestaña), con tope y descarte de lo más viejo. */
class MemoryImageCache(private val maxBytes: Long = 60L * 1024 * 1024) : ImageCache {
    private class Entry(val bytes: ByteArray, val etag: String?, var used: Long)

    private val entries = HashMap<String, Entry>()
    private val missing = HashSet<String>()
    private var total = 0L

    override fun read(path: String): ByteArray? = entries[path]?.also { it.used = nowMs() }?.bytes
    override fun etag(path: String): String? = entries[path]?.etag
    override fun isMissing(path: String): Boolean = path in missing
    override fun known(path: String): Boolean = path in entries || path in missing

    override fun write(path: String, bytes: ByteArray, etag: String?) {
        entries.remove(path)?.let { total -= it.bytes.size }
        entries[path] = Entry(bytes, etag, nowMs())
        total += bytes.size
        missing -= path
        trim(maxBytes)
    }

    override fun markMissing(path: String) {
        entries.remove(path)?.let { total -= it.bytes.size }
        missing += path
    }

    override fun delete(path: String) {
        entries.remove(path)?.let { total -= it.bytes.size }
        missing -= path
    }

    override fun clear() {
        entries.clear(); missing.clear(); total = 0
    }

    override fun trim(maxBytes: Long) {
        if (total <= maxBytes) return
        for ((path, e) in entries.entries.sortedBy { it.value.used }) {
            if (total <= maxBytes) break
            entries.remove(path)
            total -= e.bytes.size
        }
    }
}

/**
 * Preferencias del oficial en localStorage (nada sensible: avisos, chats silenciados,
 * onboarding). Los ids temporales del outbox, en memoria como la cola.
 */
class WebPrefs : LocalPrefs {
    private val outboxIds = HashMap<String, String>()

    override var onboardingDone: Boolean
        get() = Local.get("onboardingDone") == "1"
        set(v) = Local.set("onboardingDone", if (v) "1" else null)

    override fun notificationPrefs(default: NotificationPrefs) = NotificationPrefs(
        newConvocatorias = Local.get("n_conv")?.toBooleanStrictOrNull() ?: default.newConvocatorias,
        scheduleChanges = Local.get("n_sched")?.toBooleanStrictOrNull() ?: default.scheduleChanges,
        chatMessages = Local.get("n_chat")?.toBooleanStrictOrNull() ?: default.chatMessages,
        liveEventBar = Local.get("n_bar")?.toBooleanStrictOrNull() ?: default.liveEventBar,
    )

    override fun saveNotificationPrefs(p: NotificationPrefs) {
        Local.set("n_conv", p.newConvocatorias.toString())
        Local.set("n_sched", p.scheduleChanges.toString())
        Local.set("n_chat", p.chatMessages.toString())
        Local.set("n_bar", p.liveEventBar.toString())
    }

    override fun locationPausedEvent(): String? = Local.get("loc_paused_event")
    override fun setLocationPausedEvent(eventId: String?) = Local.set("loc_paused_event", eventId)

    override fun mutedChatIds(): Set<String> =
        Local.get("muted_chats")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    override fun setChatMuted(chatId: String, muted: Boolean) {
        val next = if (muted) mutedChatIds() + chatId else mutedChatIds() - chatId
        Local.set("muted_chats", next.joinToString(",").ifEmpty { null })
    }

    override fun clearUserData() {
        listOf("n_conv", "n_sched", "n_chat", "n_bar", "loc_paused_event", "muted_chats").forEach { Local.set(it, null) }
    }

    override fun outboxRealId(tempId: String): String? = outboxIds[tempId]
    override fun mapOutboxId(tempId: String, real: String) { outboxIds[tempId] = real }
    override fun clearOutboxIds() = outboxIds.clear()
}

/**
 * Sesión en el navegador: el access token SOLO en memoria; el refresh vive en una cookie
 * HttpOnly (`ep_rt`, Path=/auth) que la app no ve — un XSS no se lo puede llevar. En
 * localStorage solo lo no secreto: la marca "hay sesión" (para intentar renovar al
 * recargar), el último estado y oficial, y el verificador PKCE del enlace pedido (el
 * correo se abre en OTRA pestaña, que lo necesita para canjearlo).
 */
class WebAuthStore : AuthStore {
    override var jwt: String? = null

    /** Nunca se ve: vive en la cookie HttpOnly. */
    override var refresh: String?
        get() = null
        set(_) {}

    override val hasSession: Boolean get() = jwt != null || Local.get("session") == "1"

    override var lastStatus: AccountStatus?
        get() = Local.get("status")?.let { runCatching { AccountStatus.valueOf(it) }.getOrNull() }
        set(v) = Local.set("status", v?.name)

    override fun save(access: String, refresh: String?) {
        jwt = access
        Local.set("session", "1")
    }

    override fun clear() {
        jwt = null
        Local.set("session", null)
        Local.set("status", null)
    }

    override fun savePkce(verifier: String, email: String) {
        Local.set("pkce_verifier", verifier)
        Local.set("pkce_at", nowMs().toString())
    }

    override fun pkceVerifier(): String? {
        val v = Local.get("pkce_verifier") ?: return null
        val at = Local.get("pkce_at")?.toLongOrNull() ?: 0L
        // El enlace vence a los 15 min; el verificador se guarda el doble, por holgura.
        if (nowMs() - at > 30 * 60_000L) { clearPkce(); return null }
        return v
    }

    override fun clearPkce() {
        Local.set("pkce_verifier", null)
        Local.set("pkce_at", null)
    }

    override var lastOfficerId: String?
        get() = Local.get("last_officer")
        set(v) = Local.set("last_officer", v)

    override var lastLinkHash: String?
        get() = Local.get("last_link")
        set(v) = Local.set("last_link", v)
}
