package com.ialocal.android.inference

internal object ChatPrompt {
    private const val system = "Eres IA Local, un asistente útil. Responde en el idioma del usuario " +
        "salvo que indique lo contrario. Respeta el formato exacto que pida. " +
        "Usa bloques Markdown para código si el usuario no pidió texto sin formato. " +
        "Si falta información, dilo con claridad."
    private val controlToken = Regex("<\\|[^|<>\\n]{1,80}\\|>|</?think>")
    private val systemTurn = buildString { turn("system", system) }
    private const val MAX_HISTORY_CHARS = 24_000

    // Candidates go from most context to least. Native tokenization picks the first one that fits.
    fun candidates(history: List<Exchange>, user: String, profile: EngineProfile = EngineProfile.QWEN35): Array<ByteArray> {
        var remaining = MAX_HISTORY_CHARS
        val recent = history.takeLast(12).asReversed().takeWhile {
            remaining -= it.user.length + it.assistant.length
            remaining >= 0
        }.asReversed()
        val formatted = recent.map {
            buildString {
                turn("user", it.user)
                turn("assistant", it.assistant, profile.historyPrefix)
            }
        }
        val suffix = buildString {
            turn("user", user)
            append("<|im_start|>assistant\n").append(profile.answerPrefix)
        }
        return Array(formatted.size + 1) { drop ->
            buildString {
                append(systemTurn)
                for (index in drop until formatted.size) append(formatted[index])
                append(suffix)
            }.encodeToByteArray()
        }
    }

    private fun StringBuilder.turn(role: String, text: String, prefix: String = "") {
        append("<|im_start|>").append(role).append('\n').append(prefix)
        append(controlToken.replace(text.replace("\u0000", "\uFFFD")) {
            it.value.replace('<', '‹').replace('>', '›')
        })
        append("<|im_end|>\n")
    }
}
