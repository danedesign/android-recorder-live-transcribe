package com.example.voicerecorder.engine

/** Events an engine reports to the supervisor. May arrive on any thread. */
interface EngineListener {
    /** In-flight hypothesis; replaces the previous partial. */
    fun onPartial(text: String)

    /** A finished segment; supersedes the current partial. */
    fun onFinal(text: String)

    /** The session is being torn down/restarted: keep any words only seen as a partial. */
    fun onSessionBoundary()

    /** Debug/status line for the UI. */
    fun onStatus(msg: String)

    /** The engine restarted its session without losing audio (counted for the debug line). */
    fun onRestart()

    /** The engine has given up; the supervisor should fall back to another engine. */
    fun onFailure(reason: String)
}

/**
 * A speech recognizer that consumes audio from a shared [com.example.voicerecorder.AudioQueue].
 * Engines never touch the microphone.
 */
interface TranscriptionEngine {
    /** Human-readable name shown on the debug line. */
    val label: String

    fun start()

    /**
     * Stops the engine. With [drain] = true it first consumes everything left in the queue and
     * lets the recognizer flush; with false it stops at once and returns unconsumed audio to the
     * queue (used when handing over to another engine). [onStopped] runs on the main thread.
     */
    fun stop(drain: Boolean, onStopped: () -> Unit)
}

/**
 * Decides when a recognizer session that keeps ending on its own should be abandoned.
 *
 * A session that ends unexpectedly after [healthyMs] or more counts as normal (some recognizers
 * cap session length); one that dies sooner counts as a failure. [maxConsecutive] failures in a
 * row -> give up.
 */
class FailurePolicy(private val maxConsecutive: Int = 3, private val healthyMs: Long = 30_000) {
    var consecutive = 0
        private set

    /** Records an unexpected session end; returns true if the engine should be abandoned. */
    fun onSessionEnded(lifetimeMs: Long): Boolean {
        if (lifetimeMs >= healthyMs) consecutive = 0 else consecutive++
        return consecutive >= maxConsecutive
    }

    fun reset() {
        consecutive = 0
    }
}
