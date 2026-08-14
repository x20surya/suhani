package app.suhani.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Data-integrity invariants: a flaked tool call must ERROR, never write
 * silently-wrong records into the बही. */
class LedgerGuardrailTest {

    private lateinit var ledger: Ledger
    private var clientId: Long = 0

    @Before fun setUp() {
        ledger = Ledger(JdbcSqlDriver())
        clientId = (ledger.addClient("सुनीता")["id"] as Number).toLong()
    }

    private inline fun expectReject(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun newOrderAlwaysOpen() {
        val o = ledger.addOrder(clientId, "ब्लाउज़", 500.0)
        assertEquals("open", o["status"])
    }

    @Test fun invalidStatusRejected() {
        val o = ledger.addOrder(clientId, "ब्लाउज़", 500.0)
        expectReject { ledger.updateOrder((o["id"] as Number).toLong(), status = "finished") }
        expectReject { ledger.updateOrder((o["id"] as Number).toLong(), status = "cancelld") }
    }

    @Test fun garbledDueDateRejected() {
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", 500.0, dueDate = "शुक्रवार") }
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", 500.0, dueDate = "2026-13-45") }
        val o = ledger.addOrder(clientId, "ब्लाउज़", 500.0, dueDate = "2026-08-10")
        expectReject { ledger.updateOrder((o["id"] as Number).toLong(), dueDate = "next monday") }
    }

    @Test fun moneyInvariants() {
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", 0.0) }
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", -100.0) }
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", 500.0, advance = -50.0) }
        expectReject { ledger.addOrder(clientId, "ब्लाउज़", 500.0, advance = 600.0) }
        expectReject { ledger.markPaid(clientId, 0.0) }
        expectReject { ledger.markPaid(clientId, -200.0) }
    }

    @Test fun blankNamesRejected() {
        expectReject { ledger.addClient("   ") }
        expectReject { ledger.addOrder(clientId, "  ", 500.0) }
    }

    @Test fun duplicateReminderIgnored() {
        val due = java.time.LocalDateTime.now(Ledger.IST).plusHours(2)
            .withSecond(0).withNano(0).toString()
        val first = ledger.addReminder("चाय पीना है", due)
        assertTrue(first["duplicate_ignored"] != true)
        val second = ledger.addReminder("चाय पीना है", due)
        assertEquals(true, second["duplicate_ignored"])
        assertEquals(1, ledger.listReminders().size)
    }

    @Test fun duplicatePaymentWithinWindowIgnored() {
        ledger.addOrder(clientId, "ब्लाउज़", 500.0)
        val first = ledger.markPaid(clientId, 200.0)
        assertTrue(first["duplicate_ignored"] != true)
        val second = ledger.markPaid(clientId, 200.0) // double-fired tool call
        assertEquals(true, second["duplicate_ignored"])
        assertEquals(300.0, ledger.clientDue(clientId), 0.001)
        // A DIFFERENT amount is a real second payment.
        val third = ledger.markPaid(clientId, 100.0)
        assertNotEquals(true, third["duplicate_ignored"])
        assertEquals(200.0, ledger.clientDue(clientId), 0.001)
    }
}
