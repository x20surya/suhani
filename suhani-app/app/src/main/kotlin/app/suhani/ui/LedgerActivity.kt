package app.suhani.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.suhani.core.Ledger
import app.suhani.spikes.AndroidSqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The ledger, as sectioned cards: orders, dues, payments, memories. */
class LedgerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, Ui.dp(this@LedgerActivity, 28), pad, pad)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + Ui.dp(this@LedgerActivity, 16), pad, bars.bottom + Ui.dp(this@LedgerActivity, 12))
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(this, "Ledger", 26f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Ui.text(this, "Done", 16f, Ui.ACCENT, bold = true).apply {
            setPadding(Ui.dp(this@LedgerActivity, 12), Ui.dp(this@LedgerActivity, 8), 0, Ui.dp(this@LedgerActivity, 8))
            setOnClickListener { finish() }
        })
        container.addView(header)
        container.addView(Ui.text(this, "Loading...", 15f, Ui.MUTED).also { it.tag = "loading" })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(container)
        })

        lifecycleScope.launch(Dispatchers.IO) {
            val sections = runCatching { build() }.getOrElse {
                listOf("Error" to listOf("Couldn't open the ledger: ${it.message}" to ""))
            }
            withContext(Dispatchers.Main) { render(sections) }
        }
    }

    /** Each section: title -> list of (primary, secondary) rows. */
    private fun build(): List<Pair<String, List<Pair<String, String>>>> {
        val ledger = Ledger(AndroidSqlDriver(File(filesDir, "silai.db")))
        val open = ledger.allOpenOrders().map { o ->
            val details = buildList {
                (o["due_date"] as? String)?.let { add("due $it") }
                add("₹${(o["price"] as Number).toInt()}")
                val bal = (o["balance"] as Number).toDouble()
                if (bal > 0) add("₹${bal.toInt()} left")
                (o["notes"] as? String)?.let { add(it) }
            }.joinToString(" · ")
            "${o["client_name"]} - ${o["garment"]}" to details
        }
        val dues = ledger.allDues().map { d ->
            (d["name"] as String) to "₹${(d["due"] as Number).toInt()}"
        }
        val payments = ledger.recentPayments(10).map { p ->
            "${p["client_name"]}  +₹${(p["amount"] as Number).toInt()}" to (p["created_at"] as String).take(10)
        }
        val notes = ledger.listNotes().map { n -> (n["content"] as String) to "#${n["id"]}" }
        val reminders = ledger.listReminders().map { r ->
            (r["content"] as String) to (r["due_at"] as String).replace('T', ' ')
        }
        return listOf(
            "Open orders (${open.size})" to open.ifEmpty { listOf("None" to "") },
            "Reminders (${reminders.size})" to reminders.ifEmpty { listOf("None set" to "") },
            "Money pending" to dues.ifEmpty { listOf("All settled" to "") },
            "Recent payments" to payments.ifEmpty { listOf("None yet" to "") },
            "Suhani's memories (${notes.size})" to notes.ifEmpty { listOf("None yet" to "") },
        )
    }

    private fun render(sections: List<Pair<String, List<Pair<String, String>>>>) {
        container.findViewWithTag<TextView>("loading")?.let { container.removeView(it) }
        for ((title, rows) in sections) {
            val (card, body) = Ui.sectionCard(this, title)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@LedgerActivity, 14) }
            for ((primary, secondary) in rows) {
                // Stacked, never side-by-side: a long details line was squeezing
                // the client name into a one-character column.
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, Ui.dp(this@LedgerActivity, 5), 0, Ui.dp(this@LedgerActivity, 5))
                }
                row.addView(Ui.text(this, primary, 15f))
                if (secondary.isNotEmpty()) row.addView(Ui.text(this, secondary, 13f, Ui.MUTED))
                body.addView(row)
            }
            container.addView(card)
        }
    }
}
