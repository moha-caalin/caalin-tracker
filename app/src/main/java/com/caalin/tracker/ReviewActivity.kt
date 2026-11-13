package com.caalin.tracker

import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView

/** Goes through payments that need a category, one at a time. */
class ReviewActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var queue: MutableList<Tx> = mutableListOf()
    private var total = 0
    private var samePerson = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Add categories"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f
        box = vbox(16)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() {
        queue = Db.uncategorized().toMutableList()
        total = queue.size
        show()
    }

    private fun show() {
        box.removeAllViews()
        val t = queue.firstOrNull()
        if (t == null) {
            toast(if (total > 0) "All payments have a category ✓" else "Nothing to categorise")
            finish(); return
        }
        val done = total - queue.size
        box.add(text("${done + 1} of $total", 13f, true, MUTED))
        box.add(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total; progress = done
        }, top = 6)

        val incoming = t.direction == IN
        val c = card()
        val v = vbox(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        v.add(badge(if (incoming) GREEN_BG else RED_BG, 56, if (incoming) R.drawable.ic_in else R.drawable.ic_out,
            if (incoming) GREEN else RED), dp(56), dp(56))
        v.add(text((if (incoming) "+" else "−") + money(t.amount), 32f, true, if (incoming) GREEN else RED), WRAP, top = 10)
        v.add(text(t.who, 17f, true).apply { gravity = Gravity.CENTER }, WRAP, top = 4)
        if (t.name.isNotBlank() && t.phone.isNotBlank()) v.add(text(t.phone, 13f, color = MUTED), WRAP, top = 2)
        v.add(text("${if (incoming) "Received" else "Sent"} · ${t.walletName} · ${dateText(t.createdAt)}", 13f, color = MUTED), WRAP, top = 4)
        c.addView(v)
        box.add(c, top = 14)

        box.add(text(if (incoming) "What was this money for?" else "What did you pay for?", 16f, true), top = 18)
        box.add(categoryChips(t.direction, null, { cat -> pick(t, cat.id) }, {
            askText("New ${if (incoming) "income" else "expense"} category") { n -> pick(t, Db.addCategory(n, t.direction)) }
        }), top = 8)

        val others = if (t.phone.isNotBlank()) queue.count { it !== t && it.direction == t.direction && last9(it.phone) == last9(t.phone) } else 0
        if (others > 0) {
            box.add(CheckBox(this).apply {
                text = "Also use it for $others other payment(s) with ${t.who}"
                isChecked = samePerson
                setOnCheckedChangeListener { _, b -> samePerson = b }
            }, top = 10)
        }

        val skip = hbox()
        skip.add(button("Skip", outlined = true) { queue.removeAt(0); queue.add(t); if (queue.size == 1) finish() else show() }, 0, weight = 1f, end = 10)
        skip.add(button("Open details", outlined = true) { showTx(t) { onUnlockedResume() } }, 0, weight = 1f)
        box.add(skip, top = 18)
    }

    private fun pick(t: Tx, catId: Long) {
        Db.setCategory(t.id, catId)
        queue.remove(t)
        if (samePerson && t.phone.isNotBlank()) {
            val same = queue.filter { it.direction == t.direction && last9(it.phone) == last9(t.phone) }
            same.forEach { Db.setCategory(it.id, catId) }
            queue.removeAll(same)
        }
        show()
    }
}
