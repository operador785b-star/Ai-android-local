package com.ialocal.android.chat

internal data class ChatInput(val prompt: String, val jsonObject: Boolean)

internal fun parseChatInput(raw: String): ChatInput {
    val text = raw.trim()
    val command = "/json"
    val json = text.startsWith(command, ignoreCase = true) &&
        text.getOrNull(command.length)?.isWhitespace() != false
    return ChatInput(if (json) text.drop(command.length).trimStart() else text, json)
}
