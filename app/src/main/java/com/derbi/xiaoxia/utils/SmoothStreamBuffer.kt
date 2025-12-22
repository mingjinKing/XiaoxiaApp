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

    // 缓冲区 - 改为存储当前最新的完整内容
    private var currentText = ""
    private var currentReasoning = ""

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
            var lastOutputText = ""
            var lastOutputReasoning = ""
            var outputCount = 0

            while (isActive) {
                delay(outputDelay)

                // 检查是否有新内容需要输出
                val hasNewText = currentText != lastOutputText
                val hasNewReasoning = currentReasoning != lastOutputReasoning

                if (!hasNewText && !hasNewReasoning) {
                    if (_bufferContent.value.isFinished) {
                        Log.d(TAG, "缓冲区已完成，停止输出")
                        // 发送最终的完成标记
                        withContext(Dispatchers.Main) {
                            onOutput(OutputChunk(
                                text = currentText,
                                reasoning = currentReasoning,
                                isFinal = true
                            ))
                        }
                        break
                    }
                    continue
                }

                outputCount++

                // 检查是否是最后一次输出
                val isFinal = _bufferContent.value.isFinished

                // 发送输出 - 直接发送当前最新的完整内容
                withContext(Dispatchers.Main) {
                    onOutput(OutputChunk(
                        text = currentText,
                        reasoning = currentReasoning,
                        isFinal = isFinal
                    ))
                }

                // 更新上次输出的内容
                lastOutputText = currentText
                lastOutputReasoning = currentReasoning

                Log.d(TAG, "已输出[${outputCount}]：文本长度=${currentText.length}, 思考长度=${currentReasoning.length}")

                // 如果是最终输出，退出循环
                if (isFinal) {
                    Log.d(TAG, "发送最终输出，停止平滑输出循环")
                    break
                }
            }
        }
    }

    // 修改：直接设置最新的完整内容，而不是追加
    fun setLatestContent(text: String = "", reasoning: String = "") {
        currentText = text
        currentReasoning = reasoning
        Log.d(TAG, "设置最新内容：文本长度=${text.length}, 思考长度=${reasoning.length}")
    }

    // 修改 finish 方法，只设置完成标志，不直接输出
    fun finish(): Job {
        return scope.launch(Dispatchers.Default) {
            Log.d(TAG, "设置完成标志，等待平滑输出处理剩余内容")

            // 只设置完成标志，让平滑输出协程自然完成
            _bufferContent.value = _bufferContent.value.copy(isFinished = true)

            // 等待平滑输出任务完成
            smoothOutputJob?.join()

            Log.d(TAG, "完成处理完毕，所有内容已输出")
        }
    }

    // 取消输出
    fun cancel() {
        Log.d(TAG, "取消输出")
        smoothOutputJob?.cancel()
        currentText = ""
        currentReasoning = ""
        _bufferContent.value = BufferState()
    }

    /**
     * 清空所有缓冲区内容和状态
     */
    fun clearAll() {
        Log.d(TAG, "清空所有缓冲区")
        cancel()
        _bufferContent.value = BufferState()
    }
}