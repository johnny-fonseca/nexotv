package Nexotv.iptv.player


import Nexotv.iptv.player.diagnostics.*
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCleartextErrorsTest {
    @Test
    fun recognizesMedia3CleartextExceptionInCauseChain() {
        val failure = IOException("Playback failed", CleartextNotPermittedException())

        assertEquals(R.string.cleartext_blocked, cleartextErrorMessageResId(failure))
    }

    @Test
    fun recognizesAndroidCleartextIOExceptionMessage() {
        val failure = IOException("Cleartext HTTP traffic to 192.0.2.1 not permitted")

        assertEquals(R.string.cleartext_blocked, cleartextErrorMessageResId(failure))
        assertEquals(
            R.string.cleartext_blocked_with_headers,
            cleartextErrorMessageResId(failure, headersConfigured = true),
        )
    }

    @Test
    fun recognizesCustomHeadersSuppressionFailure() {
        assertEquals(
            R.string.cleartext_blocked_with_headers,
            cleartextErrorMessageResId(CleartextHeadersOnHttpException(), headersConfigured = true),
        )
    }

    @Test
    fun recognizesHttpsToHttpRedirectBlockedForCustomHeaders() {
        val failure = IOException("Disallowed cross-protocol redirect (https to http)")

        assertEquals(
            R.string.cleartext_blocked_with_headers,
            cleartextErrorMessageResId(failure, headersConfigured = true),
        )
    }

    @Test
    fun doesNotClassifyGenericNetworkFailuresAsCleartext() {
        assertNull(cleartextErrorMessageResId(IOException("Connection refused")))
        assertFalse(isCleartextBlocked(IOException("HTTP 401 Unauthorized")))
    }

    @Test
    fun detectsOnlyHttpUrlsAsCleartext() {
        assertTrue(isCleartextUrl("http://example.test/live.m3u8"))
        assertFalse(isCleartextUrl("https://example.test/live.m3u8"))
        assertFalse(isCleartextUrl("not a url"))
    }

    @Test
    fun recognizesTlsAndCertificateErrors() {
        val failure = javax.net.ssl.SSLHandshakeException("Handshake failed")
        assertEquals(R.string.tls_cert_or_clock_error, tlsErrorMessageResId(failure))
        assertTrue(isTlsError(failure))
    }

    @Test
    fun doesNotClassifyGenericErrorsAsTls() {
        assertNull(tlsErrorMessageResId(IOException("Connection refused")))
        assertFalse(isTlsError(IOException("Connection refused")))
    }

    private class CleartextNotPermittedException : IOException("Cleartext is not permitted")
}