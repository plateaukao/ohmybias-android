package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.MemoryBudget
import info.plateaukao.ohmybias.shared.SkinSettings
import info.plateaukao.ohmybias.shared.UserPhrases
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/// 純程式碼鍵盤面板：字母頁／數字頁／符號頁／注音查碼頁／九宮格數字頁＋分類面板。
/// 版面演算法對應 iOS 版 stack view 約束：
/// 首列 10 鍵定單位寬；第二列同寬置中；一般排按 multiplier 比例分配；
/// 含空白鍵的排 — 其他鍵用上一排單位寬、空白吃掉剩餘。
class KeyboardView(context: Context) : ViewGroup(context) {

    enum class PageKind { LETTERS, NUMBERS, SYMBOLS, ZHUYIN, NUMERIC9, SYMBOL_PANEL, EMOJI, KAOMOJIS, PHRASES }

    var onKey: ((KeyAction) -> Unit)? = null
    var onHapticDown: (() -> Unit)? = null  // 按下瞬間觸覺回饋（issue #4）— service 掛 haptic()

    var currentPage = PageKind.LETTERS
    /// 中英切換改變第三排前導鍵（英/⇧）與標點 — 變更時就地重建，
    /// 不再依賴 onStartInputView 的無條件 reloadKeys 事後修正
    var isEnglishMode = false
        set(value) {
            if (field == value) return
            field = value
            shiftState.reset()
            reloadKeys()
        }
    internal val shiftState = ShiftState()
    var needsInputModeSwitchKey = true
    /// Enter 鍵顯示文字（依 host app 的 imeOptions：搜尋/前往/送出…）
    var returnKeyLabel = "⏎"

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var keyButtons = mutableListOf<KeyButton>()
    private var rowsOfButtons = mutableListOf<List<KeyButton>>()
    private var pageBeforeToolbarToggle: PageKind? = null

    /// 目前顯示的是工具列切過去的面板（再按一次會切回原頁）
    val isShowingToolbarPage: Boolean get() = pageBeforeToolbarToggle != null
    private var panelView: CollectionPanelView? = null
    /// 建鍵時的皮膚世代 — 皮膚重載後才需要重建 KeySpec（滑動開關/版面選項）
    private var builtSkinGeneration = -1
    /// 建鍵時「中文模式大寫字母」偏好值 — 在設定頁改過後回鍵盤要重建鍵面
    private var builtUppercaseLetters = false
    /// 上次排版採用的按鍵間距縮放 — 設定頁改過後回鍵盤要重排（鍵面尺寸會變）
    private var laidOutSpacingScale = -1f

    private val bgPaint = Paint()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        reloadKeys()
    }

    override fun onDraw(canvas: Canvas) {
        // clipChildren=false 時 canvas 剪裁範圍超出 view 邊界 —
        // 用有界矩形而非 drawColor，避免蓋掉上方的候選列
        bgPaint.color = KeyboardTheme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    // MARK: - 換頁

    fun showPage(page: PageKind) {
        if (currentPage == page && pageBeforeToolbarToggle == null) return  // 同頁免重建
        currentPage = page
        pageBeforeToolbarToggle = null
        reloadKeys()
    }

    fun toggleToolbarPage(page: PageKind) {
        if (currentPage == page) {
            currentPage = pageBeforeToolbarToggle ?: PageKind.LETTERS
            pageBeforeToolbarToggle = null
        } else {
            pageBeforeToolbarToggle = currentPage
            currentPage = page
        }
        reloadKeys()
    }

    /// onStartInputView 每次切輸入框都會呼叫 — 只有鍵面實際會變（🌐 鍵有無、
    /// Enter 標籤、皮膚重載）才整面重建（取法 sweetlime initOnStartInput 的短路）
    fun syncSessionState(needsSwitchKey: Boolean, newReturnLabel: String) {
        if (needsInputModeSwitchKey == needsSwitchKey && returnKeyLabel == newReturnLabel &&
            builtSkinGeneration == SkinSettings.shared.generation &&
            builtUppercaseLetters == Prefs.uppercaseLettersInChinese &&
            currentPage != PageKind.PHRASES  // 常用語可能在設定頁被改過 — 回鍵盤時重讀
        ) return
        needsInputModeSwitchKey = needsSwitchKey
        returnKeyLabel = newReturnLabel
        reloadKeys()
    }

    /// 按鍵間距偏好改過就重排（不必重建鍵面 — 只有版面尺寸變）
    fun syncSpacingIfNeeded() {
        if (laidOutSpacingScale != Prefs.keySpacingScale) requestLayout()
    }

    fun reloadKeys() {
        builtSkinGeneration = SkinSettings.shared.generation
        builtUppercaseLetters = Prefs.uppercaseLettersInChinese
        removeAllViews()
        keyButtons.clear()
        rowsOfButtons.clear()
        panelView = null
        endPopup(null, false)

        when (currentPage) {
            PageKind.SYMBOL_PANEL -> { installPanel(CollectionData.symbols, KeyboardTheme.panelSymbolFontSize); return }
            PageKind.EMOJI -> {
                // 「常用」分類 = 最近使用紀錄，排在「表情」前；沒紀錄就不顯示
                val recent = RecentEmojis.all()
                val sections = if (recent.isEmpty()) CollectionData.emojis
                               else listOf("常用" to recent) + CollectionData.emojis
                installPanel(sections, KeyboardTheme.panelEmojiFontSize, recordRecent = true)
                return
            }
            PageKind.KAOMOJIS -> { installPanel(CollectionData.kaomojis, KeyboardTheme.panelKaomojiFontSize); return }
            PageKind.PHRASES -> {
                // ♥ 常用語面板 — 內容為 user_phrases.txt，點選直接上屏；「設定」開編輯對話框
                UserPhrases.shared.reload()
                installPanel(listOf("常用語" to UserPhrases.shared.allPhrases()), KeyboardTheme.panelKaomojiFontSize,
                             settingsAction = KeyAction.OpenUserPhrases)
                return
            }
            else -> {}
        }

        val rows = when (currentPage) {
            PageKind.LETTERS -> letterRows()
            PageKind.NUMBERS -> numberRows()
            PageKind.SYMBOLS -> symbolRows()
            PageKind.ZHUYIN -> zhuyinRows()
            PageKind.NUMERIC9 -> numeric9Rows()
            else -> emptyList()
        }
        for (row in rows) {
            val buttons = row.map { spec ->
                val b = KeyButton(context, spec, this)
                addView(b)
                keyButtons.add(b)
                b
            }
            rowsOfButtons.add(buttons)
        }
        requestLayout()
        invalidate()
    }

    private fun installPanel(
        sections: List<Pair<String, List<String>>>, fontSize: Float,
        recordRecent: Boolean = false, settingsAction: KeyAction? = null,
    ) {
        if (!MemoryBudget.canAfford(MemoryBudget.collectionPanel)) return
        val panel = CollectionPanelView(context, sections, fontSize, showSettings = settingsAction != null)
        if (settingsAction != null) panel.onSettings = { onHapticDown?.invoke(); onKey?.invoke(settingsAction) }
        panel.onInsert = { text ->
            onHapticDown?.invoke()
            if (recordRecent) RecentEmojis.record(text)
            onKey?.invoke(KeyAction.Symbol(text))
        }
        panel.onBack = { onHapticDown?.invoke(); onKey?.invoke(KeyAction.Page(PageKind.LETTERS)) }
        panel.onBackspace = { onHapticDown?.invoke(); onKey?.invoke(KeyAction.Backspace) }
        addView(panel)
        panelView = panel
        requestLayout()
    }

    // MARK: - Layout

    private fun isSpaceKey(spec: KeySpec): Boolean = spec.action is KeyAction.Space && !spec.isGlobe

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        panelView?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        // 其他子視圖（按鍵/popup）在 onLayout 內定位；此處給非精確 measure
        for (b in keyButtons) b.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST),
        )
    }

    @SuppressLint("DrawAllocation")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l).toFloat()
        val h = (b - t).toFloat()
        panelView?.layout(0, 0, w.toInt(), h.toInt())
        if (rowsOfButtons.isEmpty()) { layoutPopupIfNeeded(); return }

        // 間距縮放（設定頁「按鍵間距」）— 調小則鍵面變大、鍵與鍵更緊
        val gap = Prefs.keySpacingScale
        laidOutSpacingScale = gap
        val padTop = dp(6f * gap); val padBottom = dp(6f * gap); val padSide = dp(3f * gap)
        // 排距與鍵距相同（5dp）— 上下與左右的鍵縫等寬，鍵陣看起來才是均勻的格子
        val keySpacing = dp(5f * gap); val rowSpacing = keySpacing
        val rowCount = rowsOfButtons.size
        val rowHeight = (h - padTop - padBottom - rowSpacing * (rowCount - 1)) / rowCount
        val innerW = w - padSide * 2

        var prevUnitWidth = 0f
        for ((rowIndex, buttons) in rowsOfButtons.withIndex()) {
            val y = padTop + rowIndex * (rowHeight + rowSpacing)
            val specs = buttons.map { it.spec }
            val n = buttons.size
            val spacingTotal = keySpacing * (n - 1)
            val hasSpace = specs.any { isSpaceKey(it) }

            val widths = FloatArray(n)
            var startX = padSide

            if (currentPage == PageKind.LETTERS && rowIndex == 1 && prevUnitWidth > 0f) {
                // 第二列（a–l）：同首列鍵寬、整列置中
                for (i in 0 until n) widths[i] = prevUnitWidth
                val total = prevUnitWidth * n + spacingTotal
                startX = padSide + (innerW - total) / 2
            } else if (hasSpace) {
                // 含空白鍵的排：其他鍵 = multiplier × 上一排單位寬，空白吃剩餘
                val unit = if (prevUnitWidth > 0f) prevUnitWidth
                           else (innerW - spacingTotal) / specs.sumOf { it.widthMultiplier.toDouble() }.toFloat()
                var fixed = 0f
                for (i in 0 until n) {
                    if (!isSpaceKey(specs[i])) {
                        widths[i] = unit * specs[i].widthMultiplier
                        fixed += widths[i]
                    }
                }
                val spaceW = max(dp(40f), innerW - spacingTotal - fixed)
                for (i in 0 until n) if (isSpaceKey(specs[i])) widths[i] = spaceW
            } else {
                // 一般排：整排照 multiplier 比例填滿
                val multSum = specs.sumOf { it.widthMultiplier.toDouble() }.toFloat()
                val unit = (innerW - spacingTotal) / multSum
                for (i in 0 until n) widths[i] = unit * specs[i].widthMultiplier
                // 跨排對齊基準：第一顆單位寬、非空白的鍵
                val refIdx = specs.indexOfFirst { it.widthMultiplier == 1f && !isSpaceKey(it) }
                if (refIdx >= 0) prevUnitWidth = widths[refIdx]
            }

            var x = startX
            for (i in 0 until n) {
                buttons[i].layout(x.toInt(), y.toInt(), (x + widths[i]).toInt(), (y + rowHeight).toInt())
                x += widths[i] + keySpacing
            }
        }
        layoutPopupIfNeeded()
    }

    // MARK: - 間隙點擊導向最近按鍵（對應 iOS hitTest）

    private var gapTouchTarget: KeyButton? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (panelView != null || keyButtons.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = nearestKey(event.x, event.y) ?: return false
                gapTouchTarget = target
                forwardTouch(target, event)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = gapTouchTarget ?: return false
                forwardTouch(target, event)
                if (event.actionMasked != MotionEvent.ACTION_MOVE) gapTouchTarget = null
                return true
            }
        }
        return false
    }

    private fun forwardTouch(target: KeyButton, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        target.onTouchEvent(copy)
        copy.recycle()
    }

    private fun nearestKey(x: Float, y: Float): KeyButton? {
        var best: KeyButton? = null
        var bestDist = Float.MAX_VALUE
        for (b1 in keyButtons) {
            val dx = max(max(b1.left - x, 0f), x - b1.right)
            val dy = max(max(b1.top - y, 0f), y - b1.bottom)
            val d = dx * dx + dy * dy
            if (d < bestDist) { bestDist = d; best = b1 }
        }
        return best
    }

    // MARK: - 長按彈出選單

    private var activePopup: LongPressPopup? = null
    private var popupAnchor: KeyButton? = null

    fun showPopup(button: KeyButton) {
        val menu = button.spec.longPress ?: return
        endPopup(button, false)
        val popup = LongPressPopup(context, menu.options, menu.defaultIndex)
        addView(popup)
        activePopup = popup
        popupAnchor = button
        requestLayout()
    }

    private fun layoutPopupIfNeeded() {
        val popup = activePopup ?: return
        val button = popupAnchor ?: return
        popup.measure(
            MeasureSpec.makeMeasureSpec(popup.popupWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(popup.popupHeight, MeasureSpec.EXACTLY),
        )
        var x = button.left + button.width / 2f - popup.popupWidth / 2f
        x = max(dp(4f), min(width - popup.popupWidth - dp(4f), x))
        val y = button.top - popup.popupHeight - dp(6f)
        popup.layout(x.toInt(), y.toInt(), (x + popup.popupWidth).toInt(), (y + popup.popupHeight).toInt())
    }

    fun movePopupSelection(button: KeyButton, xInButton: Float) {
        val popup = activePopup ?: return
        val xInSelf = button.left + xInButton
        popup.updateSelection(xInSelf - popup.left)
    }

    fun endPopup(button: KeyButton?, commit: Boolean) {
        val popup = activePopup ?: return
        if (commit && button != null) {
            button.spec.longPress?.let { menu ->
                val option = menu.options[popup.selectedIndex]
                onHapticDown?.invoke()
                onKey?.invoke(KeyAction.Symbol(LongPressData.resolve(option)))
            }
        }
        removeView(popup)
        activePopup = null
        popupAnchor = null
    }

    // MARK: - 版面定義

    class KeySpec(
        val label: String,
        val action: KeyAction,
        val widthMultiplier: Float = 1f,
        val isSpecial: Boolean = false,     // 灰色功能鍵底色
        val isGlobe: Boolean = false,
        val swipeUp: SwipeData.Entry? = null,
        val swipeDown: SwipeData.Entry? = null,
        val swipeUpLeft: SwipeData.Entry? = null,   // 斜向（目前只有空白鍵用）
        val swipeUpRight: SwipeData.Entry? = null,
        val longPress: LongPressData.Menu? = null,
    )

    /// 依 cskin 全域開關過濾滑動動作/角標
    private fun swipeEntry(entry: SwipeData.Entry?, up: Boolean): SwipeData.Entry? {
        val skin = SkinSettings.shared
        if (entry == null) return null
        if (!(if (up) skin.swipeUpEnabled else skin.swipeDownEnabled)) return null
        val showHint = if (up) skin.showSwipeUpText else skin.showSwipeDownText
        return if (showHint) entry else SwipeData.Entry(null, entry.action)
    }

    private fun letterRows(): List<List<KeySpec>> {
        val skin = SkinSettings.shared
        val r1 = "qwertyuiop".map { it.toString() }
        val r2 = "asdfghjkl".map { it.toString() }
        val r3 = "zxcvbnm".map { it.toString() }
        // 鍵面大寫：英文模式看 shift；中文模式看偏好（字根表慣用大寫）。action 一律小寫碼
        val uppercase = if (isEnglishMode) shiftState.isShifted else Prefs.uppercaseLettersInChinese
        fun key(s: String): KeySpec = KeySpec(
            label = if (uppercase) s.uppercase() else s,
            action = KeyAction.Letter(s),
            swipeUp = swipeEntry(SwipeData.up[s], up = true),
            swipeDown = swipeEntry(SwipeData.down[s], up = false),
            longPress = if (skin.longPressEnabled) LongPressData.letterMenu(s) else null,
        )
        // 第三排前導鍵（sweetlime 版面）：中文模式＝「英」切換鍵（shift 位置）、英文模式＝shift
        val row3 = mutableListOf<KeySpec>()
        if (isEnglishMode) {
            val shiftLabel = if (shiftState.isCapsLocked) "⇪" else if (shiftState.isShifted) "⬆" else "⇧"
            row3.add(KeySpec(shiftLabel, KeyAction.Shift, isSpecial = true))
        } else {
            row3.add(KeySpec("英", KeyAction.ToggleLanguage, isSpecial = true))
        }
        row3 += r3.map(::key)
        row3.add(KeySpec("⌫", KeyAction.Backspace, widthMultiplier = 1.4f, isSpecial = true))

        // 底列（sweetlime）：[123] [🌐] [,] [大空白] [.] [⏎]
        // 工具列已有 123（按鈕 ID 9/29）時省略底列 123 鍵，寬度讓給空白鍵
        // 空白鍵永遠優先：逗號句號固定標準鍵寬，不採用皮膚 spaceKeyLayout 的放大值
        val show123Key = skin.toolbarButtons.none { it == 9 || it == 29 }
        val numericPage = if (skin.keyboardLayout == "row") PageKind.NUMBERS else PageKind.NUMERIC9
        val row4 = mutableListOf<KeySpec>()
        if (show123Key) {
            row4.add(KeySpec("123", KeyAction.Page(numericPage), isSpecial = true))
        }
        if (needsInputModeSwitchKey) {
            row4.add(KeySpec("🌐", KeyAction.Globe, widthMultiplier = 1.0f, isSpecial = true, isGlobe = true))
        }
        row4.add(KeySpec(",", KeyAction.Letter(","),
            swipeUp = swipeEntry(SwipeData.up[","], up = true),
            swipeDown = swipeEntry(SwipeData.down[","], up = false),
            longPress = if (skin.longPressEnabled) LongPressData.commaMenu else null))
        // 空白鍵手勢：上滑＝中↔英、右上滑＝注音查碼、左上滑＝同音字、左右拖曳＝移動游標
        row4.add(KeySpec("", KeyAction.Space,
            swipeUp = swipeEntry(SwipeData.Entry(null, KeyAction.ToggleLanguage), up = true),
            swipeUpLeft = swipeEntry(SwipeData.Entry(null, KeyAction.EnterHomophone), up = true),
            swipeUpRight = swipeEntry(SwipeData.Entry(null, KeyAction.EnterZhuyin), up = true)))
        row4.add(KeySpec(if (isEnglishMode) "." else "。", KeyAction.Letter("."),
            swipeUp = swipeEntry(SwipeData.up["."], up = true),
            swipeDown = swipeEntry(SwipeData.down["."], up = false),
            longPress = if (skin.longPressEnabled) LongPressData.periodMenu else null))
        row4.add(KeySpec(returnKeyLabel, KeyAction.Newline, widthMultiplier = 1.4f, isSpecial = true,
            swipeUp = swipeEntry(SwipeData.Entry("ㄅ", KeyAction.EnterZhuyin), up = true),
            swipeDown = swipeEntry(SwipeData.Entry(null, KeyAction.Newline), up = false)))

        return listOf(r1.map(::key), r2.map(::key), row3, row4)
    }

    /// Row 數字/半形符號頁（sweetlime symbolic_row）：數字列＋常用半形符號、大空白
    private fun numberRows(): List<List<KeySpec>> {
        val r1 = "1234567890".map { it.toString() }
        val r2 = listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "※")
        val r3 = listOf("+", "*", ".", ",", "?", "!", "'")
        val row3 = mutableListOf(KeySpec("#+=", KeyAction.Page(PageKind.SYMBOLS), widthMultiplier = 1.4f, isSpecial = true))
        row3 += r3.map { KeySpec(it, KeyAction.Symbol(it)) }
        row3.add(KeySpec("⌫", KeyAction.Backspace, widthMultiplier = 1.4f, isSpecial = true))
        val row4 = listOf(
            KeySpec("返回", KeyAction.Page(PageKind.LETTERS), widthMultiplier = 1.4f, isSpecial = true),
            KeySpec("=", KeyAction.Symbol("=")),
            KeySpec("空格", KeyAction.Space, widthMultiplier = 4.6f),
            KeySpec("\"", KeyAction.Symbol("\"")),
            KeySpec(returnKeyLabel, KeyAction.Newline, widthMultiplier = 1.5f, isSpecial = true),
        )
        return listOf(
            r1.map { KeySpec(it, KeyAction.Symbol(it)) },
            r2.map { KeySpec(it, KeyAction.Symbol(it)) },
            row3, row4,
        )
    }

    /// 全形符號頁（#+= 第二頁）
    private fun symbolRows(): List<List<KeySpec>> {
        val r1 = listOf("［", "］", "｛", "｝", "＃", "％", "＾", "＊", "＋", "＝")
        val r2 = listOf("＿", "＼", "｜", "～", "＜", "＞", "《", "》", "€", "＆")
        val r3 = listOf("『", "』", "【", "】", "〈", "〉", "・", "§")
        val row3 = mutableListOf(KeySpec("123", KeyAction.Page(PageKind.NUMBERS), widthMultiplier = 1.4f, isSpecial = true))
        row3 += r3.map { KeySpec(it, KeyAction.Symbol(it)) }
        row3.add(KeySpec("⌫", KeyAction.Backspace, widthMultiplier = 1.4f, isSpecial = true))
        val row4 = listOf(
            KeySpec("返回", KeyAction.Page(PageKind.LETTERS), widthMultiplier = 1.5f, isSpecial = true),
            KeySpec("空格", KeyAction.Space, widthMultiplier = 5.5f),
            KeySpec(returnKeyLabel, KeyAction.Newline, widthMultiplier = 1.8f, isSpecial = true),
        )
        return listOf(
            r1.map { KeySpec(it, KeyAction.Symbol(it)) },
            r2.map { KeySpec(it, KeyAction.Symbol(it)) },
            row3, row4,
        )
    }

    /// 九宮格數字鍵盤（sweetlime 預設 keyboardLayout 'panel'）：
    /// 左欄符號、中間數字九宮格、右欄功能鍵，底列 [返回][#+=][0][空白][⏎]
    private fun numeric9Rows(): List<List<KeySpec>> {
        fun sym(s: String) = KeySpec(s, KeyAction.Symbol(s), isSpecial = true)
        fun digit(d: String) = KeySpec(d, KeyAction.Symbol(d))
        val r1 = listOf(sym("@"), digit("1"), digit("2"), digit("3"),
            KeySpec("⌫", KeyAction.Backspace, isSpecial = true))
        val r2 = listOf(sym("%"), digit("4"), digit("5"), digit("6"),
            KeySpec(".", KeyAction.Symbol("."), isSpecial = true))
        val r3 = listOf(sym("-"), digit("7"), digit("8"), digit("9"), sym("+"))
        val r4 = listOf(
            KeySpec("返回", KeyAction.Page(PageKind.LETTERS), isSpecial = true),
            KeySpec("#+=", KeyAction.Page(PageKind.NUMBERS), isSpecial = true),
            digit("0"),
            KeySpec("空格", KeyAction.Space),
            KeySpec(returnKeyLabel, KeyAction.Newline, isSpecial = true),
        )
        return listOf(r1, r2, r3, r4)
    }

    /// 標準注音鍵盤排列（對應 qwerty 位置），聲調鍵混排於其中
    private fun zhuyinRows(): List<List<KeySpec>> {
        val tones = setOf("ˊ", "ˇ", "ˋ", "˙")
        fun zy(s: String): KeySpec =
            if (s in tones) KeySpec(s, KeyAction.ZhuyinTone(s))
            else KeySpec(s, KeyAction.ZhuyinSymbol(s))
        val r1 = listOf("ㄅ", "ㄉ", "ˇ", "ˋ", "ㄓ", "ˊ", "˙", "ㄚ", "ㄞ", "ㄢ").map(::zy)
        val r2 = listOf("ㄆ", "ㄊ", "ㄍ", "ㄐ", "ㄔ", "ㄗ", "ㄧ", "ㄛ", "ㄟ", "ㄣ").map(::zy)
        val r3 = listOf("ㄇ", "ㄋ", "ㄎ", "ㄑ", "ㄕ", "ㄘ", "ㄨ", "ㄜ", "ㄠ", "ㄤ").map(::zy)
        val r4 = (listOf("ㄈ", "ㄌ", "ㄏ", "ㄒ", "ㄖ", "ㄙ", "ㄩ", "ㄝ", "ㄡ", "ㄥ").map(::zy) + zy("ㄦ"))
        val row5 = listOf(
            KeySpec("退出", KeyAction.ZhuyinExit, widthMultiplier = 1.5f, isSpecial = true),
            KeySpec("空白（一聲）", KeyAction.Space, widthMultiplier = 5.5f),
            KeySpec("⌫", KeyAction.Backspace, widthMultiplier = 1.8f, isSpecial = true),
        )
        return listOf(r1, r2, r3, r4, row5)
    }
}

/// 圓角按鍵 — sweetlime 線稿風：一般鍵描邊、功能鍵填色（深色模式反白）。
/// 自行處理 touch：點按＝主動作、垂直滑動＝上滑/下滑動作、⌫ 長按連刪、空白鍵水平拖曳移動游標。
@SuppressLint("ViewConstructor")
class KeyButton(
    context: Context,
    val spec: KeyboardView.KeySpec,
    private val host: KeyboardView,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val swipeThreshold = dp(25f)
    private val cursorDragStep = dp(9f)

    private var isPressedState = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasTouchStart = false
    private var isInLongPress = false
    private var isDraggingCursor = false
    private var dragAnchorX = 0f

    private val handler2 = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = KeyboardTheme.cornerRadius * density
        // 邊框寬/色都分平時與按下兩套（皮膚未定義按下值時鏈回平時值 → 外觀不變）
        val bw = (if (isPressedState) KeyboardTheme.borderWidthHighlight
                  else KeyboardTheme.borderWidth) * density
        val half = bw / 2
        // 底色
        paint.style = Paint.Style.FILL
        paint.color = if (spec.isSpecial) {
            if (isPressedState) KeyboardTheme.keySystemHighlight else KeyboardTheme.keySystem
        } else {
            if (isPressedState) KeyboardTheme.keyNormalHighlight else KeyboardTheme.keyNormal
        }
        canvas.drawRoundRect(half, half, width - half, height - half, r, r, paint)
        // 邊框
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bw
        paint.color = if (spec.isSpecial) {
            if (isPressedState) KeyboardTheme.systemBorderHighlight else KeyboardTheme.systemBorder
        } else {
            if (isPressedState) KeyboardTheme.borderHighlight else KeyboardTheme.border
        }
        canvas.drawRoundRect(half, half, width - half, height - half, r, r, paint)
        // 主標籤
        if (spec.label.isNotEmpty()) {
            val size = if (spec.label.length > 1) KeyboardTheme.systemFontSize
                       else if (spec.isSpecial) KeyboardTheme.systemFontSize + 4
                       else KeyboardTheme.alphabetFontSize
            textPaint.textSize = dp(size)
            textPaint.color = if (spec.isSpecial) KeyboardTheme.textSystem else KeyboardTheme.textMain
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(spec.label, width / 2f, y, textPaint)
        }
        // 角標：上滑符號置頂中、下滑符號置右下（同 sweetlime 版面）
        hintPaint.textSize = dp(KeyboardTheme.swipeHintFontSize)
        hintPaint.color = KeyboardTheme.textSub
        spec.swipeUp?.hint?.let { hint ->
            hintPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(hint, width / 2f, dp(2f) - hintPaint.ascent(), hintPaint)
        }
        spec.swipeDown?.hint?.let { hint ->
            hintPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(hint, width - dp(4f), height - dp(2f) - hintPaint.descent(), hintPaint)
        }
    }

    private fun setPressedState(v: Boolean) {
        if (isPressedState != v) { isPressedState = v; invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPressedState(true)
                // 觸覺回饋在按下瞬間給（issue #4）— 之前跟著 ACTION_UP 的 onKey 才震，有 lag 感
                host.onHapticDown?.invoke()
                isInLongPress = false
                isDraggingCursor = false
                touchStartX = event.x; touchStartY = event.y; hasTouchStart = true
                if (spec.action is KeyAction.Backspace) {
                    host.onKey?.invoke(KeyAction.Backspace)
                    // 連刪節奏同 sweetlime（400ms 起跑、50ms/字 = 20 字/秒）
                    val rep = object : Runnable {
                        override fun run() {
                            host.onHapticDown?.invoke()
                            host.onKey?.invoke(KeyAction.Backspace)
                            handler2.postDelayed(this, 50)
                        }
                    }
                    repeatRunnable = rep
                    handler2.postDelayed(rep, 400)
                }
                if (spec.longPress != null || spec.action is KeyAction.Shift) {
                    val lp = Runnable {
                        isInLongPress = true
                        if (spec.action is KeyAction.Shift) host.onKey?.invoke(KeyAction.CapsLock)
                        else host.showPopup(this)
                    }
                    longPressRunnable = lp
                    handler2.postDelayed(lp, 400)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isInLongPress) {
                    host.movePopupSelection(this, event.x)
                    return true
                }
                // 空白鍵：水平拖曳 → 逐步移動游標
                if (spec.action is KeyAction.Space && hasTouchStart) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    // 只有接近水平（|dx| > 2|dy|，約 27° 內）才鎖定成游標拖曳，
                    // 斜向上滑（左上／右上）留到放開時判定手勢
                    if (!isDraggingCursor && abs(dx) > dp(15f) && abs(dx) > 2 * abs(dy)) {
                        isDraggingCursor = true
                        stopLongPressTimer()
                        dragAnchorX = event.x
                    }
                    if (isDraggingCursor) {
                        while (event.x - dragAnchorX > cursorDragStep) {
                            host.onHapticDown?.invoke()
                            host.onKey?.invoke(KeyAction.CursorRight); dragAnchorX += cursorDragStep
                        }
                        while (dragAnchorX - event.x > cursorDragStep) {
                            host.onHapticDown?.invoke()
                            host.onKey?.invoke(KeyAction.CursorLeft); dragAnchorX -= cursorDragStep
                        }
                        return true
                    }
                }
                // 開始滑動即取消長按等待
                if (hasTouchStart && hypot(event.x - touchStartX, event.y - touchStartY) > dp(12f)) {
                    stopLongPressTimer()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                setPressedState(false)
                stopRepeat()
                stopLongPressTimer()
                if (isDraggingCursor) {
                    isDraggingCursor = false
                    hasTouchStart = false
                    return true  // 拖曳游標結束，不輸出空白
                }
                if (isInLongPress) {
                    isInLongPress = false
                    hasTouchStart = false
                    host.endPopup(this, true)
                    return true
                }
                if (spec.action is KeyAction.Backspace) return true  // 已在按下時觸發
                if (!hasTouchStart) return true
                hasTouchStart = false
                val dx = event.x - touchStartX; val dy = event.y - touchStartY
                // 斜向上滑：上移超過門檻且水平分量明顯（|dx| > 0.45|dy|，離垂直約 24° 以上）
                if (-dy > swipeThreshold && abs(dx) > 0.45f * abs(dy)) {
                    val diag = if (dx > 0) spec.swipeUpRight else spec.swipeUpLeft
                    diag?.let { host.onKey?.invoke(it.action); return true }
                }
                if (abs(dy) > swipeThreshold && abs(dy) > abs(dx)) {
                    if (dy < 0) spec.swipeUp?.let { host.onKey?.invoke(it.action); return true }
                    if (dy > 0) spec.swipeDown?.let { host.onKey?.invoke(it.action); return true }
                }
                // 一般點按：允許少量滑出（同系統鍵盤手感）
                val slop = dp(20f)
                if (event.x >= -slop && event.x <= width + slop && event.y >= -slop && event.y <= height + slop) {
                    host.onKey?.invoke(spec.action)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                setPressedState(false)
                stopRepeat()
                stopLongPressTimer()
                isDraggingCursor = false
                if (isInLongPress) {
                    isInLongPress = false
                    host.endPopup(this, false)
                }
                hasTouchStart = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler2.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun stopLongPressTimer() {
        longPressRunnable?.let { handler2.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
        stopLongPressTimer()
    }
}
