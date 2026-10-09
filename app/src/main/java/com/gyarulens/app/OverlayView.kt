package com.gyarulens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 원래 글자 위에 갸루 말투 번역을 덮어 그리는 전체 화면 뷰 (터치는 통과).
 *
 * 글씨 크기 규칙
 * 1. 원래 글자 크기를 넘지 않는다.
 * 2. 원래 글자 자리 안에 들어가도록 글씨를 줄인다.
 * 3. 너무 작아지면 옆/아래의 '빈 공간'으로만 상자를 넓힌다 (다른 글자 자리는 절대 안 덮음).
 * 4. 그래도 안 들어가면 끝을 … 으로 자른다.
 */
class OverlayView(context: Context) : View(context) {

    /** rect = 원래 글자 위치(화면 좌표) */
    class Item(val rect: RectF, val text: String, val lineCount: Int)

    private class Laid(val box: RectF, val layout: StaticLayout)
    private class Fit(val layout: StaticLayout, val size: Float, val fits: Boolean)

    private val laid = ArrayList<Laid>()
    private val location = IntArray(2)
    private val padH = dp(3f)
    private val padV = dp(1.5f)
    private val corner = dp(5f)
    private val gap = dp(2f)
    private val absoluteMin = sp(6f)
    private val absoluteMax = sp(28f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF2F8")
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#FF4FA3")
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B1030")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 지금 그려진 번역 상자들(화면 좌표) */
    val boxes: List<RectF>
        get() = laid.map { RectF(it.box) }

    fun hasItems(): Boolean = laid.isNotEmpty()

    fun clear() {
        laid.clear()
        invalidate()
    }

    /** @param textScale 사용자 설정 글씨 배율 (작게 0.85 / 보통 1 / 크게 1.15) */
    fun setItems(items: List<Item>, screenW: Int, screenH: Int, textScale: Float) {
        laid.clear()
        val sw = if (screenW > 0) screenW.toFloat() else 100000f
        val sh = if (screenH > 0) screenH.toFloat() else 100000f
        val originals = items.map { it.rect }
        val placed = ArrayList<RectF>()

        for ((index, item) in items.withIndex()) {
            val r = item.rect

            // 넘으면 안 되는 곳: 다른 글자들의 원래 자리 + 이미 그린 상자
            val obstacles = ArrayList<RectF>()
            for (j in originals.indices) if (j != index) obstacles.add(originals[j])
            obstacles.addAll(placed)

            // 원래 글자 크기쯤을 목표로, 그보다 크게는 안 함
            val lineH = r.height() / max(1, item.lineCount)
            val target = (lineH * 0.78f * textScale).coerceIn(absoluteMin, absoluteMax)
            val minSize = max(absoluteMin, target * 0.55f)
            val goodEnough = target * 0.9f

            var box = RectF(r)
            var fit = fit(item.text, box, minSize, target)

            if (!fit.fits || fit.size < goodEnough) {
                // 옆 빈 공간으로 넓혀 보기 (최대 원래 폭만큼 더)
                val wide = widen(box, obstacles, sw, r.width())
                if (wide.width() > box.width() + 1f) {
                    val f = fit(item.text, wide, minSize, target)
                    if (better(f, fit)) {
                        box = wide
                        fit = f
                    }
                }
            }
            if (!fit.fits || fit.size < goodEnough) {
                // 아래 빈 공간으로 늘려 보기 (최대 원래 높이의 1.5배 더)
                val tall = growDown(box, obstacles, sh, r.height() * 1.5f)
                if (tall.height() > box.height() + 1f) {
                    val f = fit(item.text, tall, minSize, target)
                    if (better(f, fit)) {
                        box = tall
                        fit = f
                    }
                }
            }

            val layout = if (fit.fits) fit.layout else ellipsized(item.text, box, minSize)
            // 늘린 상자는 실제로 쓰는 높이까지만 (원래 자리보다 작아지진 않게)
            val usedH = max(r.height(), layout.height + padV * 2)
            val finalBox = RectF(box.left, box.top, box.right, min(box.bottom, box.top + usedH))
            laid.add(Laid(finalBox, layout))
            placed.add(finalBox)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (laid.isEmpty()) return
        // 상자 좌표는 화면 기준이라, 이 창이 화면에서 어디 있는지만큼 되돌린다
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for (l in laid) {
            canvas.drawRoundRect(l.box, corner, corner, fillPaint)
            canvas.drawRoundRect(l.box, corner, corner, strokePaint)
            canvas.save()
            canvas.translate(l.box.left + padH, l.box.top + (l.box.height() - l.layout.height) / 2f)
            l.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    // ───────────────────────── 배치 계산 ─────────────────────────

    /** box 안에 들어가는 가장 큰 글씨 크기를 찾는다 (minSize ~ maxSize) */
    private fun fit(text: String, box: RectF, minSize: Float, maxSize: Float): Fit {
        val innerW = max(1, (box.width() - padH * 2).toInt())
        val innerH = box.height() - padV * 2

        val largest = makeLayout(text, maxSize, innerW)
        if (largest.height <= innerH) return Fit(largest, maxSize, true)

        var lo = minSize
        var hi = maxSize
        var best: StaticLayout? = null
        var bestSize = minSize
        for (step in 0 until 7) {
            val mid = (lo + hi) / 2f
            val l = makeLayout(text, mid, innerW)
            if (l.height <= innerH) {
                best = l
                bestSize = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        val found = best
        if (found != null) return Fit(found, bestSize, true)
        val smallest = makeLayout(text, minSize, innerW)
        return Fit(smallest, minSize, smallest.height <= innerH)
    }

    private fun better(candidate: Fit, current: Fit): Boolean {
        if (candidate.fits != current.fits) return candidate.fits
        return candidate.size > current.size + 0.5f
    }

    /** 같은 높이대에 다른 글자가 없는 만큼만 좌우로 넓힌다 */
    private fun widen(box: RectF, obstacles: List<RectF>, screenW: Float, maxExtra: Float): RectF {
        var left = 0f
        var right = screenW
        for (o in obstacles) {
            if (o.bottom <= box.top || o.top >= box.bottom) continue // 다른 높이대
            if (o.left >= box.centerX()) {
                right = min(right, max(box.right, o.left - gap))
            } else {
                left = max(left, min(box.left, o.right + gap))
            }
        }
        val freeL = max(0f, box.left - left)
        val freeR = max(0f, right - box.right)
        val extra = min(maxExtra, freeL + freeR)
        var addL = min(freeL, extra / 2f)
        var addR = min(freeR, extra / 2f)
        val rest = extra - addL - addR
        if (rest > 0f) {
            val moreR = min(freeR - addR, rest)
            addR += moreR
            addL += min(freeL - addL, rest - moreR)
        }
        return RectF(box.left - addL, box.top, box.right + addR, box.bottom)
    }

    /** 아래쪽에 다른 글자가 나올 때까지만 늘린다 */
    private fun growDown(box: RectF, obstacles: List<RectF>, screenH: Float, maxExtra: Float): RectF {
        var limit = min(screenH, box.bottom + maxExtra)
        for (o in obstacles) {
            if (o.right <= box.left || o.left >= box.right) continue // 옆으로 안 겹침
            if (o.bottom <= box.bottom) continue // 위쪽에 있음
            limit = min(limit, max(box.bottom, o.top - gap))
        }
        return RectF(box.left, box.top, box.right, max(box.bottom, limit))
    }

    /** 최소 크기로도 안 들어갈 때: 들어가는 줄까지만 쓰고 끝을 … 으로 */
    private fun ellipsized(text: String, box: RectF, size: Float): StaticLayout {
        val innerW = max(1, (box.width() - padH * 2).toInt())
        val innerH = box.height() - padV * 2
        val paint = paintOf(size)
        val lineHeight = paint.fontSpacing * LINE_MULT
        val lines = max(1, (innerH / lineHeight).toInt())
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_MULT)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paintOf(size), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_MULT)
            .build()

    private fun paintOf(size: Float): TextPaint {
        val p = TextPaint(textPaint)
        p.textSize = size
        return p
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val LINE_MULT = 0.95f
    }
}
