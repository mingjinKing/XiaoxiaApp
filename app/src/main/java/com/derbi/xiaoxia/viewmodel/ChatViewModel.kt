package com.derbi.xiaoxia.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.derbi.xiaoxia.models.Conversation
import com.derbi.xiaoxia.models.ConversationTurn
import com.derbi.xiaoxia.models.Message
import com.derbi.xiaoxia.repository.ChatRepository
import com.derbi.xiaoxia.repository.SessionManager
import com.derbi.xiaoxia.utils.SmoothStreamBuffer
import com.derbi.xiaoxia.utils.SpeedControl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

class ChatViewModel(
    private val repository: ChatRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    // 添加调试标签
    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()

    private val _conversationsState = MutableStateFlow(ConversationsState())
    val conversationsState: StateFlow<ConversationsState> = _conversationsState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UIEvent>()
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    // 在 ChatViewModel 类中添加状态管理变量
    private var currentStreamJob: Job? = null

    // 添加平滑输出管理器
    private var smoothBuffer: SmoothStreamBuffer? = null
    private var currentAiMessageIndex: Int = -1
    private var currentAiMessage: Message.AIMessage? = null


    sealed class UIEvent {
        data class ShowMessage(val message: String) : UIEvent()
        data class ShowError(val error: String) : UIEvent()
        object NavigateBack : UIEvent()
    }

    private fun resetStreamState() {
        Log.d(TAG, "重置流状态")
        currentStreamJob?.cancel()
        smoothBuffer?.clearAll()
        currentStreamJob = null
        smoothBuffer = null
        currentAiMessage = null
        currentAiMessageIndex = -1
    }

    fun initializeApp() {
        viewModelScope.launch {
            // 检查当前是否有有效的 Session ID
            val currentSessionId = sessionManager.getSessionId()

            if (currentSessionId.isEmpty()) {
                // 只有在没有 Session ID 时才初始化
                initSession()
            } else {
                // 如果有 Session ID，验证它是否有效
                val isValid = repository.validateSession(currentSessionId)
                if (!isValid) {
                    // 如果无效，重新初始化
                    initSession()
                } else {
                    Log.d(TAG, "使用现有有效 Session: $currentSessionId")
                }
            }
        }
    }

    private fun initSession() {
        viewModelScope.launch {
            _chatState.update { it.copy(isLoading = true) }
            try {
                val sessionId = repository.initSession()
                _chatState.update { it.copy(sessionId = sessionId, isLoading = false) }
                Log.d(TAG, "会话初始化成功: $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "会话初始化失败", e)
                _chatState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun updateDeepThinking(enabled: Boolean) {
        Log.d(TAG, "更新深度思考: $enabled")
        _chatState.update { it.copy(deepThinkingEnabled = enabled) }
    }

    fun updateWebSearch(enabled: Boolean) {
        Log.d(TAG, "更新网络搜索: $enabled")
        _chatState.update { it.copy(webSearchEnabled = enabled) }
    }

    fun sendMessage(message: String) {
        Log.d(TAG, "开始发送消息: ${message.take(50)}...")

        // 1. 确保取消之前的流式请求并清理所有状态
        cancelCurrentMessage()

        viewModelScope.launch {
            // 2. 先确保状态完全重置
            _chatState.update { it.copy(isSending = true) }

            // 创建用户消息
            val userMessage = Message.UserMessage(
                id = generateMessageId(),
                content = message,
                timestamp = System.currentTimeMillis()
            )

            // 更新状态
            _chatState.update {
                it.copy(
                    messages = it.messages + userMessage,
                    showWelcome = false
                )
            }

            // 添加加载消息
            val loadingMessage = Message.LoadingMessage
            _chatState.update { it.copy(messages = it.messages + loadingMessage) }
            Log.d(TAG, "已添加用户消息和加载消息")

            // 创建唯一的AI消息ID
            val aiMessageId = generateMessageId()
            Log.d(TAG, "创建AI消息ID: $aiMessageId")

            // 用于跟踪流式响应完成状态
            var isStreamCompleted = false

            try {
                // 3. 确保重新初始化平滑缓冲区
                smoothBuffer = SmoothStreamBuffer(
                    scope = viewModelScope
                )
                Log.d(TAG, "初始化平滑缓冲区")

                // 修改平滑输出回调部分：
                smoothBuffer?.startSmoothOutput { output ->
                    if (output.text.isNotEmpty() || output.reasoning.isNotEmpty() || output.isFinal) {
                        _chatState.update { currentState ->
                            val currentMessages = currentState.messages.toMutableList()

                            // 移除loading消息（如果是第一次输出）
                            val loadingMessageIndex = currentMessages.indexOfFirst { it is Message.LoadingMessage }
                            if (loadingMessageIndex != -1) {
                                currentMessages.removeAt(loadingMessageIndex)
                            }

                            // 查找或创建AI消息
                            val existingAiMessageIndex = currentMessages.indexOfFirst {
                                it is Message.AIMessage && it.id == aiMessageId
                            }

                            if (existingAiMessageIndex != -1) {
                                // 更新现有的AI消息
                                val existingMessage = currentMessages[existingAiMessageIndex] as Message.AIMessage
                                currentMessages[existingAiMessageIndex] = existingMessage.copy(
                                    content = existingMessage.content + output.text,
                                    reasoningContent = (existingMessage.reasoningContent ?: "") + output.reasoning,
                                    showReasoning = currentState.deepThinkingEnabled &&
                                            ((existingMessage.reasoningContent?.isNotEmpty() == true) ||
                                                    output.reasoning.isNotEmpty()),
                                    isReceiving = !output.isFinal
                                )
                            } else {
                                // 创建新的AI消息
                                val aiMessage = Message.AIMessage(
                                    id = aiMessageId,
                                    content = output.text,
                                    reasoningContent = output.reasoning,
                                    showReasoning = currentState.deepThinkingEnabled,
                                    isReceivingReasoning = currentState.deepThinkingEnabled,
                                    showDisclaimer = true,
                                    timestamp = System.currentTimeMillis(),
                                    isReceiving = !output.isFinal
                                )
                                currentMessages.add(aiMessage)
                            }

                            currentState.copy(messages = currentMessages)
                        }
                    }
                }

                currentStreamJob = launch {
                    var responseCount = 0
                    var latestFullContent = ""
                    var latestFullReasoning = ""

                    // 添加变量跟踪思考内容是否完成
                    var hasReasoningContent = false
                    var reasoningCompleteInResponse = false

                    Log.d(TAG, "开始收集流式响应")

                    repository.sendMessage(
                        message = message,
                        deepThinking = _chatState.value.deepThinkingEnabled,
                        webSearch = _chatState.value.webSearchEnabled
                    ).collect { response ->
                        responseCount++

                        val currentContent = response.content
                        val currentReasoning = response.reasoningContent ?: ""

                        // 检查是否有思考内容
                        if (currentReasoning.isNotEmpty()) {
                            hasReasoningContent = true
                            latestFullReasoning = currentReasoning
                        }

                        // 保存最新完整内容
                        if (currentContent.isNotEmpty()) {
                            latestFullContent = currentContent
                        }

                        Log.v(TAG, "收到响应[$responseCount]: 文本=${currentContent.length}字符, 思考=${currentReasoning.length}字符, isFinal=${response.isFinal}")

                        // 使用替换模式更新缓冲区
                        smoothBuffer?.replaceContent(
                            text = latestFullContent,
                            reasoning = latestFullReasoning,
                            isFinal = response.isFinal
                        )
                    }

                    Log.d(TAG, "流式响应收集完成: 总共${responseCount}个响应")
                    isStreamCompleted = true

                    // 流式响应完成，标记缓冲区完成
                    smoothBuffer?.finish()?.join()
                    Log.d(TAG, "缓冲区处理完全完成")
                }

                // 等待流式响应任务完成
                currentStreamJob?.join()
                Log.d(TAG, "流式响应任务完成")

            } catch (e: Exception) {
                Log.e(TAG, "发送消息时出错", e)

                // 处理错误
                smoothBuffer?.clearAll()
                _chatState.update { currentState ->
                    val errorMessages = currentState.messages.toMutableList()
                    errorMessages.removeAll { it is Message.LoadingMessage }

                    val errorMsg = when (e) {
                        is java.net.SocketTimeoutException -> "请求超时，请检查网络连接"
                        is java.net.ConnectException -> "无法连接到服务器"
                        is java.net.UnknownHostException -> "网络连接异常，请检查网络"
                        else -> "抱歉，处理您的请求时出错了。请稍后再试。\n\n错误信息：${e.message}"
                    }

                    Log.d(TAG, "错误信息: $errorMsg")

                    // 检查是否已经存在AI消息
                    val existingAiMessageIndex = errorMessages.indexOfFirst {
                        it is Message.AIMessage && it.id == aiMessageId
                    }

                    if (existingAiMessageIndex != -1) {
                        // 更新现有的错误消息
                        val existingMessage = errorMessages[existingAiMessageIndex] as Message.AIMessage
                        errorMessages[existingAiMessageIndex] = existingMessage.copy(
                            content = existingMessage.content + errorMsg,
                            isReceiving = false,
                            isReceivingReasoning = false
                        )
                        Log.d(TAG, "更新现有AI消息为错误消息")
                    } else {
                        // 创建新的错误消息
                        errorMessages.add(
                            Message.AIMessage(
                                id = aiMessageId,
                                content = errorMsg,
                                showDisclaimer = true,
                                timestamp = System.currentTimeMillis(),
                                isReceiving = false
                            )
                        )
                        Log.d(TAG, "创建新的错误消息")
                    }

                    currentState.copy(messages = errorMessages)
                }
            } finally {
                Log.d(TAG, "进入finally块，清理状态")

                try {
                    // 如果流式响应未完成，取消平滑输出
                    if (!isStreamCompleted) {
                        smoothBuffer?.cancel()
                    }

                    // 等待流式响应任务完成（但要有超时机制）
                    currentStreamJob?.join()

                    // 清理缓冲区
                    smoothBuffer?.clearAll()
                    smoothBuffer = null

                    // 重置状态
                    currentAiMessage = null
                    currentAiMessageIndex = -1

                    // 确保isSending设置为false
                    _chatState.update { it.copy(isSending = false) }
                    currentStreamJob = null

                    Log.d(TAG, "状态清理完成")
                } catch (e: Exception) {
                    Log.e(TAG, "清理状态时出错", e)
                    // 即使出错也要确保状态重置
                    _chatState.update { it.copy(isSending = false) }
                }
            }
        }
    }

    fun cancelCurrentMessage() {
        Log.d(TAG, "取消当前消息")
        currentStreamJob?.cancel()
        smoothBuffer?.clearAll()  // 改为 clearAll 而不是 cancel
        currentStreamJob = null
        smoothBuffer = null

        _chatState.update { currentState ->
            val messages = currentState.messages.toMutableList()

            // 移除loading消息
            messages.removeAll { it is Message.LoadingMessage }
            Log.d(TAG, "移除加载消息，剩余${messages.size}条消息")

            // 如果最后一条是AI消息且还在接收中，标记为完成
            val lastMessage = messages.lastOrNull()
            if (lastMessage is Message.AIMessage && lastMessage.isReceiving) {
                val index = messages.indexOf(lastMessage)
                messages[index] = lastMessage.copy(
                    isReceiving = false,
                    isReceivingReasoning = false,
                    content = if (lastMessage.content.isNotEmpty()) lastMessage.content else "已取消"
                )
                Log.d(TAG, "标记最后一条AI消息为取消状态，内容=${messages[index]}")
            }

            currentState.copy(messages = messages, isSending = false)
        }
    }


    fun fetchConversations(refresh: Boolean = false) {
        viewModelScope.launch {
            try {
                if (refresh) {
                    _conversationsState.update { it.copy(page = 0, hasMore = true, isLoading = true) }
                } else {
                    if (!_conversationsState.value.hasMore || _conversationsState.value.isLoading) {
                        return@launch
                    }
                }

                _conversationsState.update { it.copy(isLoading = true) }
                
                val currentState = _conversationsState.value
                val conversations = repository.getConversations(currentState.page, currentState.pageSize)
                
                _conversationsState.update { state ->
                    val newList = if (refresh) {
                        conversations
                    } else {
                        (state.conversations + conversations).distinctBy { it.id }
                    }
                    
                    state.copy(
                        conversations = newList,
                        isLoading = false,
                        page = state.page + 1,
                        hasMore = conversations.size >= state.pageSize
                    )
                }
                Log.d(TAG, "成功获取对话列表: 页码=${_conversationsState.value.page-1}, 数量=${conversations.size}, hasMore=${_conversationsState.value.hasMore}")
            } catch (e: Exception) {
                _conversationsState.update { it.copy(isLoading = false) }
                Log.e(TAG, "获取对话列表失败", e)
            }
        }
    }

    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                _chatState.update { it.copy(isLoading = true) }

                // 获取对话轮次
                val conversationTurns = repository.getConversationDetail(conversationId)

                // 将 ConversationTurn 转换为 Message
                val messages = convertConversationTurnsToMessages(conversationTurns)

                // 更新当前选中的对话
                val selectedConversation = _conversationsState.value.conversations.find { it.id == conversationId }

                // 优化：更新 Session ID 为加载的历史对话 ID，以便持续接着对话
                sessionManager.saveSessionId(conversationId)
                Log.d("ChatViewModel", "更新 Session ID 为加载的历史对话 ID: $conversationId")
                repository.loadSessionToCache(conversationId)

                _chatState.update {
                    it.copy(
                        messages = messages,
                        sessionId = conversationId, // 同步更新状态
                        selectedConversation = selectedConversation,
                        showWelcome = messages.isEmpty(),
                        isLoading = false
                    )
                }

                Log.d(TAG, "成功加载对话: $conversationId, 消息数量: ${messages.size}")
            } catch (e: Exception) {
                Log.e(TAG, "加载对话失败", e)
                _chatState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            }
        }
    }

    // 辅助方法：将 ConversationTurn 列表转换为 Message 列表
    private fun convertConversationTurnsToMessages(turns: List<ConversationTurn>): List<Message> {
        val messages = mutableListOf<Message>()

        // 按轮次号排序
        val sortedTurns = turns.sortedBy { it.turnNumber }

        for (turn in sortedTurns) {
            // 添加用户消息
            if (turn.userMessage.isNotBlank()) {
                val userMessage = Message.UserMessage(
                    id = turn.id + "_user",
                    content = turn.userMessage,
                    timestamp = turn.createdAt.time
                )
                messages.add(userMessage)
            }

            // 添加AI消息
            if (turn.assistantResponse.isNotBlank()) {
                val hasReasoning = !turn.reasoningContent.isNullOrBlank()
                val aiMessage = Message.AIMessage(
                    id = turn.id + "_assistant",
                    content = turn.assistantResponse,
                    reasoningContent = turn.reasoningContent ?: "",
                    showReasoning = hasReasoning,
                    isReceivingReasoning = false, // 历史消息已经完成
                    showDisclaimer = true, // 历史消息显示免责声明
                    timestamp = turn.updatedAt.time,
                    isReceiving = false // 历史消息已经完成
                )
                messages.add(aiMessage)
            }
        }

        return messages
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                repository.deleteConversation(conversationId)

                // 从列表中移除并刷新
                Log.d(TAG, "删除对话成功: $conversationId, 正在重新获取列表")
                fetchConversations(true)

                // 如果删除的是当前选中的对话，清空消息
                if (_chatState.value.selectedConversation?.id == conversationId) {
                    _chatState.update {
                        it.copy(
                            messages = emptyList(),
                            selectedConversation = null,
                            showWelcome = true
                        )
                    }
                    sessionManager.clearSessionId()
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除对话失败", e)
            }
        }
    }

    fun clearMessages() {
        Log.d(TAG, "清空消息")
        resetStreamState()  // 新增：重置流式状态

        viewModelScope.launch {
            _chatState.update {
                it.copy(
                    messages = emptyList(),
                    showWelcome = true,
                    selectedConversation = null,
                    isSending = false,
                    isLoading = false
                )
            }
            Log.d(TAG, "状态已重置，初始化新会话")
            initSession()
            fetchConversations(true)
        }
    }

    // 新建会话时明确重置
    fun createNewSession() {
        viewModelScope.launch {
            // 清除当前状态
            clearMessages()

            // 创建全新的 Session
            initSession()
        }
    }

    private fun generateMessageId(): String = UUID.randomUUID().toString()
}

data class ChatState(
    val messages: List<Message> = emptyList(),
    val sessionId: String? = null,
    val selectedConversation: Conversation? = null,
    val isSending: Boolean = false,
    val isLoading: Boolean = false,
    val showWelcome: Boolean = true,
    val error: String? = null,
    val deepThinkingEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false
)

data class ConversationsState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 0,
    val pageSize: Int = 20,
    val hasMore: Boolean = true,
    val error: String? = null
)
