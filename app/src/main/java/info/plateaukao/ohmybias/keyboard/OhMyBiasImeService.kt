package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import info.plateaukao.ohmybias.MainActivity
import info.plateaukao.ohmybias.UserPhrasesActivity
import android.text.InputType
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.ClipboardBridge
import info.plateaukao.ohmybias.shared.InputEngine
import info.plateaukao.ohmybias.shared.InputEngineDelegate
import info.plateaukao.ohmybias.shared.SkinSettings
import kotlin.math.abs

/// Android IME 主 service — InputEngine 的 Android delegate 實作。
/// 對應 iOS 版 KeyboardViewController：按鍵事件 → InputEngine → delegate 回呼 → UI。
class OhMyBiasImeService : InputMethodService(), InputEngineDelegate, HardwareKeyHandler.Host {

    override lateinit var engine: InputEngine

    /// 實體鍵盤接上時的畫面模式（Prefs.hardKeyboardMode）；NONE = 沒接實體鍵盤、照常
    private enum class HwMode { NONE, KEYPAD, FLOATING, BAR }

    /// IME 根視圖：框架以 WRAP_CONTENT 掛載輸入視圖；實體鍵盤的浮動／底列模式要鋪滿
    /// 整個視窗高度（氣泡才擺得到游標旁、toast 才有地方浮），把 AT_MOST 改成 EXACTLY 撐滿。
    /// app 看到的 IME 高度不受影響 — 由 onComputeInsets 另行回報。
    private class ImeRootLayout(context: android.content.Context) : LinearLayout(context) {
        var fillHeight = false
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (fillHeight && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST)
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY)
            else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    private var rootView: ImeRootLayout? = null
    /// 底部面板 = 候選列 + 鍵盤本體（導覽列 padding 墊在這層；覆蓋模式時根視圖透明）
    private var panelView: LinearLayout? = null
    private var candidateBar: CandidateBar? = null
    private var keyboardView: KeyboardView? = null
    private var toastLabel: TextView? = null
    private var toastFrame: FrameLayout? = null
    /// 浮動候選容器（浮動／底列模式才有；鋪滿面板上方的空間）
    private var floatingHost: FloatingCandidateHost? = null
    /// 浮動鍵盤層（Prefs.floatingKeyboard 開著、且沒接實體鍵盤覆蓋模式時才有）
    private var floatingLayer: FloatingKeyboardLayer? = null
    private var builtFloating = false
    private var builtHwMode = HwMode.NONE
    private val hwKeys = HardwareKeyHandler(this)
    /// 候選列目前內容的鏡像 — 浮動氣泡與底列共用同一份狀態
    private var barComposingText = ""
    private var barCandidates: List<String> = emptyList()
    private var barSuggestions = false
    /// 本次輸入階段 requestCursorUpdates 是否已成功（沒有 → 氣泡退回貼底；游標一動再補要）
    private var cursorUpdatesRequested = false
    private val handler = Handler(Looper.getMainLooper())
    private var toastHideRunnable: Runnable? = null

    /// 候選列目前顯示的是聯想詞（composing 為空時點選直接送出）
    override var showingSuggestions = false

    private var builtForDark = false
    private var builtBodyHeight = 0
    /// 尺寸類偏好即時生效 — 設定頁拖滑桿時鍵盤就跟著變（同 process；
    /// SharedPreferences 只保 weak reference，必須自己持有這個欄位）
    private val prefsListener = android.content.SharedPreferences
        .OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "keyboardHeightScale", "hardKeyboardMode", "toolbarButtons", "floatingKeyboard" ->
                    handler.post { rebuildForHeightChange() }
                "keySpacingScale" -> handler.post { keyboardView?.requestLayout() }
            }
        }
    /// 建這組 view 時的皮膚世代 —— 設定頁匯入 .cskin 後（同 process）整個 view 要重建，
    /// 否則候選列工具列與鍵面會用不同皮膚（工具列按鈕只在 CandidateBar 建構時讀一次）
    private var builtSkinGeneration = -1
    /// 引擎目前載入的字表世代 —— 設定頁匯入新 liu.cin 後要重載
    private var loadedTableGeneration = CINTable.generation
    /// 目前欄位是密碼類（含 visiblePassword）— 暫時切英文直通、離開欄位還原使用者的中英狀態。
    /// 密碼不該經過組字；常用語設定頁的「組字碼」欄也靠這個讓使用者直接打碼不被組成中文。
    private var forcedEnglishForField = false

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    // MARK: - Lifecycle

    override fun onCreate() {
        super.onCreate()
        // 浮動鍵盤／實體鍵盤覆蓋模式的根視圖是透明層；Android 10 以下 IME 視窗的 decor
        // 會在根視圖沒蓋到的區域（實測 A7 底部留了 53px）畫出不透明底色，變成一條蓋住
        // app 的空白帶 — 把視窗背景明確設為透明
        window.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        suppressNavScrim()
        SkinSettings.shared.reload()
        engine = InputEngine()
        engine.delegate = this
        engine.loadTable()
        // 還原上次使用的語言模式（EN/中文）
        engine.setEnglishMode(Prefs.lastEnglishMode)
        warmUpReverseCache()
        Prefs.addListener(prefsListener)
    }

    /// 反查表（查碼提示/注音同音字）整表建立成本高 — 背景預熱，
    /// 免得第一次用到的那個按鍵卡住（取法 sweetlime 的 prefetchCache）
    private fun warmUpReverseCache() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            engine.cinTable.warmUpReverseCache()
        }, "ohmybias-warmup").start()
    }

    /// 輸入階段結束（換輸入框／換 app）—— 未送出的組字狀態不能跨欄位存活，
    /// 否則新欄位的第一個空白鍵會把上一個欄位遺留的候選字送出去。
    override fun onFinishInput() {
        super.onFinishInput()
        engine.resetSession()
        showingSuggestions = false
        cursorUpdatesRequested = false
        floatingHost?.setAnchor(null)
        refreshIdleBar()
    }

    override fun onDestroy() {
        Prefs.removeListener(prefsListener)
        super.onDestroy()
    }

    /// 高度改變 — 只改 layoutParams IME 視窗不會可靠重量測，整組視圖重建
    /// （鍵面尺寸/字級都在建構時算好）；組字狀態留在 engine，重建後補回候選列與頁面
    private fun rebuildForHeightChange() {
        if (rootView == null) return
        setInputView(onCreateInputView())
        // 新建的 KeyboardView 用預設 Enter 標籤／🌐 鍵狀態 — 補回目前欄位的值
        keyboardView?.syncSessionState(
            shouldOfferSwitching() && !Prefs.hideGlobeKey,
            returnLabel(currentInputEditorInfo),
        )
        refreshIdleBar()
        syncPageWithEngine()
        updateBarModeBody()
        requestCursorUpdatesIfFloating()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // MARK: - 實體鍵盤

    private fun hardKeyboardPresent(): Boolean {
        val c = resources.configuration
        return c.keyboard != Configuration.KEYBOARD_NOKEYS &&
            c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    }

    private fun currentHwMode(): HwMode {
        if (!hardKeyboardPresent()) return HwMode.NONE
        return when (Prefs.hardKeyboardMode) {
            Prefs.HW_MODE_FLOATING -> HwMode.FLOATING
            Prefs.HW_MODE_BAR -> HwMode.BAR
            else -> HwMode.KEYPAD
        }
    }

    /// 浮動／底列：根視圖鋪滿、鍵盤本體收起、可觸區由 onComputeInsets 決定
    private val isOverlayMode get() = builtHwMode == HwMode.FLOATING || builtHwMode == HwMode.BAR

    /// 浮動鍵盤：工具列 ID 33 開關。實體鍵盤的浮動／底列模式本來就沒有鍵盤本體可浮，不套用
    private fun wantFloating(hwMode: HwMode): Boolean =
        Prefs.floatingKeyboard && (hwMode == HwMode.NONE || hwMode == HwMode.KEYPAD)

    /// 接了實體鍵盤一律顯示我們的視窗（三種模式都需要：鍵盤／底列／透明浮動層），
    /// 不理會系統「實體鍵盤時顯示虛擬鍵盤」開關 — 關掉的話組字就看不見了
    override fun onEvaluateInputViewShown(): Boolean =
        if (hardKeyboardPresent()) true else super.onEvaluateInputViewShown()

    /// 框架預設：有實體鍵盤且系統開關關閉時，app 隱性的 showSoftInput 直接被擋 — 同上理由放行
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean =
        if (hardKeyboardPresent()) true else super.onShowInputRequested(flags, configChange)

    /// 覆蓋模式的視窗是整片透明層：告訴系統「內容」只從底部面板算起（app 不會被整片
    /// 推上去），可觸區只有面板與浮動氣泡本身，其餘觸控穿透到 app
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = rootView ?: return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        // 浮動鍵盤：整片透明層，app 內容完全不被推上去（內容高度 = 0）；只有卡片可觸。
        // 注意用 decor 高度而非根視圖底 — Android 10 的 IME content frame 會在底部保留
        // 導覽列高度（A7 實測 53px），根視圖到不了視窗底；用根視圖底當內容起點的話，
        // app 會永遠留著 53px 的底部 inset，adjustResize 的 app 就在畫面底部露出
        // 一條自家底色的空白帶
        floatingLayer?.let { layer ->
            val bottom = window.window?.decorView?.height?.takeIf { it > 0 } ?: (loc[1] + root.height)
            outInsets.contentTopInsets = bottom
            outInsets.visibleTopInsets = bottom
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
            val card = Rect()
            if (layer.cardRectInWindow(card)) outInsets.touchableRegion.union(card)
            return
        }
        if (!isOverlayMode) return
        val panel = panelView
        val panelShown = panel != null && panel.visibility == View.VISIBLE
        // 同上：面板收起時內容起點要用 decor 高度（視窗真正的底），不是根視圖底
        val contentTop = if (panelShown) loc[1] + panel.top
                         else window.window?.decorView?.height?.takeIf { it > 0 } ?: (loc[1] + root.height)
        outInsets.contentTopInsets = contentTop
        outInsets.visibleTopInsets = contentTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        if (panelShown) {
            outInsets.touchableRegion.union(Rect(loc[0], contentTop, loc[0] + root.width, loc[1] + root.height))
        }
        val bubble = Rect()
        if (floatingHost?.bubbleRectInWindow(bubble) == true) outInsets.touchableRegion.union(bubble)
    }

    private fun requestCursorUpdatesIfFloating() {
        if (builtHwMode != HwMode.FLOATING) return
        cursorUpdatesRequested = try {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
            ) == true
        } catch (e: Exception) {
            false  // 部分編輯器不支援 — 氣泡退回貼底
        }
    }

    /// 切輸入框當下的 requestCursorUpdates 偶爾回 false（連線尚未 active）— 游標一動再補要一次
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (builtHwMode == HwMode.FLOATING && !cursorUpdatesRequested) requestCursorUpdatesIfFloating()
    }

    /// 游標位置（螢幕座標，經 matrix 轉換）→ 浮動容器座標。游標在畫面外或欄位不回報時
    /// 清掉錨點，氣泡退回貼底置中
    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(info)
        val host = floatingHost ?: return
        val x = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val bottom = info.insertionMarkerBottom
        val visible = (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION) != 0
        if (x.isNaN() || top.isNaN() || bottom.isNaN() || !visible) {
            host.setAnchor(null)
            return
        }
        val pts = floatArrayOf(x, top, x, bottom)
        info.matrix.mapPoints(pts)
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        host.setAnchor(RectF(pts[0] - loc[0], pts[1] - loc[1], pts[2] - loc[0], pts[3] - loc[1]))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentInputConnection != null && hwKeys.onKeyDown(keyCode, event)) return true
        // 浮動模式的視窗幾乎看不見 — Back 不該被它吃掉（預設會先收 IME 視窗）；
        // 組字中則當 Esc 用
        if (keyCode == KeyEvent.KEYCODE_BACK && builtHwMode == HwMode.FLOATING) {
            if (engine.composing.isNotEmpty() || engine.isInSpecialMode) { engine.handleEscape(); return true }
            if (showingSuggestions) { clearSuggestions(); return true }
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hwKeys.onKeyUp(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_BACK && builtHwMode == HwMode.FLOATING) return false
        return super.onKeyUp(keyCode, event)
    }

    // HardwareKeyHandler.Host
    override fun commitText(text: String) = commitToEditor(text)
    override fun handleLetter(ch: String) = handleLetterKey(ch)
    override fun toggleLanguage() {
        if (engine.composing.isNotEmpty()) engine.handleEscape()
        if (showingSuggestions) clearSuggestions()
        applyLanguageToggle()
        // 浮動模式沒有工具列的米/英可看 — 提示一下切到哪
        if (builtHwMode == HwMode.FLOATING) showToast(if (engine.isEnglishMode) "英" else "米", 0.8)
    }
    override fun ensureShown() {
        if (!isInputViewShown) requestShowSelf(0)
    }

    /// 底列模式：鍵盤本體平常收著，只在工具列開了面板（符號/emoji/常用語/123）時展開
    private fun updateBarModeBody() {
        if (builtHwMode != HwMode.BAR) return
        val frame = toastFrame ?: return
        val want = if (keyboardView?.isShowingToolbarPage == true) View.VISIBLE else View.GONE
        if (frame.visibility != want) frame.visibility = want
    }

    /// 浮動切換後的短時間窗：期間視窗被（app 反射性 hideSoftInput）藏掉就重新顯示一次
    private var floatingToggleGuardUntil = 0L

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (SystemClock.uptimeMillis() < floatingToggleGuardUntil) {
            floatingToggleGuardUntil = 0  // 只救一次 — app 堅持要藏就隨它
            handler.post { requestShowSelf(0) }
        }
    }

    /// 導覽模式（手勢 ↔ 3 鍵）在鍵盤收起時切換的話，既有視圖不會收到新 insets
    /// 派發，padding 停在舊值 → 導覽列又蓋回最下排。每次視窗顯示時主動要求
    /// 重新派發，讓 onCreateInputView 掛的 listener 拿到當下的導覽列高度。
    /// Android 10（Q）IME 視窗的 DecorView 在底部放一條 navigationBarColor 的 scrim View
    /// （A7 實測 53px 純白，浮動模式時就是那條蓋住 app 的白帶）。實測光設
    /// navigationBarColor 透明會被框架在顯示流程蓋回白色 — 三管齊下：設透明色、
    /// 清 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS、把 decor 直屬的 scrim View（框架用
    /// 裸 View 畫 status/nav 底色）alpha 歸零 — updateColorViews 會重設底色與
    /// visibility，但不動 alpha，歸零後怎麼重畫都看不見
    private fun suppressNavScrim() {
        if (Build.VERSION.SDK_INT >= 35) return
        val w = window.window ?: return
        @Suppress("DEPRECATION")
        if (w.navigationBarColor != Color.TRANSPARENT) w.navigationBarColor = Color.TRANSPARENT
        w.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        (w.decorView as? android.view.ViewGroup)?.let { decor ->
            for (i in 0 until decor.childCount) {
                val c = decor.getChildAt(i)
                if (c.javaClass == View::class.java && c.alpha != 0f) c.alpha = 0f
            }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        suppressNavScrim()
        handler.post { suppressNavScrim() }  // decor 的 scrim 可能在顯示流程稍後才建立
        if (Build.VERSION.SDK_INT >= 35) {
            rootView?.let { r ->
                r.rootWindowInsets?.let { applyNavBarPadding(it) }
                r.requestApplyInsets()
            }
        }
    }

    /// 墊在底部面板（候選列＋鍵盤）上；浮動容器同樣墊，退回貼底的氣泡才不會壓到導覽列
    @TargetApi(35)
    private fun applyNavBarPadding(insets: WindowInsets) {
        // 浮動鍵盤：墊在整片浮動層上（卡片夾在 padding 之上，不會壓到導覽列）
        val v = floatingLayer ?: panelView ?: return
        // navigationBars ∪ tappableElement：Android 15/16 導覽列在 navigationBars；
        // Android 17 起收鍵盤箭頭/地球飾件列只算在 tappableElement（nav 只剩手勢 pill）
        val type = WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()
        val insetBottom = insets.getInsets(type).bottom
        // One UI（Samsung S25U 實測）系統本來就把 IME 排在導覽列上方、卻仍回報
        // 導覽列 inset — 照抄會多墊出一整條空白。故墊的是「視圖實際延伸進導覽列
        // 區域」的重疊量：AOSP 視圖底邊＝螢幕底 → 全額；One UI 底邊已在導覽列
        // 頂 → 0。視圖底邊由框架錨定，不受自身 padding 影響，計算收斂不震盪。
        var pad = insetBottom
        if (v.isLaidOut) {
            val screenBottom = getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds.bottom
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            pad = (loc[1] + v.height - (screenBottom - insetBottom)).coerceIn(0, insetBottom)
        }
        if (pad != v.paddingBottom) {
            v.setPadding(0, 0, 0, pad)
            // 顯示中重建 view（浮動 ↔ 貼底切換）時 insets 派發落在同一輪 traversal 的量測之後，
            // setPadding 的 requestLayout 被吞掉、面板以 0 padding 定案 — 補排一次 layout
            if (!v.isLaidOut) v.post { v.requestLayout() }
        }
        floatingHost?.let { h -> if (h.paddingBottom != pad) h.setPadding(0, 0, 0, pad) }
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        KeyboardTheme.isDark = isDarkMode()
        KeyboardTheme.keyFontScale = minOf(Prefs.keyboardHeightScale, 1.2f)
        builtForDark = KeyboardTheme.isDark
        builtSkinGeneration = SkinSettings.shared.generation
        val hwMode = currentHwMode()
        builtHwMode = hwMode
        val overlay = hwMode == HwMode.FLOATING || hwMode == HwMode.BAR
        val floating = wantFloating(hwMode)
        builtFloating = floating
        builtBodyHeight = keyboardBodyHeight()

        val root = ImeRootLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.clipToPadding = false
        root.fillHeight = overlay || floating

        // 浮動鍵盤層先建 — 卡片寬度決定鍵面字級（KeyButton 繪製時讀 KeyboardTheme）
        val layer = if (floating) FloatingKeyboardLayer(this, dp(CandidateBar.BAR_HEIGHT_DP), builtBodyHeight) else null
        floatingLayer = layer
        if (layer != null) KeyboardTheme.keyFontScale *= floatingFontFactor(layer)

        // 底部面板：候選列＋鍵盤本體。導覽列 padding 墊在這層而不是根視圖 —
        // 覆蓋模式根視圖鋪滿整個視窗且透明，只有面板有底色
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.clipChildren = false
        panel.clipToPadding = false
        panel.setBackgroundColor(KeyboardTheme.toolbarBackground)
        panelView = panel

        // 浮動候選容器：覆蓋模式鋪滿面板上方（weight 1）；一般模式不掛
        val host = if (overlay) FloatingCandidateHost(this) else null
        floatingHost = host
        if (host != null) {
            root.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            host.bubble.onSelect = { idx -> didSelectCandidate(idx) }
            host.bubble.onCommitComposing = { engine.commitComposingRaw() }
            host.bubble.onDismissSuggestions = { clearSuggestions() }
        }
        if (layer != null) {
            layer.attach(panel)
            root.addView(layer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        } else {
            root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Android 15 起 targetSdk 35+ 的 IME 視窗強制 edge-to-edge，會延伸到系統
        // 導覽列底下 — 不自己墊高的話 3 鍵導覽列／手勢區會直接蓋住最下排按鍵。
        // 舊版系統由框架自動把 IME 排在導覽列上方，不需處理。
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                applyNavBarPadding(insets)
                insets
            }
            // insets 派發常在 layout 前（isLaidOut=false 時先全額墊）；layout 完成後
            // 用實際幾何重算一次，把 One UI 的多餘 padding 修掉（值不變就不再觸發）。
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.rootWindowInsets?.let { applyNavBarPadding(it) }
            }
        }

        val bar = CandidateBar(this)
        panel.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(CandidateBar.BAR_HEIGHT_DP)))

        // 鍵盤本體外再包一層 FrameLayout 供 toast 疊加
        val frame = FrameLayout(this)
        frame.clipChildren = false
        frame.clipToPadding = false
        val kv = KeyboardView(this)
        kv.isEnglishMode = engine.isEnglishMode
        frame.addView(kv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val toast = TextView(this)
        toast.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        toast.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(0xBF000000.toInt())
        bg.cornerRadius = dp(10f).toFloat()
        toast.background = bg
        toast.gravity = Gravity.CENTER
        toast.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        toast.visibility = View.GONE
        frame.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 浮動：本體吃掉卡片剩餘高度（縮放卡片即縮放鍵盤）；貼底：固定高度
        panel.addView(frame, if (layer != null) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                             else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, builtBodyHeight))
        layer?.onResizeEnd = {
            // 寬度定案 → 重算字級、重建鍵面（KeyButton 字級在繪製時讀，重建即套用）
            KeyboardTheme.keyFontScale = minOf(Prefs.keyboardHeightScale, 1.2f) * floatingFontFactor(layer)
            keyboardView?.reloadKeys()
        }

        when (hwMode) {
            // 浮動：沒有底列也沒有鍵盤 — 面板整個收起，只剩透明浮動層
            HwMode.FLOATING -> panel.visibility = View.GONE
            // 底列：只留候選列（含工具列）；本體開面板時才展開（updateBarModeBody）
            HwMode.BAR -> frame.visibility = View.GONE
            else -> {}
        }

        bar.onSelect = { idx -> didSelectCandidate(idx) }
        bar.onCommitComposing = {
            haptic()
            engine.commitComposingRaw()
        }
        bar.onToolbarKey = { action ->
            haptic()
            // 聯想列是覆蓋在工具列上的 —— 點得到的那幾顆鍵要先把聯想收掉再做事，
            // 否則按完（例如收折鍵盤）下次開鍵盤還留著上一輪的過期聯想
            if (showingSuggestions) clearSuggestions()
            handleKey(action)
        }
        bar.onDismissSuggestions = {
            haptic()
            clearSuggestions()
        }
        kv.onKey = { action -> handleKey(action) }
        kv.onHapticDown = { haptic() }

        rootView = root
        candidateBar = bar
        keyboardView = kv
        toastLabel = toast
        toastFrame = frame
        barComposingText = ""; barCandidates = emptyList(); barSuggestions = false
        refreshIdleBar()
        return root
    }

    private fun keyboardBodyHeight(): Int {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = if (landscape) 180f else 224f
        return dp(base * Prefs.keyboardHeightScale)
    }

    /// 浮動卡片比整寬窄時鍵面字級跟著縮（下限 0.7），滿寬則不變
    private fun floatingFontFactor(layer: FloatingKeyboardLayer): Float =
        layer.widthRatio.coerceIn(0.7f, 1f)

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定頁剛匯入新字表（同 process）— 重載，否則鍵盤一直用舊表直到 process 重啟
        if (CINTable.generation != loadedTableGeneration) {
            loadedTableGeneration = CINTable.generation
            engine.loadTable()
            warmUpReverseCache()
        }
        // 深淺色、轉向、高度或皮膚改變時重建整個鍵盤（顏色/尺寸/工具列按鈕都在建構時
        // 解析；直接改 layoutParams IME 視窗不會可靠重量測）
        if (isDarkMode() != builtForDark || keyboardBodyHeight() != builtBodyHeight ||
            SkinSettings.shared.generation != builtSkinGeneration || currentHwMode() != builtHwMode ||
            wantFloating(currentHwMode()) != builtFloating
        ) {
            setInputView(onCreateInputView())
        }
        requestCursorUpdatesIfFloating()
        // 密碼類欄位：暫時英文直通（不寫 Prefs.lastEnglishMode — 離開欄位就還原）
        forcedEnglishForField = isPasswordField(info)
        engine.setEnglishMode(if (forcedEnglishForField) true else Prefs.lastEnglishMode)
        keyboardView?.isEnglishMode = engine.isEnglishMode
        keyboardView?.syncSessionState(shouldOfferSwitching() && !Prefs.hideGlobeKey, returnLabel(info))
        keyboardView?.syncSpacingIfNeeded()
        refreshIdleBar()
        syncPageWithEngine()
        updateBarModeBody()
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    private fun shouldOfferSwitching(): Boolean = try {
        shouldOfferSwitchingToNextInputMethod()
    } catch (e: Exception) {
        true
    }

    /// Enter 鍵依 host app 的 imeOptions 顯示（搜尋/前往/送出…）
    private fun returnLabel(info: EditorInfo?): String {
        return when ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "搜尋"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEND -> "送出"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_NEXT -> "下一個"
            else -> "⏎"
        }
    }

    // MARK: - Key handling

    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
    }

    private fun haptic() {
        if (!Prefs.hapticFeedback) return
        val strength = Prefs.hapticStrength
        if (strength <= 0) {
            // 系統預設：KEYBOARD_TAP 跟隨系統觸覺強度設定
            rootView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            vibrator.vibrate(info.plateaukao.ohmybias.android.customHapticEffect(strength))
        }
    }

    private fun handleKey(action: KeyAction) {
        if (action !is KeyAction.Shift) keyboardView?.shiftState?.interruptTapSequence()
        // 觸覺回饋不在這裡給 — 鍵面在按下瞬間經 kv.onHapticDown 震（issue #4）；
        // 工具列等 click 路徑各自呼叫 haptic()
        when (action) {
            is KeyAction.Letter -> handleLetterKey(action.ch)
            is KeyAction.Space -> handleSpaceKey()
            is KeyAction.Backspace -> handleBackspaceKey()
            is KeyAction.Newline -> handleReturnKey()
            is KeyAction.Symbol -> {
                // 符號頁直接送出（不經引擎組字）；成對標點仍補右半並把游標放中間
                engine.handleEscape()
                val right = engine.pairedRight(action.s)
                if (right != null) commitPair(action.s, right) else commitToEditor(action.s)
            }
            is KeyAction.ToggleLanguage -> applyLanguageToggle()
            is KeyAction.Page -> keyboardView?.showPage(action.page)
            is KeyAction.ToggleToolbarPage -> keyboardView?.toggleToolbarPage(action.page)
            is KeyAction.Shift -> {
                keyboardView?.let {
                    it.shiftState.tap(android.os.SystemClock.uptimeMillis(), android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
                    it.reloadKeys()
                }
            }
            is KeyAction.CapsLock -> {
                keyboardView?.let { it.shiftState.lock(); it.reloadKeys() }
            }
            is KeyAction.ZhuyinSymbol -> engine.handleZhuyinSymbol(action.zy)
            is KeyAction.ZhuyinTone -> engine.handleZhuyinTone(action.tone)
            is KeyAction.ZhuyinExit -> {
                engine.exitZhuyinMode()
                keyboardView?.currentPage = KeyboardView.PageKind.LETTERS
                keyboardView?.reloadKeys()
            }
            is KeyAction.SelectCandidateShortcut -> {
                // 次選/三選上屏（sweetlime n/m 上滑）— 無候選時不動作
                if (engine.currentCandidates.size > action.idx) didSelectCandidate(action.idx)
            }
            is KeyAction.LineStart -> {
                val before = textBeforeCursor()
                val lineLen = before.length - (before.lastIndexOf('\n') + 1)
                if (lineLen > 0) moveCursor(-lineLen)
            }
            is KeyAction.LineEnd -> {
                val after = textAfterCursor()
                val idx = after.indexOf('\n')
                val lineLen = if (idx >= 0) idx else after.length
                if (lineLen > 0) moveCursor(lineLen)
            }
            is KeyAction.PasteClipboard -> {
                engine.handleEscape()
                val text = ClipboardBridge.plainText()
                if (!text.isNullOrEmpty()) {
                    commitToEditor(text)
                } else {
                    showToast("剪貼簿為空", 1.2)
                }
            }
            is KeyAction.Tab -> {
                engine.handleEscape()
                commitToEditor("\t")
            }
            is KeyAction.EnterZhuyin -> {
                engine.switchToMode("zh")
                keyboardView?.showPage(KeyboardView.PageKind.ZHUYIN)
            }
            is KeyAction.EnterHomophone -> engine.switchToMode("to")
            is KeyAction.CursorLeft -> moveCursor(-1)
            is KeyAction.CursorRight -> moveCursor(1)
            is KeyAction.DismissKeyboard -> {
                // 底列模式：本體展開中先收本體，只剩底列時才收整個視窗
                if (builtHwMode == HwMode.BAR && keyboardView?.isShowingToolbarPage == true) {
                    keyboardView?.showPage(KeyboardView.PageKind.LETTERS)
                } else requestHideSelf(0)
            }
            is KeyAction.OpenSettings -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            is KeyAction.OpenUserPhrases -> {
                val intent = Intent(this, UserPhrasesActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            is KeyAction.Globe -> {
                if (!switchToNextIme()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showInputMethodPicker()
                }
            }
            is KeyAction.ShowImePicker -> {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
            is KeyAction.VoiceInput -> {
                engine.handleEscape()
                startVoiceInput()
            }
            is KeyAction.ToggleFloatingKeyboard -> {
                if (isOverlayMode) showToast("接實體鍵盤時無法浮動", 1.5)
                else {
                    // 切換瞬間 app 看到的鍵盤高度驟變，有些 app（如 einkbro 的網址輸入列，
                    // Compose 欄位）會因此 restartInput 並反射性 hideSoftInput，把我們藏掉 —
                    // 在短時間窗內被藏就自己要求重新顯示（一次性，不跟 app 對打）
                    floatingToggleGuardUntil = SystemClock.uptimeMillis() + 2500
                    Prefs.floatingKeyboard = !Prefs.floatingKeyboard  // prefsListener → 整組 view 重建
                }
            }
            // 編輯動作 — 同 sweetlime 原始實作（iOS 版因 extension API 缺失無法支援）
            is KeyAction.SelectAll -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
            is KeyAction.Copy -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }
            is KeyAction.Cut -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
            }
            is KeyAction.Undo -> {
                engine.handleEscape()
                sendCtrlKey(KeyEvent.KEYCODE_Z, shift = false)
            }
            is KeyAction.Redo -> {
                engine.handleEscape()
                sendCtrlKey(KeyEvent.KEYCODE_Z, shift = true)
            }
            is KeyAction.ToggleSimpTrad -> {
                val target = if (engine.inputMode == InputEngine.InputMode.S) "t" else "s"
                engine.switchToMode(target)
            }
        }
        // ,, 指令（如 ,,ZH）可能切換引擎模式 — 每次按鍵後同步頁面
        syncPageWithEngine()
        updateBarModeBody()
    }

    /// 中英切換（工具列米/英、空白鍵上滑、實體鍵盤單按 Shift 共用）
    private fun applyLanguageToggle() {
        engine.toggleEnglishMode()
        keyboardView?.isEnglishMode = engine.isEnglishMode
        keyboardView?.showPage(KeyboardView.PageKind.LETTERS)  // 從工具列切換時回到字母頁
        // 密碼欄位的暫時英文是欄位性質，不是使用者偏好 — 不記
        if (!forcedEnglishForField) Prefs.lastEnglishMode = engine.isEnglishMode
        refreshIdleBar()
    }

    /// Ctrl(+Shift)+按鍵組合送給編輯器（復原/重做 — EditText 內建 undo manager 走此路徑）
    private fun sendCtrlKey(keyCode: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun switchToNextIme(): Boolean = try {
        switchToNextInputMethod(false)
    } catch (e: Exception) {
        false
    }

    /// 語音輸入 — 同 sweetlime `LIMEService.startVoiceInput()`：Android IME 不能自己開聽寫視窗，
    /// 作法是切換到一個已啟用的「語音輸入法」，由它接手聽寫並把結果送進同一個輸入框；
    /// 使用者說完後語音輸入法自己會切回來（或按 🌐 切回）。找不到就提示。
    private fun startVoiceInput() {
        val id = voiceImeId()
        if (id == null) {
            showToast("找不到語音輸入法", 1.8)
            return
        }
        val ok = try {
            switchInputMethod(id); true
        } catch (e: Exception) {
            false
        }
        if (!ok) showToast("無法切換語音輸入法", 1.8)
    }

    /// 挑一個可用的語音輸入法 ID — 三段搜尋同 sweetlime `LIMEUtilities.isVoiceSearchServiceExist()`
    private fun voiceImeId(): String? {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabled = try { imm.enabledInputMethodList } catch (e: Exception) { return null }

        // 一：已知的 Google 語音輸入法（有 GMS 的機器最快路徑）
        val known = setOf(
            "com.google.android.voicesearch/.ime.VoiceInputMethodService",
            "com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService",
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService",
        )
        enabled.firstOrNull { it.id in known }?.let { return it.id }

        // 二：任何看起來像語音的已啟用輸入法 — 宣告 mode="voice" 的 subtype，或 ID 帶 voice/speech
        for (imi in enabled) {
            for (i in 0 until imi.subtypeCount) {
                if (imi.getSubtypeAt(i).mode == "voice") return imi.id
            }
            val lower = imi.id.lowercase()
            if (lower.contains("voice") || lower.contains("speech")) return imi.id
        }

        // 三：退而求其次切到 Gboard — 它有內建語音但不提供語音專用 IME/subtype，
        // 至少讓使用者能按 Gboard 自己的麥克風鍵
        return enabled.firstOrNull { it.id.startsWith("com.google.android.inputmethod.latin/") }?.id
    }

    private fun handleLetterKey(ch: String) {
        if (engine.isEnglishMode) {
            val shifted = keyboardView?.shiftState?.isShifted == true
            val out = if (shifted) ch.uppercase() else ch
            if (shifted && keyboardView?.shiftState?.isCapsLocked != true) {
                keyboardView?.shiftState?.consume()
                keyboardView?.reloadKeys()
            }
            commitToEditor(out)
            return
        }
        if (engine.isPinyinMode) {
            val d = ch.toIntOrNull()
            if (d != null && d in 1..5) engine.handlePinyinTone(d)
            else engine.handlePinyinLetter(ch)
            return
        }
        // VRSF 快選：v/r/s/f 選第 2–5 個候選（碼不可延伸時）
        if (engine.handleVRSF(ch)) return
        engine.handleLetter(ch)
    }

    private fun handleSpaceKey() {
        // 查碼模式優先於英文模式 — 英打切到注音/拼音查碼時，空白是一聲/查碼，
        // 不是直通空格（issue #6：英打進注音查碼，空白直接上屏、組不出一聲字）
        if (engine.isPinyinMode) { engine.handlePinyinSpace(); return }
        if (engine.isZhuyinMode) { engine.handleZhuyinSpace(); return }
        if (engine.isEnglishMode) { commitToEditor(" "); return }
        if (engine.composing.isEmpty() && !showingSuggestions) {
            // 唯一候選自動送出後習慣性補的那一下空白要吃掉，不輸出多餘空格。
            // （組字為空的空白鍵不會進引擎的 handleSpace，所以在這裡問）
            if (engine.consumeEatNextSpace()) return
            commitToEditor(" ")
            return
        }
        if (engine.composing.isEmpty() && showingSuggestions) {
            // 聯想詞顯示中按空白 → 清除聯想、輸出空白
            clearSuggestions()
            commitToEditor(" ")
            return
        }
        engine.handleSpace()
    }

    private fun handleBackspaceKey() {
        // 同 handleSpaceKey：查碼模式優先於英文模式（退格要清注音槽，不是刪編輯框）
        if (engine.isPinyinMode) { engine.handlePinyinBackspace(); return }
        if (engine.isZhuyinMode) { engine.handleBackspace(); return }
        if (engine.isEnglishMode) { deleteBackward(); return }
        if (showingSuggestions) clearSuggestions()
        engine.handleBackspace()
    }

    private fun handleReturnKey() {
        if (engine.isPinyinMode) { engine.exitPinyinMode(); return }
        if (engine.composing.isNotEmpty() || engine.isInSpecialMode) {
            engine.handleEnter()
            return
        }
        if (showingSuggestions) clearSuggestions()
        sendEnter()
    }

    private fun didSelectCandidate(index: Int) {
        haptic()
        if (engine.isPinyinMode) { engine.selectPinyinCandidate(index); return }
        engine.selectCandidate(index)
    }

    override fun clearSuggestions() {
        showingSuggestions = false
        engine.clearCandidates()
        refreshIdleBar()
    }

    private fun refreshIdleBar() {
        setBarComposing("")
        setBarCandidates(emptyList(), suggestions = false)
        candidateBar?.setEnglishMode(engine.isEnglishMode)
    }

    /// 候選列與浮動氣泡吃同一份狀態 — 所有更新都經這兩個入口
    private fun setBarComposing(text: String) {
        barComposingText = text
        candidateBar?.setComposing(text)
        syncBubble()
    }

    private fun setBarCandidates(candidates: List<String>, suggestions: Boolean) {
        barCandidates = candidates
        barSuggestions = suggestions
        candidateBar?.setCandidates(candidates, suggestions)
        syncBubble()
    }

    /// 氣泡只在浮動模式顯示（底列模式的容器只承載 toast）
    private fun syncBubble() {
        if (builtHwMode != HwMode.FLOATING) return
        floatingHost?.bubble?.setContent(barComposingText, barCandidates, barSuggestions)
    }

    // MARK: - 編輯器操作（對應 textDocumentProxy）

    private fun commitToEditor(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        // 有選取範圍時刪掉選取內容（同系統鍵盤）；deleteSurroundingText 只刪游標「前」的字，
        // 全選時選取起點在 0，前面沒東西 → 什麼都不會刪。
        // getSelectedText 在部分 WebView/Compose 欄位回 null，視同無選取。
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ei != null &&
            (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun textBeforeCursor(): String =
        currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""

    private fun textAfterCursor(): String =
        currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""

    /// 游標相對移動（±n 個 code point，不會切開 surrogate pair — emoji 被切開後
    /// 下一次刪除／送字會弄壞那個字）。
    /// 主路徑用 ExtractedText 算絕對位置直接 setSelection；取不到的編輯器
    /// （部分 WebView/Compose 欄位回 null）退回送方向鍵，由編輯器自己算邊界 ——
    /// 舊版在這裡直接 return，成對標點的游標就留在右半邊外面而不是中間。
    private fun moveCursor(offset: Int) {
        val ic = currentInputConnection ?: return
        if (offset == 0) return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text
        // selectionStart 是相對於 startOffset 的視窗座標 — 長文件不從 0 起算，
        // 直接當絕對位置會把游標丟到別的地方
        if (extracted != null && text != null && extracted.selectionStart in 0..text.length) {
            val target = offsetByCodePoints(text.toString(), extracted.selectionStart, offset)
            val absolute = extracted.startOffset + target
            if (absolute >= 0) {
                ic.setSelection(absolute, absolute)
                return
            }
        }
        val keyCode = if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(minOf(abs(offset), 1000)) { sendDownUpKey(keyCode) }
    }

    /// 自 from（UTF-16 index）位移 n 個 code point，越界夾到頭尾
    private fun offsetByCodePoints(text: String, from: Int, n: Int): Int {
        val total = text.codePointCount(0, text.length)
        val cur = text.codePointCount(0, from)
        return text.offsetByCodePoints(0, (cur + n).coerceIn(0, total))
    }

    private fun sendDownUpKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // MARK: - InputEngineDelegate（Android 事件皆在主執行緒，直接更新 UI）

    override fun engineDidUpdateComposing(text: String) {
        showingSuggestions = false
        setBarComposing(text)
        syncPageWithEngine()
    }

    override fun engineDidUpdateCandidates(candidates: List<String>) {
        // 送字時引擎會先清候選、之後才（在有結果時）發聯想。聯想顯示中收到的這個清空
        // 不能一律吞掉 —— 否則下一輪沒有聯想時，候選列會留著上一輪的過期聯想，
        // 而引擎的候選已空，點下去每一個都沒反應。清掉後若隨即有新聯想，
        // engineDidSuggest 會在同一個呼叫堆疊裡補上，畫面不會閃。
        if (showingSuggestions) {
            if (candidates.isEmpty()) {
                showingSuggestions = false
                refreshIdleBar()
            }
            return
        }
        setBarCandidates(candidates, suggestions = false)
        if (candidates.isEmpty() && engine.composing.isEmpty()) {
            refreshIdleBar()
        }
        syncPageWithEngine()
    }

    override fun engineDidCommit(text: String) {
        commitToEditor(text)
    }

    override fun engineDidCommitPair(left: String, right: String) = commitPair(left, right)

    /// 成對標點：游標要停在兩半中間。右半用 commitText(newCursorPosition = 0) 送 ——
    /// 0 = 游標放在插入文字的起點，正好是左右之間，全程單向 binder、零往返。
    /// 舊版是 commitText(left+right) 再 moveCursor(-1)，而 moveCursor 走
    /// getExtractedText：**同步**跨 process 呼叫，拉回整份文件、等對方 UI 執行緒
    /// 回應（實測閒置 10 字欄位 2–3ms，長文件／忙碌的 app 會到數十 ms 以上），
    /// 卡在 IME 主執行緒上正好是按鍵後那一幀。
    private fun commitPair(left: String, right: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.commitText(left, 1)
        ic.commitText(right, 0)
        ic.endBatchEdit()
    }

    override fun engineDidClearComposing() {
        setBarComposing("")
        if (!showingSuggestions) refreshIdleBar()
        syncPageWithEngine()
    }

    override fun engineDidShowToast(text: String) = showToast(text, 1.2)

    override fun engineDidShowCodeHint(text: String, duration: Double) = showToast(text, duration)

    override fun engineDidDeleteBack() {
        deleteBackward()
    }

    override fun engineDidSuggest(suggestions: List<String>) {
        // 接實體鍵盤時聯想與軟鍵盤分開：可盲打，預設不顯示聯想（獨立於聯想總開關）。
        // 攔在此處而非引擎層 — 引擎是跨平台共用碼，「是否有實體鍵盤」是 Android 平台狀態。
        if (hardKeyboardPresent() && !Prefs.suggestWithHardKeyboard) return
        showingSuggestions = true
        engine.setCandidates(suggestions)
        setBarComposing("")
        setBarCandidates(suggestions, suggestions = true)
    }

    override fun engineDidPasteText(text: String) {
        commitToEditor(text)
    }

    // MARK: - Zhuyin page sync

    private fun syncPageWithEngine() {
        val kv = keyboardView ?: return
        // 使用者從工具列開的面板（符號/emoji/顏文字/常用語/123）優先 —— 注音模式是
        // 黏著旗標，不擋掉的話每次按鍵後都會把面板搶回注音頁，面板等於打不開
        if (kv.isShowingToolbarPage) return
        val wantZhuyin = engine.isZhuyinMode
        if (wantZhuyin && kv.currentPage != KeyboardView.PageKind.ZHUYIN) {
            kv.currentPage = KeyboardView.PageKind.ZHUYIN
            kv.reloadKeys()
        } else if (!wantZhuyin && kv.currentPage == KeyboardView.PageKind.ZHUYIN) {
            kv.currentPage = KeyboardView.PageKind.LETTERS
            kv.reloadKeys()
        }
    }

    private fun showToast(text: String, duration: Double) {
        // 覆蓋模式鍵盤本體收著，toast 改浮在面板上方的容器裡（游標旁／底列上方）
        val toast = (if (isOverlayMode) floatingHost?.toast else toastLabel) ?: return
        toastHideRunnable?.let { handler.removeCallbacks(it) }
        toast.text = "  $text  "
        toast.visibility = View.VISIBLE
        toast.alpha = 1f
        val hide = Runnable {
            toast.animate().alpha(0f).setDuration(250).withEndAction {
                toast.visibility = View.GONE
            }.start()
        }
        toastHideRunnable = hide
        handler.postDelayed(hide, (duration * 1000).toLong())
    }
}
