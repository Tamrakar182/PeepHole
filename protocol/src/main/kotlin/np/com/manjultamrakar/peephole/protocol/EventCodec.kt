package np.com.manjultamrakar.peephole.protocol

import np.com.manjultamrakar.peephole.protocol.InspectorEvent.*

/**
 * Encodes events as single-line JSON objects (newline-delimited JSON on the wire).
 * Unknown event types and fields are ignored on decode for forward compatibility.
 */
object EventCodec {

    fun encode(envelope: Envelope): String = Json.stringify(toMap(envelope))

    fun decode(line: String): Envelope? {
        val map = Json.parseOrNull(line) as? Map<*, *> ?: return null
        return try {
            fromMap(map)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun toMap(envelope: Envelope): Map<String, Any?> {
        val e = envelope.event
        val m = LinkedHashMap<String, Any?>()
        m["t"] = typeOf(e)
        m["seq"] = envelope.seq
        m["ts"] = e.timestamp
        when (e) {
            is Hello -> {
                m["protocolVersion"] = e.protocolVersion
                m["packageName"] = e.packageName
                m["processName"] = e.processName
                m["appName"] = e.appName
                m["pid"] = e.pid
                m["libraryVersion"] = e.libraryVersion
            }
            is HttpRequest -> {
                m["id"] = e.id
                m["method"] = e.method
                m["url"] = e.url
                m["headers"] = headers(e.headers)
                m["body"] = body(e.body)
                m["graphql"] = graphql(e.graphql)
                m["client"] = e.client.name
            }
            is HttpResponse -> {
                m["id"] = e.id
                m["code"] = e.code
                m["message"] = e.message
                m["protocol"] = e.protocol
                m["headers"] = headers(e.headers)
                m["body"] = body(e.body)
                m["durationMs"] = e.durationMs
            }
            is HttpFailure -> {
                m["id"] = e.id
                m["error"] = e.error
                m["durationMs"] = e.durationMs
            }
            is WsConnect -> {
                m["id"] = e.id
                m["url"] = e.url
                m["headers"] = headers(e.headers)
                m["client"] = e.client.name
            }
            is WsOpen -> {
                m["id"] = e.id
                m["code"] = e.code
                m["headers"] = headers(e.headers)
            }
            is WsFrame -> {
                m["id"] = e.id
                m["direction"] = e.direction.name
                m["frameType"] = e.frameType.name
                m["payload"] = body(e.payload)
                m["label"] = e.label
                m["graphql"] = graphql(e.graphql)
            }
            is WsClosing -> {
                m["id"] = e.id
                m["code"] = e.code
                m["reason"] = e.reason
                m["initiatedBy"] = e.initiatedBy.name
            }
            is WsClosed -> {
                m["id"] = e.id
                m["code"] = e.code
                m["reason"] = e.reason
            }
            is WsFailure -> {
                m["id"] = e.id
                m["error"] = e.error
                m["responseCode"] = e.responseCode
            }
        }
        // Drop nulls to keep lines short.
        return m.filterValues { it != null }
    }

    fun fromMap(m: Map<*, *>): Envelope? {
        val seq = m.long("seq") ?: 0L
        val ts = m.long("ts") ?: 0L
        val event: InspectorEvent = when (m["t"]) {
            "hello" -> Hello(
                timestamp = ts,
                protocolVersion = m.int("protocolVersion") ?: 0,
                packageName = m.str("packageName") ?: "",
                processName = m.str("processName") ?: m.str("packageName") ?: "",
                appName = m.str("appName"),
                pid = m.int("pid") ?: 0,
                libraryVersion = m.str("libraryVersion") ?: "",
            )
            "http_request" -> HttpRequest(
                id = m.req("id"),
                timestamp = ts,
                method = m.str("method") ?: "GET",
                url = m.str("url") ?: "",
                headers = headers(m["headers"]),
                body = body(m["body"]),
                graphql = graphql(m["graphql"]),
                client = enumOr(m.str("client"), ClientLibrary.OTHER),
            )
            "http_response" -> HttpResponse(
                id = m.req("id"),
                timestamp = ts,
                code = m.int("code") ?: 0,
                message = m.str("message") ?: "",
                protocol = m.str("protocol"),
                headers = headers(m["headers"]),
                body = body(m["body"]),
                durationMs = m.long("durationMs") ?: 0,
            )
            "http_failure" -> HttpFailure(m.req("id"), ts, m.str("error") ?: "", m.long("durationMs") ?: 0)
            "ws_connect" -> WsConnect(
                id = m.req("id"),
                timestamp = ts,
                url = m.str("url") ?: "",
                headers = headers(m["headers"]),
                client = enumOr(m.str("client"), ClientLibrary.OTHER),
            )
            "ws_open" -> WsOpen(m.req("id"), ts, m.int("code") ?: 101, headers(m["headers"]))
            "ws_frame" -> WsFrame(
                id = m.req("id"),
                timestamp = ts,
                direction = enumOr(m.str("direction"), Direction.RECEIVED),
                frameType = enumOr(m.str("frameType"), FrameType.TEXT),
                payload = body(m["payload"]) ?: Body(),
                label = m.str("label"),
                graphql = graphql(m["graphql"]),
            )
            "ws_closing" -> WsClosing(
                m.req("id"), ts, m.int("code") ?: 0, m.str("reason") ?: "",
                enumOr(m.str("initiatedBy"), Direction.RECEIVED),
            )
            "ws_closed" -> WsClosed(m.req("id"), ts, m.int("code") ?: 0, m.str("reason") ?: "")
            "ws_failure" -> WsFailure(m.req("id"), ts, m.str("error") ?: "", m.int("responseCode"))
            else -> return null
        }
        return Envelope(seq, event)
    }

    private fun typeOf(e: InspectorEvent): String = when (e) {
        is Hello -> "hello"
        is HttpRequest -> "http_request"
        is HttpResponse -> "http_response"
        is HttpFailure -> "http_failure"
        is WsConnect -> "ws_connect"
        is WsOpen -> "ws_open"
        is WsFrame -> "ws_frame"
        is WsClosing -> "ws_closing"
        is WsClosed -> "ws_closed"
        is WsFailure -> "ws_failure"
    }

    private fun headers(list: List<Header>): List<List<String>> = list.map { listOf(it.name, it.value) }

    private fun headers(raw: Any?): List<Header> = (raw as? List<*>)?.mapNotNull { pair ->
        val p = pair as? List<*> ?: return@mapNotNull null
        Header(p.getOrNull(0)?.toString() ?: return@mapNotNull null, p.getOrNull(1)?.toString() ?: "")
    } ?: emptyList()

    private fun body(b: Body?): Map<String, Any?>? = b?.let {
        linkedMapOf<String, Any?>(
            "text" to it.text,
            "base64" to it.base64,
            "contentType" to it.contentType,
            "size" to it.size,
            "truncated" to it.truncated.takeIf { t -> t },
            "omitted" to it.omittedReason,
        ).filterValues { v -> v != null }
    }

    private fun body(raw: Any?): Body? {
        val m = raw as? Map<*, *> ?: return null
        return Body(
            text = m.str("text"),
            base64 = m.str("base64"),
            contentType = m.str("contentType"),
            size = m.long("size") ?: -1,
            truncated = m["truncated"] == true,
            omittedReason = m.str("omitted"),
        )
    }

    private fun graphql(g: GraphQLInfo?): Map<String, Any?>? = g?.let {
        linkedMapOf<String, Any?>(
            "operationName" to it.operationName,
            "operationType" to it.operationType.name,
            "query" to it.query,
            "variables" to it.variables,
            "persisted" to it.persisted.takeIf { p -> p },
        ).filterValues { v -> v != null }
    }

    private fun graphql(raw: Any?): GraphQLInfo? {
        val m = raw as? Map<*, *> ?: return null
        return GraphQLInfo(
            operationName = m.str("operationName"),
            operationType = enumOr(m.str("operationType"), GraphQLOperationType.UNKNOWN),
            query = m.str("query"),
            variables = m.str("variables"),
            persisted = m["persisted"] == true,
        )
    }

    private fun Map<*, *>.str(key: String): String? = this[key] as? String
    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()
    private fun Map<*, *>.req(key: String): String = str(key) ?: throw IllegalArgumentException("missing $key")

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
