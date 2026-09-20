package com.example.voicerecorder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams raw PCM16 into a canonical 44-byte-header WAV file.
 *
 * The header is patched every few seconds (not just on close), so if the process is killed
 * mid-recording the file is still a valid WAV up to the last patch.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int = SAMPLE_RATE,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var bytesAtLastPatch = 0L

    init {
        raf.setLength(0)
        raf.write(header(0, sampleRate))
    }

    /** Appends PCM bytes. Called from the audio capture thread only. */
    fun write(pcm: ByteArray) {
        raf.write(pcm)
        dataBytes += pcm.size
        if (dataBytes - bytesAtLastPatch >= sampleRate * BYTES_PER_SAMPLE * PATCH_EVERY_SECONDS) {
            patchHeader()
        }
    }

    private fun patchHeader() {
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        raf.seek(raf.length())
        bytesAtLastPatch = dataBytes
    }

    override fun close() {
        try {
            patchHeader()
            raf.fd.sync()
        } finally {
            raf.close()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2 // mono, 16-bit
        private const val PATCH_EVERY_SECONDS = 5
        const val HEADER_SIZE = 44

        /** Builds a WAV header for mono PCM16 with [dataLen] bytes of audio. */
        fun header(dataLen: Long, sampleRate: Int): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray())
            b.putInt((36 + dataLen).toInt())
            b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray())
            b.putInt(16)                          // fmt chunk size
            b.putShort(1)                         // PCM
            b.putShort(1)                         // mono
            b.putInt(sampleRate)
            b.putInt(sampleRate * BYTES_PER_SAMPLE) // byte rate
            b.putShort(BYTES_PER_SAMPLE.toShort()) // block align
            b.putShort(16)                        // bits per sample
            b.put("data".toByteArray())
            b.putInt(dataLen.toInt())
            return b.array()
        }
    }
}
