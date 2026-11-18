package com.example.boxplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Sóng nhạc giả lập: các thanh nhảy lên xuống khi đang phát, hạ xuống thấp khi tạm dừng. */
class WaveView(c: Context, a: AttributeSet?) : View(c, a) {
    private val n = 36
    private val level = FloatArray(n)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var last = 0L

    var active = false
    var tint = 0xFF82B1FF.toInt()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        t += dt
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / n
        val bw = slot * 0.6f
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val env = 0.35f + 0.65f * sin(PI * x).toFloat()
            val wave = sin(t * 3.1 + i * 0.55) + sin(t * 4.7 - i * 0.9) * 0.6 + sin(t * 1.7 + i * 0.2) * 0.4
            val raw = wave.toFloat() / 4f + 0.5f
            val target = if (active) 0.08f + 0.92f * raw * env else 0.03f
            level[i] += (target - level[i]) * min(1f, dt * 10f)
            val bh = max(bw, level[i] * h)
            val left = i * slot + (slot - bw) / 2
            rect.set(left, (h - bh) / 2, left + bw, (h + bh) / 2)
            paint.color = tint
            paint.alpha = (110 + level[i] * 145).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
        postInvalidateOnAnimation()
    }
}
