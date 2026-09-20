package com.example.voicerecorder.ui

import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.voicerecorder.data.Recording
import com.example.voicerecorder.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun prettyName(base: String) = base.removePrefix("Recording_").replace('_', ' ')

private data class Item(val rec: Recording, val preview: String)

@Composable
fun RecordingsScreen(padding: PaddingValues, refreshKey: Int, currentBase: String?, onOpen: (Recording) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { RecordingRepository(ctx) }
    var items by remember { mutableStateOf(emptyList<Item>()) }
    // Reload whenever a recording is saved/deleted. The file being written is hidden.
    LaunchedEffect(refreshKey, currentBase) {
        items = withContext(Dispatchers.IO) {
            repo.list(exclude = currentBase).map { Item(it, it.transcript.take(90).replace('\n', ' ')) }
        }
    }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("No recordings yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.rec.base }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(item.rec) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(prettyName(item.rec.base), fontWeight = FontWeight.Bold)
                        Text(formatClock(item.rec.durationMs))
                    }
                    Text(
                        item.preview.ifEmpty { "(no transcript)" },
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingDetailScreen(rec: Recording, padding: PaddingValues, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var transcript by remember(rec) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(rec) { transcript = withContext(Dispatchers.IO) { rec.transcript } }

    // --- playback ---
    var playing by remember(rec) { mutableStateOf(false) }
    var position by remember(rec) { mutableIntStateOf(0) }
    val player = remember(rec) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(rec.wav.absolutePath)
                prepare()
                setOnCompletionListener { playing = false; position = 0 }
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) { position = player?.currentPosition ?: 0; delay(200) }
    }
    val duration = (player?.duration ?: 0).coerceAtLeast(1)

    fun shareIntent(build: Intent.() -> Unit) =
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply(build), null))

    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text(prettyName(rec.base), fontWeight = FontWeight.Bold)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val p = player ?: return@Button
                if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
            }, enabled = player != null) { Text(if (playing) "Pause" else "Play") }
            Slider(
                value = position.toFloat(), valueRange = 0f..duration.toFloat(),
                onValueChange = { position = it.toInt() },
                onValueChangeFinished = { player?.seekTo(position) },
                modifier = Modifier.weight(1f),
            )
            Text("${formatClock(position.toLong())} / ${formatClock(duration.toLong())}")
        }
        if (player == null) Text("Audio file could not be opened.", color = MaterialTheme.colorScheme.error)

        Card(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(transcript.ifEmpty { "(no transcript)" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(transcript))
                Toast.makeText(ctx, "Transcript copied", Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f)) { Text("Copy") }
            OutlinedButton(onClick = {
                shareIntent { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, transcript) }
            }, Modifier.weight(1f)) { Text("Share text") }
            OutlinedButton(onClick = {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", rec.wav)
                shareIntent {
                    type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }, Modifier.weight(1f)) { Text("Share audio") }
        }
        OutlinedButton(
            onClick = { confirmDelete = true }, Modifier.fillMaxWidth(),
        ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("The audio and transcript will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    player?.release()
                    RecordingRepository(ctx).delete(rec)
                    confirmDelete = false
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
