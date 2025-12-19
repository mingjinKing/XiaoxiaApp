// SessionListResponse.kt
package com.derbi.xiaoxia.models

import com.google.gson.annotations.SerializedName
import java.util.*

/**
 * 会话列表响应
 * 对应 conversation.js 中的 fetchConversations 接口
 */
data class SessionListResponse(
    @SerializedName("content")
    val sessions: List<Conversation> = emptyList(),

    @SerializedName("totalElements")
    val totalElements: Int = 0,

    @SerializedName("totalPages")
    val totalPages: Int = 0,

    @SerializedName("last")
    val last: Boolean = true,

    @SerializedName("first")
    val first: Boolean = true,

    @SerializedName("size")
    val size: Int = 0,

    @SerializedName("number")
    val number: Int = 0,

    @SerializedName("numberOfElements")
    val numberOfElements: Int = 0,

    @SerializedName("empty")
    val empty: Boolean = true
) {
    companion object {
        val EMPTY = SessionListResponse()
    }
}

/**
 * 简化的会话项
 * 用于对话列表显示
 */
data class ConversationItem(
    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("lastMessageTime")
    val lastMessageTime: Long,

    @SerializedName("summary")
    val summary: String? = null,

    @SerializedName("messageCount")
    val messageCount: Int = 0,

    @SerializedName("isExpired")
    val isExpired: Boolean = false
)
