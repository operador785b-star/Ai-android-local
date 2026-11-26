package com.ialocal.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ialocal.android.chat.ChatStore
import com.ialocal.android.chat.MessageState
import com.ialocal.android.inference.Exchange
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStoreTest {
    @Test fun persistsHistoryRecoversInterruptedAndCascadesDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        try {
            val first = ChatStore(context, name).use { store ->
                val turn = store.beginTurn(null, "¿Cuánto es 6 × 7?")
                store.saveAnswer(turn.answerId, "42", MessageState.COMPLETE)
                store.rename(turn.chatId, "Matemáticas")
                turn
            }
            ChatStore(context, name).use { store ->
                assertEquals("Matemáticas", store.chats().single().title)
                val next = store.beginTurn(first.chatId, "¿Y 8 × 7?")
                assertEquals(listOf(Exchange("¿Cuánto es 6 × 7?", "42")), store.history(next))
                store.saveAnswer(next.answerId, "Cincuenta", MessageState.GENERATING)
            }
            ChatStore(context, name).use { store ->
                store.recoverInterrupted()
                val recovered = store.messages(first.chatId).last()
                assertEquals(MessageState.INTERRUPTED, recovered.state)
                assertEquals("Cincuenta", recovered.body)
                store.delete(first.chatId)
                assertTrue(store.messages(first.chatId).isEmpty())
                assertTrue(store.chats().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
