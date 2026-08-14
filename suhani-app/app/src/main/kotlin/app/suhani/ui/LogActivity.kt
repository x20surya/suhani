package app.suhani.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.suhani.AppLog

/** The error log, in-app: read, copy for Surya, clear. */
class LogActivity : AppCompatActivity() {

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val pad = Ui.dp(this, 16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + Ui.dp(this@LogActivity, 12), pad, bars.bottom + pad)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(this, "Error log", 24f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Ui.pillButton(this, "Copy") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("suhani-log", AppLog.read()))
        })
        header.addView(Ui.pillButton(this, "Clear") {
            AppLog.clear()
            body.text = "(cleared)"
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = Ui.dp(this@LogActivity, 8) })
        col.addView(header)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        col.addView(scroll)
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        body.text = AppLog.read()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
