package app.suhani.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.suhani.voice.ReminderScheduler

/** The speaking alarm's face: lights up over the lock screen with the aura
 * and the reminder text; dismiss or snooze 30 minutes. */
class ReminderAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val id = intent.getLongExtra("reminder_id", -1)
        val reminder = if (id >= 0) ReminderScheduler.ledger(this).getReminder(id) else null
        val content = reminder?.get("content") as? String ?: "याद दिलाना था"

        val pad = Ui.dp(this, 24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 3, pad, pad * 2)
        }
        val aura = AuraView(this).apply {
            val size = Ui.dp(this@ReminderAlarmActivity, 220)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setAgentState("speaking")
        }
        col.addView(FrameLayout(this).apply {
            addView(aura)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        col.addView(Ui.text(this, "याद दिलाना था", 18f, Ui.MUTED, center = true).apply {
            setPadding(0, pad, 0, Ui.dp(this@ReminderAlarmActivity, 8))
        })
        col.addView(Ui.text(this, content, 26f, bold = true, center = true))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad * 2, 0, 0)
        }
        buttons.addView(Ui.pillButton(this, "ठीक है", accent = true) { finish() })
        buttons.addView(Ui.pillButton(this, "आधे घंटे बाद फिर") {
            if (id >= 0) {
                val ledger = ReminderScheduler.ledger(this)
                ledger.snoozeReminder(id, 30)?.let { r ->
                    ReminderScheduler.schedule(this, id, r["due_at"] as String)
                }
            }
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@ReminderAlarmActivity, 12); gravity = Gravity.CENTER_HORIZONTAL }
        })
        col.addView(buttons)
        setContentView(col)
    }
}
