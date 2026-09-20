package com.example.voicerecorder.data

import android.content.Context
import com.example.voicerecorder.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved recording: `<base>.wav` plus `<base>.txt` side by side. */
data class Recording(val base: String, val wav: File, val txt: File) {
    /** 16 kHz mono PCM16 = 32,000 bytes per second, after the 44-byte header. */
    val durationMs: Long get() = ((wav.length() - WavWriter.HEADER_SIZE).coerceAtLeast(0) / 32L)
    val transcript: String get() = if (txt.exists()) txt.readText() else ""
    val timestamp: Long get() = wav.lastModified()
}

class RecordingRepository(context: Context) {
    /** App-specific external storage needs no permission; falls back to internal storage. */
    val dir: File = (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings"))
        .also { it.mkdirs() }

    /** Creates a fresh timestamped base name, e.g. `Recording_2026-09-20_14-30-05`. */
    fun newBaseName(): String =
        "Recording_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun wavFile(base: String) = File(dir, "$base.wav")
    fun txtFile(base: String) = File(dir, "$base.txt")

    /** Newest first. [exclude] hides the recording currently being written. */
    fun list(exclude: String? = null): List<Recording> =
        dir.listFiles { f -> f.extension == "wav" }.orEmpty()
            .map { Recording(it.nameWithoutExtension, it, txtFile(it.nameWithoutExtension)) }
            .filter { it.base != exclude && it.wav.length() > WavWriter.HEADER_SIZE }
            .sortedByDescending { it.base }

    fun delete(r: Recording) {
        r.wav.delete()
        r.txt.delete()
    }
}
