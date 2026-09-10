package com.example.ialocal.data

import android.content.Context

/**
 * Keeps the chat the user was actively using so reopening the app returns to it.
 * This is intentionally tiny and synchronous because it is read before Compose navigation starts.
 */
class ChatSessionRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val activeConversationId: String?
        get() = prefs.getString(KEY_ACTIVE_CONVERSATION, null)?.takeIf { it.isNotBlank() }

    fun setActiveConversation(id: String) {
        prefs.edit().putString(KEY_ACTIVE_CONVERSATION, id).apply()
    }

    fun clearActiveConversation() {
        prefs.edit().remove(KEY_ACTIVE_CONVERSATION).apply()
    }

    companion object {
        private const val PREFS_NAME = "chat_session"
        private const val KEY_ACTIVE_CONVERSATION = "active_conversation_id"
    }
}
