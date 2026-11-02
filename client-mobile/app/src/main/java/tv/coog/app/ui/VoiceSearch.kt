package tv.coog.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** In-app speech recognition (no system prompt activity — uses the TV remote mic). */
class VoiceSearchHandle(
    val available: Boolean,
    val listening: Boolean,
    val partial: String,
    val start: () -> Unit,
    val stop: () -> Unit,
)

@Composable
fun rememberVoiceSearch(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
): VoiceSearchHandle {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val onResultState = rememberUpdatedState(onResult)
    val onErrorState = rememberUpdatedState(onError)
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf(false) }
    val available = remember(appContext) {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    }
    val recognizer = remember(appContext, available) {
        if (!available) null else runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val startListeningNow = rememberUpdatedState {
        val sr = recognizer
        if (sr == null) {
            onErrorState.value("Voice search isn't available on this device.")
            return@rememberUpdatedState
        }
        if (!hasMicPermission()) {
            onErrorState.value("Microphone permission is required for voice search.")
            return@rememberUpdatedState
        }
        partial = ""
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        runCatching { sr.startListening(intent) }.onFailure {
            listening = false
            onErrorState.value(it.message ?: "Could not start voice search")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) {
            pendingStart = false
            startListeningNow.value()
        } else if (!granted) {
            pendingStart = false
            onErrorState.value("Microphone permission is required for voice search.")
        }
    }

    DisposableEffect(recognizer) {
        val sr = recognizer
        if (sr == null) {
            onDispose { }
        } else {
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    partial = ""
                    if (error == SpeechRecognizer.ERROR_CLIENT) return
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Couldn't hear the remote mic."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is required for voice search."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        -> "Network error during voice search."
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice search is busy. Try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again."
                        SpeechRecognizer.ERROR_SERVER -> "Voice service error. Try again."
                        else -> "Voice search failed."
                    }
                    onErrorState.value(message)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    partial = ""
                    if (spoken.isBlank()) {
                        onErrorState.value("Didn't catch that. Try again.")
                    } else {
                        onResultState.value(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val spoken = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spoken.isNotBlank()) partial = spoken
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            onDispose {
                runCatching { sr.cancel() }
                runCatching { sr.destroy() }
            }
        }
    }

    return VoiceSearchHandle(
        available = available && recognizer != null,
        listening = listening,
        partial = partial,
        start = {
            if (!available || recognizer == null) {
                onErrorState.value("Voice search isn't available on this device.")
                return@VoiceSearchHandle
            }
            if (!hasMicPermission()) {
                pendingStart = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startListeningNow.value()
            }
        },
        stop = {
            pendingStart = false
            listening = false
            partial = ""
            runCatching { recognizer?.cancel() }
        },
    )
}
