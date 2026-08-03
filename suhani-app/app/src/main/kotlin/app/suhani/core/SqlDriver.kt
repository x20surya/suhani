package app.suhani.core

/**
 * Thin SQL seam between the ledger logic and the platform database.
 *
 * The Python reference (silai/ledger.py) is raw SQL over sqlite3; this port
 * keeps that shape instead of an ORM so the two stay line-comparable, and so
 * the ledger runs as pure JVM code: Android supplies an android.database
 * implementation, unit tests supply a sqlite-jdbc one.
 */
interface SqlDriver {
    /** Execute DDL/UPDATE/DELETE. Returns affected row count where known. */
    fun exec(sql: String, args: List<Any?> = emptyList()): Int

    /** Execute INSERT, returns last inserted rowid. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /** SELECT returning rows as column-name -> value (Long/Double/String/null). */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>
}

/** Schema - verbatim port of silai/db.py (_SCHEMA). */
object SilaiSchema {
    val STATEMENTS = listOf(
        """CREATE TABLE IF NOT EXISTS clients (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            name_norm TEXT NOT NULL,
            aliases TEXT NOT NULL DEFAULT '[]',
            phone TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        )""",
        """CREATE TABLE IF NOT EXISTS orders (
            id INTEGER PRIMARY KEY,
            client_id INTEGER NOT NULL REFERENCES clients(id),
            garment TEXT NOT NULL,
            notes TEXT,
            price REAL NOT NULL DEFAULT 0,
            due_date TEXT,
            status TEXT NOT NULL DEFAULT 'open',
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            delivered_at TEXT
        )""",
        """CREATE TABLE IF NOT EXISTS payments (
            id INTEGER PRIMARY KEY,
            client_id INTEGER NOT NULL REFERENCES clients(id),
            order_id INTEGER REFERENCES orders(id),
            amount REAL NOT NULL,
            note TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        )""",
        """CREATE TABLE IF NOT EXISTS notes (
            id INTEGER PRIMARY KEY,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        )""",
        "CREATE INDEX IF NOT EXISTS idx_orders_client ON orders(client_id)",
        "CREATE INDEX IF NOT EXISTS idx_orders_due ON orders(due_date)",
        "CREATE INDEX IF NOT EXISTS idx_payments_client ON payments(client_id)",
    )

    fun create(db: SqlDriver) {
        for (s in STATEMENTS) db.exec(s)
    }
}
