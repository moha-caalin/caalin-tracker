package com.caalin.tracker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt

/** Every screen is covered by a lock screen until the fingerprint unlocks the app. Cancelling never closes the app. */
open class BaseActivity : AppCompatActivity() {
    private var prompting = false
    private var overlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlay != null) moveTaskToBack(true)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (AppLock.unlocked) {
            hideOverlay()
            onUnlockedResume()
        } else {
            showOverlay()
            unlock()
        }
    }

    open fun onUnlockedResume() {}

    private fun showOverlay() {
        if (overlay != null) return
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BRAND, DEEP))
            isClickable = true
            isFocusable = true
        }
        val col = vbox(32).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(badge(0x26FFFFFF, 96, R.drawable.ic_fingerprint, WHITE), dp(96), dp(96))
        col.add(text("Caalin Tracker", 24f, true, WHITE), WRAP, top = 22)
        col.add(text("Locked to keep your money safe", 14f, color = 0xCCFFFFFF.toInt()), WRAP, top = 6)
        col.add(text("Unlock", 16f, true, BRAND).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(14), dp(40), dp(14))
            background = rounded(WHITE, dp(28).toFloat())
            setOnClickListener { unlock() }
        }, WRAP, top = 36)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        (window.decorView as ViewGroup).addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = root
    }

    private fun hideOverlay() {
        overlay?.let { (window.decorView as ViewGroup).removeView(it) }
        overlay = null
    }

    private fun unlock() {
        if (prompting) return
        if (!Bio.available(this)) {
            toast("Add a fingerprint in your phone's Settings › Security to use Caalin Tracker.")
            return
        }
        prompting = true
        Bio.prompt(this, "Unlock Caalin Tracker", "Touch the fingerprint sensor", null,
            onOk = {
                prompting = false
                AppLock.unlocked = true
                hideOverlay()
                onUnlockedResume()
            },
            onError = { code, msg ->
                prompting = false
                if (code != BiometricPrompt.ERROR_NEGATIVE_BUTTON && code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_CANCELED) toast(msg)
            })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
