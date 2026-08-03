package app.suhani.spikes

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spike S3: phone -> Saaras v4 WS -> Sarvam-105B -> Bulbul v3 -> speaker,
 * no LiveKit in between. Tap, speak one sentence, tap stop; per-stage
 * latencies accumulate on screen. Pass bar: first-audio p50 <= 2.0s over
 * several runs on WiFi.
 */
class S3LatencyActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var table: TextView
    private lateinit var button: Button

    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mic: MicStreamer? = null
    private var stream: SaarasStream? = null
    private var running = false
    private var tStop = 0L
    private val finals = StringBuilder()
    private val runsMs = mutableListOf<LongArray>() // [stt, llm, tts, total]

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status.text = "Mic permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "S3 - direct-path latency"; textSize = 22f })
        col.addView(TextView(this).apply {
            text = "Tap start, say one Hindi sentence (e.g. 'सुनीता का ब्लाउज़ कब तक तैयार होगा?'), tap stop. Repeat 5x."
            setPadding(0, pad / 2, 0, pad)
        })
        button = Button(this).apply {
            text = "Start listening"
            setOnClickListener {
                if (!running) permission.launch(Manifest.permission.RECORD_AUDIO) else stopAndMeasure()
            }
        }
        status = TextView(this).apply { text = "Ready" }
        table = TextView(this).apply { setPadding(0, pad, 0, 0); typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(button)
        col.addView(status)
        col.addView(table)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun startListening() {
        finals.clear()
        val s = SaarasStream(
            apiKey = app.suhani.Keys.sarvam(this),
            onTranscript = { t -> synchronized(finals) { finals.append(t).append(' ') } },
            onError = { e -> runOnUiThread { status.text = "Error: $e" } },
        )
        s.connect()
        stream = s
        mic = MicStreamer(s).also { it.start(lifecycleScope) }
        running = true
        button.text = "Stop - measure"
        status.text = "Listening... speak now"
    }

    private fun stopAndMeasure() {
        running = false
        button.isEnabled = false
        tStop = SystemClock.elapsedRealtime()
        status.text = "Waiting for transcript..."
        lifecycleScope.launch(Dispatchers.IO) {
            stream?.endStream()
            // Wait for the final transcript to settle (max 5s).
            var transcript = ""
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                val now = synchronized(finals) { finals.toString().trim() }
                if (now.isNotEmpty() && now == transcript) break
                transcript = now
            }
            mic?.stop(); stream?.close()
            val tStt = SystemClock.elapsedRealtime()

            if (transcript.isBlank()) {
                withContext(Dispatchers.Main) {
                    status.text = "No transcript heard - try again"
                    button.isEnabled = true; button.text = "Start listening"
                }
                return@launch
            }

            withContext(Dispatchers.Main) { status.text = "Brain: \"$transcript\"" }
            val reply = chatOnce(transcript)
            val tLlm = SystemClock.elapsedRealtime()

            withContext(Dispatchers.Main) { status.text = "Voice: \"$reply\"" }
            val pcm = ttsOnce(reply)
            val tTts = SystemClock.elapsedRealtime()

            playPcm24k(pcm)
            val tPlay = SystemClock.elapsedRealtime()

            val row = longArrayOf(tStt - tStop, tLlm - tStt, tTts - tLlm, tPlay - tStop)
            runsMs.add(row)
            withContext(Dispatchers.Main) {
                status.text = "Done - reply played"
                button.isEnabled = true; button.text = "Start listening"
                table.text = renderTable()
            }
        }
    }

    private fun chatOnce(userText: String): String {
        val body = JSONObject()
            .put("model", "sarvam-105b")
            .put("reasoning_effort", "low")
            .put("max_tokens", 256)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "तुम सुहानी हो। एक ही छोटे हिंदी वाक्य में जवाब दो।"))
                    .put(JSONObject().put("role", "user").put("content", userText)),
            )
        val req = Request.Builder()
            .url("https://api.sarvam.ai/v1/chat/completions")
            .addHeader("api-subscription-key", app.suhani.Keys.sarvam(this))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val json = JSONObject(res.body!!.string())
            return json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
                ?.ifBlank { null } ?: "जी, बोलिए।"
        }
    }

    private fun ttsOnce(text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model", "bulbul:v3")
            .put("speaker", "suhani")
            .put("target_language_code", "hi-IN")
            .put("speech_sample_rate", 24000)
            .put("pace", 1.05)
            .put("temperature", 0.75)
        val req = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .addHeader("api-subscription-key", app.suhani.Keys.sarvam(this))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val json = JSONObject(res.body!!.string())
            val b64 = json.getJSONArray("audios").getString(0)
            val wav = Base64.decode(b64, Base64.DEFAULT)
            return wav.copyOfRange(44, wav.size) // strip wav header
        }
    }

    private fun playPcm24k(pcm: ByteArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size.coerceAtMost(24000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        var off = 0
        while (off < pcm.size) {
            val n = track.write(pcm, off, minOf(9600, pcm.size - off))
            if (n <= 0) break
            off += n
        }
        track.stop(); track.release()
    }

    private fun renderTable(): String = buildString {
        append("run   stt   llm   tts   total\n")
        runsMs.forEachIndexed { i, r ->
            append("%-4d %5d %5d %5d %6d\n".format(i + 1, r[0], r[1], r[2], r[3]))
        }
        if (runsMs.isNotEmpty()) {
            val totals = runsMs.map { it[3] }.sorted()
            val p50 = totals[totals.size / 2]
            append("\np50 total: ${p50}ms  ->  ${if (p50 <= 2000) "PASS" else "FAIL"} (bar 2000ms)")
        }
    }
}
