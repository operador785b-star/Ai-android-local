package com.ialocal.android.inference

import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamaEngine : InferenceEngine {
    private val gate = Mutex()

    override suspend fun generate(
        request: GenerationRequest,
        onMetrics: (InferenceMetrics) -> Unit,
        onPhase: (InferencePhase) -> Unit,
        onText: (String) -> Unit
    ): GenerationEnd = gate.withLock {
        withContext(Dispatchers.Default) {
            val jobContext = coroutineContext
            val decoder = Utf8Chunks()
            val settings = request.settings
            val metrics = LongArray(8)
            val result = try {
                NativeBridge.generate(
                    request.model.absolutePath,
                    request.profile.prompts(request.history, request.user),
                    settings.contextTokens, settings.outputTokens, settings.threads,
                    request.profile.nativeId, -1, 120_000, metrics,
                    NativeCallback({ jobContext.isActive }, onPhase) { bytes ->
                        decoder.append(bytes).takeIf(String::isNotEmpty)?.let(onText)
                    }
                )
            } catch (_: LinkageError) {
                throw InferenceException("El motor nativo no está disponible en este dispositivo.")
            }
            onMetrics(InferenceMetrics(metrics[0], metrics[1], metrics[2], metrics[3], metrics[4], metrics[5], metrics[6], metrics[7]))
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
    }
}

@Keep
internal object NativeBridge {
    init { System.loadLibrary("ialocal") }

    external fun inspectModel(path: String): Int

    external fun generate(
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
