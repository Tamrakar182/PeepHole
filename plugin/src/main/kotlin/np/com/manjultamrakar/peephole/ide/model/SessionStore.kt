package np.com.manjultamrakar.peephole.ide.model

import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.InspectorEvent

/** Identifies one inspected app process on one device. */
data class SessionKey(val serial: String, val processName: String)

class Session(val key: SessionKey) {
    val entries = ArrayList<NetworkEntry>()
    internal val byId = HashMap<String, NetworkEntry>()
    var hello: InspectorEvent.Hello? = null
        internal set
    internal var lastSeq = 0L
}

/** Changes made by one [SessionStore.apply] batch, used to refresh the UI incrementally. */
class StoreChanges {
    val appended = HashMap<SessionKey, Int>()
    val updated = HashMap<SessionKey, MutableSet<String>>()
    val reset = HashSet<SessionKey>()
    val helloChanged = HashSet<SessionKey>()
    val isEmpty: Boolean get() = appended.isEmpty() && updated.isEmpty() && reset.isEmpty() && helloChanged.isEmpty()
}

/**
 * Merges the event stream into per-session entries. Confined to the EDT (or a single thread in tests).
 */
class SessionStore(
    private val maxEntries: () -> Int = { 2000 },
    private val maxMessagesPerSocket: Int = 5000,
) {
    private val sessions = HashMap<SessionKey, Session>()

    /** When false, new calls are ignored (existing ones still receive their responses). */
    var recording: Boolean = true

    fun session(key: SessionKey): Session = sessions.getOrPut(key) { Session(key) }

    fun clear(key: SessionKey, changes: StoreChanges? = null) {
        val s = sessions[key] ?: return
        s.entries.clear()
        s.byId.clear()
        changes?.reset?.add(key)
    }

    fun apply(key: SessionKey, envelope: Envelope, changes: StoreChanges) {
        val session = session(key)
        val event = envelope.event
        if (event is InspectorEvent.Hello) {
            if (session.hello?.pid != event.pid) session.lastSeq = 0 // new process: sequence restarts
            session.hello = event
            changes.helloChanged.add(key)
            return
        }
        // Replays after a reconnect re-send events we already have.
        if (envelope.seq in 1..session.lastSeq) return
        if (envelope.seq > 0) session.lastSeq = envelope.seq

        when (event) {
            is InspectorEvent.HttpRequest -> {
                val existing = session.byId[event.id] as? HttpEntry
                if (existing != null) {
                    existing.request = event
                    updated(existing, session, changes)
                } else if (recording) {
                    add(session, HttpEntry(event.id, event), changes)
                }
            }
            is InspectorEvent.HttpResponse -> http(session, event.id, changes) { it.response = event }
            is InspectorEvent.HttpFailure -> http(session, event.id, changes) { it.failure = event }
            is InspectorEvent.WsConnect -> ws(session, event.id, event.timestamp, changes) { it.connect = event }
            is InspectorEvent.WsOpen -> ws(session, event.id, event.timestamp, changes) { it.open = event }
            is InspectorEvent.WsFrame -> ws(session, event.id, event.timestamp, changes) { it.addMessage(event, maxMessagesPerSocket) }
            is InspectorEvent.WsClosing -> ws(session, event.id, event.timestamp, changes) {
                it.closing = it.closing ?: event
                it.addMessage(event, maxMessagesPerSocket)
            }
            is InspectorEvent.WsClosed -> ws(session, event.id, event.timestamp, changes) {
                it.closed = event
                it.addMessage(event, maxMessagesPerSocket)
            }
            is InspectorEvent.WsFailure -> ws(session, event.id, event.timestamp, changes) {
                it.failure = event
                it.addMessage(event, maxMessagesPerSocket)
            }
            is InspectorEvent.Hello -> Unit
        }
    }

    private inline fun http(session: Session, id: String, changes: StoreChanges, update: (HttpEntry) -> Unit) {
        val entry = session.byId[id] as? HttpEntry ?: return // request was dropped (paused or trimmed)
        update(entry)
        updated(entry, session, changes)
    }

    private inline fun ws(session: Session, id: String, ts: Long, changes: StoreChanges, update: (WsEntry) -> Unit) {
        var entry = session.byId[id] as? WsEntry
        if (entry == null) {
            // Frames of a socket whose connect event fell out of the app's replay buffer still get a row.
            if (!recording) return
            entry = WsEntry(id)
            entry.touch(ts)
            update(entry)
            add(session, entry, changes)
            return
        }
        update(entry)
        updated(entry, session, changes)
    }

    private fun add(session: Session, entry: NetworkEntry, changes: StoreChanges) {
        entry.version++
        session.entries.add(entry)
        session.byId[entry.id] = entry
        val max = maxEntries().coerceAtLeast(10)
        if (session.entries.size > max + max / 10) {
            val removed = session.entries.subList(0, session.entries.size - max)
            removed.forEach { session.byId.remove(it.id) }
            removed.clear()
            changes.reset.add(session.key)
        } else {
            changes.appended.merge(session.key, 1, Int::plus)
        }
    }

    private fun updated(entry: NetworkEntry, session: Session, changes: StoreChanges) {
        entry.version++
        changes.updated.getOrPut(session.key) { HashSet() }.add(entry.id)
    }
}
