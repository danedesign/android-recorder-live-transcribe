package com.example.voicerecorder

import android.content.Context
import java.util.Locale

/** Tiny SharedPreferences wrapper for the two user settings. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Language tag typed by the user; blank means "use the device language". */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v.trim()).apply()

    /** Debug switch: skip Google and use Vosk directly. */
    var forceVosk: Boolean
        get() = prefs.getBoolean(KEY_FORCE_VOSK, false)
        set(v) = prefs.edit().putBoolean(KEY_FORCE_VOSK, v).apply()

    fun effectiveLanguage(): String = language.ifBlank { Locale.getDefault().toLanguageTag() }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_FORCE_VOSK = "force_vosk"
    }
}
