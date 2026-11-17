package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import np.com.manjultamrakar.peephole.ide.model.EntryKind
import np.com.manjultamrakar.peephole.ide.model.HttpEntry
import np.com.manjultamrakar.peephole.ide.model.NetworkEntry
import np.com.manjultamrakar.peephole.ide.model.WsEntry
import java.awt.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object Format {
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT) }

    val SUCCESS = JBColor(Color(0x2E7D32), Color(0x6FBF73))
    val REDIRECT = JBColor(Color(0x1565C0), Color(0x64B5F6))
    val CLIENT_ERROR = JBColor(Color(0xE65100), Color(0xFFB74D))
    val SERVER_ERROR = JBColor(Color(0xC62828), Color(0xEF6C6C))
    val GRAPHQL = JBColor(Color(0xB0238A), Color(0xE57CC4))
    val WEBSOCKET = JBColor(Color(0x00796B), Color(0x4DB6AC))

    fun time(ms: Long): String = if (ms <= 0) "" else timeFormat.get().format(Date(ms))
    fun dateTime(ms: Long): String = if (ms <= 0) "" else dateTimeFormat.get().format(Date(ms))

    fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    }

    fun duration(ms: Long?): String = when {
        ms == null -> ""
        ms < 1000 -> "$ms ms"
        else -> String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
    }

    fun typeLabel(e: NetworkEntry): String = when (e) {
        is WsEntry -> if (e.isGraphQL) "GraphQL WS" else "WS"
        is HttpEntry -> e.kind.label
    }

    fun typeColor(e: NetworkEntry): Color? = when (e.kind) {
        EntryKind.GRAPHQL -> GRAPHQL
        EntryKind.WEBSOCKET -> WEBSOCKET
        EntryKind.REST -> null
    }

    fun methodLabel(e: NetworkEntry): String = when (e) {
        is HttpEntry -> e.request?.graphql?.let { it.operationType.name.lowercase() } ?: e.request?.method ?: ""
        is WsEntry -> "WS"
    }

    /** Path column: GraphQL operation name when known, otherwise the URL path. */
    fun pathLabel(e: NetworkEntry): String = when (e) {
        is HttpEntry -> e.request?.graphql?.operationName?.let { "$it  ${e.path}" } ?: e.path
        is WsEntry -> e.path
    }

    fun status(e: NetworkEntry): Pair<String, Color?> = when (e) {
        is HttpEntry -> when {
            e.failure != null -> "Failed" to SERVER_ERROR
            e.response == null -> "…" to JBColor.GRAY
            else -> e.response!!.code.toString() to statusColor(e.response!!.code)
        }
        is WsEntry -> when {
            e.failure != null -> "Failed" to SERVER_ERROR
            e.closed != null -> "Closed ${e.closed!!.code}" to JBColor.GRAY
            e.closing != null -> "Closing" to JBColor.GRAY
            e.open != null -> "Open" to SUCCESS
            else -> "Connecting" to JBColor.GRAY
        }
    }

    /** Sort key for the status column. */
    fun statusSortKey(e: NetworkEntry): Int = when (e) {
        is HttpEntry -> when {
            e.failure != null -> 1000
            e.response == null -> -1
            else -> e.response!!.code
        }
        is WsEntry -> if (e.failure != null) 1000 else 101
    }

    fun statusColor(code: Int): Color? = when (code) {
        in 200..299 -> SUCCESS
        in 300..399 -> REDIRECT
        in 400..499 -> CLIENT_ERROR
        in 500..599 -> SERVER_ERROR
        else -> null
    }

    fun sizeLabel(e: NetworkEntry): String = when (e) {
        is HttpEntry -> e.response?.body?.size?.takeIf { it >= 0 }?.let { size(it) } ?: ""
        is WsEntry -> "${e.sentCount + e.receivedCount} msgs"
    }

    fun sizeSortKey(e: NetworkEntry): Long = when (e) {
        is HttpEntry -> e.response?.body?.size ?: -1
        is WsEntry -> (e.sentCount + e.receivedCount).toLong()
    }

    fun durationLabel(e: NetworkEntry): String = when (e) {
        is HttpEntry -> duration(e.durationMs)
        is WsEntry -> if (e.isActive) (if (e.open != null) "live" else "") else {
            val end = e.closed?.timestamp ?: e.failure?.timestamp ?: 0
            if (end > 0 && e.startTime > 0) duration(end - e.startTime) else ""
        }
    }

    fun durationSortKey(e: NetworkEntry): Long = when (e) {
        is HttpEntry -> e.durationMs ?: -1
        is WsEntry -> if (e.isActive) Long.MAX_VALUE else ((e.closed?.timestamp ?: e.failure?.timestamp ?: 0) - e.startTime)
    }

    fun attrs(color: Color?, bold: Boolean = false): SimpleTextAttributes = when {
        color == null && !bold -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        else -> SimpleTextAttributes(if (bold) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN, color)
    }
}
