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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import kotlin.math.abs

/** One fast screen: tap a person, type the amount, Send. Everything sits within thumb reach. */
class SendActivity : BaseActivity() {
    private lateinit var w: Wallet
    private var phone = ""
    private var who = ""
    private var amountStr = ""
    private var categoryId: Long? = null
    private var userCat = false
    private var orderId: Long? = null
    private var afterPermission: (() -> Unit)? = null
    private var busy = false
    private var extra: Pair<String, String>? = null

    private lateinit var peopleRow: LinearLayout
    private lateinit var toLine: LinearLayout
    private lateinit var hints: LinearLayout
    private lateinit var amountView: TextView
    private lateinit var sumView: TextView
    private lateinit var catRow: LinearLayout
    private lateinit var catScroll: HorizontalScrollView
    private lateinit var toCard: LinearLayout
    private lateinit var sendBtn: com.google.android.material.button.MaterialButton

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) choose(normalizePhone(c.getString(0) ?: ""), c.getString(1) ?: "")
            }
        } catch (e: Exception) { toast("Couldn't read that contact") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = Db.wallet(intent.getLongExtra("id", -1))
        if (loaded == null) { finish(); return }
        w = loaded
        supportActionBar?.hide()
        window.statusBarColor = darker(w.color)

        val root = vbox()
        val head = hbox().apply {
            setPadding(dp(6), dp(8), dp(16), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(w.color, darker(w.color)))
        }
        head.add(FrameLayout(this).apply {
            addView(icon(R.drawable.ic_back, WHITE, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { finish() }
        }, dp(48), dp(48), end = 4)
        head.add(text("Send with ${w.name}", 17f, true, WHITE), 0, weight = 1f)
        Db.latestBalance(w.id)?.let { head.add(text(money(it), 15f, true, WHITE), WRAP) }
        root.add(head)

        val col = vbox(14)
        // Who: a clear card at the top, then the people to pick from
        val card = card()
        toCard = vbox(16)
        card.addView(toCard)
        card.setOnClickListener { if (phone.isEmpty()) newNumber() }
        col.add(card)
        toLine = hbox(); hints = hbox()
        peopleRow = hbox()
        col.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(peopleRow) }, top = 12)
        // Amount in the middle of the free space
        col.add(View(this), MATCH, 0, weight = 1f)
        amountView = text("$0", 46f, true, MUTED).apply { gravity = Gravity.CENTER }
        col.add(amountView, top = 4)
        sumView = text("", 14f, true, BRAND).apply { gravity = Gravity.CENTER }
        col.add(sumView)
        col.add(View(this), MATCH, 0, weight = 1f)
        val quick = hbox()
        listOf(1, 5, 10, 20, 50, 100).forEach { q -> quick.add(pill("$$q", false) { amountStr = q.toString(); refresh() }, WRAP, end = 8) }
        col.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, top = 8)
        // What for
        catRow = hbox()
        catScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(catRow) }
        col.add(catScroll, top = 10)
        // Keypad in thumb reach
        col.add(keypad(), top = 4)

        val sv = ScrollView(this).apply { isFillViewport = true; addView(col) }
        root.add(sv, MATCH, 0, weight = 1f)
        sendBtn = button("Send") { send() }.apply {
            minHeight = dp(58); textSize = 18f
            setIconResource(R.drawable.ic_fingerprint)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        }
        root.add(vbox().apply { setPadding(dp(14), dp(8), dp(14), dp(14)); setBackgroundColor(WHITE); elevation = dp(6).toFloat(); add(sendBtn) })
        setContentView(root)

        intent.getStringExtra("phone")?.let { p -> if (normalizePhone(p).length >= 7) { extra = normalizePhone(p) to (intent.getStringExtra("name") ?: ""); choose(extra!!.first, extra!!.second) } }
        drawPeople(); drawTo(); drawCats(); refresh()
    }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv); hsv[2] *= 0.7f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun amountValue() = Calc.eval(amountStr) ?: 0.0

    private fun choose(p: String, name: String) {
        if (p.length < 7) { toast("That number looks too short"); return }
        phone = p
        who = name.ifBlank { Db.displayName(p).takeIf { it != p } ?: "" }
        orderId = null
        if (!userCat) categoryId = Smart.categoryFor(p, OUT)
        if (::peopleRow.isInitialized) { drawPeople(); drawTo(); drawCats(); refresh() }
    }

    private fun drawPeople() {
        peopleRow.removeAllViews()
        fun avatar(label: String, sub: String, selected: Boolean, bg: Int, fg: Int, onClick: () -> Unit) {
            val v = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), 0, dp(4), 0); setOnClickListener { onClick() } }
            v.add(text(label, 20f, true, fg).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(bg); if (selected) setStroke(dp(3), w.color)
                }
            }, dp(58), dp(58))
            v.add(text(sub, 12f, selected, if (selected) INK else MUTED).apply { maxLines = 1; gravity = Gravity.CENTER; maxWidth = dp(72) }, WRAP, top = 4)
            peopleRow.add(v, WRAP, end = 6)
        }
        avatar("+", "New", false, GREEN_BG, BRAND) { newNumber() }
        val recents = (listOfNotNull(extra) + Db.recentRecipients(w.id).map { normalizePhone(it.first) to it.second })
            .distinctBy { it.first }.take(12)
        recents.forEach { (p, n) ->
            val name = (Db.personInfo(p)?.name?.takeIf { it.isNotBlank() } ?: n.ifBlank { p }).let { if (it == p) it else prettyName(it) }
            avatar(name.take(1).uppercase(), name.split(" ").first(), p == phone, 0xFFE9EEF0.toInt(), INK) { choose(p, name) }
        }
    }

    private fun newNumber() {
        val f = field("e.g. 617111194", InputType.TYPE_CLASS_PHONE).apply { textSize = 20f }
        val row = hbox()
        row.add(f, 0, weight = 1f, end = 10)
        row.add(FrameLayout(this).apply {
            background = rounded(GREEN_BG, dp(14).toFloat())
            addView(icon(R.drawable.ic_contacts, BRAND, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }, dp(52), dp(52))
        lateinit var dlg: AlertDialog
        row.getChildAt(1).setOnClickListener {
            dlg.dismiss()
            try { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
            catch (e: Exception) { toast("No contacts app found") }
        }
        f.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        f.setSingleLine()
        f.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); choose(normalizePhone(f.text.toString()), ""); true }
        dlg = AlertDialog.Builder(this).setTitle("Who are you paying?").setView(vbox(20).apply {
            add(row); add(text("Type the number, or tap the person icon to pick a contact.", 12.5f, color = MUTED), top = 8)
        })
            .setPositiveButton("Use") { _, _ -> choose(normalizePhone(f.text.toString()), "") }
            .setNegativeButton("Cancel", null).create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
        f.requestFocus()
    }

    private fun drawTo() {
        toCard.removeAllViews()
        if (phone.isEmpty()) {
            val r = hbox()
            r.add(badge(GREEN_BG, 46, R.drawable.ic_people, BRAND), dp(46), dp(46), end = 14)
            val m = vbox()
            m.add(text("Who are you paying?", 17f, true))
            m.add(text("Tap a person below, or + for a new number", 13f, color = MUTED), top = 2)
            r.add(m, 0, weight = 1f)
            toCard.add(r)
            return
        }
        val r = hbox()
        val name = who.ifBlank { phone }
        r.add(badge(w.color, 46, letter = name.take(1).uppercase()), dp(46), dp(46), end = 14)
        val m = vbox()
        m.add(text(name, 17f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        m.add(text(if (who.isNotBlank()) phone else "New number", 13f, color = MUTED), top = 2)
        r.add(m, 0, weight = 1f)
        r.add(text("Change", 14f, true, BRAND).apply { setPadding(dp(8), dp(8), 0, dp(8)); setOnClickListener { newNumber() } }, WRAP)
        toCard.add(r)
        // What this person is waiting for
        val hintRow = hbox()
        Smart.openOrders(phone, OUT).take(3).forEach { (o, mo) ->
            hintRow.add(pill("Order: ${o.title.ifBlank { "#${o.id}" }.take(16)} · ${money(mo.left)} left", orderId == o.id) {
                orderId = if (orderId == o.id) null else o.id
                if (orderId != null) { amountStr = amountText(mo.left); if (!userCat) Db.categoryByName("Stock purchase", OUT)?.let { categoryId = it } }
                drawTo(); drawCats(); refresh()
            }, WRAP, end = 8)
        }
        Db.debts().filter { !it.closed && it.direction == Debt.ME && last9(it.phone) == phone }.take(2).forEach { d ->
            hintRow.add(pill("You owe ${money(d.left)}", false) { amountStr = amountText(d.left); refresh() }, WRAP, end = 8)
        }
        if (hintRow.childCount > 0) toCard.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hintRow) }, top = 12)
    }

    private fun drawCats() {
        catRow.removeAllViews()
        catRow.add(text("For:", 13f, true, MUTED), WRAP, end = 8)
        Db.categories(OUT).forEach { c ->
            val sel = c.id == categoryId
            catRow.add(text(c.name, 14f, true, if (sel) WHITE else INK).apply {
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = rounded(if (sel) c.color else WHITE, dp(18).toFloat(), if (sel) 0 else dp(1), LINE)
                setOnClickListener { categoryId = c.id; userCat = true; drawCats(); refresh() }
            }, WRAP, end = 6)
        }
        catRow.add(text("+ New", 14f, true, BRAND).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { askText("New expense category") { n -> categoryId = Db.addCategory(n, OUT); userCat = true; drawCats(); refresh() } }
        }, WRAP)
        val idx = Db.categories(OUT).indexOfFirst { it.id == categoryId }
        catScroll.post {
            val target = if (idx >= 0) catRow.getChildAt(idx + 1) else null
            catScroll.scrollTo(if (target != null) (target.left - dp(60)).coerceAtLeast(0) else 0, 0)
        }
    }

    private fun keypad(): View {
        val pad = vbox()
        val keys = listOf(listOf("1", "2", "3", "+"), listOf("4", "5", "6", "−"), listOf("7", "8", "9", "×"), listOf(".", "0", "⌫", "="))
        for (r in keys) {
            val row = hbox()
            for (k in r) {
                val op = k in listOf("+", "−", "×", "=")
                row.add(text(k, 24f, !op, if (op) BRAND else INK).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11))
                    background = rounded(if (op) GREEN_BG else WHITE, dp(14).toFloat(), if (op) 0 else dp(1), LINE)
                    setOnClickListener {
                        when (k) {
                            "⌫" -> amountStr = amountStr.dropLast(1)
                            "=" -> Calc.eval(amountStr)?.let { amountStr = amountText(it) }
                            "." -> {
                                val part = amountStr.takeLastWhile { it.isDigit() || it == '.' }
                                if (!part.contains('.')) amountStr += if (part.isEmpty()) "0." else "."
                            }
                            else -> if (amountStr.length < 24) {
                                val last = amountStr.lastOrNull()
                                // no two signs in a row, and no sign at the start except minus
                                val badSign = k in listOf("+", "−", "×") && (last == null || last in "+−×.")
                                if (!badSign) amountStr += k
                            }
                        }
                        refresh()
                    }
                    if (k == "⌫") setOnLongClickListener { amountStr = ""; refresh(); true }
                }, 0, weight = 1f, end = if (k != r.last()) 8 else 0)
            }
            pad.add(row, top = 8)
        }
        return pad
    }

    private fun refresh() {
        amountView.text = "$" + amountStr.ifEmpty { "0" }.let { if (it.startsWith(".")) "0$it" else it }
        amountView.setTextColor(if (amountStr.isEmpty()) MUTED else INK)
        sumView.text = if (Calc.hasOperator(amountStr)) Calc.eval(amountStr)?.let { "= " + money(it) } ?: "…" else ""
        sumView.visibility = if (sumView.text.isEmpty()) View.GONE else View.VISIBLE
        val a = amountValue()
        val ready = phone.length >= 7 && a > 0 && categoryId != null && !busy && !Pending.active
        sendBtn.isEnabled = ready
        sendBtn.text = when {
            busy || Pending.active -> "Waiting for reply…"
            phone.isEmpty() -> "Choose who to pay"
            a <= 0 -> "Enter the amount"
            categoryId == null -> "Choose what it's for"
            else -> "Send ${money(a)}" + (if (who.isNotBlank()) " to ${who.split(" ").first()}" else "")
        }
    }

    // ---------- sending ----------
    private fun send() {
        val p = phone
        val a = amountValue()
        if (a <= 0 || p.length < 7) return
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
            refresh()
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
                else {
                    val id = Db.addTx(Tx(walletId = m.id, walletName = m.name, direction = OUT, phone = ph, name = who, amount = am,
                        status = status, response = "Confirmed by hand in the app.", createdAt = System.currentTimeMillis(),
                        source = "APP", categoryId = cat, orderId = ord))
                    if (status == Status.COMPLETED) Smart.onPayment(id)
                }
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
        } else refresh()
    }
}
