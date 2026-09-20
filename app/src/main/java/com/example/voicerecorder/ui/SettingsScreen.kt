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
import java.util.Locale

@Composable
fun SettingsScreen(padding: PaddingValues, ui: UiState, onPauseTest: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    var language by remember { mutableStateOf(settings.language) }
    var forceVosk by remember { mutableStateOf(settings.forceVosk) }
    val models = remember { VoskModelManager.get(ctx) }
    val modelState by models.state.collectAsState()

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
                when (val s = modelState) {
                    ModelState.NotInstalled -> "Not downloaded (~40 MB, English). It is fetched automatically if the fallback is ever needed."
                    is ModelState.Downloading -> "Downloading... ${s.percent}%"
                    ModelState.Ready -> "Installed: ${VoskModelManager.MODEL_NAME}"
                    is ModelState.Error -> "Download failed: ${s.message}"
                },
            )
            if (modelState is ModelState.NotInstalled || modelState is ModelState.Error) {
                OutlinedButton(onClick = { models.downloadInBackground() }) { Text("Download now") }
            }
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
