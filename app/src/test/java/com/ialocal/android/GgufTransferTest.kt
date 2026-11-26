package com.ialocal.android

import com.ialocal.android.model.GgufTransfer
import com.ialocal.android.model.ModelException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GgufTransferTest {
    private val gguf = "GGUFdatos de prueba".encodeToByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(gguf).joinToString("") { "%02x".format(it) }

    @Test fun acceptsMatchingHeaderSizeAndHash() {
        val target = ByteArrayOutputStream()
        GgufTransfer.copyVerified(gguf.inputStream(), target, hash.uppercase(), gguf.size.toLong()) {}
        assertArrayEquals(gguf, target.toByteArray())
    }

    @Test fun rejectsTruncatedFile() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyVerified(gguf.inputStream(), ByteArrayOutputStream(), hash, gguf.size.toLong() + 1) {}
        }
    }

    @Test fun rejectsIncorrectHash() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyVerified(gguf.inputStream(), ByteArrayOutputStream(), "0".repeat(64), null) {}
        }
    }

    @Test fun rejectsNonGgufDataAndInvalidSha() {
        assertThrows(ModelException::class.java) {
            GgufTransfer.copyVerified("HTML".byteInputStream(), ByteArrayOutputStream(), hash, null) {}
        }
        assertThrows(ModelException::class.java) { GgufTransfer.normalizedHash("../modelo.gguf") }
    }
}
