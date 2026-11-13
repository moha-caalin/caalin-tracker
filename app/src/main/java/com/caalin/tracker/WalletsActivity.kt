package com.caalin.tracker

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

class WalletsActivity : BaseActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Money types"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val box = vbox(12)
        list = vbox()
        box.add(list)
        box.add(button("+ Add money type") {
            startActivity(Intent(this, WalletEditActivity::class.java).putExtra("id", -1L))
        }, top = 8)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() {
        list.removeAllViews()
        for (w in Db.wallets()) {
            val row = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            row.addView(dot(w.color, 16))
            val mid = vbox()
            mid.add(text(w.name, 16f, true))
            mid.add(text(w.template.ifBlank { "USSD code not set" }, 13f, color = MUTED), top = 2)
            mid.add(text(if (w.hasPin) "🔒 PIN saved" else "No PIN saved", 12f, color = if (w.hasPin) BRAND else MUTED), top = 2)
            row.add(mid, 0, weight = 1f)
            row.add(text("Edit", 14f, true, BRAND), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setOnClickListener {
                startActivity(Intent(this, WalletEditActivity::class.java).putExtra("id", w.id))
            }
            list.add(card().apply { addView(row) })
        }
    }
}
