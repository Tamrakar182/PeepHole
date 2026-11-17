package np.com.manjultamrakar.peephole

import android.content.Context
import android.util.Log
import np.com.manjultamrakar.peephole.core.BuildConfig
import np.com.manjultamrakar.peephole.core.InspectorServer
import np.com.manjultamrakar.peephole.core.ProcessInfo
import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import np.com.manjultamrakar.peephole.protocol.PROTOCOL_VERSION
import np.com.manjultamrakar.peephole.protocol.SOCKET_PREFIX
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Entry point of the inspector. The library starts itself through a ContentProvider; call [init] only to
 * pass a custom [PeepholeConfig] or when auto-init is disabled.
 *
 * Captured events are kept in a small ring buffer and streamed to the Android Studio plugin over an
 * abstract unix socket (`peephole_<processName>`) that the plugin reaches through `adb forward`.
 */
object Peephole {

    @Volatile
    @JvmStatic
    var config: PeepholeConfig = PeepholeConfig()

    private val lock = Any()
    private val buffer = ArrayDeque<Envelope>()
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val listeners = CopyOnWriteArrayList<(InspectorEvent) -> Unit>()
    private val seq = AtomicLong()
    private val ids = AtomicLong()
    private val pendingSummaries = ConcurrentHashMap<String, String>()

    @Volatile
    private var server: InspectorServer? = null

    @Volatile
    private var hello: InspectorEvent.Hello? = null

    private val idPrefix: String by lazy { ProcessInfo.pid().toString(36) }

    /** Starts the socket server (idempotent) and optionally replaces the [config]. */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, config: PeepholeConfig = this.config) {
        this.config = config
        if (!config.enabled || server != null) return
        synchronized(this) {
            if (server != null) return
            val app = context.applicationContext ?: context
            val processName = ProcessInfo.processName(app)
            val appName = try {
                app.applicationInfo.loadLabel(app.packageManager).toString()
            } catch (_: Exception) {
                null
            }
            hello = InspectorEvent.Hello(
                timestamp = System.currentTimeMillis(),
                protocolVersion = PROTOCOL_VERSION,
                packageName = app.packageName,
                processName = processName,
                appName = appName,
                pid = ProcessInfo.pid(),
                libraryVersion = BuildConfig.LIBRARY_VERSION,
            )
            server = InspectorServer(socketName(processName)).also { it.start() }
        }
    }

    /** Stops the socket server. Captured events are kept. */
    @JvmStatic
    fun shutdown() {
        synchronized(this) {
            server?.stop()
            server = null
        }
    }

    @JvmStatic
    val isEnabled: Boolean get() = config.enabled

    /** Unique id for a call / WebSocket within this process. */
    @JvmStatic
    fun newId(): String = "$idPrefix-${ids.incrementAndGet()}"

    /** Observe every captured event in-process (e.g. for tests or custom logging). */
    @JvmStatic
    fun addEventListener(listener: (InspectorEvent) -> Unit) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeEventListener(listener: (InspectorEvent) -> Unit) {
        listeners.remove(listener)
    }

    /** Clears the replay buffer. */
    @JvmStatic
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    /**
     * Records an event. Used by the OkHttp / Ktor integrations; you normally don't call this yourself.
     */
    @JvmStatic
    fun emit(event: InspectorEvent) {
        val cfg = config
        if (!cfg.enabled) return
        synchronized(lock) {
            val envelope = Envelope(seq.incrementAndGet(), event)
            buffer.addLast(envelope)
            while (buffer.size > cfg.bufferSize.coerceAtLeast(1)) buffer.removeFirst()
            for (sink in sinks) sink.send(envelope)
        }
        for (l in listeners) {
            try {
                l(event)
            } catch (_: Throwable) {
            }
        }
        if (cfg.logToLogcat) logSummary(cfg.logcatTag, event)
    }

    internal fun socketName(processName: String) = SOCKET_PREFIX + processName

    internal fun hello(): InspectorEvent.Hello? = hello

    /** Atomically registers a sink and returns the events it should replay first. */
    internal fun attach(sink: Sink): List<Envelope> = synchronized(lock) {
        sinks.add(sink)
        buffer.toList()
    }

    internal fun detach(sink: Sink) {
        sinks.remove(sink)
    }

    internal fun interface Sink {
        fun send(envelope: Envelope)
    }

    private fun logSummary(tag: String, event: InspectorEvent) {
        val line = when (event) {
            is InspectorEvent.HttpRequest -> {
                val op = event.graphql?.let { " [${it.operationType.name.lowercase()} ${it.operationName ?: ""}]" } ?: ""
                val summary = "${event.method} ${event.url}$op"
                if (pendingSummaries.size < 1000) pendingSummaries[event.id] = summary
                "--> $summary"
            }
            is InspectorEvent.HttpResponse ->
                "<-- ${event.code} ${pendingSummaries.remove(event.id) ?: event.id} (${event.durationMs}ms, ${event.body?.size ?: 0}B)"
            is InspectorEvent.HttpFailure ->
                "<-- FAILED ${pendingSummaries.remove(event.id) ?: event.id}: ${event.error}"
            is InspectorEvent.WsConnect -> "WS connect ${event.url}"
            is InspectorEvent.WsOpen -> "WS open ${event.id} (${event.code})"
            is InspectorEvent.WsFrame -> {
                val arrow = if (event.direction == np.com.manjultamrakar.peephole.protocol.Direction.SENT) "↑" else "↓"
                "WS $arrow ${event.id} ${event.label ?: event.frameType.name.lowercase()} (${event.payload.size}B)"
            }
            is InspectorEvent.WsClosing -> "WS closing ${event.id} ${event.code} ${event.reason}"
            is InspectorEvent.WsClosed -> "WS closed ${event.id} ${event.code} ${event.reason}"
            is InspectorEvent.WsFailure -> "WS failed ${event.id}: ${event.error}"
            is InspectorEvent.Hello -> return
        }
        try {
            Log.d(tag, line)
        } catch (_: Throwable) {
            // android.util.Log is unavailable in plain JVM unit tests.
        }
    }
}
