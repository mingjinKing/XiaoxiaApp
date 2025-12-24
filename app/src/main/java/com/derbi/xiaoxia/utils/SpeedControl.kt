// SpeedControl.kt
package com.derbi.xiaoxia.utils

class SpeedControl {
    companion object {
        const val SPEED_SLOW = 100L    // 100ms per chunk
        const val SPEED_NORMAL = 50L   // 50ms per chunk
        const val SPEED_FAST = 20L     // 20ms per chunk
        const val SPEED_INSTANT = 0L   // 无延迟

        const val CHUNK_SMALL = 5     // 每次输出5个字符
        const val CHUNK_MEDIUM = 50    // 每次输出10个字符
        const val CHUNK_LARGE = 100     // 每次输出20个字符
    }
}