package com.gyarulens.app

import android.content.Context
import android.content.SharedPreferences

/** 앱 설정 저장소 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gyarulens", Context.MODE_PRIVATE)

    var provider: String
        get() = sp.getString(K_PROVIDER, PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(value) = sp.edit().putString(K_PROVIDER, value).apply()

    var geminiKey: String
        get() = sp.getString(K_GEMINI_KEY, "") ?: ""
        set(value) = sp.edit().putString(K_GEMINI_KEY, value.trim()).apply()

    var claudeKey: String
        get() = sp.getString(K_CLAUDE_KEY, "") ?: ""
        set(value) = sp.edit().putString(K_CLAUDE_KEY, value.trim()).apply()

    var geminiModel: String
        get() = (sp.getString(K_GEMINI_MODEL, "") ?: "").ifBlank { DEFAULT_GEMINI_MODEL }
        set(value) = sp.edit().putString(K_GEMINI_MODEL, value.trim()).apply()

    var claudeModel: String
        get() = (sp.getString(K_CLAUDE_MODEL, "") ?: "").ifBlank { DEFAULT_CLAUDE_MODEL }
        set(value) = sp.edit().putString(K_CLAUDE_MODEL, value.trim()).apply()

    /** 0 = 순한맛, 1 = 보통맛, 2 = 매운맛 */
    var intensity: Int
        get() = sp.getInt(K_INTENSITY, 1)
        set(value) = sp.edit().putInt(K_INTENSITY, value).apply()

    var customStyle: String
        get() = sp.getString(K_CUSTOM_STYLE, "") ?: ""
        set(value) = sp.edit().putString(K_CUSTOM_STYLE, value).apply()

    /** "ko" = 한국어·영어, "ja" = 일본어, "zh" = 중국어 */
    var ocrScript: String
        get() = sp.getString(K_OCR_SCRIPT, "ko") ?: "ko"
        set(value) = sp.edit().putString(K_OCR_SCRIPT, value).apply()

    var autoIntervalSec: Int
        get() = sp.getInt(K_AUTO_INTERVAL, 3)
        set(value) = sp.edit().putInt(K_AUTO_INTERVAL, value).apply()

    var hideWhileCapture: Boolean
        get() = sp.getBoolean(K_HIDE_WHILE_CAPTURE, true)
        set(value) = sp.edit().putBoolean(K_HIDE_WHILE_CAPTURE, value).apply()

    /** 번역 글씨 크기: 0 = 작게, 1 = 보통, 2 = 크게 */
    var textSizeLevel: Int
        get() = sp.getInt(K_TEXT_SIZE, 1)
        set(value) = sp.edit().putInt(K_TEXT_SIZE, value).apply()

    val textScale: Float
        get() = when (textSizeLevel) {
            0 -> 0.8f
            2 -> 1.15f
            else -> 1f
        }

    val apiKey: String
        get() = if (provider == PROVIDER_CLAUDE) claudeKey else geminiKey

    val model: String
        get() = if (provider == PROVIDER_CLAUDE) claudeModel else geminiModel

    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_CLAUDE = "claude"

        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"

        private const val K_PROVIDER = "provider"
        private const val K_GEMINI_KEY = "gemini_key"
        private const val K_CLAUDE_KEY = "claude_key"
        private const val K_GEMINI_MODEL = "gemini_model"
        private const val K_CLAUDE_MODEL = "claude_model"
        private const val K_INTENSITY = "intensity"
        private const val K_CUSTOM_STYLE = "custom_style"
        private const val K_OCR_SCRIPT = "ocr_script"
        private const val K_AUTO_INTERVAL = "auto_interval"
        private const val K_HIDE_WHILE_CAPTURE = "hide_while_capture"
        private const val K_TEXT_SIZE = "text_size_level"
    }
}
