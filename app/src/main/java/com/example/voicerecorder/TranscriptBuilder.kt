package com.example.voicerecorder

/**
 * Accumulates the live transcript from any engine: committed (final) segments plus the current
 * in-flight partial. Also remembers *when* the text last changed, which the pause self-test uses.
 * Thread-safe: engines call in from their own threads.
 */
class TranscriptBuilder {
    private val finals = ArrayList<String>()
    private var partial = ""
    private val updateTimes = ArrayList<Long>()

    @Synchronized
    fun setPartial(text: String, atMs: Long) {
        if (text == partial) return
        partial = text
        if (text.isNotBlank()) updateTimes += atMs
    }

    /** Commits a final segment and clears the partial it supersedes. */
    @Synchronized
    fun commit(text: String, atMs: Long) {
        partial = ""
        val t = text.trim()
        if (t.isEmpty()) return
        finals += t
        updateTimes += atMs
    }

    /** Keeps words that were only ever seen as a partial (e.g. when a session dies or stops). */
    @Synchronized
    fun commitPendingPartial() {
        val t = partial.trim()
        partial = ""
        if (t.isNotEmpty() && finals.lastOrNull() != t) finals += t
    }

    @Synchronized
    fun snapshot(): Pair<List<String>, String> = finals.toList() to partial

    /** The text saved to the .txt file: one segment per line. */
    @Synchronized
    fun fullText(): String = (finals + partial.trim()).filter { it.isNotEmpty() }.joinToString("\n")

    @Synchronized
    fun updateTimesMs(): List<Long> = updateTimes.toList()
}
