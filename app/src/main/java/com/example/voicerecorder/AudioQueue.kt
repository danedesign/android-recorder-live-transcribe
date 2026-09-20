package com.example.voicerecorder

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Hand-off buffer between the capture thread and whichever recognizer is active.
 *
 * The capture thread must never block, so [offer] never waits: if a recognizer stalls for more
 * than [maxChunks] (~20 min at 100 ms per chunk) the oldest audio is dropped from *recognition*
 * (the WAV file is written separately and is unaffected).
 *
 * Because the queue outlives any single engine, audio that arrives while an engine is restarting
 * or being swapped simply waits here and is consumed by the next engine.
 */
class AudioQueue(private val maxChunks: Int = 12_000) {
    private val q = LinkedBlockingDeque<ByteArray>()

    fun offer(chunk: ByteArray) {
        q.addLast(chunk)
        while (q.size > maxChunks) q.pollFirst()
    }

    /** Waits up to [timeoutMs] for the next chunk; null on timeout. */
    fun poll(timeoutMs: Long): ByteArray? = q.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)

    /** Returns a chunk that a consumer took but could not deliver. */
    fun pushBack(chunk: ByteArray) = q.addFirst(chunk)

    fun isEmpty() = q.isEmpty()
}
