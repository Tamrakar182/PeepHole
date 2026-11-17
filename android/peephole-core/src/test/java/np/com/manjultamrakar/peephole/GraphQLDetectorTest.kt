package np.com.manjultamrakar.peephole

import np.com.manjultamrakar.peephole.capture.GraphQLDetector
import np.com.manjultamrakar.peephole.protocol.GraphQLOperationType
import np.com.manjultamrakar.peephole.protocol.Header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQLDetectorTest {
    @Test
    fun detectsJsonPost() {
        val body = """{"operationName":"Countries","query":"query Countries(${'$'}c: String) { countries { code } }","variables":{"c":"US"}}"""
        val info = assertNotNull(GraphQLDetector.detect("POST", "https://x.dev/graphql", emptyList(), "application/json", body))
        assertEquals("Countries", info.operationName)
        assertEquals(GraphQLOperationType.QUERY, info.operationType)
        assertEquals("""{"c":"US"}""", info.variables)
    }

    @Test
    fun detectsMutationWithoutOperationNameOnAnyPath() {
        val body = """{"query":"mutation { addTodo(text: \"a\") { id } }","variables":null}"""
        val info = assertNotNull(GraphQLDetector.detect("POST", "https://x.dev/api", emptyList(), "application/json", body))
        assertEquals(GraphQLOperationType.MUTATION, info.operationType)
        assertNull(info.operationName)
        assertNull(info.variables)
    }

    @Test
    fun anonymousQueryShorthand() {
        assertEquals(GraphQLOperationType.QUERY, GraphQLDetector.operationType("{ me { id } }"))
    }

    @Test
    fun picksNamedOperationAmongFragments() {
        val doc = "fragment F on User { id }\n# comment query Nope\nsubscription OnMsg { msg { ...F } }"
        assertEquals(GraphQLOperationType.SUBSCRIPTION, GraphQLDetector.operationType(doc))
        assertEquals("OnMsg", GraphQLDetector.operationName(doc))
    }

    @Test
    fun detectsGetAndPersistedQueries() {
        val get = assertNotNull(GraphQLDetector.detect("GET", "https://x/graphql?query=%7Bme%7Bid%7D%7D&operationName=Me", emptyList(), null, null))
        assertEquals("Me", get.operationName)
        val apq = assertNotNull(
            GraphQLDetector.detect(
                "POST", "https://x/api", listOf(Header("X-APOLLO-OPERATION-NAME", "Feed")), "application/json",
                """{"operationName":"Feed","extensions":{"persistedQuery":{"version":1,"sha256Hash":"abc"}}}""",
            ),
        )
        assertTrue(apq.persisted)
        assertEquals("Feed", apq.operationName)
    }

    @Test
    fun ignoresPlainRest() {
        assertNull(GraphQLDetector.detect("POST", "https://x/users", emptyList(), "application/json", """{"name":"query"}"""))
        assertNull(GraphQLDetector.detect("GET", "https://x/users?id=1", emptyList(), null, null))
        assertNull(GraphQLDetector.detect("POST", "https://x/search", emptyList(), "application/json", """{"query":"red shoes"}"""))
    }

    @Test
    fun describesGraphQLWsMessages() {
        val (label, info) = GraphQLDetector.describeWsMessage(
            """{"id":"1","type":"subscribe","payload":{"operationName":"OnMsg","query":"subscription OnMsg { m }"}}""",
        )
        assertEquals("subscribe · OnMsg #1", label)
        assertEquals(GraphQLOperationType.SUBSCRIPTION, info?.operationType)
        assertEquals("next #1" to null, GraphQLDetector.describeWsMessage("""{"id":"1","type":"next","payload":{"data":{}}}"""))
        assertEquals(null to null, GraphQLDetector.describeWsMessage("hello"))
    }
}
