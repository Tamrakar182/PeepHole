package np.com.manjultamrakar.peephole.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import np.com.manjultamrakar.peephole.Peephole
import np.com.manjultamrakar.peephole.ktor.PeepholePlugin
import np.com.manjultamrakar.peephole.ktor.inspectedWebSocket
import np.com.manjultamrakar.peephole.okhttp.PeepholeInterceptor
import np.com.manjultamrakar.peephole.okhttp.PeepholeWebSocketFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: TextView

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(PeepholeInterceptor())
        .build()

    private val ktor = HttpClient(CIO) {
        install(WebSockets)
        install(PeepholePlugin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Logcat output is off by default. Opt in with:
        //   Peephole.init(this, PeepholeConfig(logToLogcat = true))
        // (or launch with: adb shell am start -n np.com.manjultamrakar.peephole.sample/.MainActivity --ez logcat true)
        if (intent.getBooleanExtra("logcat", false)) {
            Peephole.init(this, Peephole.config.copy(logToLogcat = true))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val actions = mutableListOf<Pair<String, suspend () -> Unit>>()
        fun button(label: String, action: suspend () -> Unit) {
            actions += label to action
            column.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    scope.launch {
                        runCatching { action() }.onFailure { append("$label failed: $it") }
                    }
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        button("OkHttp · REST GET") { okGet("https://jsonplaceholder.typicode.com/posts/1") }
        button("OkHttp · REST POST") { okPost("https://jsonplaceholder.typicode.com/posts", """{"title":"Hello","body":"From the sample","userId":1}""") }
        button("OkHttp · GraphQL query") {
            okPost(GRAPHQL_URL, """{"operationName":"Countries","query":"query Countries(${'$'}code: String!) { countries(filter: {continent: {eq: ${'$'}code}}) { code name emoji capital } }","variables":{"code":"EU"}}""")
        }
        button("OkHttp · GraphQL error") {
            okPost(GRAPHQL_URL, """{"operationName":"Broken","query":"query Broken { doesNotExist }"}""")
        }
        button("OkHttp · WebSocket echo") { okWebSocket() }
        button("OkHttp · failing request") { okGet("https://does-not-exist.invalid/") }
        button("Ktor · REST GET") { append("Ktor GET → " + ktor.get("https://httpbin.org/json").bodyAsText().take(60)) }
        button("Ktor · GraphQL query") {
            val text = ktor.post(GRAPHQL_URL) {
                contentType(ContentType.Application.Json)
                header("X-Client", "ktor-sample")
                setBody("""{"query":"query Continents { continents { code name } }"}""")
            }.bodyAsText()
            append("Ktor GraphQL → ${text.take(60)}")
        }
        button("Ktor · GraphQL-over-WebSocket (echo)") { ktorGraphQLWebSocket() }
        button("Ktor · 404") { append("Ktor 404 → " + ktor.get("https://httpbin.org/status/404").status) }

        log = TextView(this).apply { setPadding(0, 24, 0, 0) }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column, MATCH_PARENT, WRAP_CONTENT) })

        // adb shell am start -n np.com.manjultamrakar.peephole.sample/.MainActivity --ez autorun true
        if (intent.getBooleanExtra("autorun", false)) {
            scope.launch {
                for ((label, action) in actions) {
                    runCatching { action() }.onFailure { append("$label failed: $it") }
                    delay(300)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        ktor.close()
        super.onDestroy()
    }

    private fun okGet(url: String) {
        okHttp.newCall(Request.Builder().url(url).build()).execute().use { append("GET ${it.code} ← $url") }
    }

    private fun okPost(url: String, json: String) {
        val request = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())).build()
        okHttp.newCall(request).execute().use { append("POST ${it.code} ← $url") }
    }

    private fun okWebSocket() {
        val request = Request.Builder().url(ECHO_URL).build()
        PeepholeWebSocketFactory(okHttp).newWebSocket(request, object : WebSocketListener() {
            private var count = 0
            override fun onOpen(webSocket: WebSocket, response: Response) {
                append("WS open (${response.code})")
                webSocket.send("""{"hello":"inspector","n":1}""")
                webSocket.send("plain text message")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                append("WS ↓ ${text.take(60)}")
                if (++count >= 3) webSocket.close(1000, "sample done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = append("WS failed: $t")
        })
    }

    private suspend fun ktorGraphQLWebSocket() {
        ktor.inspectedWebSocket(ECHO_URL) {
            send("""{"type":"connection_init","payload":{}}""")
            send("""{"id":"1","type":"subscribe","payload":{"operationName":"OnPrice","query":"subscription OnPrice { price(symbol: \"ACME\") { value } }"}}""")
            send("""{"id":"1","type":"next","payload":{"data":{"price":{"value":42.0}}}}""")
            send("""{"id":"1","type":"complete"}""")
            withTimeoutOrNull(5_000) {
                repeat(5) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) append("Ktor WS ↓ ${frame.readText().take(60)}")
                }
            }
            delay(200)
            close()
        }
    }

    private fun append(line: String) = runOnUiThread {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        log.text = "$time  $line\n${log.text}"
    }

    private companion object {
        const val GRAPHQL_URL = "https://countries.trevorblades.com/graphql"
        const val ECHO_URL = "wss://echo.websocket.org"
    }
}
