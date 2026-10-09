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
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** 원래 글자 위에 갸루 말투 번역을 덮어 그리는 전체 화면 뷰 (터치는 통과) */
class OverlayView(context: Context) : View(context) {

    /** rect = 원래 글자 위치(화면 좌표) */
    class Item(val rect: RectF, val text: String, val lineCount: Int)

    private class Laid(val box: RectF, val layout: StaticLayout)

    private val laid = ArrayList<Laid>()
    private val location = IntArray(2)
    private val pad = dp(4f)
    private val corner = dp(7f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF2F8")
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#FF4FA3")
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B1030")
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 지금 그려진 번역 상자들(화면 좌표) */
    val boxes: List<RectF>
        get() = laid.map { RectF(it.box) }

    fun hasItems(): Boolean = laid.isNotEmpty()

    fun clear() {
        laid.clear()
        invalidate()
    }

    fun setItems(items: List<Item>, screenW: Int, screenH: Int) {
        laid.clear()
        val minWidth = dp(56f)
        val minSize = sp(9f)
        val maxSize = sp(24f)
        for (item in items) {
            val r = item.rect
            var w = max(r.width(), minWidth)
            if (screenW > 0) w = min(w, screenW.toFloat())
            val innerW = max(1, (w - pad * 2).toInt())

            // 원래 글자 크기에 맞추고, 안 들어가면 조금씩 줄인다
            val lineH = r.height() / max(1, item.lineCount)
            var size = (lineH * 0.8f).coerceIn(minSize, maxSize)
            var layout = makeLayout(item.text, size, innerW)
            while (layout.height > r.height() - pad * 2 && size > minSize) {
                size = max(minSize, size * 0.9f)
                layout = makeLayout(item.text, size, innerW)
            }

            // 그래도 길면 상자를 아래로 늘린다
            val h = max(r.height(), layout.height + pad * 2)
            var left = r.centerX() - w / 2f
            if (screenW > 0) left = left.coerceIn(0f, max(0f, screenW - w))
            var top = r.top
            if (screenH > 0 && top + h > screenH) top = max(0f, screenH - h)
            laid.add(Laid(RectF(left, top, left + w, top + h), layout))
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
            canvas.translate(l.box.left + pad, l.box.top + (l.box.height() - l.layout.height) / 2f)
            l.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
        val paint = TextPaint(textPaint)
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
