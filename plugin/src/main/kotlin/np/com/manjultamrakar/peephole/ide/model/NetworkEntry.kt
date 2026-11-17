package np.com.manjultamrakar.peephole.ide.model

import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import java.net.URI

enum class EntryKind(val label: String) { REST("REST"), GRAPHQL("GraphQL"), WEBSOCKET("WS") }

/** One row in the tool window. Mutated only on the EDT by [SessionStore]. */
sealed class NetworkEntry(val id: String) {
    /** Incremented on every change so the UI can skip redundant refreshes. */
    var version: Int = 0
        internal set

    abstract val startTime: Long
    abstract val url: String
    abstract val kind: EntryKind

    val host: String get() = parsedUri?.let { u -> u.host?.let { h -> if (u.port > 0) "$h:${u.port}" else h } } ?: ""
    val path: String
        get() = parsedUri?.let { u ->
            val p = u.rawPath.orEmpty().ifEmpty { "/" }
            if (u.rawQuery != null) "$p?${u.rawQuery}" else p
        } ?: url

    private val parsedUri: URI? by lazy { try { URI(url) } catch (_: Exception) { null } }
}

class HttpEntry(id: String, var request: InspectorEvent.HttpRequest?) : NetworkEntry(id) {
    var response: InspectorEvent.HttpResponse? = null
    var failure: InspectorEvent.HttpFailure? = null

    override val startTime: Long get() = request?.timestamp ?: response?.timestamp ?: failure?.timestamp ?: 0
    override val url: String get() = request?.url ?: ""
    override val kind: EntryKind get() = if (request?.graphql != null) EntryKind.GRAPHQL else EntryKind.REST
    val isPending: Boolean get() = response == null && failure == null
    val durationMs: Long? get() = response?.durationMs ?: failure?.durationMs
}

class WsEntry(id: String) : NetworkEntry(id) {
    var connect: InspectorEvent.WsConnect? = null
    var open: InspectorEvent.WsOpen? = null
    var closing: InspectorEvent.WsClosing? = null
    var closed: InspectorEvent.WsClosed? = null
    var failure: InspectorEvent.WsFailure? = null
    val messages = ArrayList<InspectorEvent>()
    var sentCount = 0
        internal set
    var receivedCount = 0
        internal set
    var droppedMessages = 0
        internal set
    private var firstSeen: Long = 0

    override val startTime: Long get() = connect?.timestamp ?: firstSeen
    override val url: String get() = connect?.url ?: ""
    override val kind: EntryKind get() = EntryKind.WEBSOCKET

    val isGraphQL: Boolean
        get() = connect?.headers?.any { it.name.equals("Sec-WebSocket-Protocol", true) && it.value.contains("graphql", true) } == true ||
            open?.headers?.any { it.name.equals("Sec-WebSocket-Protocol", true) && it.value.contains("graphql", true) } == true ||
            messages.any { it is InspectorEvent.WsFrame && it.graphql != null }

    val isActive: Boolean get() = closed == null && failure == null

    internal fun touch(ts: Long) {
        if (firstSeen == 0L) firstSeen = ts
    }

    internal fun addMessage(event: InspectorEvent, max: Int) {
        if (event is InspectorEvent.WsFrame) {
            if (event.direction == Direction.SENT) sentCount++ else receivedCount++
        }
        messages.add(event)
        if (messages.size > max) {
            messages.subList(0, messages.size - max).clear()
            droppedMessages++
        }
    }
}
