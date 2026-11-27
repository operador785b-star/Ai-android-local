package com.ialocal.android.inference

import java.io.File

enum class EngineProfile(val nativeId: Int, val answerPrefix: String, val historyPrefix: String) {
    QWEN35(1, "<think>\n\n</think>\n\n", ""),
    LFM3(2, "<think></think>", "<think></think>"),
    NANBEIGE(3, "<think>\n\n</think>\n\n", "<think>\n\n</think>\n\n");

    fun prompts(history: List<Exchange>, user: String): Array<ByteArray> =
        ChatPrompt.candidates(history, user, this)

    companion object {
        internal fun inspect(file: File): EngineProfile? = try {
            val id = NativeBridge.inspectModel(file.absolutePath)
            entries.firstOrNull { it.nativeId == id }
        } catch (_: LinkageError) {
            null
        }
    }
}
