package com.photerest.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class SearchAuthorizationStatus(val label: String) {
    LOCKED("未授权"), READY("已授权 · 此手机"), INVALID("授权不可用，请重新导入"), REVOKED("授权已撤销")
}
internal class SearchAuthorizationFailure(val code: String) : IOException(when (code) {
    "authorization_time" -> "手机时间不准确，请启用自动设置日期和时间后重试。"
    "authorization_replay" -> "请求已使用，请重新操作。"
    "authorization_revoked" -> "搜图授权已撤销。"
    else -> "联网搜索功能暂时不可用。"
})

/** An installation key is never exported. Only the signed certificate and public request leave the app. */
internal object SearchAuthorization {
    private const val alias = "photerest-search-installation-v1"
    private const val maxFile = 16_384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var app: Context? = null
    private val mutableStatus = MutableStateFlow(SearchAuthorizationStatus.LOCKED)
    val status = mutableStatus.asStateFlow()
    val ready get() = mutableStatus.value == SearchAuthorizationStatus.READY
    private fun ctx() = app ?: throw SearchAuthorizationFailure("unauthorized")
    private fun certificateFile() = File(ctx().noBackupFilesDir, "search-license-v1.txt")
    private fun markerFile() = File(ctx().noBackupFilesDir, "search-license-revoked.txt")
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun un64(text: String): ByteArray {
        require(text.matches(Regex("[A-Za-z0-9_-]+"))) { "授权文件格式不正确" }
        return Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun hash(bytes: ByteArray) = b64(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun publicKey(encoded: String) = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(un64(encoded)))
    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(store().getKey(alias, null) as PrivateKey); update(bytes); sign()
    }
    private fun verify(key: PublicKey, bytes: ByteArray, signature: ByteArray) = Signature.getInstance("SHA256withRSA").run {
        initVerify(key); update(bytes); verify(signature)
    }
    fun initialize(context: Context) {
        synchronized(this) {
            if (app != null) return
            app = context.applicationContext
        }
        scope.launch { reload() }
    }
    private fun validate(certificate: String): JSONObject {
        require(certificate.length in 1..8192) { "授权文件格式不正确" }
        val parts = certificate.split('.')
        require(parts.size == 2) { "授权文件格式不正确" }
        val authority = JSONObject(ctx().resources.openRawResource(R.raw.search_authority).bufferedReader().use { it.readText() })
        require(verify(publicKey(authority.getString("key")), parts[0].toByteArray(Charsets.UTF_8), un64(parts[1]))) { "授权签名无效" }
        val payload = JSONObject(String(un64(parts[0]), Charsets.UTF_8))
        require(payload.getInt("v") == 1 && payload.getString("id").matches(Regex("[A-Za-z0-9-]{16,80}"))) { "不支持此授权格式" }
        require(payload.getString("app") == ctx().packageName) { "该授权属于另一个应用" }
        val allowed = if (ctx().packageName == "com.photerest.app") setOf("google", "baidu") else setOf("google")
        val engines = payload.getJSONArray("engines")
        require(engines.length() > 0 && (0 until engines.length()).all { engines.getString(it) in allowed }) { "授权引擎不匹配" }
        val local = store().getCertificate(alias)?.publicKey ?: throw IllegalArgumentException("该授权不属于本机，请先导出授权申请")
        require(MessageDigest.isEqual(local.encoded, un64(payload.getString("key")))) { "该授权不属于本机" }
        val challenge = "PHOTEREST-LICENSE-CHECK-V1".toByteArray(Charsets.UTF_8)
        require(verify(local, challenge, sign(challenge))) { "本机授权密钥不可用" }
        if (markerFile().exists() && markerFile().readText() == payload.getString("id")) throw SearchAuthorizationFailure("authorization_revoked")
        return payload
    }
    private fun reload() {
        mutableStatus.value = try {
            if (!certificateFile().exists()) SearchAuthorizationStatus.LOCKED
            else { validate(certificateFile().readText().trim()); SearchAuthorizationStatus.READY }
        } catch (error: Exception) {
            if (error is SearchAuthorizationFailure && error.code == "authorization_revoked") SearchAuthorizationStatus.REVOKED else SearchAuthorizationStatus.INVALID
        }
    }
    suspend fun exportRequest(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        initialize(context)
        val key = synchronized(this@SearchAuthorization) {
            val existing = store().getCertificate(alias)?.publicKey
            existing ?: KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
                generateKeyPair().public
            }
        }
        val id = UUID.randomUUID().toString()
        val encoded = b64(key.encoded)
        val proof = b64(sign(listOf("PHOTEREST-REQUEST-V1", ctx().packageName, id, encoded).joinToString("\n").toByteArray(Charsets.UTF_8)))
        val request = JSONObject().put("v", 1).put("app", ctx().packageName).put("requestId", id).put("key", encoded).put("proof", proof)
        context.contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(request.toString()) }
            ?: throw IOException("无法写入申请文件")
    }
    suspend fun importCertificate(context: Context, source: Uri) = withContext(Dispatchers.IO) {
        initialize(context)
        val bytes = context.contentResolver.openInputStream(source)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (out.size() <= maxFile) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxFile + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray() } ?: throw IOException("无法读取授权文件")
        require(bytes.size <= maxFile) { "授权文件过大" }
        val certificate = String(bytes, Charsets.UTF_8).trim()
        validate(certificate) // Validate everything BEFORE replacing a working license.
        val file = AtomicFile(certificateFile())
        val output = file.startWrite()
        try { output.write(certificate.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        reload()
    }
    suspend fun headers(method: String, path: String, body: ByteArray): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val certificate = certificateFile().readText().trim()
            val payload = validate(certificate)
            val engine = path.substringAfter("/v1/search/", "")
            if (engine.isNotEmpty()) require((0 until payload.getJSONArray("engines").length()).any { payload.getJSONArray("engines").getString(it) == engine })
            val timestamp = System.currentTimeMillis().toString()
            val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val nonce = b64(random)
            val text = listOf("PHOTEREST-SEARCH-V1", method, path, timestamp, nonce, hash(body), hash(certificate.toByteArray(Charsets.UTF_8))).joinToString("\n")
            mapOf("Authorization" to "Photerest $certificate", "X-Photerest-Time" to timestamp, "X-Photerest-Nonce" to nonce,
                "X-Photerest-Signature" to b64(sign(text.toByteArray(Charsets.UTF_8))))
        } catch (error: Exception) {
            reload()
            if (error is SearchAuthorizationFailure) throw error
            throw SearchAuthorizationFailure("unauthorized")
        }
    }
    suspend fun rejected(code: String) = withContext(Dispatchers.IO) {
        if (code == "authorization_revoked") {
            runCatching {
                val parts = certificateFile().readText().trim().split('.')
                markerFile().writeText(JSONObject(String(un64(parts[0]), Charsets.UTF_8)).getString("id"))
            }
            mutableStatus.value = SearchAuthorizationStatus.REVOKED
        } else if (code == "unauthorized") mutableStatus.value = SearchAuthorizationStatus.INVALID
    }
}

@Composable
internal fun SearchAuthorizationControls(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalTapFeedback.current
    val state by SearchAuthorization.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) { SearchAuthorization.initialize(context) }
    fun perform(action: suspend () -> Unit, success: String) {
        scope.launch {
            busy = true
            try { action(); message = success; onChanged() }
            catch (error: Exception) { if (error is CancellationException) throw error; message = error.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform({ SearchAuthorization.exportRequest(context, uri) }, "申请已导出，请交给电脑上的离线签发工具。")
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform({ SearchAuthorization.importCertificate(context, uri) }, "授权已导入，此手机可以使用联网搜图。")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("搜图授权 · ${state.label}", style = MaterialTheme.typography.titleMedium)
        Text("授权在本机离线验证。换手机、卸载或清除数据后需要重新授权。", style = MaterialTheme.typography.bodySmall)
        TapTextButton(onClick = { feedback.perform(); export.launch("${context.packageName}-search-request.json") }, enabled = !busy) { Text("导出授权申请") }
        TapTextButton(onClick = { feedback.perform(); import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入授权文件") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SearchUnavailableDialog(dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, text = { Text("联网搜索功能暂时不可用。") },
        confirmButton = { TapTextButton(onClick = dismiss) { Text("知道了") } })
}
