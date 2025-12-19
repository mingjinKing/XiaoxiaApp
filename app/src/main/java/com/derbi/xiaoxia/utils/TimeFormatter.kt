package com.derbi.xiaoxia.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

object TimeFormatter {

    private val todayFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val yesterdayFormat = SimpleDateFormat("昨天 HH:mm", Locale.CHINA)
    private val thisYearFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun formatRelativeTime(timestamp: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        target.time = Date(timestamp)

        // 重置时间为0点，用于日期比较
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val yesterday = Calendar.getInstance()
        yesterday.timeInMillis = today.timeInMillis - 24 * 60 * 60 * 1000

        val targetDay = Calendar.getInstance()
        targetDay.time = Date(timestamp)
        targetDay.set(Calendar.HOUR_OF_DAY, 0)
        targetDay.set(Calendar.MINUTE, 0)
        targetDay.set(Calendar.SECOND, 0)
        targetDay.set(Calendar.MILLISECOND, 0)

        return when {
            // 如果是今天
            targetDay.timeInMillis == today.timeInMillis ->
                "今天 ${todayFormat.format(Date(timestamp))}"

            // 如果是昨天
            targetDay.timeInMillis == yesterday.timeInMillis ->
                yesterdayFormat.format(Date(timestamp))

            // 如果是今年
            target.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                thisYearFormat.format(Date(timestamp))

            // 其他情况显示完整日期和时间
            else -> fullFormat.format(Date(timestamp))
        }
    }

    fun formatConversationTime(timestamp: Date): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        target.time = timestamp

        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val targetDay = Calendar.getInstance()
        targetDay.time = timestamp
        targetDay.set(Calendar.HOUR_OF_DAY, 0)
        targetDay.set(Calendar.MINUTE, 0)
        targetDay.set(Calendar.SECOND, 0)
        targetDay.set(Calendar.MILLISECOND, 0)

        val diffDays = ((today.timeInMillis - targetDay.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()

        return when {
            diffDays == 0 -> todayFormat.format(timestamp)
            diffDays == 1 -> "昨天"
            diffDays in 2..6 -> "${diffDays}天前"
            target.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                SimpleDateFormat("MM-dd", Locale.CHINA).format(timestamp)
            else -> SimpleDateFormat("yy-MM-dd", Locale.CHINA).format(timestamp)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return fullFormat.format(Date(timestamp))
    }
}
