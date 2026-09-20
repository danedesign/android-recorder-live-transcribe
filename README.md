# Live Recorder

Free, ad-free Android voice recorder with **live transcription**. Kotlin + Jetpack Compose,
`minSdk 33`, `targetSdk 35`. No accounts, no analytics, no ads.

- One big **REC / STOP** button. Recording stops **only** when you tap STOP - never on silence.
- Live transcript while recording; on stop, saves `Recording_<yyyy-MM-dd_HH-mm-ss>.wav` and `.txt` together.
- Recordings list: play audio, view / copy / share the transcript, share the audio, delete.
- Runs in a foreground service (`foregroundServiceType=microphone`), so it keeps going with the screen off.

## Build

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0.
Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file (`sdk.dir=/path/to/sdk`).

```bash
./gradlew assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Sideload it, e.g. `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy it to the
phone and open it. Unit tests: `./gradlew testDebugUnitTest`.

## Where files are saved

App-specific external storage: `Android/data/com.example.voicerecorder/files/recordings/`
(falls back to internal storage). No storage permission needed. Files are removed if you uninstall
the app - use **Share audio / Share text** to keep them elsewhere.

## Architecture

```
AudioRecord (16 kHz mono PCM16, the ONLY mic user)      AudioCapture.kt
   |-- every 100 ms chunk --> WavWriter (file)           WavWriter.kt
   `-- same chunk ----------> AudioQueue --> active engine
                                               |- GoogleSpeechEngine (SpeechRecognizer fed via a pipe)
                                               `- VoskEngine (offline fallback)
```

- **The app owns the microphone.** Recognizers never open it.
- **Google engine** (`engine/GoogleSpeechEngine.kt`): prefers `createOnDeviceSpeechRecognizer`, else
  `createSpeechRecognizer`. Audio goes in through `ParcelFileDescriptor.createPipe()` +
  `EXTRA_AUDIO_SOURCE` (with channel count 1, PCM16, 16000 Hz) and
  `EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE`, so silence ends a *segment*, not the session.
  Partial results and `onSegmentResults` / `onEndOfSegmentedSession` are handled.
- **Failure detection and restart:** if a session ends by itself, errors, never responds (8 s), returns
  one-shot results (segmented mode ignored) or its pipe breaks, a new session is started on a new pipe.
  Audio produced meanwhile waits in `AudioQueue`. Three quick failures in a row (a session lasting
  < 30 s) => on-device recognizer -> try the online one -> **Vosk**.
- **Vosk fallback** (`engine/VoskEngine.kt`): `vosk-model-small-en-us-0.15` or `vosk-model-small-cn-0.22` (~40 MB each, chosen by language), downloaded on first
  use (or via Settings -> "Download now") into app-private storage. It gets the same PCM chunks.
- **Settings tab:** active engine (Google on-device / Google online / Vosk), restart count, latest
  status, language (blank = device language), "Force Vosk" switch, model download.

## Testing "no cutoff on pauses"

Settings -> **Start pause test**. It records for 60 s and tells you what to do on screen:

| Time | You |
|---|---|
| 0-15 s | speak |
| 15-40 s | stay completely silent (25 s) |
| 40-60 s | speak again |

At 60 s it stops itself and shows **PASS** (transcript changed both before and after the silence) or
**FAIL** with counts, the engine used and the restart count. The recording is saved like any other.
To test the fallback on its own, turn on "Force Vosk" and run it again.

## Known limitations

- **Not yet verified on a physical device.** It builds, unit tests pass and the APK manifest is correct,
  but speech behaviour depends on the phone's recognizer. Run the pause test on your device first.
- **Recognizers vary.** Whether the Google service honours `EXTRA_AUDIO_SOURCE` and segmented sessions
  depends on the device/version. When it doesn't, the app restarts and eventually switches to Vosk;
  the debug line shows what happened. The audio file is unaffected either way.
- **Restart gaps:** up to ~2 s of audio already in the recognizer's pipe when a session dies is not
  re-recognized (it is still in the WAV).
- **Vosk** has English and Mandarin models only, no punctuation, and is less accurate than Google.
  If the model isn't downloaded when it's needed, transcription starts once the download finishes (audio
  is queued meanwhile); if Stop is tapped first, the queued audio is not transcribed.
- **Language:** pick Device / English / 中文 (Mandarin) in Settings, or type any tag (e.g. `fr-FR`) for Google. Vosk uses its Chinese model for `zh-*` and English otherwise. For on-device Google recognition the phone needs that language pack downloaded; otherwise it uses the online recognizer.
- WAV is uncompressed: ~115 MB per hour. No M4A option yet.
- If the process is killed mid-recording, the WAV stays valid up to the last ~5 s and the transcript up
  to the last ~15 s autosave, but recording does not resume.
- Only a debug build is produced (debug-signed). Battery optimisation on some phones may still
  restrict background work; exempt the app if recordings stop with the screen off.
