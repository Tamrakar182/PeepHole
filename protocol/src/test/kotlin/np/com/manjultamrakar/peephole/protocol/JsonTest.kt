package np.com.manjultamrakar.peephole.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonTest {
    @Test
    fun roundTripsNestedValues() {
        val value = mapOf(
            "s" to "hé \"quoted\"\n\t\u0001",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "z" to null,
            "l" to listOf(1L, "two", mapOf("x" to emptyList<Any>())),
        )
        val text = Json.stringify(value)
        assertEquals(value, Json.parse(text))
        assertEquals(value, Json.parse(Json.stringify(value, pretty = true)))
    }

    @Test
    fun parsesUnicodeEscapesAndExponents() {
        assertEquals("é", Json.parse("\"\\u00e9\""))
        assertEquals(1.0e3, Json.parse("1e3"))
        assertEquals(-7L, Json.parse("-7"))
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(Json.parseOrNull("{\"a\": }"))
        assertNull(Json.parseOrNull("[1, 2"))
        assertNull(Json.prettyOrNull("not json"))
    }

    @Test
    fun prettyPrints() {
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ]\n}", Json.prettyOrNull("{\"a\":[1,2]}"))
    }

    @Test
    fun base64RoundTrip() {
        for (len in 0..10) {
            val bytes = ByteArray(len) { (it * 37 - 5).toByte() }
            val encoded = Base64Codec.encode(bytes)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), encoded)
            assertEquals(bytes.toList(), Base64Codec.decode(encoded).toList())
        }
    }
}
