package com.example.voicerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.voicerecorder.data.RecordingRepository
import com.example.voicerecorder.engine.TranscriptionSupervisor
import com.example.voicerecorder.engine.VoskModelManager

/**
 * Foreground service (type=microphone) that keeps recording with the screen off.
 *
 * Data flow:  AudioCapture (one mic stream) --> WavWriter (file)
 *                                          \--> AudioQueue --> active recognizer engine
 *
 * Recording ends ONLY on ACTION_STOP (the Stop button / notification action), or, in the
 * self-test, after 60 s. It never ends because of silence.
 */
class RecorderService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: RecordingRepository
    private var capture: AudioCapture? = null
    private var writer: WavWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var base: String? = null
    private var startedAt = 0L
    private var lastAutosave = 0L
    private var selfTest = false
    private var finishing = false

    private var supervisor: TranscriptionSupervisor? = null
    private var extraStatus = "" // service-level problems (disk, mic) shown when there's no engine status
    private val restarts get() = supervisor?.restarts ?: 0

    private val transcript = TranscriptBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = RecordingRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getBooleanExtra(EXTRA_SELF_TEST, false))
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY // a killed recording cannot be resumed; the WAV stays valid up to the last patch
    }

    // ---- start -------------------------------------------------------------------------------

    private fun startRecording(selfTest: Boolean) {
        if (RecorderState.ui.value.recording) return
        this.selfTest = selfTest
        finishing = false
        extraStatus = ""

        createChannel()
        try {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            fail("Could not start foreground service: ${e.message}")
            return
        }

        val name = repo.newBaseName()
        base = name
        writer = try {
            WavWriter(repo.wavFile(name))
        } catch (e: Exception) {
            fail("Could not create file: ${e.message}")
            return
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicerecorder:recording")
            .apply { acquire(MAX_WAKELOCK_MS) }

        startedAt = SystemClock.elapsedRealtime()
        onTranscriptionStart()

        val cap = AudioCapture(onChunk = ::onAudioChunk, onError = { msg -> main.post { fail(msg, save = true) } })
        capture = cap
        if (!cap.start()) return // AudioCapture already reported via onError -> fail()

        lastAutosave = startedAt
        RecorderState.update {
            UiState(
                recording = true, engineLabel = "Starting...", selfTest = selfTest,
                currentBase = name, savedCount = it.savedCount,
            )
        }
        main.post(ticker)
    }

    /** Runs on the capture thread for every 100 ms chunk: file first, then recognizer. */
    private fun onAudioChunk(chunk: ByteArray) {
        try {
            writer?.write(chunk)
        } catch (e: Exception) {
            main.post { fail("Disk write failed: ${e.message}", save = true) }
            return
        }
        onTranscriptionAudio(chunk)
    }

    // ---- transcription hooks (filled in by the engine layer) -----------------------------------

    private fun onTranscriptionStart() {
        val settings = AppSettings(this)
        supervisor = TranscriptionSupervisor(
            context = this,
            language = settings.effectiveLanguage(),
            forceVosk = settings.forceVosk,
            transcript = transcript,
            elapsedMs = { SystemClock.elapsedRealtime() - startedAt },
            onChange = { if (!finishing) publish() },
        ).also { it.start() }
    }

    private fun onTranscriptionAudio(chunk: ByteArray) {
        supervisor?.feed(chunk)
    }

    private fun onTranscriptionStop(done: () -> Unit) {
        val s = supervisor
        if (s == null) done() else s.stop(done)
    }

    private fun engineLabel(): String = supervisor?.label ?: "None"

    // ---- periodic UI/autosave tick -------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (!RecorderState.ui.value.recording || finishing) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            publish(elapsed)
            // Autosave the transcript so a crash/kill loses at most ~15 s of text.
            if (SystemClock.elapsedRealtime() - lastAutosave > AUTOSAVE_MS) {
                lastAutosave = SystemClock.elapsedRealtime()
                base?.let { runCatching { repo.txtFile(it).writeText(transcript.fullText()) } }
            }
            if (selfTest && elapsed >= PauseTest.DURATION_MS) {
                finishRecording()
                return
            }
            main.postDelayed(this, 500)
        }
    }

    private fun publish(elapsed: Long = SystemClock.elapsedRealtime() - startedAt) {
        val (finals, partial) = transcript.snapshot()
        RecorderState.update {
            it.copy(
                elapsedMs = elapsed, committed = finals, partial = partial,
                engineLabel = engineLabel(),
                status = extraStatus.ifEmpty { supervisor?.status.orEmpty() },
                restarts = restarts,
            )
        }
    }

    // ---- stop ----------------------------------------------------------------------------------

    private fun finishRecording() {
        if (!RecorderState.ui.value.recording || finishing) return
        finishing = true
        main.removeCallbacks(ticker)
        val recordedMs = SystemClock.elapsedRealtime() - startedAt

        // 1. Stop the mic and close the WAV immediately: the audio is safe before we wait on the recognizer.
        capture?.stop()
        capture = null
        runCatching { writer?.close() }
        writer = null

        // 2. Let the recognizer flush what it has, then save the transcript and shut down.
        onTranscriptionStop {
            transcript.commitPendingPartial()
            val name = base
            if (name != null) runCatching { repo.txtFile(name).writeText(transcript.fullText()) }
            val result = if (selfTest) {
                PauseTest.evaluate(transcript.updateTimesMs(), recordedMs, restarts, engineLabel())
            } else null
            val (finals, _) = transcript.snapshot()
            RecorderState.update {
                it.copy(
                    recording = false, committed = finals, partial = "", currentBase = null,
                    status = "Saved ${name ?: ""}", testResult = result ?: it.testResult,
                    savedCount = it.savedCount + 1, selfTest = false, elapsedMs = recordedMs,
                )
            }
            supervisor = null
            releaseAndStop()
        }
    }

    /** Abort path: keep whatever audio/text exists, report [msg], and shut down. */
    private fun fail(msg: String, save: Boolean = false) {
        if (finishing) return
        if (save && RecorderState.ui.value.recording) {
            extraStatus = msg
            finishRecording()
            RecorderState.update { it.copy(status = msg) }
            return
        }
        RecorderState.update { it.copy(recording = false, status = msg, selfTest = false, currentBase = null) }
        capture?.stop()
        capture = null
        runCatching { writer?.close() }
        writer = null
        supervisor?.stop { } // don't leave a recognizer running with no audio
        supervisor = null
        releaseAndStop()
    }

    private fun releaseAndStop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        capture?.stop()
        runCatching { writer?.close() }
        wakeLock?.let { if (it.isHeld) it.release() }
        VoskModelManager.releaseAll() // free the ~100 MB models between recordings
        if (RecorderState.ui.value.recording) {
            RecorderState.update { it.copy(recording = false, status = "Recording service was stopped", currentBase = null) }
        }
        super.onDestroy()
    }

    // ---- notification --------------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording")
            .setContentText("Live transcription is running. Tap Stop to finish.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.voicerecorder.START"
        const val ACTION_STOP = "com.example.voicerecorder.STOP"
        const val EXTRA_SELF_TEST = "self_test"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val AUTOSAVE_MS = 15_000L
        private const val MAX_WAKELOCK_MS = 12L * 60 * 60 * 1000 // safety net: 12 h

        fun start(context: Context, selfTest: Boolean = false) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_SELF_TEST, selfTest),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
