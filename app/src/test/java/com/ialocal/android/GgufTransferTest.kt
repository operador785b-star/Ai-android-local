package com.ialocal.android

import com.ialocal.android.model.GgufTransfer
import com.ialocal.android.model.ModelException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GgufTransferTest {
    private val gguf = "GGUFdatos de prueba".encodeToByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(gguf).joinToString("") { "%02x".format(it) }

    @Test fun acceptsMatchingHeaderSizeAndHash() {
        val target = ByteArrayOutputStream()
        assertEquals(hash, GgufTransfer.copyAndCheck(gguf.inputStream(), target, hash.uppercase(), gguf.size.toLong()) {})
        assertArrayEquals(gguf, target.toByteArray())
    }

    @Test fun computesImportHashWithoutAnExpectedChecksum() {
        val target = ByteArrayOutputStream()
        assertEquals(hash, GgufTransfer.copyAndCheck(gguf.inputStream(), target, null, null) {})
        assertArrayEquals(gguf, target.toByteArray())
    }

    @Test fun rejectsTruncatedFile() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyAndCheck(gguf.inputStream(), ByteArrayOutputStream(), hash, gguf.size.toLong() + 1) {}
        }
    }

    @Test fun rejectsIncorrectHash() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyAndCheck(gguf.inputStream(), ByteArrayOutputStream(), "0".repeat(64), null) {}
        }
    }

    @Test fun rejectsNonGgufDataOnImport() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyAndCheck("HTML".byteInputStream(), ByteArrayOutputStream(), null, null) {}
        }
    }

    @Test fun tellsTheUserToExtractAnArchiveBeforeImporting() {
        val target = ByteArrayOutputStream()
        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val error = assertThrows(ModelException::class.java) {
            GgufTransfer.copyAndCheck(archive.inputStream(), target, null, null) {}
        }
        assertEquals("El archivo es un ZIP. Extráelo y elige el modelo .gguf.", error.message)
        assertEquals(0, target.size())
    }
}
