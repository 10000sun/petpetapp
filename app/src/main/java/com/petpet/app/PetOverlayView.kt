package com.petpet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 전체 화면 투명 뷰: 모든 터치를 받아 탭이면 쓰다듬기, 스와이프면 아래 창으로 재생한다. */
class PetOverlayView(private val service: PetPetService) : View(service) {

    private class Pet(val x: Float, val y: Float, val start: Long)

    private val pets = mutableListOf<Pet>()
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; downTime = e.eventTime; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    service.replay(downX, downY, e.rawX, e.rawY, e.eventTime - downTime)
                } else if (service.isOwnIcon(downX, downY)) {
                    service.replay(downX, downY, downX, downY, 40)
                } else {
                    pet(e.x, e.y)
                }
            }
        }
        return true
    }

    private fun pet(x: Float, y: Float) {
        pets += Pet(x, y, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        pets.removeAll { now - it.start > DURATION }
        for (p in pets) drawPet(canvas, p, (now - p.start) / DURATION.toFloat())
        if (pets.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** t: 0..1. 손이 3번 톡톡톡 내려오며 쓰다듬고, 눌릴 때 납작해지며 하트가 떠오른다. */
    private fun drawPet(c: Canvas, p: Pet, t: Float) {
        val s = 56 * d
        val press = 0.5f - 0.5f * cos(t * 3 * 2 * PI.toFloat()) // 0(위)→1(눌림), 3회
        val squash = press * press * press
        val alpha = (min(1f, t / 0.1f) * min(1f, (1f - t) / 0.15f)).coerceIn(0f, 1f)

        // 눌리는 지점 그림자/물결
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF6F91.toInt()
        paint.alpha = (90 * alpha * press).toInt()
        val rx = s * (0.55f + 0.2f * squash)
        rect.set(p.x - rx, p.y + s * 0.1f - s * 0.12f * (1 - squash), p.x + rx, p.y + s * 0.1f + s * 0.12f)
        c.drawOval(rect, paint)

        // 손
        val cy = p.y - s * (0.55f + 0.55f * (1f - press))
        c.save()
        c.translate(p.x, cy)
        c.scale(1f + 0.12f * squash, 1f - 0.18f * squash)
        drawHand(c, s, alpha)
        c.restore()

        // 하트
        if (t > 0.45f) {
            val ht = (t - 0.45f) / 0.55f
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = s * 0.45f
            paint.color = 0xFFFF4D79.toInt()
            for (i in 0..2) {
                val k = max(0f, ht - i * 0.12f)
                paint.alpha = (255 * alpha * (1f - k)).toInt().coerceIn(0, 255)
                val hx = p.x + (i - 1) * s * 0.55f + sin(k * 6 + i) * s * 0.1f
                val hy = p.y - s * 0.6f - k * s * 1.1f
                c.drawText("♥", hx, hy, paint)
            }
        }
    }

    private fun drawHand(c: Canvas, s: Float, alpha: Float) {
        val fill = 0xFFFFD3A8.toInt()
        val line = 0xFFD99A62.toInt()
        fun shape(l: Float, t: Float, r: Float, b: Float, rad: Float) {
            rect.set(l * s, t * s, r * s, b * s)
            paint.style = Paint.Style.FILL; paint.color = fill; paint.alpha = (255 * alpha).toInt()
            c.drawRoundRect(rect, rad * s, rad * s, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.035f * s
            paint.color = line; paint.alpha = (255 * alpha).toInt()
            c.drawRoundRect(rect, rad * s, rad * s, paint)
        }
        // 손가락 4개(아래를 향함)
        val tips = floatArrayOf(0.55f, 0.7f, 0.66f, 0.5f)
        for (i in 0..3) {
            val cx = -0.39f + i * 0.26f
            shape(cx - 0.12f, 0.0f, cx + 0.12f, tips[i], 0.12f)
        }
        // 엄지
        c.save()
        c.rotate(-35f, 0.5f * s, -0.05f * s)
        shape(0.42f, -0.17f, 0.95f, 0.07f, 0.12f)
        c.restore()
        // 손바닥
        shape(-0.55f, -0.5f, 0.55f, 0.2f, 0.3f)
    }

    private companion object {
        const val DURATION = 1100L
    }
}
