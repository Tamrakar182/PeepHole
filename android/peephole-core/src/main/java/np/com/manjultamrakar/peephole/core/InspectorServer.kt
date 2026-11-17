package np.com.manjultamrakar.peephole.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.protocol.Envelope
import np.com.manjultamrakar.peephole.protocol.EventCodec
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Listens on an abstract unix socket and streams newline-delimited JSON events to each connected IDE.
 * Only adb shell (uid 2000), root, and this app's own uid may connect.
 */
internal class InspectorServer(private val socketName: String) {

    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ acceptLoop() }, "Peephole-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        thread?.interrupt()
    }

    private fun acceptLoop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val ss = LocalServerSocket(socketName)
                serverSocket = ss
                backoffMs = 1000L
                while (running) {
                    val socket = ss.accept()
                    if (!isAllowed(socket)) {
                        socket.close()
                        continue
                    }
                    ClientConnection(socket).start()
                }
            } catch (e: IOException) {
                if (!running) return
                Log.w(TAG, "Inspector socket '$socketName' unavailable: ${e.message}. Retrying.")
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return
                }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun isAllowed(socket: LocalSocket): Boolean = try {
        val uid = socket.peerCredentials.uid
        uid == 0 || uid == SHELL_UID || uid == Process.myUid()
    } catch (_: IOException) {
        false
    }

    private class ClientConnection(private val socket: LocalSocket) : Peephole.Sink {
        private val queue = LinkedBlockingQueue<Envelope>(QUEUE_CAPACITY)

        @Volatile
        private var open = true

        fun start() {
            Thread({ writeLoop() }, "Peephole-client").apply {
                isDaemon = true
                start()
            }
        }

        override fun send(envelope: Envelope) {
            // Never block the app's network threads; drop events for a stalled client.
            if (open) queue.offer(envelope)
        }

        private fun writeLoop() {
            try {
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), 64 * 1024)
                Peephole.hello()?.let { hello ->
                    writer.write(EventCodec.encode(Envelope(0, hello.copy(timestamp = System.currentTimeMillis()))))
                    writer.write("\n")
                    writer.flush()
                }
                val replay = Peephole.attach(this)
                for (envelope in replay) {
                    writer.write(EventCodec.encode(envelope))
                    writer.write("\n")
                }
                writer.flush()
                while (open) {
                    val next = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
                    if (next == null) {
                        // Blank line = heartbeat; lets both ends notice dead connections.
                        writer.write("\n")
                        writer.flush()
                        continue
                    }
                    writer.write(EventCodec.encode(next))
                    writer.write("\n")
                    if (queue.isEmpty()) writer.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                open = false
                Peephole.detach(this)
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "Peephole"
        private const val SHELL_UID = 2000
        private const val QUEUE_CAPACITY = 10_000
        private const val HEARTBEAT_SECONDS = 5L
    }
}
