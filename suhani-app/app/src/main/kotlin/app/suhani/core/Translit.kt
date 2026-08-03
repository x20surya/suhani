package app.suhani.core

/**
 * Verbatim port of silai/translit.py - the coarse phonetic key used for fuzzy
 * client-name matching. Deliberately lossy: same-name variants must collide.
 * Includes a faithful port of Python difflib.SequenceMatcher.ratio(), because
 * the 0.72/0.8 thresholds in the ledger were tuned against ITS values.
 */
object Translit {

    private val CONSONANTS = mapOf(
        "क" to "k", "ख" to "kh", "ग" to "g", "घ" to "gh", "ङ" to "n",
        "च" to "ch", "छ" to "ch", "ज" to "j", "झ" to "jh", "ञ" to "n",
        "ट" to "t", "ठ" to "th", "ड" to "d", "ढ" to "dh", "ण" to "n",
        "त" to "t", "थ" to "th", "द" to "d", "ध" to "dh", "न" to "n",
        "प" to "p", "फ" to "ph", "ब" to "b", "भ" to "bh", "म" to "m",
        "य" to "y", "र" to "r", "ल" to "l", "व" to "v", "श" to "sh",
        "ष" to "sh", "स" to "s", "ह" to "h",
        "क़" to "k", "ख़" to "kh", "ग़" to "g", "ज़" to "z", "ड़" to "r",
        "ढ़" to "rh", "फ़" to "f", "य़" to "y",
    )

    private val VOWELS = mapOf(
        "अ" to "a", "आ" to "a", "इ" to "i", "ई" to "i", "उ" to "u", "ऊ" to "u",
        "ऋ" to "ri", "ए" to "e", "ऐ" to "ai", "ओ" to "o", "औ" to "au",
        "ा" to "a", "ि" to "i", "ी" to "i", "ु" to "u", "ू" to "u",
        "ृ" to "ri", "े" to "e", "ै" to "ai", "ो" to "o", "ौ" to "au",
        "ं" to "n", "ँ" to "n", "ः" to "",
    )

    private const val VIRAMA = "्"
    private const val NUKTA = "़"

    private fun devanagariToLatin(text: String): String {
        val out = StringBuilder()
        val chars = text.map { it.toString() }
        for (i in chars.indices) {
            val ch = chars[i]
            if (ch == VIRAMA || ch == NUKTA) continue
            val cons = CONSONANTS[ch]
            if (cons != null) {
                out.append(cons)
                val nxt = if (i + 1 < chars.size) chars[i + 1] else ""
                if (nxt !in VOWELS && nxt != VIRAMA) out.append("a")
            } else {
                out.append(VOWELS[ch] ?: ch)
            }
        }
        return out.toString()
    }

    fun normalize(text: String): String {
        var latin = devanagariToLatin(text.trim()).lowercase()
        latin = latin.replace(Regex("[^a-z]"), "")
        latin = latin.replace("h", "")
        // Same order as Python: vowel folds BEFORE double-collapse.
        latin = latin.replace(Regex("ee|ea"), "i")
        latin = latin.replace(Regex("oo"), "u")
        latin = latin.replace(Regex("(.)\\1+"), "$1")
        latin = latin.replace(Regex("a$"), "")
        return latin
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return sequenceMatcherRatio(a, b)
    }

    /**
     * Python difflib.SequenceMatcher.ratio() (no junk; autojunk irrelevant at
     * name lengths): 2*M / (len(a)+len(b)) where M sums the recursive
     * longest-matching-block decomposition.
     */
    private fun sequenceMatcherRatio(a: String, b: String): Double {
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, ch -> b2j.getOrPut(ch) { mutableListOf() }.add(j) }

        fun longestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
            var besti = alo
            var bestj = blo
            var bestSize = 0
            var j2len = HashMap<Int, Int>()
            for (i in alo until ahi) {
                val newJ2len = HashMap<Int, Int>()
                for (j in b2j[a[i]] ?: emptyList()) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newJ2len[j] = k
                    if (k > bestSize) {
                        besti = i - k + 1; bestj = j - k + 1; bestSize = k
                    }
                }
                j2len = newJ2len
            }
            return Triple(besti, bestj, bestSize)
        }

        var matches = 0
        val queue = ArrayDeque(listOf(intArrayOf(0, a.length, 0, b.length)))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo) = queue.first().let { Triple(it[0], it[1], it[2]) }
            val bhi = queue.removeFirst()[3]
            val (i, j, k) = longestMatch(alo, ahi, blo, bhi)
            if (k > 0) {
                matches += k
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return 2.0 * matches / (a.length + b.length)
    }
}
