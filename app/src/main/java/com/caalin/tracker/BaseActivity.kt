package com.caalin.tracker

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt

/** Every screen hides its content until the fingerprint unlocks the app. */
open class BaseActivity : AppCompatActivity() {
    private var prompting = false

    private val content: View get() = findViewById(android.R.id.content)

    override fun onResume() {
        super.onResume()
        if (AppLock.unlocked) {
            content.visibility = View.VISIBLE
            onUnlockedResume()
        } else {
            content.visibility = View.INVISIBLE
            unlock()
        }
    }

    open fun onUnlockedResume() {}

    private fun unlock() {
        if (prompting) return
        if (!Bio.available(this)) {
            AlertDialog.Builder(this)
                .setTitle("Fingerprint needed")
                .setMessage("Caalin Tracker is locked with your fingerprint. Add a fingerprint in your phone's Settings > Security, then open the app again.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finishAffinity() }
                .show()
            return
        }
        prompting = true
        Bio.prompt(this, "Unlock Caalin Tracker", "Touch the fingerprint sensor", null,
            onOk = {
                prompting = false
                AppLock.unlocked = true
                content.visibility = View.VISIBLE
                onUnlockedResume()
            },
            onError = { code, msg ->
                prompting = false
                if (code != BiometricPrompt.ERROR_NEGATIVE_BUTTON && code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_CANCELED) toast(msg)
                finishAffinity()
            })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
