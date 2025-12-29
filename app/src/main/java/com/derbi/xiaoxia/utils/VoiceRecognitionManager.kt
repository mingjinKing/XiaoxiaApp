package com.derbi.xiaoxia.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.alibaba.dashscope.audio.omni.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRecognitionManager(
    private val apiKey: String,
    private val callback: VoiceRecognitionCallback
) {
    private val TAG = "VoiceRecognitionManager"
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private var audioRecord: AudioRecord? = null
    private var conversation: OmniRealtimeConversation? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 用于追踪当前活跃的会话，防止旧会话的回调干扰新会话
    private var currentSessionId = 0

    interface VoiceRecognitionCallback {
        fun onVolumeUpdate(volume: Float) // 新增：音量反馈
        fun onTranscriptionUpdate(text: String)
        fun onTranscriptionComplete(text: String)
        fun onError(message: String)
        fun onStart()
        fun onStop()
    }

    @SuppressLint("MissingPermission")
    fun startRecognition() {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording, ignore start request")
            return
        }

        val sessionId = ++currentSessionId
        Log.d(TAG, "Starting recognition session: $sessionId")

        val param = OmniRealtimeParam.builder()
            .model("qwen3-asr-flash-realtime")
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
            .apikey(apiKey)
            .build()

        val newConversation = OmniRealtimeConversation(param, object : OmniRealtimeCallback() {
            override fun onOpen() {
                if (sessionId != currentSessionId) return
                Log.d(TAG, "Connection opened for session $sessionId")
                callback.onStart()
                
                // 使用类成员变量 conversation，它在 connect() 调用前已被赋值
                conversation?.let { setupSession(it) }
            }

            override fun onEvent(message: JsonObject) {
                if (sessionId != currentSessionId) return
                val type = message.get("type").asString
                Log.d(TAG, "Event session $sessionId: $type")
                when (type) {
                    "conversation.item.input_audio_transcription.text" -> {
                        val text = message.get("text").asString
                        // 只有在非空时更新，防止中间状态清空输入框
                        if (text.isNotEmpty()) {
                            callback.onTranscriptionUpdate(text)
                        }
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        val text = message.get("transcript").asString
                        callback.onTranscriptionComplete(text)
                    }
                    "error" -> {
                        Log.e(TAG, "SDK Error session $sessionId: $message")
                        callback.onError(message.toString())
                    }
                }
            }

            override fun onClose(code: Int, reason: String) {
                if (sessionId != currentSessionId) return
                Log.d(TAG, "Connection closed session $sessionId: $code, $reason")
                stopRecording()
                callback.onStop()
            }
        })

        // 先赋值，再 connect，确保 onOpen 中能通过成员变量拿到实例
        this.conversation = newConversation

        try {
            newConversation.connect()
            startAudioRecording(newConversation)
        } catch (e: Exception) {
            Log.e(TAG, "Connect error session $sessionId", e)
            if (sessionId == currentSessionId) {
                callback.onError(e.message ?: "Failed to connect")
                stopRecording()
            }
        }
    }

    private fun setupSession(conv: OmniRealtimeConversation) {
        val transcriptionParam = OmniRealtimeTranscriptionParam().apply {
            language = "zh"
            inputAudioFormat = "pcm"
            inputSampleRate = SAMPLE_RATE
        }

        val config = OmniRealtimeConfig.builder()
            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
            .transcriptionConfig(transcriptionParam)
            .build()
        conv.updateSession(config)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording(conv: OmniRealtimeConversation) {
        if (audioRecord == null) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            callback.onError("AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording.set(true)

        recordingJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRecording.get() && isActive) {
                val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read > 0) {
                    // 1. 计算音量 (RMS 均方根算法)
                    val volume = calculateVolume(buffer, read)
                    // 2. 回调给 UI 层
                    callback.onVolumeUpdate(volume)

                    val audioB64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    conv.appendAudio(audioB64)
                }
                delay(20)
            }
        }
    }

    // 一个简单的音量计算函数（返回 0.0 到 1.0 之间的值）
    private fun calculateVolume(buffer: ByteArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize step 2) {
            // 将两个 Byte 转换为一个 16-bit Short
            val sample = (buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / (readSize / 2))
        // 归一化处理：通常 30000 左右是很大的声音
        return (rms / 30000.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun stopRecognition() {
        // 如果已经在停止中了，直接返回
        if (!isRecording.get()) return

        val conversationToClose = conversation
        // 1. 立即停止本地录音硬件和协程循环
        stopRecording()

        // 2. 异步关闭网络连接
        scope.launch {
            try {
                // 发送最后一点数据后关闭
                val silence = ByteArray(512)
                val audioB64 = Base64.encodeToString(silence, Base64.NO_WRAP)
                conversationToClose?.appendAudio(audioB64)
                conversationToClose?.close(1000, "Normal closure")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing", e)
            } finally {
                conversation = null
            }
        }
    }

    private fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel() // 确保协程被取消
        recordingJob = null

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error release AudioRecord", e)
        }
        audioRecord = null
    }
}
