// SmoothStreamBuffer.kt
package com.derbi.xiaoxia.utils

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SmoothStreamBuffer(
    private val scope: CoroutineScope
) {
    // 添加调试标签
    companion object {
        private const val TAG = "SmoothStreamBuffer"
    }

    // 状态管理
    private val _bufferContent = MutableStateFlow(BufferState())
    // 改为直接引用常量，确保 SpeedControl 是唯一真相源
    private val outputDelay: Long get() = SpeedControl.SPEED_SLOW
    private val chunkSize: Int get() = SpeedControl.CHUNK_SMALL

    val bufferContent: StateFlow<BufferState> = _bufferContent.asStateFlow()
    // 增加：保存当前的回调函数，确保重启输出任务时依然有效
    private var currentOnOutput: (suspend (OutputChunk) -> Unit)? = null

    // 缓冲区
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()

    // 用于平滑输出的协程
    private var smoothOutputJob: Job? = null
    private var isReasoningPriority = false
    private var reasoningCompleted = false

    data class BufferState(
        val accumulatedText: String = "",
        val accumulatedReasoning: String = "",
        val isFinished: Boolean = false
    )

    data class OutputChunk(
        val text: String = "",
        val reasoning: String = "",
        val isFinal: Boolean = false
    )

    fun startSmoothOutput(onOutput: suspend (OutputChunk) -> Unit) {
        synchronized(this) {
            cancel()
            this.currentOnOutput = onOutput // 保存回调
            startGradualOutput(onOutput)
        }
    }

    // 修改 startGradualOutput 方法中的循环逻辑
    private fun startGradualOutput(onOutput: suspend (OutputChunk) -> Unit) {
        smoothOutputJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(outputDelay)

                var textChunk = ""
                var reasoningChunk = ""
                var isFinished = false

                // 在同步块内读取并消耗缓冲区内容
                synchronized(this@SmoothStreamBuffer) {
                    isFinished = _bufferContent.value.isFinished

                    // 优先输出思考内容，直到它完成
                    if (reasoningBuffer.isNotEmpty()) {
                        val chunkSizeToUse = chunkSize.coerceAtMost(reasoningBuffer.length)
                        reasoningChunk = reasoningBuffer.substring(0, chunkSizeToUse)
                        reasoningBuffer.delete(0, chunkSizeToUse)

                        // 如果思考缓冲区清空了，标记思考完成
                        if (reasoningBuffer.isEmpty()) {
                            reasoningCompleted = true
                        }
                    }
                    // 只有思考内容完成（或不存在思考内容）时，才输出回答内容
                    else if (textBuffer.isNotEmpty() && (reasoningCompleted || reasoningBuffer.isEmpty())) {
                        val chunkSizeToUse = chunkSize.coerceAtMost(textBuffer.length)
                        textChunk = textBuffer.substring(0, chunkSizeToUse)
                        textBuffer.delete(0, chunkSizeToUse)
                    }
                }

                // 检查是否全部完成
                val isActuallyFinal = isFinished && textChunk.isEmpty() && reasoningChunk.isEmpty()
                if (isActuallyFinal && textChunk.isEmpty()) {
                    withContext(Dispatchers.Main) { onOutput(OutputChunk(isFinal = true)) }
                    break
                }

                if (textChunk.isNotEmpty() || reasoningChunk.isNotEmpty()) {
                    // 更新累积状态并分发输出
                    withContext(Dispatchers.Main) {
                        onOutput(OutputChunk(text = textChunk, reasoning = reasoningChunk))
                    }

                    // 线程安全地更新累积计数
                    _bufferContent.update { state ->
                        state.copy(
                            accumulatedText = state.accumulatedText + textChunk,
                            accumulatedReasoning = state.accumulatedReasoning + reasoningChunk
                        )
                    }
                } else if (isFinished) {
                    // 检查是否还有未输出的内容
                    val hasRemainingContent = synchronized(this@SmoothStreamBuffer) {
                        textBuffer.isNotEmpty() || reasoningBuffer.isNotEmpty()
                    }
                    if (!hasRemainingContent) {
                        break
                    }
                }
            }
        }
    }

    // 修改 replaceContent 方法，重置优先级状态
    fun replaceContent(text: String = "", reasoning: String = "", isFinal: Boolean = false) {
        synchronized(this) {
            // 1. 获取当前已经累积输出的内容
            val currentAccumulatedText = _bufferContent.value.accumulatedText
            val currentAccumulatedReasoning = _bufferContent.value.accumulatedReasoning

            // 2. 清空缓冲区
            textBuffer.setLength(0)
            reasoningBuffer.setLength(0)

            // 3. 重置优先级状态
            reasoningCompleted = false

            // 4. 计算需要放入缓冲区的新内容
            if (text.startsWith(currentAccumulatedText)) {
                textBuffer.append(text.substring(currentAccumulatedText.length))
            } else {
                _bufferContent.value = _bufferContent.value.copy(accumulatedText = "")
                textBuffer.append(text)
            }

            if (reasoning.startsWith(currentAccumulatedReasoning)) {
                reasoningBuffer.append(reasoning.substring(currentAccumulatedReasoning.length))
                // 如果有思考内容，设置优先级标志
                if (reasoningBuffer.isNotEmpty()) {
                    isReasoningPriority = true
                }
            } else {
                _bufferContent.value = _bufferContent.value.copy(accumulatedReasoning = "")
                reasoningBuffer.append(reasoning)
                if (reasoning.isNotEmpty()) {
                    isReasoningPriority = true
                }
            }

            // 5. 设置完成标志
            if (isFinal) {
                _bufferContent.value = _bufferContent.value.copy(isFinished = true)
            }

            // 6. 重启输出任务
            if (smoothOutputJob == null || !smoothOutputJob!!.isActive) {
                currentOnOutput?.let { onOutput ->
                    startGradualOutput(onOutput)
                }
            }
        }
    }

    // 修改 finish 方法，只设置完成标志，不直接输出
    fun finish(): Job {
        return scope.launch(Dispatchers.Default) {
            //Log.d(TAG, "设置完成标志，等待平滑输出处理剩余内容")

            // 只设置完成标志，让平滑输出协程自然完成
            _bufferContent.value = _bufferContent.value.copy(isFinished = true)

            // 等待平滑输出任务完成
            smoothOutputJob?.join()

            //Log.d(TAG, "完成处理完毕，所有内容已输出")
        }
    }


    // 取消输出
    fun cancel() {
        Log.d(TAG, "取消输出，文本缓冲区=${textBuffer.length}, 思考缓冲区=${reasoningBuffer.length}")
        smoothOutputJob?.cancel()
        textBuffer.setLength(0)
        reasoningBuffer.setLength(0)
        _bufferContent.value = BufferState()
    }

    /**
     * 清空所有缓冲区内容和状态
     */
    fun clearAll() {
        Log.d(TAG, "清空所有缓冲区")
        cancel() // 取消当前输出
        textBuffer.clear()
        reasoningBuffer.clear()
        _bufferContent.value = BufferState() // 重置状态
    }
}