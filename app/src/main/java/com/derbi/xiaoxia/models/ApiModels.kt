package com.derbi.xiaoxia.models

// Request/Response Models
// 消息发送请求体 (参考 inputManager.js)
data class TextChatRequest(
    val reqMessage: String,
    val deepThinking: Boolean,
    val webSearch: Boolean,
    val userId: String = "U1001" // 参考 app.js 和 apiService.js 中的默认值
    // sessionId 应通过 Header 或其他方式传递
)

// 会话初始化请求体 (参考 apiService.js)
data class InitSessionRequest(
    val userId: String = "U1001" // 参考 app.js 和 apiService.js 中的默认值
)

// 删除会话请求体
data class DeleteConversationRequest(val sessionId: String)

// 流式响应数据块 (参考 apiService.js 中的 parseChunk 逻辑)
data class ChatChunk(
    val output: Output? = null,
    val usage: Usage? = null,
    val error: Error? = null
)
data class Output(
    val text: String? = null,
    val thoughts: List<Thought>? = null
)
data class Thought(
    val actionType: String? = null,
    val response: String? = null
)
data class Usage(
    val totalTokens: Int? = null
)
data class Error(
    val code: Int? = null,
    val message: String? = null
)
