package np.com.manjultamrakar.peephole

import np.com.manjultamrakar.peephole.capture.BodyCapture
import np.com.manjultamrakar.peephole.protocol.Base64Codec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyCaptureTest {
    @Test
    fun textBody() {
        val body = BodyCapture.fromBytes("{\"a\":1}".toByteArray(), "application/json; charset=utf-8", 7, 100)
        assertEquals("{\"a\":1}", body.text)
        assertEquals(7, body.size)
        assertEquals(false, body.truncated)
    }

    @Test
    fun binaryBodyIsBase64() {
        val bytes = byteArrayOf(0, 1, 2, -1)
        val body = BodyCapture.fromBytes(bytes, "image/png", 4, 100)
        assertNull(body.text)
        assertEquals(Base64Codec.encode(bytes), body.base64)
    }

    @Test
    fun truncatesAndFlags() {
        val body = BodyCapture.fromBytes("abcdef".toByteArray(), "text/plain", 1000, 6)
        assertEquals("abcdef", body.text)
        assertTrue(body.truncated)
        assertEquals(1000, body.size)
    }

    @Test
    fun gunzipsEncodedBodies() {
        val raw = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("hello gzip".toByteArray()) } }.toByteArray()
        val body = BodyCapture.fromBytes(raw, "text/plain", raw.size.toLong(), 1000, contentEncoding = "gzip")
        assertEquals("hello gzip", assertNotNull(body.text))
    }

    @Test
    fun redactsConfiguredHeaders() {
        val headers = BodyCapture.headers(listOf("authorization" to "Bearer x", "Accept" to "*/*"))
        assertEquals(BodyCapture.REDACTED, headers[0].value)
        assertEquals("*/*", headers[1].value)
    }
}
