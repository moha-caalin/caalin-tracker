package com.caalin.tracker

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class SendResult(val status: String, val walletName: String, val amount: Double, val phone: String, val note: String = "",
    val walletId: Long = 0, val categoryId: Long? = null)

object Ussd {
    fun enabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, UssdService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun code(template: String, number: String, amount: String) =
        template.replace("{NUMBER}", number).replace("{AMOUNT}", amount)
}

/** A send in progress with auto-PIN. The PIN lives only in memory and only until it is typed. */
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
    var typedByHand = false
    val transcript = StringBuilder()
    var lastText = ""
    var lastResult: SendResult? = null
    var categoryId: Long? = null
    var orderId: Long? = null

    fun start(w: Wallet, phone: String, amount: Double, pin: String, category: Long?, order: Long? = null) {
        clear()
        session++
        walletId = w.id; walletName = w.name; this.phone = phone; this.amount = amount; this.pin = pin
        rules = w.ruleList(); success = w.successList(); fail = w.failList()
        armed = !w.hasPinRule()
        typedByHand = false
        categoryId = category
        orderId = order
        active = true
        val s = session
        handler.postDelayed({ if (active && session == s) finish(Status.UNKNOWN) }, TIMEOUT_MS)
    }

    fun finish(status: String, skipEnrich: Boolean = false) {
        if (!active) return
        val text = transcript.toString().trim().ifEmpty { "No USSD reply was read." }
        val last = lastText
        Db.addTx(Tx(
            walletId = walletId, walletName = walletName, direction = OUT, phone = phone, name = "", amount = amount,
            status = status, response = text, createdAt = System.currentTimeMillis(), source = "APP",
            categoryId = categoryId, orderId = orderId,
        ))
        lastResult = SendResult(status, walletName, amount, phone,
            if (typedByHand) "The app couldn't type the PIN on this screen, so you typed it." else "", walletId, categoryId)
        clear()
        // The success pop-up has the name and balance: attach them.
        if (status == Status.COMPLETED && !skipEnrich && last.isNotBlank()) SmsStore.ingest(last, System.currentTimeMillis())
    }

    fun clear() {
        active = false
        pin = null
        fired.clear()
        transcript.setLength(0)
        lastText = ""
    }
}

/** A send without auto-PIN: the user types the PIN and confirms in the app. */
object Manual {
    var wallet: Wallet? = null
    var phone = ""
    var amount = 0.0
    var dialed = false
    var at = 0L
    var categoryId: Long? = null
    var orderId: Long? = null

    fun set(w: Wallet, phone: String, amount: Double, category: Long?, order: Long? = null) {
        wallet = w; this.phone = phone; this.amount = amount; dialed = true; at = System.currentTimeMillis()
        categoryId = category; orderId = order
    }

    fun clear() { wallet = null; dialed = false }
}

class UssdService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var lastIdle = ""

    private val positiveWords = listOf("send", "reply", "submit", "ok", "yes", "confirm", "continue", "dir", "gudbi", "haa")
    private val dismissWords = listOf("ok", "close", "dismiss", "cancel", "xir", "done")

    private val tick = object : Runnable {
        override fun run() {
            Focus.flush(this@UssdService)
            Focus.check(this@UssdService)
            h.postDelayed(this, 20_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Db.init(this)
        Focus.init(this)
        h.removeCallbacks(tick)
        h.postDelayed(tick, 20_000L)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (stateChange && pkg.isNotEmpty()) Focus.onWindow(this, pkg, event.className?.toString() ?: "")
        if (pkg == packageName) return
        // Outside a send only new pop-ups matter; skip the constant content changes of other apps (saves battery).
        if (!Pending.active && !stateChange) return
        val root = rootInActiveWindow ?: event.source ?: return

        val texts = ArrayList<String>()
        var hasInput = false
        walk(root) { n ->
            if (n.isEditable) hasInput = true
            else n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) texts.add(it) }
        }
        val screen = texts.joinToString("\n")
        if (screen.isBlank()) return

        if (!Pending.active) {
            idleScreen(pkg, screen, texts.size, hasInput)
            return
        }
        if (screen == Pending.lastText) return
        Pending.lastText = screen
        Pending.transcript.append(screen).append("\n— — —\n")
        val low = screen.lowercase()

        if (Pending.fail.any { low.contains(it) } && (Pending.armed || !hasInput)) {
            dismiss(); Pending.finish(Status.FAILED); return
        }
        if (Pending.armed && Pending.success.any { low.contains(it) }) {
            dismiss(); Pending.finish(Status.COMPLETED); return
        }
        if (!hasInput) return

        // Each rule answers at most once per send, so a wrong PIN is never retried.
        Pending.rules.forEachIndexed { i, (keyword, answer) ->
            if (i !in Pending.fired && low.contains(keyword)) {
                Pending.fired.add(i)
                val value = answer.replace("{PIN}", Pending.pin ?: "")
                if (answer.contains("{PIN}")) { Pending.pin = null; Pending.armed = true }
                if (value.isNotEmpty()) typeAndSend(value)
                return
            }
        }
    }

    /** Outside a send: a pop-up like "Hormuud Message: [-EVCPLUS-] … ka heshay …" is saved too. */
    private fun idleScreen(pkg: String, screen: String, count: Int, hasInput: Boolean) {
        if (hasInput || count > 5 || screen == lastIdle) return
        val p = pkg.lowercase()
        if (p.contains("mms") || p.contains("messag") || p.contains("sms") || p.contains("launcher")) return
        lastIdle = screen
        val w = SmsStore.walletFor(screen) ?: return
        val low = screen.lowercase()
        if (w.smsTags().sumOf { tag -> low.windowed(tag.length).count { it == tag } } != 1) return
        SmsStore.ingest(screen, System.currentTimeMillis())
    }

    override fun onInterrupt() {}

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, f) }
    }

    private fun input(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { if (found == null && it.isEditable) found = it }
        return found
    }

    private fun filled(n: AccessibilityNodeInfo, value: String) = (n.text?.toString() ?: "").length == value.length

    /** Types the answer, checks it is really in the box, and only then presses Send. */
    private fun typeAndSend(value: String) {
        val field = rootInActiveWindow?.let { input(it) } ?: return
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
        h.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            val f = input(r)
            if (f != null && filled(f, value)) { submit(); return@postDelayed }
            // Second way: paste it.
            if (f != null) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("", value))
                f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                f.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                h.postDelayed({
                    clearClipboard(cm)
                    val r2 = rootInActiveWindow ?: return@postDelayed
                    val f2 = input(r2)
                    if (f2 != null && filled(f2, value)) submit()
                    else Pending.typedByHand = true // leave the box for the user; never send an empty PIN
                }, 500)
            } else Pending.typedByHand = true
        }, 450)
    }

    private fun clearClipboard(cm: ClipboardManager) {
        try {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    private fun submit() {
        h.postDelayed({ rootInActiveWindow?.let { click(it, positiveWords, listOf("android:id/button1")) } }, 250)
    }

    private fun dismiss() {
        h.postDelayed({
            val r = rootInActiveWindow
            if (r == null || !click(r, dismissWords, listOf("android:id/button2", "android:id/button1"))) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 900)
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
