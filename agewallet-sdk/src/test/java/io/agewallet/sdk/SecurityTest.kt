package io.agewallet.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PKCE verifier and challenge, state and nonce (Security.kt). Robolectric, for android.util.Base64. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityTest {
    @Test
    fun challengeMatchesTheRfc7636AppendixBExample() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Security.generateChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun verifierIs86Base64UrlCharactersWithNoPaddingOrLineBreak() {
        val verifier = Security.generateVerifier()
        assertTrue(verifier, Regex("^[A-Za-z0-9_-]{86}$").matches(verifier))
    }

    @Test
    fun verifiersDiffer() {
        assertNotEquals(Security.generateVerifier(), Security.generateVerifier())
    }

    @Test
    fun stateAndNonceAre32LowercaseHexCharactersAndDifferEachTime() {
        val hex = Regex("^[0-9a-f]{32}$")
        assertTrue(hex.matches(Security.generateState()))
        assertTrue(hex.matches(Security.generateNonce()))
        assertNotEquals(Security.generateState(), Security.generateState())
        assertNotEquals(Security.generateNonce(), Security.generateNonce())
    }
}
