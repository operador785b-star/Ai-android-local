package com.ialocal.android.inference

import java.io.File

enum class EngineProfile(private val nativeId: Int) {
    QWEN35(1);

    fun prompts(history: List<Exchange>, user: String): Array<ByteArray> = when (this) {
        QWEN35 -> QwenPrompt.candidates(history, user)
    }

    companion object {
        internal fun inspect(file: File): EngineProfile? = try {
            val id = NativeBridge.inspectModel(file.absolutePath)
            entries.firstOrNull { it.nativeId == id }
        } catch (_: LinkageError) {
            null
        }
    }
}
