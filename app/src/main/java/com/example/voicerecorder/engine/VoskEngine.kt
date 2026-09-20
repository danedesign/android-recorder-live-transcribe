package com.example.voicerecorder.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.voicerecorder.AudioQueue
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Fallback engine: Vosk offline recognition on the same PCM chunks.
 *
 * Vosk has no "session" that can time out: it just consumes audio, so pauses can never end it.
 * (It does emit a final result at each pause, then carries on.) The model is English-only.
 *
 * Runs on its own thread; the first thing it does may be downloading the model, during which
 * audio simply accumulates in the [AudioQueue] and is transcribed afterwards.
 */
class VoskEngine(
    private val context: Context,
    private val queue: AudioQueue,
    private val language: String,
    private val listener: EngineListener,
) : TranscriptionEngine {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var drain = true
    @Volatile private var startRequested = false
    @Volatile private var done = false // worker thread has finished
    private var thread: Thread? = null
    private var onStopped: (() -> Unit)? = null

    override val label: String = "Vosk (offline)"

    @Synchronized
    override fun start() {
        if (startRequested || stopping) return
        startRequested = true
        thread = Thread(::run, "vosk").also { it.start() }
    }

    @Synchronized
    override fun stop(drain: Boolean, onStopped: () -> Unit) {
        this.drain = drain
        this.onStopped = onStopped
        stopping = true
        // If the worker never started or already ended (e.g. failed), nothing will call back later.
        if (thread == null || done) main.post(onStopped)
    }

    private fun run() {
        var recognizer: Recognizer? = null
        try {
            if (!language.startsWith("en", ignoreCase = true)) {
                listener.onStatus("Vosk fallback only has an English model (language is $language)")
            }
            val mgr = VoskModelManager.get(context)
            if (!mgr.isInstalled()) listener.onStatus("Downloading offline model (~40 MB)...")
            val model = mgr.loadModel { pct -> listener.onStatus("Downloading offline model... $pct%") }
            recognizer = Recognizer(model, 16_000f)
            listener.onStatus("Vosk running")

            while (true) {
                val chunk = queue.poll(100)
                if (chunk == null) {
                    if (stopping) break // queue drained
                    continue
                }
                if (stopping && !drain) { queue.pushBack(chunk); break }
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    listener.onFinal(JSONObject(recognizer.result).optString("text"))
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial")
                    if (p.isNotBlank()) listener.onPartial(p)
                }
            }
            if (drain) listener.onFinal(JSONObject(recognizer.finalResult).optString("text"))
        } catch (t: Throwable) {
            listener.onFailure("Vosk error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { recognizer?.close() }
            val cb = synchronized(this) { done = true; onStopped }
            if (cb != null) main.post(cb)
        }
    }
}
