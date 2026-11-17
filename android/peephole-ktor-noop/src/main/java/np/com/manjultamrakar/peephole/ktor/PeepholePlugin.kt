package np.com.manjultamrakar.peephole.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.websocket.DefaultWebSocketSession

class PeepholePluginConfig {
    var enabled: Boolean = true
}

/** No-op variant for release builds. */
val PeepholePlugin: ClientPlugin<PeepholePluginConfig> =
    createClientPlugin("Peephole", ::PeepholePluginConfig) {}

/** No-op variant for release builds: a plain delegate. */
class InspectedWebSocketSession internal constructor(
    private val delegate: DefaultClientWebSocketSession,
) : ClientWebSocketSession, DefaultWebSocketSession by delegate {
    override val call get() = delegate.call
}

fun DefaultClientWebSocketSession.inspected(): InspectedWebSocketSession = InspectedWebSocketSession(this)

suspend fun HttpClient.inspectedWebSocket(
    urlString: String,
    request: HttpRequestBuilder.() -> Unit = {},
    block: suspend InspectedWebSocketSession.() -> Unit,
) {
    inspectedWebSocket(request = {
        url(urlString)
        request()
    }, block = block)
}

suspend fun HttpClient.inspectedWebSocket(
    request: HttpRequestBuilder.() -> Unit,
    block: suspend InspectedWebSocketSession.() -> Unit,
) {
    webSocket(request) { inspected().block() }
}

suspend fun HttpClient.inspectedWebSocketSession(
    urlString: String,
    request: HttpRequestBuilder.() -> Unit = {},
): InspectedWebSocketSession = webSocketSession(urlString, request).inspected()
