# Peephole

Network inspector for Android Studio: OkHttp and Ktor, REST, GraphQL and WebSocket.

Inspect REST, GraphQL and WebSocket traffic from OkHttp (Retrofit, Apollo) and Ktor apps in a
**Peephole** tool window. Traffic goes over an adb-forwarded socket, never through logcat.

## Install the IDE plugin
```
./gradlew :plugin:buildPlugin
```
Then go to Settings → Plugins → ⚙ → **Install Plugin from Disk…** and pick `plugin/build/distributions/peephole-0.1.0.zip`.
Supports Android Studio 2025.3+. To try it in a sandbox IDE instead, run `./gradlew :plugin:runIde`.

## Add the library to your app
Publish the libraries locally with `./gradlew publishToMavenLocal`, then:
```kotlin
repositories { mavenLocal() }
dependencies {
    debugImplementation("np.com.manjultamrakar:peephole-okhttp:0.1.0")
    releaseImplementation("np.com.manjultamrakar:peephole-okhttp-noop:0.1.0")
    // Ktor:
    debugImplementation("np.com.manjultamrakar:peephole-ktor:0.1.0")
    releaseImplementation("np.com.manjultamrakar:peephole-ktor-noop:0.1.0")
}
```

**OkHttp / Retrofit / Apollo**
```kotlin
val client = OkHttpClient.Builder().addInterceptor(PeepholeInterceptor()).build()
// WebSockets:
PeepholeWebSocketFactory(client).newWebSocket(request, listener)
// Apollo Kotlin:
ApolloClient.Builder().okHttpClient(client)
    .webSocketEngine(DefaultWebSocketEngine(PeepholeWebSocketFactory(client)))
```

**Ktor**
```kotlin
val client = HttpClient(CIO) { install(WebSockets); install(PeepholePlugin) }
client.inspectedWebSocket("wss://…") { send("hi") }   // or: client.webSocketSession(url).inspected()
```

The library starts itself. To change options, call `init` with a config:
```kotlin
Peephole.init(context, PeepholeConfig(
    logToLogcat = false,          // default: logcat stays clean
    maxBodyBytes = 256 * 1024,
    redactHeaders = setOf("Authorization", "Cookie"),
))
```
If you use the Ktor OkHttp engine, add only the Ktor plugin. Adding `PeepholeInterceptor` to that engine as well records every call twice.

## Using the tool window
Choose a **Device** and an **App** at the top. Every connected device and every running process with
the library is listed. Available: filters (REST / GraphQL / WebSocket, status, text), pause, clear,
Copy as cURL, Copy Request / Copy Response (start line, headers and body), and Export HAR.

## Sample
`sample/` exercises every feature. To run all demos automatically:
`adb shell am start -n np.com.manjultamrakar.peephole.sample/.MainActivity --ez autorun true`

## License
MIT. See [LICENSE](LICENSE).
