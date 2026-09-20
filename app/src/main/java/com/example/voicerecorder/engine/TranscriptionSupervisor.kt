package com.example.voicerecorder.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.voicerecorder.AudioQueue
import com.example.voicerecorder.TranscriptBuilder

/**
 * Picks the recognizer engine, feeds it audio, and swaps to the fallback when the primary gives up.
 *
 *   start:  Google (on-device preferred)  ->  [repeated failures]  ->  Vosk (offline)
 *
 * The [AudioQueue] is owned here and outlives every engine, so a swap never drops audio.
 */
class TranscriptionSupervisor(
    private val context: Context,
    private val language: String,
    private val forceVosk: Boolean,
    private val transcript: TranscriptBuilder,
    /** Milliseconds since recording started; used to timestamp transcript updates. */
    private val elapsedMs: () -> Long,
    private val onChange: () -> Unit,
) : EngineListener {

    private val main = Handler(Looper.getMainLooper())
    private val queue = AudioQueue()
    @Volatile private var engine: TranscriptionEngine? = null

    @Volatile var status: String = ""
        private set
    @Volatile var restarts: Int = 0
        private set
    val label: String get() = engine?.label ?: "No engine"

    fun start() {
        val first: TranscriptionEngine? = when {
            forceVosk -> createVosk("Vosk forced in settings")
            GoogleSpeechEngine.isAvailable(context) -> GoogleSpeechEngine(context, queue, language, this)
            else -> createVosk("No Google speech recognizer on this device")
        }
        engine = first
        first?.start()
        onChange()
    }

    /** Called from the capture thread for every chunk; never blocks. */
    fun feed(chunk: ByteArray) = queue.offer(chunk)

    /** Flushes the active engine, then calls [done] on the main thread. */
    fun stop(done: () -> Unit) {
        val e = engine
        if (e == null) { main.post(done); return }
        var called = false
        val once = { if (!called) { called = true; done() } }
        e.stop(drain = true) { once() }
        // Don't let a stuck engine (e.g. a slow model download) hold the Stop button hostage.
        main.postDelayed({
            if (!called) {
                status = "Stopped before the recognizer finished flushing"
                e.stop(drain = false) { }
                once()
            }
        }, STOP_TIMEOUT_MS)
    }

    // ---- EngineListener ------------------------------------------------------------------------

    override fun onPartial(text: String) {
        transcript.setPartial(text, elapsedMs())
        onChange()
    }

    override fun onFinal(text: String) {
        transcript.commit(text, elapsedMs())
        onChange()
    }

    override fun onSessionBoundary() {
        transcript.commitPendingPartial()
        onChange()
    }

    override fun onStatus(msg: String) {
        status = msg
        onChange()
    }

    override fun onRestart() {
        restarts++
        onChange()
    }

    /** The active engine gave up: fall back to Vosk (or, if Vosk itself failed, run without transcription). */
    @Synchronized
    override fun onFailure(reason: String) {
        val failed = engine ?: return
        val fallback = if (failed is GoogleSpeechEngine) createVosk("Google failed: $reason") else null
        if (fallback == null) {
            status = "No transcription engine available ($reason). Audio is still being recorded."
            engine = null
            failed.stop(drain = false) { }
            onChange()
            return
        }
        status = "Switching to Vosk: $reason"
        engine = fallback
        // Start the replacement only once the old engine has handed unconsumed audio back to the queue.
        failed.stop(drain = false) { fallback.start(); onChange() }
        onChange()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 20_000L
    }

    private fun createVosk(reason: String): TranscriptionEngine {
        status = reason
        return VoskEngine(context, queue, language, this)
    }
}
