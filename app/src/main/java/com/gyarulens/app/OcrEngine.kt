package com.gyarulens.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** ML Kit로 화면 글자 덩어리(블록)를 읽는다. 작업 스레드에서만 호출할 것. */
class OcrEngine {

    /** rect는 화면 좌표(px) */
    class Block(val rect: RectF, val text: String, val lineCount: Int)

    private var recognizer: TextRecognizer? = null
    private var script = ""

    /**
     * @param scale 캡처 이미지 크기 / 실제 화면 크기
     * @param exclude 이 사각형(화면 좌표) 안에 중심이 있는 글자는 무시 (말풍선 버튼)
     */
    fun recognize(bitmap: Bitmap, script: String, scale: Float, exclude: Rect?): List<Block> {
        val result: Text = Tasks.await(recognizerFor(script).process(InputImage.fromBitmap(bitmap, 0)))
        val out = ArrayList<Block>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            val rect = RectF(box.left / scale, box.top / scale, box.right / scale, box.bottom / scale)
            if (exclude != null && exclude.contains(rect.centerX().toInt(), rect.centerY().toInt())) continue

            val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val text = joinLines(lines)
            if (!worthTranslating(text)) continue

            out.add(Block(rect, text, lines.size))
            if (out.size >= MAX_BLOCKS) break
        }
        return out
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        script = ""
    }

    private fun recognizerFor(s: String): TextRecognizer {
        val current = recognizer
        if (current != null && s == script) return current
        current?.close()
        val created = when (s) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        recognizer = created
        script = s
        return created
    }

    /** 한·영은 줄을 띄어쓰기로, 일본어·중국어끼리는 붙여서 잇는다. */
    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder(lines[0])
        for (i in 1 until lines.size) {
            val next = lines[i]
            if (isCjk(sb[sb.length - 1]) && isCjk(next[0])) sb.append(next) else sb.append(' ').append(next)
        }
        return sb.toString()
    }

    private fun worthTranslating(s: String): Boolean {
        var letters = 0
        for (c in s) if (Character.isLetter(c)) letters++
        if (letters == 0) return false // 숫자, 시간, 기호뿐
        if (s.length == 1 && !isCjk(s[0]) && !isHangul(s[0])) return false // 영어 한 글자 등
        return true
    }

    private fun isHangul(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            b == Character.UnicodeBlock.HANGUL_JAMO ||
            b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    companion object {
        private const val MAX_BLOCKS = 60
    }
}
