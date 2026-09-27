package com.alephri.elpuesto.data

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.alephri.elpuesto.db.Database

fun createDatabase(context: Context): Database {
    val driver: SqlDriver = AndroidSqliteDriver(
        Database.Schema, context, "elpuesto.db", callback = SafeMigrations(Database.Schema),
    )
    return Database(driver)
}

/**
 * Migraciones de la base local (los N.sqm de src/main/sqldelight) con red de seguridad: si
 * una falla en un teléfono —p. ej. por un dato que la verificación del build no podía
 * prever—, la app no se queda tronando cada vez que abre. Casi todo aquí es caché del
 * servidor, así que se reconstruye vacía (el siguiente sync la llena) y se rescata la cola de
 * cambios sin enviar (`outbox`), lo único que no existe en otro lado.
 */
private class SafeMigrations(
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            super.onUpgrade(db, oldVersion, newVersion)
            Log.i(TAG, "base local migrada v$oldVersion → v$newVersion")
        } catch (e: Exception) {
            Log.e(TAG, "falló la migración v$oldVersion → v$newVersion: se reconstruye la caché", e)
            rebuildKeepingOutbox(db)
        }
    }

    // Base más nueva que la app (una versión anterior instalada encima, p. ej. `adb install -d`):
    // no hay migraciones de regreso.
    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "base local v$oldVersion más nueva que la app (v$newVersion): se reconstruye")
        rebuildKeepingOutbox(db)
    }

    private fun rebuildKeepingOutbox(db: SupportSQLiteDatabase) {
        val tables = names(db, "SELECT name FROM sqlite_master WHERE type = 'table'")
            .filter { it != "android_metadata" && !it.startsWith("sqlite_") }
        val rescue = "outbox" in tables
        if (rescue) {
            db.execSQL("ALTER TABLE outbox RENAME TO outbox_rescate")
            // Sus índices conservan el nombre y chocarían con los de la outbox nueva.
            names(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'outbox_rescate' AND sql IS NOT NULL")
                .forEach { db.execSQL("DROP INDEX \"$it\"") }
        }
        names(db, "SELECT name FROM sqlite_master WHERE type = 'view'").forEach { db.execSQL("DROP VIEW \"$it\"") }
        tables.filter { it != "outbox" }.forEach { db.execSQL("DROP TABLE \"$it\"") }
        schema.create(AndroidSqliteDriver(db))
        if (!rescue) return
        // Solo las columnas que existen en ambas versiones de la tabla.
        val cols = (columns(db, "outbox") intersect columns(db, "outbox_rescate").toSet())
            .joinToString(", ") { "\"$it\"" }
        try {
            db.execSQL("INSERT INTO outbox ($cols) SELECT $cols FROM outbox_rescate")
            Log.i(TAG, "cola rescatada tras reconstruir la base local")
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo rescatar la cola de cambios sin enviar", e)
        }
        db.execSQL("DROP TABLE outbox_rescate")
    }

    private fun names(db: SupportSQLiteDatabase, sql: String): List<String> =
        db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(\"$table\")").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    private companion object { const val TAG = "ElPuestoSync" }
}
