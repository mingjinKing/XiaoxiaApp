package com.derbi.xiaoxia.models

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * 会话详情
 * 对应 conversation.js 中的会话对象
 */
data class Conversation(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("summary")
    val summary: String? = null,

    @SerializedName("createdAt")
    val createdAt: Date,

    @SerializedName("updatedAt")
    val updatedAt: Date,

    @SerializedName("messageCount")
    val messageCount: Int = 0,

    @SerializedName("isExpired")
    val isExpired: Boolean = false,

    // 前端使用的字段，可能不在后端响应中
    @Transient
    val isSelected: Boolean = false
) {
    companion object {
        val EMPTY = Conversation(
            id = "",
            title = "",
            summary = "",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}
