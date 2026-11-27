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
        onText: (String) -> Unit
    ): GenerationEnd = gate.withLock {
        withContext(Dispatchers.Default) {
            val jobContext = coroutineContext
            val decoder = Utf8Chunks()
            val settings = request.settings
            val result = try {
                NativeBridge.generate(
                    request.model.absolutePath,
                    request.profile.prompts(request.history, request.user),
                    settings.contextTokens, settings.outputTokens, settings.threads,
                    NativeCallback({ jobContext.isActive }) { bytes ->
                        decoder.append(bytes).takeIf(String::isNotEmpty)?.let(onText)
                    }
                )
            } catch (_: LinkageError) {
                throw InferenceException("El motor nativo no está disponible en este dispositivo.")
            }
            jobContext.ensureActive()
            decoder.append(byteArrayOf(), end = true).takeIf(String::isNotEmpty)?.let(onText)
            when (result) {
                0 -> GenerationEnd.COMPLETE
                1 -> GenerationEnd.LENGTH_LIMIT
                2 -> throw CancellationException("Generación detenida")
                3 -> throw InferenceException("No se pudo abrir un modelo de texto Qwen3.5 compatible.")
                4 -> throw InferenceException("El mensaje supera el contexto disponible. Acórtalo o aumenta el contexto.")
                6 -> throw InferenceException("Falta memoria para iniciar el modelo. Reduce el contexto o cierra otras aplicaciones.")
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
        receiver: NativeCallback
    ): Int
}

@Keep
internal class NativeCallback(private val active: () -> Boolean, private val emit: (ByteArray) -> Unit) {
    fun isActive(): Boolean = active()
    fun onBytes(bytes: ByteArray) = emit(bytes)
}
