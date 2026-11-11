package io.agewallet.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Secure storage for verification and OIDC state, as JSON strings in [prefs].
 * Storage(context) keeps them in EncryptedSharedPreferences; unit tests pass plain SharedPreferences.
 */
internal class Storage(private val prefs: SharedPreferences) {
    companion object {
        private const val PREFS_NAME = "io.agewallet.sdk.prefs"
        private const val KEY_VERIFICATION = "verification"
        private const val KEY_OIDC = "oidc"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    constructor(context: Context) : this(encryptedPrefs(context))

    /**
     * Get stored verification state.
     */
    fun getVerification(): VerificationState? {
        val json = prefs.getString(KEY_VERIFICATION, null) ?: return null

        return try {
            val obj = JSONObject(json)
            val state = VerificationState(
                accessToken = obj.getString("accessToken"),
                expiresAt = obj.getLong("expiresAt"),
                isVerified = obj.getBoolean("isVerified"),
                metadata = if (obj.has("metadata") && !obj.isNull("metadata")) obj.getString("metadata") else null
            )

            // Auto-clear if expired
            if (state.isExpired) {
                clearVerification()
                null
            } else {
                state
            }
        } catch (e: Exception) {
            clearVerification()
            null
        }
    }

    /**
     * Store verification state.
     */
    fun setVerification(state: VerificationState) {
        val json = JSONObject().apply {
            put("accessToken", state.accessToken)
            put("expiresAt", state.expiresAt)
            put("isVerified", state.isVerified)
            if (state.metadata != null) put("metadata", state.metadata)
        }
        prefs.edit().putString(KEY_VERIFICATION, json.toString()).apply()
    }

    /**
     * Clear verification state.
     */
    fun clearVerification() {
        prefs.edit().remove(KEY_VERIFICATION).apply()
    }

    /**
     * Get stored OIDC state.
     */
    fun getOidcState(): OidcState? {
        val json = prefs.getString(KEY_OIDC, null) ?: return null

        return try {
            val obj = JSONObject(json)
            OidcState(
                state = obj.getString("state"),
                verifier = obj.getString("verifier"),
                nonce = obj.getString("nonce")
            )
        } catch (e: Exception) {
            clearOidcState()
            null
        }
    }

    /**
     * Store OIDC state.
     */
    fun setOidcState(state: OidcState) {
        val json = JSONObject().apply {
            put("state", state.state)
            put("verifier", state.verifier)
            put("nonce", state.nonce)
        }
        prefs.edit().putString(KEY_OIDC, json.toString()).apply()
    }

    /**
     * Clear OIDC state.
     */
    fun clearOidcState() {
        prefs.edit().remove(KEY_OIDC).apply()
    }
}
