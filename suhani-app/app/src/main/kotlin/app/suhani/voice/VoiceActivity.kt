package app.suhani.voice

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.suhani.core.Ledger
import app.suhani.spikes.AndroidSqlDriver
import app.suhani.ui.AuraView
import app.suhani.ui.LedgerActivity
import app.suhani.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home screen, matching the webapp's design language: header with title and
 * date, the aura orb as the only control, honest state captions, today's work
 * as a quiet card at the bottom. UI in English; the conversation is Hindi.
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var aura: AuraView
    private lateinit var stateCaption: TextView
    private lateinit var transcript: TextView
    private lateinit var briefingBody: LinearLayout
    private lateinit var endButton: TextView
    private var live = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startConversation() else stateCaption.text = "Microphone permission needed"
    }

    // Android 13+ shows NO notifications (including the 8am briefing) unless
    // the user grants this at runtime - asked once, on first open.
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, Ui.dp(this@VoiceActivity, 28), pad, pad)
            // Respect the status/nav bars: the header must not crowd the clock,
            // the Today card must not sink under the gesture bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + Ui.dp(this@VoiceActivity, 16), pad, bars.bottom + Ui.dp(this@VoiceActivity, 12))
                insets
            }
        }

        // ---- header: Suhani + date | Ledger ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(Ui.text(this, "Suhani", 26f, bold = true))
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
        titleCol.addView(Ui.text(this, today, 14f, Ui.MUTED))
        header.addView(titleCol)
        // Quiet text links, not chunky pills - the orb is the only real control.
        header.addView(Ui.text(this, "Ledger", 16f, Ui.ACCENT, bold = true).apply {
            setPadding(Ui.dp(this@VoiceActivity, 12), Ui.dp(this@VoiceActivity, 8), Ui.dp(this@VoiceActivity, 12), Ui.dp(this@VoiceActivity, 8))
            setOnClickListener { startActivity(Intent(this@VoiceActivity, LedgerActivity::class.java)) }
        })
        header.addView(Ui.text(this, "···", 18f, Ui.MUTED).apply {
            setPadding(Ui.dp(this@VoiceActivity, 10), Ui.dp(this@VoiceActivity, 8), 0, Ui.dp(this@VoiceActivity, 8))
            setOnClickListener {
                startActivity(Intent(this@VoiceActivity, app.suhani.spikes.MainActivity::class.java))
            }
        })
        root.addView(header)

        // ---- center stage: glow + aura orb ----
        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val glowSize = Ui.dp(this, 360)
        stage.addView(
            android.view.View(this).apply {
                background = Ui.glow()
                layoutParams = FrameLayout.LayoutParams(glowSize, glowSize, Gravity.CENTER)
            },
        )
        aura = AuraView(this).apply {
            val size = Ui.dp(this@VoiceActivity, 270)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setAgentState("idle")
            setOnClickListener {
                if (!live) permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        stage.addView(aura)
        root.addView(stage)

        // ---- captions ----
        stateCaption = Ui.text(this, "Tap to talk", 24f, bold = true, center = true)
        transcript = Ui.text(this, "", 15f, Ui.MUTED, center = true).apply {
            setPadding(0, Ui.dp(this@VoiceActivity, 6), 0, Ui.dp(this@VoiceActivity, 14))
            minHeight = Ui.dp(this@VoiceActivity, 44)
            maxLines = 2
        }
        root.addView(stateCaption)
        root.addView(transcript)

        // ---- end-conversation pill (only while live) ----
        endButton = Ui.pillButton(this, "End conversation") { stopConversation() }.apply {
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(this@VoiceActivity, 14) }
        }
        root.addView(endButton)

        // ---- today card ----
        val (card, body) = Ui.sectionCard(this, "Today")
        briefingBody = body
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(this@VoiceActivity, 6) }
        root.addView(card)

        setContentView(root)
        loadBriefing()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ConversationService.listenerProxy = object : ConversationEngine.Listener {
            override fun onState(state: ConversationEngine.State) {
                runOnUiThread {
                    aura.setAgentState(
                        when (state) {
                            ConversationEngine.State.LISTENING -> "listening"
                            ConversationEngine.State.THINKING -> "thinking"
                            ConversationEngine.State.SPEAKING -> "speaking"
                            else -> "idle"
                        },
                    )
                    stateCaption.text = when (state) {
                        ConversationEngine.State.IDLE -> "Tap to talk"
                        ConversationEngine.State.LISTENING -> "Listening"
                        ConversationEngine.State.THINKING -> "Thinking..."
                        ConversationEngine.State.SPEAKING -> "Suhani is speaking"
                        ConversationEngine.State.ERROR -> "Something went wrong - tap again"
                    }
                }
            }

            override fun onCaption(text: String) {
                runOnUiThread { transcript.text = text }
            }

            override fun onLevel(level: Float) {
                runOnUiThread { aura.setLevel(level) }
            }

            override fun onSessionEnded() {
                runOnUiThread {
                    live = false
                    stateCaption.text = "Tap to talk"
                    transcript.text = ""
                    aura.setAgentState("idle")
                    endButton.visibility = android.view.View.GONE
                    loadBriefing()
                }
            }
        }
    }

    private fun row(label: String, value: String): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@VoiceActivity, 3), 0, Ui.dp(this@VoiceActivity, 3))
        }
        r.addView(Ui.text(this, label, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.addView(Ui.text(this, value, 15f, Ui.MUTED))
        return r
    }

    private fun loadBriefing() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rows: List<Pair<String, String>> = runCatching {
                val ledger = Ledger(AndroidSqlDriver(File(filesDir, "silai.db")))
                val b = ledger.dailyBriefing()
                @Suppress("UNCHECKED_CAST")
                val dueToday = b["due_today"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val overdue = b["overdue"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val pending = b["money_pending"] as List<Map<String, Any?>>
                buildList {
                    if (dueToday.isEmpty() && overdue.isEmpty()) add("Nothing due today" to "")
                    for (o in dueToday) add("${o["client_name"]} - ${o["garment"]}" to "today")
                    for (o in overdue) add("${o["client_name"]} - ${o["garment"]}" to "late")
                    val due = pending.sumOf { (it["due"] as Number).toDouble() }
                    if (due > 0) add("Money pending" to "₹${due.toInt()}")
                }
            }.getOrDefault(listOf("Ledger is empty - speak your first order" to ""))
            withContext(Dispatchers.Main) {
                briefingBody.removeAllViews()
                for ((label, value) in rows) briefingBody.addView(row(label, value))
            }
        }
    }

    private fun startConversation() {
        startForegroundService(Intent(this, ConversationService::class.java))
        live = true
        stateCaption.text = "Connecting..."
        aura.setAgentState("connecting")
        endButton.visibility = android.view.View.VISIBLE
    }

    private fun stopConversation() {
        stopService(Intent(this, ConversationService::class.java))
        live = false
        stateCaption.text = "Tap to talk"
        transcript.text = ""
        aura.setAgentState("idle")
        endButton.visibility = android.view.View.GONE
        loadBriefing()
    }

    override fun onDestroy() {
        ConversationService.listenerProxy = null
        super.onDestroy()
    }
}
