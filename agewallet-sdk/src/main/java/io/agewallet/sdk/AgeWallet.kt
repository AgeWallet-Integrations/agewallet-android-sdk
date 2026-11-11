package io.agewallet.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AgeWallet SDK for Android applications.
 *
 * Provides age verification via OIDC/PKCE flow.
 *
 * Example usage:
 * ```kotlin
 * val ageWallet = AgeWallet(context, AgeWalletConfig(
 *     clientId = "your-client-id",
 *     redirectUri = "https://yourapp.com/callback"
 * ))
 *
 * if (!ageWallet.isVerified()) {
 *     ageWallet.startVerification(activity)
 * }
 * ```
 */
class AgeWallet internal constructor(
    private val config: AgeWalletConfig,
    private val storage: Storage
) {
    /**
     * @param context Any context; the SDK keeps its state in EncryptedSharedPreferences of the application context
     * @param config Client ID, redirect URI, endpoints and default metadata
     */
    constructor(context: Context, config: AgeWalletConfig) : this(config, Storage(context.applicationContext))

    companion object {
        private const val TAG = "AgeWallet"

        /** Maximum byte length for the metadata string (matches server-side limit). */
        const val METADATA_MAX_BYTES = 4096
    }

    /**
     * Runtime metadata value. Initialised from the config default; mutable via setMetadata().
     * Used as the instance default the next time a verification flow starts (unless overridden per-call).
     */
    private var currentMetadata: String? = config.metadata

    /**
     * Check if the user is currently verified.
     * Returns true if verified and not expired, false otherwise.
     */
    fun isVerified(): Boolean {
        return storage.getVerification()?.isVerified == true
    }

    /**
     * Update the metadata default attached to subsequent verifications.
     * Pass null to clear. Validates length; throws IllegalArgumentException if > 4096 bytes.
     */
    fun setMetadata(value: String?) {
        validateMetadata(value)
        currentMetadata = value
    }

    /**
     * Return the metadata that round-tripped with the current persisted verification, or null.
     */
    fun getMetadata(): String? {
        return storage.getVerification()?.metadata
    }

    /**
     * Start the verification flow.
     * Opens Chrome Custom Tabs to the AgeWallet authorization page.
     *
     * @param context Activity context for launching the browser
     * @param metadata Optional per-call override; does NOT mutate the instance default.
     */
    fun startVerification(context: Context, metadata: String? = null) {
        // Per-call override wins over instance default.
        val effectiveMetadata = metadata ?: currentMetadata
        validateMetadata(effectiveMetadata)

        // Generate PKCE parameters
        val verifier = Security.generateVerifier()
        val challenge = Security.generateChallenge(verifier)
        // Prefix matches the netlify autoMap so the callback page can auto-fire
        // the intent for this demo's package. Without a recognized prefix the
        // netlify page falls through to a manual button view, requiring user
        // interaction to complete the OIDC chain.
        val state = "android:" + Security.generateState()
        val nonce = Security.generateNonce()

        // Store OIDC state for callback validation. Metadata round-trips through the server
        // via /userinfo, so we don't persist it locally here.
        storage.setOidcState(OidcState(state, verifier, nonce))

        // Build authorization URL
        val authUrl = buildAuthUrl(challenge, state, nonce, effectiveMetadata)

        // Open Chrome Custom Tabs
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.launchUrl(context, Uri.parse(authUrl))
    }

    private fun validateMetadata(value: String?) {
        if (value == null) return
        require(value.toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES) {
            "[AgeWallet] metadata exceeds $METADATA_MAX_BYTES-byte limit"
        }
    }

    /**
     * Handle callback intent from deep link.
     * Call this from your activity's onCreate or onNewIntent.
     *
     * @param intent The intent containing the callback URL
     * @return AgeWalletResult indicating the outcome
     */
    suspend fun handleCallback(intent: Intent): AgeWalletResult {
        val uri = intent.data ?: return AgeWalletResult.FAILED

        // Check if this is our callback
        if (!uri.toString().startsWith(config.redirectUri)) {
            return AgeWalletResult.FAILED
        }

        return processCallbackUri(uri)
    }

    private suspend fun processCallbackUri(uri: Uri): AgeWalletResult {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")

        // Handle error response
        if (error != null) {
            Log.e(TAG, "Authorization error: $error - $errorDescription")
            storage.clearOidcState()
            return if (errorDescription == "The user denied the request") AgeWalletResult.DENIED else AgeWalletResult.FAILED
        }

        // Validate required parameters
        if (code == null || state == null) {
            Log.e(TAG, "Missing code or state in callback")
            storage.clearOidcState()
            return AgeWalletResult.FAILED
        }

        // Validate state matches stored state
        val storedOidc = storage.getOidcState()
        if (storedOidc == null || storedOidc.state != state) {
            Log.e(TAG, "Invalid state or session expired")
            storage.clearOidcState()
            return AgeWalletResult.FAILED
        }

        return try {
            // Exchange code for tokens
            val tokenResponse = exchangeCode(code, storedOidc.verifier)
            if (tokenResponse == null) {
                storage.clearOidcState()
                return AgeWalletResult.FAILED
            }

            // Fetch user info to verify age claim
            val userInfo = fetchUserInfo(tokenResponse.accessToken)
            if (userInfo == null) {
                storage.clearOidcState()
                return AgeWalletResult.FAILED
            }

            // Check age_verified claim
            if (!userInfo.ageVerified) {
                Log.e(TAG, "Age verification failed")
                storage.clearOidcState()
                return AgeWalletResult.FAILED
            }

            // Store verification state (including any metadata round-tripped via /userinfo)
            storage.setVerification(
                VerificationState(
                    accessToken = tokenResponse.accessToken,
                    expiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000),
                    isVerified = true,
                    metadata = userInfo.metadata
                )
            )

            storage.clearOidcState()
            AgeWalletResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error during token exchange", e)
            storage.clearOidcState()
            AgeWalletResult.FAILED
        }
    }

    /**
     * Handle callback URL string directly.
     */
    suspend fun handleCallback(url: String): AgeWalletResult {
        val intent = Intent().apply {
            data = Uri.parse(url)
        }
        return handleCallback(intent)
    }

    /**
     * Clear all verification state (logout).
     */
    fun clearVerification() {
        storage.clearVerification()
        storage.clearOidcState()
    }

    private fun buildAuthUrl(challenge: String, state: String, nonce: String, metadata: String?): String {
        val params = mutableMapOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "scope" to "openid age",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "nonce" to nonce
        )

        if (!metadata.isNullOrEmpty()) {
            params["metadata"] = metadata
        }

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        return "${config.endpoints.auth}?$queryString"
    }

    private suspend fun exchangeCode(code: String, verifier: String): TokenResponse? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(config.endpoints.token)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true

                val params = mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to config.clientId,
                    "redirect_uri" to config.redirectUri,
                    "code" to code,
                    "code_verifier" to verifier
                )

                val body = params.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body)
                }

                if (connection.responseCode != 200) {
                    Log.e(TAG, "Token exchange failed: ${connection.responseCode}")
                    return@withContext null
                }

                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)

                TokenResponse(
                    accessToken = json.getString("access_token"),
                    expiresIn = json.optInt("expires_in", 3600)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange error", e)
                null
            }
        }

    private suspend fun fetchUserInfo(accessToken: String): UserInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(config.endpoints.userinfo)
                val connection = url.openConnection() as HttpURLConnection

                connection.setRequestProperty("Authorization", "Bearer $accessToken")

                if (connection.responseCode != 200) {
                    Log.e(TAG, "UserInfo fetch failed: ${connection.responseCode}")
                    return@withContext null
                }

                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)

                UserInfo(
                    ageVerified = json.optBoolean("age_verified", false),
                    metadata = if (json.has("metadata") && !json.isNull("metadata")) json.getString("metadata") else null
                )
            } catch (e: Exception) {
                Log.e(TAG, "UserInfo fetch error", e)
                null
            }
        }

    private data class TokenResponse(
        val accessToken: String,
        val expiresIn: Int
    )

    private data class UserInfo(
        val ageVerified: Boolean,
        val metadata: String?
    )
}
