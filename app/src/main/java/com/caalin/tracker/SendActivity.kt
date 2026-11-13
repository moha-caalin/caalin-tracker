package com.caalin.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt

class SendActivity : BaseActivity() {
    private lateinit var w: Wallet
    private lateinit var phone: EditText
    private lateinit var amount: EditText
    private lateinit var preview: TextView
    private lateinit var info: TextView
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = Db.wallet(intent.getLongExtra("id", -1))
        if (loaded == null) { finish(); return }
        w = loaded
        title = "Send · ${w.name}"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val box = vbox(16)
        val form = vbox(16)
        form.add(hbox().apply { addView(dot(w.color, 18)); addView(text(w.name, 20f, true)) })
        form.add(text("Phone number", 13f, true, MUTED), top = 16)
        phone = field("e.g. 615123456", InputType.TYPE_CLASS_PHONE)
        form.add(phone)
        form.add(text("Amount ($)", 13f, true, MUTED), top = 12)
        amount = field("0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        form.add(amount)
        preview = text("", 14f, color = MUTED)
        form.add(preview, top = 12)
        box.add(card().apply { addView(form) })

        box.add(button("Send with fingerprint") { send() }.apply { textSize = 17f; minHeight = dp(56) }, top = 8)
        info = text("", 13f, color = MUTED)
        box.add(info, top = 12)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        phone.addTextChangedListener(watcher)
        amount.addTextChangedListener(watcher)
        updatePreview()
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun numberText() = phone.text.toString().filter { it.isDigit() || it == '+' }
    private fun amountValue() = amount.text.toString().trim().toDoubleOrNull() ?: 0.0
    private fun code() = Ussd.code(w.template, numberText(), amountText(amountValue()))

    private fun updatePreview() {
        preview.text = if (numberText().isNotEmpty() && amountValue() > 0) "Will dial: ${code()}" else "Will dial: ${w.template}"
    }

    private fun autoMode() = w.hasPin && Ussd.enabled(this)

    private fun refreshInfo() {
        w = Db.wallet(w.id) ?: w
        info.text = when {
            Pending.active -> "Waiting for the USSD reply…"
            autoMode() -> "🔒 Your fingerprint unlocks the saved PIN. The app types it and saves the result."
            w.hasPin -> "Auto-PIN is off: turn on \"Caalin Tracker auto-PIN\" in Accessibility settings. Until then you type the PIN yourself."
            else -> "No PIN saved for ${w.name}. You'll type the PIN yourself, then confirm here. Save a PIN in Money types to make it automatic."
        }
    }

    private fun send() {
        val p = numberText()
        val a = amountValue()
        if (p.length < 6) { phone.error = "Enter a valid number"; return }
        if (a <= 0) { amount.error = "Enter an amount"; return }
        if (w.template.isBlank()) { toast("Add the USSD code for ${w.name} in Money types first"); return }
        if (Pending.active) { toast("Another send is still in progress"); return }
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
                    withCallPermission {
                        Pending.start(w, p, a, pin)
                        dial(code)
                    }
                },
                onError = { _, msg -> toast(msg) })
        } else {
            Bio.prompt(this, "Confirm send", subtitle, null,
                onOk = { withCallPermission { Manual.set(w, p, a); dial(code) } },
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
        } catch (e: Exception) {
            Pending.clear(); Manual.clear()
            toast("Could not dial: ${e.message}")
        }
    }

    override fun onUnlockedResume() {
        refreshInfo()
        val m = Manual.wallet
        if (m != null && Manual.dialed) {
            val ph = Manual.phone
            val am = Manual.amount
            Manual.clear()
            fun save(status: String) {
                Db.addTx(Tx(0, m.id, m.name, ph, am, status, "Confirmed by hand in the app.", System.currentTimeMillis()))
                if (status == Status.COMPLETED) amount.setText("")
                toast("Saved: ${statusLabel(status)}")
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
        Pending.lastResult?.let { r ->
            Pending.lastResult = null
            if (r.status == Status.COMPLETED) amount.setText("")
            AlertDialog.Builder(this)
                .setTitle(statusLabel(r.status))
                .setMessage("${money(r.amount)} to ${r.phone} via ${r.walletName}\n\nSaved to History." +
                    if (r.status == Status.UNKNOWN) "\n\nThe app couldn't read a clear success or failure message. Open History to see what the USSD screen said." else "")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
