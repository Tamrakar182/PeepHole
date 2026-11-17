package np.com.manjultamrakar.peephole.capture

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.protocol.Base64Codec
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.Header
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** Helpers shared by the client integrations. Not intended for direct use. */
object BodyCapture {

    const val REDACTED = "██"

    /**
     * Builds a [Body] from (possibly truncated) [bytes].
     * @param totalSize full size of the content, or -1 if unknown.
     */
    @JvmStatic
    fun fromBytes(bytes: ByteArray, contentType: String?, totalSize: Long, maxBytes: Long, contentEncoding: String? = null): Body {
        var data = bytes
        var size = totalSize
        var truncated = (totalSize >= 0 && totalSize > bytes.size) || (totalSize < 0 && bytes.size >= maxBytes)
        if (contentEncoding != null && contentEncoding.trim().equals("gzip", ignoreCase = true)) {
            gunzip(bytes, maxBytes)?.let { (unzipped, complete) ->
                data = unzipped
                if (!truncated && complete) size = unzipped.size.toLong()
                truncated = truncated || !complete
            }
        }
        if (data.size > maxBytes) {
            data = data.copyOf(maxBytes.toInt())
            truncated = true
        }
        if (size < 0 && !truncated) size = data.size.toLong()
        val text = decodeText(data, contentType, truncated)
        return if (text != null) {
            Body(text = text, contentType = contentType, size = size, truncated = truncated)
        } else {
            Body(base64 = Base64Codec.encode(data), contentType = contentType, size = size, truncated = truncated)
        }
    }

    @JvmStatic
    fun omitted(reason: String, contentType: String?, size: Long): Body =
        Body(contentType = contentType, size = size, omittedReason = reason)

    /** Converts header pairs, redacting sensitive values per [np.com.manjultamrakar.peephole.PeepholeConfig.redactHeaders]. */
    @JvmStatic
    fun headers(pairs: Iterable<Pair<String, String>>): List<Header> {
        val redact = Peephole.config.redactHeaders
        return pairs.map { (name, value) ->
            val shouldRedact = redact.any { it.equals(name, ignoreCase = true) }
            Header(name, if (shouldRedact) REDACTED else value)
        }
    }

    @JvmStatic
    fun charsetOf(contentType: String?): Charset {
        val match = contentType?.let { CHARSET.find(it) } ?: return Charsets.UTF_8
        return try {
            Charset.forName(match.groupValues[1].trim('"', '\''))
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    @JvmStatic
    fun isTextContentType(contentType: String?): Boolean? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (ct.startsWith("text/")) return true
        if (TEXT_HINTS.any { ct.contains(it) }) return true
        if (ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("font/")) return false
        if (ct == "application/octet-stream" || ct.contains("protobuf") || ct.contains("grpc") || ct == "application/zip" || ct == "application/pdf") return false
        return null
    }

    private fun decodeText(data: ByteArray, contentType: String?, truncated: Boolean): String? {
        if (data.isEmpty()) return ""
        val hint = isTextContentType(contentType)
        if (hint == false) return null
        val charset = charsetOf(contentType)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            // A truncated body may end in the middle of a multi-byte character; decode leniently then.
            if (hint == true || truncated) String(data, charset) else return null
        }
        if (hint == true) return text
        // Unknown content type: treat as text only if it has no control characters.
        val controls = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        return if (controls == 0) text else null
    }

    /** @return the decompressed prefix and whether the stream was fully read, or null if not gzip. */
    private fun gunzip(bytes: ByteArray, maxBytes: Long): Pair<ByteArray, Boolean>? {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
        val out = ByteArrayOutputStream()
        var complete = false
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buf = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) {
                        complete = true
                        break
                    }
                    out.write(buf, 0, n)
                }
            }
        } catch (_: Exception) {
            // Truncated gzip stream: keep what we have.
        }
        return out.toByteArray() to complete
    }

    private val CHARSET = Regex("charset\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
    private val TEXT_HINTS = listOf("json", "xml", "graphql", "javascript", "x-www-form-urlencoded", "html", "yaml", "csv", "event-stream")
}
