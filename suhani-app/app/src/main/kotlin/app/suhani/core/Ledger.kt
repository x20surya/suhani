package app.suhani.core

import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Verbatim port of silai/ledger.py - the single source of truth for clients,
 * orders, payments, dues and Suhani's यादें. Every behavior here is covered by
 * the mirrored test suite; deviations from the Python reference are bugs.
 */
class Ledger(private val db: SqlDriver) {

    init {
        SilaiSchema.create(db)
    }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        const val MATCH_THRESHOLD = 0.72
        const val MAX_NOTES = 40
        const val MAX_NOTES_CHARS = 3000
        private val FILLER_TOKENS = setOf("val", "vala", "vale", "ji")

        fun normTokens(text: String): Set<String> =
            text.split(Regex("\\s+")).filter { it.isNotBlank() }
                .map { Translit.normalize(it) }
                .filter { it.length >= 3 && it !in FILLER_TOKENS }
                .toSet()

        fun tokenClose(t: String, u: String): Boolean {
            if (t == u) return true
            if (t.length >= 4 && (u.contains(t) || t.contains(u))) return true
            return Translit.similarity(t, u) >= 0.8
        }

        fun tokensCovered(needles: Set<String>, haystack: Set<String>): Boolean =
            needles.isNotEmpty() && needles.all { t -> haystack.any { u -> tokenClose(t, u) } }
    }

    private fun today(): String = LocalDate.now(IST).toString()

    private fun aliasesOf(row: Map<String, Any?>): List<String> {
        val arr = JSONArray(row["aliases"] as? String ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun clientDict(row: Map<String, Any?>): MutableMap<String, Any?> =
        row.toMutableMap().also { it["aliases"] = aliasesOf(row) }

    // ---------- clients ----------

    fun addClient(name: String, phone: String? = null): Map<String, Any?> {
        val norm = Translit.normalize(name)
        val dup = db.query("SELECT id FROM clients WHERE name_norm=?", listOf(norm)).firstOrNull()
        if (dup != null) {
            val id = (dup["id"] as Number).toLong()
            if (phone != null && getClient(id)["phone"] == null) {
                db.exec("UPDATE clients SET phone=? WHERE id=?", listOf(phone, id))
            }
            return getClient(id).toMutableMap().also { it["duplicate_ignored"] = true }
        }
        val id = db.insert(
            "INSERT INTO clients (name, name_norm, aliases, phone) VALUES (?, ?, ?, ?)",
            listOf(name.trim(), norm, JSONArray(listOf(name.trim())).toString(), phone),
        )
        return getClient(id)
    }

    fun getClient(clientId: Long): Map<String, Any?> {
        val row = db.query("SELECT * FROM clients WHERE id=?", listOf(clientId)).firstOrNull()
            ?: throw IllegalArgumentException("client $clientId not found")
        return clientDict(row)
    }

    fun addAlias(clientId: Long, alias: String): Map<String, Any?> {
        val client = getClient(clientId)
        @Suppress("UNCHECKED_CAST")
        val aliases = (client["aliases"] as List<String>).toMutableList()
        val a = alias.trim()
        if (a.isNotEmpty() && a !in aliases) {
            aliases.add(a)
            db.exec("UPDATE clients SET aliases=? WHERE id=?", listOf(JSONArray(aliases).toString(), clientId))
        }
        return getClient(clientId)
    }

    fun findClient(query: String): List<Map<String, Any?>> {
        var q = query.trim()
        for (suffix in listOf(" जी", " ji", " didi", " दीदी", " aunty", " आंटी", " आँटी", " भाभी")) {
            if (q.lowercase().endsWith(suffix.trim().lowercase()) && q.length > suffix.length) {
                q = q.substring(0, q.length - suffix.length).trim()
            }
        }
        val qNorm = Translit.normalize(q)
        val qTokens = normTokens(q)
        val scored = mutableListOf<Pair<Double, Map<String, Any?>>>()
        for (row in db.query("SELECT * FROM clients")) {
            val client = clientDict(row)
            @Suppress("UNCHECKED_CAST")
            val sources = listOf(client["name"] as String) + (client["aliases"] as List<String>)
            var score = sources.maxOf { Translit.similarity(qNorm, Translit.normalize(it)) }
            for (s in sources) {
                val sTokens = normTokens(s)
                if (tokensCovered(sTokens, qTokens)) {
                    score = maxOf(score, 0.9)
                } else if (tokensCovered(qTokens, sTokens)) {
                    score = maxOf(score, 0.85)
                }
            }
            if (score >= MATCH_THRESHOLD) scored.add(score to client)
        }
        scored.sortByDescending { it.first }
        return scored.map { (s, c) ->
            c.toMutableMap().also { it["match_score"] = Math.round(s * 100) / 100.0 }
        }
    }

    fun listClients(): List<Map<String, Any?>> =
        db.query("SELECT * FROM clients ORDER BY name").map { clientDict(it) }

    // ---------- orders ----------

    fun addOrder(
        clientId: Long,
        garment: String,
        price: Double,
        dueDate: String? = null,
        advance: Double = 0.0,
        notes: String? = null,
    ): Map<String, Any?> {
        getClient(clientId) // validates
        val dup = db.query(
            """SELECT id FROM orders WHERE client_id=? AND garment=? AND price=?
               AND IFNULL(due_date,'')=IFNULL(?,'') AND status='open'""",
            listOf(clientId, garment.trim(), price, dueDate),
        ).firstOrNull()
        if (dup != null) {
            return getOrder((dup["id"] as Number).toLong()).toMutableMap()
                .also { it["duplicate_ignored"] = true }
        }
        val orderId = db.insert(
            "INSERT INTO orders (client_id, garment, price, due_date, notes) VALUES (?, ?, ?, ?, ?)",
            listOf(clientId, garment.trim(), price, dueDate, notes),
        )
        if (advance > 0) {
            db.insert(
                "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, 'advance')",
                listOf(clientId, orderId, advance),
            )
        }
        return getOrder(orderId)
    }

    private val orderSelect = """
        SELECT o.*, c.name AS client_name,
               o.price - COALESCE((SELECT SUM(amount) FROM payments p WHERE p.order_id = o.id), 0) AS balance
        FROM orders o JOIN clients c ON c.id = o.client_id"""

    fun getOrder(orderId: Long): Map<String, Any?> =
        db.query("$orderSelect WHERE o.id=?", listOf(orderId)).firstOrNull()
            ?: throw IllegalArgumentException("order $orderId not found")

    fun updateOrder(
        orderId: Long,
        garment: String? = null,
        price: Double? = null,
        dueDate: String? = null,
        notes: String? = null,
        status: String? = null,
    ): Map<String, Any?> {
        val order = getOrder(orderId)
        var deliveredAt = order["delivered_at"] as? String
        if (status == "delivered" && deliveredAt == null) {
            deliveredAt = ZonedDateTime.now(IST).toOffsetDateTime().toString()
        }
        db.exec(
            "UPDATE orders SET garment=?, price=?, due_date=?, notes=?, status=?, delivered_at=? WHERE id=?",
            listOf(
                garment ?: order["garment"],
                price ?: order["price"],
                dueDate ?: order["due_date"],
                notes ?: order["notes"],
                status ?: order["status"],
                deliveredAt,
                orderId,
            ),
        )
        return getOrder(orderId)
    }

    fun ordersForClient(clientId: Long, openOnly: Boolean = true): List<Map<String, Any?>> {
        var q = "$orderSelect WHERE o.client_id=?"
        if (openOnly) q += " AND o.status NOT IN ('delivered', 'cancelled')"
        q += " ORDER BY o.due_date IS NULL, o.due_date"
        return db.query(q, listOf(clientId))
    }

    fun allOpenOrders(): List<Map<String, Any?>> =
        db.query(
            "$orderSelect WHERE o.status NOT IN ('delivered', 'cancelled') ORDER BY o.due_date IS NULL, o.due_date",
        )

    fun searchOrders(query: String, limit: Int = 12): List<Map<String, Any?>> {
        val qTokens = normTokens(query)
        if (qTokens.isEmpty()) return emptyList()
        val scored = mutableListOf<Pair<Double, Map<String, Any?>>>()
        for (o in db.query("$orderSelect ORDER BY o.id DESC")) {
            val hay = normTokens(
                listOf(o["garment"], o["notes"] ?: "", o["client_name"]).joinToString(" ") { it.toString() },
            )
            val hits = qTokens.count { t -> hay.any { u -> tokenClose(t, u) } }
            if (hits > 0) scored.add(hits.toDouble() / qTokens.size to o)
        }
        scored.sortByDescending { it.first }
        return scored.take(limit).map { (s, o) ->
            o.toMutableMap().also { it["match"] = Math.round(s * 100) / 100.0 }
        }
    }

    fun recentPayments(limit: Int = 20): List<Map<String, Any?>> =
        db.query(
            """SELECT p.*, c.name AS client_name FROM payments p
               JOIN clients c ON c.id = p.client_id
               ORDER BY p.created_at DESC LIMIT ?""",
            listOf(limit),
        )

    fun ordersDue(dateFrom: String, dateTo: String): List<Map<String, Any?>> =
        db.query(
            """$orderSelect WHERE o.status = 'open' AND o.due_date IS NOT NULL
               AND date(o.due_date) >= date(?) AND date(o.due_date) <= date(?)
               ORDER BY o.due_date""",
            listOf(dateFrom, dateTo),
        )

    fun overdueOrders(today: String? = null): List<Map<String, Any?>> =
        db.query(
            """$orderSelect WHERE o.status = 'open' AND o.due_date IS NOT NULL AND date(o.due_date) < date(?)
               ORDER BY o.due_date""",
            listOf(today ?: today()),
        )

    // ---------- money ----------

    fun markPaid(
        clientId: Long,
        amount: Double,
        orderId: Long? = null,
        note: String? = null,
    ): Map<String, Any?> {
        getClient(clientId)
        if (orderId != null) {
            db.insert(
                "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, ?)",
                listOf(clientId, orderId, amount, note),
            )
        } else {
            var remaining = amount
            for (o in ordersForClient(clientId)) {
                if (remaining <= 0) break
                val balance = (o["balance"] as Number).toDouble()
                if (balance <= 0) continue
                val take = minOf(balance, remaining)
                db.insert(
                    "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, ?, ?, ?)",
                    listOf(clientId, o["id"], take, note),
                )
                remaining -= take
            }
            if (remaining > 0) {
                db.insert(
                    "INSERT INTO payments (client_id, order_id, amount, note) VALUES (?, NULL, ?, ?)",
                    listOf(clientId, remaining, note),
                )
            }
        }
        return mapOf("client" to getClient(clientId), "paid" to amount, "due_left" to clientDue(clientId))
    }

    fun clientDue(clientId: Long): Double {
        val row = db.query(
            """SELECT COALESCE((SELECT SUM(price) FROM orders WHERE client_id=? AND status != 'cancelled'), 0)
                    - COALESCE((SELECT SUM(amount) FROM payments WHERE client_id=?), 0) AS due""",
            listOf(clientId, clientId),
        ).first()
        return Math.round((row["due"] as Number).toDouble() * 100) / 100.0
    }

    fun allDues(): List<Map<String, Any?>> =
        listClients().mapNotNull { client ->
            val id = (client["id"] as Number).toLong()
            val due = clientDue(id)
            if (due > 0) mapOf("client_id" to id, "name" to client["name"], "due" to due) else null
        }.sortedByDescending { it["due"] as Double }

    // ---------- memory notes (Suhani's यादें) ----------

    fun addNote(content: String): Map<String, Any?> {
        val c = content.trim()
        require(c.isNotEmpty()) { "empty note" }
        val id = db.insert("INSERT INTO notes (content) VALUES (?)", listOf(c))
        val pruned = pruneNotes()
        return mapOf("id" to id, "content" to c, "pruned_old_notes" to pruned)
    }

    fun listNotes(): List<Map<String, Any?>> = db.query("SELECT * FROM notes ORDER BY id")

    fun forgetNote(noteId: Long): Map<String, Any?> {
        val deleted = db.exec("DELETE FROM notes WHERE id=?", listOf(noteId))
        return mapOf("deleted" to (deleted == 1), "note_id" to noteId)
    }

    private fun pruneNotes(): Int {
        var pruned = 0
        while (true) {
            val rows = db.query("SELECT id, content FROM notes ORDER BY id")
            val totalChars = rows.sumOf { (it["content"] as String).length }
            if (rows.size <= MAX_NOTES && totalChars <= MAX_NOTES_CHARS) break
            db.exec("DELETE FROM notes WHERE id=?", listOf(rows.first()["id"]))
            pruned++
        }
        return pruned
    }

    // ---------- briefing ----------

    fun dailyBriefing(today: String? = null): Map<String, Any?> {
        val t = today ?: today()
        val tomorrow = LocalDate.parse(t).plusDays(1).toString()
        return mapOf(
            "date" to t,
            "due_today" to ordersDue(t, t),
            "due_tomorrow" to ordersDue(tomorrow, tomorrow),
            "overdue" to overdueOrders(t),
            "money_pending" to allDues(),
        )
    }
}
