package com.ialocal.android.inference

import java.io.File

data class InferenceSettings(
    val contextTokens: Int = 2048,
    val outputTokens: Int = 512,
    val threads: Int = DEFAULT_THREADS
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
    val profile: EngineProfile
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
    val effectiveContext: Long
) {
    val tokensPerSecond: Double?
        get() = if (outputTokens > 1 && decodeMs > 0) (outputTokens - 1) * 1000.0 / decodeMs else null
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
