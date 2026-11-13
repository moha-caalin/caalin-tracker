package com.caalin.tracker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView

class MainActivity : BaseActivity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox(12)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = render()

    private fun render() {
        box.removeAllViews()

        if (!Ussd.enabled(this)) {
            val c = vbox(16)
            c.add(text("Turn on auto-PIN", 16f, true))
            c.add(text("So the app can type your PIN after your fingerprint and save each send by itself, turn on \"Caalin Tracker auto-PIN\" in Accessibility settings.", 14f, color = MUTED), top = 4)
            c.add(button("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, top = 10)
            box.add(card().apply { addView(c) })
        }

        box.add(text("Send money", 20f, true).apply { setPadding(dp(6), dp(8), 0, dp(4)) })
        val today = startOfDay()
        val wallets = Db.wallets()
        for (w in wallets) {
            val row = hbox().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
            row.addView(dot(w.color, 18))
            val mid = vbox()
            mid.add(text(w.name, 17f, true))
            if (w.template.isBlank()) {
                mid.add(text("USSD code not set — tap to add it", 13f, color = 0xFFC77700.toInt()), top = 2)
            } else {
                mid.add(text("Today ${money(Db.total(w.id, today))}  ·  Total ${money(Db.total(w.id, 0))}", 13f, color = MUTED), top = 2)
                mid.add(text(if (w.hasPin) "🔒 Auto-PIN on" else "PIN typed by hand", 12f, color = if (w.hasPin) BRAND else MUTED), top = 2)
            }
            row.add(mid, 0, weight = 1f)
            row.add(text("›", 26f, color = MUTED), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setOnClickListener {
                val target = if (w.template.isBlank()) WalletEditActivity::class.java else SendActivity::class.java
                startActivity(Intent(this, target).putExtra("id", w.id))
            }
            box.add(card().apply { addView(row) })
        }
        if (wallets.isEmpty()) box.add(text("No money types yet. Add one from the menu.", 14f, color = MUTED).apply { setPadding(dp(6), dp(6), 0, 0) })

        box.add(text("Recent", 20f, true).apply { setPadding(dp(6), dp(16), 0, dp(4)) })
        val recent = Db.txs(null, 8)
        if (recent.isEmpty()) box.add(text("No transactions yet.", 14f, color = MUTED).apply { setPadding(dp(6), dp(6), 0, 0) })
        for (t in recent) box.add(txRow(t) { showTx(t) { render() } })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "History").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Money types").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, HistoryActivity::class.java))
            2 -> startActivity(Intent(this, WalletsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
