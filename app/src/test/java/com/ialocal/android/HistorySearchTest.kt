package com.ialocal.android

import com.ialocal.android.chat.Chat
import com.ialocal.android.ui.searchChats
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySearchTest {
    @Test fun matchesTitlesWithoutAccentsAndPreservesHistoryOrder() {
        val chats = listOf(Chat(3, "Álgebra lineal", 30), Chat(2, "Código Kotlin", 20), Chat(1, "Ejercicios de álgebra", 10))
        assertEquals(listOf(chats[0], chats[2]), searchChats(chats, "  ALGEBRA "))
        assertEquals(chats, searchChats(chats, " "))
        assertEquals(emptyList<Chat>(), searchChats(chats, "Python"))
    }
}
