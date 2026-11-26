package com.ialocal.android.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ialocal.android.inference.Exchange

enum class Role { USER, ASSISTANT }
enum class MessageState { COMPLETE, GENERATING, STOPPED, INTERRUPTED, LIMITED, ERROR }

data class Message(
    val id: Long,
    val role: Role,
    val body: String,
    val state: MessageState
)
data class Chat(val id: Long, val title: String, val updatedAt: Long)
data class Turn(val chatId: Long, val answerId: Long, val userId: Long)

class ChatStore(context: Context, name: String = "chats.db") :
    SQLiteOpenHelper(context, name, null, 1) {

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chats (
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                role TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX messages_by_chat ON messages(chat_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Falta una migración de historial de $oldVersion a $newVersion")
    }

    fun recoverInterrupted() {
        writableDatabase.execSQL("UPDATE messages SET state='INTERRUPTED' WHERE state='GENERATING'")
    }

    fun chats(): List<Chat> = readableDatabase.rawQuery(
        "SELECT id, title, updated_at FROM chats ORDER BY updated_at DESC, id DESC", null
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(Chat(rows.getLong(0), rows.getString(1), rows.getLong(2)))
        }
    }

    fun messages(chatId: Long): List<Message> = readableDatabase.rawQuery(
        "SELECT id, role, body, state FROM messages WHERE chat_id=? ORDER BY id",
        arrayOf(chatId.toString())
    ).use(::readMessages)

    fun history(turn: Turn): List<Exchange> {
        val recent = readableDatabase.rawQuery(
            """SELECT id, role, body, state FROM messages
               WHERE chat_id=? AND id<? ORDER BY id DESC LIMIT 24""",
            arrayOf(turn.chatId.toString(), turn.userId.toString())
        ).use(::readMessages).asReversed()
        return recent.zipWithNext().mapNotNull { (user, answer) ->
            if (user.role == Role.USER && answer.role == Role.ASSISTANT &&
                answer.state in setOf(MessageState.COMPLETE, MessageState.LIMITED) &&
                answer.body.isNotBlank()
            ) Exchange(user.body, answer.body) else null
        }
    }

    fun beginTurn(chatId: Long?, body: String): Turn {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val id = chatId ?: db.insertOrThrow("chats", null, ContentValues().apply {
                val title = body.replace(Regex("\\s+"), " ").trim()
                put("title", title.take(52) + if (title.length > 52) "…" else "")
                put("updated_at", now)
            })
            val userId = insertMessage(db, id, Role.USER, body, MessageState.COMPLETE)
            val answerId = insertMessage(db, id, Role.ASSISTANT, "", MessageState.GENERATING)
            db.execSQL("UPDATE chats SET updated_at=? WHERE id=?", arrayOf(now, id))
            db.setTransactionSuccessful()
            return Turn(id, answerId, userId)
        } finally {
            db.endTransaction()
        }
    }

    fun saveAnswer(id: Long, body: String, state: MessageState) {
        writableDatabase.update("messages", ContentValues().apply {
            put("body", body)
            put("state", state.name)
        }, "id=? AND state='GENERATING'", arrayOf(id.toString()))
    }

    fun rename(chatId: Long, title: String) {
        writableDatabase.update("chats", ContentValues().apply {
            put("title", title)
        }, "id=?", arrayOf(chatId.toString()))
    }

    fun delete(chatId: Long) {
        writableDatabase.delete("chats", "id=?", arrayOf(chatId.toString()))
    }

    private fun insertMessage(
        db: SQLiteDatabase, chatId: Long, role: Role, body: String, state: MessageState
    ): Long = db.insertOrThrow("messages", null, ContentValues().apply {
        put("chat_id", chatId)
        put("role", role.name)
        put("body", body)
        put("state", state.name)
    })

    private fun readMessages(rows: Cursor): List<Message> = buildList {
        while (rows.moveToNext()) add(Message(
            rows.getLong(0), Role.valueOf(rows.getString(1)), rows.getString(2),
            MessageState.valueOf(rows.getString(3))
        ))
    }
}
