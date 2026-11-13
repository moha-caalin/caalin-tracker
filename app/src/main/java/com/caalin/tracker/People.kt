package com.caalin.tracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog

fun Context.openPerson(phone: String) {
    if (last9(phone).isEmpty()) return
    startActivity(Intent(this, PersonActivity::class.java).putExtra("phone", last9(phone)))
}

fun Context.call(phone: String) = try {
    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0" + last9(phone))))
} catch (_: Exception) { toast("No phone app found") }

fun Context.whatsapp(phone: String, message: String = "") = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/252${last9(phone)}" +
        if (message.isNotEmpty()) "?text=" + Uri.encode(message) else "")))
} catch (_: Exception) { toast("WhatsApp is not installed") }

fun reminderText(name: String, amount: Double) =
    "Asc ${name.split(" ").firstOrNull() ?: ""}, xasuusin: lacagta ${money(amount)} ee lagaa rabo. Mahadsanid. — Caalin"

fun Context.typeColor(t: String) = when (t) {
    PersonType.CUSTOMER -> GREEN; PersonType.SUPPLIER -> 0xFF1565C0.toInt(); PersonType.STAFF -> 0xFF6A1B9A.toInt()
    PersonType.FAMILY -> 0xFFF57C00.toInt(); else -> MUTED
}

fun Context.personRow(p: PersonSummary): View {
    val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
    r.add(badge(0xFFE9EEF0.toInt(), 44, letter = p.name.take(1).uppercase(), iconTint = INK), dp(44), dp(44), end = 12)
    val m = vbox()
    m.add(text(p.name, 15.5f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    val tags = hbox()
    if (p.type != PersonType.OTHER) tags.add(text(PersonType.label(p.type), 11.5f, true, typeColor(p.type)).apply {
        setPadding(dp(8), dp(2), dp(8), dp(2)); background = rounded((typeColor(p.type) and 0x00FFFFFF) or 0x1F000000, dp(8).toFloat())
    }, WRAP, end = 6)
    tags.add(text(p.phone + if (p.openOrders > 0) " · ${p.openOrders} open order${if (p.openOrders > 1) "s" else ""}" else "", 12f, color = MUTED).apply { maxLines = 1 }, 0, weight = 1f)
    m.add(tags, top = 3)
    r.add(m, 0, weight = 1f)
    val rv = vbox().apply { gravity = Gravity.END }
    when {
        p.owesMe > 0.009 -> { rv.add(text("Owes you", 11f, color = MUTED), WRAP); rv.add(text(money(p.owesMe), 15f, true, GREEN), WRAP) }
        p.iOwe > 0.009 -> { rv.add(text("You owe", 11f, color = MUTED), WRAP); rv.add(text(money(p.iOwe), 15f, true, RED), WRAP) }
        else -> {
            if (p.received > 0) rv.add(text("+" + money(p.received), 13.5f, true, GREEN), WRAP)
            if (p.sent > 0) rv.add(text("−" + money(p.sent), 13.5f, true, RED), WRAP)
        }
    }
    r.add(rv, WRAP)
    r.setOnClickListener { openPerson(p.phone) }
    return r
}

/** People tab: everyone you deal with. */
fun MainActivity.peoplePage(): View {
    val page = vbox(16)
    page.add(text("People", 28f, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })
    val search = field("Search name or number", InputType.TYPE_CLASS_TEXT, peopleQuery)
    page.add(search)
    val filters = hbox()
    listOf(null to "All", PersonType.CUSTOMER to "Customers", PersonType.SUPPLIER to "Suppliers", PersonType.STAFF to "Staff",
        "OWE" to "Owes you").forEach { (k, l) ->
        filters.add(pill(l, peopleFilter == k) { peopleFilter = k; render() }, WRAP, end = 8)
    }
    page.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters) }, top = 12)
    val list = vbox()
    page.add(list, top = 12)
    val all = Db.people()
    fun fill() {
        list.removeAllViews()
        val q = peopleQuery.trim().lowercase()
        val shown = all.filter { p ->
            (q.isEmpty() || p.name.lowercase().contains(q) || p.phone.contains(q)) && when (peopleFilter) {
                null -> true; "OWE" -> p.owesMe > 0.009; else -> p.type == peopleFilter
            }
        }
        if (shown.isEmpty()) list.add(emptyCard(if (all.isEmpty()) "People appear here from your payments, debts and orders." else "No one found."))
        else list.add(listCard(shown.take(300).map { personRow(it) }))
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: Editable?) { peopleQuery = e.toString(); fill() }
    })
    fill()
    return page
}

/** One person: all their money, debts, orders, notes and tasks in one place. */
class PersonActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var phone = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra("phone") ?: ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        title = ""
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = render()

    private fun render() {
        box.removeAllViews()
        val info = Db.personInfo(phone) ?: PersonInfo(phone, "", PersonType.OTHER)
        val name = Db.displayName(phone)
        val txs = Db.txsFor(phone)
        val done = txs.filter { it.status == Status.COMPLETED && it.counted }
        val debts = Db.debts().filter { last9(it.phone) == phone }
        val openDebts = debts.filter { !it.closed }
        val orders = Db.orders().filter { last9(it.phone) == phone }
        val owesMe = openDebts.filter { it.direction == Debt.THEY }.sumOf { it.left } +
            orders.filter { it.kind == Order.SELL && it.status != OrderStatus.CLOSED }.sumOf { Db.orderMoney(it).left }
        val iOwe = openDebts.filter { it.direction == Debt.ME }.sumOf { it.left } +
            orders.filter { it.kind == Order.BUY && it.status != OrderStatus.CLOSED }.sumOf { Db.orderMoney(it).left }

        // Header
        val head = card()
        val hv = vbox(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        hv.add(badge(typeColor(info.type), 72, letter = name.take(1).uppercase()), dp(72), dp(72))
        hv.add(text(name, 22f, true).apply { gravity = Gravity.CENTER }, WRAP, top = 10)
        hv.add(text("0$phone · ${PersonType.label(info.type)}", 14f, color = MUTED), WRAP, top = 2)
        if (info.note.isNotBlank()) hv.add(text(info.note, 13.5f).apply { gravity = Gravity.CENTER }, WRAP, top = 6)
        val acts = hbox()
        fun act(label: String, res: Int, onClick: () -> Unit) {
            val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { onClick() } }
            v.add(FrameLayout(this).apply {
                background = rounded(GREEN_BG, dp(16).toFloat())
                addView(icon(res, BRAND, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, dp(50), dp(50))
            v.add(text(label, 12f, true), WRAP, top = 4)
            acts.add(v, 0, weight = 1f)
        }
        act("Send", R.drawable.ic_send) { sendTo(phone, name) }
        act("Call", R.drawable.ic_call) { call(phone) }
        act("WhatsApp", R.drawable.ic_chat) { whatsapp(phone) }
        act("Edit", R.drawable.ic_tune) { editPerson(info.copy(name = info.name.ifBlank { name })) }
        hv.add(acts, top = 16)
        head.addView(hv)
        box.add(head)

        // Balance with this person
        val bc = card()
        val bv = hbox().apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }
        fun stat(l: String, v: String, c: Int) = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            add(text(l, 12f, color = MUTED), WRAP); add(text(v, 17f, true, c), WRAP, top = 4)
        }
        bv.add(stat("Received", "+" + money(done.filter { it.direction == IN }.sumOf { it.amount }), GREEN), 0, weight = 1f)
        bv.add(stat("Sent", "−" + money(done.filter { it.direction == OUT }.sumOf { it.amount }), RED), 0, weight = 1f)
        bv.add(stat(if (iOwe > owesMe) "You owe" else "Owes you", money(if (iOwe > owesMe) iOwe else owesMe),
            if (iOwe > owesMe) RED else if (owesMe > 0) GREEN else INK), 0, weight = 1f)
        bc.addView(bv)
        box.add(bc, top = 12)
        if (owesMe > 0.009) box.add(button("Send WhatsApp reminder for ${money(owesMe)}", outlined = true) {
            whatsapp(phone, reminderText(name, owesMe))
        }, top = 10)

        // Orders
        box.add(sectionTitle("Orders", "+ New") {
            val kind = if (info.type == PersonType.CUSTOMER) Order.SELL else Order.BUY
            editOrder(this, Order(kind = kind, party = name, phone = phone, title = ""), kind) { id ->
                startActivity(Intent(this, OrderActivity::class.java).putExtra("id", id))
            }
        })
        if (orders.isEmpty()) box.add(text("No orders.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
        else box.add(listCard(orders.take(10).map { o ->
            val m = Db.orderMoney(o)
            val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            r.add(text(if (o.kind == Order.BUY) "📦" else "🛒", 20f), WRAP, end = 12)
            val mm = vbox()
            mm.add(text(o.title.ifBlank { "Order #${o.id}" }, 15f, true))
            mm.add(text(OrderStatus.NAMES[o.status] + " · total ${money(m.total)}", 12.5f, color = MUTED), top = 2)
            r.add(mm, 0, weight = 1f)
            r.add(text(if (m.left > 0.009) money(m.left) + " left" else "Paid ✔", 13.5f, true, if (m.left > 0.009) AMBER else GREEN), WRAP)
            r.setOnClickListener { startActivity(Intent(this, OrderActivity::class.java).putExtra("id", o.id)) }
            r as View
        }))

        // Debts
        box.add(sectionTitle("Debts", "+ Add") { addDebtFor(name) })
        if (openDebts.isEmpty()) box.add(text("No open debts.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
        else openDebts.forEach { d ->
            val r = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val mm = vbox()
            mm.add(text(if (d.direction == Debt.THEY) "Owes you" else "You owe", 15f, true))
            mm.add(text((if (d.paid > 0) "Paid ${money(d.paid)} of ${money(d.amount)}" else d.note.ifBlank { "Since ${dateText(d.created).substringBefore(",")}" }) +
                (d.due?.let { " · due ${dateText(it).substringBefore(",")}" } ?: ""), 12.5f, color = MUTED), top = 2)
            r.add(mm, 0, weight = 1f)
            r.add(text(money(d.left), 16f, true, if (d.direction == Debt.THEY) GREEN else RED), WRAP)
            box.add(card().apply { addView(r) }, top = 8)
        }

        // Tasks & notes for this person
        val tasks = Db.tasks().filter { last9(it.phone) == phone && !it.done }
        val notes = Db.notes().filter { last9(it.phone) == phone }
        box.add(sectionTitle("Tasks & notes", "+ Add") {
            AlertDialog.Builder(this).setItems(arrayOf("Task", "Note")) { _, i ->
                if (i == 0) askText("Task for $name") { t -> Db.saveTask(Task(title = t, phone = phone, due = startOfDay())); render() }
                else askText("Note about $name") { t -> Db.saveNote(Note(0, "", t, false, phone, System.currentTimeMillis())); render() }
            }.show()
        })
        if (tasks.isEmpty() && notes.isEmpty()) box.add(text("Nothing yet.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
        tasks.forEach { t -> box.add(card().apply {
            addView(hbox().apply {
                setPadding(dp(8), dp(6), dp(16), dp(6))
                add(android.widget.CheckBox(this@PersonActivity).apply {
                    setOnCheckedChangeListener { _, b -> if (b) { Db.saveTask(t.copy(done = true)); render() } }
                }, WRAP)
                add(text(t.title, 15f), 0, weight = 1f)
            })
        }, top = 8) }
        notes.forEach { n -> box.add(card().apply {
            addView(text("📝 " + listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString(" — "), 14f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        }, top = 8) }

        // Payments
        box.add(sectionTitle("Payments (${txs.size})"))
        val cats = Db.categoryMap()
        if (txs.isEmpty()) box.add(text("No payments yet.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
        else box.add(listCard(txs.take(50).map { t -> txRow(t, cats) { showTx(t) { render() } } }))
        box.add(View(this), MATCH, dp(24))
    }

    private fun editPerson(p: PersonInfo) {
        val b = vbox(20)
        val n = field("Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, p.name)
        val note = field("Note, e.g. shop in Baidoa", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, p.note)
        var type = p.type
        val types = hbox()
        fun drawTypes() {
            types.removeAllViews()
            PersonType.ALL.forEach { t -> types.add(pill(PersonType.label(t), type == t) { type = t; drawTypes() }, WRAP, end = 6) }
        }
        drawTypes()
        b.add(n); b.add(note, top = 10)
        b.add(text("Type", 13f, true, MUTED), top = 14)
        b.add(HorizontalScrollView(this).apply { addView(types) }, top = 6)
        b.add(text("Customers' payments go to their orders and debts, and suppliers' to purchase orders, automatically.", 12f, color = MUTED), top = 10)
        AlertDialog.Builder(this).setTitle("Edit person").setView(b)
            .setPositiveButton("Save") { _, _ -> Db.savePerson(p.copy(name = n.text.toString().trim(), type = type, note = note.text.toString().trim())); render() }
            .setNegativeButton("Cancel", null).showKb()
    }

    private fun addDebtFor(name: String) {
        val f = field("Amount ($)", InputType.TYPE_CLASS_PHONE)
        var dir = Debt.THEY
        val row = hbox()
        fun draw() {
            row.removeAllViews()
            row.add(pill("They owe me", dir == Debt.THEY) { dir = Debt.THEY; draw() }, WRAP, end = 8)
            row.add(pill("I owe them", dir == Debt.ME) { dir = Debt.ME; draw() }, WRAP)
        }
        draw()
        val note = field("For what? (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        AlertDialog.Builder(this).setTitle("Debt — $name").setView(vbox(20).apply { add(row); add(f, top = 12); add(note, top = 10) })
            .setPositiveButton("Save") { _, _ ->
                val a = Calc.eval(f.text.toString()) ?: return@setPositiveButton
                Db.saveDebt(Debt(name = name, phone = phone, direction = dir, amount = a, note = note.text.toString().trim())); render()
            }.setNegativeButton("Cancel", null).showKb()
    }
}

/** Opens the send screen for this person, asking which wallet when there are several. */
fun Context.sendTo(phone: String, name: String) {
    val ready = Db.wallets().filter { it.template.isNotBlank() }
    fun go(w: Wallet) = startActivity(Intent(this, SendActivity::class.java).putExtra("id", w.id).putExtra("phone", phone).putExtra("name", name))
    when (ready.size) {
        0 -> toast("Add a USSD code for a wallet in Settings first")
        1 -> go(ready[0])
        else -> AlertDialog.Builder(this).setTitle("Send with").setItems(ready.map { it.name }.toTypedArray()) { _, i -> go(ready[i]) }.show()
    }
}
