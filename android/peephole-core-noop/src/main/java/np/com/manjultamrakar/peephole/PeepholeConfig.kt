package np.com.manjultamrakar.peephole

/**
 * Runtime configuration for the inspector. Change it with [Peephole.config] or [Peephole.init].
 */
data class PeepholeConfig @JvmOverloads constructor(
    /** Master switch. When false nothing is captured. */
    val enabled: Boolean = true,
    /**
     * Also print a one-line summary of every call to logcat. Off by default: traffic is streamed to the
     * IDE over a dedicated socket and never touches logcat unless you turn this on.
     */
    val logToLogcat: Boolean = false,
    val logcatTag: String = "Peephole",
    /** Max bytes of each request/response body that are captured. */
    val maxBodyBytes: Long = 256L * 1024,
    /** Max bytes of each WebSocket message that are captured. */
    val maxFrameBytes: Long = 64L * 1024,
    /** Header names (case-insensitive) whose values are replaced with `██`. */
    val redactHeaders: Set<String> = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie"),
    /** Number of recent events kept in memory and replayed when the IDE connects. */
    val bufferSize: Int = 1000,
)
