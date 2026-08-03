package app.suhani.spikes

import android.database.sqlite.SQLiteDatabase
import app.suhani.core.SqlDriver
import java.io.File

/** android.database implementation of the ledger's SQL seam. */
class AndroidSqlDriver(dbFile: File) : SqlDriver {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
        execSQL("PRAGMA foreign_keys=ON")
    }

    private fun bindArgs(args: List<Any?>): Array<Any?> = args.map {
        when (it) {
            is Boolean -> if (it) 1L else 0L
            else -> it
        }
    }.toTypedArray()

    override fun exec(sql: String, args: List<Any?>): Int {
        db.execSQL(sql, bindArgs(args))
        // Affected-row count for UPDATE/DELETE via changes(); DDL reports 0.
        return db.rawQuery("SELECT changes()", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        val stmt = db.compileStatement(sql)
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> stmt.bindNull(idx)
                is Long -> stmt.bindLong(idx, v)
                is Int -> stmt.bindLong(idx, v.toLong())
                is Double -> stmt.bindDouble(idx, v)
                is Float -> stmt.bindDouble(idx, v.toDouble())
                is Boolean -> stmt.bindLong(idx, if (v) 1 else 0)
                else -> stmt.bindString(idx, v.toString())
            }
        }
        return stmt.use { it.executeInsert() }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        val selectionArgs = args.map { it?.toString() }.toTypedArray()
        // rawQuery binds all args as strings; SQLite's type affinity makes
        // numeric comparisons still work for our integer ids and ISO dates.
        db.rawQuery(sql, selectionArgs).use { c ->
            val out = mutableListOf<Map<String, Any?>>()
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    }
                }
                out.add(row)
            }
            return out
        }
    }
}
