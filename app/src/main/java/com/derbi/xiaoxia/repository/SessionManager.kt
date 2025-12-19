// SessionManager.kt
package com.derbi.xiaoxia.repository

import kotlinx.coroutines.flow.Flow

/**
 * 会话管理器接口
 * 负责管理用户会话状态，包括 sessionId、userId 等
 */
interface SessionManager {
    // Session ID 相关
    fun getSessionId(): String
    suspend fun saveSessionId(sessionId: String)
    suspend fun clearSessionId()
    fun observeSessionId(): Flow<String?>

    // User ID 相关
    fun getUserId(): String
    suspend fun saveUserId(userId: String)

    // 其他会话状态
    fun isSessionValid(): Boolean
    suspend fun clearAll()

    // 设备信息
    fun getDeviceId(): String

    // 首选项相关
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    suspend fun saveBoolean(key: String, value: Boolean)
    fun getString(key: String, defaultValue: String = ""): String
    suspend fun saveString(key: String, value: String)
    fun getInt(key: String, defaultValue: Int = 0): Int
    suspend fun saveInt(key: String, value: Int)
    fun getLong(key: String, defaultValue: Long = 0L): Long
    suspend fun saveLong(key: String, value: Long)
}