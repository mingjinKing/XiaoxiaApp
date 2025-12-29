package com.derbi.xiaoxia.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.alibaba.dashscope.audio.qwen_tts_realtime.*
import com.alibaba.dashscope.exception.NoApiKeyException
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音合成管理类，使用 DashScope 实时语音合成 API
 */
class TtsManager(private val apiKey: String) {
    private val TAG = "TtsManager"
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val b64AudioBuffer = ConcurrentLinkedQueue<String>()
    private var playerThread: Thread? = null
    private var qwenTtsRealtime: QwenTtsRealtime? = null

    private val sampleRate = 24000

    init {
        initAudioTrack(sampleRate)
    }

    private fun initAudioTrack(sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 开始合成并播放语音
     */
    fun startSpeaking(text: String) {
        stopSpeaking()
        isPlaying.set(true)
        audioTrack?.play()

        // 启动播放线程
        playerThread = Thread {
            while (isPlaying.get()) {
                val b64Audio = b64AudioBuffer.poll()
                if (b64Audio != null) {
                    try {
                        val rawAudio = Base64.decode(b64Audio, Base64.DEFAULT)
                        audioTrack?.write(rawAudio, 0, rawAudio.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Decode or write error", e)
                    }
                } else {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }.apply { start() }

        val param = QwenTtsRealtimeParam.builder()
            .model("qwen3-tts-flash-realtime")
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
            .apikey(apiKey)
            .build()

        qwenTtsRealtime = QwenTtsRealtime(param, object : QwenTtsRealtimeCallback() {
            override fun onOpen() {
                Log.d(TAG, "TTS Connection opened")
            }

            override fun onEvent(message: JsonObject) {
                val type = message.get("type")?.asString
                when (type) {
                    "response.audio.delta" -> {
                        val recvAudioB64 = message.get("delta").asString
                        b64AudioBuffer.add(recvAudioB64)
                    }
                    "session.finished" -> {
                        Log.d(TAG, "TTS Session finished")
                    }
                    "error" -> {
                        Log.e(TAG, "TTS Error: $message")
                    }
                }
            }

            override fun onClose(code: Int, reason: String) {
                Log.d(TAG, "TTS Connection closed: $reason")
            }
        })

        try {
            qwenTtsRealtime?.connect()
            
            val config = QwenTtsRealtimeConfig.builder()
                .voice("Sunny")
                .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                .mode("server_commit")
                .build()
            
            qwenTtsRealtime?.updateSession(config)
            qwenTtsRealtime?.appendText(text)
            qwenTtsRealtime?.finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS Connect error", e)
        }
    }

    /**
     * 停止当前播放
     */
    fun stopSpeaking() {
        isPlaying.set(false)
        playerThread?.interrupt()
        playerThread = null
        b64AudioBuffer.clear()
        
        try {
            qwenTtsRealtime?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TTS", e)
        }
        qwenTtsRealtime = null

        audioTrack?.pause()
        audioTrack?.flush()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopSpeaking()
        audioTrack?.release()
        audioTrack = null
    }
}
