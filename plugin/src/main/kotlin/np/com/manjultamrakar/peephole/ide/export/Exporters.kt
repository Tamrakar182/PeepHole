package np.com.manjultamrakar.peephole.ide.export

import np.com.manjultamrakar.peephole.ide.model.HttpEntry
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.Header
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import np.com.manjultamrakar.peephole.protocol.Json
import java.net.URI
import java.time.Instant

object CurlExporter {
    fun toCurl(request: InspectorEvent.HttpRequest): String {
        val parts = mutableListOf("curl")
        if (request.method != "GET" || request.body != null) parts += "-X ${request.method}"
        parts += quote(request.url)
        var compressed = false
        for (h in request.headers) {
            if (h.name.equals("Content-Length", true) || h.name.equals("Host", true)) continue
            if (h.name.equals("Accept-Encoding", true) && h.value.contains("gzip")) {
                compressed = true
                continue
            }
            parts += "-H ${quote("${h.name}: ${h.value}")}"
        }
        request.body?.text?.let { parts += "--data-raw ${quote(it)}" }
        if (compressed) parts += "--compressed"
        return parts.joinToString(" \\\n  ")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** Plain-text HTTP message: start line, headers, blank line, body (as captured, not reformatted). */
object HttpTextExporter {
    fun request(req: InspectorEvent.HttpRequest): String =
        message("${req.method} ${req.url}", req.headers, req.body)

    fun response(res: InspectorEvent.HttpResponse): String {
        val proto = res.protocol?.uppercase()?.takeIf { it.isNotBlank() } ?: "HTTP/1.1"
        return message("$proto ${res.code} ${res.message}".trim(), res.headers, res.body)
    }

    private fun message(startLine: String, headers: List<Header>, body: Body?): String = buildString {
        append(startLine).append('\n')
        headers.forEach { append(it.name).append(": ").append(it.value).append('\n') }
        val content = when {
            body == null -> null
            body.omittedReason != null -> "<body omitted: ${body.omittedReason}>"
            body.text != null -> body.text + if (body.truncated) "\n<body truncated>" else ""
            body.base64 != null -> "<binary body, ${body.size} bytes>"
            else -> null
        }
        if (!content.isNullOrEmpty()) append('\n').append(content)
    }.trimEnd() + "\n"
}

/** HTTP Archive 1.2 export; opens in Chrome DevTools, Charles, Proxyman, etc. */
object HarExporter {
    fun toHar(entries: List<HttpEntry>, creatorVersion: String): String {
        val list = entries.mapNotNull { e -> e.request?.let { entry(it, e.response, e.failure) } }
        return Json.stringify(
            mapOf("log" to mapOf("version" to "1.2", "creator" to mapOf("name" to "Peephole", "version" to creatorVersion), "entries" to list)),
            pretty = true,
        )
    }

    private fun entry(req: InspectorEvent.HttpRequest, res: InspectorEvent.HttpResponse?, failure: InspectorEvent.HttpFailure?): Map<String, Any?> {
        val time = res?.durationMs ?: failure?.durationMs ?: 0
        val httpVersion = res?.protocol?.uppercase() ?: "HTTP/1.1"
        val request = linkedMapOf<String, Any?>(
            "method" to req.method,
            "url" to req.url,
            "httpVersion" to httpVersion,
            "cookies" to emptyList<Any>(),
            "headers" to headers(req.headers),
            "queryString" to queryString(req.url),
            "headersSize" to -1,
            "bodySize" to (req.body?.size ?: 0),
        )
        req.body?.let { body ->
            request["postData"] = mapOf("mimeType" to (body.contentType ?: ""), "text" to (body.text ?: body.base64 ?: ""))
        }
        val response = if (res != null) linkedMapOf(
            "status" to res.code,
            "statusText" to res.message,
            "httpVersion" to httpVersion,
            "cookies" to emptyList<Any>(),
            "headers" to headers(res.headers),
            "content" to content(res.body),
            "redirectURL" to (res.headers.firstOrNull { it.name.equals("Location", true) }?.value ?: ""),
            "headersSize" to -1,
            "bodySize" to (res.body?.size ?: -1),
        ) else linkedMapOf(
            "status" to 0,
            "statusText" to (failure?.error ?: "Pending"),
            "httpVersion" to httpVersion,
            "cookies" to emptyList<Any>(),
            "headers" to emptyList<Any>(),
            "content" to mapOf("size" to 0, "mimeType" to ""),
            "redirectURL" to "",
            "headersSize" to -1,
            "bodySize" to -1,
            "_error" to failure?.error,
        )
        return linkedMapOf(
            "startedDateTime" to Instant.ofEpochMilli(req.timestamp).toString(),
            "time" to time,
            "request" to request,
            "response" to response,
            "cache" to emptyMap<String, Any>(),
            "timings" to mapOf("send" to 0, "wait" to time, "receive" to 0),
        )
    }

    private fun content(body: Body?): Map<String, Any?> {
        if (body == null) return mapOf("size" to 0, "mimeType" to "")
        val m = linkedMapOf<String, Any?>("size" to body.size, "mimeType" to (body.contentType ?: ""))
        when {
            body.text != null -> m["text"] = body.text
            body.base64 != null -> {
                m["text"] = body.base64
                m["encoding"] = "base64"
            }
        }
        if (body.truncated) m["comment"] = "Body truncated by Peephole"
        return m
    }

    private fun headers(list: List<Header>) = list.map { mapOf("name" to it.name, "value" to it.value) }

    private fun queryString(url: String): List<Map<String, String>> = try {
        URI(url).rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map {
            mapOf("name" to decode(it.substringBefore('=')), "value" to decode(it.substringAfter('=', "")))
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun decode(s: String) = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }
}
