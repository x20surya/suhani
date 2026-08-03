package app.suhani.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Silero VAD (v5 ONNX, same file livekit-plugins-silero ships).
 * S2 proved this is load-bearing: the server's START_SPEECH lags real speech
 * by up to ~1.1s, while local detection lands within ~150ms - the speculative
 * cancel layer only works because of this.
 *
 * 16kHz interface: 512-sample windows with 64 samples of carried context;
 * inputs "input"(1,576) f32, "state"(2,1,128) f32, "sr" int64; outputs
 * probability and the new state.
 */
class SileroVad(modelBytes: ByteArray, private val threshold: Float = 0.65f) {

    companion object {
        const val WINDOW = 512
        private const val CONTEXT = 64
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)
    private var state = FloatArray(2 * 128)
    private var context = FloatArray(CONTEXT)

    /** Feed one 512-sample PCM16 window; returns speech probability 0..1. */
    fun probability(window: ShortArray): Float {
        require(window.size == WINDOW)
        val input = FloatArray(CONTEXT + WINDOW)
        context.copyInto(input, 0)
        for (i in 0 until WINDOW) input[CONTEXT + i] = window[i] / 32768f
        input.copyInto(context, 0, input.size - CONTEXT, input.size)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { inT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf(1)).use { srT ->
                    session.run(mapOf("input" to inT, "state" to stT, "sr" to srT)).use { out ->
                        val prob = (out[0].value as Array<FloatArray>)[0][0]
                        val newState = out[1].value as Array<Array<FloatArray>>
                        var k = 0
                        for (a in newState) for (b in a) for (v in b) state[k++] = v
                        return prob
                    }
                }
            }
        }
    }

    fun isSpeech(window: ShortArray): Boolean = probability(window) >= threshold

    fun reset() {
        state = FloatArray(2 * 128)
        context = FloatArray(CONTEXT)
    }
}
