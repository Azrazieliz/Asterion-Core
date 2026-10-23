package com.ailm.android.runtime

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class TeraBoxConfig(
    val clientId: String,
    val clientSecret: String,
    val privateSecret: String,
)

internal data class TeraBoxSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val apiDomain: String = "www.terabox.com",
    val userId: String = "",
)

internal class TeraBoxSecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun saveConfig(config: TeraBoxConfig) {
        writeEncrypted(KEY_CLIENT_ID, config.clientId)
        writeEncrypted(KEY_CLIENT_SECRET, config.clientSecret)
        writeEncrypted(KEY_PRIVATE_SECRET, config.privateSecret)
    }

    fun readConfig(): TeraBoxConfig? {
        val clientId = readEncrypted(KEY_CLIENT_ID).orEmpty()
        val clientSecret = readEncrypted(KEY_CLIENT_SECRET).orEmpty()
        val privateSecret = readEncrypted(KEY_PRIVATE_SECRET).orEmpty()
        if (clientId.isBlank() || clientSecret.isBlank() || privateSecret.isBlank()) return null
        return TeraBoxConfig(clientId, clientSecret, privateSecret)
    }

    fun saveSession(session: TeraBoxSession) {
        writeEncrypted(KEY_ACCESS_TOKEN, session.accessToken)
        writeEncrypted(KEY_REFRESH_TOKEN, session.refreshToken)
        prefs.edit()
            .putLong(KEY_EXPIRES_AT, session.expiresAtMs)
            .putString(KEY_API_DOMAIN, session.apiDomain)
            .putString(KEY_USER_ID, session.userId)
            .apply()
    }

    fun readSession(): TeraBoxSession? {
        val access = readEncrypted(KEY_ACCESS_TOKEN).orEmpty()
        val refresh = readEncrypted(KEY_REFRESH_TOKEN).orEmpty()
        if (access.isBlank() && refresh.isBlank()) return null
        return TeraBoxSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            apiDomain = prefs.getString(KEY_API_DOMAIN, "www.terabox.com").orEmpty().ifBlank { "www.terabox.com" },
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
        )
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN + "_iv")
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_REFRESH_TOKEN + "_iv")
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_API_DOMAIN)
            .remove(KEY_USER_ID)
            .apply()
    }

    fun status(): Map<String, Any> {
        val config = readConfig()
        val session = readSession()
        return mapOf(
            "configured" to (config != null),
            "connected" to (session?.accessToken?.isNotBlank() == true),
            "expires_at_ms" to (session?.expiresAtMs ?: 0L),
            "api_domain" to (session?.apiDomain ?: ""),
            "user_id" to (session?.userId ?: ""),
        )
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun writeEncrypted(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).remove(key + "_iv").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(key + "_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun readEncrypted(key: String): String? {
        val encrypted = prefs.getString(key, null) ?: return null
        val iv = prefs.getString(key + "_iv", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "asterion_terabox_secure"
        const val ALIAS = "asterion_terabox_key"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_PRIVATE_SECRET = "private_secret"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_API_DOMAIN = "api_domain"
        const val KEY_USER_ID = "user_id"
    }
}

internal class TeraBoxClient(
    private val store: TeraBoxSecureStore,
) {
    fun authorizationUrl(): String {
        val config = store.readConfig() ?: return ""
        return "https://www.terabox.com/wap/outside/login?clientId=" +
            enc(config.clientId) + "&isFromApp=1"
    }

    fun exchangeAuthorizationCode(code: String): Map<String, Any> {
        val config = store.readConfig()
            ?: return mapOf("ok" to false, "message" to "TeraBox client credentials are not configured.")
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) return mapOf("ok" to false, "message" to "TeraBox authorization code is missing.")

        val timestamp = System.currentTimeMillis() / 1000L
        val response = postForm(
            "https://www.terabox.com/oauth/gettoken",
            mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "grant_type" to "authorization_code",
                "code" to normalizedCode,
                "timestamp" to timestamp.toString(),
                "sign" to signature(config, timestamp),
            ),
        )
        return saveTokenResponse(response)
    }

    fun refreshSessionIfNeeded(force: Boolean = false): TeraBoxSession? {
        val current = store.readSession() ?: return null
        if (!force && current.accessToken.isNotBlank() && current.expiresAtMs > System.currentTimeMillis() + 120_000L) {
            return current
        }
        val config = store.readConfig() ?: return null
        if (current.refreshToken.isBlank()) return null

        val timestamp = System.currentTimeMillis() / 1000L
        val response = postForm(
            "https://www.terabox.com/oauth/refreshtoken",
            mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "refresh_token" to current.refreshToken,
                "timestamp" to timestamp.toString(),
                "sign" to signature(config, timestamp),
            ),
        )
        val result = saveTokenResponse(response)
        return if (result["ok"] == true) store.readSession() else null
    }

    fun connectionStatus(): Map<String, Any> = store.status()

    fun list(path: String, page: Int = 1, num: Int = 1000): List<TeraBoxRemoteNode> {
        val session = refreshSessionIfNeeded() ?: return emptyList()
        val pageSize = num.coerceIn(1, 10_000)
        var currentPage = page.coerceAtLeast(1)
        val results = mutableListOf<TeraBoxRemoteNode>()

        while (true) {
            val response = listPage(session, path, currentPage, pageSize)
            if (response.optInt("errno", -1) != 0) break
            val array = response.optJSONArray("list") ?: response.optJSONArray("info") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val remotePath = item.optString("path").trim()
                if (remotePath.isBlank()) continue
                results += TeraBoxRemoteNode(
                    path = remotePath,
                    name = item.optString("server_filename").ifBlank { remotePath.substringAfterLast('/') },
                    directory = item.optInt("isdir", 0) == 1,
                    size = item.optLong("size", 0L),
                    modifiedAtMs = item.optLong("server_mtime", item.optLong("local_mtime", 0L)) * 1000L,
                    fsId = item.optString("fs_id"),
                )
            }

            val hasMore = response.optInt("has_more", 0) == 1
            if (!hasMore || array.length() == 0) break
            currentPage += 1
            if (currentPage - page > 10_000) break
        }
        return results.distinctBy(TeraBoxRemoteNode::path)
    }

    private fun listPage(session: TeraBoxSession, path: String, page: Int, num: Int): JSONObject {
        val domain = session.apiDomain.ifBlank { "www.terabox.com" }
        val url = "https://$domain/openapi/api/list" +
            "?access_tokens=" + enc(session.accessToken) +
            "&order=name&desc=0&dir=" + enc(normalizePath(path)) +
            "&num=" + num.coerceIn(1, 10_000) +
            "&page=" + page.coerceAtLeast(1)
        return requestJson(url, "GET")
    }

    fun fileManager(operation: String, fileList: JSONArray): Map<String, Any> {
        val session = refreshSessionIfNeeded()
            ?: return mapOf("ok" to false, "message" to "TeraBox is not connected.")
        val domain = session.apiDomain.ifBlank { "www.terabox.com" }
        val url = "https://$domain/openapi/api/filemanager" +
            "?access_tokens=" + enc(session.accessToken) +
            "&opera=" + enc(operation) +
            "&async=0"
        val json = postForm(url, mapOf("filelist" to fileList.toString()))
        val errno = json.optInt("errno", Int.MIN_VALUE)
        return mapOf(
            "ok" to (errno == 0),
            "errno" to errno,
            "message" to json.optString("show_msg").ifBlank { if (errno == 0) "ok" else "TeraBox operation failed." },
            "response" to json.toString(),
        )
    }

    fun rename(path: String, newName: String): Map<String, Any> =
        fileManager(
            "rename",
            JSONArray().put(JSONObject().put("path", normalizePath(path)).put("newname", newName)),
        )

    fun move(path: String, destination: String, newName: String): Map<String, Any> =
        fileManager(
            "move",
            JSONArray().put(
                JSONObject()
                    .put("path", normalizePath(path))
                    .put("dest", normalizePath(destination))
                    .put("newname", newName),
            ),
        )

    fun copy(path: String, destination: String, newName: String): Map<String, Any> =
        fileManager(
            "copy",
            JSONArray().put(
                JSONObject()
                    .put("path", normalizePath(path))
                    .put("dest", normalizePath(destination))
                    .put("newname", newName),
            ),
        )

    fun delete(path: String): Map<String, Any> =
        fileManager("delete", JSONArray().put(normalizePath(path)))

    fun openDownloadStream(path: String): InputStream? {
        val session = refreshSessionIfNeeded() ?: return null
        val normalizedPath = normalizePath(path)
        val parent = normalizedPath.substringBeforeLast('/', "").ifBlank { "/" }
        val remote = list(parent).firstOrNull { it.path == normalizedPath && !it.directory } ?: return null
        if (remote.fsId.isBlank()) return null

        val domain = session.apiDomain.ifBlank { "www.terabox.com" }
        val json = requestJson(
            "https://$domain/openapi/api/download?access_tokens=" + enc(session.accessToken) +
                "&fidlist=" + enc("[${remote.fsId}]") +
                "&type=dlink",
            "GET",
        )
        if (json.optInt("errno", -1) != 0) return null

        val links = json.optJSONArray("dlink")
            ?: json.optJSONArray("list")
            ?: JSONArray()
        val dlink = links.optJSONObject(0)?.optString("dlink").orEmpty()
            .ifBlank { links.optString(0).orEmpty() }
            .ifBlank { json.optString("dlink") }
        if (dlink.isBlank()) return null

        val connection = URL(dlink).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "*/*")
        return connection.inputStream
    }

    private fun saveTokenResponse(json: JSONObject): Map<String, Any> {
        val errno = json.optInt("errno", Int.MIN_VALUE)
        val data = json.optJSONObject("data") ?: JSONObject()
        val accessToken = data.optString("access_token").trim()
        val refreshToken = data.optString("refresh_token").trim()
        if (errno != 0 || accessToken.isBlank()) {
            return mapOf(
                "ok" to false,
                "errno" to errno,
                "message" to json.optString("show_msg").ifBlank { "TeraBox token exchange failed." },
            )
        }

        val current = store.readSession()
        val tokenInfo = tokenInfo(accessToken)
        val tokenInfoData = tokenInfo.optJSONObject("data") ?: JSONObject()
        val expiresIn = data.optLong("expires_in", 172800L).coerceAtLeast(60L)
        val session = TeraBoxSession(
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { current?.refreshToken.orEmpty() },
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
            apiDomain = tokenInfoData.optString("api_domain")
                .ifBlank { data.optString("api_domain") }
                .ifBlank { current?.apiDomain.orEmpty() }
                .ifBlank { "www.terabox.com" },
            userId = tokenInfoData.optString("user_id")
                .ifBlank { data.optString("user_id") }
                .ifBlank { current?.userId.orEmpty() },
        )
        store.saveSession(session)
        return mapOf(
            "ok" to true,
            "expires_at_ms" to session.expiresAtMs,
            "api_domain" to session.apiDomain,
            "user_id" to session.userId,
        )
    }

    private fun tokenInfo(accessToken: String): JSONObject {
        return postForm(
            "https://www.terabox.com/oauth/tokeninfo",
            mapOf("access_token" to accessToken),
        )
    }

    private fun signature(config: TeraBoxConfig, timestamp: Long): String {
        val raw = config.clientId + "_" + timestamp + "_" + config.clientSecret + "_" + config.privateSecret
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (key, value) -> enc(key) + "=" + enc(value) }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readJson(connection)
    }

    private fun requestJson(url: String, method: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        return readJson(connection)
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(text) }.getOrElse {
            JSONObject()
                .put("errno", connection.responseCode)
                .put("show_msg", text.ifBlank { connection.responseMessage.orEmpty() })
        }
    }

    private fun normalizePath(path: String): String {
        val clean = path.trim().ifBlank { "/" }
        return if (clean.startsWith('/')) clean else "/$clean"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal data class TeraBoxRemoteNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAtMs: Long,
    val fsId: String,
)

internal object TeraBoxUris {
    fun fromPath(path: String): String {
        val normalized = if (path.startsWith('/')) path else "/$path"
        return Uri.Builder()
            .scheme("terabox")
            .authority("open")
            .appendQueryParameter("path", normalized)
            .build()
            .toString()
    }

    fun path(uri: String): String? {
        val parsed = Uri.parse(uri)
        if (!parsed.scheme.equals("terabox", ignoreCase = true)) return null
        return parsed.getQueryParameter("path")?.takeIf(String::isNotBlank)
    }

    fun isTeraBox(uri: String): Boolean =
        Uri.parse(uri).scheme.equals("terabox", ignoreCase = true)
}

internal class TeraBoxStorageProvider(
    private val client: TeraBoxClient,
) : StorageProvider {
    override fun walkTree(rootUri: String): Sequence<StorageNode> = sequence {
        val rootPath = TeraBoxUris.path(rootUri) ?: return@sequence
        val stack = ArrayDeque<Pair<String, String?>>()
        stack.add(rootPath to null)
        while (stack.isNotEmpty()) {
            val (path, parentUri) = stack.removeLast()
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { "TeraBox" }
            yield(
                StorageNode(
                    uri = TeraBoxUris.fromPath(path),
                    name = name,
                    isDirectory = true,
                    sizeBytes = null,
                    lastModifiedMs = null,
                    parentUri = parentUri,
                ),
            )
            val children = client.list(path)
            for (child in children.asReversed()) {
                val childUri = TeraBoxUris.fromPath(child.path)
                if (child.directory) {
                    stack.add(child.path to TeraBoxUris.fromPath(path))
                } else {
                    yield(
                        StorageNode(
                            uri = childUri,
                            name = child.name,
                            isDirectory = false,
                            sizeBytes = child.size,
                            lastModifiedMs = child.modifiedAtMs,
                            parentUri = TeraBoxUris.fromPath(path),
                        ),
                    )
                }
            }
        }
    }

    override fun listChildren(folderUri: String): List<StorageNode> {
        val path = TeraBoxUris.path(folderUri) ?: return emptyList()
        return client.list(path).map { child ->
            StorageNode(
                uri = TeraBoxUris.fromPath(child.path),
                name = child.name,
                isDirectory = child.directory,
                sizeBytes = if (child.directory) null else child.size,
                lastModifiedMs = child.modifiedAtMs,
                parentUri = folderUri,
            )
        }
    }

    override fun openInputStream(uri: String): InputStream? =
        TeraBoxUris.path(uri)?.let(client::openDownloadStream)

    override fun exists(uri: String): Boolean {
        val path = TeraBoxUris.path(uri) ?: return false
        if (path == "/") return client.connectionStatus()["connected"] == true
        val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
        return client.list(parent).any { it.path == path }
    }

    override fun rename(uri: String, newName: String, parentUri: String): StorageWriteResult {
        val path = TeraBoxUris.path(uri) ?: return StorageWriteResult(false, message = "invalid TeraBox URI")
        val result = client.rename(path, newName)
        val newPath = path.substringBeforeLast('/', "").ifBlank { "" } + "/" + newName
        return StorageWriteResult(
            ok = result["ok"] == true,
            uri = if (result["ok"] == true) TeraBoxUris.fromPath(newPath) else null,
            message = result["message"]?.toString().orEmpty(),
            changed = result["ok"] == true,
        )
    }

    override fun createFolder(parentUri: String, folderName: String): StorageWriteResult {
        return StorageWriteResult(
            ok = false,
            message = "TeraBox Open Platform documentation does not expose a supported mkdir operation for this integration.",
        )
    }

    override fun delete(uri: String): StorageWriteResult {
        val path = TeraBoxUris.path(uri) ?: return StorageWriteResult(false, message = "invalid TeraBox URI")
        val result = client.delete(path)
        return StorageWriteResult(
            ok = result["ok"] == true,
            message = result["message"]?.toString().orEmpty(),
            changed = result["ok"] == true,
        )
    }

    override fun copy(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val source = TeraBoxUris.path(sourceUri) ?: return StorageWriteResult(false, message = "invalid source TeraBox URI")
        val destination = TeraBoxUris.path(targetFolderUri) ?: return StorageWriteResult(false, message = "invalid destination TeraBox URI")
        val name = preferredName?.trim()?.takeIf { it.isNotBlank() } ?: source.substringAfterLast('/')
        val result = client.copy(source, destination, name)
        return StorageWriteResult(
            ok = result["ok"] == true,
            uri = if (result["ok"] == true) TeraBoxUris.fromPath(destination.trimEnd('/') + "/" + name) else null,
            message = result["message"]?.toString().orEmpty(),
            changed = result["ok"] == true,
        )
    }

    override fun move(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val source = TeraBoxUris.path(sourceUri) ?: return StorageWriteResult(false, message = "invalid source TeraBox URI")
        val destination = TeraBoxUris.path(targetFolderUri) ?: return StorageWriteResult(false, message = "invalid destination TeraBox URI")
        val name = preferredName?.trim()?.takeIf { it.isNotBlank() } ?: source.substringAfterLast('/')
        val result = client.move(source, destination, name)
        return StorageWriteResult(
            ok = result["ok"] == true,
            uri = if (result["ok"] == true) TeraBoxUris.fromPath(destination.trimEnd('/') + "/" + name) else null,
            message = result["message"]?.toString().orEmpty(),
            changed = result["ok"] == true,
        )
    }
}

internal class RoutingStorageProvider(
    private val saf: StorageProvider,
    private val teraBox: StorageProvider,
) : StorageProvider {
    private fun provider(uri: String): StorageProvider =
        if (TeraBoxUris.isTeraBox(uri)) teraBox else saf

    override fun walkTree(rootUri: String): Sequence<StorageNode> = provider(rootUri).walkTree(rootUri)
    override fun listChildren(folderUri: String): List<StorageNode> = provider(folderUri).listChildren(folderUri)
    override fun findDirectory(folderUri: String, name: String): StorageNode? =
        provider(folderUri).findDirectory(folderUri, name)
    override fun openInputStream(uri: String): InputStream? = provider(uri).openInputStream(uri)
    override fun exists(uri: String): Boolean = provider(uri).exists(uri)
    override fun rename(uri: String, newName: String, parentUri: String): StorageWriteResult =
        provider(uri).rename(uri, newName, parentUri)
    override fun createFolder(parentUri: String, folderName: String): StorageWriteResult =
        provider(parentUri).createFolder(parentUri, folderName)
    override fun delete(uri: String): StorageWriteResult = provider(uri).delete(uri)

    override fun copy(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val sourceProvider = provider(sourceUri)
        val targetProvider = provider(targetFolderUri)
        if (sourceProvider === targetProvider) return sourceProvider.copy(sourceUri, targetFolderUri, preferredName)
        return StorageWriteResult(false, message = "Cross-provider copy is not enabled yet.")
    }

    override fun move(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val sourceProvider = provider(sourceUri)
        val targetProvider = provider(targetFolderUri)
        if (sourceProvider === targetProvider) return sourceProvider.move(sourceUri, targetFolderUri, preferredName)
        return StorageWriteResult(false, message = "Cross-provider move is not enabled yet.")
    }
}
