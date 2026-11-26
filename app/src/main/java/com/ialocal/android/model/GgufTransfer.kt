package com.ialocal.android.model

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

class ModelException(message: String) : Exception(message)

internal object GgufTransfer {
    const val MAX_BYTES = 6L * 1024 * 1024 * 1024
    private val shaPattern = Regex("[a-f0-9]{64}")

    fun normalizedHash(input: String): String = input.trim().lowercase(Locale.ROOT).also {
        if (!shaPattern.matches(it)) throw ModelException("Introduce un SHA-256 de 64 dígitos hexadecimales.")
    }

    fun copyVerified(
        source: InputStream,
        target: OutputStream,
        hash: String,
        expectedBytes: Long?,
        onProgress: (Long) -> Unit
    ) {
        val expectedHash = normalizedHash(hash)
        val header = ByteArray(4)
        var headerOffset = 0
        while (headerOffset < header.size) {
            val count = source.read(header, headerOffset, header.size - headerOffset)
            if (count < 0) break
            headerOffset += count
        }
        if (!header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
            throw ModelException("El archivo no tiene una cabecera GGUF válida.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(header)
        target.write(header)
        var written = header.size.toLong()
        val buffer = ByteArray(256 * 1024)
        val maxBytes = expectedBytes ?: MAX_BYTES
        while (true) {
            onProgress(written)
            val count = source.read(buffer)
            if (count < 0) break
            written += count
            if (written > maxBytes) throw ModelException("El archivo supera el tamaño permitido.")
            digest.update(buffer, 0, count)
            target.write(buffer, 0, count)
        }
        if (expectedBytes != null && written != expectedBytes) {
            throw ModelException("La descarga está incompleta. Vuelve a intentarlo.")
        }
        val actual = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        if (actual != expectedHash) throw ModelException("El SHA-256 no coincide. El archivo se descartó.")
    }
}
