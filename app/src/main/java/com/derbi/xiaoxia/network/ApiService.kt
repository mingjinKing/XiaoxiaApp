// ApiService.kt - Retrofit接口定义
package com.derbi.xiaoxia.network

import com.derbi.xiaoxia.models.*
import com.derbi.xiaoxia.repository.MessageResponse
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    // 初始化会话 (参考 apiService.js 中的 initSession)
    @POST("/xiaoXia/initSession")
    suspend fun initSession(
        @Body request: InitSessionRequest,
        @Header("X-Session-Id") sessionId: String? = null
    ): Response<Unit>

    // 发送消息 - 流式响应 (参考 apiService.js 中的 sendMessage)
    @Streaming
    @POST("/xiaoXia/textChat")
    suspend fun sendMessage(
        @Body request: TextChatRequest,
        @Header("X-Session-Id") sessionId: String
    ): Response<ResponseBody>

    // 获取会话列表 (参考 conversation.js 中的 fetchConversations)
    @GET("/xiaoXia/sessions")
    suspend fun getSessions(
        @Query("page") page: Int = 0,
        @Query("size") pageSize: Int = 20
    ): SessionListResponse

    // 获取会话详情 (参考 conversation.js 中的 fetchConversationTurns)
    @GET("/xiaoXia/conversationTurns")
    suspend fun getConversationTurns(
        @Query("sessionId") sessionId: String,
        @Header("X-Session-Id") currentSessionId: String
    ): ConversationTurnsResponse

    // 删除会话 (参考 conversation.js 中的 deleteConversation)
    @DELETE("/xiaoXia/conversationTurns")
    suspend fun deleteConversation(
        @Query("sessionId") sessionId: String,
        @Header("X-Session-Id") currentSessionId: String
    ): Response<Unit>

    // 验证会话有效性 (参考 app.js 中的 validateSessionId)
    @GET("/xiaoXia/validateSession")
    suspend fun validateSession(
        @Header("X-Session-Id") sessionId: String
    ): ValidateSessionResponse

    // 加载会话到缓存 (参考 conversation.js 中的 loadSessionToCache)
    @GET("/xiaoXia/loadSession")
    suspend fun loadSessionToCache(
        @Query("sessionId") sessionId: String,
        @Header("X-Session-Id") currentSessionId: String
    ): Response<Unit>
}
