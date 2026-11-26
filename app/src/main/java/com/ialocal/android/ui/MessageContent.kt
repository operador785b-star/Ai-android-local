package com.ialocal.android.ui

internal sealed interface MessageBlock {
    data class Text(val value: String) : MessageBlock
    data class Code(val language: String, val value: String) : MessageBlock
}

internal fun messageBlocks(body: String): List<MessageBlock> = buildList {
    val openFence = Regex("^ {0,3}(\\x60{3,}|~{3,})(.*)$")
    var fence: String? = null
    var language = ""
    val lines = mutableListOf<String>()
    for (line in body.split('\n')) {
        val match = openFence.matchEntire(line)
        val active = fence
        when {
            active == null && match != null -> {
                val prose = lines.joinToString("\n").trim()
                if (prose.isNotEmpty()) add(MessageBlock.Text(prose))
                lines.clear()
                fence = match.groupValues[1]
                language = match.groupValues[2].trim().substringBefore(' ').take(30)
            }
            active != null && match != null && match.groupValues[1].first() == active.first() &&
                match.groupValues[1].length >= active.length && match.groupValues[2].isBlank() -> {
                add(MessageBlock.Code(language, lines.joinToString("\n")))
                lines.clear()
                fence = null
            }
            else -> lines.add(line)
        }
    }
    val remainder = lines.joinToString("\n")
    if (fence != null) add(MessageBlock.Code(language, remainder))
    else if (remainder.isNotBlank()) add(MessageBlock.Text(remainder.trim()))
}
