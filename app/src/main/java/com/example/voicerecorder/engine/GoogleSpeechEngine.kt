package com.example.voicerecorder.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.voicerecorder.AudioQueue
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * Primary engine: Android's [SpeechRecognizer], fed from OUR audio (never the recognizer's own mic).
 *
 * How audio gets in: each session creates a pipe ([ParcelFileDescriptor.createPipe]); the read end
 * goes to the recognizer via EXTRA_AUDIO_SOURCE, and a feeder thread writes our PCM into the write
 * end. Segmented-session mode asks the recognizer to keep going across pauses until we close the pipe.
 *
 * Reliability: recognizers vary. If a session ends by itself, errors out, never responds, or its
 * pipe breaks, we start a fresh session on a new pipe. Audio produced meanwhile waits in the shared
 * [AudioQueue], so the restart loses no *recorded* audio (the WAV never depended on this engine) and
 * at most ~2 s of *recognition* input (whatever was already sitting in the dead pipe).
 * After repeated quick failures we try the network recognizer once (if we began on-device), then
 * report failure so the supervisor can switch to Vosk.
 *
 * Threading: SpeechRecognizer must be used from the main thread, so all session logic runs there;
 * only the feeder thread touches the pipe.
 */
class GoogleSpeechEngine(
    private val context: Context,
    private val queue: AudioQueue,
    private val language: String,
    private val listener: EngineListener,
) : TranscriptionEngine {

    private val main = Handler(Looper.getMainLooper())
    private val policy = FailurePolicy()

    // Main-thread state
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    private var sessionStartMs = 0L
    private var gotCallback = false
    private var useOnDevice = onDeviceAvailable(context)
    private var triedOnline = false
    @Volatile private var finished = false
    private var stopCallback: (() -> Unit)? = null

    // Shared with the feeder thread
    @Volatile private var sessionId = 0
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopping = false
    @Volatile private var drain = true
    private var feeder: Thread? = null

    override val label: String get() = if (useOnDevice) "Google (on-device)" else "Google (online)"

    override fun start() {
        main.post { startSession() }
        feeder = Thread(::feedLoop, "google-feeder").also { it.start() }
    }

    // ---- session lifecycle (main thread) -------------------------------------------------------

    private fun startSession() {
        if (stopping || finished) return
        teardownSession()
        val id = ++sessionId
        gotCallback = false
        sessionStartMs = SystemClock.elapsedRealtime()

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            fail("pipe: ${e.message}")
            return
        }
        readSide = pipe[0]
        val rec = try {
            if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            pipe.forEach { it.close() }
            fail("create recognizer: ${e.message}")
            return
        }
        rec.setRecognitionListener(SessionListener(id))
        recognizer = rec

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            // Feed our own PCM instead of letting the recognizer open the mic.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // Segmented session: keep recognizing across pauses until the audio source closes.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            onSessionEnded(id, "startListening threw: ${e.message}")
            return
        }
        listener.onStatus("Google session #$id started ($label)")

        // Watchdog: a recognizer that ignores our extras or never answers is treated as a failure.
        main.postDelayed({
            if (id == sessionId && !gotCallback) onSessionEnded(id, "no response from recognizer")
        }, NO_RESPONSE_MS)
    }

    /**
     * The session died on its own (not because we're stopping). Restart on a fresh pipe, or give up
     * after repeated quick failures. [fatal] skips the counting (e.g. language unsupported).
     */
    private fun onSessionEnded(id: Int, reason: String, fatal: Boolean = false, backoffMs: Long = 300) {
        if (id != sessionId || stopping || finished) return
        sessionId++ // invalidate any late callbacks from the dead session
        listener.onSessionBoundary()
        val lifetime = SystemClock.elapsedRealtime() - sessionStartMs
        teardownSession()

        if (fatal || policy.onSessionEnded(lifetime)) {
            if (useOnDevice && !triedOnline) {
                // On-device recognizer is unreliable here: try the network one before abandoning Google.
                triedOnline = true
                useOnDevice = false
                policy.reset()
                listener.onStatus("On-device recognizer failed ($reason); trying online recognizer")
                listener.onRestart()
                main.postDelayed({ startSession() }, backoffMs)
            } else {
                fail(reason)
            }
            return
        }
        listener.onStatus("Recognizer restarted: $reason (after ${lifetime / 1000}s)")
        listener.onRestart()
        main.postDelayed({ startSession() }, backoffMs)
    }

    private fun fail(reason: String) {
        if (finished) return
        // Hand control to the supervisor, which stops us with drain=false and starts Vosk.
        listener.onFailure(reason)
    }

    private fun teardownSession() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        closeQuietly(readSide); readSide = null
        closeQuietly(out as? Closeable); out = null
    }

    private fun closeQuietly(c: Closeable?) {
        try { c?.close() } catch (_: IOException) { }
    }

    // ---- feeding audio (feeder thread) ---------------------------------------------------------

    private fun feedLoop() {
        var pending: ByteArray? = null
        while (true) {
            if (finished) break // engine shut down (e.g. flush timed out): stop feeding
            val chunk = pending ?: queue.poll(100)
            if (chunk == null) {
                if (stopping) break // queue drained
                continue
            }
            if (stopping && !drain) { pending = chunk; break }
            pending = chunk
            val session = sessionId
            val o = out
            if (o == null) { // between sessions: hold the chunk, audio keeps queueing behind it
                sleep(20)
                continue
            }
            try {
                o.write(chunk)
                pending = null
            } catch (e: IOException) {
                // Recognizer closed its end. Keep the chunk for the next session and restart.
                if (out === o) out = null // don't clobber a newer session's stream
                main.post { onSessionEnded(session, "audio pipe closed") }
                sleep(50)
            }
        }
        pending?.let { queue.pushBack(it) }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    // ---- stopping ------------------------------------------------------------------------------

    override fun stop(drain: Boolean, onStopped: () -> Unit) {
        this.drain = drain
        stopping = true
        stopCallback = onStopped
        Thread({
            feeder?.join(if (drain) 8_000 else 1_500)
            main.post { closeInputAndAwaitFlush(drain) }
        }, "google-stop").start()
    }

    /** Closing the pipe = end of audio: a segmented session then emits its last segment and ends. */
    private fun closeInputAndAwaitFlush(drain: Boolean) {
        val awaiting = drain && recognizer != null
        closeQuietly(out as? Closeable); out = null
        if (awaiting) main.postDelayed(::finish, FLUSH_WAIT_MS) else finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        listener.onSessionBoundary()
        teardownSession()
        stopCallback?.invoke()
        stopCallback = null
    }

    // ---- recognizer callbacks (main thread) ----------------------------------------------------

    private inner class SessionListener(private val id: Int) : RecognitionListener {
        private fun live() = id == sessionId

        private fun text(b: Bundle?): String =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        override fun onReadyForSpeech(params: Bundle?) {
            if (!live()) return
            gotCallback = true
            // The recognizer now holds its own duplicate of the read end; drop ours so a
            // recognizer-side close shows up as EPIPE on our writes.
            closeQuietly(readSide); readSide = null
        }

        override fun onBeginningOfSpeech() { if (live()) gotCallback = true }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            gotCallback = true
            val t = text(partialResults)
            if (t.isNotBlank()) listener.onPartial(t)
        }

        /** A finished segment (silence ends a segment, NOT the session). */
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!live()) return
            gotCallback = true
            listener.onFinal(text(segmentResults))
        }

        override fun onEndOfSegmentedSession() {
            if (!live()) return
            if (stopping) { main.post(::finish); return } // normal end after we closed the pipe
            onSessionEnded(id, "session ended by recognizer")
        }

        /** Only arrives when the recognizer ignored segmented mode and behaved like a one-shot session. */
        override fun onResults(results: Bundle?) {
            if (!live()) return
            gotCallback = true
            listener.onFinal(text(results))
            if (stopping) { main.post(::finish); return }
            onSessionEnded(id, "one-shot results (segmented mode ignored?)")
        }

        override fun onError(error: Int) {
            if (!live()) return
            gotCallback = true
            if (stopping) { main.post(::finish); return }
            val fatal = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_000L else 300L
            onSessionEnded(id, "error ${errorName(error)}", fatal, backoff)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {} // fires per segment in segmented mode; not a session end
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val NO_RESPONSE_MS = 8_000L
        private const val FLUSH_WAIT_MS = 4_000L

        private fun onDeviceAvailable(context: Context) =
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** True if any Google/system recognizer can be used at all. */
        fun isAvailable(context: Context): Boolean =
            onDeviceAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            else -> "code $code"
        }
    }
}
