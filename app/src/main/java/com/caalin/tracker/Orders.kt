package com.caalin.tracker

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import java.util.Calendar

private fun statusColor(s: Int) = when (s) {
    0 -> MUTED; 1 -> 0xFF1565C0.toInt(); 2 -> 0xFF6A1B9A.toInt(); 3 -> AMBER; 4 -> GREEN; else -> 0xFF37474F.toInt()
}

private fun qty(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)

/** List of purchase orders (China) and customer orders. */
class OrdersActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var kind = Order.BUY
    private var showClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Orders"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = render()

    private fun render() {
        box.removeAllViews()
        val seg = hbox()
        seg.add(pill("🇨🇳  Purchases", kind == Order.BUY) { kind = Order.BUY; render() }, WRAP, end = 8)
        seg.add(pill("🛒  Customer orders", kind == Order.SELL) { kind = Order.SELL; render() }, WRAP)
        box.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seg) })

        val all = Db.orders(kind)
        val open = all.filter { it.status != OrderStatus.CLOSED }
        val money = open.map { Db.orderMoney(it) }
        val sum = card()
        val s = hbox().apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }
        fun stat(l: String, v: String, c: Int) = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            add(text(l, 12.5f, color = MUTED), WRAP); add(text(v, 18f, true, c), WRAP, top = 4)
        }
        s.add(stat("Open", open.size.toString(), INK), 0, weight = 1f)
        s.add(stat("Value", money(money.sumOf { it.total }), INK), 0, weight = 1f)
        s.add(stat(if (kind == Order.BUY) "To pay" else "To collect", money(money.sumOf { it.left }), if (kind == Order.BUY) RED else GREEN), 0, weight = 1f)
        sum.addView(s)
        box.add(sum, top = 12)

        box.add(button(if (kind == Order.BUY) "+ New purchase order" else "+ New customer order") { editOrder(this, null, kind) { id ->
            startActivity(Intent(this, OrderActivity::class.java).putExtra("id", id))
        } }, top = 12)

        val list = if (showClosed) all else open
        if (list.isEmpty()) box.add(card().apply {
            addView(text(if (kind == Order.BUY) "No open purchase orders. Add one when you order from China." else "No open customer orders.",
                14f, color = MUTED).apply { setPadding(dp(18), dp(22), dp(18), dp(22)); gravity = Gravity.CENTER })
        }, top = 12)
        list.forEach { o -> box.add(orderCard(o), top = 12) }
        val closed = all.size - open.size
        if (closed > 0) box.add(text(if (showClosed) "Hide closed orders" else "Show $closed closed order${if (closed == 1) "" else "s"}", 14f, true, BRAND).apply {
            setPadding(dp(4), dp(16), 0, dp(16)); setOnClickListener { showClosed = !showClosed; render() }
        })
    }

    private fun orderCard(o: Order): View {
        val m = Db.orderMoney(o)
        val ships = Db.shipments(o.id)
        val c = card()
        val v = vbox(16)
        val top = hbox()
        val t = vbox()
        t.add(text(o.title.ifBlank { "Order #${o.id}" }, 16f, true).apply { maxLines = 1 })
        t.add(text(o.party.ifBlank { o.phone } + " · " + dateText(o.created).substringBefore(","), 12.5f, color = MUTED), top = 2)
        top.add(t, 0, weight = 1f)
        top.add(text(OrderStatus.NAMES[o.status], 12f, true, WHITE).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4)); background = rounded(statusColor(o.status), dp(10).toFloat())
        }, WRAP)
        v.add(top)
        val r = hbox()
        r.add(text("Total ${money(m.total)}", 14f, true), 0, weight = 1f)
        r.add(text(if (m.left > 0.009) "${if (o.kind == Order.BUY) "To pay" else "To collect"} ${money(m.left)}" else "Paid ✓", 13.5f, true,
            if (m.left > 0.009) (if (o.kind == Order.BUY) RED else AMBER) else GREEN), WRAP)
        v.add(r, top = 10)
        if (m.total > 0) v.add(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = ((m.paid / m.total) * 100).toInt().coerceIn(0, 100)
        }, top = 4)
        if (ships.isNotEmpty()) v.add(text("🚚 ${ships.count { it.arrived }} of ${ships.size} shipments arrived", 12.5f, color = MUTED), top = 6)
        c.addView(v)
        c.setOnClickListener { startActivity(Intent(this, OrderActivity::class.java).putExtra("id", o.id)) }
        return c
    }
}

/** Create or edit an order's main details. */
fun editOrder(a: BaseActivity, o: Order?, kind: String, onSaved: (Long) -> Unit) {
    val box = a.vbox(20)
    val title = a.field("What is it? e.g. 200 chargers", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, o?.title ?: "")
    val party = a.field(if (kind == Order.BUY) "Supplier / agent" else "Customer / shop", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, o?.party ?: "")
    val phone = a.field("Phone (links their payments)", InputType.TYPE_CLASS_PHONE, o?.phone ?: "")
    val note = a.field("Note (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, o?.note ?: "")
    box.add(title); box.add(party, top = 10); box.add(phone, top = 10); box.add(note, top = 10)
    var due = o?.due
    val dueBtn = a.text("", 14f, true, BRAND).apply { setPadding(a.dp(4), a.dp(14), 0, a.dp(4)) }
    fun drawDue() { dueBtn.text = due?.let { "Expected: ${dateText(it).substringBefore(",")}  (change)" } ?: "+ Expected date" }
    drawDue()
    dueBtn.setOnClickListener {
        val c = Calendar.getInstance().apply { due?.let { timeInMillis = it } }
        DatePickerDialog(a, { _, y, m, d -> due = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis; drawDue() },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    box.add(dueBtn)
    AlertDialog.Builder(a).setTitle(if (o == null) (if (kind == Order.BUY) "New purchase order" else "New customer order") else "Edit order")
        .setView(ScrollView(a).apply { addView(box) })
        .setPositiveButton("Save") { _, _ ->
            val base = o ?: Order(kind = kind, party = "", phone = "", title = "")
            onSaved(Db.saveOrder(base.copy(title = title.text.toString().trim(), party = party.text.toString().trim(),
                phone = normalizePhone(phone.text.toString()), note = note.text.toString().trim(), due = due)))
        }
        .setNegativeButton("Cancel", null).show()
}

/** One order: status, items, shipments, money and linked payments. */
class OrderActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var id = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", -1)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = render()

    private fun render() {
        val o = Db.order(id) ?: run { finish(); return }
        title = if (o.kind == Order.BUY) "Purchase order" else "Customer order"
        box.removeAllViews()
        val items = Db.items(o.id)
        val ships = Db.shipments(o.id)
        val m = Db.orderMoney(o)

        // Header
        val hc = card()
        val hv = vbox(18)
        val hr = hbox()
        val ht = vbox()
        ht.add(text(o.title.ifBlank { "Order #${o.id}" }, 20f, true))
        ht.add(text(listOf(o.party, o.phone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No supplier set" }, 13.5f, color = MUTED), top = 2)
        o.due?.let { ht.add(text("Expected ${dateText(it).substringBefore(",")}", 13f, color = if (it < startOfDay() && o.status < 4) RED else MUTED), top = 2) }
        hr.add(ht, 0, weight = 1f)
        hr.add(text("Edit", 14f, true, BRAND).apply { setOnClickListener { editOrder(this@OrderActivity, o, o.kind) { render() } } }, WRAP)
        hv.add(hr)
        if (o.note.isNotBlank()) hv.add(text("📝 " + o.note, 13.5f), top = 8)
        val st = hbox()
        OrderStatus.NAMES.forEachIndexed { i, n ->
            st.add(pill(n, o.status == i) { Db.saveOrder(o.copy(status = i)); render() }, WRAP, end = 6)
        }
        hv.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(st) }, top = 12)
        hc.addView(hv)
        box.add(hc)

        // Money
        box.add(sectionTitle("Money"))
        val mc = card()
        val mv = vbox(18)
        fun line(l: String, v: String, c: Int = INK, bold: Boolean = false) {
            val r = hbox(); r.add(text(l, 14.5f, bold, if (bold) INK else MUTED), 0, weight = 1f); r.add(text(v, 14.5f, true, c), WRAP)
            mv.add(r, top = if (mv.childCount == 0) 0 else 8)
        }
        line("Items", money(m.items))
        line("Freight", money(m.freight))
        line("Total", money(m.total), bold = true)
        line("Paid", money(m.paid), GREEN)
        line(if (o.kind == Order.BUY) "Still to pay" else "Still to collect", money(m.left), if (m.left > 0.009) RED else GREEN, true)
        mv.add(button("+ Record payment by hand", outlined = true) {
            val f = field("Amount", InputType.TYPE_CLASS_PHONE)
            AlertDialog.Builder(this).setTitle("Payment").setView(vbox(20).apply { add(f) })
                .setPositiveButton("Save") { _, _ -> Calc.eval(f.text.toString())?.let { Db.saveOrder(o.copy(paidManual = o.paidManual + it)); render() } }
                .setNegativeButton("Cancel", null).show()
        }, top = 12)
        mc.addView(mv)
        box.add(mc)

        val linked = Db.orderTxs(o.id)
        if (linked.isNotEmpty()) {
            box.add(sectionTitle("Linked payments"))
            val cats = Db.categoryMap()
            box.add(listCard(linked.map { t -> txRow(t, cats) { showTx(t) { render() } } }))
        }

        // Items
        box.add(sectionTitle("Items", "+ Add") { editItem(o.id, null) })
        if (items.isEmpty()) box.add(text("No items yet. Add what you ordered with quantity and price.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
        else box.add(listCard(items.map { i ->
            val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            val mm = vbox()
            mm.add(text(i.name, 15f, true))
            val recv = if (o.kind == Order.BUY) " · received ${qty(i.received)}/${qty(i.qty)}" else ""
            mm.add(text("${qty(i.qty)} × ${money(i.price)}$recv", 12.5f, color = if (o.kind == Order.BUY && i.received < i.qty) AMBER else MUTED), top = 2)
            r.add(mm, 0, weight = 1f)
            r.add(text(money(i.qty * i.price), 15f, true), WRAP)
            r.setOnClickListener { editItem(o.id, i) }
            r as View
        }))

        // Shipments
        if (o.kind == Order.BUY || ships.isNotEmpty()) {
            box.add(sectionTitle("Shipments", "+ Add") { editShipment(o.id, null, ships.size + 1) })
            if (ships.isEmpty()) box.add(text("Goods coming in parts? Add each shipment with its own freight.", 13.5f, color = MUTED).apply { setPadding(dp(4), 0, 0, 0) })
            else box.add(listCard(ships.map { s ->
                val r = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
                r.add(text(if (s.arrived) "✅" else "🚚", 20f), WRAP, end = 12)
                val mm = vbox()
                mm.add(text(s.label, 15f, true))
                mm.add(text((if (s.arrived) "Arrived " else "Expected ") + dateText(s.date).substringBefore(",") +
                    if (s.note.isNotBlank()) " · ${s.note}" else "", 12.5f, color = MUTED), top = 2)
                r.add(mm, 0, weight = 1f)
                r.add(text("Freight ${money(s.freight)}", 13.5f, true), WRAP)
                r.setOnClickListener { editShipment(o.id, s, 0) }
                r as View
            }))
        }

        box.add(text("Delete order", 14f, true, RED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(24))
            setOnClickListener {
                AlertDialog.Builder(this@OrderActivity).setMessage("Delete this order? Linked payments stay in History.")
                    .setPositiveButton("Delete") { _, _ -> Db.deleteOrder(o.id); finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    private fun editItem(orderId: Long, i: OrderItem?) {
        val o = Db.order(orderId) ?: return
        val b = vbox(20)
        val name = field("Item, e.g. Type-C charger", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, i?.name ?: "")
        val q = field("Quantity", InputType.TYPE_CLASS_PHONE, i?.let { qty(it.qty) } ?: "")
        val p = field("Price each ($)", InputType.TYPE_CLASS_PHONE, i?.let { amountText(it.price) } ?: "")
        val rcv = field("Received so far", InputType.TYPE_CLASS_PHONE, i?.let { qty(it.received) } ?: "0")
        b.add(name); b.add(q, top = 10); b.add(p, top = 10)
        if (o.kind == Order.BUY) b.add(rcv, top = 10)
        val d = AlertDialog.Builder(this).setTitle(if (i == null) "Add item" else "Edit item").setView(b)
            .setPositiveButton("Save") { _, _ ->
                val qq = Calc.eval(q.text.toString()) ?: 0.0
                val pp = Calc.eval(p.text.toString()) ?: 0.0
                if (name.text.isBlank() || qq <= 0) { toast("Enter the item and quantity"); return@setPositiveButton }
                Db.saveItem(OrderItem(i?.id ?: 0, orderId, name.text.toString().trim(), qq, pp, Calc.eval(rcv.text.toString()) ?: 0.0))
                render()
            }.setNegativeButton("Cancel", null)
        if (i != null) d.setNeutralButton("Delete") { _, _ -> Db.deleteItem(i.id); render() }
        d.show()
    }

    private fun editShipment(orderId: Long, s: Shipment?, n: Int) {
        val b = vbox(20)
        val label = field("Name, e.g. Shipment 1 (air)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, s?.label ?: "Shipment $n")
        val fr = field("Freight cost ($)", InputType.TYPE_CLASS_PHONE, s?.let { amountText(it.freight) } ?: "")
        val note = field("Note, e.g. 80 cartons, tracking no.", InputType.TYPE_CLASS_TEXT, s?.note ?: "")
        val arrived = CheckBox(this).apply { text = "Arrived"; isChecked = s?.arrived ?: false }
        var date = s?.date ?: System.currentTimeMillis()
        val db = text("", 14f, true, BRAND).apply { setPadding(dp(4), dp(12), 0, dp(4)) }
        fun drawDate() { db.text = "Date: ${dateText(date).substringBefore(",")}  (change)" }
        drawDate()
        db.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = date }
            DatePickerDialog(this, { _, y, m, d -> date = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis; drawDate() },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        b.add(label); b.add(fr, top = 10); b.add(note, top = 10); b.add(db); b.add(arrived, top = 4)
        val d = AlertDialog.Builder(this).setTitle(if (s == null) "Add shipment" else "Edit shipment").setView(ScrollView(this).apply { addView(b) })
            .setPositiveButton("Save") { _, _ ->
                Db.saveShipment(Shipment(s?.id ?: 0, orderId, label.text.toString().trim().ifBlank { "Shipment" }, date,
                    Calc.eval(fr.text.toString()) ?: 0.0, arrived.isChecked, note.text.toString().trim()))
                // Move the order status along with its shipments.
                Db.order(orderId)?.let { o ->
                    val all = Db.shipments(orderId)
                    val st = when { all.isNotEmpty() && all.all { it.arrived } -> 4; all.any { it.arrived } -> 3; o.status < 2 -> 2; else -> o.status }
                    if (o.status != OrderStatus.CLOSED && st > o.status) Db.saveOrder(o.copy(status = st))
                }
                render()
            }.setNegativeButton("Cancel", null)
        if (s != null) d.setNeutralButton("Delete") { _, _ -> Db.deleteShipment(s.id); render() }
        d.show()
    }
}
