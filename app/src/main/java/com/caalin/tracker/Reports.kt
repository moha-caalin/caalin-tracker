package com.caalin.tracker

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthRange(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, offset)
    }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis - 1
}

fun MainActivity.reportsPage(): View {
    val page = vbox(16)
    page.add(text("Reports", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })

    // Period selector
    val (mFrom, mTo) = monthRange(reportMonth)
    val from = if (reportAll) Prefs.countFrom else mFrom
    val to = if (reportAll) System.currentTimeMillis() else mTo
    val label = if (reportAll) (if (Prefs.start > 0) "Since ${SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(Prefs.start))}" else "All time")
        else SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(mFrom))
    val sel = hbox()
    if (!reportAll) sel.add(text("‹", 26f, true, BRAND).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { reportMonth--; render() } }, WRAP)
    sel.add(text(label, 17f, true).apply { gravity = Gravity.CENTER }, 0, weight = 1f)
    if (!reportAll && reportMonth < 0) sel.add(text("›", 26f, true, BRAND).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { reportMonth++; render() } }, WRAP)
    page.add(sel)
    val modes = hbox().apply { gravity = Gravity.CENTER }
    modes.add(pill("Month", !reportAll) { reportAll = false; render() }, WRAP, end = 8)
    modes.add(pill("All since start", reportAll) { reportAll = true; render() }, WRAP)
    page.add(modes, top = 8)

    val all = Db.txsBetween(from, to)
    val txs = all.filter { it.status == Status.COMPLETED && it.counted }
    val cats = Db.categoryMap()
    val inn = txs.filter { it.direction == IN }.sumOf { it.amount }
    val out = txs.filter { it.direction == OUT }.sumOf { it.amount }

    // Totals
    val tc = card()
    val tr = hbox().apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }
    fun stat(l: String, v: String, c: Int) = vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        add(text(l, 12.5f, color = MUTED), WRAP); add(text(v, 18f, true, c), WRAP, top = 4)
    }
    tr.add(stat("Received", "+" + money(inn), GREEN), 0, weight = 1f)
    tr.add(stat("Sent", "−" + money(out), RED), 0, weight = 1f)
    tr.add(stat("Net", (if (inn - out < 0) "−" else "+") + money(inn - out), INK), 0, weight = 1f)
    tc.addView(tr)
    page.add(tc, top = 14)

    // Daily chart
    page.add(sectionTitle("Day by day"))
    val dayFrom = if (reportAll) maxOf(from, startOfDay() - 29L * 86_400_000L) else from
    val days = ArrayList<Long>()
    run {
        val c = Calendar.getInstance().apply { timeInMillis = dayFrom; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val end = minOf(to, System.currentTimeMillis())
        while (c.timeInMillis <= end && days.size < 62) { days.add(c.timeInMillis); c.add(Calendar.DAY_OF_MONTH, 1) }
    }
    if (days.isNotEmpty()) {
        val ins = days.map { d -> txs.filter { it.direction == IN && it.createdAt >= d && it.createdAt < d + 86_400_000L }.sumOf { it.amount } }
        val outs = days.map { d -> txs.filter { it.direction == OUT && it.createdAt >= d && it.createdAt < d + 86_400_000L }.sumOf { it.amount } }
        val labels = days.map { SimpleDateFormat("d", Locale.US).format(Date(it)) }
        val cc = card()
        val cv = vbox(14)
        val legend = hbox()
        legend.addView(dot(GREEN, 10)); legend.add(text("Received", 12f, color = MUTED), WRAP, end = 16)
        legend.addView(dot(RED, 10)); legend.add(text("Sent", 12f, color = MUTED), WRAP)
        cv.add(legend)
        cv.add(BarChart(this, labels, ins, outs), MATCH, dp(160), top = 10)
        cc.addView(cv)
        page.add(cc)
    }

    // By category
    for ((dir, title, total) in listOf(Triple(OUT, "Spending by category", out), Triple(IN, "Income by category", inn))) {
        page.add(sectionTitle(title))
        val group = txs.filter { it.direction == dir }.groupBy { it.categoryId }
            .map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
        if (group.isEmpty()) { page.add(emptyCard("Nothing yet.")); continue }
        val c = card()
        val v = vbox(16)
        group.forEachIndexed { i, (catId, sum) ->
            val cat = catId?.let { cats[it] }
            val color = cat?.color ?: AMBER
            val row = hbox()
            row.addView(dot(color, 10))
            row.add(text(cat?.name ?: "Needs category", 14f, true, if (cat == null) AMBER else INK), 0, weight = 1f)
            val pct = if (total > 0) sum / total else 0.0
            row.add(text("${money(sum)}  ·  ${(pct * 100).toInt()}%", 13.5f, color = MUTED), WRAP)
            v.add(row, top = if (i == 0) 0 else 12)
            val bar = hbox().apply { background = rounded(0xFFEFF3F1.toInt(), dp(4).toFloat()) }
            bar.add(View(this).apply { background = rounded(color, dp(4).toFloat()) }, 0, dp(8), weight = pct.toFloat().coerceAtLeast(0.01f))
            bar.add(View(this), 0, dp(8), weight = (1 - pct).toFloat().coerceAtLeast(0.0001f))
            v.add(bar, top = 6)
            if (cat == null) row.setOnClickListener { startActivity(Intent(this, ReviewActivity::class.java)) }
        }
        c.addView(v)
        page.add(c)
    }

    // People
    page.add(sectionTitle("People"))
    data class Person(var name: String, val phone: String, var inn: Double = 0.0, var out: Double = 0.0, var n: Int = 0)
    val people = LinkedHashMap<String, Person>()
    txs.sortedByDescending { it.createdAt }.forEach { t ->
        val k = last9(t.phone).ifEmpty { t.name.lowercase() }
        if (k.isEmpty()) return@forEach
        val p = people.getOrPut(k) { Person(t.name, t.phone) }
        if (p.name.isBlank()) p.name = t.name
        if (t.direction == IN) p.inn += t.amount else p.out += t.amount
        p.n++
    }
    val top = people.values.sortedByDescending { it.inn + it.out }.take(10)
    if (top.isEmpty()) page.add(emptyCard("Nothing yet."))
    else page.add(listCard(top.map { p ->
        val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        r.add(badge(0xFFE9EEF0.toInt(), 40, letter = (p.name.ifBlank { p.phone }).take(1).uppercase(), iconTint = INK), dp(40), dp(40), end = 12)
        val m = vbox()
        m.add(text(p.name.ifBlank { p.phone }, 15f, true).apply { maxLines = 1 })
        m.add(text("${p.n} payment${if (p.n == 1) "" else "s"}" + if (p.name.isNotBlank() && p.phone.isNotBlank()) " · ${p.phone}" else "", 12f, color = MUTED), top = 2)
        r.add(m, 0, weight = 1f)
        val rv = vbox().apply { gravity = Gravity.END }
        if (p.inn > 0) rv.add(text("+" + money(p.inn), 14f, true, GREEN), WRAP)
        if (p.out > 0) rv.add(text("−" + money(p.out), 14f, true, RED), WRAP)
        r.add(rv, WRAP)
        r.setOnClickListener { histQuery = p.phone.ifBlank { p.name }; tab = 1; render() }
        r as View
    }))

    // Balance check
    page.add(sectionTitle("Balance check"))
    val bc = card()
    val bv = vbox(16)
    bv.add(text("Opening + received − sent = expected. The real balance comes from the latest SMS.", 12.5f, color = MUTED))
    Db.wallets().forEach { w ->
        val open = Db.opening(w.id)
        val t = Db.totals(w.id, 0)
        val real = Db.latestBalance(w.id)
        bv.add(hbox().apply { addView(dot(w.color, 12)); add(text(w.name, 16f, true), WRAP) }, top = 16)
        fun line(l: String, v: String, color: Int = INK, onClick: (() -> Unit)? = null) {
            val r = hbox()
            r.add(text(l, 14f, color = MUTED), 0, weight = 1f)
            r.add(text(v, 14f, true, color), WRAP)
            if (onClick != null) r.setOnClickListener { onClick() }
            bv.add(r, top = 6)
        }
        line("Opening balance  ✎", open?.let { money(it) } ?: "Tap to set", if (open == null) AMBER else BRAND) {
            val f = field("0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, open?.let { amountText(it) } ?: "")
            AlertDialog.Builder(this).setTitle("Opening balance — ${w.name}").setView(vbox(20).apply { add(f) })
                .setPositiveButton("Save") { _, _ -> Prefs.setOpening(w.id, f.text.toString().toDoubleOrNull()); render() }
                .setNeutralButton("Use from SMS") { _, _ -> Prefs.setOpening(w.id, null); render() }
                .setNegativeButton("Cancel", null).show()
        }
        line("Received", "+" + money(t[0]), GREEN)
        line("Sent", "−" + money(t[1]), RED)
        if (open != null) {
            val exp = open + t[0] - t[1]
            line("Expected balance", money(exp))
            if (real != null) {
                line("Real balance (SMS)", money(real))
                val diff = real - exp
                if (kotlin.math.abs(diff) >= 0.01) line("Difference", (if (diff < 0) "−" else "+") + money(diff), AMBER)
                else line("Difference", "None ✓", GREEN)
            }
        } else if (real != null) line("Real balance (SMS)", money(real))
    }
    bv.add(text("A difference usually means a payment without an SMS, a fee, or airtime bought from this wallet.", 12f, color = MUTED), top = 14)
    bc.addView(bv)
    page.add(bc)

    // Export
    page.add(button("Export to Excel — $label") { exportCsv(all, label) }, top = 18)
    page.add(View(this), MATCH, dp(24))
    return page
}

private fun MainActivity.exportCsv(txs: List<Tx>, label: String) {
    if (txs.isEmpty()) { toast("Nothing to export for $label"); return }
    val cats = Db.categoryMap()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    fun q(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
    val sb = StringBuilder("﻿")
    sb.append("Date,Wallet,Type,Person,Phone,Amount,Category,Status,Counted,Note,Balance after\n")
    txs.sortedBy { it.createdAt }.forEach { t ->
        sb.append(listOf(
            fmt.format(Date(t.createdAt)), t.walletName, if (t.direction == IN) "Received" else "Sent", t.name, t.phone,
            (if (t.direction == IN) "" else "-") + amountText(t.amount), t.categoryId?.let { cats[it]?.name } ?: "",
            statusLabel(t.status), if (t.counted) "Yes" else "No (before start)", t.note, t.balance?.let { amountText(it) } ?: "",
        ).joinToString(",") { q(it) }).append("\n")
    }
    val dir = File(cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "Caalin-Tracker-${label.replace(Regex("[^A-Za-z0-9]+"), "-")}.csv")
    file.writeText(sb.toString())
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Caalin Tracker — $label")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Save or share the Excel file"))
}
