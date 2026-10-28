package com.waz.saathi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The always-available part of the assistant: a floating mic button that works
 * from inside any app, plus the panel that shows what was heard, what it was
 * understood as, and what happened.
 *
 * The flow is deliberately visible rather than instant. Speech recognition
 * mishears names and numbers, so the user sees "call Ramesh?" before a call is
 * placed. That confirmation can be switched off for speed once trust is there.
 */
class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.waz.saathi.START"
        const val ACTION_STOP = "com.waz.saathi.STOP"
        const val ACTION_LISTEN = "com.waz.saathi.LISTEN"
        const val ACTION_TOGGLE_BUBBLE = "com.waz.saathi.TOGGLE_BUBBLE"

        private const val CHANNEL_ID = "saathi_voice"
        private const val NOTIF_ID = 7701

        @Volatile
        var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var speech: SpeechInput

    private var bubble: View? = null
    private var panel: View? = null

    private var screenW = 0
    private var screenH = 0

    /** Command waiting for a yes/no. */
    private var pending: Command? = null

    /** Choices waiting for a tap, when more than one thing could have been meant. */
    private var choices: List<Pair<String, Command>> = emptyList()

    /** A multi-step plan waiting for one yes. */
    private var plan: List<Command> = emptyList()

    /** What was actually heard, without the panel's parser tag stuck on it. */
    private var lastHeard: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speech = SpeechInput(this)
        Speaker.init(this)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_BUBBLE -> {
                val now = !Settings.showBubble(this)
                Settings.setShowBubble(this, now)
                if (now) showBubble() else hideBubble()
                startAsForeground()      // refresh the notification's label
            }

            ACTION_LISTEN -> {
                startAsForeground()
                applyBubbleSetting()
                isRunning = true
                beginListening()
            }

            else -> {
                startAsForeground()
                applyBubbleSetting()
                isRunning = true
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------- foreground

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Saathi", NotificationManager.IMPORTANCE_LOW)
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val listen = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).setAction(ACTION_LISTEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleBubble = PendingIntent.getService(
            this, 3,
            Intent(this, VoiceService::class.java).setAction(ACTION_TOGGLE_BUBBLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bubbleLabel = if (Settings.showBubble(this)) "Bubble chhupao" else "Bubble dikhao"

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            // The version is here on purpose: when something does not behave
            // as expected, the first question is always which build is running.
            .setContentTitle("Saathi v${BuildConfig.VERSION_NAME} tayyar hai")
            .setContentText(
                if (Settings.showBubble(this)) "Bubble dabakar boliye"
                else "Yahan \"Suno\" dabakar boliye"
            )
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(Notification.Action.Builder(null, "Suno", listen).build())
            .addAction(Notification.Action.Builder(null, bubbleLabel, toggleBubble).build())
            .addAction(Notification.Action.Builder(null, "Band karo", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // -------------------------------------------------------------- bubble

    private fun showBubble() {
        if (bubble != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - 230
            y = screenH / 3
        }

        var downX = 0f; var downY = 0f
        var origX = 0; var origY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    origX = params.x; origY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = origX + dx
                    params.y = origY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val heldLong = event.eventTime - event.downTime > 600
                    when {
                        moved -> Unit                    // was a drag
                        heldLong -> {
                            // Long press hides it. The assistant keeps running;
                            // the notification and the tile still start it.
                            Settings.setShowBubble(this@VoiceService, false)
                            hideBubble()
                            startAsForeground()
                            toast("Bubble chhup gaya. Notification ya Quick Settings tile se chalu kar sakte hain.")
                        }
                        else -> beginListening()
                    }
                    true
                }

                else -> false
            }
        }

        windowManager.addView(view, params)
        bubble = view
    }

    private fun applyBubbleSetting() {
        if (Settings.showBubble(this)) showBubble() else hideBubble()
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    // ------------------------------------------------------------ listening

    private fun beginListening() {
        Speaker.stop()
        CommandParser.clearSource()
        pending = null
        choices = emptyList()
        plan = emptyList()
        showPanel("Sun raha hoon…", "")

        speech.start(object : SpeechInput.Callback {
            override fun onReady() {
                main.post { updatePanel("Boliye…", "") }
            }

            override fun onPartial(text: String) {
                main.post { updatePanel("Sun raha hoon…", text) }
            }

            override fun onResult(text: String) {
                main.post { handleHeard(text) }
            }

            override fun onError(message: String) {
                main.post {
                    updatePanel(message, "")
                    Speaker.say(this@VoiceService, message)
                }
            }
        })
    }

    private fun handleHeard(spoken: String) {
        updatePanel("Samajh raha hoon…", spoken)

        // A watchdog, because a silent stall here is what the user actually
        // sees: the panel sat on "Samajh raha hoon…" with no way forward.
        // v3.5: pehle timeout ke baad Gemini ka der se aaya jawab phenk
        // diya jata tha — user ko sirf timeout dikhta tha, kaam kabhi
        // nahi hota tha. Ab timeout sirf ek message hai, uske baad aaya
        // jawab bhi panel par aa jayega aur chalega. "planned" sirf
        // isliye hai ki timeout/progress doosri baar na dikhein.
        val planned = java.util.concurrent.atomic.AtomicBoolean(false)

        // v3.4: 22 second bahut kam tha — Gemini ke 429/503 retry aur
        // chaar model try karne mein usse zyada lag jata tha, aur user
        // ko lagta tha app ruk gaya hai. 45 second ka timeout, aur
        // 15 second par ek progress line, taaki beech mein khamoshi na ho.
        val timeout = Runnable {
            if (!planned.get()) {
                updatePanel(
                    "Samajhne mein bahut der lag gayi. Dobara boliye, ya chhota command dein.",
                    spoken
                )
            }
        }
        val progress = Runnable {
            if (!planned.get()) {
                updatePanel("Gemini se pooch raha hoon, thoda rukiye…", spoken)
            }
        }
        main.postDelayed(progress, 15_000)
        main.postDelayed(timeout, 45_000)

        // Parsing may call the network, so it never runs on the main thread.
        Thread {
            val steps = runCatching { CommandParser.plan(this, spoken) }
                .getOrElse { listOf(Command(Command.Action.UNKNOWN, text = spoken)) }

            main.post {
                planned.set(true)
                main.removeCallbacks(timeout)
                main.removeCallbacks(progress)
                onPlanned(spoken, steps)
            }
        }.start()
    }

    private fun onPlanned(spoken: String, steps: List<Command>) {
        val real = steps.filter { it.action != Command.Action.UNKNOWN }

        if (real.isEmpty()) {
            val msg = "Yeh samajh nahi aaya."
            // When the model could not be reached, the reason belongs on screen
            // rather than in a log nobody can open from a phone.
            val why = CommandParser.lastError
                .takeIf { it.isNotEmpty() }
                ?.let { listOf("Gemini: $it", "App mein \"Gemini test karo\" dabakar check karein.") }
                .orEmpty()

            updatePanel(msg, spoken, why)
            Speaker.say(this, msg)
            Conversation.add(spoken, msg)
            return
        }

        // Several jobs in one sentence are shown as a numbered plan and
        // confirmed once, rather than running silently one after another.
        if (real.size > 1) {
            plan = real
            pending = null
            choices = emptyList()
            updatePanel(
                "${real.size} kaam karne hain — karun?",
                spoken,
                real.mapIndexed { i, c -> "${i + 1}. ${c.describe().trimEnd('?')}" }
            )
            Speaker.say(this, "${real.size} kaam karne hain, karun?")
            return
        }

        onParsed(spoken, real.first())
    }

    private fun onParsed(spoken: String, command: Command) {

        // The app the user named is only opened when its label matches exactly.
        // Anything short of that is offered, never assumed: asking for
        // "WhatsApp Business" and silently getting WhatsApp is the single thing
        // that wasted the most of the user's evening, and it happened because
        // a near match was treated as a match.
        // v3.4: Business ke liye choices mat poochho — package pin hai
        // (AppIndex.businessEntry), label chahe kuch bhi ho. Yeh block
        // tabhi chalega jab Business install hi nahi hai.
        // v3.9: "whatsapp" shortcut ko AppIndex check se mat roko —
        // parser ne pakka WhatsApp diya hai, aur ActionRunner
        // package se seedha kholega, chahe app list mein kuch bhi ho.
        if (command.action == Command.Action.OPEN_APP && command.hint.isNotEmpty() &&
            command.hint != "whatsapp" &&
            !(command.hint.contains("business", ignoreCase = true) &&
                AppIndex.businessEntry(this) != null)
        ) {
            val match = AppIndex.best(this, command.hint)
            val near = AppIndex.matches(this, command.hint)

            if (match == null) {
                // v3.7: app nahi mili toh haar nahi — ho sakta hai
                // user file/folder keh raha ho ("test ko open karke
                // dikhao"). Dono raste choice mein dein.
                showChoices(
                    "\"${command.hint}\" naam ki koi app nahi mili. File/folder hai?",
                    spoken,
                    listOf(
                        "📁 Folder kholo: ${command.hint}" to Command(
                            Command.Action.OPEN_FOLDER, from = "", to = command.hint
                        ),
                        "📄 File dhoondho: ${command.hint}" to Command(
                            Command.Action.SEARCH_FILE, target = command.hint
                        )
                    )
                )
                Speaker.say(this, "${command.hint} app nahi mili. File ya folder dhoondhun?")
                return
            }

            if (!match.exact) {
                // v3.7: near app ke saath file/folder ka rasta bhi
                // dein — "test" par sirf Notes dena galat tha, jabki
                // user test folder ki baat kar raha tha.
                showChoices(
                    "\"${command.hint}\" exactly nahi mili. Inme se koi?",
                    spoken,
                    near.map { app ->
                        app.label to Command(Command.Action.OPEN_APP, target = app.label)
                    } + listOf(
                        "📁 Folder kholo: ${command.hint}" to Command(
                            Command.Action.OPEN_FOLDER, from = "", to = command.hint
                        ),
                        "📄 File dhoondho: ${command.hint}" to Command(
                            Command.Action.SEARCH_FILE, target = command.hint
                        )
                    )
                )
                Speaker.say(this, "${command.hint} nahi mili. Kaunsi kholun?")
                return
            }

            if (near.size > 1) {
                showChoices(
                    "Kaunsi app?", spoken,
                    near.map { app ->
                        app.label to Command(Command.Action.OPEN_APP, target = app.label)
                    }
                )
                return
            }
        }

        val needsAsk = command.needsConfirmation && Settings.confirmActions(this)

        if (!needsAsk) {
            execute(command, spoken)
            return
        }

        // For anything aimed at a person, the confirmation names the contact
        // and shows the number. "S ko call karun?" told the user nothing; the
        // number is what makes a wrong match obvious before it is dialled.
        val person = command.action in setOf(
            Command.Action.CALL, Command.Action.SEND_SMS, Command.Action.WHATSAPP
        )

        // "WhatsApp kholo" arrives here with no one named: nothing to look up,
        // so it goes straight to the ordinary confirmation.
        if (!person || command.target.isBlank()) {
            pending = command
            showConfirm(command.describe(), spoken)
            Speaker.say(this, command.describe())
            return
        }

        // v3.10, pehli parat: number bolne par pehle jaanch, phir
        // confirmation. Galat digit wala number "Haan, karo" tak
        // pahunche hi nahi, isliye WhatsApp invalid nahi bolega.
        // Panel mein digit count dikhega, taaki missing digit
        // user ko pehle hi nazar aa jaye.
        if (ContactIndex.isDigitTarget(command.target) &&
            ContactIndex.normalizedIndianNumber(command.target) == null
        ) {
            val count = ContactIndex.digitCount(command.target)
            val msg = "Number $count digit ka hai, Indian number 10 digit ka hota hai. " +
                "Ek digit kam/zyada lag raha hai, number phir se boliye — WhatsApp nahi khola."
            updatePanel(msg, spoken, listOf("Sahi number: 10 digit ka Indian number boliye."))
            Speaker.say(this, msg)
            return
        }

        updatePanel("Contact dhoondh raha hoon…", spoken)

        Thread {
            val found = runCatching { ContactIndex.candidates(this, command.target) }
                .getOrElse { emptyList() }

            main.post {
                if (found.isEmpty()) {
                    val msg = "\"${command.target}\" contacts mein nahi mila."
                    // v3.4: mic "sis" ko "syster" sun leta hai, toh poora
                    // naam bolne par match toot jata hai. Pehla naam hi
                    // kaafi hai — yeh hint panel par bhi dikhega.
                    updatePanel(
                        msg, spoken,
                        listOf("Sirf pehla naam bolkar dobara try karein, jaise \"Shaheen ko call karo\".")
                    )
                    Speaker.say(this, "$msg Sirf pehla naam bolkar dobara try karein.")
                    return@post
                }

                val verb = when (command.action) {
                    Command.Action.CALL -> "ko call karun?"
                    Command.Action.SEND_SMS -> "ko SMS bhejun?"
                    else -> "ko WhatsApp bhejun?"
                }

                // The number is carried forward so the action cannot re-run the
                // lookup and land on a different contact; the name rides along
                // in `speak` purely so messages read back nicely.
                if (found.size > 1) {
                    showChoices(
                        "Kaunsa contact?", spoken,
                        found.map { c ->
                            "${c.name} — ${c.number}" to
                                command.copy(target = c.number, speak = c.name)
                        }
                    )
                    Speaker.say(this, "Kaunsa contact?")
                    return@post
                }

                val best = found.first()
                pending = command.copy(target = best.number, speak = best.name)
                showConfirm("${best.name} — ${best.number}\n$verb", spoken)
                Speaker.say(this, "${best.name} $verb")
            }
        }.start()
    }

    /**
     * What IS installed under a similar name. Without this the user had no way
     * to tell "the app is not on this phone" from "the assistant is being
     * stupid", and spent several rounds assuming the second.
     */
    private fun installedHint(spokenName: String): List<String> {
        val all = AppIndex.load(this)
        val key = spokenName.split(" ").firstOrNull().orEmpty()

        val related =
            if (key.length < 3) emptyList()
            else all.filter { it.label.contains(key, ignoreCase = true) }

        return if (related.isEmpty()) listOf("Is phone par ${all.size} apps hain.")
        else listOf("Is naam se jo installed hai:") + related.map { "• ${it.label}" }
    }

    private fun showChoices(
        title: String,
        heard: String,
        items: List<Pair<String, Command>>
    ) {
        pending = null
        choices = items
        updatePanel(title, heard)
    }

    private fun execute(command: Command, spoken: String) {
        pending = null
        choices = emptyList()
        plan = emptyList()
        updatePanel("Kar raha hoon…", spoken)

        Thread {
            val outcome = runCatching { ActionRunner.run(this, command) }
                .getOrElse { ActionRunner.Outcome(false, "Error: ${it.localizedMessage}") }

            main.post {
                // v3.8: folder ke baad panel par "Files mein kholo"
                // ka button dein — ab yeh generic Files nahi kholta,
                // balki usi folder ka Documents URI try karta hai
                // (ActionRunner.openFolderInFiles dekhein).
                if (command.action == Command.Action.OPEN_FOLDER && outcome.ok) {
                    choices = listOf(
                        "Files mein kholo" to Command(
                            Command.Action.OPEN_FOLDER_IN_FILES,
                            from = outcome.folderPath,
                            to = outcome.message
                        )
                    )
                }
                updatePanel(outcome.message, spoken, outcome.details)
                Speaker.say(this, outcome.message)
                History.add(this, spoken, outcome.message, outcome.ok)
                Conversation.add(spoken, outcome.message)
                // v3.7 Siri-style: doosri app khulne ke baad panel
                // sirf 1.2 second mein hat jaye, taaki neeche wala
                // button turant dikhe. OPEN_FOLDER is list mein nahi
                // hai — uski list panel par hi dekhni hai, isliye
                // woh tab tak rehta hai jab tak user band na kare.
                // v3.10: OPEN_FOLDER_IN_FILES, MOVE_FILE aur COPY_FILE
                // bhi isi list mein hain — Files khulne ke baad panel
                // 4 second mein hat jata tha aur bubble bhi nahi dikhta
                // tha, isliye user ko bahar aakar dobara bolna padta
                // tha. Ab panel tab tak rahega jab tak user band na
                // kare, taaki Files ke andar se hi "Phir se boliye"
                // dabakar dobara bola ja sake.
                if (command.action in setOf(
                        Command.Action.OPEN_APP,
                        Command.Action.CALL,
                        Command.Action.SEND_SMS,
                        Command.Action.WHATSAPP
                    )
                ) {
                    val shownPanel = panel
                    main.postDelayed({
                        if (panel != null && panel === shownPanel) removePanelQuietly()
                    }, 1200)
                } else if (command.action !in setOf(
                        Command.Action.OPEN_FOLDER,
                        Command.Action.OPEN_FOLDER_IN_FILES,
                        Command.Action.LIST_FILES,
                        Command.Action.SEARCH_FILE,
                        Command.Action.MOVE_FILE,
                        Command.Action.COPY_FILE
                    )
                ) {
                    // v3.8: simple kaam ke baad panel 4 second mein
                    // khud hat jaye, taaki user ko wapas jaane ke
                    // liye panel band karke dobara chalu na karna pade.
                    // Folder/list/search ki list padhni hoti hai,
                    // isliye woh tab tak rehte hain jab tak user band
                    // na kare ya bahar tap na kare.
                    val shownPanel = panel
                    main.postDelayed({
                        if (panel != null && panel === shownPanel) removePanelQuietly()
                    }, 4000)
                }
            }
        }.start()
    }

    /**
     * Runs the steps in order and stops at the first failure, so a bad second
     * step cannot leave half the work done with no word about it.
     */
    private fun runPlan(steps: List<Command>, spoken: String) {
        pending = null
        choices = emptyList()
        plan = emptyList()

        Thread {
            val lines = mutableListOf<String>()
            var failed = false

            for ((index, step) in steps.withIndex()) {
                val shown = lines.toList()
                main.post { updatePanel("Step ${index + 1} / ${steps.size}…", spoken, shown) }

                val outcome = runCatching { ActionRunner.run(this, step) }
                    .getOrElse { ActionRunner.Outcome(false, "Error: ${it.localizedMessage}") }

                lines.add(if (outcome.ok) "OK — ${outcome.message}" else "RUKA — ${outcome.message}")
                lines.addAll(outcome.details)

                if (!outcome.ok) {
                    failed = true
                    break
                }
            }

            val done = if (failed) steps.size - 1 else steps.size
            val summary =
                if (failed) "Step ${lines.count { it.startsWith("OK") } + 1} par ruk gaya."
                else "Saare $done kaam ho gaye."

            val shown = lines.toList()
            main.post {
                updatePanel(summary, spoken, shown)
                Speaker.say(this, summary)
                History.add(this, spoken, summary, !failed)
                Conversation.add(spoken, summary + " " + shown.joinToString("; "))
            }
        }.start()
    }

    // --------------------------------------------------------------- panel

    private fun showPanel(status: String, heard: String) {
        removePanel()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        // v3.8 Siri-style, sahi wala: window WRAP_CONTENT hai, aur
        // ScrollView ki height updatePanel() mein tabhi cap hoti hai
        // jab list lambi ho. v3.7 mein ScrollView 45% par fix thi,
        // chahe details khaali ho — isliye panel khaali dark box ban
        // kar bada dikhta tha, peeche ka screen thoda hi dikhta tha.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        view.findViewById<View>(R.id.panelClose).setOnClickListener { removePanel() }
        view.findViewById<View>(R.id.panelAgain).setOnClickListener { beginListening() }

        view.findViewById<View>(R.id.panelHideMic).setOnClickListener {
            Settings.setShowBubble(this, false)
            hideBubble()
            startAsForeground()
            removePanel()
            toast("Mic hat gaya. Notification mein \"Suno\" se wapas bula sakte hain.")
        }

        view.findViewById<View>(R.id.panelYes).setOnClickListener {
            val heard = lastHeard
            val steps = plan
            if (steps.isNotEmpty()) runPlan(steps, heard)
            else pending?.let { execute(it, heard) }
        }
        view.findViewById<View>(R.id.panelNo).setOnClickListener {
            pending = null
            plan = emptyList()
            updatePanel("Theek hai, rehne diya.", "")
            // v3.8: "Nahi" ke baad panel atka na rahe — 2.5 second
            // mein khud hat jaye, taaki peeche ki app dikhe.
            val shownPanel = panel
            main.postDelayed({
                if (panel != null && panel === shownPanel) removePanelQuietly()
            }, 2500)
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                removePanel(); true
            } else false
        }

        windowManager.addView(view, params)
        panel = view

        updatePanel(status, heard)
    }

    private fun currentHeard(view: View): String =
        view.findViewById<TextView>(R.id.panelHeard).text?.toString().orEmpty()

    private fun updatePanel(
        status: String,
        heard: String,
        details: List<String> = emptyList()
    ) {
        if (heard.isNotBlank()) lastHeard = heard

        val view = panel ?: run { showPanel(status, heard); return }

        view.findViewById<TextView>(R.id.panelStatus).text = status

        view.findViewById<TextView>(R.id.panelHeard).apply {
            visibility = if (heard.isBlank()) View.GONE else View.VISIBLE
            // The parser's name rides along with what was heard, so it is
            // always obvious whether the model was consulted at all.
            val tag = CommandParser.lastSource
            text = if (tag.isEmpty()) heard else "$heard   ·   $tag"
        }

        view.findViewById<TextView>(R.id.panelDetails).apply {
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            text = details.joinToString("\n")
        }

        // v3.8: ScrollView ki height details ke hisaab se. Khaali
        // ya chhoti list par WRAP_CONTENT — panel chhota hi rahega.
        // Lambi list (8 se zyada) par 45% cap, taaki buttons kabhi
        // screen se bahar na jayein aur list scroll ho sake.
        view.findViewById<View>(R.id.panelScroll)?.let { scroll ->
            scroll.layoutParams = scroll.layoutParams.apply {
                height = if (details.size > 8) (screenH * 0.45f).toInt()
                else WindowManager.LayoutParams.WRAP_CONTENT
            }
            scroll.requestLayout()
        }

        renderChoices(view, lastHeard)

        val asking = pending != null || plan.isNotEmpty()
        val busy = asking || choices.isNotEmpty()
        view.findViewById<View>(R.id.confirmRow).visibility =
            if (asking) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.panelAgain).visibility =
            if (busy) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.panelHideMic).visibility =
            if (busy) View.GONE else View.VISIBLE
    }

    private fun renderChoices(view: View, heard: String) {
        val row = view.findViewById<LinearLayout>(R.id.optionsRow)
        row.removeAllViews()

        if (choices.isEmpty()) {
            row.visibility = View.GONE
            return
        }

        row.visibility = View.VISIBLE

        choices.forEach { (label, command) ->
            // v3.8 Siri-style chips: full-width green button nahi,
            // chhota chip — text jitna hi chauda, padding kam.
            val button = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2E7D52.toInt())
                setPadding(20, 14, 20, 14)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                setOnClickListener { execute(command, heard) }
            }
            row.addView(button)
        }
    }

    private fun showConfirm(
        question: String,
        heard: String,
        details: List<String> = emptyList()
    ) {
        updatePanel(question, heard, details)
    }

    private fun removePanel() {
        Speaker.stop()
        pending = null
        choices = emptyList()
        plan = emptyList()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    // removePanel() jaisa, lekin bolna band kiye bina — auto-dismiss
    // ke liye, taaki outcome ki awaaz poori sunai de.
    private fun removePanelQuietly() {
        pending = null
        choices = emptyList()
        plan = emptyList()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    // ------------------------------------------------------------ teardown

    private fun shutdown() {
        isRunning = false
        speech.stop()
        Speaker.shutdown()
        removePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
