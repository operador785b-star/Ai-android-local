package com.ialocal.android.inference

internal object QwenPrompt {
    private const val system = "Eres IA Local, un asistente útil. Responde en el idioma del usuario " +
        "salvo que indique lo contrario. Si escribes código, usa bloques Markdown con lenguaje. " +
        "Si falta información, dilo con claridad."
    private val controlToken = Regex("<\\|[^|<>\\n]{1,80}\\|>")
    private const val MAX_HISTORY_CHARS = 24_000

    // Candidates go from most context to least. Native tokenization picks the first one that fits.
    fun candidates(history: List<Exchange>, user: String): Array<ByteArray> {
        var remaining = MAX_HISTORY_CHARS
        val recent = history.takeLast(12).asReversed().takeWhile {
            remaining -= it.user.length + it.assistant.length
            remaining >= 0
        }.asReversed()
        val formatted = recent.map {
            buildString {
                turn("user", it.user)
                turn("assistant", it.assistant)
            }
        }
        return Array(formatted.size + 1) { drop ->
            buildString {
                turn("system", system)
                for (index in drop until formatted.size) append(formatted[index])
                turn("user", user)
                append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
            }.encodeToByteArray()
        }
    }

    private fun StringBuilder.turn(role: String, text: String) {
        append("<|im_start|>").append(role).append('\n')
        append(controlToken.replace(text.replace("\u0000", "\uFFFD")) {
            it.value.replace('<', '‹').replace('>', '›')
        })
        append("<|im_end|>\n")
    }
}
