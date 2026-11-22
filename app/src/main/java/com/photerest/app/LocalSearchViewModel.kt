package com.photerest.app

import android.app.Application
import android.net.Uri
import com.photerest.ai.ModelSpec
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal enum class LocalSearchEngineState {
    UNSUPPORTED, NEEDS_DOWNLOAD, DOWNLOADED, DOWNLOADING, LOADING, READY, FAILED
}

internal data class LocalSearchMatch(val uri: String, val similarity: Float)

internal data class LocalSearchUiState(
    val supported: Boolean = false,
    val engine: LocalSearchEngineState = LocalSearchEngineState.UNSUPPORTED,
    val downloadPercent: Int = 0,
    val accelerator: String? = null,
    val analyzing: Boolean = false,
    val analysisScope: String? = null,
    val analysisDone: Int = 0,
    val analysisTotal: Int = 0,
    val analyzedScope: String? = null,
    val analyzedCount: Int = 0,
    val searching: Boolean = false,
    val results: List<LocalSearchMatch> = emptyList(),
    val hasLegacyModel: Boolean = false,
    val sharedVerified: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the Gallery-derived EmbeddingGemma engine and vector index for the lifetime of the activity,
 * so switching between Home/Search/Profile does not reload the 484MB model.
 */
internal class LocalSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val index = LocalSearchIndex(application)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var embedder: LocalSearchEmbedder? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var searchJob: Job? = null

    private val supported = LocalSearchModel.supported()
    private val _state = MutableStateFlow(
        LocalSearchUiState(
            supported = supported,
            hasLegacyModel = LocalSearchModel.downloaded(application),
            sharedVerified = application.getSharedPreferences("shared_ai", 0).getString("proof", "") == ModelSpec.SHA,
            engine = when {
                !supported -> LocalSearchEngineState.UNSUPPORTED
                else -> LocalSearchEngineState.DOWNLOADED
            },
        )
    )
    val state: StateFlow<LocalSearchUiState> = _state.asStateFlow()

    /** Called when the search tab is first shown; loads an already-downloaded model automatically. */
    fun autoLoad() {
        if (!app.getSharedPreferences("appearance", 0).getBoolean("fi_enabled", false)) return
        if (!supported) return
        if (_state.value.engine == LocalSearchEngineState.DOWNLOADED) loadModel()
    }

    fun downloadAndLoad() = openCenter()

    fun openCenter() {
        runCatching { ModelSpec.openCenter(app) }.onFailure { failure ->
            _state.update { it.copy(error = failure.message ?: "请先安装模型中心") }
        }
    }
    fun migrateModel() {
        runCatching {
            check(LocalSearchModel.downloaded(app)) { "Alpha 没有完整模型可迁移" }
            ModelSpec.importFromAlpha(app, Uri.parse("content://${app.packageName}.models/model"))
        }.onFailure { failure -> _state.update { it.copy(error = failure.message ?: "无法迁移模型") } }
    }
    private fun recordProof(engine: LocalSearchEmbedder) {
        check(engine.metadata.getString("sha256") == ModelSpec.SHA) { "中心模型不匹配" }
        app.getSharedPreferences("shared_ai", 0).edit().putString("proof", ModelSpec.SHA).apply()
        _state.update { it.copy(sharedVerified = true, accelerator = engine.accelerator) }
    }
    fun cleanupLegacyModel() {
        if (analysisJob?.isActive == true || searchJob?.isActive == true || loadJob?.isActive == true) return
        viewModelScope.launch(io) {
            try {
                check(_state.value.sharedVerified) { "请先在 Alpha 成功执行一次共享搜索" }
                val status = checkNotNull(embedder) { "请先连接中心" }.status()
                check(status.getBoolean("modelReady") && status.getString("sha256") == ModelSpec.SHA) { "中心模型尚未就绪，旧副本保留" }
                val old = LocalSearchModel.file(app)
                check(!old.exists() || old.delete()) { "无法清理旧模型" }
                _state.update { it.copy(hasLegacyModel = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "未清理旧副本") } }
        }
    }
    fun pause() {
        loadJob?.cancel(); analysisJob?.cancel(); searchJob?.cancel()
        embedder?.disconnect()
        _state.update { it.copy(analyzing = false, searching = false,
            engine = if (supported) LocalSearchEngineState.DOWNLOADED else LocalSearchEngineState.UNSUPPORTED) }
    }

    fun loadModel() {
        if (!app.getSharedPreferences("appearance", 0).getBoolean("fi_enabled", false)) {
            _state.update { it.copy(error = "φ 智能尚未开启") }; return
        }
        if (!supported || loadJob?.isActive == true ||
            _state.value.engine in setOf(LocalSearchEngineState.LOADING, LocalSearchEngineState.READY)
        ) return
        loadJob = viewModelScope.launch(io) {
            try {
                loadNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        engine = LocalSearchEngineState.FAILED,
                        error = "模型加载失败：${failure.message ?: failure.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun retry() {
        loadModel()
    }

    private suspend fun loadNow() {
        _state.update { it.copy(engine = LocalSearchEngineState.LOADING, error = null) }
        val engine = LocalSearchEmbedder(app)
        try { engine.initialize() } catch (failure: Throwable) { engine.close(); throw failure }
        embedder?.close()
        embedder = engine
        _state.update {
            it.copy(
                engine = LocalSearchEngineState.READY,
                accelerator = engine.accelerator,
                error = null,
            )
        }
    }

    fun analyze(photos: List<Photo>, scope: String?) {
        if (!app.getSharedPreferences("appearance", 0).getBoolean("fi_enabled", false)) {
            _state.update { it.copy(error = "φ 智能尚未开启") }; return
        }
        val engine = embedder
        if (engine == null) {
            _state.update { it.copy(error = "请先加载本地模型") }
            return
        }
        if (photos.isEmpty()) {
            _state.update { it.copy(error = "这个范围内没有可分析的照片") }
            return
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(io) {
            _state.update {
                it.copy(
                    analyzing = true,
                    analysisScope = scope,
                    analysisDone = 0,
                    analysisTotal = photos.size,
                    analyzedScope = null,
                    analyzedCount = 0,
                    results = emptyList(),
                    error = null,
                )
            }
            try {
                for ((position, photo) in photos.withIndex()) {
                    coroutineContext.ensureActive()
                    if (index.read(photo) == null) {
                        index.write(photo, engine.embedImage(LocalSearchEmbedder.loadTga(app, photo)))
                        recordProof(engine)
                    }
                    _state.update { it.copy(analysisDone = position + 1) }
                }
                _state.update {
                    it.copy(
                        analyzing = false,
                        analyzedScope = scope,
                        analyzedCount = photos.size,
                        analysisDone = photos.size,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(analyzing = false) }
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        analyzing = false,
                        error = "相册分析失败：${failure.message ?: failure.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _state.update { it.copy(analyzing = false) }
    }

    fun search(query: String, photos: List<Photo>) {
        if (!app.getSharedPreferences("appearance", 0).getBoolean("fi_enabled", false)) {
            _state.update { it.copy(error = "φ 智能尚未开启") }; return
        }
        searchJob?.cancel()
        val text = query.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        val engine = embedder
        if (engine == null) {
            _state.update { it.copy(error = "请先加载本地模型") }
            return
        }
        searchJob = viewModelScope.launch(io) {
            _state.update { it.copy(searching = true, error = null) }
            try {
                val queryVector = engine.embedText(text)
                recordProof(engine)
                val entries = photos.mapNotNull { photo ->
                    index.read(photo)?.let { vector -> photo to vector }
                }
                val matches = rankPhotos(queryVector, entries).take(60)
                coroutineContext.ensureActive()
                _state.update {
                    it.copy(
                        searching = false,
                        results = matches.map { match -> LocalSearchMatch(match.photo.uri, match.similarity) },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        searching = false,
                        error = "搜索失败：${failure.message ?: failure.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun clearResults() {
        _state.update { it.copy(results = emptyList(), searching = false) }
    }

    fun clearIndex() {
        analysisJob?.cancel()
        searchJob?.cancel()
        viewModelScope.launch(io) {
            index.clear()
            _state.update {
                it.copy(
                    analyzedScope = null,
                    analyzedCount = 0,
                    analysisDone = 0,
                    analysisTotal = 0,
                    analyzing = false,
                    searching = false,
                    results = emptyList(),
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        analysisJob?.cancel()
        searchJob?.cancel()
        embedder?.close()
        embedder = null
        index.close()
        super.onCleared()
    }
}
