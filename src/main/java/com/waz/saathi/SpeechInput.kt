package com.waz.saathi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper

/**
 * Wraps Android's speech recogniser.
 *
 * Forcing a language turned out to be a mistake: asking for hi-IN on a phone
 * without the Hindi voice model made the recogniser fail with network and
 * no-match errors even on a strong connection. So the language is whatever the
 * phone is set to unless the user picks one, and a failed attempt is retried
 * once in offline mode before giving up — which also covers patchy networks.
 */
class SpeechInput(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Guards the one automatic retry, so a failure cannot loop. */
    private var retried = false

    val isListening: Boolean get() = listening

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(callback: Callback) {
        retried = false
        launch(callback, offline = false)
    }

    private fun launch(callback: Callback, offline: Boolean) {
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
                if (text.isNullOrBlank()) {
                    handleFailure(SpeechRecognizer.ERROR_NO_MATCH, callback, offline)
                } else {
                    callback.onResult(text)
                }
            }

            override fun onError(error: Int) {
                listening = false
                handleFailure(error, callback, offline)
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

            // Empty means "use whatever the phone is set to", which is almost
            // always a language the device actually has a model for.
            val chosen = Settings.speechLanguage(context)
            if (chosen.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, chosen)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, chosen)
            }

            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        runCatching { sr.startListening(intent) }
            .onFailure { callback.onError("Mic shuru nahi hua: ${it.localizedMessage}") }
    }

    /**
     * Network, server and no-match failures are worth one more go in offline
     * mode: on a phone whose voice model is on-device, the online path can fail
     * while the offline one works perfectly.
     */
    private fun handleFailure(error: Int, callback: Callback, wasOffline: Boolean) {
        val retryable = error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_NO_MATCH

        if (retryable && !wasOffline && !retried) {
            retried = true
            main.postDelayed({ launch(callback, offline = true) }, 150)
            return
        }

        callback.onError(message(error))
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
        SpeechRecognizer.ERROR_CLIENT -> "Voice engine ruk gaya. Dobara try karein."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic ki permission nahi hai."

        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER ->
            "Voice engine tak nahi pahunch paya. Google app → Settings → Voice → " +
                "Languages mein apni bhasha offline download kar lein — phir bina " +
                "internet ke bhi chalega."

        SpeechRecognizer.ERROR_NO_MATCH ->
            "Kuch samajh nahi aaya. Thoda paas se aur saaf boliye. Agar baar baar " +
                "ho, toh app mein bhasha badal kar dekhein."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice engine abhi busy hai."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya."
        else -> "Voice error ($error)."
    }
}
