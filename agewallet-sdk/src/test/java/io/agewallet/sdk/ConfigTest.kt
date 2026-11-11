package io.agewallet.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** AgeWalletConfig's checks and the default endpoints (AgeWalletConfig.kt). Plain JVM. */
class ConfigTest {
    @Test
    fun aBlankClientIdOrRedirectUriIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AgeWalletConfig(" ", REDIRECT_URI) }
        assertThrows(IllegalArgumentException::class.java) { AgeWalletConfig("cid", "") }
    }

    @Test
    fun theDefaultEndpointsAreOnAppAgewalletIo() {
        val endpoints = AgeWalletConfig("cid", REDIRECT_URI).endpoints
        assertEquals("https://app.agewallet.io/user/authorize", endpoints.auth)
        assertEquals("https://app.agewallet.io/user/token", endpoints.token)
        assertEquals("https://app.agewallet.io/user/userinfo", endpoints.userinfo)
    }

    @Test
    fun metadataIsLimitedTo4096Utf8Bytes() {
        AgeWalletConfig("cid", REDIRECT_URI, metadata = "a".repeat(4096))
        AgeWalletConfig("cid", REDIRECT_URI, metadata = "€".repeat(1365)) // 3 bytes each: 4095 bytes
        assertThrows(IllegalArgumentException::class.java) { AgeWalletConfig("cid", REDIRECT_URI, metadata = "a".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { AgeWalletConfig("cid", REDIRECT_URI, metadata = "€".repeat(1366)) }
    }
}
