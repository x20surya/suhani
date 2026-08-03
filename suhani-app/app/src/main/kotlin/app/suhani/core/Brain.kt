package app.suhani.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Port of brain_text.SarvamChatSession - Sarvam-105B at the bake-off's winning
 * fixed/low config: guards + calendar + resolve_due_date + 1024-token budget +
 * one deterministic retry on empty replies. ~94-96% across ~1,300 test calls.
 */
class Brain(
    private val apiKey: String,
    private val ledger: Ledger,
    private val model: String = "sarvam-105b",
    private val baseUrl: String = "https://api.sarvam.ai/v1",
    private val effort: String = "low",
    private val maxTokens: Int = 1024,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 8
        const val FAILURE_LINE = "माफ़ कीजिए, यह काम पूरा नहीं हो पाया। दोबारा बोलिए।"
        // History cap: system prompt + the last ~10 turns. Without it, a long
        // session's prefill grows unbounded and TTFT creeps up turn by turn.
        const val MAX_HISTORY_MESSAGES = 30
    }

    private val toolset = Tools.openaiToolset()
    private val messages = JSONArray()

    /** Marks whether the LAST send() executed any write tool - the speculative
     * layer uses this to know a cancelled run must never have written. */
    var lastRunWrites: Int = 0
        private set

    /** Set when the model called end_conversation this turn. */
    var endRequested: Boolean = false
        private set

    private val writeTools = setOf("add_client", "add_order", "update_order", "mark_paid", "remember", "forget_note")

    /** Thrown out of send() when writeGate aborts a speculative run. */
    class SpeculationCancelled : RuntimeException()

    /**
     * Called immediately BEFORE the first write tool of a run executes.
     * Return true to commit, false to abort the whole run with zero writes
     * (S2's invariant: a cancelled speculative run must never have written).
     * The engine implements this as a short grace wait watching for the
     * speaker to resume mid-thought.
     */
    @Volatile
    var writeGate: (() -> Boolean)? = null

    /** Fires when a completion round returned tool calls; hasWrites signals a
     * slow two-round turn so the engine can play a filler ack. */
    @Volatile
    var onToolPhase: ((hasWrites: Boolean) -> Unit)? = null

    init {
        var system = Prompts.STABLE_SYSTEM + "\n\n" + Prompts.SARVAM_GUARDS + "\n\n" + Prompts.calendarTable()
        val notes = Prompts.notesContext(ledger.listNotes())
        if (notes.isNotEmpty()) system += "\n\n" + notes
        system += "\n\n" + Prompts.dateContext()
        messages.put(JSONObject().put("role", "system").put("content", system))
    }

    /** Streaming completion (SSE). Assembles the assistant message - including
     * tool calls, which arrive as index-keyed fragments - and feeds content
     * deltas to [onDelta] as they stream. */
    private fun call(onDelta: ((String) -> Unit)? = null): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", toolset)
            .put("max_tokens", maxTokens)
            .put("reasoning_effort", effort)
            .put("stream", true)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("api-subscription-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                val text = res.body?.string().orEmpty()
                throw IllegalStateException("brain http ${res.code}: ${text.take(300)}")
            }
            val source = res.body?.source() ?: throw IllegalStateException("empty response body")
            val content = StringBuilder()
            val toolIds = HashMap<Int, String>()
            val toolNames = HashMap<Int, String>()
            val toolArgs = HashMap<Int, StringBuilder>()
            var sawTool = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val delta = try {
                    JSONObject(payload).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                } catch (_: Exception) {
                    continue
                }
                if (!delta.isNull("content")) {
                    val piece = delta.optString("content")
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        // Tool rounds must not leak preamble into early TTS.
                        if (!sawTool) onDelta?.invoke(piece)
                    }
                }
                delta.optJSONArray("tool_calls")?.let { fragments ->
                    sawTool = true
                    for (i in 0 until fragments.length()) {
                        val frag = fragments.getJSONObject(i)
                        val idx = frag.optInt("index", i)
                        // Continuation fragments carry JSON null id/name, which
                        // optString would turn into the literal string "null" -
                        // guard with isNull or the real values get clobbered.
                        if (!frag.isNull("id")) {
                            frag.optString("id").takeIf { it.isNotEmpty() }?.let { toolIds[idx] = it }
                        }
                        frag.optJSONObject("function")?.let { fn ->
                            if (!fn.isNull("name")) {
                                fn.optString("name").takeIf { it.isNotEmpty() }?.let { toolNames[idx] = it }
                            }
                            if (!fn.isNull("arguments")) {
                                toolArgs.getOrPut(idx) { StringBuilder() }.append(fn.optString("arguments"))
                            }
                        }
                    }
                }
            }
            val msg = JSONObject().put("role", "assistant")
            if (content.isNotEmpty()) msg.put("content", content.toString())
            if (toolNames.isNotEmpty()) {
                val arr = JSONArray()
                for (idx in toolNames.keys.sorted()) {
                    arr.put(
                        JSONObject()
                            .put("id", toolIds[idx] ?: "call_$idx")
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", toolNames[idx])
                                    .put("arguments", toolArgs[idx]?.toString() ?: "{}"),
                            ),
                    )
                }
                msg.put("tool_calls", arr)
            }
            return msg
        }
    }

    private val sentenceEnd = Regex("[।?!]")

    /** One user turn -> Suhani's reply, running the tool loop to completion.
     * [onFirstSentence] fires the moment the streamed reply crosses its first
     * sentence boundary, so TTS can start while the model is still writing -
     * only for clean speech (never for tool JSON, [चुप], or tool rounds). */
    fun send(userText: String, onFirstSentence: ((String) -> Unit)? = null): String {
        trimHistory()
        messages.put(JSONObject().put("role", "user").put("content", userText))
        lastRunWrites = 0
        endRequested = false
        var sentenceFired = false
        repeat(MAX_TOOL_ROUNDS) {
            val streamBuf = StringBuilder()
            val onDelta: ((String) -> Unit)? = if (onFirstSentence == null) null else { piece ->
                streamBuf.append(piece)
                if (!sentenceFired) {
                    val buffered = streamBuf.toString()
                    val m = sentenceEnd.find(buffered)
                    if (m != null) {
                        val s1 = buffered.substring(0, m.range.last + 1).trim()
                        val clean = s1.isNotEmpty() && !s1.startsWith("{") && !s1.startsWith("[") &&
                            !s1.contains("[चुप]") && !s1.contains("[chup]")
                        if (clean) {
                            sentenceFired = true
                            onFirstSentence(s1.replace("[बंद]", "").replace("[band]", "").trim())
                        }
                    }
                }
            }
            var msg = call(onDelta)
            // org.json's optString turns a JSON null into the literal string
            // "null" - which then gets SPOKEN. Treat null as empty everywhere.
            fun contentOf(m: org.json.JSONObject): String {
                if (m.isNull("content")) return ""
                val c = m.optString("content")
                return if (c.trim() == "null") "" else c
            }
            val emptyReply = !msg.has("tool_calls") && contentOf(msg).isBlank()
            if (emptyReply) msg = call() // one deterministic retry - dead air is worse than latency
            messages.put(sanitize(msg))
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                return contentOf(msg)
            }
            val anyWrites = (0 until toolCalls.length()).any {
                toolCalls.getJSONObject(it).getJSONObject("function").getString("name") in writeTools
            }
            onToolPhase?.invoke(anyWrites)
            if (anyWrites && lastRunWrites == 0) {
                val gate = writeGate
                if (gate != null && !gate()) throw SpeculationCancelled()
            }
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val name = fn.getString("name")
                val args = try {
                    JSONObject(fn.optString("arguments").ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                if (name in writeTools) lastRunWrites++
                if (name == "end_conversation") endRequested = true
                val result = Tools.dispatch(ledger, name, args)
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", tc.optString("id")).put("content", result),
                )
            }
        }
        return FAILURE_LINE
    }

    /** Keep prefill flat over long sessions: drop the oldest whole turns
     * (user..until-next-user, so tool call/result pairs stay intact). */
    private fun trimHistory() {
        while (messages.length() > MAX_HISTORY_MESSAGES + 1) {
            var seenFirstUser = false
            var secondUser = -1
            for (i in 1 until messages.length()) {
                if (messages.getJSONObject(i).optString("role") == "user") {
                    if (seenFirstUser) { secondUser = i; break }
                    seenFirstUser = true
                }
            }
            if (secondUser <= 1) return // one giant turn; nothing safe to drop
            repeat(secondUser - 1) { messages.remove(1) }
        }
    }

    /** Drop the last user turn and everything after it (a cancelled speculative run). */
    fun rollbackLastTurn() {
        var lastUser = -1
        for (i in 0 until messages.length()) {
            if (messages.getJSONObject(i).optString("role") == "user") lastUser = i
        }
        if (lastUser >= 0) {
            while (messages.length() > lastUser) messages.remove(messages.length() - 1)
        }
    }

    private fun sanitize(msg: JSONObject): JSONObject {
        // Keep only the fields the API round-trips cleanly (model_dump(exclude_none)).
        val out = JSONObject().put("role", msg.optString("role", "assistant"))
        if (!msg.isNull("content")) out.put("content", msg.optString("content"))
        val tc = msg.optJSONArray("tool_calls")
        if (tc != null && tc.length() > 0) out.put("tool_calls", tc)
        return out
    }
}
