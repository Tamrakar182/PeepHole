package np.com.manjultamrakar.peephole.ktor

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.capture.BodyCapture
import np.com.manjultamrakar.peephole.capture.GraphQLDetector
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.FrameType
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/**
 * A [DefaultClientWebSocketSession] wrapper that records frames for the Peephole.
 * Use it exactly like the session Ktor gives you.
 */
class InspectedWebSocketSession internal constructor(
    private val delegate: DefaultClientWebSocketSession,
) : ClientWebSocketSession, DefaultWebSocketSession by delegate {

    private val id: String = Peephole.newId()
    private val enabled = Peephole.isEnabled

    override val call get() = delegate.call

    override val outgoing: SendChannel<Frame> =
        if (enabled) RecordingSendChannel(delegate.outgoing) { record(Direction.SENT, it) } else delegate.outgoing

    override val incoming: ReceiveChannel<Frame> = if (!enabled) delegate.incoming else {
        val proxy = Channel<Frame>()
        delegate.launch {
            try {
                for (frame in delegate.incoming) {
                    record(Direction.RECEIVED, frame)
                    proxy.send(frame)
                }
                proxy.close()
            } catch (e: CancellationException) {
                proxy.cancel(e)
                throw e
            } catch (e: Throwable) {
                proxy.close(e)
            }
        }
        proxy
    }

    override suspend fun send(frame: Frame) {
        outgoing.send(frame)
    }

    init {
        if (enabled) {
            val request = delegate.call.request
            Peephole.emit(
                InspectorEvent.WsConnect(
                    id = id,
                    timestamp = System.currentTimeMillis(),
                    url = request.url.toString(),
                    headers = BodyCapture.headers(request.headers.pairs()),
                    client = ClientLibrary.KTOR,
                ),
            )
            val response = delegate.call.response
            Peephole.emit(
                InspectorEvent.WsOpen(id, System.currentTimeMillis(), response.status.value, BodyCapture.headers(response.headers.pairs())),
            )
            delegate.launch {
                try {
                    val reason = delegate.closeReason.await()
                    Peephole.emit(InspectorEvent.WsClosed(id, System.currentTimeMillis(), reason?.code?.toInt() ?: 1005, reason?.message ?: ""))
                } catch (e: CancellationException) {
                    Peephole.emit(InspectorEvent.WsClosed(id, System.currentTimeMillis(), 1006, "Session cancelled"))
                } catch (e: Throwable) {
                    Peephole.emit(InspectorEvent.WsFailure(id, System.currentTimeMillis(), e.toString(), null))
                }
            }
        }
    }

    private fun record(direction: Direction, frame: Frame) {
        val max = Peephole.config.maxFrameBytes
        val data = frame.data
        val now = System.currentTimeMillis()
        when (frame) {
            is Frame.Text -> {
                val text = String(data, Charsets.UTF_8)
                val (label, gql) = GraphQLDetector.describeWsMessage(text)
                val payload = BodyCapture.fromBytes(data.copyOf(minOf(data.size.toLong(), max).toInt()), "text/plain; charset=utf-8", data.size.toLong(), max)
                Peephole.emit(InspectorEvent.WsFrame(id, now, direction, FrameType.TEXT, payload, label, gql))
            }
            is Frame.Binary -> {
                val payload = BodyCapture.fromBytes(data.copyOf(minOf(data.size.toLong(), max).toInt()), "application/octet-stream", data.size.toLong(), max)
                Peephole.emit(InspectorEvent.WsFrame(id, now, direction, FrameType.BINARY, payload))
            }
            is Frame.Close -> {
                val reason = frame.readReason()
                Peephole.emit(InspectorEvent.WsClosing(id, now, reason?.code?.toInt() ?: 1005, reason?.message ?: "", direction))
            }
            is Frame.Ping, is Frame.Pong -> Unit // keep-alive noise
        }
    }
}

private class RecordingSendChannel(
    private val delegate: SendChannel<Frame>,
    private val onSent: (Frame) -> Unit,
) : SendChannel<Frame> by delegate {
    override suspend fun send(element: Frame) {
        onSent(element)
        delegate.send(element)
    }

    override fun trySend(element: Frame): ChannelResult<Unit> {
        val result = delegate.trySend(element)
        if (result.isSuccess) onSent(element)
        return result
    }
}

/** Wraps an already-open session so its frames are recorded. */
fun DefaultClientWebSocketSession.inspected(): InspectedWebSocketSession = InspectedWebSocketSession(this)

/** Drop-in replacement for [HttpClient.webSocket] that records the connection and all frames. */
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

/** Drop-in replacement for [HttpClient.webSocket] that records the connection and all frames. */
suspend fun HttpClient.inspectedWebSocket(
    request: HttpRequestBuilder.() -> Unit,
    block: suspend InspectedWebSocketSession.() -> Unit,
) {
    var opened = false
    val probe = HttpRequestBuilder().apply(request)
    try {
        webSocket(request) {
            opened = true
            inspected().block()
        }
    } catch (t: Throwable) {
        if (!opened && t !is CancellationException && Peephole.isEnabled) {
            val id = Peephole.newId()
            Peephole.emit(InspectorEvent.WsConnect(id, System.currentTimeMillis(), probe.url.buildString(), emptyList(), ClientLibrary.KTOR))
            Peephole.emit(InspectorEvent.WsFailure(id, System.currentTimeMillis(), t.toString(), null))
        }
        throw t
    }
}

/** Drop-in replacement for [HttpClient.webSocketSession]. */
suspend fun HttpClient.inspectedWebSocketSession(
    urlString: String,
    request: HttpRequestBuilder.() -> Unit = {},
): InspectedWebSocketSession = webSocketSession(urlString, request).inspected()
