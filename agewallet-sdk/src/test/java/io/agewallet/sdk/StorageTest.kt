package io.agewallet.sdk

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Storage.kt over plain SharedPreferences, and VerificationState.isExpired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var storage: Storage

    private fun inAnHour() = System.currentTimeMillis() + 3_600_000

    @Before
    fun setUp() {
        prefs = testPrefs()
        storage = Storage(prefs)
    }

    @Test
    fun aVerificationIsSavedAsJsonUnderTheVerificationKeyAndReadBack() {
        val verification = VerificationState("tok", inAnHour(), true, "m1")
        storage.setVerification(verification)

        assertEquals("m1", JSONObject(prefs.getString("verification", null)!!).getString("metadata"))
        assertEquals(verification, storage.getVerification())
    }

    @Test
    fun aVerificationWithNoMetadataIsSavedWithoutTheField() {
        storage.setVerification(VerificationState("tok", inAnHour(), true))

        assertFalse(JSONObject(prefs.getString("verification", null)!!).has("metadata"))
        assertNull(storage.getVerification()!!.metadata)
    }

    @Test
    fun oidcStateIsSavedUnderTheOidcKeyAndReadBack() {
        storage.setOidcState(OidcState("s", "v", "n"))

        assertEquals("s", JSONObject(prefs.getString("oidc", null)!!).getString("state"))
        assertEquals(OidcState("s", "v", "n"), storage.getOidcState())
    }

    @Test
    fun clearingOneKeepsTheOther() {
        storage.setVerification(VerificationState("tok", inAnHour(), true))
        storage.setOidcState(OidcState("s", "v", "n"))

        storage.clearVerification()
        assertNull(storage.getVerification())
        assertNotNull(storage.getOidcState())

        storage.setVerification(VerificationState("tok", inAnHour(), true))
        storage.clearOidcState()
        assertNull(storage.getOidcState())
        assertNotNull(storage.getVerification())
    }

    @Test
    fun anExpiredVerificationReadsAsNoneAndIsDeleted() {
        storage.setVerification(VerificationState("tok", System.currentTimeMillis() - 1, true))

        assertNull(storage.getVerification())
        assertFalse(prefs.contains("verification"))
    }

    @Test
    fun aVerificationIsExpiredFromItsExpiryTime() {
        assertTrue(VerificationState("tok", System.currentTimeMillis(), true).isExpired)
        assertFalse(VerificationState("tok", System.currentTimeMillis() + 60_000, true).isExpired)
    }

    @Test
    fun corruptJsonReadsAsNoneAndIsDeleted() {
        prefs.edit().putString("verification", "{not json").putString("oidc", "{not json").commit()

        assertNull(storage.getVerification())
        assertNull(storage.getOidcState())
        assertFalse(prefs.contains("verification"))
        assertFalse(prefs.contains("oidc"))
    }
}
