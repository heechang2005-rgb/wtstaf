package com.gyarulens.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 설정 화면 + 시작/중지 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var overlayStatus: TextView
    private lateinit var startButton: Button
    private lateinit var providerGroup: RadioGroup
    private lateinit var keyInput: EditText
    private lateinit var keyLink: Button
    private lateinit var modelInput: EditText
    private lateinit var intensityGroup: RadioGroup
    private lateinit var styleInput: EditText
    private lateinit var textSizeGroup: RadioGroup
    private lateinit var scriptGroup: RadioGroup
    private lateinit var intervalGroup: RadioGroup
    private lateinit var hideCheck: CheckBox
    private lateinit var testResult: TextView

    private val providerIds = HashMap<String, Int>()
    private val intensityIds = HashMap<Int, Int>()
    private val textSizeIds = HashMap<Int, Int>()
    private val scriptIds = HashMap<String, Int>()
    private val intervalIds = HashMap<Int, Int>()
    private var shownProvider = Prefs.PROVIDER_GEMINI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = PINK
        setContentView(buildUi())
        loadFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        saveToPrefs()
    }

    // ───────────────────────── 화면 구성 ─────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(20), dp(20), dp(40))

        root.addView(TextView(this).apply {
            text = "갸루렌즈 ✨"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PINK)
        })
        root.addView(body("화면에 보이는 글자를 한국어 갸루 말투로 바꿔서 그 자리에 덮어 보여줘요."))

        root.addView(header("1. 권한"))
        overlayStatus = body("")
        root.addView(overlayStatus)
        root.addView(button("'다른 앱 위에 표시' 권한 열기", primary = false) { openOverlaySettings() })

        root.addView(header("2. AI 연결"))
        providerGroup = radioGroup(horizontal = false)
        providerIds[Prefs.PROVIDER_GEMINI] = addRadio(providerGroup, "Gemini (무료 사용량 있음, 추천)")
        providerIds[Prefs.PROVIDER_CLAUDE] = addRadio(providerGroup, "Claude (유료, 아주 저렴)")
        root.addView(providerGroup)
        root.addView(label("API 키"))
        keyInput = input("여기에 API 키 붙여넣기", secret = true)
        root.addView(keyInput)
        keyLink = button("API 키 발급 페이지 열기", primary = false) { openKeyPage() }
        root.addView(keyLink)
        root.addView(label("모델 이름 (모르면 그대로 두기)"))
        modelInput = input("", secret = false)
        root.addView(modelInput)

        root.addView(header("3. 말투"))
        intensityGroup = radioGroup(horizontal = true)
        intensityIds[0] = addRadio(intensityGroup, "순한맛")
        intensityIds[1] = addRadio(intensityGroup, "보통맛")
        intensityIds[2] = addRadio(intensityGroup, "매운맛")
        root.addView(intensityGroup)
        root.addView(label("말투 추가 주문 (선택)"))
        styleInput = input("예: 부산 사투리 섞어 줘 / 이모지는 빼 줘", secret = false, multiLine = true)
        root.addView(styleInput)
        root.addView(label("번역 글씨 크기"))
        textSizeGroup = radioGroup(horizontal = true)
        textSizeIds[0] = addRadio(textSizeGroup, "작게")
        textSizeIds[1] = addRadio(textSizeGroup, "보통")
        textSizeIds[2] = addRadio(textSizeGroup, "크게")
        root.addView(textSizeGroup)
        root.addView(hint("원래 글자 크기에 맞춰 자동으로 줄어들어요. 그래도 크거나 작으면 여기서 조절하세요."))

        root.addView(header("4. 화면 글자 언어"))
        scriptGroup = radioGroup(horizontal = true)
        scriptIds["ko"] = addRadio(scriptGroup, "한국어·영어")
        scriptIds["ja"] = addRadio(scriptGroup, "일본어")
        scriptIds["zh"] = addRadio(scriptGroup, "중국어")
        root.addView(scriptGroup)
        root.addView(hint("일본어·중국어를 골라도 영어는 같이 읽어요."))

        root.addView(header("5. 실시간 모드"))
        root.addView(body("말풍선을 길게 누르면 켜져요. 화면이 바뀌었을 때만 다시 번역해요. 확인 간격:"))
        intervalGroup = radioGroup(horizontal = true)
        intervalIds[2] = addRadio(intervalGroup, "2초")
        intervalIds[3] = addRadio(intervalGroup, "3초")
        intervalIds[5] = addRadio(intervalGroup, "5초")
        root.addView(intervalGroup)
        hideCheck = CheckBox(this).apply {
            text = "캡처하는 순간 번역창 잠깐 숨기기 (권장)"
            setTextColor(INK)
            buttonTintList = ColorStateList.valueOf(PINK)
        }
        root.addView(hideCheck)
        root.addView(hint("안드로이드 14 이상에서 시작할 때 '앱 하나만 공유'를 고르면 이걸 꺼도 돼요. 그러면 깜빡임이 없어요."))

        root.addView(header("6. 테스트 & 시작"))
        root.addView(button("API 키 테스트", primary = false) { runTest() })
        testResult = body("")
        root.addView(testResult)
        startButton = button("시작하기", primary = true) { onStartStop() }
        root.addView(startButton)
        root.addView(
            hint(
                "사용법\n" +
                    "• 말풍선 탭 → 지금 화면 번역\n" +
                    "• 한 번 더 탭 → 번역 지우기\n" +
                    "• 길게 누르기 → 실시간 모드 켜기/끄기 (보라색)\n" +
                    "• 끌어서 위치 이동\n" +
                    "• 끝낼 때는 알림의 '종료' 또는 여기서 '중지하기'\n\n" +
                    "화면 글자가 AI 회사(Google 또는 Anthropic)로 전송돼요. 비밀번호·개인정보 화면에서는 쓰지 마세요."
            )
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.isFillViewport = true
        scroll.addView(root)
        return scroll
    }

    private fun header(t: String): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(INK)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun label(t: String): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(INK)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun body(t: String): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(INK)
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun hint(t: String): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(MUTED)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun input(hintText: String, secret: Boolean, multiLine: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiLine) minLines = 2
            backgroundTintList = ColorStateList.valueOf(PINK)
        }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        val bg = GradientDrawable()
        if (primary) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            bg.cornerRadius = dp(28).toFloat()
            bg.setColor(PINK)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        } else {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(PINK)
            bg.cornerRadius = dp(22).toFloat()
            bg.setColor(SOFT)
            bg.setStroke(dp(1), PINK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        background = bg
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(if (primary) 16 else 8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun radioGroup(horizontal: Boolean): RadioGroup = RadioGroup(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }

    private fun addRadio(group: RadioGroup, label: String): Int {
        val rb = RadioButton(this)
        rb.id = View.generateViewId()
        rb.text = label
        rb.setTextColor(INK)
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        rb.buttonTintList = ColorStateList.valueOf(PINK)
        rb.setPadding(0, 0, dp(12), 0)
        group.addView(rb)
        return rb.id
    }

    // ───────────────────────── 설정 저장/불러오기 ─────────────────────────

    private fun loadFromPrefs() {
        shownProvider = prefs.provider
        providerIds[shownProvider]?.let { providerGroup.check(it) }
        loadKeyAndModel(shownProvider)
        intensityIds[prefs.intensity]?.let { intensityGroup.check(it) }
        (textSizeIds[prefs.textSizeLevel] ?: textSizeIds[1])?.let { textSizeGroup.check(it) }
        scriptIds[prefs.ocrScript]?.let { scriptGroup.check(it) }
        (intervalIds[prefs.autoIntervalSec] ?: intervalIds[3])?.let { intervalGroup.check(it) }
        styleInput.setText(prefs.customStyle)
        hideCheck.isChecked = prefs.hideWhileCapture
        updateKeyLink()
        // 초기값을 채운 다음에 리스너를 단다
        providerGroup.setOnCheckedChangeListener { _, checkedId -> onProviderChanged(checkedId) }
    }

    private fun saveToPrefs() {
        saveKeyAndModel(shownProvider)
        prefs.provider = shownProvider
        prefs.intensity = keyOf(intensityIds, intensityGroup.checkedRadioButtonId) ?: 1
        prefs.textSizeLevel = keyOf(textSizeIds, textSizeGroup.checkedRadioButtonId) ?: 1
        prefs.ocrScript = keyOf(scriptIds, scriptGroup.checkedRadioButtonId) ?: "ko"
        prefs.autoIntervalSec = keyOf(intervalIds, intervalGroup.checkedRadioButtonId) ?: 3
        prefs.customStyle = styleInput.text.toString().trim()
        prefs.hideWhileCapture = hideCheck.isChecked
    }

    private fun <K> keyOf(map: Map<K, Int>, id: Int): K? = map.entries.firstOrNull { it.value == id }?.key

    private fun onProviderChanged(checkedId: Int) {
        val p = keyOf(providerIds, checkedId) ?: return
        if (p == shownProvider) return
        saveKeyAndModel(shownProvider)
        shownProvider = p
        prefs.provider = p
        loadKeyAndModel(p)
        updateKeyLink()
    }

    private fun saveKeyAndModel(provider: String) {
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        if (provider == Prefs.PROVIDER_CLAUDE) {
            prefs.claudeKey = key
            prefs.claudeModel = model
        } else {
            prefs.geminiKey = key
            prefs.geminiModel = model
        }
    }

    private fun loadKeyAndModel(provider: String) {
        if (provider == Prefs.PROVIDER_CLAUDE) {
            keyInput.setText(prefs.claudeKey)
            modelInput.setText(prefs.claudeModel)
        } else {
            keyInput.setText(prefs.geminiKey)
            modelInput.setText(prefs.geminiModel)
        }
    }

    private fun updateKeyLink() {
        keyLink.text = if (shownProvider == Prefs.PROVIDER_CLAUDE) {
            "Claude API 키 발급 페이지 열기"
        } else {
            "Gemini API 키 발급 페이지 열기 (무료)"
        }
    }

    // ───────────────────────── 동작 ─────────────────────────

    private fun refreshStatus() {
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) {
            "✅ 다른 앱 위에 표시: 허용됨"
        } else {
            "❌ 다른 앱 위에 표시: 아직 안 켜짐 (아래 버튼으로 켜 주세요)"
        }
        startButton.text = if (CaptureService.isRunning) "중지하기" else "시작하기"
    }

    private fun onStartStop() {
        saveToPrefs()
        if (CaptureService.isRunning) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            startButton.postDelayed({ refreshStatus() }, 400)
            return
        }
        if (prefs.apiKey.isBlank()) {
            toast("먼저 API 키를 넣어 주세요")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시' 권한을 먼저 켜 주세요")
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // 결과를 받은 뒤 이어서 진행 (거절해도 앱은 동작, 알림만 안 보임)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            toast("이 기기는 화면 캡처를 지원하지 않아요")
            return
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            toast("화면 캡처를 시작할 수 없어요")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("화면 캡처를 허용해야 쓸 수 있어요")
            return
        }
        val intent = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            toast("시작하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        startButton.text = "중지하기"
        toast("화면 오른쪽의 '갸' 말풍선을 눌러 봐 ✨")
        moveTaskToBack(true)
    }

    private fun runTest() {
        saveToPrefs()
        if (prefs.apiKey.isBlank()) {
            toast("API 키를 먼저 넣어 주세요")
            return
        }
        testResult.text = "테스트 중… ⏳"
        val samples = listOf("오늘 날씨 진짜 좋네요", "Settings", "続きを読む")
        val translator = Translator(prefs)
        Thread {
            val text = try {
                val map = translator.translate(samples)
                if (map.isEmpty()) {
                    "응답은 왔는데 비어 있어요 🤔"
                } else {
                    "성공! 🎉\n" + samples.joinToString("\n") { s -> "$s → ${map[s] ?: "(없음)"}" }
                }
            } catch (e: Exception) {
                "실패: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { testResult.text = text }
        }.start()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                toast("설정 화면을 열 수 없어요")
            }
        }
    }

    private fun openKeyPage() {
        val url = if (shownProvider == Prefs.PROVIDER_CLAUDE) {
            "https://platform.claude.com/settings/keys"
        } else {
            "https://aistudio.google.com/apikey"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("브라우저를 열 수 없어요")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REQ_CAPTURE = 41
        private const val REQ_NOTIFY = 42
        private val PINK = Color.parseColor("#FF4FA3")
        private val INK = Color.parseColor("#2B1030")
        private val SOFT = Color.parseColor("#FFF2F8")
        private val MUTED = Color.parseColor("#7A6680")
    }
}
