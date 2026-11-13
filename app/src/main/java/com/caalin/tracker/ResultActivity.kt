package com.caalin.tracker

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback

/** Full-screen result after a payment: success, failed or not confirmed. */
class ResultActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = done()
        })
        render()
    }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun render() {
        val status = intent.getStringExtra("status") ?: Status.UNKNOWN
        val walletId = intent.getLongExtra("wallet", -1)
        val amount = intent.getDoubleExtra("amount", 0.0)
        val phone = intent.getStringExtra("phone") ?: ""
        val note = intent.getStringExtra("note") ?: ""
        val w = Db.wallet(walletId)
        val cat = Db.categoryMap()[intent.getLongExtra("category", -1)]
        // Name from the saved transaction (filled in from the SMS) when we have it.
        val tx = Db.txs(walletId, OUT, 5).firstOrNull { last9(it.phone) == last9(phone) && kotlin.math.abs(it.amount - amount) < 0.001 }
        val name = intent.getStringExtra("name")?.takeIf { it.isNotBlank() } ?: tx?.name ?: ""
        val ok = status == Status.COMPLETED
        val failed = status == Status.FAILED
        val (c1, c2) = when { ok -> 0xFF15985A.toInt() to DEEP; failed -> 0xFFD64545.toInt() to 0xFF8E2424.toInt(); else -> 0xFFE09A1A.toInt() to 0xFF9A6200.toInt() }
        window.statusBarColor = c1

        val root = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)) }
        val col = vbox(24).apply { gravity = Gravity.CENTER_HORIZONTAL }

        val mark = text(if (ok) "✓" else if (failed) "✕" else "?", 54f, true, c1).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE) }
        }
        col.add(mark, dp(112), dp(112), top = 24)
        mark.scaleX = 0.3f; mark.scaleY = 0.3f
        mark.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(2.2f)).start()

        col.add(text(if (ok) "Payment sent" else if (failed) "Payment failed" else "Not confirmed yet", 24f, true, WHITE), WRAP, top = 22)
        col.add(text(money(amount), 44f, true, WHITE), WRAP, top = 4)
        col.add(text("to ${name.ifBlank { phone }}", 17f, color = 0xEEFFFFFF.toInt()).apply { gravity = Gravity.CENTER }, WRAP, top = 2)

        val card = card()
        val cv = vbox(18)
        fun line(l: String, v: String) {
            val r = hbox()
            r.add(text(l, 14f, color = MUTED), 0, weight = 1f)
            r.add(text(v, 14f, true), WRAP)
            cv.add(r, top = if (cv.childCount == 0) 0 else 12)
        }
        if (name.isNotBlank()) line("Number", phone)
        line("Wallet", w?.name ?: "")
        cat?.let { line("Category", it.name) }
        line("Date", dayText(System.currentTimeMillis()).substringAfter(", ") + " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()))
        if (ok) Db.latestBalance(walletId)?.let { line("New balance", money(it)) }
        line("Status", statusLabel(status))
        if (note.isNotBlank() || !ok) cv.add(text(note.ifBlank {
            if (failed) "Nothing was sent. Check your balance and the number, then try again."
            else "The app couldn't read a clear answer. It will update by itself when the SMS arrives."
        }, 12.5f, color = MUTED), top = 12)
        card.addView(cv)
        col.add(card, MATCH, top = 26)

        val btns = vbox()
        btns.add(text("Done", 17f, true, c2).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)); background = rounded(WHITE, dp(28).toFloat())
            setOnClickListener { done() }
        }, MATCH)
        val row = hbox()
        fun ghost(label: String, onClick: () -> Unit) = row.add(text(label, 15f, true, WHITE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = rounded(0x26FFFFFF, dp(28).toFloat())
            setOnClickListener { onClick() }
        }, 0, weight = 1f, end = if (row.childCount == 0) 10 else 0)
        if (ok) ghost("Share receipt") {
            val text = "✅ Payment sent\n${money(amount)} to ${name.ifBlank { phone }}" + (if (name.isNotBlank()) " ($phone)" else "") +
                "\nvia ${w?.name ?: ""}\n${dateText(System.currentTimeMillis())}" + (cat?.let { "\nFor: ${it.name}" } ?: "") + "\n— Caalin Tracker"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share receipt"))
        }
        ghost(if (ok) "Send again" else "Try again") {
            if (w != null) startActivity(Intent(this, SendActivity::class.java).putExtra("id", w.id).putExtra("phone", phone).putExtra("name", name))
            finish()
        }
        btns.add(row, top = 12)
        col.add(btns, MATCH, top = 28)

        root.addView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        setContentView(root)
    }
}
