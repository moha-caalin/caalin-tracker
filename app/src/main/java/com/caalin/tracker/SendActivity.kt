package com.caalin.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import kotlin.math.abs

/** One-hand send: 1 Who → 2 How much → 3 What for. Every control sits in the lower half of the screen. */
class SendActivity : BaseActivity() {
    private lateinit var w: Wallet
    private var step = 0
    private var phone = ""
    private var who = ""
    private var amountStr = ""
    private var categoryId: Long? = null
    private var orderId: Long? = null
    private var afterPermission: (() -> Unit)? = null
    private var busy = false

    private lateinit var stepText: TextView
    private lateinit var body: FrameLayout
    private lateinit var dots: LinearLayout

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) choosePerson(normalizePhone(c.getString(0) ?: ""), c.getString(1) ?: "")
            }
        } catch (e: Exception) { toast("Couldn't read that contact") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = Db.wallet(intent.getLongExtra("id", -1))
        if (loaded == null) { finish(); return }
        w = loaded
        intent.getStringExtra("phone")?.let { phone = normalizePhone(it); who = intent.getStringExtra("name") ?: ""; if (phone.isNotEmpty()) step = 1 }
        supportActionBar?.hide()

        val root = vbox()
        // Slim header
        val head = hbox().apply {
            setPadding(dp(8), dp(10), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(w.color, darker(w.color)))
        }
        head.add(text("←", 24f, true, WHITE).apply { setPadding(dp(10), 0, dp(14), 0); setOnClickListener { back() } }, WRAP)
        val ht = vbox()
        ht.add(text("Send with ${w.name}", 17f, true, WHITE))
        stepText = text("", 12.5f, color = 0xDDFFFFFF.toInt())
        ht.add(stepText)
        head.add(ht, 0, weight = 1f)
        Db.latestBalance(w.id)?.let { head.add(text(money(it), 15f, true, WHITE), WRAP) }
        root.add(head)
        dots = hbox().apply { setPadding(dp(16), dp(10), dp(16), 0) }
        root.add(dots)
        body = FrameLayout(this)
        root.add(body, MATCH, 0, weight = 1f)
        setContentView(root)
        window.statusBarColor = darker(w.color)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        draw()
    }

    private fun back() { if (step > 0 && !busy) { step--; draw() } else finish() }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv); hsv[2] *= 0.7f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun amountValue() = Calc.eval(amountStr) ?: 0.0

    private fun draw() {
        stepText.text = listOf("Step 1 of 3 · Who?", "Step 2 of 3 · How much?", "Step 3 of 3 · What for?")[step]
        dots.removeAllViews()
        for (i in 0..2) dots.add(View(this).apply { background = rounded(if (i <= step) w.color else LINE, dp(3).toFloat()) },
            0, dp(5), weight = 1f, end = if (i < 2) 6 else 0)
        body.removeAllViews()
        body.addView(when (step) { 0 -> stepWho(); 1 -> stepAmount(); else -> stepWhat() })
    }

    /** Content pushed to the bottom so the thumb reaches everything. */
    private fun bottomPage(content: View, footer: View?): View {
        val col = vbox()
        val sv = ScrollView(this).apply { isFillViewport = true }
        val inner = vbox(16).apply { gravity = Gravity.BOTTOM }
        inner.add(content)
        sv.addView(inner)
        col.add(sv, MATCH, 0, weight = 1f)
        if (footer != null) col.add(vbox().apply {
            setPadding(dp(16), dp(10), dp(16), dp(16)); setBackgroundColor(WHITE); elevation = dp(6).toFloat(); add(footer)
        })
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return col
    }

    // ---------- Step 1: who ----------
    private fun choosePerson(p: String, name: String) {
        if (p.length < 7) { toast("That number looks too short"); return }
        phone = p; who = name
        if (categoryId == null) categoryId = Db.guessCategory(p, OUT)
        step = 1; draw()
    }

    private fun stepWho(): View {
        val c = vbox()
        c.add(text("Who are you paying?", 22f, true).apply { setPadding(dp(4), 0, 0, dp(12)) })
        val recents = Db.recentRecipients(w.id).map { normalizePhone(it.first) to it.second }.distinctBy { it.first }.take(5)
        if (recents.isNotEmpty()) {
            c.add(text("Recent", 13f, true, MUTED).apply { setPadding(dp(4), 0, 0, dp(6)) })
            c.add(listCard(recents.map { (ph, nm) ->
                val r = hbox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
                val label = nm.ifBlank { ph }
                r.add(badge(0xFFE9EEF0.toInt(), 44, letter = label.take(1).uppercase(), iconTint = INK), dp(44), dp(44), end = 14)
                val m = vbox()
                m.add(text(label.split(" ").joinToString(" ") { it.lowercase().replaceFirstChar { ch -> ch.uppercase() } }, 16f, true).apply { maxLines = 1 })
                if (nm.isNotBlank()) m.add(text(ph, 13f, color = MUTED))
                r.add(m, 0, weight = 1f)
                r.add(text("›", 24f, color = MUTED), WRAP)
                r.setOnClickListener { choosePerson(ph, nm) }
                r as View
            }))
        }
        c.add(text("Or a new number", 13f, true, MUTED).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
        val f = field("e.g. 617111194", InputType.TYPE_CLASS_PHONE, if (who.isBlank()) phone else "")
        f.textSize = 20f
        val row = hbox()
        row.add(f, 0, weight = 1f, end = 10)
        row.add(roundIcon(R.drawable.ic_contacts, 56) {
            try { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
            catch (e: Exception) { toast("No contacts app found") }
        }, dp(56), dp(56))
        c.add(row)
        val next = button("Next") { choosePerson(normalizePhone(f.text.toString()), "") }.apply { minHeight = dp(56); textSize = 17f }
        return bottomPage(c, next)
    }

    // ---------- Step 2: amount ----------
    private fun stepAmount(): View {
        val c = vbox()
        val to = hbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(WHITE, dp(14).toFloat(), dp(1), LINE)
            setOnClickListener { step = 0; draw() }
        }
        to.add(text("To  ", 14f, color = MUTED), WRAP)
        to.add(text(who.ifBlank { phone }, 15f, true).apply { maxLines = 1 }, 0, weight = 1f)
        if (who.isNotBlank()) to.add(text(phone, 13f, color = MUTED), WRAP, end = 8)
        to.add(text("Change", 13f, true, BRAND), WRAP)
        c.add(to)

        val big = text("", 48f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }
        val sub = text("", 15f, true, BRAND).apply { gravity = Gravity.CENTER }
        c.add(big); c.add(sub)
        fun refresh() {
            big.text = "$" + amountStr.ifEmpty { "0" }
            big.setTextColor(if (amountStr.isEmpty()) MUTED else INK)
            sub.text = if (Calc.hasOperator(amountStr)) Calc.eval(amountStr)?.let { "= " + money(it) } ?: "…" else ""
        }
        refresh()

        val quick = hbox()
        listOf(1, 5, 10, 20, 50, 100).forEach { q ->
            quick.add(pill("$$q", false) { amountStr = q.toString(); refresh() }, WRAP, end = 8)
        }
        c.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, top = 16)

        val keys = listOf(listOf("1", "2", "3", "+"), listOf("4", "5", "6", "−"), listOf("7", "8", "9", "×"), listOf(".", "0", "⌫", "="))
        val pad = vbox()
        for (r in keys) {
            val row = hbox()
            for (k in r) {
                val op = k in listOf("+", "−", "×", "=")
                row.add(text(k, 26f, !op, if (op) BRAND else INK).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
                    background = rounded(if (op) GREEN_BG else WHITE, dp(16).toFloat(), if (op) 0 else dp(1), LINE)
                    setOnClickListener {
                        when (k) {
                            "⌫" -> amountStr = amountStr.dropLast(1)
                            "=" -> Calc.eval(amountStr)?.let { amountStr = amountText(it) }
                            else -> if (amountStr.length < 24) amountStr += k
                        }
                        refresh()
                    }
                    if (k == "⌫") setOnLongClickListener { amountStr = ""; refresh(); true }
                }, 0, weight = 1f, end = if (k != r.last()) 8 else 0)
            }
            pad.add(row, top = 8)
        }
        c.add(pad, top = 8)
        val next = button("Next") {
            val v = amountValue()
            if (v <= 0) toast("Enter an amount") else { amountStr = amountText(v); step = 2; draw() }
        }.apply { minHeight = dp(56); textSize = 17f }
        return bottomPage(c, next)
    }

    // ---------- Step 3: what for + send ----------
    private fun stepWhat(): View {
        val a = amountValue()
        val c = vbox()
        val sum = card()
        val sv = vbox(18).apply { gravity = Gravity.CENTER_HORIZONTAL }
        sv.add(text(money(a), 34f, true), WRAP)
        sv.add(text("to ${who.ifBlank { phone }}" + if (who.isNotBlank()) " · $phone" else "", 15f, color = MUTED).apply { gravity = Gravity.CENTER }, WRAP, top = 2)
        sv.add(text("via ${w.name}", 13f, color = MUTED), WRAP, top = 2)
        sum.addView(sv)
        c.add(sum)

        c.add(text("What for?", 18f, true).apply { setPadding(dp(4), dp(18), 0, dp(8)) })
        val cats = Db.categories(OUT)
        val grid = vbox()
        (cats.map { it.id to it.name } + (-1L to "+ New")).chunked(3).forEach { rowCats ->
            val row = hbox()
            rowCats.forEachIndexed { i, (id, name) ->
                val sel = id == categoryId
                val cat = cats.firstOrNull { it.id == id }
                row.add(text(name, 14f, true, if (sel) WHITE else INK).apply {
                    gravity = Gravity.CENTER; minLines = 2; maxLines = 2
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    background = rounded(if (sel) (cat?.color ?: BRAND) else WHITE, dp(14).toFloat(), if (sel) 0 else dp(1), LINE)
                    setOnClickListener {
                        if (id == -1L) askText("New expense category") { n -> categoryId = Db.addCategory(n, OUT); draw() }
                        else { categoryId = id; draw() }
                    }
                }, 0, weight = 1f, end = if (i < 2) 8 else 0)
            }
            repeat(3 - rowCats.size) { row.add(View(this), 0, weight = 1f, end = 8) }
            grid.add(row, top = 8)
        }
        c.add(grid)

        val open = Db.orders(Order.BUY).filter { it.status != OrderStatus.CLOSED }.take(6)
        if (open.isNotEmpty()) {
            c.add(text("For an order? (optional)", 13f, true, MUTED).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
            val orow = hbox()
            open.forEach { o ->
                orow.add(pill(o.title.ifBlank { o.party }.take(22), orderId == o.id) { orderId = if (orderId == o.id) null else o.id; draw() }, WRAP, end = 8)
            }
            c.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(orow) })
        }

        val auto = w.hasPin && Ussd.enabled(this)
        c.add(text(if (auto) "🔒 Auto-PIN on — the app types your PIN after the fingerprint."
            else "Auto-PIN off — you'll type the PIN yourself.", 12.5f, true, if (auto) BRAND else AMBER).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(if (auto) GREEN_BG else AMBER_BG, dp(12).toFloat())
        }, top = 16)

        val send = button(if (busy) "Waiting for reply…" else "Send ${money(a)}") { send() }.apply {
            minHeight = dp(60); textSize = 18f; isEnabled = !busy && !Pending.active
            setIconResource(R.drawable.ic_fingerprint)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        }
        return bottomPage(c, send)
    }

    private fun roundIcon(res: Int, size: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = rounded(GREEN_BG, dp(14).toFloat())
        addView(icon(res, BRAND, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    // ---------- sending ----------
    private fun send() {
        val p = phone
        val a = amountValue()
        if (a <= 0 || p.length < 7) { step = 0; draw(); return }
        if (w.template.isBlank()) { toast("Add the USSD code for ${w.name} in Settings first"); return }
        if (Pending.active) { toast("Another send is still in progress"); return }
        val cat = categoryId
        if (cat == null) { toast("Choose what this payment is for"); return }
        val now = System.currentTimeMillis()
        val dup = Db.near(w.id, OUT, a, now - 10 * 60_000L, now + 60_000L)
            .filter { it.status != Status.FAILED && (it.phone.isEmpty() || last9(it.phone) == last9(p)) }
            .maxByOrNull { it.createdAt }
        if (dup != null) {
            val mins = ((now - dup.createdAt) / 60_000L).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Already sent?")
                .setMessage("You sent ${money(a)} to ${dup.who} ${if (mins == 0L) "less than a minute" else "$mins min"} ago.\n\nSend the same amount again?")
                .setPositiveButton("Send again") { _, _ -> doSend(p, a, cat) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        doSend(p, a, cat)
    }

    private fun doSend(p: String, a: Double, cat: Long) {
        val code = Ussd.code(w.template, p, amountText(a))
        val subtitle = "${money(a)} to ${who.ifBlank { p }} via ${w.name}"
        val ord = orderId
        if (w.hasPin && Ussd.enabled(this)) {
            val cipher = try {
                PinVault.decryptCipher(w.pinIv!!)
            } catch (e: KeyPermanentlyInvalidatedException) {
                PinVault.reset()
                toast("Your phone's fingerprints changed, so saved PINs were cleared for safety. Please save them again.")
                return
            } catch (e: Exception) {
                toast("Could not unlock the PIN: ${e.message}"); return
            }
            Bio.prompt(this, "Confirm send", subtitle, BiometricPrompt.CryptoObject(cipher),
                onOk = ok@{ result ->
                    val c = result.cryptoObject?.cipher ?: return@ok
                    val pin = try {
                        String(c.doFinal(PinVault.b64(w.pinEnc!!)), Charsets.UTF_8)
                    } catch (e: Exception) {
                        toast("Could not unlock the PIN: ${e.message}"); return@ok
                    }
                    withCallPermission { Pending.start(w, p, a, pin, cat, ord); dial(code) }
                },
                onError = { _, msg -> toast(msg) })
        } else {
            Bio.prompt(this, "Confirm send", subtitle, null,
                onOk = { withCallPermission { Manual.set(w, p, a, cat, ord); dial(code) } },
                onError = { _, msg -> toast(msg) })
        }
    }

    private fun withCallPermission(action: () -> Unit) {
        val need = mutableListOf(Manifest.permission.CALL_PHONE)
        if (w.sim > 0) need.add(Manifest.permission.READ_PHONE_STATE)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            afterPermission = action
            requestPermissions(missing.toTypedArray(), 7)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = afterPermission
        afterPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else toast("Phone permission is needed to dial the USSD code")
    }

    private fun dial(code: String) {
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code)))
        if (w.sim > 0) {
            try {
                val tm = getSystemService(TelecomManager::class.java)
                val accounts = tm.callCapablePhoneAccounts
                if (accounts.size >= w.sim) i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[w.sim - 1])
            } catch (_: SecurityException) {
            }
        }
        try {
            startActivity(i)
            busy = true
            if (step == 2) draw()
        } catch (e: Exception) {
            Pending.clear(); Manual.clear()
            toast("Could not dial: ${e.message}")
        }
    }

    private fun confirmedBySms(m: Wallet, ph: String, am: Double, since: Long): Tx? =
        Db.near(m.id, OUT, am, since - 60_000L, System.currentTimeMillis() + 60_000L)
            .firstOrNull { it.source == "SMS" && (it.phone.isEmpty() || last9(it.phone) == last9(ph)) && abs(it.amount - am) < 0.001 }

    private fun showResult(r: SendResult) {
        Pending.lastResult = null
        startActivity(Intent(this, ResultActivity::class.java)
            .putExtra("status", r.status).putExtra("wallet", r.walletId.takeIf { it > 0 } ?: w.id)
            .putExtra("amount", r.amount).putExtra("phone", r.phone).putExtra("name", who)
            .putExtra("category", r.categoryId ?: categoryId ?: -1L).putExtra("note", r.note))
        finish()
    }

    override fun onUnlockedResume() {
        Pending.lastResult?.let { showResult(it); return }
        if (busy && !Pending.active && Manual.wallet == null) busy = false
        val m = Manual.wallet
        if (m != null && Manual.dialed) {
            val ph = Manual.phone; val am = Manual.amount; val at = Manual.at; val cat = Manual.categoryId; val ord = Manual.orderId
            Manual.clear()
            fun attach(t: Tx) {
                if (cat != null) Db.setCategory(t.id, cat)
                if (ord != null) Db.linkTx(t.id, ord)
            }
            confirmedBySms(m, ph, am, at)?.let { attach(it); showResult(SendResult(Status.COMPLETED, m.name, am, ph, "Confirmed by SMS.", m.id, cat)); return }
            fun save(status: String) {
                val sms = if (status == Status.COMPLETED) confirmedBySms(m, ph, am, at) else null
                if (sms != null) attach(sms)
                else Db.addTx(Tx(walletId = m.id, walletName = m.name, direction = OUT, phone = ph, name = who, amount = am,
                    status = status, response = "Confirmed by hand in the app.", createdAt = System.currentTimeMillis(),
                    source = "APP", categoryId = cat, orderId = ord))
                showResult(SendResult(status, m.name, am, ph, "", m.id, cat))
            }
            AlertDialog.Builder(this)
                .setTitle("Did it go through?")
                .setMessage("${money(am)} to ${who.ifBlank { ph }} via ${m.name}")
                .setCancelable(false)
                .setPositiveButton("Yes, completed") { _, _ -> save(Status.COMPLETED) }
                .setNegativeButton("No, failed") { _, _ -> save(Status.FAILED) }
                .setNeutralButton("Not sure") { _, _ -> save(Status.UNKNOWN) }
                .show()
        } else if (step == 2) draw()
    }
}
