package app.suhani.spikes

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spike S1: while Suhani's voice plays on the LOUDSPEAKER, does the platform
 * echo canceller keep her out of the mic stream?
 *
 * Pass bar: < 10% of the played words come back as transcript with nobody
 * speaking in the room. (Run it in a quiet room, phone on the table, hands off.)
 */
class S1EchoActivity : AppCompatActivity() {

    // Distinctive words in the bundled clip - leak scoring set.
    private val targetWords = listOf(
        "नमस्ते", "मालकिन", "सुनीता", "ब्लाउज़", "शुक्रवार", "तैयार",
        "मीना", "डेढ़", "रुपये", "एडवांस", "परसों", "गुरुवार", "लहंगा", "पेटीकोट", "फिटिंग",
    )

    private lateinit var status: TextView
    private lateinit var transcriptView: TextView
    private lateinit var result: TextView
    private lateinit var runButton: Button
    private val transcripts = StringBuilder()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runTest() else status.text = "Mic permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "S1 - AEC echo test"; textSize = 22f })
        col.addView(TextView(this).apply {
            text = "Quiet room. Phone on table, loudspeaker on. Do NOT speak during the run."
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply { text = "Ready" }
        transcriptView = TextView(this).apply { setPadding(0, pad, 0, 0) }
        result = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, 0) }
        runButton = Button(this).apply {
            text = "Run S1"
            setOnClickListener {
                isEnabled = false
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        col.addView(runButton)
        col.addView(status)
        col.addView(result)
        col.addView(transcriptView)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun runTest() {
        transcripts.clear()
        result.text = ""
        status.text = "Connecting to Saaras v4..."

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { am.setCommunicationDevice(it) }

        val stream = SaarasStream(
            apiKey = app.suhani.Keys.sarvam(this),
            onTranscript = { t ->
                runOnUiThread {
                    transcripts.append(t).append('\n')
                    transcriptView.text = "Heard back:\n$transcripts"
                }
            },
            onError = { e -> runOnUiThread { status.text = "Error: $e" } },
        )
        stream.connect()
        val mic = MicStreamer(stream)
        mic.start(lifecycleScope)

        lifecycleScope.launch(Dispatchers.IO) {
            delay(700) // let the WS settle and AEC engage
            withContext(Dispatchers.Main) { status.text = "Playing Suhani's voice..." }

            // Bundled Bulbul clip: 24kHz mono PCM16 wav; skip the 44-byte header.
            val wav = assets.open("s1_suhani_test.wav").readBytes()
            val pcm = wav.copyOfRange(44, wav.size)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            track.stop()
            track.release()

            withContext(Dispatchers.Main) { status.text = "Playback done - draining STT..." }
            delay(2500)
            stream.endStream()
            delay(3000)
            mic.stop()
            stream.close()
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL

            val heard = transcripts.toString()
            val leaked = targetWords.filter { heard.contains(it) }
            val pct = leaked.size * 100 / targetWords.size
            withContext(Dispatchers.Main) {
                status.text = "Done"
                runButton.isEnabled = true
                result.text = buildString {
                    append(if (pct < 10) "PASS" else "FAIL")
                    append(" - leak $pct% (${leaked.size}/${targetWords.size} words)\n")
                    if (leaked.isNotEmpty()) append("Leaked: ${leaked.joinToString(", ")}")
                }
            }
        }
    }
}
