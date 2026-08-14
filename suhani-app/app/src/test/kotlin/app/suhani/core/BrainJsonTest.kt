package app.suhani.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for the "{}{}"-poisons-history bug: strict validation must
 * reject what org.json's lenient parser happily accepts. */
class BrainJsonTest {

    private val brain = Brain("test-key", Ledger(JdbcSqlDriver()))

    @Test fun concatenatedObjectsRejected() {
        assertNull(brain.strictJsonObject("""{"name":"मीना"}{"name":"सुनीता"}"""))
        assertNull(brain.strictJsonObject("{}{}"))
    }

    @Test fun trailingGarbageRejected() {
        assertNull(brain.strictJsonObject("""{"a":1} xyz"""))
    }

    @Test fun nonObjectRejected() {
        assertNull(brain.strictJsonObject(""""just a string""""))
        assertNull(brain.strictJsonObject("[1,2]"))
        assertNull(brain.strictJsonObject(""))
    }

    @Test fun validObjectAccepted() {
        assertNotNull(brain.strictJsonObject("""{"name":"मीना","price":400}"""))
    }

    @Test fun lenientInputCanonicalized() {
        // org.json accepts unquoted keys/single quotes; canonical output must
        // be strict JSON the server will take.
        val v = brain.strictJsonObject("{name:'मीना'}")
        assertNotNull(v)
        assertEquals("मीना", v!!.getString("name"))
        assertEquals("""{"name":"मीना"}""", v.toString())
    }
}
