package com.caalin.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import kotlin.math.abs

class SendActivity : BaseActivity() {
    private lateinit var w: Wallet
    private lateinit var phone: EditText
    private lateinit var amount: EditText
    private lateinit var preview: TextView
    private lateinit var info: TextView
    private lateinit var infoBox: View
    private var afterPermission: (() -> Unit)? = null
    private var categoryId: Long? = null
    private var userPicked = false
    private lateinit var catHolder: android.widget.FrameLayout
    private lateinit var sendBtn: com.google.android.material.button.MaterialButton
    private lateinit var sumHint: TextView
    private val pickContact = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    phone.setText(normalizePhone(c.getString(0) ?: ""))
                    toast(c.getString(1) ?: "")
                    amount.requestFocus()
                }
            }
        } catch (e: Exception) { toast("Couldn't read that contact") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = Db.wallet(intent.getLongExtra("id", -1))
        if (loaded == null) { finish(); return }
        w = loaded
        title = "Send money"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0f

        val page = vbox(16)

        // Wallet header
        val head = hbox().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(w.color, darker(w.color))).apply {
                cornerRadius = dp(20).toFloat()
            }
        }
        head.add(badge(0x33FFFFFF, 46, letter = w.name.take(1).uppercase()), dp(46), dp(46), end = 14)
        val ht = vbox()
        ht.add(text(w.name, 20f, true, WHITE))
        val bal = Db.latestBalance(w.id)
        ht.add(text(if (bal != null) "Balance ${money(bal)}" else "Send with USSD", 13f, color = 0xDDFFFFFF.toInt()), top = 2)
        head.add(ht, 0, weight = 1f)
        page.add(head)

        // Form
        val form = card()
        val f = vbox(18)
        f.add(text("Amount", 13f, true, MUTED))
        amount = EditText(this).apply {
            hint = "$0"; textSize = 36f; inputType = InputType.TYPE_CLASS_PHONE
            background = null; setPadding(0, dp(4), 0, dp(4)); setTextColor(INK)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val ar = hbox()
        ar.add(amount, 0, weight = 1f)
        ar.add(roundIcon(R.drawable.ic_calc) {
            Calc.show(this, amount.text.toString()) { v -> amount.setText(amountText(v)); amount.setSelection(amount.text.length) }
        }, dp(48), dp(48))
        f.add(ar)
        sumHint = text("", 14f, true, BRAND)
        f.add(sumHint)
        f.add(View(this).apply { setBackgroundColor(LINE) }, MATCH, dp(1), top = 4)
        val quick = hbox()
        listOf(1, 5, 10, 20, 50, 100).forEach { q ->
            quick.add(pill("$$q", false) { amount.setText(q.toString()); amount.setSelection(amount.text.length) }, WRAP, end = 6)
        }
        f.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, top = 10)
        f.add(text("Phone number", 13f, true, MUTED), top = 16)
        phone = field("e.g. 617111194", InputType.TYPE_CLASS_PHONE)
        val pr = hbox()
        pr.add(phone, 0, weight = 1f, end = 10)
        pr.add(roundIcon(R.drawable.ic_contacts) {
            try {
                pickContact.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } catch (e: Exception) { toast("No contacts app found") }
        }, dp(48), dp(48))
        f.add(pr, top = 6)

        val recents = Db.recentRecipients(w.id)
        if (recents.isNotEmpty()) {
            f.add(text("Recent", 13f, true, MUTED), top = 14)
            val chips = hbox()
            recents.forEach { (ph, nm) ->
                val label = nm.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ph
                chips.add(vbox().apply {
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = rounded(0xFFF4F7F5.toInt(), dp(14).toFloat(), dp(1), LINE)
                    add(text(label, 14f, true), WRAP)
                    if (label != ph) add(text(ph, 11.5f, color = MUTED), WRAP)
                    setOnClickListener { phone.setText(ph); amount.requestFocus() }
                }, WRAP, end = 8)
            }
            f.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, top = 6)
        }
        f.add(text("Category", 13f, true, MUTED), top = 16)
        catHolder = android.widget.FrameLayout(this)
        f.add(catHolder, top = 6)
        drawCats()
        preview = text("", 13f, color = MUTED)
        f.add(preview, top = 14)
        form.addView(f)
        page.add(form, top = 14)

        val info2 = hbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        info = text("", 13f, color = INK)
        info2.add(info, 0, weight = 1f)
        infoBox = info2
        page.add(info2, top = 12)

        sendBtn = button("Send with fingerprint") { send() }
        page.add(sendBtn.apply {
            minHeight = dp(58); textSize = 17f
            setIconResource(R.drawable.ic_fingerprint); iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        }, top = 14)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
                if (!userPicked) {
                    val g = Db.guessCategory(numberText(), OUT)
                    if (g != categoryId) { categoryId = g; drawCats() }
                }
            }
        }
        phone.addTextChangedListener(watcher)
        amount.addTextChangedListener(watcher)
        updatePreview()
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun drawCats() {
        catHolder.removeAllViews()
        catHolder.addView(categoryChips(OUT, categoryId, { c ->
            categoryId = if (categoryId == c.id) null else c.id; userPicked = true; drawCats()
        }, {
            askText("New expense category") { n -> categoryId = Db.addCategory(n, OUT); userPicked = true; drawCats() }
        }))
    }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv); hsv[2] *= 0.7f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun roundIcon(res: Int, onClick: () -> Unit): View = android.widget.FrameLayout(this).apply {
        background = rounded(GREEN_BG, dp(14).toFloat())
        addView(icon(res, BRAND, 22), android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun numberText() = normalizePhone(phone.text.toString())
    private fun amountValue() = Calc.eval(amount.text.toString().trim()) ?: 0.0
    private fun code() = Ussd.code(w.template, numberText(), amountText(amountValue()))

    private fun updatePreview() {
        val raw = amount.text.toString()
        sumHint.text = if (Calc.hasOperator(raw)) Calc.eval(raw)?.let { "= " + money(it) } ?: "…" else ""
        sumHint.visibility = if (sumHint.text.isEmpty()) View.GONE else View.VISIBLE
        preview.text = "Dials  " + if (numberText().isNotEmpty() && amountValue() > 0) code() else w.template
    }

    private fun autoMode() = w.hasPin && Ussd.enabled(this)

    private fun refreshInfo() {
        w = Db.wallet(w.id) ?: w
        val (msg, ok) = when {
            Pending.active -> "Waiting for the USSD reply…" to true
            autoMode() -> "🔒 Auto-PIN is on. Your fingerprint unlocks the saved PIN; the app types it and saves the result." to true
            !w.hasPin -> "Auto-PIN is off: no PIN saved for ${w.name}. You'll type the PIN yourself. Save it in Settings › Money types." to false
            else -> "Auto-PIN is off: turn on \"Caalin Tracker auto-PIN\" in Accessibility (see Settings › Setup). You'll type the PIN yourself." to false
        }
        info.text = msg
        info.setTextColor(if (ok) BRAND else AMBER)
        infoBox.background = rounded(if (ok) GREEN_BG else AMBER_BG, dp(14).toFloat())
    }

    private fun send() {
        val p = numberText()
        val a = amountValue()
        if (a <= 0) { amount.error = "Enter an amount"; return }
        if (p.length < 6) { phone.error = "Enter a valid number"; return }
        if (w.template.isBlank()) { toast("Add the USSD code for ${w.name} in Settings first"); return }
        if (Pending.active) { toast("Another send is still in progress"); return }
        val cat = categoryId
        if (cat == null) { toast("Choose a category first"); return }
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
        val code = code()
        val subtitle = "${money(a)} to $p via ${w.name}"

        if (autoMode()) {
            val cipher = try {
                PinVault.decryptCipher(w.pinIv!!)
            } catch (e: KeyPermanentlyInvalidatedException) {
                PinVault.reset(); refreshInfo()
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
                    withCallPermission { Pending.start(w, p, a, pin, cat); dial(code) }
                },
                onError = { _, msg -> toast(msg) })
        } else {
            Bio.prompt(this, "Confirm send", subtitle, null,
                onOk = { withCallPermission { Manual.set(w, p, a, cat); dial(code) } },
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
            sendBtn.isEnabled = false
        } catch (e: Exception) {
            Pending.clear(); Manual.clear()
            toast("Could not dial: ${e.message}")
        }
    }

    /** True when an SMS for this send was already saved, so asking (and saving twice) is not needed. */
    private fun confirmedBySms(m: Wallet, ph: String, am: Double, since: Long): Boolean =
        Db.near(m.id, OUT, am, since - 60_000L, System.currentTimeMillis() + 60_000L)
            .any { it.source == "SMS" && (it.phone.isEmpty() || last9(it.phone) == last9(ph)) && abs(it.amount - am) < 0.001 }

    override fun onUnlockedResume() {
        refreshInfo()
        sendBtn.isEnabled = !Pending.active
        // A finished auto-PIN send: close this screen so nothing is sent twice; Home shows the result.
        if (Pending.lastResult != null) { finish(); return }
        val m = Manual.wallet
        if (m != null && Manual.dialed) {
            val ph = Manual.phone
            val am = Manual.amount
            val at = Manual.at
            val cat = Manual.categoryId
            Manual.clear()
            if (confirmedBySms(m, ph, am, at)) {
                attachCategoryToSms(m, ph, am, at, cat)
                Pending.lastResult = SendResult(Status.COMPLETED, m.name, am, ph, "Confirmed by SMS.")
                finish(); return
            }
            fun save(status: String) {
                if (status == Status.COMPLETED && confirmedBySms(m, ph, am, at)) {
                    attachCategoryToSms(m, ph, am, at, cat)
                } else {
                    Db.addTx(Tx(walletId = m.id, walletName = m.name, direction = OUT, phone = ph, name = "", amount = am,
                        status = status, response = "Confirmed by hand in the app.", createdAt = System.currentTimeMillis(),
                        source = "APP", categoryId = cat))
                }
                Pending.lastResult = SendResult(status, m.name, am, ph)
                finish()
            }
            AlertDialog.Builder(this)
                .setTitle("Did it go through?")
                .setMessage("${money(am)} to $ph via ${m.name}")
                .setCancelable(false)
                .setPositiveButton("Yes, completed") { _, _ -> save(Status.COMPLETED) }
                .setNegativeButton("No, failed") { _, _ -> save(Status.FAILED) }
                .setNeutralButton("Not sure") { _, _ -> save(Status.UNKNOWN) }
                .show()
        }
    }

    private fun attachCategoryToSms(m: Wallet, ph: String, am: Double, since: Long, cat: Long?) {
        if (cat == null) return
        Db.near(m.id, OUT, am, since - 60_000L, System.currentTimeMillis() + 60_000L)
            .firstOrNull { it.source == "SMS" && (it.phone.isEmpty() || last9(it.phone) == last9(ph)) }
            ?.let { Db.setCategory(it.id, cat) }
    }
}
