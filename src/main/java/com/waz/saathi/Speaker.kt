package com.waz.saathi

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads replies back. Hindi voice where available, otherwise whatever the
 * phone has — the replies are Hinglish, so an English voice is still intelligible.
 *
 * Speech is never queued for later: if the engine is not ready yet the line is
 * simply skipped. A sentence arriving out of nowhere minutes later is worse
 * than not hearing it at all.
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val hindi = Locale("hi", "IN")
                val result = tts?.setLanguage(hindi)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(0.95f)
            }
        }
    }

    fun say(context: Context, text: String) {
        if (!Settings.speakBack(context)) return
        if (text.isBlank()) return

        if (tts == null) {
            init(context)
            return
        }
        if (!ready) return

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saathi")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
