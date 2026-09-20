package com.example.voicerecorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Everything the UI needs to know about the current recording. */
data class UiState(
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val committed: List<String> = emptyList(),
    val partial: String = "",
    /** Which engine is active, e.g. "Google (on-device)" or "Vosk (offline)". */
    val engineLabel: String = "Idle",
    /** Latest status/debug message (restarts, model download, errors). */
    val status: String = "",
    val restarts: Int = 0,
    val selfTest: Boolean = false,
    val testResult: PauseTest.Result? = null,
    /** Base name of the file being written, so the list screen can hide it. */
    val currentBase: String? = null,
    /** Bumped every time a recording is saved so the list screen refreshes. */
    val savedCount: Int = 0,
)

/**
 * Process-wide state holder. The service writes, the UI reads. A singleton is the simplest
 * thing that works for a single-service app; the service is the only writer.
 */
object RecorderState {
    val ui = MutableStateFlow(UiState())

    fun update(block: (UiState) -> UiState) = ui.update(block)
}
