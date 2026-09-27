package com.alephri.elpuesto.data

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * La caché local en SQLite (SQLDelight, con migraciones: ver src/main/sqldelight). Las
 * consultas son las de `ElPuesto.sq`; los Flows emiten tras cada cambio de su tabla y,
 * dentro de una transacción, una sola vez al hacer commit.
 */
class SqlDelightLocalDb(context: Context) : LocalDb {
    private val q = createDatabase(context).elPuestoQueries
    private val io = Dispatchers.IO

    override fun <T> transaction(block: () -> T): T = q.transactionWithResult { block() }

    override fun selectOfficer(): ByteArray? = q.selectOfficer().executeAsOneOrNull()
    override fun officerFlow(): Flow<ByteArray?> = q.selectOfficer().asFlow().mapToOneOrNull(io)
    override fun upsertOfficer(id: String, data: ByteArray) = q.upsertOfficer(id, data)
    override fun clearOfficers() = q.clearOfficers()

    override fun clearActiveEvent() = q.clearActiveEvent()
    override fun upsertEvent(id: String, data: ByteArray, active: Long) = q.upsertEvent(id, data, active)
    override fun selectActiveEvent(): ByteArray? = q.selectActiveEvent().executeAsOneOrNull()
    override fun activeEventFlow(): Flow<ByteArray?> = q.selectActiveEvent().asFlow().mapToOneOrNull(io)

    override fun upsertAssignment(eventId: String, data: ByteArray) = q.upsertAssignment(eventId, data)
    override fun selectAssignment(eventId: String): ByteArray? = q.selectAssignment(eventId).executeAsOneOrNull()
    override fun assignmentFlow(eventId: String): Flow<ByteArray?> = q.selectAssignment(eventId).asFlow().mapToOneOrNull(io)

    override fun deleteMates(eventId: String) = q.deleteMates(eventId)
    override fun insertMate(eventId: String, officerId: String, ord: Long, data: ByteArray) = q.insertMate(eventId, officerId, ord, data)
    override fun matesFlow(eventId: String): Flow<List<ByteArray>> = q.selectMates(eventId).asFlow().mapToList(io)

    override fun deleteSessions(eventId: String) = q.deleteSessions(eventId)
    override fun insertSession(id: String, eventId: String, ord: Long, data: ByteArray) = q.insertSession(id, eventId, ord, data)
    override fun selectSessions(eventId: String): List<ByteArray> = q.selectSessions(eventId).executeAsList()
    override fun sessionsFlow(eventId: String): Flow<List<ByteArray>> = q.selectSessions(eventId).asFlow().mapToList(io)

    override fun deleteAgenda() = q.deleteAgenda()
    override fun insertAgenda(id: String, ord: Long, data: ByteArray) = q.insertAgenda(id, ord, data)
    override fun selectAgenda(): List<ByteArray> = q.selectAgenda().executeAsList()
    override fun agendaFlow(): Flow<List<ByteArray>> = q.selectAgenda().asFlow().mapToList(io)

    override fun upsertCircuit(id: String, name: String) = q.upsertCircuit(id, name)
    override fun circuitNameFlow(id: String): Flow<String?> = q.selectCircuitName(id).asFlow().mapToOneOrNull(io)

    override fun deleteChecklist(eventId: String) = q.deleteChecklist(eventId)
    override fun insertChecklist(id: String, eventId: String, ord: Long, text: String, done: Long) =
        q.insertChecklist(id, eventId, ord, text, done)
    override fun selectChecklist(eventId: String): List<ChecklistRow> =
        q.selectChecklist(eventId).executeAsList().map { ChecklistRow(it.id, it.text, it.done) }
    override fun checklistFlow(eventId: String): Flow<List<ChecklistRow>> =
        q.selectChecklist(eventId).asFlow().mapToList(io).map { rows -> rows.map { ChecklistRow(it.id, it.text, it.done) } }
    override fun setChecklistDone(done: Long, id: String) = q.setChecklistDone(done, id)

    override fun enqueue(kind: String, refId: String, payload: String, createdAt: Long) = q.enqueue(kind, refId, payload, createdAt)
    override fun selectOutbox(): List<OutboxRow> =
        q.selectOutbox().executeAsList().map { OutboxRow(it.seq, it.kind, it.refId, it.payload, it.createdAt) }
    override fun outboxFlow(): Flow<List<OutboxRow>> =
        q.selectOutbox().asFlow().mapToList(io).map { rows -> rows.map { OutboxRow(it.seq, it.kind, it.refId, it.payload, it.createdAt) } }
    override fun countOutboxFlow(): Flow<Long> = q.countOutbox().asFlow().mapToOne(io)
    override fun deleteOutbox(seq: Long) = q.deleteOutbox(seq)

    override fun putCatalog(key: String, data: ByteArray) = q.putCatalog(key, data)
    override fun getCatalog(key: String): ByteArray? = q.getCatalog(key).executeAsOneOrNull()
    override fun catalogKeys(prefix: String): List<String> = q.selectCatalogKeys("$prefix%").executeAsList()
    override fun deleteCatalog(key: String) = q.deleteCatalog(key)

    override fun wipeAll() = q.transaction {
        q.wipeOutbox(); q.wipeCatalog(); q.wipeOfficer(); q.wipeEvent(); q.wipeAssignment()
        q.wipeMate(); q.wipeSession(); q.wipeAgenda(); q.wipeCircuit(); q.wipeChecklist()
    }
}
