package com.waz.saathi

import android.content.Context

/** Small persisted settings. Nothing here ever leaves the phone by itself. */
object Settings {

    private const val PREFS = "saathi"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_CONFIRM = "confirm_actions"
    private const val KEY_SPEAK_BACK = "speak_back"

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

    fun speakBack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_BACK, true)

    fun setSpeakBack(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_BACK, value).apply()
    }
}
