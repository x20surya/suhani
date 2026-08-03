package app.suhani.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.sqrt

/**
 * Voice recognition: is this speech segment मालकिन, or someone else in the room?
 *
 * CAM++ speaker embeddings (3D-Speaker zh+en advanced, via sherpa-onnx) compared
 * by cosine similarity against a locally-stored enrollment. The voiceprint never
 * leaves the phone. Segments shorter than MIN_SECONDS are passed through as
 * owner (a quick "हाँ" is too short for a reliable embedding - never drop it).
 */
class SpeakerGate(context: Context) {

    companion object {
        private const val TAG = "suhani.speaker"
        const val MODEL_ASSET = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        // Host calibration (calibrate_speaker.py, shipped model, Bulbul voices):
        // same-voice 0.70-0.77, different-voice 0.39-0.54 (synthetic voices share
        // a vocoder, inflating impostor scores - real humans separate wider).
        // 0.55 splits the observed distributions; re-tune on Surya-vs-mom via
        // the Verify button in EnrollActivity.
        const val THRESHOLD = 0.55f
        const val MIN_SECONDS = 0.8f
        private const val RATE = 16000
    }

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = context.assets,
        config = SpeakerEmbeddingExtractorConfig(model = MODEL_ASSET, numThreads = 2),
    )
    private val ownerFile = File(context.filesDir, "owner_voice.emb")
    private val thresholdFile = File(context.filesDir, "owner_voice.thr")

    @Volatile
    var owner: FloatArray? = loadOwner()
        private set

    /** Per-voice threshold derived at enrollment (Voice Match-style); falls
     * back to the global default for pre-existing enrollments. */
    @Volatile
    var effectiveThreshold: Float =
        thresholdFile.takeIf { it.exists() }?.readText()?.toFloatOrNull() ?: THRESHOLD
        private set

    val enrolled: Boolean get() = owner != null

    data class EnrollResult(val meanTakeSim: Float, val minTakeSim: Float, val threshold: Float) {
        /** Takes that barely match each other = noisy/rushed enrollment. */
        val shaky: Boolean get() = minTakeSim < 0.5f
    }

    private fun loadOwner(): FloatArray? {
        if (!ownerFile.exists()) return null
        return try {
            val bytes = ownerFile.readBytes()
            FloatArray(bytes.size / 4) { i ->
                java.lang.Float.intBitsToFloat(
                    (bytes[i * 4].toInt() and 0xFF) or
                        ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "owner load failed", e)
            null
        }
    }

    fun embed(pcm: ShortArray): FloatArray {
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            stream.inputFinished()
            return extractor.compute(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-9f)
    }

    /** Similarity of this segment to मालकिन, or null when not enrolled / too short. */
    fun score(pcm: ShortArray): Float? {
        val o = owner ?: return null
        if (pcm.size < (MIN_SECONDS * RATE).toInt()) return null
        return cosine(embed(pcm), o)
    }

    /** true = someone ELSE. null-score segments are never foreign. */
    fun isForeign(pcm: ShortArray): Boolean {
        val s = score(pcm) ?: return false
        Log.i(TAG, "segment score ${"%.3f".format(s)} vs thr ${"%.2f".format(effectiveThreshold)} (${pcm.size / RATE.toFloat()}s)")
        return s < effectiveThreshold
    }

    /** Average enrollment takes into the voiceprint, deriving a per-voice
     * threshold from how consistently the speaker matches herself. */
    fun enroll(takes: List<ShortArray>): EnrollResult {
        val embs = takes.map { embed(it) }
        // Take-to-take consistency drives the personal threshold.
        val sims = mutableListOf<Float>()
        for (i in embs.indices) for (j in i + 1 until embs.size) sims.add(cosine(embs[i], embs[j]))
        val meanSim = sims.average().toFloat()
        val minSim = sims.min()
        // Threshold sits a safety margin below her own consistency, clamped so
        // neither a perfect studio take nor a shaky one produces extremes.
        val thr = (meanSim - 0.18f).coerceIn(0.40f, 0.60f)
        val dim = embs[0].size
        val mean = FloatArray(dim)
        for (e in embs) for (i in 0 until dim) mean[i] += e[i] / embs.size
        owner = mean
        effectiveThreshold = thr
        thresholdFile.writeText(thr.toString())
        val bytes = ByteArray(dim * 4)
        for (i in 0 until dim) {
            val bits = java.lang.Float.floatToIntBits(mean[i])
            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        ownerFile.writeBytes(bytes)
        Log.i(TAG, "enrolled: ${takes.size} takes, dim $dim, takeSim mean ${"%.3f".format(meanSim)} min ${"%.3f".format(minSim)}, thr ${"%.2f".format(thr)}")
        return EnrollResult(meanSim, minSim, thr)
    }

    fun clear() {
        owner = null
        effectiveThreshold = THRESHOLD
        ownerFile.delete()
        thresholdFile.delete()
    }
}
