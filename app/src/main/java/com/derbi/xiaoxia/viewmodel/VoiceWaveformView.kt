package com.derbi.xiaoxia.viewmodel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class VoiceWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90E2") // 可以改成你喜欢的颜色
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private var amplitudes = FloatArray(7) { 0.1f } // 7根跳动的条
    private var targetPower = 0f

    // 更新音量强度 (0.0 ~ 1.0)
    fun updatePower(power: Float) {
        targetPower = power.coerceIn(0.1f, 1.0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val midY = height / 2f
        val spacing = width / (amplitudes.size + 1).toFloat()

        for (i in amplitudes.indices) {
            // 增加一些随机抖动，让波形看起来更灵动
            val randomFactor = 0.8f + Random.nextFloat() * 0.4f
            // 这种平滑处理让动画不那么生硬
            amplitudes[i] = amplitudes[i] * 0.8f + (targetPower * randomFactor) * 0.2f

            val x = spacing * (i + 1)
            val lineHeight = height * 0.8f * amplitudes[i]

            canvas.drawLine(x, midY - lineHeight / 2, x, midY + lineHeight / 2, paint)
        }

        // 如果在跳动中，持续重绘实现动画效果
        if (targetPower > 0.11f) {
            postInvalidateDelayed(30)
        }
    }
}