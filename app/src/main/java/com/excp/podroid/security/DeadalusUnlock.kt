package com.excp.podroid.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * One-tap unlock for the Deadalus launcher icon.
 *
 * WHY THE SECRET LIVES HERE AND NOT IN THE GUEST
 * An autologin switch inside the Alpine guest (a marker file that makes
 * `login -f root` skip authentication) would have given the same number of
 * taps, but it makes the console permanently passwordless for anyone holding
 * the phone, and it cancels out the forced default-password change that
 * podroid-login performs on first run. So the console keeps a real password
 * and the convenience lives here: the password is stored encrypted, released
 * only after a biometric prompt, and typed into the terminal by the app.
 *
 * Without a matching fingerprint the terminal behaves exactly as before — a
 * plain login prompt. Losing the phone unlocked does not hand over the guest.
 *
 * The key is generated in the AndroidKeyStore and never leaves it;
 * EncryptedSharedPreferences encrypts both key and value.
 */
object DeadalusUnlock {

    private const val PREFS = "deadalus_unlock"
    private const val KEY_PASSWORD = "guest_password"
    private const val KEY_USER = "guest_user"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** True once the operator has stored the guest password for one-tap use. */
    fun hasCredential(context: Context): Boolean =
        runCatching { prefs(context).contains(KEY_PASSWORD) }.getOrDefault(false)

    fun storeCredential(context: Context, user: String, password: String) {
        prefs(context).edit()
            .putString(KEY_USER, user)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clearCredential(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }

    fun storedUser(context: Context): String =
        runCatching { prefs(context).getString(KEY_USER, "root") ?: "root" }.getOrDefault("root")

    /**
     * Can this device actually prompt for a fingerprint (or equivalent strong
     * biometric)? If not, the caller must fall back to a manual login rather
     * than releasing the secret — never silently.
     */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompt, then hand the password to [onUnlocked]. [onFailed] fires for a
     * cancel, a lockout, or a device that cannot authenticate — the caller
     * shows the normal login prompt in that case.
     */
    fun unlock(
        activity: FragmentActivity,
        onUnlocked: (user: String, password: String) -> Unit,
        onFailed: (reason: String) -> Unit,
    ) {
        if (!canAuthenticate(activity)) {
            onFailed("no biometric enrolled")
            return
        }
        if (!hasCredential(activity)) {
            onFailed("no stored credential")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val p = runCatching { prefs(activity).getString(KEY_PASSWORD, null) }.getOrNull()
                    if (p.isNullOrEmpty()) onFailed("credential unreadable")
                    else onUnlocked(storedUser(activity), p)
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) = onFailed(msg.toString())

                // Not terminal: the prompt stays up for another try. Reporting
                // it as a failure here would drop the operator to a manual
                // login on the first bad read of a finger.
                override fun onAuthenticationFailed() = Unit
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Deadalus")
                .setSubtitle("Unlock the pod terminal")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
        )
    }
}
