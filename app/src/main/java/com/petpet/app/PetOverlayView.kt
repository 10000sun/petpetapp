package com.petpet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 전체 화면 투명 뷰: 모든 터치를 받아
 * - 스와이프 → 아래 창(런처)에 재생
 * - petpet 앱 아이콘 탭 → 통과
 * - 다른 앱 아이콘 탭 → 그 아이콘 위에만 쓰다듬는 손 애니메이션
 * - 아이콘이 아닌 곳 탭 → 무시
 */
class PetOverlayView(private val service: PetPetService) : View(service) {

    private class Pet(val bounds: Rect, val widget: Boolean, val start: Long)

    private val pets = mutableListOf<Pet>()
    private val d = resources.displayMetrics.density
    // 탭 중 손가락이 살짝 움직여도 스와이프로 재생(=위젯/아이콘이 눌림)되지 않도록 넉넉하게
    private val slop = max(ViewConfiguration.get(service).scaledTouchSlop.toFloat(), 20 * d)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val loc = IntArray(2)
    private val frames = loadFrames(service)

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
                } else {
                    val x = downX
                    val y = downY
                    service.hitIconAsync(x, y) { hit ->
                        if (!isAttachedToWindow) return@hitIconAsync // 그 사이 오버레이가 내려갔으면 무시
                        when {
                            hit == null -> Unit
                            hit.isOwn -> service.replay(x, y, x, y, 40)
                            else -> pet(hit.bounds, hit.isWidget)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun pet(bounds: Rect, widget: Boolean) {
        pets += Pet(bounds, widget, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        pets.removeAll { now - it.start >= DURATION }
        getLocationOnScreen(loc)
        for (p in pets) drawPet(canvas, p, now - p.start)
        if (pets.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawPet(c: Canvas, p: Pet, elapsed: Long) {
        val frame = frames[min((elapsed / FRAME_MS).toInt(), frames.size - 1)]
        val w = min(p.bounds.width(), p.bounds.height()).toFloat()
        val size: Float
        val cx = p.bounds.exactCenterX() - loc[0]
        val cy: Float
        if (p.widget) {
            // 위젯: 위젯 전체 중심을 기준으로 큼직하게
            size = (w * 0.9f).coerceIn(80 * d, 240 * d)
            cy = p.bounds.exactCenterY() - loc[1]
        } else {
            size = (w * HAND_SCALE).coerceIn(56 * d, 200 * d)
            // 아이콘 이미지 중심(라벨 제외) 기준
            cy = p.bounds.top + w / 2f - loc[1]
        }
        dst.set(cx - size * ANCHOR_X, cy - size * ANCHOR_Y, cx + size * (1 - ANCHOR_X), cy + size * (1 - ANCHOR_Y))
        src.set(0, 0, frame.width, frame.height)
        val fadeIn = min(1f, elapsed / 80f)
        val fadeOut = min(1f, (DURATION - elapsed) / 100f)
        paint.alpha = (255 * max(0f, min(fadeIn, fadeOut))).toInt()
        c.drawBitmap(frame, src, dst, paint)
    }

    companion object {
        private const val FRAME_MS = 100L
        private const val DURATION = 11 * FRAME_MS
        private const val HAND_SCALE = 1.7f   // 손 크기 = 아이콘 너비 × 이 값
        private const val ANCHOR_X = 0.5f     // 아이콘 중심이 움짤 프레임의 (x, y) 비율 위치에 오도록
        private const val ANCHOR_Y = 0.68f

        @Volatile private var cache: List<Bitmap>? = null

        /** 중복 디코딩을 막기 위해 동기화 (서비스 연결 시 백그라운드 선로딩 ↔ 오버레이 생성) */
        @Synchronized
        fun loadFrames(ctx: Context): List<Bitmap> = cache ?: List(11) { i ->
            val id = ctx.resources.getIdentifier("petpet_hand_%02d".format(i), "drawable", ctx.packageName)
            BitmapFactory.decodeResource(ctx.resources, id)
        }.also { cache = it }
    }
}
