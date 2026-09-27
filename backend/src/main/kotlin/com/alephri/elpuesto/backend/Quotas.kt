package com.alephri.elpuesto.backend

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Cupos por oficial: lo que guarda cada uno tiene techo. Sin ellos una sola cuenta llenaba
 * el disco (fotos, bitácora, mensajes) en días — y los respaldos crecían con él. Los
 * límites de ritmo ([RateLimits]) frenan ráfagas; estos frenan la acumulación.
 */
object Quotas {
    /** Entradas de bitácora/planeación por oficial (incluye fotos y notas). */
    const val MAX_TRIP_ITEMS = 2_000

    /** Chats públicos/privados abiertos (no archivados) creados por un oficial. */
    const val MAX_OPEN_CHATS = 30

    /**
     * Fotos (bitácora + chat) de [officerId]: rechaza con 409 si ya llegó a su cupo en MB
     * ([Config.storageQuotaMb]). Se revisa ANTES de leer la foto.
     */
    fun checkPhotos(officerId: String) {
        val used = photoBytes(officerId)
        val max = Config.storageQuotaMb * 1024 * 1024
        if (used >= max) {
            throw Rejected(
                HttpStatusCode.Conflict,
                "Llegaste al límite de fotos guardadas (${Config.storageQuotaMb} MB). Borra algunas de tu bitácora para subir más.",
            )
        }
    }

    /** Bytes de las fotos de un oficial: las de su bitácora y las que mandó a chats. */
    fun photoBytes(officerId: String): Long = transaction {
        val sql = """
            SELECT COALESCE(SUM(octet_length(i.data)), 0) FROM images i
             WHERE (i.kind = 'trip' AND i.owner_id IN (SELECT id FROM trip_items WHERE officer_id = ?))
                OR (i.kind = 'chatmedia' AND i.owner_id IN (SELECT id FROM messages WHERE sender_id = ?))
        """.trimIndent()
        exec(sql, listOf(VarCharColumnType() to officerId, VarCharColumnType() to officerId)) { rs ->
            if (rs.next()) rs.getLong(1) else 0L
        } ?: 0L
    }

    fun checkTripItems(officerId: String) {
        val n = transaction { TripItemsT.selectAll().where { TripItemsT.officerId eq officerId }.count() }
        if (n >= MAX_TRIP_ITEMS) {
            throw Rejected(
                HttpStatusCode.Conflict,
                "Llegaste al límite de $MAX_TRIP_ITEMS entradas en tu bitácora y planeación. Borra algunas para agregar más.",
            )
        }
    }

    fun checkOpenChats(officerId: String) {
        val n = transaction {
            ChatsT.selectAll().where { (ChatsT.creatorId eq officerId) and (ChatsT.archived eq false) }.count()
        }
        if (n >= MAX_OPEN_CHATS) {
            throw Rejected(
                HttpStatusCode.Conflict,
                "Tienes $MAX_OPEN_CHATS chats abiertos creados por ti. Archiva alguno para crear otro.",
            )
        }
    }

}
