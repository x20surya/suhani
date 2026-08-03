package app.suhani.core

import java.sql.Connection
import java.sql.DriverManager

/** sqlite-jdbc implementation for pure-JVM unit tests (in-memory DB per instance). */
class JdbcSqlDriver : SqlDriver {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    private fun bind(sql: String, args: List<Any?>) = conn.prepareStatement(sql).apply {
        args.forEachIndexed { i, v -> setObject(i + 1, v) }
    }

    override fun exec(sql: String, args: List<Any?>): Int =
        bind(sql, args).use { it.executeUpdate() }

    override fun insert(sql: String, args: List<Any?>): Long {
        bind(sql, args).use { it.executeUpdate() }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        bind(sql, args).use { ps ->
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val out = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>(meta.columnCount)
                    for (i in 1..meta.columnCount) {
                        row[meta.getColumnLabel(i)] = when (val v = rs.getObject(i)) {
                            is Int -> v.toLong()
                            is java.math.BigDecimal -> v.toDouble()
                            is Float -> v.toDouble()
                            else -> v // Long, Double, String, null
                        }
                    }
                    out.add(row)
                }
                out
            }
        }
}
