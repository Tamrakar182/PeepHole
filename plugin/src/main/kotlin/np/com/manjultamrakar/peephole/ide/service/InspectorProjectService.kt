package np.com.manjultamrakar.peephole.ide.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import np.com.manjultamrakar.peephole.ide.adb.AdbClient
import np.com.manjultamrakar.peephole.ide.model.SessionKey
import np.com.manjultamrakar.peephole.ide.model.SessionStore
import np.com.manjultamrakar.peephole.ide.model.StoreChanges
import np.com.manjultamrakar.peephole.ide.settings.InspectorSettings
import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.SOCKET_PREFIX
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Timer

data class ConnectionStatus(val key: SessionKey?, val state: ConnectionState, val message: String?)

/**
 * Owns device discovery, the active connection and the captured sessions for a project.
 * Everything except the network/adb threads runs on the EDT.
 */
@Service(Service.Level.PROJECT)
class InspectorProjectService(private val project: Project) : Disposable {

    interface Listener {
        fun devicesChanged(snapshot: DeviceSnapshot) {}
        fun connectionChanged(status: ConnectionStatus) {}
        fun entriesChanged(changes: StoreChanges) {}
    }

    val store = SessionStore(maxEntries = { InspectorSettings.getInstance().state.maxEntries })

    var snapshot: DeviceSnapshot = DeviceSnapshot(null, emptyList())
        private set
    var status: ConnectionStatus = ConnectionStatus(null, ConnectionState.CLOSED, null)
        private set
    var currentKey: SessionKey? = null
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val incoming = ConcurrentLinkedQueue<Pair<SessionKey, Envelope>>()
    private var connection: InspectorConnection? = null
    private val monitor = DeviceMonitor(project) { snap ->
        ApplicationManager.getApplication().invokeLater({ updateSnapshot(snap) }, project.disposed)
    }
    private val drainTimer = Timer(150) { drain() }

    init {
        drainTimer.start()
        monitor.start()
    }

    fun addListener(listener: Listener, parent: Disposable) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    fun setPollingFast(fast: Boolean) {
        monitor.fast = fast
    }

    fun refreshDevices() = monitor.refreshNow()

    /** Switches the inspected app. Data captured for the previous app is kept. */
    fun select(key: SessionKey?) {
        if (key == currentKey) return
        connection?.close()
        connection = null
        currentKey = key
        if (key != null) {
            InspectorSettings.getInstance().state.apply {
                lastSerial = key.serial
                lastProcess = key.processName
            }
        }
        updateStatus(ConnectionStatus(key, if (key == null) ConnectionState.CLOSED else ConnectionState.CONNECTING, null))
        val adbFile = snapshot.adb ?: return
        if (key == null) return
        val conn = InspectorConnection(
            adb = AdbClient(adbFile),
            serial = key.serial,
            socketName = SOCKET_PREFIX + key.processName,
            onEvent = { incoming.add(key to it) },
            onState = { state, message ->
                ApplicationManager.getApplication().invokeLater({
                    if (currentKey == key && (connection == null || state != ConnectionState.CLOSED)) {
                        updateStatus(ConnectionStatus(key, state, message))
                    }
                }, project.disposed)
            },
        )
        connection = conn
        conn.start()
    }

    /** Reconnects to the current app (e.g. after adb became available). */
    fun reconnect() {
        val key = currentKey ?: return
        currentKey = null
        select(key)
    }

    fun clear(key: SessionKey) {
        val changes = StoreChanges()
        store.clear(key, changes)
        listeners.forEach { it.entriesChanged(changes) }
    }

    private fun updateSnapshot(snap: DeviceSnapshot) {
        val adbAppeared = snapshot.adb == null && snap.adb != null
        snapshot = snap
        listeners.forEach { it.devicesChanged(snap) }
        if (adbAppeared && currentKey != null && connection == null) reconnect()
    }

    private fun updateStatus(s: ConnectionStatus) {
        status = s
        listeners.forEach { it.connectionChanged(s) }
    }

    private fun drain() {
        if (incoming.isEmpty()) return
        val changes = StoreChanges()
        var n = 0
        while (n < 5000) {
            val (key, envelope) = incoming.poll() ?: break
            store.apply(key, envelope, changes)
            n++
        }
        if (!changes.isEmpty) listeners.forEach { it.entriesChanged(changes) }
    }

    override fun dispose() {
        drainTimer.stop()
        monitor.stop()
        connection?.close()
        connection = null
    }
}
