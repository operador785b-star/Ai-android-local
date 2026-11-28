package com.ialocal.android.inference

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal class Utf8Chunks {
    private var input = ByteBuffer.allocate(256)
    private var output = CharBuffer.allocate(256)
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun append(bytes: ByteArray, end: Boolean = false): String {
        if (bytes.size > input.remaining()) {
            val grown = ByteBuffer.allocate(maxOf(input.capacity() * 2, input.position() + bytes.size))
            input.flip()
            grown.put(input)
            input = grown
        }
        input.put(bytes).flip()
        if (output.capacity() < input.remaining() + 2) {
            output = CharBuffer.allocate(maxOf(output.capacity() * 2, input.remaining() + 2))
        }
        output.clear()
        decoder.decode(input, output, end).throwIfError()
        if (end) decoder.flush(output).throwIfError()
        input.compact()
        output.flip()
        return output.toString()
    }

    private fun java.nio.charset.CoderResult.throwIfError() {
        if (isError) throwException()
        check(!isOverflow)
    }
}
