package com.derbi.xiaoxia.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.derbi.xiaoxia.models.Conversation
import com.derbi.xiaoxia.models.ConversationTurn
import com.derbi.xiaoxia.models.Message
import com.derbi.xiaoxia.repository.ChatRepository
import com.derbi.xiaoxia.repository.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val repository: ChatRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()

    private val _conversationsState = MutableStateFlow(ConversationsState())
    val conversationsState: StateFlow<ConversationsState> = _conversationsState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UIEvent>()
    val uiEvents: SharedFlow<UIEvent> = _uiEvents.asSharedFlow()

    // 状态管理变量
    private var currentStreamJob: Job? = null
    private var currentAiMessageId: String? = null
    private var currentAiMessageIndex: Int = -1
    private var hasStartedContent = false
    private var hasValidContent = false
    private var isReplaceMode = false

    sealed class UIEvent {
        data class ShowMessage(val message: String) : UIEvent()
        data class ShowError(val error: String) : UIEvent()
        object NavigateBack : UIEvent()
    }

    fun initializeApp() {
        viewModelScope.launch {
            val currentSessionId = sessionManager.getSessionId()

            if (currentSessionId.isEmpty()) {
                initSession()
            } else {
                val isValid = repository.validateSession(currentSessionId)
                if (!isValid) {
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

        // 1. 取消之前的请求
        cancelCurrentMessage()

        // 2. 重置状态
        resetStreamState()

        viewModelScope.launch {
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

            // 创建AI消息ID
            currentAiMessageId = generateMessageId()
            Log.d(TAG, "创建AI消息ID: $currentAiMessageId")

            try {
                // 启动流式响应收集
                currentStreamJob = launch {
                    var responseCount = 0
                    var lastContent = ""

                    Log.d(TAG, "开始收集流式响应")

                    repository.sendMessage(
                        message = message,
                        deepThinking = _chatState.value.deepThinkingEnabled,
                        webSearch = _chatState.value.webSearchEnabled
                    ).collect { response ->
                        responseCount++
                        Log.d(TAG, "收到第${responseCount}个响应: content=${response.content?.take(20)}..., " +
                                "reasoning=${response.reasoningContent?.take(20)}..., " +
                                "isReplaceMode=${response.isReplaceMode}")

                        // 处理响应
                        handleStreamResponse(response)
                    }

                    Log.d(TAG, "流式响应收集完成")

                    // 完成消息处理
                    completeAIMessage()
                }

                // 等待流式响应任务完成
                currentStreamJob?.join()

            } catch (e: Exception) {
                Log.e(TAG, "发送消息时出错", e)
                handleStreamError(e)
            } finally {
                // 清理状态
                _chatState.update { it.copy(isSending = false) }
                resetStreamState()
                Log.d(TAG, "消息处理完成，状态已重置")
            }
        }
    }

    private fun handleStreamResponse(response: com.derbi.xiaoxia.repository.MessageResponse) {
        // 获取是否有实际内容
        val hasRealContent = response.content?.isNotBlank() == true
        val hasRealReasoning = response.reasoningContent?.isNotBlank() == true

        // 更新有效内容标记
        if (hasRealContent || hasRealReasoning) {
            hasValidContent = true
        }

        // 更新替换模式
        response.isReplaceMode?.let { isReplaceMode = it }

        // 获取当前状态
        val currentState = _chatState.value
        val messages = currentState.messages.toMutableList()

        // 查找AI消息索引
        if (currentAiMessageIndex == -1) {
            // 第一次收到响应，移除loading消息，创建AI消息
            val loadingIndex = messages.indexOfFirst { it is Message.LoadingMessage }
            if (loadingIndex != -1) {
                messages.removeAt(loadingIndex)
            }

            // 创建AI消息
            val aiMessage = Message.AIMessage(
                id = currentAiMessageId ?: generateMessageId(),
                content = response.content ?: "",
                reasoningContent = response.reasoningContent ?: "",
                showReasoning = _chatState.value.deepThinkingEnabled &&
                        (response.reasoningContent?.isNotBlank() == true),
                isReceivingReasoning = _chatState.value.deepThinkingEnabled &&
                        (response.reasoningContent?.isNotBlank() == true),
                showDisclaimer = false,
                timestamp = System.currentTimeMillis(),
                isReceiving = true
            )

            messages.add(aiMessage)
            currentAiMessageIndex = messages.lastIndex
            hasStartedContent = true

            // 如果不是深度思考模式且没有思考内容，立即隐藏思考区域
            if (!_chatState.value.deepThinkingEnabled && !hasRealReasoning) {
                (messages[currentAiMessageIndex] as? Message.AIMessage)?.let { updatedMessage ->
                    messages[currentAiMessageIndex] = updatedMessage.copy(
                        showReasoning = false,
                        isReceivingReasoning = false
                    )
                }
            }
        } else {
            // 更新现有AI消息
            val existingMessage = messages[currentAiMessageIndex] as? Message.AIMessage
            if (existingMessage != null) {
                val newContent = if (isReplaceMode) {
                    response.content ?: existingMessage.content
                } else {
                    existingMessage.content + (response.content ?: "")
                }

                val newReasoning = if (isReplaceMode) {
                    response.reasoningContent ?: existingMessage.reasoningContent
                } else {
                    existingMessage.reasoningContent + (response.reasoningContent ?: "")
                }

                messages[currentAiMessageIndex] = existingMessage.copy(
                    content = newContent,
                    reasoningContent = newReasoning,
                    // 只有在深度思考模式下才保持isReceivingReasoning状态
                    isReceivingReasoning = _chatState.value.deepThinkingEnabled &&
                            (response.reasoningContent?.isNotBlank() == true)
                )

                // 非深度思考模式下，如果一直没有收到思考内容，就隐藏思考区域
                if (!_chatState.value.deepThinkingEnabled && !hasRealReasoning) {
                    (messages[currentAiMessageIndex] as? Message.AIMessage)?.let { updatedMessage ->
                        messages[currentAiMessageIndex] = updatedMessage.copy(
                            showReasoning = false,
                            isReceivingReasoning = false
                        )
                    }
                }
            }
        }

        // 更新状态
        _chatState.update { it.copy(messages = messages) }
    }

    private fun completeAIMessage() {
        if (currentAiMessageIndex >= 0) {
            val messages = _chatState.value.messages.toMutableList()
            val message = messages[currentAiMessageIndex] as? Message.AIMessage

            message?.let {
                messages[currentAiMessageIndex] = it.copy(
                    showDisclaimer = true,
                    isReceiving = false,
                    isReceivingReasoning = false,
                    // 如果最终没有思考内容，隐藏思考区域
                    showReasoning = it.reasoningContent.isNotBlank() &&
                            _chatState.value.deepThinkingEnabled
                )

                _chatState.update { it.copy(messages = messages) }
            }
        }
    }

    private fun handleStreamError(e: Exception) {
        val errorMsg = when (e) {
            is java.net.SocketTimeoutException -> "请求超时，请检查网络连接"
            is java.net.ConnectException -> "无法连接到服务器"
            is java.net.UnknownHostException -> "网络连接异常，请检查网络"
            else -> "抱歉，处理您的请求时出错了。请稍后再试。\n\n错误信息：${e.message}"
        }

        Log.d(TAG, "错误信息: $errorMsg")

        // 移除loading消息
        val messages = _chatState.value.messages.toMutableList()
        val loadingIndex = messages.indexOfFirst { it is Message.LoadingMessage }
        if (loadingIndex != -1) {
            messages.removeAt(loadingIndex)
        }

        // 如果没有AI消息，创建错误消息
        if (currentAiMessageIndex == -1) {
            val aiMessage = Message.AIMessage(
                id = currentAiMessageId ?: generateMessageId(),
                content = errorMsg,
                showDisclaimer = true,
                timestamp = System.currentTimeMillis(),
                isReceiving = false
            )
            messages.add(aiMessage)
        } else {
            // 更新现有AI消息为错误消息
            val existingMessage = messages[currentAiMessageIndex] as? Message.AIMessage
            existingMessage?.let {
                messages[currentAiMessageIndex] = it.copy(
                    content = it.content + "\n\n" + errorMsg,
                    isReceiving = false,
                    isReceivingReasoning = false,
                    showDisclaimer = true
                )
            }
        }

        _chatState.update { it.copy(messages = messages) }
    }

    fun cancelCurrentMessage() {
        Log.d(TAG, "取消当前消息")
        currentStreamJob?.cancel()
        currentStreamJob = null

        // 更新UI状态
        _chatState.update { currentState ->
            val messages = currentState.messages.toMutableList()

            // 移除loading消息
            messages.removeAll { it is Message.LoadingMessage }

            // 如果最后一条是AI消息且还在接收中，标记为完成
            val lastMessage = messages.lastOrNull()
            if (lastMessage is Message.AIMessage && lastMessage.isReceiving) {
                val index = messages.indexOf(lastMessage)
                messages[index] = lastMessage.copy(
                    isReceiving = false,
                    isReceivingReasoning = false,
                    showDisclaimer = true,
                    content = if (lastMessage.content.isNotEmpty()) lastMessage.content else "已取消"
                )
            }

            currentState.copy(messages = messages, isSending = false)
        }

        resetStreamState()
    }

    private fun resetStreamState() {
        currentAiMessageId = null
        currentAiMessageIndex = -1
        hasStartedContent = false
        hasValidContent = false
        isReplaceMode = false
    }

    // 其他方法保持不变...
    fun fetchConversations(refresh: Boolean = false) {
        viewModelScope.launch {
            try {
                _conversationsState.update { it.copy(isLoading = true) }
                val conversations = repository.getConversations()
                _conversationsState.update {
                    it.copy(
                        conversations = conversations,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _conversationsState.update { it.copy(isLoading = false) }
                Log.e("ChatViewModel", "获取对话列表失败", e)
            }
        }
    }

    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                _chatState.update { it.copy(isLoading = true) }
                val conversationTurns = repository.getConversationDetail(conversationId)
                // 更新 sessionId
                sessionManager.saveSessionId(conversationId)
                Log.d("ChatViewModel", "loadConversation conversationId is : $conversationId")
                val messages = convertConversationTurnsToMessages(conversationTurns)
                val selectedConversation = _conversationsState.value.conversations.find { it.id == conversationId }

                _chatState.update {
                    it.copy(
                        messages = messages,
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

    private fun convertConversationTurnsToMessages(turns: List<ConversationTurn>): List<Message> {
        val messages = mutableListOf<Message>()
        val sortedTurns = turns.sortedBy { it.turnNumber }

        for (turn in sortedTurns) {
            if (turn.userMessage.isNotBlank()) {
                val userMessage = Message.UserMessage(
                    id = turn.id + "_user",
                    content = turn.userMessage,
                    timestamp = turn.createdAt.time
                )
                messages.add(userMessage)
            }

            if (turn.assistantResponse.isNotBlank()) {
                val hasReasoning = !turn.reasoningContent.isNullOrBlank()
                val aiMessage = Message.AIMessage(
                    id = turn.id + "_assistant",
                    content = turn.assistantResponse,
                    reasoningContent = turn.reasoningContent ?: "",
                    showReasoning = hasReasoning,
                    isReceivingReasoning = false,
                    showDisclaimer = true,
                    timestamp = turn.updatedAt.time,
                    isReceiving = false
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
                val updatedList = _conversationsState.value.conversations.filter { it.id != conversationId }
                _conversationsState.update { it.copy(conversations = updatedList) }

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
                Log.e("ChatViewModel", "删除对话失败", e)
            }
        }
    }

    fun clearMessages() {
        Log.d(TAG, "清空消息")
        resetStreamState()

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
            fetchConversations(false)
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            clearMessages()
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