package com.example.dictationbutton

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Use the system's configured speech-recognition provider.
 * No ChatGPT sign-in, API key, or server owned by this app is required.
 * Device/provider may process speech online depending on Android settings.
 */
class AndroidSpeechBridge(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    fun begin() {
        check(SpeechRecognizer.isRecognitionAvailable(context)) {
            "No speech recognition service found. Install or enable Speech Services in Android settings."
        }
        finished = false
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (!finished) {
                    finished = true
                    onFailure("Speech recognition error ($error). Try again or check your microphone and speech services.")
                }
            }
            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val options = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = options?.firstOrNull()?.trim().orEmpty()
                if (text.isBlank()) onFailure("No speech detected. Try again.")
                else onResult(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    fun finish() { recognizer?.stopListening() }
    fun cancel() {
        finished = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
