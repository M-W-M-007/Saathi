package com.waz.saathi

import android.content.Context

/** Small persisted settings. Nothing here ever leaves the phone by itself. */
object Settings {

    private const val PREFS = "saathi"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_CONFIRM = "confirm_actions"
    private const val KEY_SPEAK_BACK = "speak_back"
    private const val KEY_LANG = "speech_language"
    private const val KEY_BUBBLE = "show_bubble"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun geminiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI, "").orEmpty().trim()

    fun setGeminiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    /**
     * Defaults to on. Speech recognition mishears names, and a wrong guess here
     * dials a stranger or deletes the wrong file — so the user sees what was
     * understood before it happens, until they decide otherwise.
     */
    fun confirmActions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM, true)

    fun setConfirmActions(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM, value).apply()
    }

    /**
     * Empty = use whatever the phone is set to. Forcing a language the device
     * has no voice model for makes the recogniser fail outright, so "phone
     * default" is the safe default and the others are opt-in.
     */
    // v3.9 REVERT of v3.8: v3.8 mein default hi-IN kar diya tha,
    // lekin jis phone par Hindi voice model nahi hai, wahan
    // recognizer network/no-match error dekar sunna hi band kar
    // deta hai — SpeechInput.kt ki pehle se likhi warning wahi
    // kehti hai, aur v3.8 video mein wahi hua ("bilkul bhi sunayi
    // nhi"). Default phir se phone default (empty) hai; jo user
    // Settings mein Hindi/English chun chuka hai, uska chunav hi
    // rahega. Hindi chahiye toh Settings mein Hindi chunna hoga,
    // lekin uske liye phone par Hindi voice model hona zaroori hai.
    fun speechLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "").orEmpty()

    fun setSpeechLanguage(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANG, tag).apply()
    }

    /**
     * Whether the floating mic sits on screen. Turning it off does not stop the
     * assistant — the notification and the Quick Settings tile still start it —
     * it just gets the button out of the way.
     */
    fun showBubble(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE, true)

    fun setShowBubble(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE, value).apply()
    }

    fun speakBack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_BACK, true)

    fun setSpeakBack(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_BACK, value).apply()
    }
}
