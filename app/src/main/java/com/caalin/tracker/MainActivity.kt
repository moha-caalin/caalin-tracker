package com.caalin.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

class MainActivity : BaseActivity() {
    private var tab = 0
    private var period = 0 // 0 today, 1 month, 2 all
    private var histDir: String? = null
    private var histWallet: Long? = null
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout

    companion object { private var imported = false }

    private class Check(val title: String, val detail: String, val ok: Boolean, val action: String, val run: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox()
        content = FrameLayout(this)
        root.add(content, MATCH, 0, weight = 1f)
        nav = hbox().apply {
            setBackgroundColor(WHITE)
            elevation = dp(8).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        root.add(nav)
        setContentView(root)
    }

    override fun onUnlockedResume() {
        render()
        if (!imported && SmsStore.canRead(this)) importSms(false)
    }

    private fun importSms(showResult: Boolean) {
        imported = true
        Thread {
            val n = SmsStore.importInbox(this)
            runOnUiThread {
                if (showResult) toast(if (n == 0) "No new money messages found" else "Added $n money messages")
                if (n > 0 || showResult) render()
            }
        }.start()
    }

    private fun render() {
        drawNav()
        content.removeAllViews()
        val page = when (tab) { 0 -> home(); 1 -> history(); else -> settings() }
        content.addView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun drawNav() {
        nav.removeAllViews()
        listOf(Triple("Home", R.drawable.ic_home, 0), Triple("History", R.drawable.ic_list, 1),
            Triple("Settings", R.drawable.ic_tune, 2)).forEach { (label, res, i) ->
            val sel = tab == i
            val item = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            val ic = icon(res, if (sel) BRAND else MUTED, 24)
            val holder = FrameLayout(this).apply {
                setPadding(dp(18), dp(4), dp(18), dp(4))
                background = if (sel) rounded(GREEN_BG, dp(16).toFloat()) else null
                addView(ic)
            }
            item.add(holder, WRAP)
            item.add(text(label, 12f, sel, if (sel) BRAND else MUTED), WRAP, top = 2)
            item.setOnClickListener { tab = i; render() }
            nav.add(item, 0, weight = 1f)
        }
    }

    // ---------------- Home ----------------

    private fun home(): View {
        val page = vbox()

        val head = vbox().apply {
            setPadding(dp(20), dp(18), dp(20), dp(26))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BRAND, DEEP)).apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        val top = hbox()
        val titles = vbox()
        titles.add(text("Caalin Tracker", 22f, true, WHITE))
        titles.add(text(dayText(System.currentTimeMillis()), 13f, color = 0xCCFFFFFF.toInt()), top = 2)
        top.add(titles, 0, weight = 1f)
        head.add(top)

        val pills = hbox()
        listOf("Today", "This month", "All").forEachIndexed { i, s ->
            pills.add(pill(s, period == i, onDark = true) { period = i; render() }, WRAP, end = 8)
        }
        head.add(pills, top = 16)

        val since = when (period) { 0 -> startOfDay(); 1 -> startOfMonth(); else -> 0L }
        val t = Db.totals(null, since)
        val nums = hbox()
        fun col(label: String, value: String, iconRes: Int, tint: Int): View {
            val c = vbox(14).apply { background = rounded(0x1FFFFFFF, dp(16).toFloat()) }
            val l = hbox()
            l.add(badge(WHITE, 26, iconRes, tint), dp(26), dp(26), end = 8)
            l.add(text(label, 13f, color = 0xDDFFFFFF.toInt()), WRAP)
            c.add(l)
            c.add(text(value, 24f, true, WHITE), top = 8)
            return c
        }
        nums.add(col("Received", "+" + money(t[0]), R.drawable.ic_in, GREEN), 0, weight = 1f, end = 10)
        nums.add(col("Sent", "−" + money(t[1]), R.drawable.ic_out, RED), 0, weight = 1f)
        head.add(nums, top = 18)
        val net = t[0] - t[1]
        head.add(text("Net ${if (net < 0) "−" else "+"}${money(net)}", 14f, true, 0xEEFFFFFF.toInt()), top = 12)
        page.add(head)

        val body = vbox(16)
        checklistCard(onlyProblems = true)?.let { body.add(it) }

        body.add(sectionTitle("Send money"))
        val wallets = Db.wallets()
        wallets.chunked(2).forEach { pair ->
            val row = hbox()
            pair.forEachIndexed { i, w -> row.add(walletTile(w), 0, weight = 1f, end = if (i == 0) 12 else 0) }
            if (pair.size == 1) row.add(View(this), 0, weight = 1f)
            body.add(row, top = if (body.childCount > 2) 12 else 0)
        }
        if (wallets.isEmpty()) body.add(text("No money types yet — add one in Settings.", 14f, color = MUTED))

        body.add(sectionTitle("Recent activity", "See all") { tab = 1; render() })
        val recent = Db.txs(null, null, 6)
        if (recent.isEmpty()) body.add(emptyCard("No transactions yet. Money you send or receive shows up here."))
        else body.add(listCard(recent.map { tx -> txRow(tx) { showTx(tx) { render() } } }))
        page.add(body)
        return page
    }

    private fun walletTile(w: Wallet): View {
        val c = card()
        val v = vbox(16)
        val top = hbox()
        top.add(badge(w.color, 42, letter = w.name.take(1).uppercase()), dp(42), dp(42))
        top.add(View(this), 0, weight = 1f)
        if (w.hasPin) top.add(text("🔒", 14f), WRAP)
        v.add(top)
        v.add(text(w.name, 16f, true), top = 12)
        val bal = Db.latestBalance(w.id)
        v.add(text(when {
            w.template.isBlank() -> "Tap to set up"
            bal != null -> "Balance ${money(bal)}"
            else -> "Balance —"
        }, 13f, color = if (w.template.isBlank()) AMBER else MUTED), top = 2)
        v.add(text(if (w.template.isBlank()) "Set up →" else "Send →", 14f, true, BRAND), top = 12)
        c.addView(v)
        c.setOnClickListener {
            val target = if (w.template.isBlank()) WalletEditActivity::class.java else SendActivity::class.java
            startActivity(Intent(this, target).putExtra("id", w.id))
        }
        return c
    }

    private fun emptyCard(msg: String): View = card().apply {
        addView(text(msg, 14f, color = MUTED).apply { setPadding(dp(18), dp(22), dp(18), dp(22)); gravity = Gravity.CENTER })
    }

    // ---------------- History ----------------

    private fun history(): View {
        val page = vbox(16)
        page.add(text("History", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })

        val dirs = hbox()
        listOf<Pair<String, String?>>("All" to null, "Received" to IN, "Sent" to OUT).forEach { (label, d) ->
            dirs.add(pill(label, histDir == d) { histDir = d; render() }, WRAP, end = 8)
        }
        page.add(dirs)

        val wallets = Db.wallets()
        if (wallets.size > 1) {
            val wr = hbox()
            wr.add(pill("All wallets", histWallet == null) { histWallet = null; render() }, WRAP, end = 8)
            wallets.forEach { w -> wr.add(pill(w.name, histWallet == w.id) { histWallet = w.id; render() }, WRAP, end = 8) }
            page.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wr) }, top = 10)
        }

        val txs = Db.txs(histWallet, histDir)
        val done = txs.filter { it.status == Status.COMPLETED }
        val inn = done.filter { it.direction == IN }.sumOf { it.amount }
        val out = done.filter { it.direction == OUT }.sumOf { it.amount }
        val sum = card()
        val s = hbox().apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }
        fun stat(label: String, value: String, color: Int): View = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            add(text(label, 12.5f, color = MUTED), WRAP)
            add(text(value, 18f, true, color), WRAP, top = 4)
        }
        s.add(stat("Received", "+" + money(inn), GREEN), 0, weight = 1f)
        s.add(stat("Sent", "−" + money(out), RED), 0, weight = 1f)
        val net = inn - out
        s.add(stat("Net", (if (net < 0) "−" else "+") + money(net), INK), 0, weight = 1f)
        sum.addView(s)
        page.add(sum, top = 14)

        val notDone = txs.count { it.status != Status.COMPLETED }
        if (notDone > 0) page.add(text("$notDone not confirmed or failed — not counted in totals.", 12.5f, color = MUTED)
            .apply { setPadding(dp(4), dp(8), 0, 0) })

        page.add(View(this), MATCH, dp(12))
        if (txs.isEmpty()) page.add(emptyCard("Nothing here yet."))
        else page.add(listCard(txs.map { tx -> txRow(tx) { showTx(tx) { render() } } }))
        return page
    }

    // ---------------- Settings ----------------

    private fun settings(): View {
        val page = vbox(16)
        page.add(text("Settings", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(4)) })

        page.add(sectionTitle("Setup"))
        checklistCard(onlyProblems = false)?.let { page.add(it) }

        page.add(sectionTitle("Money types"))
        val rows = Db.wallets().map { w ->
            val r = hbox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
            r.add(badge(w.color, 40, letter = w.name.take(1).uppercase()), dp(40), dp(40), end = 14)
            val m = vbox()
            m.add(text(w.name, 15f, true))
            m.add(text(w.template.ifBlank { "USSD code not set" }, 12.5f, color = if (w.template.isBlank()) AMBER else MUTED), top = 2)
            m.add(text(if (w.hasPin) "🔒 PIN saved" else "No PIN saved", 12f, color = if (w.hasPin) BRAND else MUTED), top = 2)
            r.add(m, 0, weight = 1f)
            r.add(text("Edit", 14f, true, BRAND), WRAP)
            r.setOnClickListener { startActivity(Intent(this, WalletEditActivity::class.java).putExtra("id", w.id)) }
            r as View
        }
        if (rows.isNotEmpty()) page.add(listCard(rows))
        page.add(button("+ Add money type", outlined = true) {
            startActivity(Intent(this, WalletEditActivity::class.java).putExtra("id", -1L))
        }, top = 10)

        page.add(sectionTitle("Messages"))
        val sms = card()
        val sv = vbox(16)
        sv.add(text("Money SMS are read automatically when they arrive — money you receive and money you send, " +
            "even when you send without this app.", 14f, color = MUTED))
        sv.add(button("Read past messages (90 days)") {
            if (SmsStore.canRead(this)) importSms(true) else requestSms()
        }, top = 12)
        sms.addView(sv)
        page.add(sms)

        page.add(text("Caalin Tracker 1.1", 12f, color = MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8))
        })
        return page
    }

    // ---------------- Setup checklist ----------------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestSms() =
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 21)

    private fun checks(): List<Check> {
        val wallets = Db.wallets()
        val pm = getSystemService(PowerManager::class.java)
        return listOf(
            Check("Phone permission", "Needed to dial the USSD code.", granted(Manifest.permission.CALL_PHONE), "Allow") {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 20)
            },
            Check("Read money SMS", "Counts money you receive and send.",
                granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS), "Allow") { requestSms() },
            Check("Auto-PIN", "Turn on \"Caalin Tracker auto-PIN\". If the switch is greyed out: Settings › Apps › " +
                "Caalin Tracker › ⋮ › Allow restricted settings, then try again.", Ussd.enabled(this), "Open") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Check("PIN saved", "Save your PIN once so the app can type it after your fingerprint.",
                wallets.any { it.hasPin }, "Set PIN") {
                val w = wallets.firstOrNull { it.template.isNotBlank() } ?: wallets.firstOrNull()
                startActivity(Intent(this, WalletEditActivity::class.java).putExtra("id", w?.id ?: -1L))
            },
            Check("Keep running", "Stops the phone from closing auto-PIN and SMS reading to save battery.",
                pm.isIgnoringBatteryOptimizations(packageName), "Allow") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )
    }

    private fun checklistCard(onlyProblems: Boolean): View? {
        val all = checks()
        val shown = if (onlyProblems) all.filter { !it.ok } else all
        if (shown.isEmpty()) return null
        val rows = shown.map { c ->
            val r = hbox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
            r.add(text(if (c.ok) "✓" else "!", 15f, true, if (c.ok) GREEN else AMBER).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (c.ok) GREEN_BG else AMBER_BG) }
            }, dp(34), dp(34), end = 14)
            val m = vbox()
            m.add(text(c.title, 15f, true))
            if (!c.ok) m.add(text(c.detail, 12.5f, color = MUTED), top = 2)
            r.add(m, 0, weight = 1f)
            if (!c.ok) r.add(text(c.action, 14f, true, WHITE).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(BRAND, dp(18).toFloat())
                setOnClickListener { c.run() }
            }, WRAP)
            r as View
        }
        val wrap = vbox()
        if (onlyProblems) wrap.add(text("Finish setup", 18f, true).apply { setPadding(dp(4), dp(10), 0, dp(8)) })
        wrap.add(listCard(rows))
        return wrap
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && SmsStore.canRead(this)) importSms(true) else render()
    }
}
