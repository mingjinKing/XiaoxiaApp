// ModelUtils.kt
package com.derbi.xiaoxia.utils

import com.derbi.xiaoxia.models.*
import java.util.Date

object ModelUtils {

    /**
     * 将 ConversationItem 转换为 Conversation
     */
    fun ConversationItem.toConversation(): Conversation {
        return Conversation(
            id = this.sessionId,
            title = this.title,
            summary = this.summary ?: "",
            createdAt = Date(this.lastMessageTime),
            updatedAt = Date(this.lastMessageTime),
            messageCount = this.messageCount,
            isExpired = this.isExpired
        )
    }

    /**
     * 将 ConversationTurn 转换为 ChatMessage
     */
    fun ConversationTurn.toChatMessage(): ChatMessage {
        return ChatMessage(
            id = this.id,
            sessionId = this.sessionId,
            turnNumber = this.turnNumber,
            userMessage = this.userMessage,
            assistantResponse = this.assistantResponse,
            reasoningContent = this.reasoningContent,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
            deepThinkingEnabled = this.deepThinkingEnabled,
            webSearchEnabled = this.webSearchEnabled
        )
    }

    /**
     * 格式化时间显示
     */
    fun formatConversationTime(date: Date): String {
        val now = Date()
        val diff = now.time - date.time

        return when {
            diff < 60 * 1000 -> "刚刚"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}分钟前"
            diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}小时前"
            diff < 30 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)}天前"
            diff < 365 * 24 * 60 * 60 * 1000 -> "${diff / (30 * 24 * 60 * 60 * 1000)}个月前"
            else -> "${diff / (365 * 24 * 60 * 60 * 1000)}年前"
        }
    }
}

/**
 * 聊天消息（UI 层使用）
 */
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val turnNumber: Int,
    val userMessage: String,
    val assistantResponse: String,
    val reasoningContent: String?,
    val createdAt: Date,
    val updatedAt: Date,
    val deepThinkingEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val isExpanded: Boolean = false
)