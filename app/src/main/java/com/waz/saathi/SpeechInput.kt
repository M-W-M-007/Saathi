package com.waz.saathi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Wraps Android's speech recogniser.
 *
 * The language is set to Hindi (India) rather than English, because that model
 * handles Hinglish far better: it transcribes English words spoken in an Indian
 * accent, while the English model mangles the Hindi ones entirely.
 */
class SpeechInput(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isListening: Boolean get() = listening

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(callback: Callback) {
        stop()

        if (!available()) {
            callback.onError("Is phone par voice recognition available nahi hai.")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                callback.onReady()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { callback.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = first(results)
                if (text.isNullOrBlank()) callback.onError("Kuch sunai nahi diya.")
                else callback.onResult(text)
            }

            override fun onError(error: Int) {
                listening = false
                callback.onError(message(error))
            }

            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() { listening = false }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        runCatching { sr.startListening(intent) }
            .onFailure { callback.onError("Mic shuru nahi hua: ${it.localizedMessage}") }
    }

    fun stop() {
        listening = false
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Mic se awaaz nahi aayi."
        SpeechRecognizer.ERROR_CLIENT -> "Voice engine ruk gaya."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic ki permission nahi hai."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network nahi mila. Offline voice ke liye Google app mein Hindi download karein."
        SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara boliye."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice engine abhi busy hai."
        SpeechRecognizer.ERROR_SERVER -> "Voice server se jawab nahi mila."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya."
        else -> "Voice error ($error)."
    }

    companion object {
        val LOCALE: Locale = Locale("hi", "IN")
    }
}
