package com.example.voicerecorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Owns the microphone. A single [AudioRecord] (16 kHz, mono, PCM16) is read on a dedicated
 * thread and every ~100 ms chunk is handed to [onChunk], which fans it out to the file writer
 * and the recognizer queue. Nothing else in the app ever opens the mic.
 *
 * There is deliberately no silence detection here: capture only ends when [stop] is called.
 */
class AudioCapture(
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Returns false (and reports via [onError]) if the mic could not be opened. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI before the service starts.
    fun start(): Boolean {
        val rate = WavWriter.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, rate * 2) // >= 1 second of headroom in the driver
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (e: Exception) {
            onError("Could not open microphone: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("Microphone unavailable (in use by another app?)")
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ readLoop(rec) }, "audio-capture").also { it.start() }
        return true
    }

    private fun readLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                // Copy: the chunk is shared with other threads and `buf` is reused.
                onChunk(buf.copyOf(n))
            } else if (n < 0) {
                if (running) onError("Audio read error ($n)")
                break
            }
        }
    }

    /** Stops capture and waits for the read thread so no chunk is delivered after return. */
    fun stop() {
        running = false
        try { record?.stop() } catch (_: IllegalStateException) { }
        thread?.join(2000)
        record?.release()
        record = null
        thread = null
    }

    private companion object {
        const val CHUNK_BYTES = 3200 // 100 ms of 16 kHz mono PCM16
    }
}
