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
 * The earlier version matched a keyword anywhere in the sentence and then took
 * everything that was left as the target. On a short command that works. On a
 * real sentence it is a disaster: "contact open karo jismein sister ka number
 * search karo" contains the word "call", so it became CALL with a target of
 * thirty characters of leftovers — and because the rules had "understood" it,
 * the model never got a look at it.
 *
 * So the rules now report how much they should be trusted. A short, single
 * intent sentence with a clean slot answers instantly and offline. Anything
 * longer, or anything containing two different intents, is handed to Gemini,
 * which is what it is for.
 */
object CommandParser {

    /** A parse plus how far it should be trusted. 1.0 = certain. */
    data class Guess(val command: Command, val confidence: Float)

    /** Above this the rules answer on their own — no network, no wait. */
    private const val TRUST_RULES = 0.80f

    /** Below this the rules are not worth acting on even with no model. */
    private const val FLOOR = 0.45f

    /** Words that mean "what you just did was wrong", not a fresh command. */
    private val CORRECTION = listOf(
        "nahi", "nahin", "galat", "wo nahi", "ye nahi", "doosra", "dusra",
        "second wala", "maine kaha", "arre"
    )

    fun parse(context: Context, spoken: String): Command =
        plan(context, spoken).firstOrNull() ?: Command(Command.Action.UNKNOWN, text = spoken)

    /**
     * One sentence can be several jobs: "folder banao aur ye file usme daal do".
     * The rules only ever produce one step; the model produces the rest.
     */
    fun plan(context: Context, spoken: String): List<Command> {
        val text = spoken.trim()
        if (text.isEmpty()) return emptyList()

        val lower = text.lowercase(Locale.ROOT)
        val correcting = !Conversation.isEmpty() &&
            CORRECTION.any { lower.contains(Regex("""\b${Regex.escape(it)}\b""")) }

        val guess = runCatching { rules(context, text) }
            .getOrElse { Guess(Command(Command.Action.UNKNOWN, text = text), 0f) }

        // A correction always goes to the model, however confident the rules
        // are: the rules cannot see what they are correcting.
        if (!correcting && guess.confidence >= TRUST_RULES) return listOf(guess.command)

        if (Settings.geminiKey(context).isNotEmpty()) {
            val steps = runCatching { gemini(context, text) }.getOrNull().orEmpty()
                .filter { it.action != Command.Action.UNKNOWN }
            if (steps.isNotEmpty()) return steps
        }

        if (guess.confidence >= FLOOR) return listOf(guess.command)
        return listOf(Command(Command.Action.UNKNOWN, text = text))
    }

    // ------------------------------------------------------------- patterns

    // Each regex pins the slot between explicit markers instead of taking
    // "whatever is left", so a long sentence cannot leak into a target.

    private val OPEN = Regex(
        """^(?:(.+?)\s+)?(?:ko\s+)?""" +
            """(?:kholiye|kholie|kholo|khol\s*do|kholna|khol|""" +
            """open\s*kar(?:o|iye|ein|na)?|open|launch\s*kar(?:o|iye)?|launch|""" +
            """chalu\s*kar(?:o|iye)?|start\s*kar(?:o|iye)?|start)""" +
            """(?:\s+(.+))?$"""
    )

    private val CALL = Regex(
        """^(?:(.+?)\s+)?(?:ko\s+)?""" +
            """(?:call\s*kar(?:o|iye|ein|na|un)?|call|phone\s*kar(?:o|iye)?|phone|""" +
            """dial\s*kar(?:o|iye)?|dial|milao|milaiye|laga\s*do|lagao|lagaiye)""" +
            """(?:\s+(.+))?$"""
    )

    private val WHATSAPP = Regex(
        """^(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(.+?)\s+ko\s+""" +
            """(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?""" +
            """(?:likho|likh\s*do|likhiye|bhejo|bhej\s*do|bhejiye|message\s*kar(?:o|iye)?|msg)""" +
            """\s*[:,\-]?\s*(.+)$"""
    )

    private val SMS = Regex(
        """^(.+?)\s+ko\s+(?:sms|message|msg|text|massage)\s*""" +
            """(?:kar(?:o|iye)?|bhejo|bhej\s*do|bhejiye)?\s*[:,\-]?\s*(.+)$"""
    )

    private val TYPE = Regex(
        """^(?:likho|likh\s*do|likhiye|likh\s*dijiye|type\s*kar(?:o|iye)?|type|write)""" +
            """\s*[:,\-]?\s*(.+)$"""
    )

    private val FOLDER = Regex(
        """^(?:(.+?)\s+(?:mein|me|main|ke\s+andar)\s+)?(?:naya\s+|new\s+)?folder\s*""" +
            """(?:banao|bana\s*do|banaiye|banana|create\s*kar(?:o|iye)?|create)\s*""" +
            """(?:ka\s+)?(?:naam|name)?\s*(.*)$"""
    )

    private val MOVE = Regex(
        """^(.+?)\s+(?:ko|wali\s+file)\s+(.+?)\s+(?:mein|me|main|folder\s+mein)\s+""" +
            """(?:move\s*kar(?:o|iye)?|shift\s*kar(?:o|iye)?|daal\s*do|bhej\s*do)$"""
    )

    private val COPY = Regex(
        """^(.+?)\s+ko\s+(.+?)\s+(?:mein|me|main)\s+copy\s*kar(?:o|iye)?$"""
    )

    private val LIST = Regex(
        """^(.+?)\s+(?:mein|me|main|ki|ke)\s+(?:kya\s+hai|files?\s*(?:dikhao|dikhaiye|batao)|""" +
            """list\s*kar(?:o|iye)?|dikhao|dikhaiye)$"""
    )

    private val SEARCH = Regex(
        """^(.+?)\s+(?:naam\s+ki\s+)?(?:file\s+)?(?:dhoondho|dhundo|dhoondiye|search\s*kar(?:o|iye)?|""" +
            """khojo|find\s*kar(?:o|iye)?)$"""
    )

    /** Words that carry no meaning in a command and only confuse the slots. */
    private val FILLER = setOf(
        "zara", "please", "plz", "bhai", "yaar", "abhi", "jaldi",
        "mere", "meri", "mera", "app", "ek", "to", "toh", "na", "hai"
    )

    /**
     * Each family's giveaway words. When two different families show up in one
     * sentence the rules stop guessing — that mixture is exactly what the model
     * is better at.
     */
    private val FAMILIES = mapOf(
        "open" to listOf("kholo", "kholiye", "khol", "open", "launch", "chalu", "start"),
        "call" to listOf("call", "phone", "dial", "milao", "milaiye", "lagao"),
        // "whatsapp" is deliberately not here: "WhatsApp kholo" is an app being
        // opened, and counting it as a message intent made that simple command
        // look ambiguous and sent it to the network for no reason.
        "msg" to listOf("sms", "message", "msg", "bhejo", "bhej", "massage"),
        "write" to listOf("likho", "likh", "type", "write"),
        "file" to listOf("folder", "file", "move", "copy", "delete", "dhoondho", "search", "khojo")
    )

    // ---------------------------------------------------------------- rules

    private fun rules(context: Context, spoken: String): Guess {
        val lower = spoken.lowercase(Locale.ROOT).trim().trim('.', '!', '?', ',')
        val words = lower.split(Regex("\\s+")).size

        val mixed = FAMILIES.count { (_, keys) ->
            keys.any { k -> lower.contains(Regex("""\b${Regex.escape(k)}\b""")) }
        } > 1

        // A sentence with two intents in it, or one long enough to be a
        // sentence rather than a command, never answers on rules alone.
        val cap = when {
            mixed -> 0.35f
            words > 8 -> 0.40f
            else -> 1f
        }

        // In Hinglish the last verb decides the action, so that is what picks
        // the family here. Without this, "phone kholo" matched the call rule —
        // "phone" is a call word wherever it appears — and tried to ring a
        // contact called "kholo" instead of opening the Phone app.
        val verb = mainVerb(lower)
        // "call karo mummy ko" puts the verb first instead, so both ends count.
        val lead = lower.split(Regex("\\s+")).first()

        if (verb in OPEN_VERBS || lead in OPEN_VERBS) {
            OPEN.find(lower)?.let { m ->
                val name = slot(m.groupValues[1].ifEmpty { m.groupValues[2] })
                if (name.isNotEmpty() && nameLooksClean(name)) {
                    AppIndex.find(context, name)?.let {
                        // A named app that is actually installed is as certain
                        // as this gets, mixed wording or not.
                        return Guess(
                            Command(Command.Action.OPEN_APP, target = it.label, hint = name),
                            0.93f
                        )
                    }
                }
            }
        }

        // Messages first: "X ko whatsapp par likho ..." also contains "bhej".
        WHATSAPP.find(lower)?.let { m ->
            val who = slot(m.groupValues[1])
            val body = m.groupValues[2].trim()
            if (who.isNotEmpty() && body.isNotEmpty()) {
                // The slots here are pinned by "ko" and the verb, so a long
                // message body does not make the parse any less certain.
                return Guess(
                    Command(Command.Action.WHATSAPP, target = who, text = body),
                    if (nameLooksClean(who)) 0.9f else 0.5f
                )
            }
        }

        SMS.find(lower)?.let { m ->
            val who = slot(m.groupValues[1])
            val body = m.groupValues[2].trim()
            if (who.isNotEmpty() && body.isNotEmpty()) {
                return Guess(
                    Command(Command.Action.SEND_SMS, target = who, text = body),
                    if (nameLooksClean(who)) 0.88f else 0.5f
                )
            }
        }

        TYPE.find(lower)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.isNotEmpty()) {
                return Guess(Command(Command.Action.TYPE_TEXT, text = what), minOf(0.85f, cap))
            }
        }

        MOVE.find(lower)?.let { m ->
            return Guess(
                Command(
                    Command.Action.MOVE_FILE,
                    from = slot(m.groupValues[1]),
                    to = slot(m.groupValues[2])
                ),
                minOf(0.85f, cap)
            )
        }

        COPY.find(lower)?.let { m ->
            return Guess(
                Command(
                    Command.Action.COPY_FILE,
                    from = slot(m.groupValues[1]),
                    to = slot(m.groupValues[2])
                ),
                minOf(0.85f, cap)
            )
        }

        FOLDER.find(lower)?.let { m ->
            val name = slot(m.groupValues[2]).ifEmpty { "Naya Folder" }
            return Guess(
                Command(Command.Action.CREATE_FOLDER, from = slot(m.groupValues[1]), to = name),
                minOf(0.85f, cap)
            )
        }

        if (verb in CALL_VERBS || lead in CALL_VERBS) CALL.find(lower)?.let { m ->
            val who = slot(m.groupValues[1].ifEmpty { m.groupValues[2] })
            if (who.isNotEmpty()) {
                // A name is one or two words. Anything longer is leftovers from
                // a sentence, and dialling on leftovers is how a stray "S" in
                // a 4,000-contact phonebook gets called.
                val confidence = if (nameLooksClean(who)) 0.86f else 0.30f
                return Guess(Command(Command.Action.CALL, target = who), minOf(confidence, cap))
            }
        }

        SEARCH.find(lower)?.let { m ->
            val what = slot(m.groupValues[1])
            if (what.isNotEmpty() && what.split(" ").size <= 4) {
                return Guess(Command(Command.Action.SEARCH_FILE, target = what), minOf(0.8f, cap))
            }
        }

        LIST.find(lower)?.let { m ->
            return Guess(
                Command(Command.Action.LIST_FILES, from = slot(m.groupValues[1])),
                minOf(0.8f, cap)
            )
        }

        if (Regex("""\bsettings?\b""").containsMatchIn(lower) && words <= 4) {
            return Guess(Command(Command.Action.OPEN_SETTINGS), minOf(0.9f, cap))
        }

        OPEN.find(lower)?.let { m ->
            val name = slot(m.groupValues[1].ifEmpty { m.groupValues[2] })
            if (name.isNotEmpty() && name.split(" ").size <= 4) {
                val entry = AppIndex.find(context, name)
                if (entry != null) {
                    return Guess(
                        Command(Command.Action.OPEN_APP, target = entry.label, hint = name),
                        minOf(0.92f, cap)
                    )
                }
                return Guess(
                    Command(Command.Action.OPEN_APP, target = name, hint = name),
                    minOf(0.4f, cap)
                )
            }
        }

        // Bare app name with no verb at all: "WhatsApp".
        if (words <= 3) {
            val bare = slot(lower)
            AppIndex.find(context, bare)?.let {
                return Guess(
                    Command(Command.Action.OPEN_APP, target = it.label, hint = bare),
                    minOf(0.82f, cap)
                )
            }
        }

        return Guess(Command(Command.Action.UNKNOWN, text = spoken), 0f)
    }

    /** A real name or app name: short, and not a leftover clause. */
    private fun nameLooksClean(value: String): Boolean {
        val parts = value.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 3) return false
        // Clause words mean a sentence was sliced, not a name extracted.
        val clause = listOf("jisme", "jismein", "jiska", "jiske", "aur", "phir", "karo", "kar")
        return parts.none { it in clause }
    }

    /** Helper verbs that sit after the real one: "open KARO", "likh DO". */
    private val AUX = setOf(
        "karo", "kar", "kijiye", "kariye", "karein", "karna", "karun",
        "do", "dijiye", "de", "dena", "please", "zara", "na"
    )

    private val OPEN_VERBS = setOf(
        "kholo", "kholiye", "kholie", "kholna", "khol", "open", "launch", "chalu", "start"
    )

    private val CALL_VERBS = setOf(
        "call", "phone", "dial", "milao", "milaiye", "lagao", "lagaiye", "laga"
    )

    /** The last word once the helper verbs are peeled off the end. */
    private fun mainVerb(lower: String): String {
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.size > 1 && words.last() in AUX) words.removeAt(words.size - 1)
        return words.lastOrNull().orEmpty()
    }

    /** Hindi case markers, which cling to the end of a slot: "mummy ko". */
    private val TAIL = setOf("ko", "ka", "ki", "ke", "se", "par", "pe", "pr", "mein", "me")

    private fun slot(value: String): String {
        val words = value.trim().trim(',', '.', '!', '?', ':', ';')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in FILLER }
            .toMutableList()

        while (words.isNotEmpty() && words.last() in TAIL) words.removeAt(words.size - 1)

        return words.joinToString(" ").trim()
    }

    // --------------------------------------------------------------- gemini

    private fun gemini(context: Context, spoken: String): List<Command>? {
        val key = Settings.geminiKey(context)
        if (key.isEmpty()) return null

        // Only app labels are sent — no contacts, no file contents.
        val appList = AppIndex.load(context).take(80).joinToString(", ") { it.label }

        val history = Conversation.recent().ifBlank { "(abhi tak kuch nahi)" }

        val prompt = """
            You convert a spoken phone command into JSON. The speaker mixes
            Hindi and English (Hinglish), often in long sentences, and may be
            correcting something you got wrong a moment ago. Reply with ONLY a
            JSON object, no markdown.

            Schema:
            {
              "steps": [
                {
                  "action": one of OPEN_APP, CALL, SEND_SMS, WHATSAPP, TYPE_TEXT,
                            CREATE_FOLDER, MOVE_FILE, COPY_FILE, DELETE_FILE,
                            LIST_FILES, SEARCH_FILE, OPEN_SETTINGS, UNKNOWN,
                  "target": app name / contact name / phone number / file name,
                  "text":   message body or text to type,
                  "from":   source file or folder,
                  "to":     destination folder, or new folder name for CREATE_FOLDER,
                  "speak":  one short Hinglish line saying what this step does
                }
              ]
            }

            Installed apps: $appList

            What happened just before (oldest first):
            $history

            Rules:
            - One sentence can be several jobs. "folder banao aur ye file usme
              daal do" is TWO steps: CREATE_FOLDER then MOVE_FILE. Put them in
              "steps" in the order they must run. Most commands are one step.
            - If the user is correcting the previous turn ("nahi, business wala",
              "galat app khul gayi"), work out what they actually meant from the
              history and return THAT command — not UNKNOWN.
            - "target" must be SHORT: an app name or a person's name, at most
              three words. Never put a whole clause in it.
            - Use an app name exactly as it appears in the installed list. If
              two labels are similar, pick the one the user said in full —
              "WhatsApp Business" is not "WhatsApp".
            - Folder names: Download, Documents, Pictures, DCIM, Movies, Music.
            - For MOVE_FILE and COPY_FILE: "from" is the file, "to" is the
              destination folder. For CREATE_FOLDER: "to" is the new folder's
              name and "from" is the folder it goes inside (may be empty).
            - "bhej do" / "message karo" about WhatsApp means WHATSAPP, not SEND_SMS.
            - If the user asks to open contacts and look someone up, that is
              OPEN_APP with the contacts app — not CALL.
            - Only use CALL when the user clearly names ONE person to ring.
            - If you cannot tell, use a single step with action UNKNOWN rather
              than guessing an action that calls someone or deletes something.

            Command: $spoken
        """.trimIndent()

        val body = JSONObject().apply {
            put(
                "contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 800)
                put("responseMimeType", "application/json")
            })
        }.toString()

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-flash-latest:generateContent?key=$key"
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 10000
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

            val json = JSONObject(raw)

            // Both shapes are accepted: a bare command, and the list of steps
            // the prompt asks for. A model that drops the wrapper should not
            // cost the user their command.
            val steps = json.optJSONArray("steps")
            if (steps == null) {
                listOfNotNull(toCommand(json))
            } else {
                (0 until minOf(steps.length(), 6)).mapNotNull { i ->
                    steps.optJSONObject(i)?.let { toCommand(it) }
                }
            }
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

        val target = json.optString("target").trim()

        // A model that returns a whole clause as the target is making the same
        // mistake the old rules made, so the same guard applies.
        if (action == Command.Action.CALL && !nameLooksClean(target)) return null

        return Command(
            action = action,
            target = target,
            text = json.optString("text").trim(),
            from = json.optString("from").trim(),
            to = json.optString("to").trim(),
            speak = json.optString("speak").trim(),
            hint = target
        )
    }
}
