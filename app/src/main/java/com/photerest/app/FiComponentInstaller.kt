package com.photerest.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photerest.ai.ModelSpec
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class FiInstallState(val busy: Boolean = false, val percent: Int? = null,
    val prepared: Boolean = false, val message: String? = null)

/** Local package preparation only: no URL, automatic network download or silent package operations. */
internal class FiComponentInstaller(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val file = File(app.cacheDir, "fi-install/ai-center.apk")
    private val mutable = MutableStateFlow(FiInstallState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun prepare(uri: Uri) {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            val part = File(file.parentFile, "ai-center.part")
            try {
                check(ModelSpec.supported()) { "本地 AI 需要 Android 12 及以上的 ARM64 手机" }
                mutable.value = FiInstallState(busy = true, message = "正在准备本地安装包")
                file.parentFile?.mkdirs()
                val size = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null,null,null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                } ?: -1L }.getOrDefault(-1L)
                check(size <= 256L * 1024 * 1024) { "组件安装包过大" }
                var copied = 0L
                checkNotNull(app.contentResolver.openInputStream(uri)) { "无法读取安装包" }.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= 256L * 1024 * 1024) { "组件安装包过大" }
                            output.write(buffer,0,count)
                            mutable.value = FiInstallState(true, if (size > 0) (copied*100/size).toInt().coerceIn(0,100) else null,
                                message = "正在准备本地安装包")
                        }
                    }
                }
                validate(part)
                check(!file.exists() || file.delete()) { "无法更新准备文件" }
                check(part.renameTo(file)) { "无法保存安装包" }
                mutable.value = FiInstallState(prepared = true, percent = 100, message = "安装包校验通过，请确认安装")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = FiInstallState(message = error.message ?: "准备失败，请重新选择安装包")
            } finally { part.delete() }
        }
    }
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signers.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
    @Suppress("DEPRECATION")
    internal fun validate(candidate: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = checkNotNull(app.packageManager.getPackageArchiveInfo(candidate.path, flags)) { "不是有效的 Android 安装包" }
        check(info.packageName == ModelSpec.HUB) { "请选择 Photerest AI Hub 组件安装包" }
        val own = app.packageManager.getPackageInfo(app.packageName, flags)
        check(signatures(info).isNotEmpty() && signatures(info) == signatures(own)) { "组件签名不匹配，无法与此版本安全共享模型" }
        check((info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "当前系统不支持此组件" }
        val installed = runCatching { app.packageManager.getPackageInfo(ModelSpec.HUB,0) }.getOrNull()
        fun version(p: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        check(installed == null || version(info) >= version(installed)) { "所选组件版本较旧，请选择更新版本" }
    }
    fun installIntent(): Intent {
        check(file.isFile && mutable.value.prepared) { "请先选择并校验安装包" }
        validate(file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(app,"${app.packageName}.files",file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }
}
