package np.com.manjultamrakar.peephole.ktor

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.capture.BodyCapture
import np.com.manjultamrakar.peephole.capture.GraphQLDetector
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isWebsocket
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

class PeepholePluginConfig {
    /** Disable capture for this client only. */
    var enabled: Boolean = true
}

private class CallInfo(val id: String, val startNs: Long)

private val CallInfoKey = AttributeKey<CallInfo>("np.com.manjultamrakar.peephole.CallInfo")

/**
 * Ktor client plugin that captures REST and GraphQL calls.
 *
 * ```
 * HttpClient(CIO) { install(PeepholePlugin) }
 * ```
 * For WebSockets use [inspectedWebSocket] or [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.inspected].
 */
val PeepholePlugin: ClientPlugin<PeepholePluginConfig> =
    createClientPlugin("Peephole", ::PeepholePluginConfig) {
        val pluginEnabled = pluginConfig.enabled

        on(SendingRequest) { request, content ->
            if (!pluginEnabled || !Peephole.isEnabled || isWebSocket(request, content)) return@on
            val id = Peephole.newId()
            request.attributes.put(CallInfoKey, CallInfo(id, System.nanoTime()))
            val headerPairs = request.headers.entries().flatMap { (k, vs) -> vs.map { k to it } }.toMutableList()
            content.headers.forEach { k, vs -> vs.forEach { headerPairs.add(k to it) } }
            val contentType = content.contentType?.toString()
            if (contentType != null && headerPairs.none { it.first.equals(HttpHeaders.ContentType, true) }) {
                headerPairs.add(HttpHeaders.ContentType to contentType)
            }
            content.contentLength?.let { len ->
                if (headerPairs.none { it.first.equals(HttpHeaders.ContentLength, true) }) headerPairs.add(HttpHeaders.ContentLength to len.toString())
            }
            val headers = BodyCapture.headers(headerPairs)
            val body = captureOutgoing(content)
            val url = request.url.buildString()
            Peephole.emit(
                InspectorEvent.HttpRequest(
                    id = id,
                    timestamp = System.currentTimeMillis(),
                    method = request.method.value,
                    url = url,
                    headers = headers,
                    body = body,
                    graphql = GraphQLDetector.detect(request.method.value, url, headers, contentType, body?.text),
                    client = ClientLibrary.KTOR,
                ),
            )
        }

        on(Send) { request ->
            try {
                proceed(request)
            } catch (t: Throwable) {
                request.attributes.getOrNull(CallInfoKey)?.let { info ->
                    Peephole.emit(InspectorEvent.HttpFailure(info.id, System.currentTimeMillis(), t.toString(), elapsedMs(info.startNs)))
                }
                throw t
            }
        }

        val observer = ResponseObserver.prepare {
            onResponse { response -> recordResponse(response) }
        }
        ResponseObserver.install(observer, client)
    }

private fun isWebSocket(request: HttpRequestBuilder, content: OutgoingContent): Boolean =
    request.url.protocol.isWebsocket() ||
        content.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true) ||
        request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)

private fun captureOutgoing(content: OutgoingContent): Body? {
    val max = Peephole.config.maxBodyBytes
    val contentType = content.contentType?.toString()
    return when (content) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ByteArrayContent -> {
            val bytes = content.bytes()
            BodyCapture.fromBytes(bytes.copyOf(minOf(bytes.size.toLong(), max).toInt()), contentType, bytes.size.toLong(), max)
        }
        is OutgoingContent.ContentWrapper -> captureOutgoing(content.delegate())
        is OutgoingContent.ProtocolUpgrade -> null
        else -> BodyCapture.omitted("Streaming body not captured", contentType, content.contentLength ?: -1)
    }
}

private suspend fun recordResponse(response: HttpResponse) {
    val info = response.call.attributes.getOrNull(CallInfoKey) ?: return
    val durationMs = elapsedMs(info.startNs)
    val max = Peephole.config.maxBodyBytes
    val contentType = response.contentType()?.toString()
    val length = response.contentLength() ?: -1L
    val body: Body? = try {
        when {
            response.call.request.method.value == "HEAD" -> null
            contentType != null && contentType.startsWith("text/event-stream", true) ->
                BodyCapture.omitted("Streaming response (server-sent events) not captured", contentType, length)
            else -> {
                val bytes = response.bodyAsChannel().readRemaining(max).readByteArray()
                BodyCapture.fromBytes(bytes, contentType, length, max)
            }
        }
    } catch (e: Exception) {
        BodyCapture.omitted("Could not read body: $e", contentType, length)
    }
    Peephole.emit(
        InspectorEvent.HttpResponse(
            id = info.id,
            timestamp = System.currentTimeMillis(),
            code = response.status.value,
            message = response.status.description,
            protocol = response.version.toString(),
            headers = BodyCapture.headers(response.headers.pairs()),
            body = body,
            durationMs = durationMs,
        ),
    )
}

internal fun Headers.pairs(): List<Pair<String, String>> = entries().flatMap { (k, vs) -> vs.map { k to it } }

private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
