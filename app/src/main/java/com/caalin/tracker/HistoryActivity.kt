package com.caalin.tracker

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner

class HistoryActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var spinner: Spinner
    private var wallets: List<Wallet> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val box = vbox(12)
        spinner = Spinner(this)
        box.add(spinner)
        list = vbox()
        box.add(list, top = 8)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() {
        wallets = Db.wallets()
        val names = listOf("All money types") + wallets.map { it.name }
        val keep = spinner.selectedItemPosition.coerceAtLeast(0)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(if (keep < names.size) keep else 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = render()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val pos = spinner.selectedItemPosition
        val wallet = if (pos > 0) wallets.getOrNull(pos - 1) else null
        val txs = Db.txs(wallet?.id)
        val done = txs.filter { it.status == Status.COMPLETED }

        val sum = vbox(16)
        sum.add(text(wallet?.name ?: "All money types", 14f, true, MUTED))
        sum.add(text(money(done.sumOf { it.amount }), 28f, true), top = 4)
        sum.add(text("${done.size} completed  ·  ${txs.count { it.status == Status.FAILED }} failed  ·  " +
            "${txs.count { it.status == Status.UNKNOWN }} not confirmed", 13f, color = MUTED), top = 2)
        if (wallet == null && wallets.size > 1) {
            for (w in wallets) {
                val total = done.filter { it.walletId == w.id }.sumOf { it.amount }
                val r = hbox()
                r.addView(dot(w.color, 10))
                r.add(text(w.name, 14f), 0, weight = 1f)
                r.add(text(money(total), 14f, true), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                sum.add(r, top = 8)
            }
        }
        list.add(card().apply { addView(sum) })

        if (txs.isEmpty()) list.add(text("No transactions yet.", 14f, color = MUTED).apply { setPadding(dp(6), dp(12), 0, 0) })
        for (t in txs) list.add(txRow(t) { showTx(t) { render() } })
    }
}
