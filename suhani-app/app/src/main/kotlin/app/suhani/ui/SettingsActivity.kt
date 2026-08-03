package app.suhani.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.suhani.Keys
import app.suhani.voice.Warmup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** API key rotation: paste, test against the live API, save. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + Ui.dp(this@SettingsActivity, 16), pad, bars.bottom + pad)
                insets
            }
        }
        col.addView(Ui.text(this, "API key", 26f, bold = true))
        status = Ui.text(this, sourceLine(), 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 14))
        }
        col.addView(status)

        input = EditText(this).apply {
            hint = "Paste new Sarvam API key (sk_...)"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.FG)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = Ui.card(this@SettingsActivity, 12)
            val p = Ui.dp(this@SettingsActivity, 12)
            setPadding(p, p, p, p)
        }
        col.addView(input)

        col.addView(Ui.pillButton(this, "Test & save", accent = true) { testAndSave() }.withMargin())
        col.addView(Ui.pillButton(this, "Use built-in key again") {
            Keys.setSarvam(this, null)
            status.text = sourceLine()
        }.withMargin())
        col.addView(
            Ui.text(
                this,
                "The saved key lives only on this phone and applies from the next conversation. " +
                    "Rotate at dashboard.sarvam.ai, paste here, test, and revoke the old key.",
                13f, Ui.MUTED,
            ).apply { setPadding(0, Ui.dp(this@SettingsActivity, 14), 0, 0) },
        )
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun TextView.withMargin(): TextView = apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(this@SettingsActivity, 10) }
    }

    private fun sourceLine(): String {
        val key = Keys.sarvam(this)
        val masked = if (key.length > 12) key.take(11) + "..." + key.takeLast(4) else "(none)"
        val source = if (Keys.overrideActive(this)) "custom (saved on phone)" else "built-in (from build)"
        return "Active: $masked - $source"
    }

    private fun testAndSave() {
        val candidate = input.text.toString().trim()
        if (!candidate.startsWith("sk_")) {
            status.text = "That doesn't look like a Sarvam key (sk_...)"
            return
        }
        status.text = "Testing key..."
        lifecycleScope.launch(Dispatchers.IO) {
            val code = runCatching {
                val body = JSONObject()
                    .put("model", "sarvam-105b")
                    .put("max_tokens", 1)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    )
                val req = Request.Builder()
                    .url("https://api.sarvam.ai/v1/chat/completions")
                    .addHeader("api-subscription-key", candidate)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                Warmup.http.newCall(req).execute().use { it.code }
            }.getOrDefault(-1)
            withContext(Dispatchers.Main) {
                when {
                    code in listOf(401, 403) ->
                        status.text = "Key REJECTED by the API - not saved."
                    code == 402 -> {
                        // Auth passed but the wallet is empty - save it anyway
                        // and say EXACTLY that, or rotation debugging gets ugly.
                        Keys.setSarvam(this@SettingsActivity, candidate)
                        input.setText("")
                        status.text = "Key valid but NO CREDITS (402) - saved. " +
                            "Add credits at dashboard.sarvam.ai, then Suhani will speak."
                    }
                    code > 0 -> {
                        Keys.setSarvam(this@SettingsActivity, candidate)
                        input.setText("")
                        status.text = "Key valid ✓ saved. ${sourceLine()}"
                    }
                    else -> status.text = "No network - couldn't test, not saved."
                }
            }
        }
    }
}
