package com.ialocal.android.inference

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal class Utf8Chunks {
    private var pending = ByteArray(0)
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun append(bytes: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, end).throwIfError()
        if (end) decoder.flush(output).throwIfError()
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }

    private fun java.nio.charset.CoderResult.throwIfError() {
        if (isError) throwException()
        check(!isOverflow)
    }
}
