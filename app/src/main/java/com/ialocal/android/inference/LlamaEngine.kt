package com.ialocal.android.inference

import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class LlamaEngine : InferenceEngine {
    private val gate = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private var handle = 0L
    private var idleJob: Job? = null

    override suspend fun generate(
        request: GenerationRequest,
        onMetrics: (InferenceMetrics) -> Unit,
        onPhase: (InferencePhase) -> Unit,
        onText: (String) -> Unit
    ): GenerationEnd = gate.withLock {
        check(!closed.get())
        idleJob?.cancel()
        try {
            withContext(Dispatchers.Default) {
                val jobContext = coroutineContext
                val decoder = Utf8Chunks()
                val settings = request.settings
                val metrics = LongArray(InferenceMetrics.FIELD_COUNT)
                val result = try {
                    if (handle == 0L) handle = NativeBridge.openSession()
                    if (handle == 0L) throw InferenceException("Falta memoria para iniciar la sesión del modelo.")
                    NativeBridge.generateFormatted(
                        handle, request.conversationId, 64, settings.mode != PerformanceMode.ECONOMICO, request.jsonObject,
                        request.model.absolutePath,
                        request.profile.prompts(request.history, request.user),
                        settings.contextTokens, settings.outputTokens, request.effectiveThreads,
                        request.profile.nativeId, -1, 120_000, metrics,
                        NativeCallback({ jobContext.isActive }, onPhase) { bytes ->
                            decoder.append(bytes).takeIf(String::isNotEmpty)?.let(onText)
                        }
                    )
                } catch (_: LinkageError) {
                    throw InferenceException("El motor nativo no está disponible en este dispositivo.")
                }
                onMetrics(InferenceMetrics.read(metrics))
                jobContext.ensureActive()
                decoder.append(byteArrayOf(), end = true).takeIf(String::isNotEmpty)?.let(onText)
                when (result) {
                    0 -> GenerationEnd.COMPLETE
                    1 -> GenerationEnd.LENGTH_LIMIT
                    2 -> throw CancellationException("Generación detenida")
                    3 -> throw InferenceException("No se pudo cargar el GGUF con el perfil elegido.")
                    4 -> throw InferenceException("El mensaje supera el contexto disponible. Acórtalo o aumenta el contexto.")
                    6 -> throw InferenceException("Falta memoria para iniciar el modelo. Reduce el contexto o cierra otras aplicaciones.")
                    7 -> throw InferenceException("El modelo tardó más de dos minutos en empezar. Prueba un mensaje más corto o un modelo más ligero.")
                    else -> throw InferenceException("El motor local no pudo completar la respuesta.")
                }
            }
        } finally {
            if (handle != 0L && !closed.get()) {
                idleJob = cleanup.launch {
                    delay(request.settings.mode.idleMs)
                    gate.withLock { releaseLocked() }
                }
            }
        }
    }

    fun releaseWhenIdle() {
        if (!closed.get()) cleanup.launch { gate.withLock { releaseLocked() } }
    }

    suspend fun release() = gate.withLock { withContext(Dispatchers.Default) { releaseLocked() } }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            cleanup.launch { gate.withLock { releaseLocked() } }.invokeOnCompletion { cleanup.cancel() }
        }
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            NativeBridge.closeSession(handle)
            handle = 0
        }
    }
}

@Keep
internal object NativeBridge {
    init { System.loadLibrary("ialocal") }

    external fun inspectModel(path: String): Int

    external fun openSession(): Long
    external fun closeSession(handle: Long)

    external fun generateFormatted(
        handle: Long, conversation: Long, batch: Int, keepCheckpoint: Boolean, jsonObject: Boolean,
        path: String, prompts: Array<ByteArray>, context: Int, output: Int, threads: Int,
        profile: Int, seed: Int, firstTokenTimeoutMs: Int, metrics: LongArray, receiver: NativeCallback
    ): Int
}

@Keep
internal class NativeCallback(
    private val active: () -> Boolean,
    private val progress: (InferencePhase) -> Unit,
    private val emit: (ByteArray) -> Unit
) {
    fun isActive(): Boolean = active()
    fun onBytes(bytes: ByteArray) = emit(bytes)
    fun onPhase(id: Int) { InferencePhase.entries.firstOrNull { it.nativeId == id }?.let(progress) }
}
