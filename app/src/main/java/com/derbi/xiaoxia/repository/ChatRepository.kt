// ChatRepository.kt
package com.derbi.xiaoxia.repository

import android.util.Log
import com.derbi.xiaoxia.models.*
import com.derbi.xiaoxia.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(
    private val apiService: ApiService,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "ChatRepository"
    }

    // 初始化会话
    suspend fun initSession(): String {
        return withContext(Dispatchers.IO) {
            try {
                val request = InitSessionRequest(userId = "user_id_placeholder")
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

    // 发送消息 - 支持流式响应
    fun sendMessage(
        message: String,
        deepThinking: Boolean = false,
        webSearch: Boolean = false
    ): Flow<MessageResponse> = flow {
        try {
            Log.d(TAG, "开始发送消息: ${message.take(50)}...")

            var sessionId = sessionManager.getSessionId()
            Log.d("ChatRepository", "sendMessage sessionId: $sessionId")
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

            val responseBody = apiService.sendMessage(request, sessionId)

            Log.d(TAG, "收到流式响应")

            // 使用缓冲读取器处理流式响应 - 修复类型推断问题
            val inputStream = responseBody.body()?.byteStream();
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            try {
                var remainingLine = ""
                var lineCount = 0
                var totalBytes = 0
                val buffer = CharArray(8192)

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
                            Log.d(TAG, "处理第${lineCount}行，内容长度=${it.content?.length ?: 0}, " +
                                    "思考长度=${it.reasoningContent?.length ?: 0}, " +
                                    "替换模式=${it.isReplaceMode}")
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
                        Log.d(TAG, "处理最后一行")
                        emit(it)
                    }
                }
            } finally {
                reader.close()
                inputStream?.close()
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
            Log.v(TAG, "解析完整JSON: $json")

            // 获取output字段
            val output = json.optJSONObject("output")
            if (output != null) {
                Log.v(TAG, "output字段内容: $output")

                var content: String? = null
                var reasoningContent: String? = null
                var isReplaceMode: Boolean? = null

                // 格式1：从output.text获取内容（追加模式）
                if (output.has("text")) {
                    val text = output.optString("text", "").trim()
                    // 修复：检查是否是字符串"null"
                    if (text.isNotEmpty() && text.lowercase() != "null") {
                        content = text
                        isReplaceMode = false // 追加模式
                        Log.v(TAG, "格式1(追加模式): ${text.take(50)}...")
                    } else {
                        Log.v(TAG, "格式1: 跳过空或'null'字符串")
                    }
                }

                // 格式2：从output.choices[0].message获取内容（替换模式）
                if (output.has("choices")) {
                    val choices = output.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.optJSONObject("message")

                        if (message != null) {
                            // 获取content字段
                            val textContent = message.optString("content", "").trim()
                            // 修复：检查是否是字符串"null"
                            if (textContent.isNotEmpty() && textContent.lowercase() != "null") {
                                content = textContent
                                isReplaceMode = true // 替换模式
                                Log.v(TAG, "格式2(替换模式)内容: ${textContent.take(50)}...")
                            } else {
                                Log.v(TAG, "格式2: content是空或'null'字符串")
                            }

                            // 获取reasoningContent字段
                            val reasoning = message.optString("reasoningContent", "").trim()
                            // 修复：检查是否是字符串"null"
                            if (reasoning.isNotEmpty() && reasoning.lowercase() != "null") {
                                reasoningContent = reasoning
                                isReplaceMode = true // 替换模式
                                Log.v(TAG, "格式2思考过程: ${reasoning.take(50)}...")
                            } else {
                                Log.v(TAG, "格式2: reasoning是空或'null'字符串")
                            }
                        }
                    }
                }

                // 旧格式：从output.thoughts获取思考内容
                if (output.has("thoughts")) {
                    val thoughts = output.optJSONArray("thoughts")
                    if (thoughts != null && thoughts.length() > 0) {
                        var combinedReasoning = ""
                        for (i in 0 until thoughts.length()) {
                            val thought = thoughts.getJSONObject(i)
                            val actionType = thought.optString("actionType", "")
                            val response = thought.optString("response", "").trim()

                            if (actionType == "reasoning" && response.isNotEmpty() && response.lowercase() != "null") {
                                combinedReasoning += response
                            }
                        }

                        if (combinedReasoning.isNotEmpty()) {
                            reasoningContent = combinedReasoning
                            Log.v(TAG, "旧格式思考过程: ${combinedReasoning.take(50)}...")
                        }
                    }
                }

                // 过滤掉只有usage信息的空数据包
                val hasRealContent = content?.isNotBlank() == true && content.lowercase() != "null"
                val hasRealReasoning = reasoningContent?.isNotBlank() == true && reasoningContent.lowercase() != "null"

                if (!hasRealContent && !hasRealReasoning) {
                    Log.v(TAG, "空数据包，跳过 - content: '$content', reasoning: '$reasoningContent'")
                    return null
                }

                Log.v(TAG, "返回有效响应: content='${content?.take(50)}...', reasoning='${reasoningContent?.take(50)}...'")
                return MessageResponse(
                    content = content,
                    reasoningContent = reasoningContent,
                    isReplaceMode = isReplaceMode,
                    timestamp = System.currentTimeMillis()
                )
            }

            // 如果都没有内容，返回null
            Log.v(TAG, "没有output字段的空响应")
            return null

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE data: ${data.take(200)}...", e)
            return null
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

    // 获取会话列表
    suspend fun getConversations(page: Int = 0, pageSize: Int = 20): List<Conversation> {
        return withContext(Dispatchers.IO) {
            try {
                val sessionId = sessionManager.getSessionId()
                Log.d("ChatRepository", "getConversations sessionId: $sessionId")
                val response = apiService.getSessions(page, pageSize)
                convertToConversationList(response)
            } catch (e: Exception) {
                Log.e("ChatRepository", "getConversations error", e)
                emptyList()
            }
        }
    }

    private fun convertToConversationList(response: SessionListResponse): List<Conversation> {
        return response.sessions.map { session ->
            Conversation(
                id = session.id,
                title = session.title,
                summary = session.summary ?: "暂无摘要",
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = session.messageCount,
                isExpired = session.isExpired,
                isSelected = false
            )
        }
    }

    // 获取会话详情
    suspend fun getConversationDetail(sessionId: String): List<ConversationTurn> {
        return withContext(Dispatchers.IO) {
            try {
                val currentSessionId = sessionManager.getSessionId() ?: ""
                val response = apiService.getConversationTurns(sessionId, currentSessionId)
                response.turns
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
                response.isSuccessful
            } catch (e: Exception) {
                Log.e("ChatRepository", "loadSessionToCache error", e)
                false
            }
        }
    }
}

// 数据类 - 添加isReplaceMode字段
data class MessageResponse(
    val content: String?,
    val reasoningContent: String? = null,
    val isReplaceMode: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
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