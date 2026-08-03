package app.suhani.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import android.util.Log
import app.suhani.core.Brain
import app.suhani.core.Ledger
import app.suhani.spikes.SaarasStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The conversation loop: mic -> Saaras v4 -> 5a turn-taking -> brain -> Bulbul.
 *
 * Implements the S2-validated layered stack:
 *   L1 acoustic boundaries  - Saaras server VAD events + on-device Silero
 *   L2 completeness waits   - TurnPolicy (verb-final rule), 500/1800ms
 *   L3 speculative cancel   - brain starts on likely-complete; her resuming
 *                             before first audio cancels the run; writes are
 *                             gated so a cancelled run never wrote anything
 * Barge-in ports the LiveKit tuning: >=2 transcribed words to interrupt,
 * sustained local speech pauses playback, false interruptions resume.
 */
class ConversationEngine(
    context: Context,
    apiKey: String,
    ledger: Ledger,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    interface Listener {
        fun onState(state: State)
        fun onCaption(text: String)
        /** Live audio level 0..1 - her voice while listening, Suhani's while speaking. */
        fun onLevel(level: Float) {}

        /** The conversation ended itself ("ठीक है अभी के लिए" + farewell played). */
        fun onSessionEnded() {}
    }

    companion object {
        private const val TAG = "suhani.engine"
        private const val RATE = 16000
        private const val SPECULATION_WRITE_GRACE_MS = 400L
        private const val BARGE_SUSTAIN_MS = 800L
        private const val FALSE_INTERRUPT_RESUME_MS = 2000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vad = Warmup.vad(context)
    private val speech = Speech(apiKey, context.cacheDir, Warmup.http)
    private val brain = Brain(apiKey, ledger, http = Warmup.http)
    private val speakerGate = Warmup.speakerGate(context)

    // Voice recognition state: pcm of the current local-VAD speech segment, and
    // a time window during which arriving finals belong to a foreign speaker.
    private val segmentPcm = ArrayList<Short>(16000 * 12)
    @Volatile private var foreignUntil = 0L
    @Volatile private var lastEndSpeechAt = 0L
    @Volatile private var sessionEnding = false
    @Volatile private var blankFires = 0

    private var stream: SaarasStream? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var micJob: Job? = null
    private var fireJob: Job? = null
    private var brainJob: Job? = null
    private var resumeJob: Job? = null

    private val running = AtomicBoolean(false)
    private val resumed = AtomicBoolean(false)   // she spoke again during THINKING
    @Volatile private var state = State.IDLE
    @Volatile private var localSpeech = false
    @Volatile private var localSpeechSince = 0L
    @Volatile private var playback: Speech.Playback? = null
    @Volatile private var streamHandle: Speech.StreamHandle? = null
    @Volatile private var interruptedPcm: ByteArray? = null
    private val turnBuffer = StringBuilder()

    private fun setState(s: State) {
        state = s
        listener.onState(s)
    }

    private val apiKeyRef = apiKey

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sessionEnding = false
        vad.reset() // cached across sessions - state must not leak between them
        Warmup.preconnect()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { audioManager.setCommunicationDevice(it) }

        brain.writeGate = {
            // Give her a beat to keep talking before anything is written.
            Thread.sleep(SPECULATION_WRITE_GRACE_MS)
            !resumed.get()
        }
        brain.onToolPhase = { hasWrites ->
            if (hasWrites && !resumed.get() && playback == null) {
                scope.launch(Dispatchers.IO) {
                    runCatching { Speech.Playback(speech.fillerPcm(Speech.FILLER_WRITE.random())).play() }
                }
            }
        }

        connectStream()
        startMic()
        setState(State.LISTENING)
        scope.launch(Dispatchers.IO) { // pre-warm filler + error clips
            runCatching {
                Speech.FILLER_WRITE.forEach { speech.fillerPcm(it) }
                speech.fillerPcm(Speech.ERROR_LINE)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        fireJob?.cancel(); brainJob?.cancel(); resumeJob?.cancel(); micJob?.cancel()
        playback?.stop()
        streamHandle?.stop()
        stream?.close(); stream = null
        record?.let { runCatching { it.stop() }; it.release() }; record = null
        aec?.release(); aec = null
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        setState(State.IDLE)
    }

    // ---------- audio in ----------

    private fun connectStream() {
        stream = SaarasStream(
            apiKey = apiKeyRef,
            onTranscript = ::onFinal,
            onVadEvent = ::onServerVad,
            onError = { err ->
                Log.w(TAG, "stt error: $err")
                if (running.get()) {
                    scope.launch(Dispatchers.IO) {
                        delay(600)
                        if (running.get()) connectStream() // mic loop keeps feeding the new socket
                    }
                }
            },
        ).also { it.connect() }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, RATE),
        )
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        rec.startRecording()
        record = rec

        micJob = scope.launch(Dispatchers.IO) {
            val window = ShortArray(SileroVad.WINDOW)
            val batch = ByteArray(RATE / 10 * 2) // 100ms upload batches
            var batchFill = 0
            var speechWindows = 0
            var silenceWindows = 0
            while (isActive && running.get()) {
                val n = rec.read(window, 0, window.size)
                if (n <= 0) continue
                // Feed the uplink.
                for (i in 0 until n) {
                    batch[batchFill++] = (window[i].toInt() and 0xFF).toByte()
                    batch[batchFill++] = (window[i].toInt() shr 8).toByte()
                    if (batchFill == batch.size) {
                        stream?.sendPcm(batch, batchFill)
                        batchFill = 0
                    }
                }
                // Live level for the aura (RMS of this window).
                if (state == State.LISTENING) {
                    var sum = 0.0
                    for (i in 0 until n) sum += window[i].toDouble() * window[i]
                    val rms = Math.sqrt(sum / n) / 32768.0
                    listener.onLevel((rms * 6).toFloat().coerceIn(0f, 1f))
                }
                // Feed local VAD (32ms windows).
                if (n == window.size) {
                    val speaking = runCatching { vad.isSpeech(window) }.getOrDefault(false)
                    if (speaking) { speechWindows++; silenceWindows = 0 } else { silenceWindows++; speechWindows = 0 }
                    if (localSpeech && segmentPcm.size < RATE * 12) {
                        for (i in 0 until n) segmentPcm.add(window[i])
                    }
                    if (!localSpeech && speechWindows >= 2) {
                        localSpeech = true
                        localSpeechSince = SystemClock.elapsedRealtime()
                        segmentPcm.clear()
                        onLocalSpeechStart()
                    } else if (localSpeech && silenceWindows >= 15) {
                        localSpeech = false
                        // Verify WHO just spoke - synchronously on this thread,
                        // so the verdict exists before the transcript arrives
                        // (finals lag segment end by 200-800ms; this takes ~100ms;
                        // the 4x AudioRecord buffer absorbs the stall).
                        if (speakerGate.enrolled && segmentPcm.size > 0) {
                            val seg = ShortArray(segmentPcm.size) { segmentPcm[it] }
                            val foreign = runCatching { speakerGate.isForeign(seg) }.getOrDefault(false)
                            if (foreign) {
                                foreignUntil = SystemClock.elapsedRealtime() + 2500
                                Log.i(TAG, "foreign speaker segment - finals in next 2.5s will be dropped")
                            }
                        }
                        segmentPcm.clear()
                    }
                }
            }
        }
    }

    // ---------- turn-taking (5a) ----------

    private fun onServerVad(signal: String) {
        if (signal == "END_SPEECH" && state == State.LISTENING) {
            lastEndSpeechAt = SystemClock.elapsedRealtime()
            scheduleFire()
        }
        if (signal == "START_SPEECH") fireJob?.cancel()
    }

    private fun onLocalSpeechStart() {
        fireJob?.cancel()
        when (state) {
            State.THINKING -> resumed.set(true) // cancels speculation at the next checkpoint
            State.SPEAKING -> watchBargeIn()
            else -> {}
        }
    }

    private fun onFinal(text: String) {
        if (SystemClock.elapsedRealtime() < foreignUntil) {
            Log.i(TAG, "dropped foreign-speaker final: $text")
            return
        }
        synchronized(turnBuffer) { turnBuffer.append(' ').append(text) }
        listener.onCaption(text)
        when (state) {
            // ALWAYS (re)schedule on a final: Saaras emits END_SPEECH before the
            // transcript, so the END_SPEECH-time schedule can find an empty
            // buffer and bail - without this line the turn never fires and she
            // has to speak again ("hello are you there?") to wake Suhani.
            State.LISTENING -> scheduleFire()
            State.THINKING -> resumed.set(true)
            State.SPEAKING -> if (text.trim().split(Regex("\\s+")).size >= 2) interruptPlayback()
            else -> {}
        }
    }

    private fun currentTurnText(): String = synchronized(turnBuffer) { turnBuffer.toString().trim() }

    private fun scheduleFire() {
        fireJob?.cancel()
        val text = currentTurnText()
        if (text.isEmpty()) return
        // Count the wait from when she actually STOPPED SPEAKING (the server's
        // END_SPEECH), not from transcript arrival - the transcript lags the
        // boundary by 400-800ms and re-waiting from it was pure added latency.
        val sinceEnd = SystemClock.elapsedRealtime() - lastEndSpeechAt
        val wait = (TurnPolicy.waitFor(text) - sinceEnd).coerceAtLeast(60L)
        fireJob = scope.launch(Dispatchers.IO) {
            delay(wait)
            if (!running.get() || state != State.LISTENING) return@launch
            if (localSpeech) {
                // VAD says she's mid-speech - give it a beat, then fire anyway:
                // a stuck flag must never mute Suhani (barge-in covers the rest).
                delay(600)
                if (!running.get() || state != State.LISTENING) return@launch
            }
            fire()
        }
    }

    private fun fire() {
        val text = currentTurnText()
        if (text.isEmpty()) return
        resumed.set(false)
        setState(State.THINKING)
        val t0 = SystemClock.elapsedRealtime()
        brainJob = scope.launch(Dispatchers.IO) {
            var earlyText: String? = null
            var earlyHandle: Speech.StreamHandle? = null
            try {
                // True first-danda cutover: the moment the streamed reply
                // crosses its first sentence boundary, Bulbul starts SPEAKING
                // it while the model is still writing the rest.
                val reply = brain.send(text) { s1 ->
                    if (running.get() && !resumed.get() && !localSpeech) {
                        earlyText = s1
                        earlyHandle = speech.startStreaming(s1)
                        streamHandle = earlyHandle
                        setState(State.SPEAKING)
                        listener.onCaption(s1)
                    }
                }
                val tBrain = SystemClock.elapsedRealtime()
                val earlyStopped = earlyHandle?.wasStopped == true
                if ((resumed.get() || earlyStopped) && brain.lastRunWrites == 0) {
                    // She kept talking (or cut the early audio off) and nothing
                    // was written - this run never happened.
                    earlyHandle?.stop()
                    streamHandle = null
                    brain.rollbackLastTurn()
                    setState(State.LISTENING)
                    scheduleFire() // her fuller utterance must still get its turn
                    return@launch
                }
                if (reply.isBlank()) {
                    // A blank reply is usually the model choosing silence (side
                    // speech). Retry the turn once; twice blank = drop it, or
                    // this loops the same utterance against the API forever.
                    blankFires++
                    setState(State.LISTENING)
                    if (blankFires >= 2) {
                        Log.i(TAG, "blank twice - treating as side-speech, dropping turn")
                        synchronized(turnBuffer) { turnBuffer.setLength(0) }
                        blankFires = 0
                    } else {
                        scheduleFire()
                    }
                    return@launch
                }
                blankFires = 0
                if (reply.contains("[चुप]") || reply.contains("[chup]")) {
                    // She was talking to someone in the room, not to Suhani -
                    // stay silent, discard the side-speech from the turn buffer.
                    Log.i(TAG, "side-speech turn - staying silent: $text")
                    synchronized(turnBuffer) { turnBuffer.setLength(0) }
                    setState(State.LISTENING)
                    return@launch
                }
                // Sarvam occasionally emits a tool call as raw JSON TEXT in the
                // reply instead of a structured call. Never speak JSON aloud:
                // salvage end_conversation, retry anything else via the blank path.
                val trimmedReply = reply.trim()
                val malformedToolJson = trimmedReply.startsWith("{") && trimmedReply.contains("\"name\"")
                if (malformedToolJson && !trimmedReply.contains("end_conversation")) {
                    Log.w(TAG, "malformed tool-call text - retrying turn: ${trimmedReply.take(80)}")
                    blankFires++
                    setState(State.LISTENING)
                    if (blankFires >= 2) {
                        synchronized(turnBuffer) { turnBuffer.setLength(0) }
                        blankFires = 0
                    } else {
                        scheduleFire()
                    }
                    return@launch
                }
                var speakText = reply
                if (brain.endRequested || malformedToolJson ||
                    reply.contains("[बंद]") || reply.contains("[band]")
                ) {
                    // "ठीक है अभी के लिए" - she is done; say the farewell, then
                    // the session ends itself instead of listening forever.
                    sessionEnding = true
                    speakText = reply.replace("[बंद]", "").replace("[band]", "").trim()
                    Log.i(TAG, "hang-up requested by conversation")
                    if (speakText.isBlank() || speakText.startsWith("{")) speakText = "ठीक है जी, नमस्ते!"
                }
                // Commit point: the turn is confirmed, the buffer starts fresh.
                synchronized(turnBuffer) { turnBuffer.setLength(0) }
                Log.i(TAG, "turn: brain=${tBrain - t0}ms early=${earlyText != null} writes=${brain.lastRunWrites}")
                listener.onCaption(speakText)

                val et = earlyText
                if (et != null && earlyHandle != null && speakText.startsWith(et.take(12))) {
                    // Sentence 1 is ALREADY PLAYING via the stream; synthesize
                    // the remainder while she hears it.
                    val restText = speakText.removePrefix(et).trim()
                    val restJob = if (restText.isNotBlank()) {
                        scope.async(Dispatchers.IO) { runCatching { speech.synthesize(restText) }.getOrNull() }
                    } else null
                    val firstDone = earlyHandle!!.awaitFinished()
                    streamHandle = null
                    val restPcm = restJob?.await()
                    if (firstDone && restPcm != null && running.get() && !resumed.get()) {
                        speak(restPcm)
                    } else {
                        restJob?.cancel()
                        if (firstDone && sessionEnding) {
                            listener.onSessionEnded()
                        } else if (running.get() && state != State.THINKING) {
                            setState(State.LISTENING)
                        }
                    }
                } else {
                    // No early sentence (short reply, or guard-altered text):
                    // classic pipelined REST path.
                    earlyHandle?.stop()
                    streamHandle = null
                    val split = Regex("(?<=[।?!])\\s+").split(speakText.trim(), 2)
                    val firstText = split[0]
                    val restText = if (split.size > 1) split[1] else ""
                    val pcm = speech.synthesize(firstText)
                    if (resumed.get() && brain.lastRunWrites == 0) {
                        brain.rollbackLastTurn()
                        setState(State.LISTENING)
                        return@launch
                    }
                    if (restText.isNotBlank()) {
                        val restJob = scope.async(Dispatchers.IO) {
                            runCatching { speech.synthesize(restText) }.getOrNull()
                        }
                        val firstDone = speak(pcm, finalSegment = false)
                        val restPcm = restJob.await()
                        if (firstDone && restPcm != null && running.get() && !resumed.get()) {
                            speak(restPcm)
                        } else {
                            restJob.cancel()
                            if (sessionEnding) {
                                listener.onSessionEnded()
                            } else if (running.get() && state != State.THINKING) {
                                setState(State.LISTENING)
                            }
                        }
                    } else {
                        speak(pcm)
                    }
                }
            } catch (_: Brain.SpeculationCancelled) {
                earlyHandle?.stop()
                streamHandle = null
                brain.rollbackLastTurn()
                setState(State.LISTENING)
                scheduleFire()
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                earlyHandle?.stop()
                streamHandle = null
                runCatching { speak(speech.fillerPcm(Speech.ERROR_LINE)) }
                setState(State.LISTENING)
            }
        }
    }

    // ---------- audio out + barge-in ----------

    private fun speak(pcm: ByteArray, finalSegment: Boolean = true): Boolean {
        setState(State.SPEAKING)
        interruptedPcm = pcm
        val pb = Speech.Playback(pcm)
        playback = pb
        val reached = pb.play()
        playback = null
        if (pb.finished) {
            interruptedPcm = null
            if (running.get() && finalSegment) {
                if (sessionEnding) listener.onSessionEnded() else setState(State.LISTENING)
            }
        } else {
            // Stopped mid-way - barge-in flow owns what happens next.
            interruptedPcm = pcm.copyOfRange(reached.coerceAtMost(pcm.size), pcm.size)
        }
        return pb.finished
    }

    private fun watchBargeIn() {
        resumeJob?.cancel()
        resumeJob = scope.launch(Dispatchers.IO) {
            delay(BARGE_SUSTAIN_MS)
            if (!localSpeech || state != State.SPEAKING) return@launch
            // Sustained speech while Suhani talks: pause her.
            playback?.stop()
            streamHandle?.stop()
            val paused = SystemClock.elapsedRealtime()
            // If no real words arrive, it was noise - pick the sentence back up.
            delay(FALSE_INTERRUPT_RESUME_MS)
            val spokeWords = currentTurnText().isNotEmpty()
            val remaining = interruptedPcm
            if (!spokeWords && remaining != null && running.get() && state != State.THINKING) {
                Log.i(TAG, "false interruption (${SystemClock.elapsedRealtime() - paused}ms) - resuming")
                speak(remaining)
            }
        }
    }

    private fun interruptPlayback() {
        // Real words while she speaks: she wins, immediately.
        playback?.stop()
        streamHandle?.stop()
        interruptedPcm = null
        setState(State.LISTENING)
    }
}
