package com.waz.saathi

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types into other apps and taps their buttons.
 *
 * This exists only because Android gives no other way: an ordinary app cannot
 * put text into another app's field or press its send button. It is optional —
 * opening apps, calling, SMS and all the file work run without it — and the
 * app says so rather than demanding it up front.
 *
 * It deliberately does nothing on its own. Nothing is logged, nothing is read
 * unless an action asks for it, and the only triggers are explicit calls from
 * the rest of the app.
 */
class SaathiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: SaathiAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** Puts [text] into whatever field currently has focus. */
        fun typeIntoFocusedField(text: String): Boolean =
            instance?.typeInternal(text) ?: false

        /**
         * Taps a send button in the app on screen. Used right after a WhatsApp
         * chat is opened with the message prefilled; the view takes a moment to
         * appear, so this retries briefly rather than firing once and missing.
         */
        fun requestSend() {
            instance?.trySendRepeatedly()
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Nothing is done per-event: the service acts only when asked to. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ---------------------------------------------------------------- type

    private fun typeInternal(text: String): Boolean {
        val focused = findFocusedEditable() ?: return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existingText(focused) + text
            )
        }

        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return ok
    }

    private fun existingText(node: AccessibilityNodeInfo): String =
        node.text?.toString().orEmpty()

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }

        // Some apps do not report input focus; fall back to the first editable
        // field on screen, which in a chat app is the message box.
        val root = rootInActiveWindow ?: return null
        return firstEditable(root)
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node

        for (i in 0 until node.childCount) {
            firstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- send

    // v3.3: pehle sirf 9 baar 400ms par try hota tha (kul ~3.6 sec).
    // WhatsApp ka chat aksar usse der se khulta hai, isliye tap
    // chat khulne se pehle hi khatam ho jata tha aur message sirf
    // likha hua reh jata tha. Ab 30 baar 500ms par (kul ~15 sec)
    // try hota hai, aur sirf tab tap hota hai jab saamne wali
    // window sach mein WhatsApp/Business ho — kisi aur app ka
    // button galti se nahi dabega.
    private fun trySendRepeatedly(attempt: Int = 0) {
        if (attempt > 30) return
        if (tapSend()) return
        main.postDelayed({ trySendRepeatedly(attempt + 1) }, 500)
    }

    private fun tapSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return false

        // WhatsApp's send button, by id first — far more reliable than text,
        // which changes with the app's language.
        val ids = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )

        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrNull().orEmpty()
            nodes.firstOrNull { it.isClickable || it.isEnabled }?.let { node ->
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
        }

        for (label in listOf("Send", "भेजें")) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(label) }
                .getOrNull().orEmpty()
            nodes.firstOrNull { it.isClickable }?.let { node ->
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }

        // Icon-only send button: na text hota hai, na hamesha wahi id.
        // Poora tree dekh kar send wali cheez dhoondhein — id mein
        // "send" ho, ya contentDescription mein Send/भेजें likha ho.
        return tapSendByTree(root)
    }

    private fun tapSendByTree(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val viewId = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val isSend = viewId.contains("send", ignoreCase = true) ||
            desc.equals("Send", ignoreCase = true) ||
            desc.contains("भेजें")
        if (isSend && node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
            if (child != null && tapSendByTree(child)) return true
        }
        return false
    }
}
