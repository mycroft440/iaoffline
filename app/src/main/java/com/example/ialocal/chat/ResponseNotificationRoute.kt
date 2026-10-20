package com.example.ialocal.chat

import android.content.Context
import android.content.Intent
import com.example.ialocal.MainActivity

/** Direct notification navigation, including after the Android process has been recreated. */
internal object ResponseNotificationRoute {
    const val EXTRA_KIND = "com.example.ialocal.extra.RESPONSE_KIND"
    const val EXTRA_CONVERSATION_ID = "com.example.ialocal.extra.RESPONSE_CONVERSATION_ID"
    const val OFFLINE = "offline"
    const val ONLINE = "online"

    fun openConversation(context: Context, kind: String, conversationId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }

    fun parse(intent: Intent?): Destination? {
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: return null
        val id = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() } ?: return null
        return if (kind == OFFLINE || kind == ONLINE) Destination(kind, id) else null
    }

    data class Destination(val kind: String, val conversationId: String)
}
