package com.example.voicerecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicerecorder.AppSettings
import com.example.voicerecorder.PauseTest
import com.example.voicerecorder.UiState
import com.example.voicerecorder.engine.ModelState
import com.example.voicerecorder.engine.VoskModelManager
import com.example.voicerecorder.engine.VoskModelSpec
import androidx.compose.material3.FilterChip
import java.util.Locale

@Composable
fun SettingsScreen(padding: PaddingValues, ui: UiState, onPauseTest: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    var language by remember { mutableStateOf(settings.language) }
    var forceVosk by remember { mutableStateOf(settings.forceVosk) }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Engine (debug)") {
            Text("Active: ${ui.engineLabel}")
            Text("Session restarts this recording: ${ui.restarts}")
            if (ui.status.isNotEmpty()) Text(ui.status, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = forceVosk, onCheckedChange = { forceVosk = it; settings.forceVosk = it })
                Text("  Force Vosk (skip Google) - for testing")
            }
        }

        Section("Language") {
            // Quick picks. This overrides the device language, so an English phone can record in Mandarin.
            fun pick(tag: String) { language = tag; settings.language = tag }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(language.isBlank(), { pick("") }, label = { Text("Device") })
                FilterChip(language.startsWith("en", true), { pick("en-US") }, label = { Text("English") })
                FilterChip(language.startsWith("zh", true), { pick("zh-CN") }, label = { Text("中文 (Mandarin)") })
            }
            OutlinedTextField(
                value = language,
                onValueChange = { language = it; settings.language = it },
                label = { Text("Language tag, e.g. en-AU, fr-FR") },
                placeholder = { Text(Locale.getDefault().toLanguageTag()) },
                supportingText = { Text("Blank = device language (${Locale.getDefault().toLanguageTag()}). Applies to the next recording.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Offline model (Vosk fallback)") {
            Text(
                "Used only if Google's recognizer fails. Each model is ~40 MB and is fetched " +
                    "automatically if needed; download now to have it available offline.",
                style = MaterialTheme.typography.bodySmall,
            )
            VoskModelSpec.entries.forEach { ModelRow(it) }
        }

        Section("Pause test (60 s)") {
            Text(
                "Verifies the transcript does not stop on silence. You will be told when to speak " +
                    "(0-15 s), stay silent (15-40 s) and speak again (40-60 s). It stops itself at 60 s " +
                    "and reports PASS/FAIL on the Record screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onPauseTest, enabled = !ui.recording) { Text("Start pause test") }
        }
    }
}

/** One line per offline model: status plus a download button when it is missing. */
@Composable
private fun ModelRow(spec: VoskModelSpec) {
    val manager = VoskModelManager.get(LocalContext.current, spec)
    val state by manager.state.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${spec.displayName}: " + when (val s = state) {
                ModelState.NotInstalled -> "not downloaded"
                is ModelState.Downloading -> "downloading ${s.percent}%"
                ModelState.Ready -> "installed"
                is ModelState.Error -> "failed (${s.message})"
            },
            Modifier.weight(1f),
        )
        if (state is ModelState.NotInstalled || state is ModelState.Error) {
            OutlinedButton(onClick = { manager.downloadInBackground() }) { Text("Download") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
