package np.com.manjultamrakar.peephole.ktor

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorWebSocketTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<InspectorEvent>()
    private val listener: (InspectorEvent) -> Unit = { events.add(it) }

    @Before
    fun setUp() {
        Peephole.addEventListener(listener)
        server.start()
    }

    @After
    fun tearDown() {
        Peephole.removeEventListener(listener)
        server.shutdown()
    }

    @Test
    fun recordsFramesBothWays() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("echo:$text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        val client = HttpClient(OkHttp) {
            install(WebSockets)
            install(PeepholePlugin)
        }
        val url = server.url("/ws").toString().replace("http://", "ws://")
        withTimeout(5000) {
            client.inspectedWebSocket(url) {
                send("""{"id":"1","type":"subscribe","payload":{"query":"subscription OnTick { tick }"}}""")
                val reply = incoming.receive() as Frame.Text
                assertTrue(reply.readText().startsWith("echo:"))
                close()
            }
            repeat(50) {
                if (events.any { it is InspectorEvent.WsClosed }) return@repeat
                delay(20)
            }
        }

        assertTrue(events.none { it is InspectorEvent.HttpRequest }, "upgrade must not be recorded as REST")
        assertEquals(1, events.filterIsInstance<InspectorEvent.WsConnect>().size)
        assertEquals(101, events.filterIsInstance<InspectorEvent.WsOpen>().single().code)
        val frames = events.filterIsInstance<InspectorEvent.WsFrame>()
        assertEquals(listOf(Direction.SENT, Direction.RECEIVED), frames.map { it.direction })
        assertEquals("subscribe · OnTick #1", frames[0].label)
        assertTrue(events.any { it is InspectorEvent.WsClosing && it.initiatedBy == Direction.SENT })
    }
}
