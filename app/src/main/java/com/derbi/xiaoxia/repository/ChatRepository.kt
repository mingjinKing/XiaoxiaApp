// ChatRepository.kt
package com.derbi.xiaoxia.repository

import android.util.Log
import com.derbi.xiaoxia.models.*
import com.derbi.xiaoxia.network.ApiService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ChatRepository(
    private val apiService: ApiService,
    private val sessionManager: SessionManager
) {

    // 初始化会话
    suspend fun initSession(): String {
        return withContext(Dispatchers.IO) {
            try {
                val request = InitSessionRequest(userId = "user_id_placeholder") // TODO: Replace with actual user ID
                val response = apiService.initSession(request)
                val newSessionId = response.headers()["X-Session-Id"]
                if (!newSessionId.isNullOrEmpty()) {
                    sessionManager.saveSessionId(newSessionId)
                    newSessionId
                } else {
                    throw Exception("会话初始化失败：未获取到sessionId")
                }
            } catch (e: Exception) {
                Log.e("ChatRepository", "initSession error", e)
                val tempSessionId = "temp_${System.currentTimeMillis()}"
                sessionManager.saveSessionId(tempSessionId)
                tempSessionId
            }
        }
    }

    // 在 ChatRepository 类中添加日志
    companion object {
        private const val TAG = "ChatRepository"
    }

    // 优化 sendMessage 方法，添加更好的流式处理
    fun sendMessage(
        message: String,
        deepThinking: Boolean = false,
        webSearch: Boolean = false
    ): Flow<MessageResponse> = flow {
        try {
            Log.d(TAG, "开始发送消息: ${message.take(50)}...")

            var sessionId = sessionManager.getSessionId()
            // 多轮对话应该使用同一个 Session ID
            if (sessionId.isBlank()) {
                sessionId = initSession()
                Log.d(TAG, "初始化新 Session: $sessionId")
            } else {
                Log.d(TAG, "使用现有 Session: $sessionId")
            }

            val request = TextChatRequest(
                reqMessage = message,
                deepThinking = deepThinking,
                webSearch = webSearch
            )

            // 修改这里：处理 Response<ResponseBody>
            Log.d("ChatRepository", "开始发送消息, sessionId: $sessionId")
            val response = apiService.sendMessage(request, sessionId)
            
            if (!response.isSuccessful) {
                val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Server error: ${response.code()} $errorMsg")
                throw Exception("服务器错误 (${response.code()})")
            }

            val responseBody = response.body() ?: throw Exception("响应正文为空")

            Log.d(TAG, "收到流式响应")

            // 使用缓冲读取器处理流式响应
            responseBody.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192) // 增大缓冲区
                var remainingLine = ""
                var lineCount = 0
                var totalBytes = 0

                while (true) {
                    val charsRead = reader.read(buffer)
                    if (charsRead == -1) {
                        Log.d(TAG, "流结束，总共读取${lineCount}行，${totalBytes}字节")
                        break
                    }

                    totalBytes += charsRead
                    val chunk = remainingLine + String(buffer, 0, charsRead)
                    val lines = chunk.split("\n")

                    Log.v(TAG, "读取到${charsRead}字节，分割为${lines.size}行")

                    // 处理所有完整的行
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i]
                        lineCount++
                        processLine(line)?.let {
                            Log.d(TAG, "处理第${lineCount}行，内容长度=${it.content.length}, 思考长度=${it.reasoningContent?.length ?: 0}")
                            emit(it)
                        }
                    }

                    // 保存不完整的行到下一次处理
                    remainingLine = lines.last()
                    if (remainingLine.isNotEmpty()) {
                        Log.v(TAG, "保存未完成行: ${remainingLine.take(50)}...")
                    }
                }

                // 处理最后一行
                if (remainingLine.isNotEmpty()) {
                    processLine(remainingLine)?.let {
                        Log.d(TAG, "处理最后一行，内容长度=${it.content.length}")
                        emit(it)
                    }
                }
            }

            Log.d(TAG, "消息处理完成")

        } catch (e: Exception) {
            Log.e(TAG, "sendMessage stream error", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun processLine(line: String): MessageResponse? {
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) return null

        if (trimmedLine.startsWith("data:")) {
            val data = trimmedLine.substring(5).trim()
            if (data == "[DONE]") {
                Log.d(TAG, "收到[DONE]标记")
                return null
            }

            try {
                Log.v(TAG, "解析SSE数据: ${data.take(100)}...")
                return parseSseData(data)
            } catch (e: Exception) {
                Log.e(TAG, "SSE JSON parsing error for data: $data", e)
            }
        } else {
            Log.v(TAG, "非SSE格式行: ${trimmedLine.take(100)}...")
        }

        return null
    }

    private fun parseSseData(data: String): MessageResponse? {
        try {
            if (data.isEmpty()) return null

            val json = JSONObject(data)

            // 检查是否有 usage 字段，这是新格式的特征
            val hasUsage = json.has("usage")
            val hasRequestId = json.has("requestId")

            if (hasUsage && hasRequestId) {
                // 这是新的报文格式
                return parseNewFormat(json)
            } else {
                // 这是旧的报文格式
                return parseOldFormat(json)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE data: ${data.take(200)}...", e)
            return MessageResponse(content = data.takeIf { it.length < 1000 } ?: "")
        }
    }

    // ChatRepository.kt - 修改 parseNewFormat 方法
    private fun parseNewFormat(json: JSONObject): MessageResponse? {
        try {
            val output = json.optJSONObject("output")
            if (output == null) {
                Log.v(TAG, "output字段为空")
                return MessageResponse(content = "")
            }

            val choices = output.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.v(TAG, "choices字段为空或空数组")
                return MessageResponse(content = "")
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            if (message == null) {
                Log.v(TAG, "message字段为空")
                return MessageResponse(content = "")
            }

            // 从新位置获取内容
            val content = message.optString("content", "").trim()
            val reasoningContent = message.optString("reasoningContent", "").trim()

            Log.v(TAG, "解析新格式: 内容长度=${content.length}, 思考长度=${reasoningContent.length}")

            // 新增：检查是否是最终消息
            val finishReason = firstChoice.optString("finishReason", null)
            val isFinal = finishReason == "stop" || finishReason == "length" || finishReason == "tool_calls"

            // 返回响应
            return MessageResponse(
                content = content,
                reasoningContent = if (reasoningContent.isNotBlank()) reasoningContent else null,
                // 新增：标记是否是最终消息
                isFinal = isFinal,
                // 可选：传递 finishReason 用于调试
                finishReason = finishReason
            )

        } catch (e: Exception) {
            Log.e(TAG, "解析新格式失败", e)
            return MessageResponse(content = "")
        }
    }

    private fun parseOldFormat(json: JSONObject): MessageResponse? {
        try {
            // 优先处理 output 字段
            val output = json.optJSONObject("output")
            if (output != null) {
                var content = ""
                var reasoningContent = ""

                // 处理 choices 数组
                val choices = output.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")

                    if (message != null) {
                        // 获取 content 字段
                        val textContent = message.optString("content", "").trim()
                        if (textContent.isNotEmpty()) {
                            content = textContent
                            Log.v(TAG, "解析到内容: ${textContent.take(50)}...")
                        }

                        // 获取 reasoningContent 字段
                        val reasoning = message.optString("reasoningContent", "").trim()
                        if (reasoning.isNotEmpty()) {
                            reasoningContent = reasoning
                            Log.v(TAG, "解析到思考过程: ${reasoning.take(50)}...")
                        }
                    }
                }

                // 返回响应（即使是空内容也要返回，以便处理流式中的中间状态）
                return MessageResponse(
                    content = content,
                    reasoningContent = if (reasoningContent.isNotBlank()) reasoningContent else null
                )
            }

            // 直接文本响应（兼容旧格式）
            val text = json.optString("text", "").trim()
            if (text.isNotEmpty()) {
                Log.v(TAG, "直接文本响应: ${text.take(50)}...")
                return MessageResponse(content = text)
            }

            // 如果都没有内容，返回空的响应以便流式处理继续
            Log.v(TAG, "空响应数据")
            return MessageResponse(content = "")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse old format data", e)
            return MessageResponse(content = "")
        }
    }

    // 验证会话
    suspend fun validateSession(sessionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.validateSession(sessionId)
                response.isValid && response.sessionId?.isNotEmpty() == true
            } catch (e: Exception) {
                Log.e("ChatRepository", "validateSession error", e)
                false
            }
        }
    }

    // 获取会话列表 - 修改返回类型为 List<Conversation>
    suspend fun getConversations(page: Int = 0, pageSize: Int = 20): List<Conversation> {
        return withContext(Dispatchers.IO) {
            try {
                val sessionId = sessionManager.getSessionId()
                Log.d("ChatRepository", "getConversations sessionId: $sessionId")
                val response = apiService.getSessions(page, pageSize)

                // 转换 SessionListResponse 为 List<Conversation>
                convertToConversationList(response)
            } catch (e: Exception) {
                Log.e("ChatRepository", "getConversations error", e)
                emptyList()
            }
        }
    }

    // 辅助方法：将 SessionListResponse 转换为 List<Conversation>
    private fun convertToConversationList(response: SessionListResponse): List<Conversation> {
        return response.sessions.map { session ->
            // 使用 Conversation 构造函数，直接传入需要的字段
            Conversation(
                id = session.id,
                title = session.title,
                summary = session.summary ?: "暂无摘要",
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = session.messageCount,
                isExpired = session.isExpired,
                isSelected = false // 默认未选中
            )
        }
    }

    // 获取会话详情
    suspend fun getConversationDetail(sessionId: String): List<ConversationTurn> {
        return withContext(Dispatchers.IO) {
            try {
                val currentSessionId = sessionManager.getSessionId() ?: ""
                val response = apiService.getConversationTurns(sessionId, currentSessionId)
                
                // 加载完成后，将当前 SessionId 设置为加载历史对话的 SessionId
                if (sessionId.isNotEmpty()) {
                    sessionManager.saveSessionId(sessionId)
                    Log.d(TAG, "加载历史对话详情成功，更新当前 SessionId: $sessionId")
                }
                
                response.turns  // 从响应中提取 turns 字段
            } catch (e: Exception) {
                Log.e("ChatRepository", "getConversationDetail error", e)
                emptyList()
            }
        }
    }

    // 删除会话
    suspend fun deleteConversation(sessionId: String) {
        withContext(Dispatchers.IO) {
            try {
                val currentSessionId = sessionManager.getSessionId() ?: ""
                apiService.deleteConversation(sessionId, currentSessionId)
            } catch (e: Exception) {
                Log.e("ChatRepository", "deleteConversation error", e)
                throw e
            }
        }
    }

    // 加载会话到缓存
    suspend fun loadSessionToCache(sessionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentSessionId = sessionManager.getSessionId() ?: ""
                val response = apiService.loadSessionToCache(sessionId, currentSessionId)
                
                // 加载完成后，将当前 SessionId 设置为加载历史对话的 SessionId
                if (response.isSuccessful && sessionId.isNotEmpty()) {
                    sessionManager.saveSessionId(sessionId)
                    Log.d(TAG, "加载历史对话到缓存成功，更新当前 SessionId: $sessionId")
                }
                
                response.isSuccessful
            } catch (e: Exception) {
                Log.e("ChatRepository", "loadSessionToCache error", e)
                false
            }
        }
    }
}

// 数据类
data class MessageResponse(
    val content: String,
    val reasoningContent: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // 新增字段
    val isFinal: Boolean = false,
    val finishReason: String? = null
)

data class InitSessionResponse(
    val sessionId: String,
    val userId: String,
    val timestamp: Long
)

data class ValidateSessionResponse(
    val isValid: Boolean,
    val sessionId: String? = null,
    val message: String? = null
)