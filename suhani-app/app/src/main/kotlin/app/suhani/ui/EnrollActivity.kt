package app.suhani.ui

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.suhani.voice.SpeakerGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice enrollment: मालकिन records three short takes; their averaged CAM++
 * embedding becomes the on-device voiceprint that gates the conversation.
 * The Verify button doubles as the threshold calibration tool - run it with
 * mom's voice (expect ~0.6+) and with anyone else's (expect ~0.3 or less).
 */
class EnrollActivity : AppCompatActivity() {

    companion object {
        private const val RATE = 16000
        private const val TAKE_SECONDS = 6
        private const val PREP_MS = 1600L
        private const val TAKES = 3
        private val PROMPTS = listOf(
            "नमस्ते सुहानी, मैं आपकी मालकिन बोल रही हूँ।",
            "सुनीता का ब्लाउज़ शुक्रवार तक लिखना, पाँच सौ का।",
            "आज क्या-क्या देना है, ज़रा बताना।",
        )
    }

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var gate: SpeakerGate
    private val takes = mutableListOf<ShortArray>()
    private var pendingAction: (() -> Unit)? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingAction?.invoke() else status.text = "Microphone permission needed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gate = SpeakerGate(this)
        val pad = Ui.dp(this, 20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + Ui.dp(this@EnrollActivity, 16), pad, bars.bottom + pad)
                insets
            }
        }
        col.addView(Ui.text(this, "Voice enrollment", 26f, bold = true))
        col.addView(Ui.text(
            this,
            "Suhani learns मालकिन's voice so nobody else in the room can write to the ledger. " +
                "Three short recordings, stored only on this phone.",
            14f, Ui.MUTED,
        ).apply { setPadding(0, Ui.dp(this@EnrollActivity, 8), 0, Ui.dp(this@EnrollActivity, 16)) })

        status = Ui.text(this, if (gate.enrolled) "Enrolled ✓ - re-record any time" else "Not enrolled yet", 17f)
        result = Ui.text(this, "", 15f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@EnrollActivity, 8), 0, Ui.dp(this@EnrollActivity, 16)) }
        col.addView(status)
        col.addView(result)

        col.addView(Ui.pillButton(this, "Record 3 takes (मालकिन speaks)", accent = true) {
            pendingAction = { runEnrollment() }
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }.withMargin())
        col.addView(Ui.pillButton(this, "Verify a voice (3s test)") {
            pendingAction = { runVerify() }
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }.withMargin())
        col.addView(Ui.pillButton(this, "Clear enrollment") {
            gate.clear()
            status.text = "Not enrolled yet"
            result.text = "Cleared."
        }.withMargin())

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun TextView.withMargin(): TextView = apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(this@EnrollActivity, 10) }
    }

    /** Drop leading/trailing silence so pauses don't dilute the voiceprint. */
    private fun trimSilence(pcm: ShortArray): ShortArray {
        val frame = RATE / 10 // 100ms
        fun rms(start: Int): Double {
            var s = 0.0
            val end = minOf(start + frame, pcm.size)
            for (i in start until end) s += pcm[i].toDouble() * pcm[i]
            return Math.sqrt(s / (end - start).coerceAtLeast(1))
        }
        var first = 0
        while (first + frame < pcm.size && rms(first) < 250) first += frame
        var last = pcm.size
        while (last - frame > first && rms(last - frame) < 250) last -= frame
        val trimmed = pcm.copyOfRange((first - frame).coerceAtLeast(0), (last + frame).coerceAtMost(pcm.size))
        return if (trimmed.size >= RATE) trimmed else pcm // keep original if trim went too far
    }

    @SuppressLint("MissingPermission")
    private fun record(seconds: Int, onTick: ((Int) -> Unit)? = null): ShortArray {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION, deliberately: the conversation records through
        // this processed path (AEC/NS), so the voiceprint must be built from
        // the SAME channel - enrolling on the raw mic skews every live score.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, RATE),
        )
        val out = ShortArray(RATE * seconds)
        rec.startRecording()
        var off = 0
        var lastTick = -1
        while (off < out.size) {
            val n = rec.read(out, off, out.size - off)
            if (n <= 0) break
            off += n
            val remaining = seconds - off / RATE
            if (remaining != lastTick) {
                lastTick = remaining
                onTick?.invoke(remaining)
            }
        }
        rec.stop(); rec.release()
        return out
    }

    private fun runEnrollment() {
        takes.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            for (i in 0 until TAKES) {
                // Show the sentence FIRST and give her a beat to read it -
                // recording used to start instantly and cut people off mid-line.
                withContext(Dispatchers.Main) {
                    status.text = "Take ${i + 1}/$TAKES - get ready..."
                    result.text = "\"${PROMPTS[i]}\""
                }
                Thread.sleep(PREP_MS)
                withContext(Dispatchers.Main) { status.text = "🔴 Speak now (${TAKE_SECONDS}s)" }
                val pcm = record(TAKE_SECONDS) { remaining ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (remaining > 0) status.text = "🔴 Speaking... ${remaining}s left"
                    }
                }
                takes.add(trimSilence(pcm))
                withContext(Dispatchers.Main) { status.text = "Take ${i + 1} done ✓" }
                Thread.sleep(900)
            }
            withContext(Dispatchers.Main) { status.text = "Computing voiceprint..." }
            runCatching { gate.enroll(takes) }
                .onSuccess { r ->
                    withContext(Dispatchers.Main) {
                        if (r.shaky) {
                            status.text = "Enrolled, but takes were inconsistent"
                            result.text = ("Takes matched each other only %.2f - maybe noise or the TV. " +
                                "Re-record in a quieter moment for a stronger voiceprint. " +
                                "Personal threshold set to %.2f.").format(r.minTakeSim, r.threshold)
                        } else {
                            status.text = "Enrolled ✓"
                            result.text = ("Voice consistency %.2f - personal threshold %.2f. " +
                                "Verify: मालकिन should score above it, others below.")
                                .format(r.meanTakeSim, r.threshold)
                        }
                    }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        status.text = "Enrollment failed"
                        result.text = e.message ?: "unknown error"
                    }
                }
        }
    }

    private fun runVerify() {
        if (!gate.enrolled) {
            result.text = "Enroll first."
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                status.text = "Get ready to speak..."
                result.text = "Say anything for a few seconds."
            }
            Thread.sleep(1000)
            withContext(Dispatchers.Main) { status.text = "🔴 Speak now..." }
            val pcm = trimSilence(record(4))
            val score = runCatching { gate.score(pcm) }.getOrNull()
            withContext(Dispatchers.Main) {
                status.text = if (gate.enrolled) "Enrolled ✓" else "Not enrolled yet"
                val thr = gate.effectiveThreshold
                result.text = when {
                    score == null -> "Too short/quiet - try again."
                    score >= thr -> "Score %.3f - recognized as मालकिन (threshold %.2f)".format(score, thr)
                    else -> "Score %.3f - NOT मालकिन (threshold %.2f)".format(score, thr)
                }
            }
        }
    }
}
