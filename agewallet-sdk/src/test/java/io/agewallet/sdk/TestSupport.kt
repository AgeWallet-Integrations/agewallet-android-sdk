package io.agewallet.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

internal const val REDIRECT_URI = "https://shop.example/callback"

/** Plain (unencrypted) SharedPreferences from Robolectric's application, empty at the start of each test. */
internal fun testPrefs(): SharedPreferences =
    ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("agewallet-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

/** Makes this server answer a request it has no queued reply for with 404 at once, instead of waiting for one. */
internal fun MockWebServer.failFastWhenEmpty() {
    (dispatcher as QueueDispatcher).setFailFast(true)
}

/** Endpoints on [server]: /authorize, /token and /userinfo. */
internal fun endpointsOn(server: MockWebServer) = AgeWalletEndpoints(
    auth = server.url("/authorize").toString(),
    token = server.url("/token").toString(),
    userinfo = server.url("/userinfo").toString()
)

/** An AgeWallet with client ID "cid" and REDIRECT_URI, storing into [prefs]. */
internal fun testWallet(prefs: SharedPreferences, endpoints: AgeWalletEndpoints, metadata: String? = null) =
    AgeWallet(AgeWalletConfig("cid", REDIRECT_URI, endpoints, metadata), Storage(prefs))

/** A 200 (or [code]) reply with [body]. */
internal fun reply(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

/** The form fields of a recorded application/x-www-form-urlencoded request. */
internal fun RecordedRequest.formFields(): Map<String, String> =
    body.readUtf8().split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

/** S256 of [verifier], computed with the JDK rather than the SDK's Security. */
internal fun s256(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
