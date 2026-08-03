package app.suhani.spikes

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.suhani.core.Brain
import app.suhani.core.Ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P1 exit demo: the full brain + tools + ledger loop on-device, no audio.
 * The smoke_chat equivalent - type Hindi, watch the बही change for real.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var brain: Brain? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "सुहानी - text debug (on-device brain)"; textSize = 18f })
        log = TextView(this).apply { setPadding(0, pad, 0, pad); textSize = 15f }
        scroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        col.addView(scroll)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "यहाँ लिखिए..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        row.addView(input)
        row.addView(Button(this).apply { text = "भेजो"; setOnClickListener { send() } })
        col.addView(row)
        setContentView(col)

        // Same DB file the voice loop will use - this IS the real बही.
        val dbFile = File(filesDir, "silai.db")
        val ledger = Ledger(AndroidSqlDriver(dbFile))
        brain = Brain(app.suhani.Keys.sarvam(this), ledger)
        append("(brain: sarvam-105b fixed/low, DB: ${dbFile.name})\n")
    }

    private fun append(text: String) {
        log.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        append("\nआप: $text\n")
        lifecycleScope.launch(Dispatchers.IO) {
            val reply = try {
                brain?.send(text) ?: "(no brain)"
            } catch (e: Exception) {
                "(error: ${e.message})"
            }
            withContext(Dispatchers.Main) { append("सुहानी: $reply\n") }
        }
    }
}
