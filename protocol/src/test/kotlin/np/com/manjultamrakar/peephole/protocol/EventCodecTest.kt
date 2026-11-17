package np.com.manjultamrakar.peephole.protocol

import np.com.manjultamrakar.peephole.protocol.InspectorEvent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EventCodecTest {
    private val gql = GraphQLInfo("GetUser", GraphQLOperationType.QUERY, "query GetUser { me { id } }", "{\"id\":1}")

    private val events = listOf(
        Hello(1, PROTOCOL_VERSION, "com.example", "com.example:remote", "Example", 123, "0.1.0"),
        HttpRequest("1-1", 2, "POST", "https://api.example.com/graphql", listOf(Header("A", "b")),
            Body(text = "{}", contentType = "application/json", size = 2), gql, ClientLibrary.OKHTTP),
        HttpResponse("1-1", 3, 200, "OK", "h2", emptyList(),
            Body(base64 = "AAEC", contentType = "image/png", size = 3000, truncated = true), 55),
        HttpFailure("1-2", 4, "java.net.UnknownHostException: nope", 12),
        WsConnect("1-3", 5, "wss://echo.example.com", emptyList(), ClientLibrary.KTOR),
        WsOpen("1-3", 6, 101, listOf(Header("Upgrade", "websocket"))),
        WsFrame("1-3", 7, Direction.SENT, FrameType.TEXT, Body(text = "hi", size = 2), "subscribe · OnEvent", gql),
        WsClosing("1-3", 8, 1000, "bye", Direction.RECEIVED),
        WsClosed("1-3", 9, 1000, "bye"),
        WsFailure("1-4", 10, "EOF", null),
        HttpRequest("1-5", 11, "GET", "https://x", emptyList(), Body(omittedReason = "one-shot body", size = 10), null, ClientLibrary.OTHER),
    )

    @Test
    fun everyEventRoundTrips() {
        events.forEachIndexed { i, event ->
            val line = EventCodec.encode(Envelope(i.toLong(), event))
            assertFalse(line.contains('\n'), "encoded event must be a single line")
            assertEquals(Envelope(i.toLong(), event), EventCodec.decode(line))
        }
    }

    @Test
    fun unknownTypesAreIgnored() {
        assertNull(EventCodec.decode("{\"t\":\"future_event\",\"seq\":1}"))
        assertNull(EventCodec.decode("garbage"))
    }
}
