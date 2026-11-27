package com.ialocal.android.inference

import java.io.File

enum class PerformanceMode(val label: String, val idleMs: Long) {
    ECONOMICO("Económico", 30_000),
    EQUILIBRADO("Equilibrado", 90_000),
    MAXIMO("Máximo rendimiento", 180_000);

    fun defaults() = when (this) {
        ECONOMICO -> InferenceSettings(1024, 256, mode = this)
        EQUILIBRADO -> InferenceSettings(mode = this)
        MAXIMO -> InferenceSettings(mode = this)
    }
}

data class InferenceSettings(
    val contextTokens: Int = 2048,
    val outputTokens: Int = 512,
    val threads: Int = DEFAULT_THREADS,
    val mode: PerformanceMode = PerformanceMode.EQUILIBRADO
) {
    companion object {
        const val DEFAULT_THREADS = 2
        val contexts = listOf(1024, 2048, 4096)
        val outputs = listOf(256, 512, 1024)
        val threadCounts = listOf(2, 3, 4)
    }
}

data class Exchange(val user: String, val assistant: String)

data class GenerationRequest(
    val model: File,
    val history: List<Exchange>,
    val user: String,
    val settings: InferenceSettings,
    val profile: EngineProfile,
    val conversationId: Long,
    val effectiveThreads: Int = settings.threads
)

enum class GenerationEnd { COMPLETE, LENGTH_LIMIT }

enum class InferencePhase(val nativeId: Int) {
    LOADING_MODEL(1), CREATING_CONTEXT(2), PROCESSING_PROMPT(3), GENERATING_TEXT(4)
}

data class InferenceMetrics(
    val promptTokens: Long,
    val outputTokens: Long,
    val totalMs: Long,
    val firstPieceMs: Long,
    val decodeMs: Long,
    val processPeakRssKiB: Long,
    val code: Long,
    val effectiveContext: Long,
    val modelLoadMs: Long,
    val contextInitMs: Long,
    val tokenizeMs: Long,
    val promptMs: Long,
    val reusedPromptTokens: Long,
    val evaluatedPromptTokens: Long,
    val modelReused: Boolean,
    val contextReused: Boolean,
    val processRssKiB: Long,
    val armFeatures: Long,
    val emittedPieces: Long
) {
    val tokensPerSecond: Double?
        get() = if (emittedPieces > 1 && decodeMs > 0) (emittedPieces - 1) * 1000.0 / decodeMs else null
    val promptTokensPerSecond: Double?
        get() = if (evaluatedPromptTokens > 0 && promptMs > 0) evaluatedPromptTokens * 1000.0 / promptMs else null

    companion object {
        internal const val FIELD_COUNT = 19

        internal fun read(raw: LongArray) = InferenceMetrics(
            raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7],
            raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14] != 0L,
            raw[15] != 0L, raw[16], raw[17], raw[18]
        )
    }
}

interface InferenceEngine {
    suspend fun generate(
        request: GenerationRequest,
        onMetrics: (InferenceMetrics) -> Unit = {},
        onPhase: (InferencePhase) -> Unit = {},
        onText: (String) -> Unit
    ): GenerationEnd
}

class InferenceException(message: String) : Exception(message)
