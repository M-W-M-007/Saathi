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

    /**
     * How the last sentence was understood, shown on the panel.
     *
     * Three rounds were spent not knowing whether the Gemini key was even
     * being used. One word on screen settles it every time.
     */
    @Volatile
    var lastSource: String = ""
        private set

    /** The full text of the last Gemini failure, for the panel and the test button. */
    @Volatile
    var lastError: String = ""
        private set

    fun clearSource() {
        lastSource = ""
    }

    /**
     * Model names are not forever. "gemini-flash-latest" returning 404 looks
     * exactly like a bad key from inside the app, so the alternatives are tried
     * in order and the one that answers is remembered for the session.
     */
    // v3.3: 2026-10-27 ko curl se verified — gemini-3.5-flash-lite OK deta
    // hai. 2.5/2.0/1.5 naye users ko 404 dete hain ("no longer available"),
    // isliye list se hata diye. flash-lite-latest abhi 3.5-flash-lite ko
    // hi point karta hai, isliye woh doosra fallback hai, pehla nahi.
    private val MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-3.8-flash",
        "gemini-flash-latest"
    )

    @Volatile
    private var workingModel: String? = null

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

        // v3.4: mic aksar "WhatsApp" ko "what's" / "whats app" sun leta hai
        // ("what's the business open karo"). Us galat sunai ko yahan hi
        // WhatsApp mein badal dein, warna na rules samajh paate hain aur
        // na Gemini — dono "pata nahi" bol dete hain. Original spoken
        // panel par waisa hi dikhta hai, sirf parse ke liye badla hai.
        // IMPORTANT: yeh badlao sirf tab, jab baat app kholne ki ho.
        // Message ki body mein "what's up" aana normal hai, usko
        // WhatsApp mein badalna body ko kharab kar dega.
        val lowerOrig = text.lowercase(Locale.ROOT)
        val hasWhatsMisheard = Regex("""\bwhat'?s\b""").containsMatchIn(lowerOrig)
        val hasOpenVerb = listOf("open", "kholo", "khol", "launch", "chalu", "start")
            .any { v -> Regex("""\b${Regex.escape(v)}\b""").containsMatchIn(lowerOrig) }
        val parsedText = if (hasWhatsMisheard && hasOpenVerb) {
            text
                .replace(Regex("what'?s\\s+app", RegexOption.IGNORE_CASE), "WhatsApp")
                .replace(Regex("what'?s\\b", RegexOption.IGNORE_CASE), "WhatsApp")
        } else {
            text
        }

        val lower = parsedText.lowercase(Locale.ROOT)
        val correcting = !Conversation.isEmpty() &&
            CORRECTION.any { lower.contains(Regex("""\b${Regex.escape(it)}\b""")) }

        val guess = runCatching { rules(context, parsedText) }
            .getOrElse { Guess(Command(Command.Action.UNKNOWN, text = text), 0f) }

        // A correction always goes to the model, however confident the rules
        // are: the rules cannot see what they are correcting.
        if (!correcting && guess.confidence >= TRUST_RULES) {
            lastSource = "offline"
            return listOf(guess.command)
        }

        if (Settings.geminiKey(context).isEmpty()) {
            lastSource = "Gemini key nahi"
        } else {
            val steps = runCatching { gemini(context, parsedText) }.getOrNull()
            when {
                steps == null -> lastSource = "Gemini fail" +
                    lastError.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                steps.none { it.action != Command.Action.UNKNOWN } -> lastSource = "Gemini: pata nahi"
                else -> {
                    lastSource = "Gemini"
                    // v3.3: Gemini path se aaye WhatsApp command mein bhi
                    // Business ka pata rakhna hai, warna ActionRunner ko
                    // nahi pata chalega kaunsi WhatsApp kholni hai.
                    val business = lower.contains("whatsapp business")
                    return steps.filter { it.action != Command.Action.UNKNOWN }
                        .map { step ->
                            if (business && step.action == Command.Action.WHATSAPP) {
                                step.copy(hint = "whatsapp business")
                            } else step
                        }
                }
            }
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

    // v3.5: "WhatsApp per Wajid ko ek message send karo how r u" —
    // purana pattern "ek" se pehle aur "message send karo" par toot
    // jata tha, kyunki verb list mein sirf "message kar" tha, "message
    // send kar" nahi. Ab "ek" optional hai aur verb mein "message send
    // kar / message send" bhi hai. "WhatsApp per" wale case mein slot()
    // shuru ka "per" hata deta hai, isliye who "wajid" banta hai, na
    // ki "per wajid" ya "whatsapp".
    private val WHATSAPP = Regex(
        """^(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(.+?)\s+ko\s+""" +
            """(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(?:ek\s+)?""" +
            """(?:likho|likh\s*do|likhiye|bhejo|bhej\s*do|bhejiye|message\s*kar(?:o|iye)?|""" +
            """message\s+send\s*kar(?:o|iye)?|message\s+send|msg\s+send\s*kar(?:o|iye)?|msg)""" +
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

    // v3.3: naam-pehle wale folder aur "wali file ko" wale move.
    // "Downloads mein Bills naam ka folder banao" purane FOLDER se nahi
    // milta tha (naam folder se pehle hai), aur "resume wali file ko
    // Documents mein move karo" ka destination "ko documents" ban jata
    // tha. Yeh do pattern app ke apne UI examples ko cover karte hain.
    private val FOLDER_PARENT_NAME = Regex(
        """^(.+?)\s+(?:mein|me|main|ke\s+andar)\s+(.+?)\s+(?:naam\s+ka\s+|name\s+)?folder\s+""" +
            """(?:banao|bana\s*do|banaiye|banana|create\s*kar(?:o|iye)?|create)\s*$"""
    )

    private val FOLDER_NAME_PARENT = Regex(
        """^(.+?)\s+(?:naam\s+ka\s+)?folder\s+""" +
            """(?:banao|bana\s*do|banaiye|banana|create\s*kar(?:o|iye)?|create)\s+""" +
            """(.+?)\s+(?:mein|me|main|ke\s+andar)\s*$"""
    )

    private val MOVE2 = Regex(
        """^(.+?)\s+(?:wali\s+file\s+)?(?:ko\s+)?(.+?)\s+(?:folder\s+)?""" +
            """(?:mein|me|main)\s+(?:move\s*kar(?:o|iye)?|shift\s*kar(?:o|iye)?|daal\s*do|bhej\s*do)$"""
    )

    private val WHATSAPP_NO_BODY = Regex(
        """^(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(.+?)\s+ko\s+""" +
            """(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(?:ek\s+)?""" +
            """(?:likho|likh\s*do|likhiye|bhejo|bhej\s*do|bhejiye|message\s*kar(?:o|iye)?|""" +
            """message\s+send\s*kar(?:o|iye)?|message\s+send|msg)\s*$"""
    )

    // v3.4: "Ammi new number per Ek how are you ka message bhejo" —
    // purana WHATSAPP sirf "ko" se milta tha, isliye "per" wali baat
    // Gemini par ja kar 22 second mein timeout ho jati thi. Yahan
    // slot "per/pe/par" se pin hai, message "ka message bhejo" se.
    private val WHATSAPP_PER = Regex(
        """^(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?(.+?)\s+(?:per|pe|par|pr)\s+""" +
            """(?:whatsapp\s*(?:par|pe|pr|me|mein)?\s+)?""" +
            """(?:likho\s+(?:ki\s+)?|message\s+)?(.+?)""" +
            """(?:\s+ka\s+message|\s+message|\s+msg)?(?:\s+bhej(?:o|na|dein|dijiye|do|na))?$"""
    )

    // v3.4: folder kholna, banana nahi. "Download mein test folder
    // open karo" / "test folder kholo" / "download folder open karo"
    // (aakhri mein to = download hi hai, matlab Download folder khud).
    // v3.5: user ki asli boli "download mein ek test naam SE folder
    // HAI USE open karo" hai — "naam ka" nahi, "naam se" hai, aur
    // "hai use" bhi hai. Dono ko optional kar diya, warna yeh bhi
    // Gemini par ja kar timeout ho jata tha.
    private val FOLDER_OPEN_PARENT = Regex(
        """^(?:(.+?)\s+(?:mein|me|main|ke\s+andar)\s+)?(?:ek\s+)?(.+?)\s+(?:naam\s+(?:ka|se)\s+)?folder\s+""" +
            // v3.6: "folder KO open karo / folder KO kholo" — "ko" ke
            // bina pattern milta hi nahi tha, isliye yeh bhi Gemini
            // par ja kar marr jata tha.
            """(?:ko\s+)?(?:hai\s+)?(?:use\s+|usko\s+|isko\s+)?""" +
            """(?:open\s*kar(?:o|iye|ein|na)?|open|kholo|khol\s*do|khol|dikhao|dikhaiye)\s*$"""
    )

    // v3.7: "download open karo" / "documents open karo" — folder
    // shabd ke bina bhi folder hi kholna hai, app nahi. Pehle yeh
    // OPEN_APP par ja kar generic Files khol deta tha.
    private val ALIAS_FOLDER_OPEN = Regex(
        """^(download|downloads|document|documents|pictures|music|movies|dcim|camera)\s+""" +
            """(?:ko\s+)?(?:open\s*kar(?:o|iye|ein|na)?|open|kholo|khol\s*do|khol|dikhao|dikhaiye)\s*$"""
    )

    // v3.7: "ek text file banao" / "documents mein notes naam ki
    // text file banao". Pehle CREATE_FILE action hi nahi tha, isliye
    // "folder banakar text file create kar" mein sirf folder banta tha.
    private val CREATE_FILE_PARENT = Regex(
        """^(?:(.+?)\s+(?:mein|me|main)\s+)?(?:ek\s+)?(.+?)\s+(?:naam\s+(?:ki|ka)\s+)?""" +
            """(?:text\s+)?file\s+(?:banao|banado|create\s*kar(?:o|iye)?|create)\s*$"""
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

        // v3.4: "WhatsApp Business open karo" ko label se mat dhoondho.
        // App list mein Business ka label exact nahi mil raha tha, isliye
        // panel sirf personal WhatsApp offer karta tha. Yahan hint hi
        // kaafi hai: VoiceService/ActionRunner "business" dekh kar seedha
        // com.whatsapp.w4b package kholenge, label chahe kuch bhi ho.
        if (lower.contains("whatsapp") && lower.contains("business") &&
            (verb in OPEN_VERBS || lead in OPEN_VERBS)
        ) {
            return Guess(
                Command(Command.Action.OPEN_APP, target = "WhatsApp Business", hint = "whatsapp business"),
                0.95f
            )
        }

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
        // v3.3: "WhatsApp Business" ko pehle "WhatsApp" mein badal dein,
        // warna who "business par ramesh" ban jata tha. hint mein asli
        // app ka naam rakhte hain, taaki ActionRunner sahi app khole.
        val isBusiness = lower.contains("whatsapp business")
        val waText = if (isBusiness) lower.replace("whatsapp business", "whatsapp") else lower
        val waHint = if (isBusiness) "whatsapp business" else "whatsapp"
        WHATSAPP.find(waText)?.let { m ->
            val who = cleanNamePrefix(slot(m.groupValues[1]))
            // "likho ki test message hai" mein body "ki test message
            // hai" nahi, "test message hai" honi chahiye.
            val body = m.groupValues[2].trim()
                .let { if (it.startsWith("ki ")) it.removePrefix("ki ") else it }
            if (who.isNotEmpty() && body.isNotEmpty()) {
                // The slots here are pinned by "ko" and the verb, so a long
                // message body does not make the parse any less certain.
                return Guess(
                    Command(Command.Action.WHATSAPP, target = who, text = body, hint = waHint),
                    if (nameLooksClean(who)) 0.9f else 0.5f
                )
            }
        }

        // v3.4: "per" wala WhatsApp message (upar WHATSAPP_PER dekhein).
        // Slots "per" aur "ka message bhejo" se pin hain, isliye lambi
        // body bhi is parse ko kamzor nahi karti.
        WHATSAPP_PER.find(waText)?.let { m ->
            // v3.7: "ismein ammi new number per ..." mein "ismein"
            // naam ka hissa nahi hai, warna contact kabhi nahi milega.
            val who = cleanNamePrefix(slot(m.groupValues[1]))
            val body = m.groupValues[2].trim()
            // v3.5: "WhatsApp per Wajid ko ..." mein PER pattern galat
            // who "whatsapp" bana deta tha. Platform kabhi contact nahi
            // hota — is case ko WHATSAPP pattern par chhod dein, jo
            // slot() se "per wajid" ko "wajid" bana deta hai.
            if (who.isNotEmpty() && !who.contains("whatsapp") &&
                body.isNotEmpty() &&
                body !in setOf("bhejo", "bhej", "message", "msg")
            ) {
                return Guess(
                    Command(Command.Action.WHATSAPP, target = who, text = body, hint = waHint),
                    if (nameLooksClean(who)) 0.9f else 0.5f
                )
            }
        }

        // Body ke bina WhatsApp: sirf chat kholna hai, message baad mein
        // likha jayega. Pehle yeh UNKNOWN ban jata tha aur Gemini par
        // ja kar fail hota tha.
        WHATSAPP_NO_BODY.find(waText)?.let { m ->
            val who = slot(m.groupValues[1])
            if (who.isNotEmpty()) {
                return Guess(
                    Command(Command.Action.WHATSAPP, target = who, text = "", hint = waHint),
                    if (nameLooksClean(who)) 0.85f else 0.5f
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

        MOVE2.find(lower)?.let { m ->
            return Guess(
                Command(
                    Command.Action.MOVE_FILE,
                    from = cleanFileName(slot(m.groupValues[1])),
                    to = cleanFileName(slot(m.groupValues[2]))
                ),
                minOf(0.85f, cap)
            )
        }

        MOVE.find(lower)?.let { m ->
            return Guess(
                Command(
                    Command.Action.MOVE_FILE,
                    from = cleanFileName(slot(m.groupValues[1])),
                    to = cleanFileName(slot(m.groupValues[2]))
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

        // v3.7: alias folder khud — "download open karo" mein
        // folder shabd hi nahi hai, isliye FOLDER_OPEN_PARENT kabhi
        // nahi milta tha.
        ALIAS_FOLDER_OPEN.find(lower)?.let { m ->
            return Guess(
                Command(Command.Action.OPEN_FOLDER, from = "", to = slot(m.groupValues[1])),
                minOf(0.88f, cap)
            )
        }

        // v3.7: text file banana — pehle action hi nahi tha.
        CREATE_FILE_PARENT.find(lower)?.let { m ->
            val parent = slot(m.groupValues[1])
            var name = cleanFileName(slot(m.groupValues[2]))
            if (name in setOf("normal", "nayi", "naya", "new", "")) name = "New Text File"
            return Guess(
                Command(Command.Action.CREATE_FILE, from = parent, to = name),
                minOf(0.85f, cap)
            )
        }

        // v3.4: folder kholna (banana nahi). Pehle is action ka naam hi
        // nahi tha, isliye "folder open karo" Gemini par ja kar marr jata
        // tha. ActionRunner Files app kholega aur folder ki list panel
        // par dikhayega.
        FOLDER_OPEN_PARENT.find(lower)?.let { m ->
            val parent = slot(m.groupValues[1])
            val name = cleanFolderName(slot(m.groupValues[2]))
            if (name.isNotEmpty()) {
                return Guess(
                    Command(Command.Action.OPEN_FOLDER, from = parent, to = name),
                    minOf(0.88f, cap)
                )
            }
        }

        FOLDER_PARENT_NAME.find(lower)?.let { m ->
            val name = cleanFolderName(slot(m.groupValues[2])).ifEmpty { "Naya Folder" }
            return Guess(
                Command(Command.Action.CREATE_FOLDER, from = slot(m.groupValues[1]), to = name),
                minOf(0.85f, cap)
            )
        }

        FOLDER_NAME_PARENT.find(lower)?.let { m ->
            val name = cleanFolderName(slot(m.groupValues[1])).ifEmpty { "Naya Folder" }
            return Guess(
                Command(Command.Action.CREATE_FOLDER, from = slot(m.groupValues[2]), to = name),
                minOf(0.85f, cap)
            )
        }

        FOLDER.find(lower)?.let { m ->
            val name = cleanFolderName(slot(m.groupValues[2])).ifEmpty { "Naya Folder" }
            return Guess(
                Command(Command.Action.CREATE_FOLDER, from = slot(m.groupValues[1]), to = name),
                minOf(0.85f, cap)
            )
        }

        if (verb in CALL_VERBS || lead in CALL_VERBS) CALL.find(lower)?.let { m ->
            // v3.6: "call list se shaheen sis ko call karo" mein who
            // "call list se shaheen sis" ban jata tha (5 shabd), isliye
            // confidence 0.30 ho kar Gemini par jata tha aur late /
            // samajh-nahi hota tha. "call list / contacts / nikal kar"
            // naam ka hissa nahi hote, isliye unko pehle hi hata dein.
            val who = cleanCallTarget(slot(m.groupValues[1].ifEmpty { m.groupValues[2] }))
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
    // v3.5: "per" bhi isi list mein hai, warna "per wajid" ka contact
    // "per wajid" ban jata hai aur kabhi nahi milta.
    private val TAIL = setOf("ko", "ka", "ki", "ke", "se", "par", "pe", "per", "pr", "mein", "me")

    private fun slot(value: String): String {
        val words = value.trim().trim(',', '.', '!', '?', ':', ';')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in FILLER }
            .toMutableList()

        while (words.isNotEmpty() && words.last() in TAIL) words.removeAt(words.size - 1)

        // v3.3: shuru ke case markers bhi hatayein. "ko Documents" ka
        // destination "ko documents" ban jata tha aur folder nahi milta
        // tha; "par Ramesh" ka contact "par ramesh" ban jata tha.
        // Naam ke shuru mein ko/par/pe kabhi naam ka hissa nahi hote.
        while (words.isNotEmpty() && words.first() in TAIL) words.removeAt(0)

        return words.joinToString(" ").trim()
    }


    /** "Bills naam ka" -> "Bills". Folder naam mein naam/ka shabd nahi. */
    private fun cleanFolderName(value: String): String =
        value.split(" ").filter { it !in setOf("naam", "name", "ka") }.joinToString(" ").trim()

    /** "call list se shaheen sis" -> "shaheen sis". */
    private fun cleanCallTarget(value: String): String {
        // v3.7: "rubina second number" mein "second number" naam
        // nahi, number ka chunav hai — usko hatao, taaki "rubina"
        // par contact choices dikhein. "Ammi New Number" jaise naam
        // ko chhedna nahi, isliye sirf second/first number hatao,
        // generic "number" nahi.
        val noOrdinal = value
            .replace("second number", "")
            .replace("first number", "")
            .replace("doosra number", "")
            .replace("pehla number", "")
        return noOrdinal.split(" ").filter {
            it !in setOf(
                "call", "calling", "list", "contacts", "contact", "history",
                "nikal", "nikalkar", "nikalke", "karke", "kar"
            )
        }.joinToString(" ").trim().let { slot(it) }
    }

    /** "ismein ammi new number" -> "ammi new number". */
    private fun cleanNamePrefix(value: String): String =
        value.split(" ").dropWhile {
            it in setOf("ismein", "isme", "is", "ye", "yeh", "isko", "usmein", "usme", "inmein", "inme")
        }.joinToString(" ").trim()

    /** "resume wali file" -> "resume", "ko documents" -> "documents". */
    private fun cleanFileName(value: String): String =
        value.split(" ").filter { it !in setOf("wali", "file") }.joinToString(" ").trim()
            .let { slot(it) }

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
                            LIST_FILES, OPEN_FOLDER, SEARCH_FILE, OPEN_SETTINGS, UNKNOWN,
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
            - "folder open karo" / "folder kholo" means OPEN_FOLDER, not
              OPEN_APP and not LIST_FILES: "from" is the parent folder alias
              (download/documents/empty) and "to" is the folder name. "jo
              folder banaya hai use open karo" means the folder from the
              previous turn in the history above — use its name and parent.
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

        val response = call(key, body) ?: return null

        return try {
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
            lastError = "jawab padha nahi gaya"
            null
        }
    }

    /**
     * One POST, trying each model name until one answers.
     *
     * Every failure used to come back as a bare null, so a wrong key, a dead
     * model name and a slow network were indistinguishable — and the user spent
     * an evening assuming the first. The reason is now kept in [lastError].
     *
     * v3.3: 429 par rukna band kar diya gaya hai. Pehle 429 aate hi loop
     * toot jata tha, isliye Lite fallback kabhi try hi nahi hota tha.
     * Ab 429/503 par wahi model ek baar 3 second ruk kar dobara try hota
     * hai, phir agla model try hota hai. Sirf 400/401/403 (galat/blocked
     * key) par rukte hain, kyunki woh doosre model se theek nahi honge.
     * Yeh function hamesha worker thread se call hota hai, main thread se
     * kabhi nahi, isliye Thread.sleep yahan safe hai.
     */
    private fun call(key: String, body: String): String? {
        lastError = ""

        val order = workingModel?.let { listOf(it) + MODELS.filter { m -> m != it } } ?: MODELS
        var problem = ""

        for (model in order) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "$model:generateContent?key=$key"
                )

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code == 200) {
                        workingModel = model
                        return BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                            .use { it.readText() }
                    }

                    val detail = runCatching {
                        BufferedReader(InputStreamReader(conn.errorStream, "UTF-8"))
                            .use { it.readText() }
                    }.getOrNull().orEmpty()

                    val message = runCatching {
                        JSONObject(detail).getJSONObject("error").getString("message")
                    }.getOrNull() ?: detail.take(80)

                    problem = "$code: ${message.take(90)}"

                    // A bad key, a blocked key or a quota limit will not be fixed
                    // by asking a different model, so stop rather than hammer.
                    if (code == 400 || code == 401 || code == 403) {
                        lastError = problem.ifEmpty { "koi jawab nahi" }
                        return null
                    }

                    // 429 (rate limit) ya 503 (server busy): ek baar ruk kar
                    // wahi model dobara, phir agla model.
                    if ((code == 429 || code == 503) && attempt < 2) {
                        runCatching { Thread.sleep(3000) }
                        continue
                    }
                    // 404 (retired model) aur baaki codes: seedha agla model.
                    break
                } catch (e: Exception) {
                    problem = e.javaClass.simpleName.removeSuffix("Exception")
                        .ifEmpty { "network" }
                    break
                } finally {
                    conn.disconnect()
                }
            }
        }

        lastError = problem.ifEmpty { "koi jawab nahi" }
        return null
    }

    /** Used by the app's "Gemini test" button: one tiny request, plain answer. */
    fun testKey(context: Context): String {
        val key = Settings.geminiKey(context)
        if (key.isEmpty()) return "Key save hi nahi hui hai."

        val body = JSONObject().apply {
            put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", "Reply with the word OK."))
                    )
                )
            )
        }.toString()

        val answer = call(key, body)
            ?: return "Kaam nahi kiya — $lastError"

        val text = runCatching {
            JSONObject(answer).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
        }.getOrNull().orEmpty()

        return "Chal gaya. Model: ${workingModel ?: "?"}. Jawab: ${text.take(40)}"
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
