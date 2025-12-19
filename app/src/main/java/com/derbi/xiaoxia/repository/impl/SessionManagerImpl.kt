package com.derbi.xiaoxia.repository.impl

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.derbi.xiaoxia.repository.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session_prefs")

class SessionManagerImpl(private val context: Context) : SessionManager {

    private val dataStore = context.dataStore

    companion object {
        val SESSION_ID = stringPreferencesKey("session_id")
        val USER_ID = stringPreferencesKey("user_id")
    }

    // 统一使用 SharedPreferences（同步操作）
    private val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)

    override fun getSessionId(): String {
        return prefs.getString("session_id", "") ?: ""
    }

    override suspend fun saveSessionId(sessionId: String) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putString("session_id", sessionId)
            }
        }
    }

    override suspend fun clearSessionId() {
        withContext(Dispatchers.IO) {
            prefs.edit {
                remove("session_id")
            }
        }
    }

    override fun observeSessionId(): Flow<String?> {
        return dataStore.data.map { preferences -> preferences[SESSION_ID] }
    }

    override fun getUserId(): String {
        val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
        return prefs.getString("user_id", "") ?: ""
    }

    override suspend fun saveUserId(userId: String) {
        dataStore.edit { preferences ->
            preferences[USER_ID] = userId
        }
    }

    override fun isSessionValid(): Boolean {
        return getSessionId().isNotEmpty()
    }

    override suspend fun clearAll() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    override fun getDeviceId(): String {
        return ""
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return false
    }

    override suspend fun saveBoolean(key: String, value: Boolean) {
    }

    override fun getString(key: String, defaultValue: String): String {
        return ""
    }

    override suspend fun saveString(key: String, value: String) {
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return 0
    }

    override suspend fun saveInt(key: String, value: Int) {
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return 0
    }

    override suspend fun saveLong(key: String, value: Long) {
    }
}
