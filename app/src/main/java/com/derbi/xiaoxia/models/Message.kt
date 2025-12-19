package com.derbi.xiaoxia.models

sealed class Message {
    data class UserMessage(val id: String, val content: String, val timestamp: Long) : Message()
    data class AIMessage(
        val id: String,
        var content: String,
        var reasoningContent: String = "",
        var showReasoning: Boolean = false,
        var isReceivingReasoning: Boolean = false,
        val showDisclaimer: Boolean,
        val timestamp: Long,
        var isReceiving: Boolean = true
    ) : Message()
    object LoadingMessage : Message()

    // 添加 role 属性
    val role: String
        get() = when (this) {
            is UserMessage -> "user"
            is AIMessage -> "ai"
            LoadingMessage -> "loading"
        }

    // 添加 isStreaming 属性
    val isStreaming: Boolean
        get() = when (this) {
            is AIMessage -> isReceiving
            else -> false
        }
}
