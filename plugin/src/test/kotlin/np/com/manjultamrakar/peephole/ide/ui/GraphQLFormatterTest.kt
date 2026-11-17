package np.com.manjultamrakar.peephole.ide.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphQLFormatterTest {
    @Test
    fun formatsSelectionSetsOneFieldPerLine() {
        val src = "query GetCreatorImages(\$skip: Int, \$types: [Type!]) { creatorImages(request: { query: null types: \$types }, order: [{ createdAt: DESC }], skip: \$skip) " +
            "{ items { id name ...ImageParts analytics { likes } } ... on Paged { totalCount } alias: pageInfo @include(if: true) { hasNextPage } } }"
        val expected = """
            query GetCreatorImages(${'$'}skip: Int, ${'$'}types: [Type!]) {
              creatorImages(request: { query: null types: ${'$'}types }, order: [{ createdAt: DESC }], skip: ${'$'}skip) {
                items {
                  id
                  name
                  ...ImageParts
                  analytics {
                    likes
                  }
                }
                ... on Paged {
                  totalCount
                }
                alias: pageInfo @include(if: true) {
                  hasNextPage
                }
              }
            }
        """.trimIndent() + "\n"
        assertEquals(expected, GraphQLFormatter.formatOrNull(src))
    }

    @Test
    fun separatesDefinitionsAndRejectsUnbalanced() {
        assertEquals("query A {\n  a\n}\n\nfragment F on T {\n  b\n}\n", GraphQLFormatter.formatOrNull("query A { a } fragment F on T { b }"))
        assertNull(GraphQLFormatter.formatOrNull("query { a "))
    }
}
