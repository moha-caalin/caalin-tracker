package com.caalin.tracker

import android.app.DatePickerDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.Calendar

fun MainActivity.notesPage(): View {
    val page = vbox(16)
    page.add(text("Notes", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })
    val seg = hbox()
    seg.add(pill("Notes", !notesDebts) { notesDebts = false; render() }, WRAP, end = 8)
    seg.add(pill("Debts", notesDebts) { notesDebts = true; render() }, WRAP)
    page.add(seg)
    if (notesDebts) debtsSection(page) else notesSection(page)
    page.add(View(this), MATCH, dp(24))
    return page
}

// ---------------- Notes ----------------

private fun MainActivity.notesSection(page: LinearLayout) {
    page.add(button("+ New note") { editNote(null) }, top = 14)
    val notes = Db.notes()
    if (notes.isEmpty()) {
        page.add(emptyCard("Write anything down: orders, prices, reminders. Pin the important ones."), top = 12)
        return
    }
    notes.forEach { n ->
        val c = card()
        val v = vbox(16)
        val top = hbox()
        top.add(text(n.title.ifBlank { n.body.lineSequence().firstOrNull() ?: "Note" }, 16f, true).apply { maxLines = 1 }, 0, weight = 1f)
        if (n.pinned) top.add(text("📌", 14f), WRAP)
        v.add(top)
        if (n.body.isNotBlank() && n.title.isNotBlank()) v.add(text(n.body, 14f, color = 0xFF3D4A52.toInt()).apply { maxLines = 4 }, top = 4)
        v.add(text(dateText(n.updated) + if (n.phone.isNotBlank()) " · ${n.phone}" else "", 12f, color = MUTED), top = 8)
        c.addView(v)
        c.setOnClickListener { editNote(n) }
        page.add(c, top = 10)
    }
}

private fun MainActivity.editNote(n: Note?) {
    val box = vbox(20)
    val title = field("Title", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, n?.title ?: "")
    val body = field("Write your note…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, n?.body ?: "").apply {
        minLines = 5; gravity = Gravity.TOP or Gravity.START
    }
    val phone = field("Person's number (optional)", InputType.TYPE_CLASS_PHONE, n?.phone ?: "")
    val pin = CheckBox(this).apply { text = "Pin to top"; isChecked = n?.pinned ?: false }
    box.add(title); box.add(body, top = 10); box.add(phone, top = 10); box.add(pin, top = 6)
    val d = AlertDialog.Builder(this).setTitle(if (n == null) "New note" else "Edit note").setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton("Save") { _, _ ->
            if (title.text.isBlank() && body.text.isBlank()) return@setPositiveButton
            Db.saveNote(Note(n?.id ?: 0, title.text.toString().trim(), body.text.toString().trim(), pin.isChecked,
                normalizePhone(phone.text.toString()), System.currentTimeMillis()))
            render()
        }
        .setNegativeButton("Cancel", null)
    if (n != null) d.setNeutralButton("Delete") { _, _ -> Db.deleteNote(n.id); render() }
    d.show()
}

// ---------------- Debts ----------------

private fun MainActivity.debtsSection(page: LinearLayout) {
    val debts = Db.debts()
    val open = debts.filter { !it.closed }
    val owedToMe = open.filter { it.direction == Debt.THEY }.sumOf { it.left }
    val iOwe = open.filter { it.direction == Debt.ME }.sumOf { it.left }

    val sum = card()
    val s = hbox().apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }
    fun stat(l: String, v: String, c: Int) = vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        add(text(l, 12.5f, color = MUTED), WRAP); add(text(v, 20f, true, c), WRAP, top = 4)
    }
    s.add(stat("Owed to you", money(owedToMe), GREEN), 0, weight = 1f)
    s.add(stat("You owe", money(iOwe), RED), 0, weight = 1f)
    sum.addView(s)
    page.add(sum, top = 14)
    page.add(text("When their money arrives by SMS, the debt goes down by itself.", 12.5f, color = MUTED).apply {
        setPadding(dp(4), dp(8), 0, 0)
    })
    page.add(button("+ Add debt") { editDebt(null) }, top = 12)

    if (debts.isEmpty()) { page.add(emptyCard("No debts. Add who owes you and whom you owe."), top = 12); return }

    fun row(d: Debt): View {
        val they = d.direction == Debt.THEY
        val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        if (d.closed) r.alpha = 0.55f
        r.add(badge(if (they) GREEN_BG else RED_BG, 42, letter = d.name.take(1).uppercase().ifEmpty { "?" }, iconTint = if (they) GREEN else RED),
            dp(42), dp(42), end = 12)
        val m = vbox()
        m.add(text(d.name.ifBlank { d.phone }, 15f, true).apply { maxLines = 1 })
        val sub = when {
            d.closed -> "Settled ✓"
            d.paid > 0 -> "Paid ${money(d.paid)} of ${money(d.amount)}"
            else -> if (they) "Owes you" else "You owe"
        } + (d.due?.let { " · due ${dateText(it).substringBefore(",")}" } ?: "")
        val late = !d.closed && d.due != null && d.due < startOfDay()
        m.add(text(sub, 12.5f, late, if (late) RED else MUTED), top = 2)
        if (!d.closed && d.paid > 0) m.add(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = ((d.paid / d.amount) * 100).toInt()
        }, top = 4)
        r.add(m, 0, weight = 1f)
        r.add(text(money(d.left), 16f, true, if (d.closed) MUTED else if (they) GREEN else RED), WRAP)
        r.setOnClickListener { editDebt(d) }
        return r
    }
    val openRows = open.map { row(it) }
    if (openRows.isNotEmpty()) page.add(listCard(openRows), top = 12)
    val closed = debts.filter { it.closed }
    if (closed.isNotEmpty()) {
        page.add(sectionTitle("Settled"))
        page.add(listCard(closed.take(30).map { row(it) }))
    }
}

private fun MainActivity.editDebt(d: Debt?) {
    val box = vbox(20)
    var dir = d?.direction ?: Debt.THEY
    var due: Long? = d?.due
    val dirRow = hbox()
    fun drawDir() {
        dirRow.removeAllViews()
        dirRow.add(pill("They owe me", dir == Debt.THEY) { dir = Debt.THEY; drawDir() }, WRAP, end = 8)
        dirRow.add(pill("I owe them", dir == Debt.ME) { dir = Debt.ME; drawDir() }, WRAP)
    }
    drawDir()
    box.add(HorizontalScrollView(this).apply { addView(dirRow) })
    val name = field("Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, d?.name ?: "")
    val phone = field("Phone number (to match SMS)", InputType.TYPE_CLASS_PHONE, d?.phone ?: "")
    val amount = field("Amount ($)", InputType.TYPE_CLASS_PHONE, d?.let { amountText(it.amount) } ?: "")
    val note = field("Note (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, d?.note ?: "")
    box.add(name, top = 12); box.add(phone, top = 10); box.add(amount, top = 10); box.add(note, top = 10)
    val dueBtn = text("", 14f, true, BRAND).apply { setPadding(dp(4), dp(14), dp(4), dp(6)) }
    fun drawDue() { dueBtn.text = due?.let { "Due: ${dateText(it).substringBefore(",")}  (tap to change)" } ?: "+ Add due date" }
    drawDue()
    dueBtn.setOnClickListener {
        val c = Calendar.getInstance().apply { due?.let { timeInMillis = it } }
        DatePickerDialog(this, { _, y, m, dd ->
            due = Calendar.getInstance().apply { set(y, m, dd, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis; drawDue()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    box.add(dueBtn)
    if (d != null && d.history.isNotBlank()) {
        box.add(text("Payments", 13f, true, MUTED), top = 10)
        box.add(text(d.history, 13f), top = 4)
    }
    val b = AlertDialog.Builder(this).setTitle(if (d == null) "New debt" else d.name.ifBlank { "Debt" })
        .setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton("Save") { _, _ ->
            val a = Calc.eval(amount.text.toString())
            if (a == null || a <= 0) { toast("Enter an amount"); return@setPositiveButton }
            val base = d ?: Debt(name = "", phone = "", direction = dir, amount = a)
            Db.saveDebt(base.copy(name = name.text.toString().trim(), phone = normalizePhone(phone.text.toString()), direction = dir,
                amount = a, note = note.text.toString().trim(), due = due, closed = base.paid >= a - 0.0001 && base.paid > 0))
            render()
        }
        .setNegativeButton("Cancel", null)
    if (d != null && !d.closed) b.setNeutralButton("Record payment") { _, _ -> recordPayment(d) }
    else if (d != null) b.setNeutralButton("Delete") { _, _ -> Db.deleteDebt(d.id); render() }
    b.show()
}

private fun MainActivity.recordPayment(d: Debt) {
    val f = field("Amount paid", InputType.TYPE_CLASS_PHONE, amountText(d.left))
    AlertDialog.Builder(this).setTitle("Payment from ${d.name.ifBlank { d.phone }}")
        .setView(vbox(20).apply { add(f) })
        .setPositiveButton("Save") { _, _ ->
            val a = Calc.eval(f.text.toString()) ?: return@setPositiveButton
            val paid = (d.paid + a).coerceAtMost(d.amount)
            val line = dateText(System.currentTimeMillis()).substringBefore(",") + ": " + money(a) + " by hand"
            Db.saveDebt(d.copy(paid = paid, closed = paid >= d.amount - 0.0001, history = (d.history + "\n" + line).trim()))
            render()
        }
        .setNeutralButton("Delete debt") { _, _ -> Db.deleteDebt(d.id); render() }
        .setNegativeButton("Cancel", null).show()
}
