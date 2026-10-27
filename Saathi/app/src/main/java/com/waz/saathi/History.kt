package com.waz.saathi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few commands and what came of them.
 *
 * This is here so the user can see why something went wrong — "call mummy"
 * heard as "call mommy" is obvious once it is written down, and invisible
 * otherwise. Capped and local; nothing is uploaded.
 */
object History {

    private const val PREFS = "saathi_history"
    private const val KEY = "items"
    private const val MAX = 40

    data class Item(val heard: String, val result: String, val ok: Boolean, val at: Long)

    fun add(context: Context, heard: String, result: String, ok: Boolean) {
        val items = load(context).toMutableList()
        items.add(0, Item(heard, result, ok, System.currentTimeMillis()))
        while (items.size > MAX) items.removeAt(items.size - 1)

        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().apply {
                put("heard", it.heard)
                put("result", it.result)
                put("ok", it.ok)
                put("at", it.at)
            })
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun load(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Item(
                    o.optString("heard"),
                    o.optString("result"),
                    o.optBoolean("ok", true),
                    o.optLong("at")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
