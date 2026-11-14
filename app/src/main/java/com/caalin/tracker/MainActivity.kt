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
import androidx.appcompat.app.AlertDialog

class MainActivity : BaseActivity() {
    var tab = 0
    private var period = 0 // 0 today, 1 month, 2 all
    private var histDir: String? = null
    private var histWallet: Long? = null
    private var histNeeds = false
    var histQuery = ""
    private var histList: LinearLayout? = null
    var reportMonth = 0 // 0 = this month, -1 last month, ... ; 1 = all since start
    var reportAll = false
    var notesSeg = 0
    var peopleQuery = ""
    var peopleFilter: String? = null
    private val scrollY = IntArray(6)
    private var scroller: ScrollView? = null
    private var shownTab = -1
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
        Rates.refresh(this) { if (it && tab == 0) render() }
        if (!imported && SmsStore.canRead(this)) importSms(false)
        if (Prefs.start < 0) askStart(first = true)
        Pending.lastResult?.let { r ->
            Pending.lastResult = null
            startActivity(Intent(this, ResultActivity::class.java).putExtra("status", r.status).putExtra("wallet", r.walletId)
                .putExtra("amount", r.amount).putExtra("phone", r.phone).putExtra("category", r.categoryId ?: -1L).putExtra("note", r.note))
        }
    }

    /** When counting starts: today with an opening balance, the last 90 days, or a chosen date. */
    fun askStart(first: Boolean) {
        val items = arrayOf(
            "Start today — older messages become the opening balance",
            "Count the last 90 days — I'll add categories to them",
            "Pick a start date…",
        )
        AlertDialog.Builder(this)
            .setTitle(if (first) "When should counting start?" else "Change start date")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.start = startOfDay(); render() }
                    1 -> { Prefs.start = System.currentTimeMillis() - 90L * 24 * 3600_000L; render() }
                    else -> {
                        val c = java.util.Calendar.getInstance()
                        android.app.DatePickerDialog(this, { _, y, m, d ->
                            Prefs.start = java.util.Calendar.getInstance().apply {
                                set(y, m, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            render()
                        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                            setOnCancelListener { if (Prefs.start < 0) askStart(first) }
                        }.show()
                    }
                }
            }
            .setCancelable(!first)
            .show()
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

    fun render() {
        if (shownTab >= 0) scroller?.let { scrollY[shownTab] = it.scrollY }
        drawNav()
        content.removeAllViews()
        histList = null
        val page = when (tab) {
            0 -> home(); 1 -> history(); 2 -> peoplePage(); 3 -> reportsPage(); 5 -> vbox().apply {
                add(hbox().apply { setPadding(dp(14), dp(14), dp(20), 0); add(icon(R.drawable.ic_back, BRAND, 22), dp(22), dp(22), end = 8); add(text("More", 15f, true, BRAND), WRAP); setOnClickListener { tab = 4; render() } })
                add(notesPage())
            }
            else -> more()
        }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(page) }
        content.addView(sv)
        scroller = sv
        val keep = if (shownTab == tab) scrollY[tab] else 0
        shownTab = tab
        sv.post { sv.scrollTo(0, keep) }
    }

    private fun drawNav() {
        nav.removeAllViews()
        listOf(Triple("Home", R.drawable.ic_home, 0), Triple("History", R.drawable.ic_list, 1),
            Triple("People", R.drawable.ic_people, 2), Triple("Reports", R.drawable.ic_chart, 3),
            Triple("More", R.drawable.ic_more, 4)).forEach { (label, res, i) ->
            val sel = tab == i || (i == 4 && tab == 5)
            val item = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            val ic = icon(res, if (sel) BRAND else MUTED, 24)
            val holder = FrameLayout(this).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = if (sel) rounded(GREEN_BG, dp(16).toFloat()) else null
                addView(ic)
            }
            item.add(holder, WRAP)
            item.add(text(label, 11.5f, sel, if (sel) BRAND else MUTED), WRAP, top = 2)
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
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hr) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        titles.add(text("$hello 👋", 14f, color = 0xDDFFFFFF.toInt()))
        titles.add(text("Caalin Tracker", 22f, true, WHITE), top = 2)
        titles.add(text(dayText(System.currentTimeMillis()), 12.5f, color = 0xB3FFFFFF.toInt()), top = 2)
        top.add(titles, 0, weight = 1f)
        head.add(top)

        val pills = hbox()
        listOf("Today", "This month", "All").forEachIndexed { i, s ->
            pills.add(pill(s, period == i, onDark = true) { period = i; render() }, WRAP, end = 8)
        }
        head.add(pills, top = 16)

        val since = when (period) { 0 -> startOfDay(); 1 -> startOfMonth(); else -> 0L }
        val sinceLabel = if (period == 2 && Prefs.start > 0) "Since ${dateText(Prefs.start).substringBefore(",")}" else null
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
        head.add(text("Net ${if (net < 0) "−" else "+"}${money(net)}" + (sinceLabel?.let { "  ·  $it" } ?: ""), 14f, true, 0xEEFFFFFF.toInt()), top = 12)
        page.add(head)

        val body = vbox(16)
        body.add(quickActions())
        checklistCard(onlyProblems = true)?.let { body.add(it, top = 12) }
        body.add(ratesCard(), top = 14)
        focusCard()?.let { body.add(it, top = 12) }
        attentionCard()?.let { body.add(it) }

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
        else { val cats = Db.categoryMap(); body.add(listCard(recent.map { tx -> txRow(tx, cats) { showTx(tx) { render() } } })) }
        page.add(body)
        return page
    }

    private fun quickActions(): View {
        val grid = vbox()
        var row = hbox()
        var n = 0
        fun action(label: String, res: Int, onClick: () -> Unit) {
            val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            v.add(FrameLayout(this).apply {
                background = rounded(WHITE, dp(18).toFloat(), dp(1), LINE)
                addView(icon(res, BRAND, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, dp(54), dp(54))
            v.add(text(label, 12f, true, INK), WRAP, top = 6)
            v.setOnClickListener { onClick() }
            row.add(v, 0, weight = 1f)
            n++
            if (n % 4 == 0) { grid.add(row, top = if (n > 4) 10 else 0); row = hbox() }
        }
        action("Send", R.drawable.ic_send) {
            val ready = Db.wallets().filter { it.template.isNotBlank() }
            when (ready.size) {
                0 -> { tab = 4; render() }
                1 -> startActivity(Intent(this, SendActivity::class.java).putExtra("id", ready[0].id))
                else -> AlertDialog.Builder(this).setTitle("Send with")
                    .setItems(ready.map { it.name }.toTypedArray()) { _, i ->
                        startActivity(Intent(this, SendActivity::class.java).putExtra("id", ready[i].id))
                    }.show()
            }
        }
        action("Orders", R.drawable.ic_box) { startActivity(Intent(this, OrdersActivity::class.java)) }
        action("Rates", R.drawable.ic_rate) { startActivity(Intent(this, RatesActivity::class.java)) }
        action("Calculator", R.drawable.ic_calc) { Calc.show(this) }
        if (n % 4 != 0) grid.add(row)
        return grid
    }

    /** One list of what needs you today: debts, shipments, tasks, payments without a category. */
    private fun attentionCard(): View? {
        val rows = ArrayList<View>()
        val today = startOfDay(); val tomorrow = today + 86_400_000L
        fun row(emoji: String, title: String, sub: String, subColor: Int, action: String?, onAction: (() -> Unit)?, onClick: () -> Unit) {
            val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            r.add(text(emoji, 20f).apply { gravity = Gravity.CENTER }, dp(32), WRAP, end = 10)
            val m = vbox()
            m.add(text(title, 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            m.add(text(sub, 12.5f, subColor != MUTED, subColor).apply { maxLines = 1 }, top = 2)
            r.add(m, 0, weight = 1f)
            if (action != null) r.add(text(action, 13f, true, WHITE).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7)); background = rounded(BRAND, dp(16).toFloat()); setOnClickListener { onAction?.invoke() }
            }, WRAP)
            r.setOnClickListener { onClick() }
            rows.add(r)
        }
        // Money owed to you that is due
        Db.debts().filter { !it.closed && it.direction == Debt.THEY && it.due != null && it.due < tomorrow }.sortedBy { it.due }.take(3).forEach { d ->
            val days = ((today - (d.due ?: today)) / 86_400_000L).toInt()
            val nm = d.name.ifBlank { Db.displayName(d.phone) }
            row("💰", "$nm owes ${money(d.left)}", if (days > 0) "$days day${if (days > 1) "s" else ""} late" else "Due today", if (days > 0) RED else AMBER,
                if (d.phone.isNotBlank()) "Remind" else null, { whatsapp(d.phone, reminderText(nm, d.left)) }) {
                if (d.phone.isNotBlank()) openPerson(d.phone) else { tab = 5; notesSeg = 2; render() }
            }
        }
        Db.debts().filter { !it.closed && it.direction == Debt.ME && it.due != null && it.due < tomorrow }.take(2).forEach { d ->
            row("🧾", "You owe ${d.name.ifBlank { Db.displayName(d.phone) }} ${money(d.left)}", "Due ${if (d.due!! < today) "already" else "today"}", RED,
                if (d.phone.isNotBlank()) "Pay" else null, { sendTo(d.phone, d.name) }) { if (d.phone.isNotBlank()) openPerson(d.phone) }
        }
        // Shipments / orders expected
        Db.orders().filter { it.status in 1..3 && it.due != null && it.due < tomorrow + 86_400_000L }.take(3).forEach { o ->
            val late = o.due!! < today
            row(if (o.kind == Order.BUY) "🚚" else "🛒", o.title.ifBlank { o.party.ifBlank { "Order #${o.id}" } },
                (if (late) "Expected ${dateText(o.due).substringBefore(",")} — late" else "Expected soon") + " · ${OrderStatus.NAMES[o.status]}",
                if (late) RED else AMBER, null, null) { startActivity(Intent(this, OrderActivity::class.java).putExtra("id", o.id)) }
        }
        // Tasks
        Db.tasks().filter { !it.done && it.due != null && it.due < tomorrow }.take(3).forEach { t ->
            row("☑️", t.title, if (t.due!! < today) "Overdue" else "Today" + if (t.phone.isNotBlank()) " · ${Db.displayName(t.phone)}" else "",
                if (t.due < today) RED else BRAND, "Done", { Db.saveTask(t.copy(done = true)); render() }) { tab = 5; notesSeg = 1; render() }
        }
        // Categories
        val needs = Db.uncategorized().size
        if (needs > 0) row("🏷", "$needs payment${if (needs == 1) "" else "s"} need a category", "Takes a few seconds", AMBER, "Review",
            { startActivity(Intent(this, ReviewActivity::class.java)) }) { startActivity(Intent(this, ReviewActivity::class.java)) }
        if (rows.isEmpty()) return null
        val wrap = vbox()
        wrap.add(sectionTitle("Needs attention"))
        wrap.add(listCard(rows))
        return wrap
    }

    private fun ratesCard(): View {
        Rates.init(this)
        val c = card()
        val r = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        r.add(text("💱", 22f), WRAP, end = 12)
        val m = vbox()
        val cny = Rates.perUsd("CNY"); val aed = Rates.perUsd("AED")
        m.add(text(if (cny != null && aed != null) "$1 = ${Rates.fmt(cny, "CNY")}  ·  ${Rates.fmt(aed, "AED")}" else "Exchange rates", 15f, true))
        m.add(text(if (Rates.updated > 0) "Updated ${dateText(Rates.updated)} · tap to convert" else "Connect to the internet to load rates", 12f, color = MUTED), top = 2)
        r.add(m, 0, weight = 1f)
        r.add(text("›", 22f, color = MUTED), WRAP)
        c.addView(r)
        c.setOnClickListener { startActivity(Intent(this, RatesActivity::class.java)) }
        return c
    }

    private fun tasksCard(): View? {
        val today = startOfDay() + 86_400_000L
        val due = Db.tasks().filter { !it.done && (it.due == null || it.due < today) }.take(3)
        if (due.isEmpty()) return null
        val wrap = vbox()
        wrap.add(sectionTitle("To do", "All") { tab = 5; notesSeg = 1; render() })
        wrap.add(listCard(due.map { taskRow(it) { render() } }))
        return wrap
    }

    private fun focusCard(): View? {
        Focus.init(this)
        if (!Focus.enabled) return null
        val used = Focus.usedTodayMin()
        val c = card()
        val v = vbox(16)
        val r = hbox()
        r.add(text("⏳  Social apps today", 15f, true), 0, weight = 1f)
        r.add(text("$used / ${Focus.limitMin} min", 14f, true, if (used >= Focus.limitMin) RED else BRAND), WRAP)
        v.add(r)
        v.add(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = Focus.limitMin; progress = used.coerceAtMost(Focus.limitMin)
        }, top = 8)
        if (Focus.hoursOn && Focus.inFocusHours()) v.add(text("Focus hours until ${Focus.timeText(Focus.hoursEnd)}", 12.5f, color = MUTED), top = 4)
        c.addView(v)
        c.setOnClickListener { startActivity(Intent(this, FocusActivity::class.java)) }
        return c
    }

    private fun debtCard(): View? {
        val open = Db.debts().filter { !it.closed }
        if (open.isEmpty()) return null
        val them = open.filter { it.direction == Debt.THEY }.sumOf { it.left }
        val me = open.filter { it.direction == Debt.ME }.sumOf { it.left }
        val late = open.count { it.due != null && it.due < startOfDay() }
        val c = card()
        val r = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val m = vbox()
        m.add(text("Debts", 15f, true))
        m.add(text(if (late > 0) "$late overdue" else "${open.size} open", 12.5f, late > 0, if (late > 0) RED else MUTED), top = 2)
        r.add(m, 0, weight = 1f)
        val rv = vbox().apply { gravity = Gravity.END }
        if (them > 0) rv.add(text("Owed to you ${money(them)}", 13.5f, true, GREEN), WRAP)
        if (me > 0) rv.add(text("You owe ${money(me)}", 13.5f, true, RED), WRAP)
        r.add(rv, WRAP)
        c.addView(r)
        c.setOnClickListener { tab = 5; notesSeg = 2; render() }
        return c
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
        v.add(text(if (w.template.isBlank()) "Set up  ›" else "Send  ›", 14f, true, BRAND), top = 12)
        c.addView(v)
        c.setOnClickListener {
            val target = if (w.template.isBlank()) WalletEditActivity::class.java else SendActivity::class.java
            startActivity(Intent(this, target).putExtra("id", w.id))
        }
        return c
    }

    fun emptyCard(msg: String): View = card().apply {
        addView(text(msg, 14f, color = MUTED).apply { setPadding(dp(18), dp(22), dp(18), dp(22)); gravity = Gravity.CENTER })
    }

    // ---------------- History ----------------

    private fun history(): View {
        val page = vbox(16)
        page.add(text("History", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })

        val search = field("Search name, number or note", android.text.InputType.TYPE_CLASS_TEXT, histQuery)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { histQuery = e.toString(); fillHistory() }
        })
        page.add(search)

        val dirs = hbox()
        dirs.add(pill("All", histDir == null && !histNeeds) { histDir = null; histNeeds = false; render() }, WRAP, end = 8)
        dirs.add(pill("Received", histDir == IN && !histNeeds) { histDir = IN; histNeeds = false; render() }, WRAP, end = 8)
        dirs.add(pill("Sent", histDir == OUT && !histNeeds) { histDir = OUT; histNeeds = false; render() }, WRAP, end = 8)
        dirs.add(pill("Needs category", histNeeds) { histNeeds = !histNeeds; histDir = null; render() }, WRAP, end = 8)
        page.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dirs) }, top = 12)

        val wallets = Db.wallets()
        if (wallets.size > 1) {
            val wr = hbox()
            wr.add(pill("All wallets", histWallet == null) { histWallet = null; render() }, WRAP, end = 8)
            wallets.forEach { w -> wr.add(pill(w.name, histWallet == w.id) { histWallet = w.id; render() }, WRAP, end = 8) }
            page.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wr) }, top = 10)
        }
        val list = vbox()
        histList = list
        page.add(list)
        fillHistory()
        return page
    }

    private fun fillHistory() {
        val list = histList ?: return
        list.removeAllViews()
        val q = histQuery.trim().lowercase()
        val cats = Db.categoryMap()
        val txs = (if (histNeeds) Db.uncategorized().filter { histWallet == null || it.walletId == histWallet }
            else Db.txs(histWallet, histDir, 2000)).filter { t ->
            q.isEmpty() || t.name.lowercase().contains(q) || t.phone.contains(q) || t.note.lowercase().contains(q) ||
                (t.categoryId?.let { cats[it]?.name?.lowercase()?.contains(q) } ?: false)
        }
        val done = txs.filter { it.status == Status.COMPLETED && it.counted }
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
        list.add(sum, top = 14)

        val notCounted = txs.count { it.status != Status.COMPLETED || !it.counted }
        if (notCounted > 0) list.add(text("$notCounted failed, not confirmed or before start — not in totals.", 12.5f, color = MUTED)
            .apply { setPadding(dp(4), dp(8), 0, 0) })
        if (histNeeds && txs.isNotEmpty()) list.add(button("Go through them one by one") {
            startActivity(Intent(this, ReviewActivity::class.java))
        }, top = 10)

        list.add(View(this), MATCH, dp(12))
        if (txs.isEmpty()) list.add(emptyCard(if (q.isNotEmpty()) "No matches." else "Nothing here yet."))
        else list.add(listCard(txs.take(400).map { tx -> txRow(tx, cats) { showTx(tx) { fillHistory() } } }))
    }

    // ---------------- More ----------------

    private fun more(): View {
        val page = vbox()
        val tools = vbox(16)
        tools.add(text("More", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })
        val items = listOf(
            Triple<String, Int, () -> Unit>("Orders", R.drawable.ic_box, { startActivity(Intent(this, OrdersActivity::class.java)) }),
            Triple<String, Int, () -> Unit>("Notes", R.drawable.ic_note, { tab = 5; notesSeg = 0; render() }),
            Triple<String, Int, () -> Unit>("Tasks", R.drawable.ic_check, { tab = 5; notesSeg = 1; render() }),
            Triple<String, Int, () -> Unit>("Debts", R.drawable.ic_debt, { tab = 5; notesSeg = 2; render() }),
            Triple<String, Int, () -> Unit>("Rates", R.drawable.ic_rate, { startActivity(Intent(this, RatesActivity::class.java)) }),
            Triple<String, Int, () -> Unit>("Calculator", R.drawable.ic_calc, { Calc.show(this) }),
            Triple<String, Int, () -> Unit>("Focus", R.drawable.ic_focus, { startActivity(Intent(this, FocusActivity::class.java)) }),
            Triple<String, Int, () -> Unit>("Categorise", R.drawable.ic_tune, { startActivity(Intent(this, ReviewActivity::class.java)) }),
        )
        items.chunked(4).forEachIndexed { ri, chunk ->
            val row = hbox()
            chunk.forEach { (label, res, go) ->
                val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)); setOnClickListener { go() } }
                v.add(FrameLayout(this).apply {
                    background = rounded(WHITE, dp(18).toFloat(), dp(1), LINE)
                    addView(icon(res, BRAND, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, dp(54), dp(54))
                v.add(text(label, 12f, true), WRAP, top = 6)
                row.add(v, 0, weight = 1f)
            }
            tools.add(row, top = if (ri == 0) 0 else 12)
        }
        page.add(tools)
        page.add(settings())
        return page
    }

    // ---------------- Settings ----------------

    private fun settings(): View {
        val page = vbox(16)
        page.add(text("Settings", 22f, true).apply { setPadding(dp(4), dp(8), 0, dp(4)) })

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

        page.add(sectionTitle("Security & focus"))
        val sec = card()
        val secv = vbox(16)
        secv.add(text("Ask for fingerprint after", 15f, true))
        val lk = hbox()
        listOf(0 to "Immediately", 60 to "1 min", 300 to "5 min", 900 to "15 min").forEach { (sec2, l) ->
            lk.add(pill(l, Prefs.lockAfterSec == sec2) { Prefs.lockAfterSec = sec2; render() }, WRAP, end = 6)
        }
        secv.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lk) }, top = 8)
        secv.add(text("Focus & screen time", 15f, true, BRAND).apply {
            setPadding(0, dp(18), 0, dp(2))
            setOnClickListener { startActivity(Intent(this@MainActivity, FocusActivity::class.java)) }
        })
        secv.add(text("Limit TikTok, Facebook, YouTube and more.", 12.5f, color = MUTED))
        sec.addView(secv)
        sec.setOnClickListener { }
        page.add(sec)

        page.add(sectionTitle("Categories"))
        for ((dir, label) in listOf(OUT to "Money out", IN to "Money in")) {
            page.add(text(label, 13f, true, MUTED).apply { setPadding(dp(4), dp(4), 0, dp(6)) })
            val cr = Db.categories(dir).map { c ->
                val r = hbox().apply { setPadding(dp(16), dp(12), dp(14), dp(12)) }
                r.addView(dot(c.color, 14))
                r.add(text(c.name, 15f), 0, weight = 1f)
                r.add(text("Edit", 14f, true, BRAND), WRAP)
                r.setOnClickListener { editCategory(c) }
                r as View
            }
            page.add(listCard(cr))
            page.add(text("+ Add ${label.lowercase()} category", 14f, true, BRAND).apply {
                setPadding(dp(4), dp(10), 0, dp(10))
                setOnClickListener { askText("New category") { n -> Db.addCategory(n, dir); render() } }
            })
        }

        page.add(sectionTitle("Counting"))
        val st = card()
        val sv2 = vbox(16)
        sv2.add(text(if (Prefs.start > 0) "Counting since ${dayText(Prefs.start)}" else "Counting everything", 15f, true))
        sv2.add(text("Payments before this date are kept in History but not counted. Your balance at that date is the opening balance (Reports › Balance check).", 13f, color = MUTED), top = 4)
        sv2.add(button("Change start date", outlined = true) { askStart(first = false) }, top = 10)
        st.addView(sv2)
        page.add(st)

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

        page.add(text("Caalin Tracker 1.5.2", 12f, color = MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8))
        })
        return page
    }

    private fun editCategory(c: Category) {
        AlertDialog.Builder(this).setTitle(c.name)
            .setItems(arrayOf("Rename", "Delete")) { _, i ->
                if (i == 0) askText("Rename category", c.name) { n -> Db.renameCategory(c.id, n); render() }
                else AlertDialog.Builder(this).setMessage("Delete \"${c.name}\"? Its payments will need a category again.")
                    .setPositiveButton("Delete") { _, _ -> Db.deleteCategory(c.id); render() }
                    .setNegativeButton("Cancel", null).show()
            }.show()
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
            if (c.ok) r.add(badge(GREEN_BG, 34, R.drawable.ic_tick, GREEN), dp(34), dp(34), end = 14)
            else r.add(text("!", 16f, true, AMBER).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER_BG) }
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
