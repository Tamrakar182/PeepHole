package np.com.manjultamrakar.peephole.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** No-op variant for release builds: delegates directly. */
class PeepholeWebSocketFactory(private val delegate: WebSocket.Factory) : WebSocket.Factory {
    constructor(client: OkHttpClient) : this(client as WebSocket.Factory)

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        delegate.newWebSocket(request, listener)
}
