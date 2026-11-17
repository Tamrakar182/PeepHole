package np.com.manjultamrakar.peephole.capture

import np.com.manjultamrakar.peephole.protocol.GraphQLInfo
import np.com.manjultamrakar.peephole.protocol.GraphQLOperationType
import np.com.manjultamrakar.peephole.protocol.Header
import np.com.manjultamrakar.peephole.protocol.Json
import java.net.URLDecoder

/** Recognises GraphQL requests (POST JSON, `application/graphql`, GET `?query=`) and GraphQL-over-WebSocket messages. */
object GraphQLDetector {

    @JvmStatic
    fun detect(method: String, url: String, headers: List<Header>, contentType: String?, bodyText: String?): GraphQLInfo? {
        val headerName = headers.firstOrNull { it.name.equals("X-APOLLO-OPERATION-NAME", ignoreCase = true) }?.value
        val ct = contentType?.lowercase() ?: headers.firstOrNull { it.name.equals("Content-Type", true) }?.value?.lowercase()
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        val looksLikeEndpoint = path.endsWith("/graphql") || path.endsWith("/gql") || headerName != null

        if (ct != null && ct.startsWith("application/graphql") && !ct.contains("json") && bodyText != null) {
            return fromDocument(bodyText, headerName, null)
        }

        if (method.equals("GET", ignoreCase = true)) {
            val params = queryParams(url)
            val query = params["query"]
            val extensions = params["extensions"]
            if (query != null || (looksLikeEndpoint && (extensions != null || params["operationName"] != null))) {
                return build(query, params["operationName"] ?: headerName, params["variables"], persisted = query == null)
            }
            return null
        }

        if (bodyText.isNullOrBlank()) return null
        val trimmed = bodyText.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = Json.parseOrNull(trimmed) ?: return null
            val request = when (parsed) {
                is Map<*, *> -> parsed
                is List<*> -> parsed.firstOrNull() as? Map<*, *> // batched queries: describe the first
                else -> null
            } ?: return null
            return fromJsonRequest(request, headerName, looksLikeEndpoint)
        }
        // Raw document posted to a GraphQL endpoint.
        if (looksLikeEndpoint && OPERATION_START.containsMatchIn(trimmed)) return fromDocument(trimmed, headerName, null)
        return null
    }

    /** Builds [GraphQLInfo] from a `{query, operationName, variables, extensions}` map. */
    @JvmStatic
    fun fromJsonRequest(request: Map<*, *>, headerName: String? = null, looksLikeEndpoint: Boolean = true): GraphQLInfo? {
        val query = request["query"] as? String
        val operationName = (request["operationName"] as? String) ?: headerName
        val hasPersisted = (request["extensions"] as? Map<*, *>)?.containsKey("persistedQuery") == true
        if (query == null && !(hasPersisted && (operationName != null || looksLikeEndpoint))) return null
        if (query != null && !looksLikeEndpoint && !request.containsKey("variables") && !OPERATION_START.containsMatchIn(query)) return null
        val variables = request["variables"]?.let { if (it is String) it else Json.stringify(it) }
        return build(query, operationName, variables, persisted = query == null)
    }

    /**
     * Describes a WebSocket text message using the `graphql-transport-ws` / legacy `subscriptions-transport-ws`
     * protocols. Returns a label like `subscribe · OnMessage #1` and GraphQL info for start messages.
     */
    @JvmStatic
    fun describeWsMessage(text: String): Pair<String?, GraphQLInfo?> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return null to null
        val msg = Json.parseOrNull(trimmed) as? Map<*, *> ?: return null to null
        val type = msg["type"] as? String ?: return null to null
        val id = msg["id"]?.toString()
        val payload = msg["payload"] as? Map<*, *>
        val gql = if (type == "subscribe" || type == "start") payload?.let { fromJsonRequest(it) } else null
        val label = buildString {
            append(type)
            gql?.operationName?.let { append(" · ").append(it) }
            if (id != null) append(" #").append(id)
        }
        return label to gql
    }

    @JvmStatic
    fun operationType(document: String, operationName: String? = null): GraphQLOperationType {
        val doc = stripComments(document)
        if (operationName != null) {
            Regex("\\b(query|mutation|subscription)\\s+${Regex.escape(operationName)}\\b").find(doc)?.let {
                return typeOf(it.groupValues[1])
            }
        }
        val first = OPERATION_START.find(doc) ?: return if (doc.trimStart().startsWith("{")) GraphQLOperationType.QUERY else GraphQLOperationType.UNKNOWN
        return if (first.groupValues[1].isEmpty()) GraphQLOperationType.QUERY else typeOf(first.groupValues[1])
    }

    @JvmStatic
    fun operationName(document: String): String? =
        OPERATION_START.find(stripComments(document))?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }

    private fun fromDocument(document: String, operationName: String?, variables: String?): GraphQLInfo =
        build(document, operationName, variables, persisted = false)

    private fun build(query: String?, operationName: String?, variables: String?, persisted: Boolean): GraphQLInfo {
        val name = operationName ?: query?.let { operationName(it) }
        val type = query?.let { operationType(it, name) } ?: GraphQLOperationType.UNKNOWN
        return GraphQLInfo(name, type, query, variables?.takeIf { it != "null" }, persisted)
    }

    private fun typeOf(keyword: String) = when (keyword) {
        "mutation" -> GraphQLOperationType.MUTATION
        "subscription" -> GraphQLOperationType.SUBSCRIPTION
        else -> GraphQLOperationType.QUERY
    }

    private fun stripComments(doc: String) = doc.lineSequence().joinToString("\n") { line ->
        val idx = line.indexOf('#')
        if (idx >= 0 && line.lastIndexOf('"', idx) < 0) line.substring(0, idx) else line
    }

    private fun queryParams(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            try {
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    /** Matches the start of the first operation definition (or anonymous `{`). */
    private val OPERATION_START = Regex("(?:^|[}\\s])(query|mutation|subscription)\\b\\s*([_A-Za-z][_0-9A-Za-z]*)?")
}
