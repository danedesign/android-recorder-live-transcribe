package com.example.voicerecorder.engine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.vosk.Model

/** Download/install state of the offline model, shown in Settings. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val percent: Int) : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Downloads (once) and loads the small English Vosk model (~40 MB zip).
 *
 * The model is fetched on first use rather than bundled to keep the APK small; Settings also has
 * a button to fetch it ahead of time so the fallback works offline. Stored in app-private storage.
 */
class VoskModelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "vosk/$MODEL_NAME")
    private var model: Model? = null

    val state = MutableStateFlow<ModelState>(if (isInstalled()) ModelState.Ready else ModelState.NotInstalled)

    fun isInstalled() = File(modelDir, "conf/model.conf").exists()

    /** Blocking: downloads if needed, then loads. Call from a background thread. */
    @Synchronized
    fun loadModel(onProgress: (Int) -> Unit = {}): Model {
        model?.let { return it }
        if (!isInstalled()) download(onProgress)
        return Model(modelDir.absolutePath).also { model = it }
    }

    /** Fire-and-forget download for the Settings button. */
    fun downloadInBackground() {
        if (state.value is ModelState.Downloading || isInstalled()) return
        Thread({ runCatching { download { } } }, "vosk-download").start()
    }

    @Synchronized
    fun release() {
        model?.close()
        model = null
    }

    @Synchronized
    private fun download(onProgress: (Int) -> Unit) {
        if (isInstalled()) return
        val root = File(appContext.filesDir, "vosk").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { deleteRecursively(); mkdirs() }
        try {
            state.value = ModelState.Downloading(0)
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; state.value = ModelState.Downloading(pct); onProgress(pct) }
                    }
                    return n
                }
            }
            unzip(counting, tmp)
            val extracted = File(tmp, MODEL_NAME)
            if (!File(extracted, "conf/model.conf").exists()) throw java.io.IOException("Downloaded archive looks wrong")
            modelDir.deleteRecursively()
            if (!extracted.renameTo(modelDir)) throw java.io.IOException("Could not move model into place")
            state.value = ModelState.Ready
        } catch (e: Exception) {
            state.value = ModelState.Error(e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun unzip(input: java.io.InputStream, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val f = File(dest, entry.name)
                // Zip-slip guard: never write outside the destination directory.
                if (!f.canonicalPath.startsWith(destPath)) throw java.io.IOException("Bad zip entry ${entry.name}")
                if (entry.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

        @Volatile private var instance: VoskModelManager? = null
        fun get(context: Context): VoskModelManager =
            instance ?: synchronized(this) { instance ?: VoskModelManager(context).also { instance = it } }
    }
}
