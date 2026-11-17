package np.com.manjultamrakar.peephole.ktor

import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.GraphQLOperationType
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KtorCaptureTest {
    private val events = CopyOnWriteArrayList<InspectorEvent>()
    private val listener: (InspectorEvent) -> Unit = { events.add(it) }

    @Before
    fun setUp() = Peephole.addEventListener(listener)

    @After
    fun tearDown() = Peephole.removeEventListener(listener)

    private fun client() = HttpClient(MockEngine { request ->
        when (request.url.encodedPath) {
            "/fail" -> respondError(HttpStatusCode.InternalServerError, "boom")
            else -> respond("{\"data\":{\"ok\":true}}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    }) {
        install(PeepholePlugin)
    }

    private suspend fun awaitResponses(n: Int) {
        repeat(100) {
            if (events.filterIsInstance<InspectorEvent.HttpResponse>().size >= n) return
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun capturesRequestAndResponse() = runBlocking {
        val text = client().get("https://api.test/users?id=1").bodyAsText()
        assertEquals("{\"data\":{\"ok\":true}}", text, "app must still be able to read the body")
        awaitResponses(1)
        val req = events.filterIsInstance<InspectorEvent.HttpRequest>().single()
        assertEquals("GET", req.method)
        assertEquals(ClientLibrary.KTOR, req.client)
        val res = events.filterIsInstance<InspectorEvent.HttpResponse>().single()
        assertEquals(req.id, res.id)
        assertEquals("{\"data\":{\"ok\":true}}", res.body?.text)
    }

    @Test
    fun capturesGraphQLAndErrors() = runBlocking {
        val c = client()
        c.post("https://api.test/graphql") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"subscription OnX { x }"}""")
        }
        c.get("https://api.test/fail")
        awaitResponses(2)
        val gql = assertNotNull(events.filterIsInstance<InspectorEvent.HttpRequest>().first().graphql)
        assertEquals(GraphQLOperationType.SUBSCRIPTION, gql.operationType)
        assertEquals("OnX", gql.operationName)
        assertEquals("""{"query":"subscription OnX { x }"}""", events.filterIsInstance<InspectorEvent.HttpRequest>().first().body?.text)
        assertEquals(500, events.filterIsInstance<InspectorEvent.HttpResponse>().last().code)
    }
}
