package np.com.manjultamrakar.peephole.okhttp

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.GraphQLOperationType
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OkHttpCaptureTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<InspectorEvent>()
    private val listener: (InspectorEvent) -> Unit = { events.add(it) }
    private val client = OkHttpClient.Builder().addInterceptor(PeepholeInterceptor()).build()

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
    fun capturesRestCall() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"))
        val body = "{\"name\":\"a\"}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/users")).post(body).header("Authorization", "secret").build())
            .execute().use { assertEquals("{\"ok\":true}", it.body!!.string(), "app must still be able to read the body") }

        val req = events.filterIsInstance<InspectorEvent.HttpRequest>().single()
        assertEquals("POST", req.method)
        assertEquals("{\"name\":\"a\"}", req.body?.text)
        assertEquals("██", req.headers.first { it.name == "Authorization" }.value)
        assertEquals(null, req.graphql)
        val res = events.filterIsInstance<InspectorEvent.HttpResponse>().single()
        assertEquals(req.id, res.id)
        assertEquals(200, res.code)
        assertEquals("{\"ok\":true}", res.body?.text)
    }

    @Test
    fun capturesGraphQL() {
        server.enqueue(MockResponse().setBody("{\"data\":{}}"))
        val body = """{"operationName":"AddTodo","query":"mutation AddTodo { add { id } }"}""".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/graphql")).post(body).build()).execute().close()
        val gql = assertNotNull(events.filterIsInstance<InspectorEvent.HttpRequest>().single().graphql)
        assertEquals("AddTodo", gql.operationName)
        assertEquals(GraphQLOperationType.MUTATION, gql.operationType)
    }

    @Test
    fun capturesFailure() {
        server.shutdown()
        runCatching { client.newCall(Request.Builder().url(server.url("/x")).build()).execute() }
        assertEquals(1, events.filterIsInstance<InspectorEvent.HttpFailure>().size)
    }

    @Test
    fun capturesWebSocketFrames() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("echo:$text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        val closed = CountDownLatch(1)
        val factory = PeepholeWebSocketFactory(client)
        factory.newWebSocket(Request.Builder().url(server.url("/ws")).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("hi") // sent through the wrapper handed to the listener
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1000, "done")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed.countDown()
        })
        assertTrue(closed.await(5, TimeUnit.SECONDS))

        assertTrue(events.filterIsInstance<InspectorEvent.HttpRequest>().isEmpty(), "upgrade must not be recorded as REST")
        val connect = events.filterIsInstance<InspectorEvent.WsConnect>().single()
        assertTrue(connect.url.startsWith("ws://"))
        assertEquals(101, events.filterIsInstance<InspectorEvent.WsOpen>().single().code)
        val frames = events.filterIsInstance<InspectorEvent.WsFrame>()
        assertEquals(listOf(Direction.SENT to "hi", Direction.RECEIVED to "echo:hi"), frames.map { it.direction to it.payload.text })
        assertTrue(events.any { it is InspectorEvent.WsClosing && it.initiatedBy == Direction.SENT })
        assertEquals(1000, events.filterIsInstance<InspectorEvent.WsClosed>().single().code)
    }
}
