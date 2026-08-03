package app.suhani.spikes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams the mic to a SaarasStream in 100ms PCM16 chunks.
 *
 * Uses VOICE_COMMUNICATION: this is the load-bearing choice - it engages the
 * platform echo canceller so Suhani's own voice (played on the loudspeaker)
 * is subtracted before her mother's speech reaches STT. S1 exists to measure
 * exactly how well this works on the POCO X6.
 */
class MicStreamer(private val stream: SaarasStream, private val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var job: Job? = null

    val sessionId: Int get() = record?.audioSessionId ?: 0

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, sampleRate), // >= 0.5s of headroom
        )
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        rec.startRecording()
        record = rec

        val chunk = ByteArray(sampleRate / 10 * 2) // 100ms of int16 mono
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) stream.sendPcm(chunk, n)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        aec?.release()
        aec = null
    }
}
