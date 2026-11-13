package com.caalin.tracker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt

class WalletEditActivity : BaseActivity() {
    private var id = -1L
    private var color = PALETTE[0]
    private lateinit var name: EditText
    private lateinit var template: EditText
    private lateinit var sim: Spinner
    private lateinit var rules: EditText
    private lateinit var success: EditText
    private lateinit var fail: EditText
    private lateinit var smsTag: EditText
    private lateinit var pinStatus: TextView
    private lateinit var colors: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        id = intent.getLongExtra("id", -1L)
        val w = if (id > 0) Db.wallet(id) else null
        if (w != null) color = w.color
        title = if (w == null) "New money type" else "Edit ${w.name}"

        val box = vbox(16)

        // Basics
        val basic = vbox(16)
        basic.add(text("Name", 13f, true, MUTED))
        name = field("e.g. EVC Plus", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, w?.name ?: "")
        basic.add(name)
        basic.add(text("Colour", 13f, true, MUTED), top = 12)
        colors = hbox()
        basic.add(colors, top = 6)
        drawColors()
        basic.add(text("Send-money USSD code", 13f, true, MUTED), top = 12)
        template = field("*712*{NUMBER}*{AMOUNT}#", InputType.TYPE_CLASS_TEXT, w?.template ?: "")
        basic.add(template)
        basic.add(text("Use {NUMBER} and {AMOUNT} where the phone number and amount go.", 12f, color = MUTED))
        basic.add(text("SIM card", 13f, true, MUTED), top = 12)
        sim = Spinner(this)
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Ask / phone default", "SIM 1", "SIM 2"))
        sim.setSelection((w?.sim ?: 0).coerceIn(0, 2))
        basic.add(sim)
        box.add(card().apply { addView(basic) }, top = 4)

        // PIN
        val pin = vbox(16)
        pin.add(text("PIN (auto-complete)", 16f, true))
        pin.add(text("Saved encrypted on this phone. Only your fingerprint can unlock it, and only to type it into the USSD screen.", 13f, color = MUTED), top = 4)
        pinStatus = text("", 14f, true)
        pin.add(pinStatus, top = 8)
        val pinButtons = hbox()
        pinButtons.add(button("Set / change PIN") { askPin() }, 0, weight = 1f)
        pinButtons.add(View(this), dp(8))
        pinButtons.add(button("Remove PIN", outlined = true) { removePin() }, 0, weight = 1f)
        pin.add(pinButtons, top = 8)
        box.add(card().apply { addView(pin) }, top = 12)

        // Advanced
        val adv = vbox(16)
        adv.add(text("USSD screen matching (advanced)", 16f, true))
        adv.add(text("Prompts: one per line, \"words on screen => answer\". {PIN} is replaced by your PIN. Each answer is given once per send.", 12f, color = MUTED), top = 4)
        rules = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, w?.rules ?: Defaults.PIN_RULES)
        adv.add(rules)
        adv.add(text("Success words (comma separated)", 13f, true, MUTED), top = 12)
        success = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, w?.successKw ?: Defaults.SUCCESS)
        adv.add(success)
        adv.add(text("Failure words (comma separated)", 13f, true, MUTED), top = 12)
        fail = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, w?.failKw ?: Defaults.FAIL)
        adv.add(fail)
        adv.add(text("SMS name (word in this wallet's messages)", 13f, true, MUTED), top = 12)
        smsTag = field("e.g. evcplus", InputType.TYPE_CLASS_TEXT, w?.smsTag ?: "")
        adv.add(smsTag)
        adv.add(text("Tip: after a test send, open it in History to see exactly what the USSD screens said, and copy words from there.", 12f, color = MUTED), top = 8)
        box.add(card().apply { addView(adv) }, top = 12)

        box.add(button("Save") { if (save() > 0) finish() }.apply { minHeight = dp(54) }, top = 8)
        if (w != null) {
            box.add(button("Delete this money type", outlined = true) {
                AlertDialog.Builder(this).setMessage("Delete ${w.name}? Its saved transactions stay in History.")
                    .setPositiveButton("Delete") { _, _ -> Db.deleteWallet(w.id); finish() }
                    .setNegativeButton("Cancel", null).showKb()
            })
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onUnlockedResume() = refreshPin()

    private fun drawColors() {
        colors.removeAllViews()
        for (c in PALETTE) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(c)
                if (c == this@WalletEditActivity.color) setStroke(dp(3), INK)
            }
            v.setOnClickListener { color = c; drawColors() }
            colors.add(v, dp(34), dp(34)).let { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) }
        }
    }

    private fun refreshPin() {
        val w = if (id > 0) Db.wallet(id) else null
        val has = w?.hasPin == true
        pinStatus.text = if (has) "🔒 PIN saved" else "No PIN saved — you'll type it yourself"
        pinStatus.setTextColor(if (has) BRAND else MUTED)
    }

    /** Saves the form; returns the id or -1 if invalid. */
    private fun save(): Long {
        val n = name.text.toString().trim()
        if (n.isEmpty()) { name.error = "Enter a name"; return -1 }
        val t = template.text.toString().trim()
        if (t.isNotEmpty() && !t.contains("{NUMBER}")) {
            template.error = "The code needs {NUMBER} where the phone number goes"; return -1
        }
        val old = if (id > 0) Db.wallet(id) else null
        id = Db.saveWallet(Wallet(id, n, color, t, sim.selectedItemPosition, old?.pinEnc, old?.pinIv,
            rules.text.toString().trim(), success.text.toString().trim(), fail.text.toString().trim(),
            smsTag.text.toString().trim()))
        title = "Edit $n"
        return id
    }

    private fun askPin() {
        if (save() <= 0) return
        if (!Bio.available(this)) { toast("Add a fingerprint in your phone settings first"); return }
        val form = vbox(20)
        val p1 = field("PIN", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val p2 = field("Repeat PIN", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        form.add(p1); form.add(p2, top = 8)
        AlertDialog.Builder(this)
            .setTitle("PIN for ${name.text.toString().trim()}")
            .setView(form)
            .setPositiveButton("Save with fingerprint") { _, _ ->
                val a = p1.text.toString(); val b = p2.text.toString()
                when {
                    a.length < 4 -> toast("PIN is too short")
                    a != b -> toast("The two PINs don't match")
                    else -> encryptAndSave(a)
                }
            }
            .setNegativeButton("Cancel", null)
            .showKb()
    }

    private fun encryptAndSave(pin: String) {
        val cipher = try {
            PinVault.encryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            PinVault.reset()
            try { PinVault.encryptCipher() } catch (e2: Exception) { toast("Could not prepare secure storage: ${e2.message}"); return }
        } catch (e: Exception) {
            toast("Could not prepare secure storage: ${e.message}"); return
        }
        Bio.prompt(this, "Save PIN", "Confirm with your fingerprint", BiometricPrompt.CryptoObject(cipher),
            onOk = ok@{ result ->
                val c = result.cryptoObject?.cipher ?: return@ok
                try {
                    val enc = c.doFinal(pin.toByteArray(Charsets.UTF_8))
                    Db.setPin(id, PinVault.b64(enc), PinVault.b64(c.iv))
                    toast("PIN saved 🔒")
                } catch (e: Exception) {
                    toast("Could not save PIN: ${e.message}")
                }
                refreshPin()
            },
            onError = { _, msg -> toast(msg) })
    }

    private fun removePin() {
        if (id <= 0) return
        Db.setPin(id, null, null)
        refreshPin()
        toast("PIN removed")
    }
}
