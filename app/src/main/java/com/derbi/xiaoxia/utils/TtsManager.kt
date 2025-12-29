package com.derbi.xiaoxia.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.alibaba.dashscope.audio.qwen_tts_realtime.*
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音合成管理类，使用 DashScope 实时语音合成 API
 * 修复了多次点击导致的会话冲突和 AudioTrack 超时问题
 */
class TtsManager(private val apiKey: String) {
    private val TAG = "TtsManager"
    private var audioTrack: AudioTrack? = null
    
    // 使用 Session 对象来隔离多次点击产生的冲突
    private class PlaybackSession(val id: Long) {
        val isActive = AtomicBoolean(true)
        val isReceivingFinished = AtomicBoolean(false)
        val audioBuffer = ConcurrentLinkedQueue<String>()
        var qwenTtsRealtime: QwenTtsRealtime? = null
    }

    private var currentSession: PlaybackSession? = null
    private var sessionCounter = 0L

    private val sampleRate = 24000
    var onCompletionListener: (() -> Unit)? = null

    init {
        initAudioTrack(sampleRate)
    }

    private fun initAudioTrack(sampleRate: Int) {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 适当加大缓冲区，减少由于网络抖动导致的音频卡顿
            val bufferSize = (minBufferSize * 4).coerceAtLeast(minBufferSize)

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
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            Log.d(TAG, "AudioTrack initialized with bufferSize: $bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    /**
     * 开始合成并播放语音
     */
    fun startSpeaking(text: String) {
        if (text.isBlank()) return
        
        // 1. 停止并清理旧 Session
        stopSpeaking()
        
        // 2. 创建新 Session
        val sessionId = ++sessionCounter
        val session = PlaybackSession(sessionId)
        currentSession = session
        
        Log.d(TAG, "[$sessionId] Start speaking (length: ${text.length})")

        // 3. 启动播放线程
        Thread {
            Log.d(TAG, "[$sessionId] Player thread started")
            var hasStartedPlaying = false
            try {
                while (session.isActive.get()) {
                    val b64Audio = session.audioBuffer.poll()
                    if (b64Audio != null) {
                        try {
                            val rawAudio = Base64.decode(b64Audio, Base64.DEFAULT)
                            if (!hasStartedPlaying && session.isActive.get()) {
                                // 只有在确实拿到第一块音频数据时才开启 AudioTrack，防止超时
                                audioTrack?.play()
                                hasStartedPlaying = true
                                Log.d(TAG, "[$sessionId] AudioTrack.play() called")
                            }
                            audioTrack?.write(rawAudio, 0, rawAudio.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "[$sessionId] Decode or write error", e)
                        }
                    } else {
                        if (session.isReceivingFinished.get()) {
                            // 等待一小段时间确保音频流在硬件层播放完毕
                            Thread.sleep(300)
                            Log.d(TAG, "[$sessionId] Playback finished")
                            break
                        }
                        try {
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$sessionId] Player thread error", e)
            } finally {
                // 只有当当前活跃的 Session 还是本 Session 时才执行回调和停止硬件
                if (currentSession?.id == sessionId) {
                    stopAudioHardware()
                    onCompletionListener?.invoke()
                }
                Log.d(TAG, "[$sessionId] Player thread finished")
            }
        }.apply { start() }

        // 4. 启动 TTS 网络线程
        Thread {
            val param = QwenTtsRealtimeParam.builder()
                .model("qwen3-tts-flash-realtime")
                .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
                .apikey(apiKey)
                .build()

            val callback = object : QwenTtsRealtimeCallback() {
                override fun onOpen() {
                    Log.d(TAG, "[$sessionId] TTS Connection opened")
                }

                override fun onEvent(message: JsonObject) {
                    if (!session.isActive.get()) return
                    val type = message.get("type")?.asString
                    when (type) {
                        "response.audio.delta" -> {
                            val delta = message.get("delta")?.asString
                            if (delta != null) {
                                session.audioBuffer.add(delta)
                            }
                        }
                        "session.finished" -> {
                            Log.d(TAG, "[$sessionId] TTS Session finished")
                            session.isReceivingFinished.set(true)
                        }
                        "error" -> {
                            Log.e(TAG, "[$sessionId] TTS Error from server: $message")
                            session.isReceivingFinished.set(true)
                        }
                    }
                }

                override fun onClose(code: Int, reason: String) {
                    Log.d(TAG, "[$sessionId] TTS Connection closed: $reason (code: $code)")
                    session.isReceivingFinished.set(true)
                }
            }

            session.qwenTtsRealtime = QwenTtsRealtime(param, callback)

            try {
                Log.d(TAG, "[$sessionId] Connecting to TTS service...")
                session.qwenTtsRealtime?.connect()
                
                val config = QwenTtsRealtimeConfig.builder()
                    .voice("Sunny")
                    .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .mode("server_commit")
                    .build()
                
                session.qwenTtsRealtime?.updateSession(config)
                
                val chunks = splitText(text, 1000)
                for (chunk in chunks) {
                    if (!session.isActive.get()) break
                    Log.d(TAG, "[$sessionId] Sending chunk: ${chunk.take(15)}...")
                    session.qwenTtsRealtime?.appendText(chunk)
                }
                
                session.qwenTtsRealtime?.finish()
                Log.d(TAG, "[$sessionId] TTS finish() sent")
                
            } catch (e: Exception) {
                Log.e(TAG, "[$sessionId] TTS synthesis error", e)
                session.isReceivingFinished.set(true)
            }
        }.apply { start() }
    }

    fun stopSpeaking() {
        currentSession?.let {
            it.isActive.set(false)
            try {
                it.qwenTtsRealtime?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        currentSession = null
        stopAudioHardware()
    }

    private fun stopAudioHardware() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack hardware", e)
        }
    }

    fun release() {
        stopSpeaking()
        audioTrack?.release()
        audioTrack = null
    }

    /**
     * 将文本分割成符合权重长度限制的短句
     */
    private fun splitText(text: String, maxWeight: Int): List<String> {
        val result = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[。！？；.!?;\\n])"))
        
        var currentChunk = StringBuilder()
        var currentWeight = 0
        
        for (sentence in sentences) {
            val sentenceWeight = calculateWeight(sentence)
            
            if (sentenceWeight > maxWeight) {
                if (currentChunk.isNotEmpty()) {
                    result.add(currentChunk.toString())
                    currentChunk = StringBuilder()
                    currentWeight = 0
                }
                
                for (char in sentence) {
                    val charWeight = if (isCjk(char)) 2 else 1
                    if (currentWeight + charWeight > maxWeight) {
                        result.add(currentChunk.toString())
                        currentChunk = StringBuilder()
                        currentWeight = 0
                    }
                    currentChunk.append(char)
                    currentWeight += charWeight
                }
            } else if (currentWeight + sentenceWeight > maxWeight) {
                if (currentChunk.isNotEmpty()) {
                    result.add(currentChunk.toString())
                }
                currentChunk = StringBuilder(sentence)
                currentWeight = sentenceWeight
            } else {
                currentChunk.append(sentence)
                currentWeight += sentenceWeight
            }
        }
        
        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString())
        }
        
        return result.filter { it.isNotBlank() }
    }

    private fun calculateWeight(text: String): Int {
        var weight = 0
        for (c in text) {
            weight += if (isCjk(c) || c.isLetter()) 2 else 1
        }
        return weight
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        if (code in 0..127) return false
        return code in 0x2E80..0x9FFF || 
               code in 0xAC00..0xD7AF || 
               code in 0x3000..0x30FF || 
               code in 0xFF00..0xFFEF || 
               Character.isLetter(c)
    }
}
