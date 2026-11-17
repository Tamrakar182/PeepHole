package np.com.manjultamrakar.peephole.protocol

/** Bumped on incompatible wire-format changes. The IDE refuses to talk to a different major version. */
const val PROTOCOL_VERSION = 1

/** Prefix of the abstract unix socket each inspected process listens on: `peephole_<processName>`. */
const val SOCKET_PREFIX = "peephole_"

data class Header(val name: String, val value: String)

/**
 * A captured HTTP body or WebSocket payload.
 * Exactly one of [text] / [base64] is set when content was captured; both are null when it was omitted
 * (see [omittedReason]).
 */
data class Body(
    val text: String? = null,
    val base64: String? = null,
    val contentType: String? = null,
    /** Full size in bytes, or -1 when unknown. */
    val size: Long = -1,
    /** True when only a prefix of the content was captured. */
    val truncated: Boolean = false,
    val omittedReason: String? = null,
) {
    val isBinary: Boolean get() = base64 != null
}

enum class GraphQLOperationType { QUERY, MUTATION, SUBSCRIPTION, UNKNOWN }

data class GraphQLInfo(
    val operationName: String?,
    val operationType: GraphQLOperationType,
    val query: String?,
    /** Variables as a JSON string. */
    val variables: String?,
    /** True for persisted / automatic-persisted queries sent without a query document. */
    val persisted: Boolean = false,
)

enum class ClientLibrary { OKHTTP, KTOR, OTHER }

enum class Direction { SENT, RECEIVED }

enum class FrameType { TEXT, BINARY, PING, PONG, CLOSE }

sealed class InspectorEvent {
    abstract val timestamp: Long

    /** Sent by the app to every client right after it connects. */
    data class Hello(
        override val timestamp: Long,
        val protocolVersion: Int,
        val packageName: String,
        val processName: String,
        val appName: String?,
        val pid: Int,
        val libraryVersion: String,
    ) : InspectorEvent()

    data class HttpRequest(
        val id: String,
        override val timestamp: Long,
        val method: String,
        val url: String,
        val headers: List<Header>,
        val body: Body?,
        val graphql: GraphQLInfo?,
        val client: ClientLibrary,
    ) : InspectorEvent()

    data class HttpResponse(
        val id: String,
        override val timestamp: Long,
        val code: Int,
        val message: String,
        val protocol: String?,
        val headers: List<Header>,
        val body: Body?,
        val durationMs: Long,
    ) : InspectorEvent()

    data class HttpFailure(
        val id: String,
        override val timestamp: Long,
        val error: String,
        val durationMs: Long,
    ) : InspectorEvent()

    data class WsConnect(
        val id: String,
        override val timestamp: Long,
        val url: String,
        val headers: List<Header>,
        val client: ClientLibrary,
    ) : InspectorEvent()

    data class WsOpen(
        val id: String,
        override val timestamp: Long,
        val code: Int,
        val headers: List<Header>,
    ) : InspectorEvent()

    data class WsFrame(
        val id: String,
        override val timestamp: Long,
        val direction: Direction,
        val frameType: FrameType,
        val payload: Body,
        /** Short description, e.g. `subscribe · OnMessage` for GraphQL-over-WebSocket messages. */
        val label: String? = null,
        val graphql: GraphQLInfo? = null,
    ) : InspectorEvent()

    data class WsClosing(
        val id: String,
        override val timestamp: Long,
        val code: Int,
        val reason: String,
        val initiatedBy: Direction,
    ) : InspectorEvent()

    data class WsClosed(
        val id: String,
        override val timestamp: Long,
        val code: Int,
        val reason: String,
    ) : InspectorEvent()

    data class WsFailure(
        val id: String,
        override val timestamp: Long,
        val error: String,
        val responseCode: Int?,
    ) : InspectorEvent()
}

/** An event plus the per-process sequence number used to de-duplicate replays. */
data class Envelope(val seq: Long, val event: InspectorEvent)
