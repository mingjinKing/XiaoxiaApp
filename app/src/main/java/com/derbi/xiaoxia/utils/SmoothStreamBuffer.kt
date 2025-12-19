// SmoothStreamBuffer.kt
package com.derbi.xiaoxia.utils

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // 缓冲区
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()

    // 用于平滑输出的协程
    private var smoothOutputJob: Job? = null
    private var isBufferEmpty = true

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

    // 开始平滑输出
// 开始平滑输出
    fun startSmoothOutput(onOutput: suspend (OutputChunk) -> Unit) {
        cancel()

        Log.d(TAG, "开始平滑输出，chunkSize=$chunkSize, outputDelay=$outputDelay")

        // 重置完成标志
        _bufferContent.value = _bufferContent.value.copy(isFinished = false)

        // 立即启动平滑输出协程
        startGradualOutput(onOutput)
    }

    // 修改 startGradualOutput 方法，添加完成标志检查
    private fun startGradualOutput(onOutput: suspend (OutputChunk) -> Unit) {
        smoothOutputJob = scope.launch(Dispatchers.Default) {
            var isFirstOutput = true
            var outputCount = 0

            while (isActive) {
                delay(outputDelay)

                // 检查是否有内容需要输出
                val hasText = textBuffer.isNotEmpty()
                val hasReasoning = reasoningBuffer.isNotEmpty()

                if (!hasText && !hasReasoning) {
                    if (_bufferContent.value.isFinished) {
                        Log.d(TAG, "缓冲区已空且已完成，停止输出")
                        // 发送最终的完成标记
                        withContext(Dispatchers.Main) {
                            onOutput(OutputChunk(
                                text = "",
                                reasoning = "",
                                isFinal = true
                            ))
                        }
                        break
                    }
                    continue
                }

                // 从缓冲区取出内容
                val textChunk = if (hasText) {
                    val chunkSizeToUse = chunkSize.coerceAtMost(textBuffer.length)
                    val chunk = textBuffer.take(chunkSizeToUse)
                    textBuffer.delete(0, chunkSizeToUse)
                    //Log.v(TAG, "文本块[${outputCount+1}]：长度=${chunk.length}, 剩余缓冲区=${textBuffer.length}")
                    chunk
                } else ""

                val reasoningChunk = if (hasReasoning) {
                    val chunkSizeToUse = chunkSize.coerceAtMost(reasoningBuffer.length)
                    val chunk = reasoningBuffer.take(chunkSizeToUse)
                    reasoningBuffer.delete(0, chunkSizeToUse)
                    //Log.v(TAG, "思考块[${outputCount+1}]：长度=${chunk.length}, 剩余缓冲区=${reasoningBuffer.length}")
                    chunk
                } else ""

                // 优化后的判断条件
                val isBufferNearlyEmpty = textBuffer.length < chunkSize
                if (isFirstOutput && textChunk.length < 50 && isBufferNearlyEmpty && !_bufferContent.value.isFinished) {
                    //Log.d(TAG, "第一次输出内容少且缓冲区库存不足，等待更多内容")
                    textBuffer.insert(0, textChunk)
                    reasoningBuffer.insert(0, reasoningChunk)
                    continue
                }

                isFirstOutput = false
                outputCount++

                // 检查是否是最后一次输出
                val isFinal = _bufferContent.value.isFinished &&
                        textBuffer.isEmpty() &&
                        reasoningBuffer.isEmpty()

                // 发送输出
                withContext(Dispatchers.Main) {
                    onOutput(OutputChunk(
                        text = textChunk as String,
                        reasoning = reasoningChunk as String,
                        isFinal = isFinal
                    ))
                }

                // 更新累积状态
                val currentState = _bufferContent.value
                _bufferContent.value = currentState.copy(
                    accumulatedText = currentState.accumulatedText + textChunk,
                    accumulatedReasoning = currentState.accumulatedReasoning + reasoningChunk
                )

                //Log.d(TAG, "已输出[${outputCount}]：文本累积=${currentState.accumulatedText.length + textChunk.length}字符")

                // 如果是最终输出，退出循环
                if (isFinal) {
                    Log.d(TAG, "发送最终输出，停止平滑输出循环")
                    break
                }
            }
        }
    }

    // 添加新内容到缓冲区
    fun appendContent(text: String = "", reasoning: String = "") {
        if (text.isNotEmpty()) {
            val oldLength = textBuffer.length
            textBuffer.append(text)
            //Log.d(TAG, "添加文本：长度=${text.length}, 缓冲区从${oldLength}到${textBuffer.length}")
        }
        if (reasoning.isNotEmpty()) {
            val oldLength = reasoningBuffer.length
            reasoningBuffer.append(reasoning)
            //Log.d(TAG, "添加思考：长度=${reasoning.length}, 缓冲区从${oldLength}到${reasoningBuffer.length}")
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
        textBuffer.clear()
        reasoningBuffer.clear()
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