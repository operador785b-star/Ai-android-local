package com.ialocal.android.chat

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ialocal.android.inference.GenerationEnd
import com.ialocal.android.inference.GenerationRequest
import com.ialocal.android.inference.InferenceException
import com.ialocal.android.inference.InferenceJournal
import com.ialocal.android.inference.InferenceMetrics
import com.ialocal.android.inference.InferencePhase
import com.ialocal.android.inference.InferenceSettings
import com.ialocal.android.inference.LlamaEngine
import com.ialocal.android.inference.PerformanceMode
import com.ialocal.android.model.InstalledModel
import com.ialocal.android.model.ModelException
import com.ialocal.android.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TransferProgress(val label: String, val bytes: Long = 0, val total: Long? = null)
data class LiveAnswer(val id: Long, val body: String)

data class ChatUiState(
    val chats: List<Chat> = emptyList(),
    val selectedChat: Long? = null,
    val messages: List<Message> = emptyList(),
    val models: List<InstalledModel> = emptyList(),
    val activeModel: InstalledModel? = null,
    val settings: InferenceSettings = InferenceSettings(),
    val diagnostics: String = "",
    val appearance: AppearanceSettings = AppearanceSettings(),
    val loading: Boolean = true,
    val generating: Boolean = false,
    val inferencePhase: InferencePhase? = null,
    val stopping: Boolean = false,
    val transfer: TransferProgress? = null,
    val notice: String? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val chats = ChatStore(app)
    private val models = ModelStore(app)
    private val engine = LlamaEngine()
    private val journal = InferenceJournal(app)
    private val power = app.getSystemService(PowerManager::class.java)
    private val prefs = app.getSharedPreferences("preferences", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(ChatUiState(
        appearance = AppearanceSettings(
            mode = AppearanceMode.entries.firstOrNull { it.name == prefs.getString("appearance", null) }
                ?: AppearanceMode.SYSTEM,
            reduceMotion = prefs.getBoolean("reduce_motion", false)
        )
    ))
    val state = _state.asStateFlow()
    private val _live = MutableStateFlow<LiveAnswer?>(null)
    val live = _live.asStateFlow()
    private var generation: Job? = null
    private var transfer: Job? = null

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    chats.recoverInterrupted()
                    journal.recover()
                    _state.update { it.copy(diagnostics = journal.report()) }
                    models.discardPartialFiles()
                }
                val saved = prefs.getLong("chat", 0L).takeIf { it > 0 }
                refreshChats(saved)
                refreshModels(prefs.getString("model", null))
                val context = prefs.getInt("context", 2048).takeIf { it in InferenceSettings.contexts } ?: 2048
                val output = prefs.getInt("output", 512)
                    .takeIf { it in InferenceSettings.outputs && it < context } ?: 512
                val defaults = InferenceSettings.DEFAULT_THREADS
                val threads = prefs.getInt("threads", defaults).takeIf { it in InferenceSettings.threadCounts } ?: defaults
                val mode = PerformanceMode.entries.firstOrNull { it.name == prefs.getString("performance-mode", null) }
                    ?: PerformanceMode.EQUILIBRADO
                _state.update { it.copy(settings = InferenceSettings(context, output, threads, mode)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("No se pudieron cargar los datos locales.")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        val snapshot = _state.value
        val model = snapshot.activeModel ?: return
        val profile = model.profile ?: return
        if (snapshot.loading || snapshot.generating || snapshot.transfer != null || text.isBlank()) return
        if (text.length > MAX_MESSAGE_CHARS) {
            notice("El mensaje admite hasta ${MAX_MESSAGE_CHARS} caracteres.")
            return
        }
        _state.update { it.copy(generating = true, stopping = false, inferencePhase = InferencePhase.LOADING_MODEL) }
        generation = viewModelScope.launch {
            var turn: Turn? = null
            val body = StringBuilder()
            var end = MessageState.ERROR
            var runId: Long? = null
            var metrics: InferenceMetrics? = null
            var cancellation: String? = null
            try {
                withContext(NonCancellable) {
                    turn = withContext(Dispatchers.IO) { chats.beginTurn(snapshot.selectedChat, text) }
                    refreshChats(checkNotNull(turn).chatId)
                }
                ensureActive()
                val currentTurn = checkNotNull(turn)
                val history = withContext(Dispatchers.IO) { chats.history(currentTurn) }
                val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
                val reduced = power?.isPowerSaveMode == true || (thermal ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
                val threads = if (reduced) minOf(snapshot.settings.threads, InferenceSettings.DEFAULT_THREADS)
                    else snapshot.settings.threads
                var lastPaint = 0L
                var lastSave = SystemClock.elapsedRealtime()
                withContext(NonCancellable + Dispatchers.IO) {
                    runId = journal.start(model.name, snapshot.settings, model.file.length(), threads, thermal)
                    _state.update { it.copy(diagnostics = journal.report()) }
                }
                val result = engine.generate(
                    GenerationRequest(model.file, history, text, snapshot.settings, profile, currentTurn.chatId, threads),
                    onMetrics = { metrics = it },
                    onPhase = { phase ->
                        if (_state.value.inferencePhase != phase) {
                            runId?.let { journal.updatePhase(it, phase) }
                            val report = journal.report()
                            _state.update { it.copy(inferencePhase = phase, diagnostics = report) }
                        }
                    }
                ) { fragment ->
                    body.append(fragment)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPaint >= 35L) {
                        _live.value = LiveAnswer(currentTurn.answerId, body.toString())
                        lastPaint = now
                    }
                    if (now - lastSave >= 1_000L) {
                        chats.saveAnswer(currentTurn.answerId, body.toString(), MessageState.GENERATING)
                        lastSave = now
                    }
                }
                end = when {
                    body.isBlank() -> {
                        notice("El modelo no devolvió texto. Prueba reformular el mensaje.")
                        MessageState.ERROR
                    }
                    result == GenerationEnd.LENGTH_LIMIT -> MessageState.LIMITED
                    else -> MessageState.COMPLETE
                }
            } catch (cancelled: CancellationException) {
                end = MessageState.STOPPED
                cancellation = if (_state.value.stopping) "BOTON_DETENER" else "CICLO_DE_VIDA"
                throw cancelled
            } catch (error: InferenceException) {
                notice(error.message ?: "Falló la generación.")
            } catch (_: Exception) {
                notice("No se pudo completar o guardar el mensaje.")
            } finally {
                try {
                    withContext(NonCancellable) {
                        runId?.let { id ->
                            val report = withContext(Dispatchers.IO) {
                                journal.finish(id, end.name, metrics, cancellation)
                                journal.report()
                            }
                            _state.update { it.copy(diagnostics = report) }
                        }
                        turn?.let { saved ->
                            withContext(Dispatchers.IO) {
                                chats.saveAnswer(saved.answerId, body.toString(), end)
                            }
                            refreshChats(saved.chatId)
                        }
                    }
                } catch (_: Exception) {
                    notice("No se pudo actualizar el historial.")
                } finally {
                    _live.value = null
                    _state.update { it.copy(generating = false, stopping = false, inferencePhase = null) }
                    generation = null
                }
            }
        }
    }

    fun stopGeneration() {
        _state.update { it.copy(stopping = it.generating) }
        generation?.cancel()
    }

    fun selectChat(id: Long?) = editHistory { refreshChats(id) }

    fun renameChat(id: Long, name: String) = editHistory {
        withContext(Dispatchers.IO) { chats.rename(id, name.trim().take(72)) }
        refreshChats(_state.value.selectedChat)
    }

    fun deleteChat(id: Long) = editHistory {
        withContext(Dispatchers.IO) { chats.delete(id) }
        refreshChats(_state.value.selectedChat.takeUnless { it == id })
    }

    private fun editHistory(action: suspend () -> Unit) {
        if (_state.value.loading || _state.value.generating) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("No se pudo actualizar el historial.")
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun downloadModel(sha256: String) = startTransfer("Descargando modelo") {
        models.download(sha256, it)
    }

    fun importModel(uri: Uri, name: String) = startTransfer("Importando modelo") {
        models.import(uri, name, it)
    }

    private fun startTransfer(
        label: String, work: suspend ((Long, Long?) -> Unit) -> InstalledModel
    ) {
        if (!canChangeModel()) return
        _state.update { it.copy(transfer = TransferProgress(label)) }
        transfer = viewModelScope.launch {
            var installedModel: InstalledModel? = null
            try {
                engine.release()
                installedModel = work { bytes, total ->
                    _state.update { it.copy(transfer = TransferProgress(label, bytes, total)) }
                }
            } catch (cancelled: CancellationException) {
                notice("Transferencia cancelada.")
                throw cancelled
            } catch (error: ModelException) {
                notice(error.message ?: "No se pudo verificar el modelo.")
            } catch (_: Exception) {
                notice("No se pudo transferir el modelo. Comprueba la conexión, el archivo y el espacio disponible.")
            } finally {
                withContext(NonCancellable) {
                    try {
                        refreshModels(installedModel?.sha256 ?: _state.value.activeModel?.sha256)
                        if (installedModel != null) notice("Modelo en uso. Vuelve al chat y escribe tu mensaje.")
                    } catch (_: Exception) {
                        notice("No se pudo actualizar la biblioteca. Vuelve a abrir la aplicación.")
                    } finally {
                        _state.update { it.copy(transfer = null) }
                        transfer = null
                    }
                }
            }
        }
    }

    fun cancelTransfer() { transfer?.cancel() }

    fun activateModel(hash: String) {
        if (!canChangeModel()) return
        val model = _state.value.models.firstOrNull { it.sha256 == hash && it.profile != null } ?: return
        if (_state.value.activeModel?.sha256 != model.sha256) engine.releaseWhenIdle()
        prefs.edit().putString("model", model.sha256).apply()
        _state.update { it.copy(activeModel = model) }
    }

    fun deleteModel(model: InstalledModel) {
        if (!canChangeModel()) return
        _state.update { it.copy(transfer = TransferProgress("Eliminando modelo")) }
        viewModelScope.launch {
            try {
                if (_state.value.activeModel?.sha256 == model.sha256) engine.release()
                withContext(Dispatchers.IO) { models.delete(model) }
                refreshModels(_state.value.activeModel?.sha256)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("No se pudo eliminar el modelo.")
            } finally {
                _state.update { it.copy(transfer = null) }
            }
        }
    }

    private fun canChangeModel() = _state.value.let { !it.loading && !it.generating && it.transfer == null }

    fun configure(value: InferenceSettings) {
        _state.update { it.copy(settings = value) }
        prefs.edit().putInt("context", value.contextTokens)
            .putInt("output", value.outputTokens).putInt("threads", value.threads)
            .putString("performance-mode", value.mode.name).apply()
    }

    fun releaseEngineWhenIdle() = engine.releaseWhenIdle()

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }

    fun configureAppearance(value: AppearanceSettings) {
        prefs.edit().putString("appearance", value.mode.name)
            .putBoolean("reduce_motion", value.reduceMotion).apply()
        _state.update { it.copy(appearance = value) }
    }

    fun notice(text: String) { _state.update { it.copy(notice = text) } }

    fun dismissNotice(text: String) {
        _state.update { if (it.notice == text) it.copy(notice = null) else it }
    }

    private suspend fun refreshChats(selected: Long?) {
        val snapshot = withContext(Dispatchers.IO) {
            val summaries = chats.chats()
            val realId = selected?.takeIf { id -> summaries.any { it.id == id } }
            Triple(summaries, realId, realId?.let(chats::messages).orEmpty())
        }
        prefs.edit().putLong("chat", snapshot.second ?: 0L).apply()
        _state.update {
            it.copy(chats = snapshot.first, selectedChat = snapshot.second, messages = snapshot.third)
        }
    }

    private suspend fun refreshModels(preferred: String?) {
        val installed = withContext(Dispatchers.IO) { models.installed() }
        val compatible = installed.filter { it.profile != null }
        val active = compatible.firstOrNull { it.sha256 == preferred } ?: compatible.firstOrNull()
        prefs.edit().putString("model", active?.sha256).apply()
        _state.update { it.copy(models = installed, activeModel = active) }
    }

    companion object { const val MAX_MESSAGE_CHARS = 12_000 }
}
