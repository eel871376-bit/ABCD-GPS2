package com.example.gpsjoy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** 조이스틱 뷰: onMove(동쪽성분, 북쪽성분) 값은 -1~1 */
class JoystickView(
    ctx: Context,
    private val onMove: (Float, Float) -> Unit
) : View(ctx) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4DA3FF.toInt() }

    private var kx = 0f
    private var ky = 0f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f * 0.7f
        c.drawCircle(cx, cy, r, basePaint)
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx + kx * r, cy - ky * r, r * 0.35f, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cx = width / 2f
                val cy = height / 2f
                val r = width / 2f * 0.7f
                var dx = e.x - cx
                var dy = cy - e.y
                val m = hypot(dx, dy)
                if (m > r) {
                    dx = dx / m * r
                    dy = dy / m * r
                }
                kx = dx / r
                ky = dy / r
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f
                ky = 0f
            }
        }
        invalidate()
        onMove(kx, ky)
        return true
    }
}
