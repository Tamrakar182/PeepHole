package np.com.manjultamrakar.peephole.ide

import np.com.manjultamrakar.peephole.ide.model.HttpEntry
import np.com.manjultamrakar.peephole.ide.model.SessionKey
import np.com.manjultamrakar.peephole.ide.model.SessionStore
import np.com.manjultamrakar.peephole.ide.model.StoreChanges
import np.com.manjultamrakar.peephole.ide.model.WsEntry
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.FrameType
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionStoreTest {
    private val key = SessionKey("emulator-5554", "com.example")

    private fun hello(pid: Int) = Envelope(0, InspectorEvent.Hello(0, 1, "com.example", "com.example", null, pid, "0.1.0"))
    private fun req(seq: Long, id: String) = Envelope(seq, InspectorEvent.HttpRequest(id, seq, "GET", "https://a.dev/x?y=1", emptyList(), null, null, ClientLibrary.OKHTTP))
    private fun res(seq: Long, id: String) = Envelope(seq, InspectorEvent.HttpResponse(id, seq, 200, "OK", "h2", emptyList(), null, 5))

    @Test
    fun mergesAndDeduplicatesReplays() {
        val store = SessionStore()
        val changes = StoreChanges()
        listOf(hello(10), req(1, "a-1"), res(2, "a-1")).forEach { store.apply(key, it, changes) }
        // Reconnect to the same process replays the buffer.
        listOf(hello(10), req(1, "a-1"), res(2, "a-1"), req(3, "a-2")).forEach { store.apply(key, it, changes) }
        val entries = store.session(key).entries
        assertEquals(listOf("a-1", "a-2"), entries.map { it.id })
        val first = entries[0] as HttpEntry
        assertEquals(200, first.response?.code)
        assertEquals("a.dev", first.host)
        assertEquals("/x?y=1", first.path)
    }

    @Test
    fun newProcessRestartsSequence() {
        val store = SessionStore()
        val c = StoreChanges()
        listOf(hello(10), req(1, "a-1"), hello(11), req(1, "b-1")).forEach { store.apply(key, it, c) }
        assertEquals(listOf("a-1", "b-1"), store.session(key).entries.map { it.id })
    }

    @Test
    fun pausedIgnoresNewCallsButCompletesExisting() {
        val store = SessionStore()
        val c = StoreChanges()
        store.apply(key, req(1, "a-1"), c)
        store.recording = false
        store.apply(key, req(2, "a-2"), c)
        store.apply(key, res(3, "a-1"), c)
        val entries = store.session(key).entries
        assertEquals(1, entries.size)
        assertNotNull((entries[0] as HttpEntry).response)
    }

    @Test
    fun trimsOldEntries() {
        val store = SessionStore(maxEntries = { 10 })
        val c = StoreChanges()
        for (i in 1..12L) store.apply(key, req(i, "a-$i"), c)
        assertEquals(10, store.session(key).entries.size)
        assertEquals("a-3", store.session(key).entries.first().id)
    }

    @Test
    fun webSocketFramesAttachToConnection() {
        val store = SessionStore()
        val c = StoreChanges()
        store.apply(key, Envelope(1, InspectorEvent.WsConnect("w-1", 1, "wss://x", emptyList(), ClientLibrary.KTOR)), c)
        store.apply(key, Envelope(2, InspectorEvent.WsFrame("w-1", 2, Direction.SENT, FrameType.TEXT, Body(text = "hi"))), c)
        store.apply(key, Envelope(3, InspectorEvent.WsFrame("w-1", 3, Direction.RECEIVED, FrameType.TEXT, Body(text = "yo"))), c)
        val ws = store.session(key).entries.single() as WsEntry
        assertEquals(1, ws.sentCount)
        assertEquals(1, ws.receivedCount)
        assertEquals(2, ws.messages.size)
    }
}
