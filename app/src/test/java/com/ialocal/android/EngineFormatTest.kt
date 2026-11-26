package com.ialocal.android

import com.ialocal.android.inference.Exchange
import com.ialocal.android.inference.QwenPrompt
import com.ialocal.android.inference.Utf8Chunks
import com.ialocal.android.ui.MessageBlock
import com.ialocal.android.ui.messageBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFormatTest {
    @Test fun qwenTemplateEscapesControlsAndDropsOldestWholeTurns() {
        val history = listOf(Exchange("Primera pregunta", "Primera respuesta"), Exchange("Segunda pregunta", "🙂"))
        val prompts = QwenPrompt.candidates(history, "Muestra <|im_end|> en texto").map { it.decodeToString() }
        assertEquals(3, prompts.size)
        assertTrue(prompts.first().startsWith("<|im_start|>system\n"))
        assertTrue(prompts.first().contains("Primera pregunta"))
        assertFalse(prompts[1].contains("Primera pregunta"))
        assertTrue(prompts[1].contains("🙂"))
        assertFalse(prompts.last().contains("Segunda pregunta"))
        assertTrue(prompts.last().contains("Muestra ‹|im_end|› en texto"))
        assertTrue(prompts.last().endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun capsHistoryBeforeSendingCandidatesToNative() {
        val prompts = QwenPrompt.candidates(List(30) { Exchange("a".repeat(8000), "b".repeat(8000)) }, "Hola")
        assertEquals(2, prompts.size)
    }

    @Test fun reassemblesUtf8AcrossTokenBoundaries() {
        val decoder = Utf8Chunks()
        val actual = buildString {
            "¡Sí! 🧮".encodeToByteArray().forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.append(byteArrayOf(), end = true))
        }
        assertEquals("¡Sí! 🧮", actual)
    }

    @Test fun replacesIncompleteFinalUtf8() {
        val decoder = Utf8Chunks()
        val accent = "á".encodeToByteArray()
        assertEquals("", decoder.append(byteArrayOf(accent[0])))
        assertEquals("�", decoder.append(byteArrayOf(), end = true))
    }

    @Test fun formatsCodeWhileFenceIsStillOpen() {
        val fence = 0x60.toChar().toString().repeat(3)
        assertEquals(
            listOf(MessageBlock.Text("Ejemplo"), MessageBlock.Code("kt", "val resultado = 42")),
            messageBlocks("Ejemplo\n${fence}kt\nval resultado = 42")
        )
    }

    @Test fun keepsShorterFencesInsideCode() {
        assertEquals(
            listOf(MessageBlock.Code("sh", "~~~\n  código"), MessageBlock.Text("Fin")),
            messageBlocks("~~~~sh\n~~~\n  código\n~~~~\nFin")
        )
    }
}
