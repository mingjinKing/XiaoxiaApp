// ValidateSessionResponse.kt
package com.derbi.xiaoxia.models

import com.google.gson.annotations.SerializedName

/**
 * 会话验证响应
 * 对应 app.js 中的 validateSessionId 接口
 */
data class ValidateSessionResponse(
    @SerializedName("sessionId")
    val sessionId: String? = null,

    @SerializedName("isValid")
    val isValid: Boolean = false,

    @SerializedName("userId")
    val userId: String? = null,

    @SerializedName("expiresAt")
    val expiresAt: Long? = null,

    @SerializedName("createdAt")
    val createdAt: Long? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("code")
    val code: Int = 0
) {
    companion object {
        val INVALID = ValidateSessionResponse(
            sessionId = null,
            isValid = false,
            code = 400
        )

        val VALID = ValidateSessionResponse(
            sessionId = "valid_session",
            isValid = true,
            code = 200
        )
    }
}

/**
 * 通用 API 响应
 */
data class ApiResponse<T>(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: T? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("code")
    val code: Int = 200,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(success = true, data = data, code = 200)
        }

        fun <T> error(message: String, code: Int = 400): ApiResponse<T> {
            return ApiResponse(success = false, message = message, code = code)
        }

        fun success(): ApiResponse<Unit> {
            return ApiResponse(success = true, data = Unit, code = 200)
        }
    }
}