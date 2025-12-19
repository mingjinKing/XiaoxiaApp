// ConversationTurn.kt
package com.derbi.xiaoxia.models

import com.google.gson.annotations.SerializedName
import java.util.*

/**
 * 对话轮次
 * 对应 conversation.js 中的 fetchConversationTurns 接口
 */
data class ConversationTurn(
    @SerializedName("id")
    val id: String,

    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("turnNumber")
    val turnNumber: Int = 0,

    @SerializedName("userMessage")
    val userMessage: String,

    @SerializedName("assistantResponse")
    val assistantResponse: String,

    @SerializedName("reasoningContent")
    val reasoningContent: String? = null,

    @SerializedName("createdAt")
    val createdAt: Date,

    @SerializedName("updatedAt")
    val updatedAt: Date,

    @SerializedName("deepThinkingEnabled")
    val deepThinkingEnabled: Boolean = false,

    @SerializedName("webSearchEnabled")
    val webSearchEnabled: Boolean = false,

    // 思考过程相关的字段（如果后端返回）
    @SerializedName("thoughts")
    val thoughts: List<Thought>? = null,

    @SerializedName("metadata")
    val metadata: Map<String, Any>? = null
) {
    companion object {
        val EMPTY = ConversationTurn(
            id = "",
            sessionId = "",
            userMessage = "",
            assistantResponse = "",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}

/**
 * 对话轮次列表响应
 * 对应 conversation.js 中的 turns 数据结构
 */
data class ConversationTurnsResponse(
    @SerializedName("turns")
    val turns: List<ConversationTurn> = emptyList(),

    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("totalTurns")
    val totalTurns: Int = 0,

    @SerializedName("page")
    val page: Int = 0,

    @SerializedName("size")
    val size: Int = 0,

    @SerializedName("hasNext")
    val hasNext: Boolean = false
) {
    companion object {
        val EMPTY = ConversationTurnsResponse(
            sessionId = "",
            turns = emptyList()
        )
    }
}
