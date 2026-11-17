package np.com.manjultamrakar.peephole.okhttp

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.capture.BodyCapture
import np.com.manjultamrakar.peephole.capture.GraphQLDetector
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.FrameType
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Marks requests made by [PeepholeWebSocketFactory] so [PeepholeInterceptor] doesn't record the upgrade twice. */
internal class InspectorWebSocketTag(val id: String)

/**
 * A [WebSocket.Factory] that records every frame sent and received.
 *
 * ```
 * val ws = PeepholeWebSocketFactory(okHttpClient).newWebSocket(request, listener)
 * // Apollo Kotlin: WebSocketNetworkTransport.Builder().webSocketEngine(DefaultWebSocketEngine(PeepholeWebSocketFactory(okHttpClient)))
 * ```
 */
class PeepholeWebSocketFactory(private val delegate: WebSocket.Factory) : WebSocket.Factory {

    constructor(client: OkHttpClient) : this(client as WebSocket.Factory)

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        if (!Peephole.isEnabled) return delegate.newWebSocket(request, listener)
        val id = Peephole.newId()
        Peephole.emit(
            InspectorEvent.WsConnect(
                id = id,
                timestamp = System.currentTimeMillis(),
                url = wsUrl(request),
                headers = BodyCapture.headers(request.headers.pairs()),
                client = ClientLibrary.OKHTTP,
            ),
        )
        val tagged = request.newBuilder().tag(InspectorWebSocketTag::class.java, InspectorWebSocketTag(id)).build()
        val recordingListener = RecordingListener(id, listener)
        val socket = RecordingWebSocket(id, delegate.newWebSocket(tagged, recordingListener))
        recordingListener.wrapper = socket
        return socket
    }

    private fun wsUrl(request: Request): String {
        val url = request.url.toString()
        return when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            else -> url
        }
    }
}

internal fun recordFrame(id: String, direction: Direction, text: String?, bytes: ByteString?) {
    val max = Peephole.config.maxFrameBytes
    if (text != null) {
        val (label, gql) = GraphQLDetector.describeWsMessage(text)
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val payload = BodyCapture.fromBytes(utf8.copyOf(minOf(utf8.size.toLong(), max).toInt()), "text/plain; charset=utf-8", utf8.size.toLong(), max)
        Peephole.emit(InspectorEvent.WsFrame(id, System.currentTimeMillis(), direction, FrameType.TEXT, payload, label, gql))
    } else if (bytes != null) {
        val data = bytes.substring(0, minOf(bytes.size.toLong(), max).toInt()).toByteArray()
        val payload = BodyCapture.fromBytes(data, "application/octet-stream", bytes.size.toLong(), max)
        Peephole.emit(InspectorEvent.WsFrame(id, System.currentTimeMillis(), direction, FrameType.BINARY, payload))
    }
}

private class RecordingWebSocket(private val id: String, private val delegate: WebSocket) : WebSocket by delegate {
    override fun send(text: String): Boolean {
        val queued = delegate.send(text)
        if (queued) recordFrame(id, Direction.SENT, text, null)
        return queued
    }

    override fun send(bytes: ByteString): Boolean {
        val queued = delegate.send(bytes)
        if (queued) recordFrame(id, Direction.SENT, null, bytes)
        return queued
    }

    override fun close(code: Int, reason: String?): Boolean {
        val accepted = delegate.close(code, reason)
        if (accepted) Peephole.emit(InspectorEvent.WsClosing(id, System.currentTimeMillis(), code, reason ?: "", Direction.SENT))
        return accepted
    }

    override fun cancel() {
        delegate.cancel()
        Peephole.emit(InspectorEvent.WsFailure(id, System.currentTimeMillis(), "Cancelled by client", null))
    }
}

private class RecordingListener(private val id: String, private val delegate: WebSocketListener) : WebSocketListener() {
    @Volatile
    var wrapper: WebSocket? = null

    /** Hand the app our recording wrapper so sends made from inside callbacks are captured too. */
    private fun ws(original: WebSocket) = wrapper ?: original

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Peephole.emit(
            InspectorEvent.WsOpen(id, System.currentTimeMillis(), response.code, BodyCapture.headers(response.headers.pairs())),
        )
        delegate.onOpen(ws(webSocket), response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        recordFrame(id, Direction.RECEIVED, text, null)
        delegate.onMessage(ws(webSocket), text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        recordFrame(id, Direction.RECEIVED, null, bytes)
        delegate.onMessage(ws(webSocket), bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Peephole.emit(InspectorEvent.WsClosing(id, System.currentTimeMillis(), code, reason, Direction.RECEIVED))
        delegate.onClosing(ws(webSocket), code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Peephole.emit(InspectorEvent.WsClosed(id, System.currentTimeMillis(), code, reason))
        delegate.onClosed(ws(webSocket), code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Peephole.emit(InspectorEvent.WsFailure(id, System.currentTimeMillis(), t.toString(), response?.code))
        delegate.onFailure(ws(webSocket), t, response)
    }
}
