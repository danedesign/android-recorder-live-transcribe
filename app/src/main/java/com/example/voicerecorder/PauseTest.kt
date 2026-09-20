package com.example.voicerecorder

/**
 * The "no cutoff on pauses" self-test.
 *
 * Timeline (60 s): speak 0-15 s, stay silent 15-40 s (25 s of silence), speak again 40-60 s.
 * PASS = the transcript changed both before the silence and after it, and the session survived
 * the full 60 s. If the recognizer had quit on the pause, nothing would appear after 40 s.
 */
object PauseTest {
    const val DURATION_MS = 60_000L
    const val SPEAK_1_END_MS = 15_000L
    const val SPEAK_2_START_MS = 40_000L

    data class Result(val passed: Boolean, val summary: String)

    /** What the user should be doing at [elapsedMs]. */
    fun instruction(elapsedMs: Long): String = when {
        elapsedMs < SPEAK_1_END_MS -> "SPEAK now (e.g. count out loud or read something)"
        elapsedMs < SPEAK_2_START_MS -> "STAY SILENT - do not say anything"
        else -> "SPEAK again"
    }

    fun evaluate(updateTimesMs: List<Long>, recordedMs: Long, restarts: Int, engine: String): Result {
        val before = updateTimesMs.count { it < SPEAK_1_END_MS + 5_000 } // first speaking phase (+5 s recognizer latency)
        val after = updateTimesMs.count { it >= SPEAK_2_START_MS }
        val ranFull = recordedMs >= DURATION_MS - 2_000
        val passed = ranFull && before > 0 && after > 0
        val why = when {
            !ranFull -> "FAIL: recording stopped early (${recordedMs / 1000}s of 60s)."
            before == 0 -> "INCONCLUSIVE: nothing was transcribed in the first phase - did you speak?"
            after == 0 -> "FAIL: transcript stopped after the silence (recognizer cut off on the pause)."
            else -> "PASS: transcript continued after the 25 s silence."
        }
        return Result(passed, "$why  [engine=$engine, updates before=$before, after=$after, restarts=$restarts]")
    }
}
