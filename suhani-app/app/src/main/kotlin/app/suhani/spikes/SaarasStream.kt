package app.suhani.spikes

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Minimal Saaras v4 streaming client - the exact protocol verified against
 * wss://api.sarvam.ai/speech-to-text/ws on 2026-08-02 (same URL params, config,
 * chunk format, and end_of_stream message the LiveKit plugin uses).
 */
class SaarasStream(
    private val apiKey: String,
    private val sampleRate: Int = 16000,
    private val onTranscript: (String) -> Unit,
    private val onVadEvent: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : WebSocketListener() {

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val url = "wss://api.sarvam.ai/speech-to-text/ws" +
            "?language-code=hi-IN&model=saaras:v4&mode=codemix" +
            "&vad_signals=true&sample_rate=$sampleRate" +
            "&high_vad_sensitivity=true&flush_signal=true"
        val request = Request.Builder()
            .url(url)
            .addHeader("api-subscription-key", apiKey)
            .build()
        ws = client.newWebSocket(request, this)
    }

    fun sendPcm(chunk: ByteArray, length: Int = chunk.size) {
        val b64 = Base64.encodeToString(chunk, 0, length, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "audio",
            JSONObject().put("data", b64).put("encoding", "audio/wav").put("sample_rate", sampleRate),
        )
        ws?.send(msg.toString())
    }

    fun endStream() {
        val msg = JSONObject()
            .put("type", "end_of_stream")
            .put(
                "audio",
                JSONObject().put("data", "").put("encoding", "audio/wav").put("sample_rate", sampleRate),
            )
        ws?.send(msg.toString())
    }

    fun close() {
        ws?.close(1000, null)
        ws = null
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val data = JSONObject(text)
            when (data.optString("type")) {
                "data" -> {
                    val transcript = data.optJSONObject("data")?.optString("transcript").orEmpty()
                    if (transcript.isNotBlank()) onTranscript(transcript)
                }
                "events" -> {
                    val signal = data.optJSONObject("data")?.optString("signal_type").orEmpty()
                    if (signal.isNotBlank()) onVadEvent(signal)
                }
            }
        } catch (e: Exception) {
            onError("parse: ${e.message}")
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        onError("ws: ${t.message} (http ${response?.code})")
    }
}
