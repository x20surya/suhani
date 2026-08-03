package app.suhani.voice

/**
 * Layer 2 of the 5a turn-taking stack, exactly as tuned in the S2 bake-off
 * (silai/s2_bakeoff.py): Hindi is verb-final, and Saaras auto-punctuates even
 * dangling fragments, so completeness = "not connective-ending AND verb-final"
 * judged on the transcript with trailing punctuation stripped.
 *
 * Winning waits: 500ms after a complete-looking end, 1800ms after an
 * incomplete-looking one; layer 3 (speculative cancel) absorbs the rest.
 */
object TurnPolicy {

    const val WAIT_COMPLETE_MS = 500L
    const val WAIT_INCOMPLETE_MS = 1800L

    private val CONNECTIVE_END = Regex(
        "(और|का|की|के|से|तक|तो|में|पर|अब|मतलब|यानी|मगर|क्योंकि|शायद|पहला|एक|सा|रो|ताईं|,)\\s*$",
    )

    private val VERB_END = Regex(
        "(दो|दें|दे|दिया|दिए|दी|दियो|गया|गए|गई|ग्यो|गी|गे|है|हैं|था|थे|थी|हूँ|हो" +
            "|करो|कर|करना|रखो|रखना|देना|लेना|लो|बनाओ|बताओ|सुनाओ|चलाओ|जाओ|जाए|चाहिए" +
            "|िए|िये|ेगा|ेगी|ेंगे|ूँगा|ूँगी|नहीं)\\s*$",
    )

    fun looksComplete(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val base = t.replace(Regex("[।?!.,…\\s]+$"), "")
        if (base.isEmpty() || CONNECTIVE_END.containsMatchIn(base)) return false
        return VERB_END.containsMatchIn(base)
    }

    fun waitFor(transcript: String): Long =
        if (looksComplete(transcript)) WAIT_COMPLETE_MS else WAIT_INCOMPLETE_MS
}
