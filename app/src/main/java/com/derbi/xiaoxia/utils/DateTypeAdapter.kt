// DateTypeAdapter.kt
package com.derbi.xiaoxia.utils

import com.google.gson.*
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.*

class DateTypeAdapter : JsonSerializer<Date>, JsonDeserializer<Date> {

    companion object {
        // 添加缺少的格式：无毫秒无时区的ISO格式
        private val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss",          // 添加这一行：处理 2025-12-17T07:47:07
            "yyyy-MM-dd'T'HH:mm:ss.SSS",      // 有毫秒无时区
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",   // ISO 8601 带时区
            "yyyy-MM-dd'T'HH:mm:ss'Z'",       // ISO 8601 无毫秒带时区
            "yyyy-MM-dd'T'HH:mm:ssXXX",       // 带时区偏移
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",   // 带毫秒和时区偏移
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "dd.MM.yyyy"
        )
    }

    override fun serialize(
        src: Date?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return if (src != null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            JsonPrimitive(dateFormat.format(src))
        } else {
            JsonNull.INSTANCE
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Date? {
        if (json == null || json.isJsonNull) {
            return null
        }

        return try {
            // 1. 首先尝试解析为时间戳（数字）
            if (json.isJsonPrimitive && json.asJsonPrimitive.isNumber) {
                val timestamp = json.asLong
                return Date(timestamp)
            }

            // 2. 尝试解析为字符串
            if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
                val dateString = json.asString.trim()

                if (dateString.isBlank()) return null

                // 特殊处理：如果是纯数字，尝试作为时间戳
                if (dateString.matches(Regex("^\\d+$"))) {
                    val timestamp = dateString.toLong()
                    val milliseconds = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
                    return Date(milliseconds)
                }

                // 尝试所有格式
                for (format in formats) {
                    try {
                        val dateFormat = SimpleDateFormat(format, Locale.US)
                        // 对于没有时区的格式，使用系统默认时区
                        if (!format.contains("Z") && !format.contains("XXX")) {
                            dateFormat.timeZone = TimeZone.getDefault()
                        } else {
                            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                        }
                        dateFormat.isLenient = false
                        return dateFormat.parse(dateString)
                    } catch (e: Exception) {
                        // 继续尝试下一个格式
                        continue
                    }
                }
            }

            // 无法解析，返回null
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}