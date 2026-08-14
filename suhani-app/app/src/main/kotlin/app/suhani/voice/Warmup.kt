package app.suhani.voice

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Process-wide caches so a conversation tap doesn't pay model-load or TLS
 * costs: the two ONNX models load once per process, and both Sarvam hosts get
 * their HTTPS connections established while the UI is still animating.
 */
object Warmup {

    /** One HTTP client for everything - shared connection pool means the
     * brain's and TTS's TLS handshakes are paid once, then reused.
     * Timeouts tuned for streaming: OkHttp's default 10s read timeout was
     * killing turns whenever the model paused (SocketTimeoutException, "tell
     * it again"); ping keep-alive protects the idle WebSockets. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var vad: SileroVad? = null
    @Volatile private var gate: SpeakerGate? = null

    fun vad(context: Context): SileroVad =
        vad ?: synchronized(this) {
            vad ?: SileroVad(context.applicationContext.assets.open("silero_vad.onnx").readBytes())
                .also { vad = it }
        }

    fun speakerGate(context: Context): SpeakerGate =
        gate ?: synchronized(this) {
            gate ?: SpeakerGate(context.applicationContext).also { gate = it }
        }

    /** Fire-and-forget TLS establishment to api.sarvam.ai - the pooled
     * connection then serves the first real brain/TTS call handshake-free. */
    fun preconnect() {
        val req = Request.Builder().url("https://api.sarvam.ai/").head().build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }
}
