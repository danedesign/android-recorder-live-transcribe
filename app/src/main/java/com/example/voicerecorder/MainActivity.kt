package com.example.voicerecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.voicerecorder.data.Recording
import com.example.voicerecorder.ui.RecordScreen
import com.example.voicerecorder.ui.RecordingDetailScreen
import com.example.voicerecorder.ui.RecordingsScreen
import com.example.voicerecorder.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val ui by RecorderState.ui.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Recording?>(null) }
    var listRefresh by remember { mutableIntStateOf(0) }
    var pendingSelfTest by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            RecorderService.start(ctx, pendingSelfTest)
        } else {
            Toast.makeText(ctx, "Microphone permission is required to record", Toast.LENGTH_LONG).show()
        }
    }

    /** Starts recording, asking for the mic (and notification) permission first if needed. */
    fun begin(selfTest: Boolean) {
        pendingSelfTest = selfTest
        tab = 0
        fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        if (has(Manifest.permission.RECORD_AUDIO) && has(Manifest.permission.POST_NOTIFICATIONS)) {
            RecorderService.start(ctx, selfTest)
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0; selected = null },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Record") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1; selected = null },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Recordings") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2; selected = null },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        when {
            tab == 0 -> RecordScreen(
                ui, padding,
                onToggle = { if (ui.recording) RecorderService.stop(ctx) else begin(false) },
            )
            tab == 1 && selected != null -> RecordingDetailScreen(
                selected!!, padding,
                onBack = { selected = null },
                onDeleted = { selected = null; listRefresh++ },
            )
            tab == 1 -> RecordingsScreen(padding, ui.savedCount + listRefresh, ui.currentBase) { selected = it }
            else -> SettingsScreen(padding, ui, onPauseTest = { begin(true) })
        }
    }
}
