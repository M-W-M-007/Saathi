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
            .setContentTitle("Saathi tayyar hai")
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
        pending = null
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

        // Parsing may call the network, so it never runs on the main thread.
        Thread {
            val command = CommandParser.parse(this, spoken)
            main.post { onParsed(spoken, command) }
        }.start()
    }

    private fun onParsed(spoken: String, command: Command) {
        if (command.action == Command.Action.UNKNOWN) {
            val msg = "Yeh samajh nahi aaya."
            updatePanel(msg, spoken)
            Speaker.say(this, msg)
            return
        }

        val needsAsk = command.needsConfirmation && Settings.confirmActions(this)

        if (needsAsk) {
            pending = command
            showConfirm(command, spoken)
            Speaker.say(this, command.describe())
        } else {
            execute(command, spoken)
        }
    }

    private fun execute(command: Command, spoken: String) {
        pending = null
        updatePanel("Kar raha hoon…", spoken)

        Thread {
            val outcome = runCatching { ActionRunner.run(this, command) }
                .getOrElse { ActionRunner.Outcome(false, "Error: ${it.localizedMessage}") }

            main.post {
                updatePanel(outcome.message, spoken, outcome.details)
                Speaker.say(this, outcome.message)
                History.add(this, spoken, outcome.message, outcome.ok)
            }
        }.start()
    }

    // --------------------------------------------------------------- panel

    private fun showPanel(status: String, heard: String) {
        removePanel()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        // Fixed height: a long file listing must never grow the panel until the
        // buttons are pushed off-screen and nothing can be dismissed.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenH * 0.45f).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        view.findViewById<View>(R.id.panelClose).setOnClickListener { removePanel() }
        view.findViewById<View>(R.id.panelAgain).setOnClickListener { beginListening() }

        view.findViewById<View>(R.id.panelYes).setOnClickListener {
            pending?.let { execute(it, currentHeard(view)) }
        }
        view.findViewById<View>(R.id.panelNo).setOnClickListener {
            pending = null
            updatePanel("Theek hai, rehne diya.", "")
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
        val view = panel ?: run { showPanel(status, heard); return }

        view.findViewById<TextView>(R.id.panelStatus).text = status

        view.findViewById<TextView>(R.id.panelHeard).apply {
            visibility = if (heard.isBlank()) View.GONE else View.VISIBLE
            text = heard
        }

        view.findViewById<TextView>(R.id.panelDetails).apply {
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            text = details.joinToString("\n")
        }

        val confirming = pending != null
        view.findViewById<View>(R.id.confirmRow).visibility =
            if (confirming) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.panelAgain).visibility =
            if (confirming) View.GONE else View.VISIBLE
    }

    private fun showConfirm(command: Command, heard: String) {
        updatePanel(command.describe(), heard)
    }

    private fun removePanel() {
        Speaker.stop()
        pending = null
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
