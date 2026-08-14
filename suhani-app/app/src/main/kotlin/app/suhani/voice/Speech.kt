package app.suhani.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bulbul v3 synthesis (REST) + an interruptible player with resume support. */
class Speech(
    private val apiKey: String,
    private val cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
) {

    private object End

    companion object {
        const val RATE = 24000
        val FILLER_WRITE = listOf("हाँ, लिखती हूँ...", "ठीक है, लिख रही हूँ...")
        const val ERROR_LINE = "माफ़ कीजिए, थोड़ी दिक्कत आ गई। एक बार फिर बोलिए।"
        const val ERROR_CREDITS = "API के पैसे ख़त्म हो गए लगते हैं। ज़रा recharge करवा लीजिए।"
        const val ERROR_KEY = "API key ग़लत लग रही है। Settings में key बदलिए।"
    }

    /** Synthesize text -> raw 24k mono PCM16 (wav header stripped). */
    fun synthesize(text: String, speaker: String = "suhani"): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model", "bulbul:v3")
            .put("speaker", speaker)
            .put("target_language_code", "hi-IN")
            .put("speech_sample_rate", RATE)
            .put("pace", 1.12)        // brisker - she was dragging words at 1.05
            .put("temperature", 0.85) // livelier, more human delivery
        val req = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .addHeader("api-subscription-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val json = JSONObject(res.body?.string() ?: throw IllegalStateException("empty TTS body"))
            if (!res.isSuccessful) throw IllegalStateException("tts http ${res.code}")
            val wav = Base64.decode(json.getJSONArray("audios").getString(0), Base64.DEFAULT)
            return wav.copyOfRange(44, wav.size)
        }
    }

    /** Cached filler clip - synthesized once, instant forever after. */
    fun fillerPcm(text: String): ByteArray {
        val f = File(cacheDir, "filler_${text.hashCode()}.pcm")
        if (!f.exists()) f.writeBytes(synthesize(text))
        return f.readBytes()
    }

    /**
     * Streaming synthesis: opens Bulbul's WebSocket, plays PCM chunks the
     * moment they arrive (~350ms to first audio vs REST's full-synthesis
     * wait), collects everything for barge-in/resume, falls back to REST
     * transparently if the socket path fails before any audio played.
     */
    fun startStreaming(text: String, speaker: String = "suhani"): StreamHandle =
        StreamHandle(text, speaker).also { it.begin() }

    inner class StreamHandle(private val text: String, private val speaker: String) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
        private val collected = java.io.ByteArrayOutputStream()
        private val stopped = AtomicBoolean(false)
        @Volatile var finished = false
            private set
        val wasStopped: Boolean get() = stopped.get()
        @Volatile private var failedBeforeAudio = false
        private var ws: okhttp3.WebSocket? = null
        private var thread: Thread? = null

        fun begin() {
            val req = Request.Builder()
                .url("wss://api.sarvam.ai/text-to-speech/ws?model=bulbul:v3&send_completion_event=true")
                .addHeader("api-subscription-key", apiKey)
                .build()
            ws = http.newWebSocket(req, object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    val config = JSONObject().put("type", "config").put(
                        "data",
                        JSONObject()
                            .put("target_language_code", "hi-IN")
                            .put("speaker", speaker)
                            .put("pace", 1.12)
                            .put("model", "bulbul:v3")
                            .put("speech_sample_rate", RATE)
                            .put("output_audio_codec", "linear16")
                            .put("temperature", 0.85)
                            .put("min_buffer_size", 30)
                            .put("max_chunk_length", 250),
                    )
                    webSocket.send(config.toString())
                    webSocket.send(JSONObject().put("type", "text").put("data", JSONObject().put("text", text)).toString())
                    webSocket.send(JSONObject().put("type", "flush").toString())
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, msgText: String) {
                    try {
                        val msg = JSONObject(msgText)
                        when (msg.optString("type")) {
                            "audio" -> {
                                val b64 = msg.optJSONObject("data")?.optString("audio").orEmpty()
                                if (b64.isNotEmpty()) queue.put(Base64.decode(b64, Base64.DEFAULT))
                            }
                            "event", "error" -> {
                                if (msg.optString("type") == "error") {
                                    app.suhani.AppLog.w("suhani.tts", "ws error: ${msgText.take(160)}")
                                    if (collected.size() == 0) failedBeforeAudio = true
                                }
                                queue.put(End)
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    // "Socket closed" AFTER a finished utterance is normal
                    // teardown, not a failure worth logging.
                    if (!finished && !stopped.get()) {
                        app.suhani.AppLog.w("suhani.tts", "ws failure: ${t.message}")
                    }
                    if (collected.size() == 0) failedBeforeAudio = true
                    queue.put(End)
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    queue.put(End)
                }
            })
            thread = Thread { playLoop() }.also { it.start() }
        }

        private fun playLoop() {
            var track: AudioTrack? = null
            var framesWritten = 0
            try {
                while (!stopped.get()) {
                    val item = queue.poll(8, TimeUnit.SECONDS) ?: break
                    if (item === End) break
                    val pcm = item as ByteArray
                    collected.write(pcm)
                    if (track == null) {
                        track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                            .setAudioFormat(
                                AudioFormat.Builder().setSampleRate(RATE)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                            )
                            .setBufferSizeInBytes(RATE)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        track.play()
                    }
                    var off = 0
                    while (off < pcm.size && !stopped.get()) {
                        val n = track.write(pcm, off, minOf(4800, pcm.size - off))
                        if (n <= 0) break
                        off += n
                    }
                    framesWritten += off / 2
                }
                if (!stopped.get() && failedBeforeAudio) {
                    // Socket died before any sound: REST fallback, same voice.
                    runCatching { synthesize(text, speaker) }.getOrNull()?.let { pcm ->
                        collected.write(pcm)
                        Playback(pcm).also { fallback ->
                            fallbackPlayback = fallback
                            fallback.play()
                        }
                        finished = !stopped.get()
                        return
                    }
                }
                if (track != null && !stopped.get()) {
                    val deadline = System.currentTimeMillis() + 4000
                    while (!stopped.get() && track.playbackHeadPosition < framesWritten &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(25)
                    }
                    Thread.sleep(60)
                    finished = true
                }
            } catch (_: InterruptedException) {
            } finally {
                track?.let {
                    if (stopped.get()) runCatching { it.pause(); it.flush() }
                    runCatching { it.stop() }
                    it.release()
                }
                ws?.close(1000, null)
            }
        }

        @Volatile private var fallbackPlayback: Playback? = null

        fun stop() {
            stopped.set(true)
            fallbackPlayback?.stop()
            ws?.cancel()
            thread?.interrupt()
        }

        /** Block until playback ends; true when the utterance played fully. */
        fun awaitFinished(): Boolean {
            thread?.join(30_000)
            return finished
        }
    }

    /**
     * Interruptible playback. Blocks until done or stopped; returns the byte
     * offset reached (== pcm.size when fully played) so a false interruption
     * can resume from where it stopped.
     */
    class Playback(private val pcm: ByteArray, private var offset: Int = 0) {
        private val stopped = AtomicBoolean(false)
        @Volatile var playing = false
            private set

        val position: Int get() = offset
        val finished: Boolean get() = offset >= pcm.size

        fun stop() = stopped.set(true)

        fun play(): Int {
            stopped.set(false)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(RATE) // ~0.5s of int16
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            playing = true
            val startOffset = offset
            try {
                track.play()
                while (offset < pcm.size && !stopped.get()) {
                    val n = track.write(pcm, offset, minOf(4800, pcm.size - offset))
                    if (n <= 0) break
                    offset += n
                }
                if (!stopped.get()) {
                    // Wait until the hardware has PLAYED every frame we wrote -
                    // stop() discards the buffered tail, which used to clip the
                    // last word of every reply.
                    val totalFrames = (offset - startOffset) / 2
                    val deadline = System.currentTimeMillis() + 4000
                    while (!stopped.get() && track.playbackHeadPosition < totalFrames &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(25)
                    }
                    Thread.sleep(60) // small pad past the final frame
                }
            } finally {
                if (stopped.get()) {
                    // Barge-in: discard what she no longer wants to hear.
                    runCatching { track.pause(); track.flush() }
                }
                runCatching { track.stop() }
                track.release()
                playing = false
            }
            return offset
        }
    }
}
