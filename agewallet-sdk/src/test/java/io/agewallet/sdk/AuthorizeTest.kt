package io.agewallet.sdk

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AgeWallet.startVerification(): the authorize URL it opens in the browser (read from the activity Robolectric
 * records as started), the OIDC state it saves, and metadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthorizeTest {
    @get:Rule
    val server = MockWebServer()

    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        server.failFastWhenEmpty()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        prefs = testPrefs()
    }

    private fun wallet(metadata: String? = null) = testWallet(prefs, endpointsOn(server), metadata)

    /** Starts the flow and returns the URL the browser was sent to. */
    private fun start(wallet: AgeWallet, metadata: String? = null): Uri {
        wallet.startVerification(activity, metadata)
        val started: Intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        return started.data!!
    }

    @Test
    fun theUrlCarriesTheOidcParameters() {
        val url = start(wallet())

        assertEquals(server.url("/authorize").toString(), url.toString().substringBefore("?"))
        assertEquals(
            setOf("response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "nonce"),
            url.queryParameterNames
        )
        assertEquals("code", url.getQueryParameter("response_type"))
        assertEquals("cid", url.getQueryParameter("client_id"))
        assertEquals(REDIRECT_URI, url.getQueryParameter("redirect_uri"))
        assertEquals("openid age", url.getQueryParameter("scope"))
        assertEquals("S256", url.getQueryParameter("code_challenge_method"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSavedStateVerifierAndNonceMatchTheUrl() {
        val url = start(wallet())
        val saved = Storage(prefs).getOidcState()!!

        assertTrue(Regex("^android:[0-9a-f]{32}$").matches(url.getQueryParameter("state")!!))
        assertEquals(saved.state, url.getQueryParameter("state"))
        assertEquals(saved.nonce, url.getQueryParameter("nonce"))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(saved.nonce))
        assertEquals(s256(saved.verifier), url.getQueryParameter("code_challenge"))
    }

    @Test
    fun eachStartHasItsOwnStateNonceAndChallenge() {
        val wallet = wallet()
        val first = start(wallet)
        val second = start(wallet)

        for (name in listOf("state", "nonce", "code_challenge")) {
            assertNotEquals(name, first.getQueryParameter(name), second.getQueryParameter(name))
        }
    }

    @Test
    fun metadataIsLeftOutWhenNoneIsSet() {
        assertNull(start(wallet()).getQueryParameter("metadata"))
    }

    @Test
    fun theConfigDefaultMetadataIsSent() {
        assertEquals("tenant-1", start(wallet("tenant-1")).getQueryParameter("metadata"))
    }

    @Test
    fun aPerCallValueWinsWithoutChangingTheDefault() {
        val wallet = wallet("tenant-1")
        assertEquals("order:42 & co", start(wallet, "order:42 & co").getQueryParameter("metadata"))
        assertEquals("tenant-1", start(wallet).getQueryParameter("metadata"))
    }

    @Test
    fun setMetadataChangesTheDefaultAndNullClearsIt() {
        val wallet = wallet("tenant-1")
        wallet.setMetadata("tenant-2")
        assertEquals("tenant-2", start(wallet).getQueryParameter("metadata"))
        wallet.setMetadata(null)
        assertNull(start(wallet).getQueryParameter("metadata"))
    }

    @Test
    fun metadataOver4096BytesIsRejectedBeforeAnythingIsSavedOrOpened() {
        val wallet = wallet()
        val over = "€".repeat(1366) // 3 bytes each: 4098 bytes

        assertThrows(IllegalArgumentException::class.java) { wallet.setMetadata(over) }
        assertThrows(IllegalArgumentException::class.java) { wallet.startVerification(activity, over) }
        assertNull(Storage(prefs).getOidcState())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(prefs.contains("oidc"))
    }
}
