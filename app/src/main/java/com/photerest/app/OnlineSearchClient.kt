package com.photerest.app
import android.content.Context
import android.graphics.Bitmap
import java.io.*
import java.net.*
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONObject

enum class SearchEngine(val id: String, val title: String, val recipient: String) {
    GOOGLE("google", "Google", "Google Cloud Vision"),
}

internal data class SearchQuota(val limit: Int, val remaining: Int, val resetsAt: Long?)
internal data class SearchResult(val image: String, val thumbnail: String, val page: String?, val title: String)
private class SearchFailure(val code: String) : IOException()

private fun openHttps(address: String): HttpsURLConnection =
    (URL(address).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 20_000
        instanceFollowRedirects = false
    }

private fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read == -1) return out.toByteArray()
        if (out.size() + read > limit) throw IOException("Response too large")
        out.write(buffer, 0, read)
    }
}

internal object ImageSearchClient {
    private val origin = BuildConfig.IMAGE_SEARCH_ORIGIN
    val configured get() = origin.isNotEmpty()

    private suspend fun request(path: String, jpeg: ByteArray? = null): JSONObject = withContext(Dispatchers.IO) {
        if (!configured) throw SearchFailure("not_configured")
        val connection = openHttps(origin + path)
        try {
            if (jpeg != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "image/jpeg")
                connection.setFixedLengthStreamingMode(jpeg.size)
                connection.outputStream.use { it.write(jpeg) }
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { JSONObject(it.readBounded(256 * 1024).toString(Charsets.UTF_8)) }
                ?: throw SearchFailure("unavailable")
            if (status !in 200..299) throw SearchFailure(body.optString("error", "unavailable"))
            body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun quotas(): Map<SearchEngine, SearchQuota> {
        val json = request("/v1/quota")
        return SearchEngine.entries.associateWith { engine ->
            val entry = json.getJSONObject(engine.id)
            SearchQuota(entry.getInt("limit"), entry.getInt("remaining"), entry.optLong("resetsAt").takeIf { it > 0 })
        }
    }

    suspend fun search(context: Context, engine: SearchEngine, photo: Photo): List<SearchResult> {
        if (!configured) throw SearchFailure("not_configured")
        val bitmap = PhotoBitmaps.load(context, photo.uri, large = true, revision = photo.added)
            ?: throw SearchFailure("unreadable")
        val jpeg = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) || it.size() !in 4..2_500_000) {
                    throw SearchFailure("unreadable")
                }
                it.toByteArray()
            }
        }
        val items = request("/v1/search/${engine.id}", jpeg).getJSONArray("results")
        return (0 until items.length()).map { index ->
            val row = items.getJSONObject(index)
            SearchResult(row.getString("image"), row.optString("thumbnail", row.getString("image")),
                row.optString("page").takeIf { it.startsWith("https://") || it.startsWith("http://") },
                row.optString("title"))
        }
    }
}

internal fun searchMessage(error: Exception): String = when (error) {
    is SocketTimeoutException -> "请求超时，请稍后重试"
    is SearchFailure -> when (error.code) {
        "quota" -> "该引擎的共享搜索额度已用完"
        "rate" -> "请求较频繁，请稍后重试"
        "not_configured" -> "此安装包尚未配置识图服务"
        "unreadable" -> "原图无法读取，请检查照片权限"
        "upstream" -> "搜索引擎暂时不可用"
        "timeout" -> "搜索引擎响应超时，请稍后重试"
        else -> "识图服务暂时不可用"
    }
    is IOException -> "无法连接识图服务，请检查网络"
    else -> "识图服务暂时不可用"
}

