package com.ailm.android.runtime.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class TeraBoxShareFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val downloadUrl: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "path" to path,
        "size_bytes" to sizeBytes,
        "download_url_available" to downloadUrl.isNotBlank(),
    )
}

internal data class TeraBoxShareListing(
    val shareUrl: String,
    val files: List<TeraBoxShareFile>,
)

internal class TeraBoxDownloadHandle(
    val file: TeraBoxShareFile,
    val mimeType: String?,
    val contentLength: Long,
    val input: InputStream,
    private val connection: HttpURLConnection,
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        connection.disconnect()
    }
}

/**
 * Public-share bridge for TeraBox.
 *
 * TeraBox's Android DocumentsProvider can expose a provider root while returning
 * no children for remote-only files. A client app cannot repair another app's
 * DocumentsProvider. This bridge bypasses SAF by using a user-created TeraBox
 * share link, enumerating the share, and streaming the selected file directly
 * into AsterionCore's private model install directory.
 */
internal class TeraBoxShareClient {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var referer = "https://www.terabox.com/"

    fun listShare(shareUrl: String, password: String = ""): TeraBoxShareListing {
        val normalized = normalizeShareUrl(shareUrl)
        val bootstrap = requestText(normalized)
        referer = bootstrap.finalUrl
        val keys = shareKeyCandidates(bootstrap.finalUrl.ifBlank { normalized })
            .ifEmpty { shareKeyCandidates(normalized) }
        require(keys.isNotEmpty()) { "Unable to recognize the TeraBox share link." }

        val context = resolveWorkingContext(keys, extractJsToken(bootstrap.body), password)
        val files = collectFiles(context, root = true, directory = "", depth = 0)
            .distinctBy { it.path.ifBlank { it.name } }
            .sortedBy { it.name.lowercase(Locale.US) }

        require(files.isNotEmpty()) {
            "The TeraBox share contains no accessible files. Confirm that the link is active and, if required, provide its extraction code."
        }
        return TeraBoxShareListing(normalized, files)
    }

    fun openFile(
        shareUrl: String,
        targetPath: String,
        password: String = "",
    ): TeraBoxDownloadHandle {
        val normalized = normalizeShareUrl(shareUrl)
        val bootstrap = requestText(normalized)
        referer = bootstrap.finalUrl
        val keys = shareKeyCandidates(bootstrap.finalUrl.ifBlank { normalized })
            .ifEmpty { shareKeyCandidates(normalized) }
        require(keys.isNotEmpty()) { "Unable to recognize the TeraBox share link." }

        val context = resolveWorkingContext(keys, extractJsToken(bootstrap.body), password)
        val files = collectFiles(context, root = true, directory = "", depth = 0)
        val normalizedTarget = targetPath.trim()
        val file = files.firstOrNull { it.path == normalizedTarget }
            ?: files.firstOrNull { it.name == normalizedTarget }
            ?: throw IllegalArgumentException("Selected TeraBox file is no longer available in the share.")
        require(file.downloadUrl.isNotBlank()) { "TeraBox did not provide a download URL for the selected file." }

        val connection = openFollowingRedirects(file.downloadUrl, refererOverride = context.referer)
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("TeraBox download failed with HTTP $code.")
        }
        val stream = BufferedInputStream(connection.inputStream, NETWORK_BUFFER_SIZE)
        return TeraBoxDownloadHandle(
            file = file,
            mimeType = connection.contentType,
            contentLength = connection.contentLengthLong.coerceAtLeast(file.sizeBytes),
            input = stream,
            connection = connection,
        )
    }

    private data class ShareContext(
        val host: String,
        val shareKey: String,
        val jsToken: String,
        val referer: String,
    )

    private data class TextResponse(
        val finalUrl: String,
        val body: String,
    )

    private fun resolveWorkingContext(
        keys: List<String>,
        jsToken: String,
        password: String,
    ): ShareContext {
        val hosts = linkedSetOf(
            runCatching { URL(referer).host }.getOrNull().orEmpty(),
            "www.terabox.com",
            "www.terabox.app",
            "www.1024tera.com",
        ).filter(String::isNotBlank)

        var lastMessage = "TeraBox did not return a readable share listing."
        hosts.forEach { host ->
            keys.forEach { key ->
                val candidate = ShareContext(
                    host = host,
                    shareKey = key,
                    jsToken = jsToken,
                    referer = referer,
                )
                if (password.isNotBlank()) {
                    runCatching { verifyPassword(candidate, password) }
                        .onFailure { lastMessage = it.message ?: lastMessage }
                }
                val root = runCatching { requestListing(candidate, root = true, directory = "", page = 1) }
                    .getOrElse {
                        lastMessage = it.message ?: lastMessage
                        null
                    }
                if (root != null && root.optInt("errno", 0) == 0 && root.optJSONArray("list") != null) {
                    return candidate
                }
                if (root != null) {
                    val errno = root.optInt("errno", Int.MIN_VALUE)
                    val show = root.optString("show_msg").ifBlank { root.optString("errmsg") }
                    if (errno == -9 || errno == -3) {
                        lastMessage = if (password.isBlank()) {
                            "This TeraBox share requires its extraction code."
                        } else {
                            "TeraBox rejected the supplied extraction code."
                        }
                    } else if (show.isNotBlank()) {
                        lastMessage = show
                    }
                }
            }
        }
        throw IOException(lastMessage)
    }

    private fun verifyPassword(context: ShareContext, password: String) {
        require(password.matches(Regex("[0-9a-zA-Z]{4}"))) {
            "TeraBox extraction code must contain exactly four letters or digits."
        }
        val endpoint = buildString {
            append("https://")
            append(context.host)
            append("/share/verify?surl=")
            append(urlEncode(context.shareKey))
            append("&t=")
            append(System.currentTimeMillis())
            append("&channel=chunlei&web=1&app_id=250528&clienttype=0")
        }
        val body = "pwd=" + urlEncode(password.lowercase(Locale.US)) + "&vcode=&vcode_str="
        val response = requestText(
            endpoint,
            method = "POST",
            requestBody = body,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            refererOverride = context.referer,
        )
        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: throw IOException("TeraBox extraction-code verification returned an invalid response.")
        if (json.optInt("errno", -1) != 0) {
            throw IOException(
                json.optString("show_msg").ifBlank { "TeraBox rejected the extraction code." },
            )
        }
    }

    private fun collectFiles(
        context: ShareContext,
        root: Boolean,
        directory: String,
        depth: Int,
    ): List<TeraBoxShareFile> {
        require(depth <= MAX_DIRECTORY_DEPTH) { "TeraBox share folder nesting is too deep." }
        val result = mutableListOf<TeraBoxShareFile>()
        var page = 1
        while (true) {
            val json = requestListing(context, root, directory, page)
            val errno = json.optInt("errno", 0)
            if (errno != 0) {
                throw IOException(
                    json.optString("show_msg")
                        .ifBlank { json.optString("errmsg") }
                        .ifBlank { "TeraBox share listing failed with errno $errno." },
                )
            }
            val items = json.optJSONArray("list") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("server_filename")
                    .ifBlank { item.optString("filename") }
                    .ifBlank { item.optString("path").substringAfterLast('/') }
                val path = item.optString("path").ifBlank { name }
                val isDirectory = when (val value = item.opt("isdir")) {
                    is Number -> value.toInt() != 0
                    is String -> value == "1" || value.equals("true", ignoreCase = true)
                    else -> false
                }
                if (isDirectory) {
                    result += collectFiles(context, root = false, directory = path, depth = depth + 1)
                } else {
                    result += TeraBoxShareFile(
                        name = name,
                        path = path,
                        sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L),
                        downloadUrl = item.optString("dlink"),
                    )
                    require(result.size <= MAX_SHARE_FILES) {
                        "TeraBox share contains too many files to import safely."
                    }
                }
            }

            val hasMore = when (val value = json.opt("has_more")) {
                is Number -> value.toInt() != 0
                is String -> value == "1" || value.equals("true", ignoreCase = true)
                else -> false
            }
            if (!hasMore || items.length() == 0) break
            page += 1
            require(page <= MAX_PAGES) { "TeraBox share pagination exceeded the safe limit." }
        }
        return result
    }

    private fun requestListing(
        context: ShareContext,
        root: Boolean,
        directory: String,
        page: Int,
    ): JSONObject {
        val endpoint = buildString {
            append("https://")
            append(context.host)
            append("/share/list?app_id=250528&web=1&channel=0")
            append("&shorturl=")
            append(urlEncode(context.shareKey))
            append("&page=")
            append(page)
            append("&num=100&by=name&order=asc")
            if (context.jsToken.isNotBlank()) {
                append("&jsToken=")
                append(urlEncode(context.jsToken))
            }
            if (root) {
                append("&root=1")
            } else {
                append("&dir=")
                append(urlEncode(directory))
            }
        }
        val response = requestText(endpoint, refererOverride = context.referer)
        return runCatching { JSONObject(response.body) }.getOrElse {
            throw IOException("TeraBox returned a non-JSON share listing.")
        }
    }

    private fun requestText(
        url: String,
        method: String = "GET",
        requestBody: String = "",
        contentType: String? = null,
        refererOverride: String? = null,
    ): TextResponse {
        val connection = openFollowingRedirects(
            url = url,
            method = method,
            requestBody = requestBody,
            contentType = contentType,
            refererOverride = refererOverride,
        )
        return try {
            val code = connection.responseCode
            val source = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = source?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..399) {
                throw IOException("TeraBox request failed with HTTP $code.")
            }
            TextResponse(connection.url.toString(), body)
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(
        url: String,
        method: String = "GET",
        requestBody: String = "",
        contentType: String? = null,
        refererOverride: String? = null,
    ): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json,text/plain,text/html,*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", refererOverride ?: referer)
                val cookieHeaders = cookies.get(current.toURI(), emptyMap())
                cookieHeaders.forEach { (key, values) ->
                    if (key.equals("Cookie", ignoreCase = true) && values.isNotEmpty()) {
                        setRequestProperty("Cookie", values.joinToString("; "))
                    }
                }
                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                if (method == "POST") {
                    doOutput = true
                    outputStream.use { output ->
                        output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }
            val code = connection.responseCode
            cookies.put(current.toURI(), connection.headerFields)
            if (code !in REDIRECT_CODES) {
                return connection
            }
            val location = connection.getHeaderField("Location")
                ?: return connection
            val next = URL(current, location)
            connection.disconnect()
            current = next
            redirects += 1
            require(redirects <= MAX_REDIRECTS) { "TeraBox redirected too many times." }
        }
    }

    internal fun shareKeyCandidates(url: String): List<String> {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val uri = runCatching { URI(decoded) }.getOrNull()
        val results = linkedSetOf<String>()

        val query = uri?.rawQuery.orEmpty()
        query.split('&').forEach { item ->
            val key = item.substringBefore('=')
            if (key == "surl" || key == "shorturl") {
                val value = URLDecoder.decode(item.substringAfter('=', ""), "UTF-8").trim()
                if (value.isNotBlank()) {
                    results += value
                    if (value.startsWith("1") && value.length > 1) results += value.drop(1)
                }
            }
        }

        val path = uri?.path.orEmpty()
        Regex("/s/([^/?#]+)").find(path)?.groupValues?.getOrNull(1)?.let { segment ->
            val value = segment.trim()
            if (value.isNotBlank()) {
                results += value
                if (value.startsWith("1") && value.length > 1) results += value.drop(1)
            }
        }
        return results.filter(String::isNotBlank)
    }

    internal fun extractJsToken(html: String): String {
        val patterns = listOf(
            Regex("""window\.jsToken\s*=\s*["']([^"']+)["']"""),
            Regex("""["']jsToken["']\s*:\s*["']([^"']+)["']"""),
            Regex("""window\.jsToken.*?%22([^%"]+)%22"""),
            Regex("""fn%28%22([^%]+)%22%29"""),
        )
        patterns.forEach { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)?.let { value ->
                return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
        }
        return ""
    }

    private fun normalizeShareUrl(raw: String): String {
        val candidate = raw.trim()
        require(candidate.isNotBlank()) { "TeraBox share link is required." }
        val url = runCatching { URL(candidate) }.getOrElse {
            throw IllegalArgumentException("Invalid TeraBox share link.")
        }
        require(url.protocol.equals("https", ignoreCase = true)) {
            "TeraBox share link must use HTTPS."
        }
        val host = url.host.lowercase(Locale.US)
        require(
            host == "terabox.com" ||
                host.endsWith(".terabox.com") ||
                host == "terabox.app" ||
                host.endsWith(".terabox.app") ||
                host == "1024tera.com" ||
                host.endsWith(".1024tera.com"),
        ) { "Only TeraBox share links are accepted by this importer." }
        return url.toString()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36"
        private const val NETWORK_BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_REDIRECTS = 10
        private const val MAX_DIRECTORY_DEPTH = 12
        private const val MAX_SHARE_FILES = 5_000
        private const val MAX_PAGES = 100
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
