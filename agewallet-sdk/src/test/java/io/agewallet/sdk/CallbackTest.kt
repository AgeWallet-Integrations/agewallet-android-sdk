package io.agewallet.sdk

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgeWallet.handleCallback(): the callback checks, the token exchange and userinfo (against MockWebServer), and the
 * saved verification. The saved OIDC state is state "android:abc", verifier "v1", nonce "n1".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallbackTest {
    @get:Rule
    val server = MockWebServer()

    private lateinit var prefs: SharedPreferences
    private lateinit var storage: Storage
    private lateinit var wallet: AgeWallet

    @Before
    fun setUp() {
        server.failFastWhenEmpty()
        prefs = testPrefs()
        storage = Storage(prefs)
        wallet = testWallet(prefs, endpointsOn(server))
        storage.setOidcState(OidcState("android:abc", "v1", "n1"))
    }

    private fun callback(state: String = "android:abc") = "$REDIRECT_URI?code=c1&state=${Uri.encode(state)}"

    private fun enqueueVerified(token: String = """{"access_token":"tok","expires_in":3600}""", userinfo: String = """{"sub":"u1","age_verified":true,"metadata":"m1"}""") {
        server.enqueue(reply(token))
        server.enqueue(reply(userinfo))
    }

    // Rejected before any request

    @Test
    fun aUrlThatIsNotTheRedirectUriFailsAndKeepsTheSavedState() = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback("https://other.example/callback?code=c1&state=android%3Aabc"))
        assertNotNull(storage.getOidcState())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anIntentWithNoUrlFails() = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback(Intent()))
    }

    @Test
    fun aDeniedRequestReturnsDeniedAndClearsTheSavedState() = runTest {
        val result = wallet.handleCallback("$REDIRECT_URI?error=access_denied&error_description=The%20user%20denied%20the%20request")

        assertEquals(AgeWalletResult.DENIED, result)
        assertNull(storage.getOidcState())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anyOtherErrorReturnsFailedAndClearsTheSavedState() = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback("$REDIRECT_URI?error=server_error&error_description=Oops"))
        assertNull(storage.getOidcState())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMissingCodeOrStateFails() = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback("$REDIRECT_URI?state=android%3Aabc"))
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback("$REDIRECT_URI?code=c1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aStateThatDoesNotMatchFailsAndClearsTheSavedState() = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback(callback("android:other")))
        assertNull(storage.getOidcState())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noSavedStateFails() = runTest {
        storage.clearOidcState()
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback(callback()))
        assertEquals(0, server.requestCount)
    }

    // A valid callback

    @Test
    fun exchangesTheCodeWithTheSavedVerifierCallsUserinfoAndSavesTheVerification() = runTest {
        enqueueVerified()
        val before = System.currentTimeMillis()

        assertEquals(AgeWalletResult.SUCCESS, wallet.handleCallback(callback()))
        val after = System.currentTimeMillis()

        val token = server.takeRequest()
        assertEquals("POST", token.method)
        assertEquals("/token", token.path)
        assertEquals("application/x-www-form-urlencoded", token.getHeader("Content-Type"))
        assertEquals(
            mapOf("grant_type" to "authorization_code", "client_id" to "cid", "redirect_uri" to REDIRECT_URI, "code" to "c1", "code_verifier" to "v1"),
            token.formFields()
        )
        val userinfo = server.takeRequest()
        assertEquals("GET", userinfo.method)
        assertEquals("/userinfo", userinfo.path)
        assertEquals("Bearer tok", userinfo.getHeader("Authorization"))

        assertTrue(wallet.isVerified())
        assertEquals("m1", wallet.getMetadata())
        val saved = storage.getVerification()!!
        assertEquals("tok", saved.accessToken)
        assertTrue(saved.expiresAt in (before + 3_600_000)..(after + 3_600_000))
        assertNull(storage.getOidcState())
    }

    @Test
    fun aTokenWithNoExpiresInLastsAnHour() = runTest {
        enqueueVerified(token = """{"access_token":"tok"}""")
        val before = System.currentTimeMillis()

        wallet.handleCallback(callback())

        assertTrue(storage.getVerification()!!.expiresAt in (before + 3_600_000)..(System.currentTimeMillis() + 3_600_000))
    }

    @Test
    fun noMetadataInUserinfoSavesNone() = runTest {
        enqueueVerified(userinfo = """{"sub":"u1","age_verified":true}""")

        assertEquals(AgeWalletResult.SUCCESS, wallet.handleCallback(callback()))
        assertNull(wallet.getMetadata())
    }

    @Test
    fun theSameCallbackCannotBeUsedTwice() = runTest {
        enqueueVerified()

        assertEquals(AgeWalletResult.SUCCESS, wallet.handleCallback(callback()))
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback(callback()))
        assertEquals(2, server.requestCount)
        assertTrue(wallet.isVerified())
    }

    @Test
    fun clearVerificationRemovesTheVerificationAndTheOidcState() = runTest {
        enqueueVerified()
        wallet.handleCallback(callback())
        storage.setOidcState(OidcState("android:def", "v2", "n2"))

        wallet.clearVerification()

        assertFalse(wallet.isVerified())
        assertNull(storage.getOidcState())
    }

    // Fails and saves nothing

    private fun assertFailsAndSavesNothing(requests: Int) = runTest {
        assertEquals(AgeWalletResult.FAILED, wallet.handleCallback(callback()))
        assertEquals(requests, server.requestCount)
        assertFalse(wallet.isVerified())
        assertFalse(prefs.contains("verification"))
        assertNull(storage.getOidcState())
    }

    @Test
    fun failsWhenTheTokenRequestIsRefused() {
        server.enqueue(reply("""{"error":"invalid_grant"}""", 400))
        assertFailsAndSavesNothing(1)
    }

    @Test
    fun failsWhenTheTokenReplyIsNotJson() {
        server.enqueue(reply("<html>error</html>"))
        assertFailsAndSavesNothing(1)
    }

    @Test
    fun failsWhenTheTokenReplyHasNoAccessToken() {
        server.enqueue(reply("""{"expires_in":3600}"""))
        assertFailsAndSavesNothing(1)
    }

    @Test
    fun failsWhenUserinfoIsRefused() {
        server.enqueue(reply("""{"access_token":"tok"}"""))
        server.enqueue(reply("""{"error":"invalid_token"}""", 401))
        assertFailsAndSavesNothing(2)
    }

    @Test
    fun failsWhenAgeVerifiedIsFalse() {
        enqueueVerified(userinfo = """{"sub":"u1","age_verified":false}""")
        assertFailsAndSavesNothing(2)
    }

    @Test
    fun failsWhenAgeVerifiedIsMissing() {
        enqueueVerified(userinfo = """{"sub":"u1"}""")
        assertFailsAndSavesNothing(2)
    }
}
