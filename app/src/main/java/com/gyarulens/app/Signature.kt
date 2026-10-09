package com.gyarulens.app

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 화면을 작은 격자(칸마다 평균 밝기)로 요약한 것.
 * 실시간 모드에서 "화면이 바뀌었는지"를 싸게 판단하는 데 쓴다.
 */
class Signature private constructor(
    private val cols: Int,
    private val rows: Int,
    private val srcW: Int,
    private val srcH: Int,
    private val lum: FloatArray
) {

    /**
     * 두 화면에서 밝기가 눈에 띄게 달라진 칸의 비율(0~1).
     * @param masks 비교에서 뺄 영역(이 시그니처를 만든 비트맵 좌표). 번역창·말풍선 자리.
     */
    fun changedFraction(other: Signature, masks: List<RectF>): Float {
        if (other.cols != cols || other.rows != rows || other.srcW != srcW || other.srcH != srcH) return 1f
        val cellW = srcW.toFloat() / cols
        val cellH = srcH.toFloat() / rows
        var considered = 0
        var changed = 0
        for (r in 0 until rows) {
            val top = r * cellH
            val bottom = top + cellH
            for (c in 0 until cols) {
                val left = c * cellW
                val right = left + cellW
                var masked = false
                for (m in masks) {
                    if (m.left < right && m.right > left && m.top < bottom && m.bottom > top) {
                        masked = true
                        break
                    }
                }
                if (masked) continue
                considered++
                val i = r * cols + c
                if (abs(lum[i] - other.lum[i]) > DIFF_LEVEL) changed++
            }
        }
        if (considered == 0) return 0f
        return changed.toFloat() / considered
    }

    companion object {
        private const val COLS = 36
        private const val STEP = 4
        private const val DIFF_LEVEL = 10f

        fun of(bmp: Bitmap): Signature {
            val w = bmp.width
            val h = bmp.height
            val rows = max(8, (COLS.toFloat() * h / w).toInt())
            val sum = FloatArray(COLS * rows)
            val count = IntArray(COLS * rows)
            val row = IntArray(w)
            var y = 0
            while (y < h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val cy = min(rows - 1, y * rows / h)
                var x = 0
                while (x < w) {
                    val p = row[x]
                    val l = 0.299f * ((p shr 16) and 0xff) + 0.587f * ((p shr 8) and 0xff) + 0.114f * (p and 0xff)
                    val i = cy * COLS + min(COLS - 1, x * COLS / w)
                    sum[i] += l
                    count[i]++
                    x += STEP
                }
                y += STEP
            }
            for (i in sum.indices) if (count[i] > 0) sum[i] /= count[i]
            return Signature(COLS, rows, w, h, sum)
        }
    }
}
