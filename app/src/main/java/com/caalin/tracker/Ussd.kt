package com.caalin.tracker

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class SendResult(val status: String, val walletName: String, val amount: Double, val phone: String)

object Ussd {
    fun enabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, UssdService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun code(template: String, number: String, amount: String) =
        template.replace("{NUMBER}", number).replace("{AMOUNT}", amount)
}

/** A send that is in progress with auto-PIN. The PIN lives only in memory and only until it is typed. */
object Pending {
    private const val TIMEOUT_MS = 120_000L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var active = false
    private var session = 0
    var walletId = 0L; var walletName = ""; var phone = ""; var amount = 0.0
    var pin: String? = null
    var rules: List<Pair<String, String>> = emptyList()
    var success: List<String> = emptyList()
    var fail: List<String> = emptyList()
    val fired = HashSet<Int>()
    var armed = false
    val transcript = StringBuilder()
    var lastText = ""
    var lastResult: SendResult? = null

    fun start(w: Wallet, phone: String, amount: Double, pin: String) {
        clear()
        session++
        walletId = w.id; walletName = w.name; this.phone = phone; this.amount = amount; this.pin = pin
        rules = w.ruleList(); success = w.successList(); fail = w.failList()
        armed = !w.hasPinRule()
        active = true
        val s = session
        handler.postDelayed({ if (active && session == s) finish(Status.UNKNOWN) }, TIMEOUT_MS)
    }

    fun finish(status: String) {
        if (!active) return
        val text = transcript.toString().trim().ifEmpty { "No USSD reply was read." }
        Db.addTx(Tx(0, walletId, walletName, phone, amount, status, text, System.currentTimeMillis()))
        lastResult = SendResult(status, walletName, amount, phone)
        clear()
    }

    fun clear() {
        active = false
        pin = null
        fired.clear()
        transcript.setLength(0)
        lastText = ""
    }
}

/** A send without auto-PIN: the user types the PIN and tells the app whether it completed. */
object Manual {
    var wallet: Wallet? = null
    var phone = ""
    var amount = 0.0
    var dialed = false

    fun set(w: Wallet, phone: String, amount: Double) {
        wallet = w; this.phone = phone; this.amount = amount; dialed = true
    }

    fun clear() { wallet = null; dialed = false }
}

class UssdService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())

    private val positiveWords = listOf("send", "reply", "submit", "ok", "yes", "confirm", "continue", "dir", "gudbi", "haa")
    private val dismissWords = listOf("ok", "close", "dismiss", "cancel", "xir", "done")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Pending.active) return
        if (event.packageName?.toString() == packageName) return
        val root = rootInActiveWindow ?: event.source ?: return

        val texts = ArrayList<String>()
        var hasInput = false
        walk(root) { n ->
            if (n.isEditable) hasInput = true
            else n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) texts.add(it) }
        }
        val screen = texts.joinToString("\n")
        if (screen.isBlank() || screen == Pending.lastText) return
        Pending.lastText = screen
        Pending.transcript.append(screen).append("\n— — —\n")
        val low = screen.lowercase()

        // Final answers
        if (Pending.fail.any { low.contains(it) } && (Pending.armed || !hasInput)) {
            dismiss(); Pending.finish(Status.FAILED); return
        }
        if (Pending.armed && Pending.success.any { low.contains(it) }) {
            dismiss(); Pending.finish(Status.COMPLETED); return
        }
        if (!hasInput) return

        // Prompts: each rule answers at most once per send, so a wrong PIN is never retried.
        Pending.rules.forEachIndexed { i, (keyword, answer) ->
            if (i !in Pending.fired && low.contains(keyword)) {
                Pending.fired.add(i)
                val value = answer.replace("{PIN}", Pending.pin ?: "")
                if (answer.contains("{PIN}")) { Pending.pin = null; Pending.armed = true }
                reply(root, value)
                return
            }
        }
    }

    override fun onInterrupt() {}

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, f) }
    }

    private fun reply(root: AccessibilityNodeInfo, value: String) {
        var input: AccessibilityNodeInfo? = null
        walk(root) { if (input == null && it.isEditable) input = it }
        val field = input ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        h.postDelayed({ rootInActiveWindow?.let { click(it, positiveWords, listOf("android:id/button1")) } }, 350)
    }

    private fun dismiss() {
        h.postDelayed({
            val r = rootInActiveWindow
            if (r == null || !click(r, dismissWords, listOf("android:id/button2", "android:id/button1"))) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 700)
    }

    private fun click(root: AccessibilityNodeInfo, words: List<String>, ids: List<String>): Boolean {
        for (w in words) {
            var found: AccessibilityNodeInfo? = null
            walk(root) {
                if (found == null && it.isClickable && it.text?.toString()?.trim()?.lowercase() == w) found = it
            }
            found?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
        }
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
            }
        }
        return false
    }
}
