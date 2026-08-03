package app.suhani.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Mirror of tests/test_ledger.py - all 44 checks, case for case. */

private fun freshLedger() = Ledger(JdbcSqlDriver())

private fun num(m: Map<String, Any?>, key: String): Double = (m[key] as Number).toDouble()
private fun id(m: Map<String, Any?>): Long = (m["id"] as Number).toLong()

class TestFuzzyClients {
    private lateinit var ledger: Ledger
    private lateinit var sunita: Map<String, Any?>
    private lateinit var meena: Map<String, Any?>

    @Before fun setUp() {
        ledger = freshLedger()
        sunita = ledger.addClient("सुनीता", phone = "9999900001")
        meena = ledger.addClient("मीना")
        ledger.addClient("राधा वर्मा")
    }

    @Test fun exactDevanagari() {
        assertEquals(id(sunita), id(ledger.findClient("सुनीता")[0]))
    }

    @Test fun duplicateAddClientReturnsExisting() {
        val again = ledger.addClient("सुनीता")
        assertEquals(id(sunita), id(again))
        assertTrue(again["duplicate_ignored"] == true)
        assertEquals(1, ledger.listClients().count { it["name"] == "सुनीता" })
    }

    @Test fun distinctFullerNameStillCreates() {
        val jain = ledger.addClient("सुनीता जैन")
        assertNotEquals(id(sunita), id(jain))
        assertFalse(jain.containsKey("duplicate_ignored"))
    }

    @Test fun romanSpellingMatchesDevanagari() {
        for (spelling in listOf("Sunita", "sunita", "Suneeta", "sunitha")) {
            val matches = ledger.findClient(spelling)
            assertTrue("no match for $spelling", matches.isNotEmpty())
            assertEquals(spelling, id(sunita), id(matches[0]))
        }
    }

    @Test fun misheardDevanagariVariant() {
        val matches = ledger.findClient("सुनिता")
        assertTrue(matches.isNotEmpty())
        assertEquals(id(sunita), id(matches[0]))
    }

    @Test fun honorificStripped() {
        val matches = ledger.findClient("मीना आंटी")
        assertTrue(matches.isNotEmpty())
        assertEquals(id(meena), id(matches[0]))
    }

    @Test fun unknownNameReturnsEmpty() {
        assertEquals(emptyList<Map<String, Any?>>(), ledger.findClient("कविता"))
    }

    @Test fun aliasLearning() {
        ledger.addAlias(id(sunita), "Sunita didi")
        assertEquals(id(sunita), id(ledger.findClient("Sunita didi")[0]))
    }
}

class TestOrdersAndDues {
    private lateinit var ledger: Ledger
    private lateinit var c: Map<String, Any?>

    @Before fun setUp() {
        ledger = freshLedger()
        c = ledger.addClient("सुनीता")
    }

    @Test fun orderWithAdvanceBalance() {
        val order = ledger.addOrder(id(c), "ब्लाउज़", price = 500.0, dueDate = "2026-08-01", advance = 200.0)
        assertEquals(300.0, num(order, "balance"), 0.001)
        assertEquals(300.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun markPaidClearsDue() {
        ledger.addOrder(id(c), "कुर्ती", price = 400.0)
        ledger.markPaid(id(c), 400.0)
        assertEquals(0.0, ledger.clientDue(id(c)), 0.001)
        assertTrue(ledger.allDues().isEmpty())
    }

    @Test fun partialPayment() {
        ledger.addOrder(id(c), "साड़ी फॉल-पीको", price = 150.0)
        ledger.markPaid(id(c), 100.0)
        assertEquals(50.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun ordersDueWindowAndOverdue() {
        ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-08-01")
        ledger.addOrder(id(c), "सूट", 900.0, dueDate = "2026-08-05")
        ledger.addOrder(id(c), "लहंगा", 2000.0, dueDate = "2026-07-20")
        val due = ledger.ordersDue("2026-08-01", "2026-08-01")
        assertEquals(listOf("ब्लाउज़"), due.map { it["garment"] })
        val overdue = ledger.overdueOrders(today = "2026-07-28")
        assertEquals(listOf("लहंगा"), overdue.map { it["garment"] })
    }

    @Test fun deliveredOrderLeavesDueWindow() {
        val o = ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-08-01")
        ledger.updateOrder(id(o), status = "delivered")
        assertTrue(ledger.ordersDue("2026-08-01", "2026-08-01").isEmpty())
        assertEquals(500.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun unallocatedPaymentFlowsToOrdersFifo() {
        val o1 = ledger.addOrder(id(c), "ब्लाउज़", 300.0, dueDate = "2026-08-01")
        val o2 = ledger.addOrder(id(c), "सूट", 200.0, dueDate = "2026-08-05")
        ledger.markPaid(id(c), 400.0)
        assertEquals(0.0, num(ledger.getOrder(id(o1)), "balance"), 0.001)
        assertEquals(100.0, num(ledger.getOrder(id(o2)), "balance"), 0.001)
        assertEquals(100.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun overpaymentBecomesCredit() {
        ledger.addOrder(id(c), "ब्लाउज़", 300.0)
        ledger.markPaid(id(c), 500.0)
        assertEquals(-200.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun cancelledOrderLeavesEverything() {
        val o = ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-08-01")
        ledger.updateOrder(id(o), status = "cancelled")
        assertTrue(ledger.allOpenOrders().isEmpty())
        assertTrue(ledger.ordersDue("2026-08-01", "2026-08-01").isEmpty())
        assertEquals(0.0, ledger.clientDue(id(c)), 0.001)
    }

    @Test fun duplicateOrderIgnored() {
        val o1 = ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-08-01")
        val o2 = ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-08-01")
        assertTrue(o2["duplicate_ignored"] == true)
        assertEquals(id(o1), id(o2))
        assertEquals(1, ledger.allOpenOrders().size)
        val o3 = ledger.addOrder(id(c), "ब्लाउज़", 600.0, dueDate = "2026-08-01")
        assertFalse(o3.containsKey("duplicate_ignored"))
        assertEquals(2, ledger.allOpenOrders().size)
    }

    @Test fun briefingShape() {
        ledger.addOrder(id(c), "ब्लाउज़", 500.0, dueDate = "2026-07-28")
        val b = ledger.dailyBriefing(today = "2026-07-28")
        @Suppress("UNCHECKED_CAST")
        val dueToday = b["due_today"] as List<Map<String, Any?>>
        assertEquals(1, dueToday.size)
        assertEquals("सुनीता", dueToday[0]["client_name"])
        @Suppress("UNCHECKED_CAST")
        val pending = b["money_pending"] as List<Map<String, Any?>>
        assertEquals(500.0, num(pending[0], "due"), 0.001)
    }
}

class TestRealisticNamePatterns {
    private lateinit var ledger: Ledger
    private lateinit var vasvani: Map<String, Any?>
    private lateinit var jain: Map<String, Any?>

    @Before fun setUp() {
        ledger = freshLedger()
        vasvani = ledger.addClient("सुनीता वासवानी")
        jain = ledger.addClient("सुनीता जैन")
    }

    @Test fun fullNameMatchesExactlyOne() {
        for ((q, expect) in listOf(
            "सुनीता जैन" to jain,
            "सुनीता वासवानी" to vasvani,
            "Sunita Jain" to jain,
        )) {
            val m = ledger.findClient(q)
            assertEquals(q, id(expect), id(m[0]))
            val top = m.filter { num(it, "match_score") >= 0.9 }
            assertEquals("$q: ${m.map { it["name"] to it["match_score"] }}", 1, top.size)
        }
    }

    @Test fun bareFirstNameReturnsBoth() {
        val m = ledger.findClient("सुनीता")
        assertEquals(m.map { it["name"] to it["match_score"] }.toString(), 2, m.size)
    }

    @Test fun localityTagStillMatches() {
        val m = ledger.findClient("सुनीता वासवानी ब्रेंटवुड वाली")
        assertTrue("locality-tagged full name found nobody", m.isNotEmpty())
        assertEquals(id(vasvani), id(m[0]))
    }

    @Test fun surnameWithFiller() {
        val m = ledger.findClient("जैन वाली सुनीता")
        assertTrue(m.isNotEmpty())
        assertEquals(id(jain), id(m[0]))
    }

    @Test fun unrelatedNameNoMatch() {
        assertTrue(ledger.findClient("मीना").isEmpty())
    }

    @Test fun learnedLocalityAlias() {
        ledger.addAlias(id(vasvani), "ब्रेंटवुड वाली सुनीता")
        val m = ledger.findClient("ब्रेंटवुड वाली")
        assertTrue(m.isNotEmpty())
        assertEquals(id(vasvani), id(m[0]))
    }
}

class TestSearchOrders {
    private lateinit var ledger: Ledger
    private lateinit var maroon: Map<String, Any?>
    private lateinit var lehenga: Map<String, Any?>

    @Before fun setUp() {
        ledger = freshLedger()
        val sunita = ledger.addClient("सुनीता")
        val shruti = ledger.addClient("श्रुति")
        val geeta = ledger.addClient("गीता")
        ledger.addOrder(id(sunita), "ब्लाउज़", 400.0, notes = "red colour")
        maroon = ledger.addOrder(id(shruti), "ब्लाउज़", 400.0, notes = "maroon colour, पफ स्लीव")
        lehenga = ledger.addOrder(id(geeta), "लहंगा", 2000.0, notes = "gold गोटा पत्ती")
    }

    @Test fun colorFindsOpenOrder() {
        assertEquals("श्रुति", ledger.searchOrders("maroon ब्लाउज़")[0]["client_name"])
    }

    @Test fun findsDeliveredOrder() {
        ledger.updateOrder(id(maroon), status = "delivered")
        val hits = ledger.searchOrders("maroon")
        assertEquals("श्रुति", hits[0]["client_name"])
        assertEquals("delivered", hits[0]["status"])
    }

    @Test fun findsCancelledOrderWithStatus() {
        ledger.updateOrder(id(lehenga), status = "cancelled")
        val hits = ledger.searchOrders("गोटा पत्ती")
        assertEquals("गीता", hits[0]["client_name"])
        assertEquals("cancelled", hits[0]["status"])
    }

    @Test fun patternQuery() {
        assertEquals("श्रुति", ledger.searchOrders("पफ स्लीव वाला")[0]["client_name"])
    }

    @Test fun clientNameQuery() {
        assertEquals("गीता", ledger.searchOrders("गीता लहंगा")[0]["client_name"])
    }

    @Test fun noMatchReturnsEmpty() {
        assertTrue(ledger.searchOrders("purple शेरवानी").isEmpty())
        assertTrue(ledger.searchOrders("").isEmpty())
    }

    @Test fun bestMatchRankedFirst() {
        assertEquals("सुनीता", ledger.searchOrders("red ब्लाउज़")[0]["client_name"])
    }
}

class TestResolveDueDate {
    private val today: LocalDate = LocalDate.of(2026, 7, 29) // a Wednesday

    private fun resolve(vararg args: Pair<String, Any>): JSONObject =
        JSONObject(Tools.resolveDueDate(JSONObject(args.toMap()), today = today))

    @Test fun nextFriday() {
        assertEquals("2026-07-31", resolve("weekday" to "शुक्रवार").getString("date"))
    }

    @Test fun sameWeekdayMeansNextWeek() {
        assertEquals("2026-08-05", resolve("weekday" to "बुधवार").getString("date"))
    }

    @Test fun daysFromToday() {
        assertEquals("2026-07-31", resolve("days_from_today" to 2).getString("date"))
        assertEquals("2026-08-12", resolve("days_from_today" to 14).getString("date"))
    }

    @Test fun dayOfMonthFuture() {
        assertEquals("2026-08-15", resolve("day_of_month" to 15, "month" to 8).getString("date"))
    }

    @Test fun dayOfMonthPastRollsToNextYear() {
        assertEquals("2027-03-15", resolve("day_of_month" to 15, "month" to 3).getString("date"))
    }

    @Test fun noInputIsError() {
        assertTrue(resolve().has("error"))
    }
}

class TestMemoryNotes {
    private lateinit var ledger: Ledger

    @Before fun setUp() {
        ledger = freshLedger()
    }

    @Test fun addAndList() {
        val n = ledger.addNote("बेटी की शादी अगले महीने है")
        val notes = ledger.listNotes()
        assertEquals(1, notes.size)
        assertEquals((n["id"] as Number).toLong(), (notes[0]["id"] as Number).toLong())
        assertTrue((notes[0]["content"] as String).contains("शादी"))
    }

    @Test fun forget() {
        val n = ledger.addNote("कुछ")
        assertTrue(ledger.forgetNote((n["id"] as Number).toLong())["deleted"] == true)
        assertTrue(ledger.listNotes().isEmpty())
        assertFalse(ledger.forgetNote(999L)["deleted"] == true)
    }

    @Test fun countCapPrunesOldest() {
        repeat(Ledger.MAX_NOTES + 5) { i -> ledger.addNote("बात $i") }
        val notes = ledger.listNotes()
        assertEquals(Ledger.MAX_NOTES, notes.size)
        assertEquals("बात 5", notes[0]["content"])
    }

    @Test fun charCapPrunes() {
        val big = "क".repeat(1000)
        repeat(5) { ledger.addNote(big) }
        val total = ledger.listNotes().sumOf { (it["content"] as String).length }
        assertTrue(total <= Ledger.MAX_NOTES_CHARS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyNoteRejected() {
        ledger.addNote("   ")
    }

    @Test fun dispatchRememberRoundtrip() {
        val out = JSONObject(Tools.dispatch(ledger, "remember", JSONObject().put("content", "मालकिन को चाय पसंद है")))
        assertTrue(out.has("id"))
        val gone = JSONObject(Tools.dispatch(ledger, "forget_note", JSONObject().put("note_id", out.getLong("id"))))
        assertTrue(gone.getBoolean("deleted"))
    }
}

class TestDispatchJSON {
    @Test fun dispatchRoundtrip() {
        val ledger = freshLedger()
        val out = JSONObject(Tools.dispatch(ledger, "add_client", JSONObject().put("name", "मीना")))
        assertEquals("मीना", out.getString("name"))
        val found = JSONObject(Tools.dispatch(ledger, "find_client", JSONObject().put("name", "Meena")))
        assertEquals(out.getLong("id"), found.getJSONArray("matches").getJSONObject(0).getLong("id"))
        val err = JSONObject(
            Tools.dispatch(
                ledger,
                "add_order",
                JSONObject().put("client_id", 999).put("garment", "x").put("price", 1),
            ),
        )
        assertTrue(err.has("error"))
    }
}
