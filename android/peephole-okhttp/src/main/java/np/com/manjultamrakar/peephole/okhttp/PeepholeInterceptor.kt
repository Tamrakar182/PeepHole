package np.com.manjultamrakar.peephole.okhttp

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.capture.BodyCapture
import np.com.manjultamrakar.peephole.capture.GraphQLDetector
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.Header
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Captures REST and GraphQL traffic for the Peephole tool window.
 *
 * Add it as an application interceptor (`addInterceptor`) so bodies are seen after OkHttp's transparent
 * gzip handling. Works with Retrofit and Apollo Kotlin, which both run on OkHttp.
 */
class PeepholeInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val config = Peephole.config
        if (!config.enabled || request.tag(InspectorWebSocketTag::class.java) != null) {
            return chain.proceed(request)
        }

        val id = Peephole.newId()
        val startNs = System.nanoTime()
        val requestHeaders = requestHeaders(request)
        val requestBody = captureRequestBody(request, config.maxBodyBytes)
        val graphql = GraphQLDetector.detect(
            request.method, request.url.toString(), requestHeaders,
            request.body?.contentType()?.toString(), requestBody?.text,
        )
        Peephole.emit(
            InspectorEvent.HttpRequest(
                id = id,
                timestamp = System.currentTimeMillis(),
                method = request.method,
                url = request.url.toString(),
                headers = requestHeaders,
                body = requestBody,
                graphql = graphql,
                client = ClientLibrary.OKHTTP,
            ),
        )

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Peephole.emit(InspectorEvent.HttpFailure(id, System.currentTimeMillis(), e.toString(), elapsedMs(startNs)))
            throw e
        }
        val durationMs = elapsedMs(startNs)

        val responseBody = try {
            captureResponseBody(response, config.maxBodyBytes)
        } catch (e: Exception) {
            BodyCapture.omitted("Could not read body: $e", response.header("Content-Type"), -1)
        }
        Peephole.emit(
            InspectorEvent.HttpResponse(
                id = id,
                timestamp = System.currentTimeMillis(),
                code = response.code,
                message = response.message,
                protocol = response.protocol.toString(),
                headers = BodyCapture.headers(response.headers.pairs()),
                body = responseBody,
                durationMs = durationMs,
            ),
        )
        return response
    }

    private fun requestHeaders(request: Request): List<Header> {
        val pairs = request.headers.pairs().toMutableList()
        val body = request.body
        // Application interceptors run before OkHttp adds these from the body; show them anyway.
        if (body != null) {
            if (request.header("Content-Type") == null) body.contentType()?.let { pairs.add("Content-Type" to it.toString()) }
            if (request.header("Content-Length") == null) {
                val len = try { body.contentLength() } catch (_: IOException) { -1L }
                if (len >= 0) pairs.add("Content-Length" to len.toString())
            }
        }
        return BodyCapture.headers(pairs)
    }

    private fun captureRequestBody(request: Request, maxBytes: Long): Body? {
        val body = request.body ?: return null
        val contentType = body.contentType()?.toString()
        val length = try { body.contentLength() } catch (_: IOException) { -1L }
        if (body.isDuplex()) return BodyCapture.omitted("Duplex body not captured", contentType, length)
        if (body.isOneShot()) return BodyCapture.omitted("One-shot body not captured", contentType, length)
        if (length > maxBytes * 16) return BodyCapture.omitted("Body too large (${length} bytes)", contentType, length)
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val total = buffer.size
            val bytes = buffer.readByteArray(minOf(total, maxBytes))
            BodyCapture.fromBytes(bytes, contentType, total, maxBytes, request.header("Content-Encoding"))
        } catch (e: Exception) {
            BodyCapture.omitted("Could not read body: $e", contentType, length)
        }
    }

    private fun captureResponseBody(response: Response, maxBytes: Long): Body? {
        val body = response.body ?: return null
        val contentType = body.contentType()?.toString() ?: response.header("Content-Type")
        val length = body.contentLength()
        if (!promisesBody(response)) return null
        if (contentType != null && contentType.startsWith("text/event-stream", ignoreCase = true)) {
            return BodyCapture.omitted("Streaming response (server-sent events) not captured", contentType, length)
        }
        val peeked = response.peekBody(maxBytes).bytes()
        return BodyCapture.fromBytes(peeked, contentType, length, maxBytes, response.header("Content-Encoding"))
    }

    private fun promisesBody(response: Response): Boolean {
        if (response.request.method == "HEAD") return false
        val code = response.code
        if ((code < 100 || code >= 200) && code != 204 && code != 304) return true
        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
        return len > 0 || response.header("Transfer-Encoding").equals("chunked", ignoreCase = true)
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
}

internal fun Headers.pairs(): List<Pair<String, String>> = (0 until size).map { name(it) to value(it) }
