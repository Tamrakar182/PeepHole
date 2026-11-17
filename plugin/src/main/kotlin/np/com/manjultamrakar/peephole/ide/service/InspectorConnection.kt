package np.com.manjultamrakar.peephole.ide.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import np.com.manjultamrakar.peephole.ide.adb.AdbClient
import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.EventCodec
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import np.com.manjultamrakar.peephole.protocol.PROTOCOL_VERSION
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

enum class ConnectionState { CONNECTING, CONNECTED, WAITING, INCOMPATIBLE, CLOSED }

/**
 * Connects to one app's inspector socket through `adb forward` and streams its events.
 * Reconnects automatically (e.g. when the app restarts) until [close] is called.
 */
class InspectorConnection(
    private val adb: AdbClient,
    private val serial: String,
    private val socketName: String,
    private val onEvent: (Envelope) -> Unit,
    private val onState: (ConnectionState, String?) -> Unit,
) {
    private val log = logger<InspectorConnection>()

    @Volatile
    private var closed = false

    @Volatile
    private var socket: Socket? = null

    fun start() {
        ApplicationManager.getApplication().executeOnPooledThread { runLoop() }
    }

    fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun runLoop() {
        var backoffMs = 500L
        while (!closed) {
            onState(ConnectionState.CONNECTING, null)
            var port = -1
            var gotHello = false
            try {
                port = adb.forward(serial, socketName)
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress("127.0.0.1", port), 3000)
                    s.soTimeout = 20_000 // the app sends a heartbeat every 5s
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 64 * 1024)
                    while (!closed) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val envelope = EventCodec.decode(line) ?: continue
                        val event = envelope.event
                        if (event is InspectorEvent.Hello) {
                            if (event.protocolVersion != PROTOCOL_VERSION) {
                                onState(
                                    ConnectionState.INCOMPATIBLE,
                                    "App uses inspector protocol v${event.protocolVersion}, plugin supports v$PROTOCOL_VERSION. Update the library or the plugin.",
                                )
                                closed = true
                                break
                            }
                            gotHello = true
                            backoffMs = 500L
                            onState(ConnectionState.CONNECTED, "${event.appName ?: event.packageName} · pid ${event.pid}")
                        }
                        if (gotHello) onEvent(envelope)
                    }
                }
            } catch (e: SocketTimeoutException) {
                log.debug("Inspector connection timed out", e)
            } catch (e: Exception) {
                if (!closed) log.debug("Inspector connection failed", e)
            } finally {
                socket = null
                if (port > 0) adb.removeForward(serial, port)
            }
            if (closed) break
            onState(ConnectionState.WAITING, if (gotHello) "Disconnected — waiting for the app…" else "Waiting for the app…")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(4_000)
        }
        onState(ConnectionState.CLOSED, null)
    }
}
