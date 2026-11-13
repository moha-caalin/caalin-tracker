package com.caalin.tracker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Fingerprint prompt helper. */
object Bio {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun available(c: Context) = BiometricManager.from(c).canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        a: FragmentActivity,
        title: String,
        subtitle: String,
        crypto: BiometricPrompt.CryptoObject?,
        onOk: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, CharSequence) -> Unit,
    ) {
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk(result)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(STRONG)
            .build()
        if (crypto == null) p.authenticate(info) else p.authenticate(info, crypto)
    }
}

/** PINs are encrypted with a hardware-backed key that only unlocks after a fingerprint. */
object PinVault {
    private const val ALIAS = "caalin_pin_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (store().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(b.build())
        return g.generateKey()
    }

    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }

    fun decryptCipher(iv: String): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b64(iv))) }

    /** Called when fingerprints on the phone changed: the old key is gone, so PINs must be set again. */
    fun reset() {
        try { store().deleteEntry(ALIAS) } catch (_: Exception) {}
        Db.clearAllPins()
    }

    fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
}

/** Locks the whole app when it has been in the background for more than a minute. */
object AppLock : Application.ActivityLifecycleCallbacks {
    var unlocked = false
    private var started = 0
    private var backgroundAt = 0L

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1 && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt >= Prefs.lockAfterSec * 1000L) unlocked = false
    }

    override fun onActivityStopped(activity: Activity) {
        started--
        if (started <= 0) { started = 0; backgroundAt = System.currentTimeMillis() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        registerActivityLifecycleCallbacks(AppLock)
    }
}
