package com.waz.saathi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Turns a spoken sentence into a [Command].
 *
 * Two parsers, tried in order:
 *
 *  GEMINI — handles free phrasing and mixed Hindi/English, and knows which apps
 *           are actually installed, so "wo blue wala chat app kholo" can resolve.
 *           Needs an API key and a network.
 *
 *  RULES  — keyword matching. Narrower, but instant, offline and predictable.
 *           It is the fallback, and it is also what runs when no key is set.
 *
 * The rule parser is not a stub: a voice assistant that stops working without
 * a network is not much use on a phone.
 */
object CommandParser {

    fun parse(context: Context, spoken: String): Command {
        val text = spoken.trim()
        if (text.isEmpty()) return Command(Command.Action.UNKNOWN)

        if (Settings.geminiKey(context).isNotEmpty()) {
            runCatching { parseWithGemini(context, text) }.getOrNull()?.let { return it }
        }

        return parseWithRules(context, text)
    }

    // ------------------------------------------------------------- gemini

    private fun parseWithGemini(context: Context, spoken: String): Command? {
        val key = Settings.geminiKey(context)
        if (key.isEmpty()) return null

        // Only app labels are sent — no contacts, no file contents.
        val appList = AppIndex.load(context).take(80).joinToString(", ") { it.label }

        val prompt = """
            You convert a spoken phone command into JSON. The speaker mixes Hindi
            and English (Hinglish). Reply with ONLY a JSON object, no markdown.

            Schema:
            {
              "action": one of OPEN_APP, CALL, SEND_SMS, WHATSAPP, TYPE_TEXT,
                        CREATE_FOLDER, MOVE_FILE, COPY_FILE, DELETE_FILE,
                        LIST_FILES, SEARCH_FILE, OPEN_SETTINGS, UNKNOWN,
              "target": app name / contact name / phone number / file name,
              "text":   message body or text to type,
              "from":   source file or folder,
              "to":     destination folder, or new folder name for CREATE_FOLDER,
              "speak":  one short Hinglish line confirming what you understood
            }

            Installed apps: $appList

            Rules:
            - Use an app name exactly as it appears in the installed list.
            - "bhej do", "message karo" on WhatsApp means WHATSAPP, not SEND_SMS.
            - If the user dictates words to write somewhere, that is TYPE_TEXT.
            - If you cannot tell, use UNKNOWN rather than guessing an action
              that calls someone or deletes something.

            Command: $spoken
        """.trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 400)
                put("responseMimeType", "application/json")
            })
        }.toString()

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-flash-latest:generateContent?key=$key"
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null

            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                .use { it.readText() }

            val raw = JSONObject(response)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()

            toCommand(JSONObject(raw))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun toCommand(json: JSONObject): Command? {
        val action = runCatching {
            Command.Action.valueOf(json.optString("action", "UNKNOWN").uppercase(Locale.ROOT))
        }.getOrNull() ?: return null

        return Command(
            action = action,
            target = json.optString("target").trim(),
            text = json.optString("text").trim(),
            from = json.optString("from").trim(),
            to = json.optString("to").trim(),
            speak = json.optString("speak").trim()
        )
    }

    // -------------------------------------------------------------- rules

    private val openWords = listOf("kholo", "kholna", "khol", "open", "chalu karo", "start")
    private val callWords = listOf("call", "phone karo", "milao", "lagao", "dial")
    private val whatsappWords = listOf("whatsapp", "whats app", "watsapp")
    private val smsWords = listOf("sms", "message bhejo", "text bhejo", "massage")
    private val folderWords = listOf("folder banao", "folder bana", "new folder", "folder create")
    private val moveWords = listOf("move karo", "move kar", "shift karo", "hatao yahan", "daal do")
    private val copyWords = listOf("copy karo", "copy kar", "nakal")
    private val deleteWords = listOf("delete karo", "hata do", "mita do", "remove karo")
    private val listWords = listOf("files dikhao", "kya hai", "list karo", "dikhao")
    private val typeWords = listOf("likho", "likh do", "type karo", "write")

    private fun parseWithRules(context: Context, spoken: String): Command {
        val lower = spoken.lowercase(Locale.ROOT)

        // WhatsApp is checked before the generic call/message words, because
        // "whatsapp par bhej do" contains "bhej do" too.
        if (whatsappWords.any { lower.contains(it) } && !openOnly(lower)) {
            val to = afterAny(lower, listOf("ko", "to")).ifEmpty { "" }
            val msg = afterAny(lower, listOf("likho", "bhejo", "message", "bol do", ":"))
            if (to.isNotEmpty() || msg.isNotEmpty()) {
                return Command(
                    Command.Action.WHATSAPP,
                    target = cleanName(to),
                    text = msg.trim()
                )
            }
        }

        if (callWords.any { lower.contains(it) }) {
            val who = removeWords(lower, callWords + listOf("ko", "please", "zara"))
            if (who.isNotBlank()) return Command(Command.Action.CALL, target = cleanName(who))
        }

        if (smsWords.any { lower.contains(it) }) {
            val to = afterAny(lower, listOf("ko"))
            val msg = afterAny(lower, listOf("likho", "bhejo", ":"))
            return Command(Command.Action.SEND_SMS, target = cleanName(to), text = msg.trim())
        }

        if (folderWords.any { lower.contains(it) }) {
            val name = afterAny(lower, listOf("naam", "name", "folder banao", "folder bana"))
            val parent = afterAny(lower, listOf("mein", "me", "in"))
            return Command(
                Command.Action.CREATE_FOLDER,
                from = cleanName(parent),
                to = cleanName(name).ifEmpty { "Naya Folder" }
            )
        }

        if (moveWords.any { lower.contains(it) }) {
            return Command(
                Command.Action.MOVE_FILE,
                from = cleanName(betweenAny(lower, listOf("file", "wali"), listOf("ko", "ko "))),
                to = cleanName(afterAny(lower, listOf("mein", "me", "folder mein")))
            )
        }

        if (copyWords.any { lower.contains(it) }) {
            return Command(
                Command.Action.COPY_FILE,
                from = cleanName(betweenAny(lower, listOf("file", "wali"), listOf("ko"))),
                to = cleanName(afterAny(lower, listOf("mein", "me")))
            )
        }

        if (deleteWords.any { lower.contains(it) }) {
            return Command(
                Command.Action.DELETE_FILE,
                from = cleanName(removeWords(lower, deleteWords + listOf("file", "ko")))
            )
        }

        if (typeWords.any { lower.contains(it) }) {
            val what = afterAny(lower, typeWords)
            if (what.isNotBlank()) return Command(Command.Action.TYPE_TEXT, text = what.trim())
        }

        if (listWords.any { lower.contains(it) }) {
            return Command(
                Command.Action.LIST_FILES,
                from = cleanName(afterAny(lower, listOf("mein", "me", "ki", "folder")))
            )
        }

        if (lower.contains("settings")) return Command(Command.Action.OPEN_SETTINGS)

        // Anything left that names an installed app is treated as "open it".
        val stripped = removeWords(lower, openWords + listOf("app", "zara", "please"))
        AppIndex.find(context, stripped)?.let {
            return Command(Command.Action.OPEN_APP, target = it.label)
        }

        return Command(Command.Action.UNKNOWN, text = spoken)
    }

    private fun openOnly(lower: String): Boolean =
        openWords.any { lower.contains(it) } &&
            !lower.contains("bhej") && !lower.contains("message") && !lower.contains("likh")

    private fun afterAny(text: String, markers: List<String>): String {
        for (m in markers) {
            val i = text.indexOf(m)
            if (i >= 0) return text.substring(i + m.length).trim()
        }
        return ""
    }

    private fun betweenAny(text: String, start: List<String>, end: List<String>): String {
        val from = start.firstNotNullOfOrNull { m ->
            text.indexOf(m).takeIf { it >= 0 }?.plus(m.length)
        } ?: 0
        val rest = text.substring(from)
        val to = end.firstNotNullOfOrNull { m -> rest.indexOf(m).takeIf { it > 0 } } ?: rest.length
        return rest.substring(0, to).trim()
    }

    private fun removeWords(text: String, words: List<String>): String {
        var out = text
        words.forEach { out = out.replace(it, " ") }
        return out.trim().replace(Regex("\\s+"), " ")
    }

    private fun cleanName(value: String): String =
        value.trim().trim(',', '.', '!', '?', ':').trim()
}
