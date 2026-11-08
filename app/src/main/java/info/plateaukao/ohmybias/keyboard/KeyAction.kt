package info.plateaukao.ohmybias.keyboard

/// 鍵盤動作 — 對應 iOS KeyAction enum
sealed class KeyAction {
    data class Letter(val ch: String) : KeyAction()        // a–z、,、.（中文模式進引擎組字）
    data class Symbol(val s: String) : KeyAction()         // 符號頁直接輸出
    object Space : KeyAction()
    object Backspace : KeyAction()
    object Newline : KeyAction()
    object ToggleLanguage : KeyAction()                    // 中 ↔ 英
    object Shift : KeyAction()                             // 英文模式大寫
    object CapsLock : KeyAction()
    data class Page(val page: KeyboardView.PageKind) : KeyAction()
    data class ToggleToolbarPage(val page: KeyboardView.PageKind) : KeyAction()
    data class ZhuyinSymbol(val zy: String) : KeyAction()
    data class ZhuyinTone(val tone: String) : KeyAction()
    object ZhuyinExit : KeyAction()
    // sweetlime 滑動手勢動作
    data class SelectCandidateShortcut(val idx: Int) : KeyAction()  // 次選/三選上屏（n/m 上滑）
    object LineStart : KeyAction()                         // 游標移至句首（z 下滑）
    object LineEnd : KeyAction()                           // 游標移至句尾（m 下滑）
    object PasteClipboard : KeyAction()                    // 貼上剪貼簿（v 下滑）
    object Tab : KeyAction()                               // Tab（b 下滑）
    object EnterZhuyin : KeyAction()                       // 跳轉注音查碼（Enter 上滑／空白鍵右上滑）
    object EnterHomophone : KeyAction()                    // 同音字模式（空白鍵左上滑，同 ,,TO）
    // sweetlime 工具列動作
    object CursorLeft : KeyAction()                        // 游標左移
    object CursorRight : KeyAction()                       // 游標右移
    object DismissKeyboard : KeyAction()                   // 收折鍵盤
    object OpenSettings : KeyAction()                      // 開啟設定 Activity
    object OpenUserPhrases : KeyAction()                   // 開啟設定 Activity 的常用語設定對話框
    object Globe : KeyAction()                             // 切換系統輸入法
    object ShowImePicker : KeyAction()                     // 顯示系統輸入法選單（工具列米/英長按）
    object VoiceInput : KeyAction()                        // 語音輸入（切到系統語音輸入法）
    object ToggleFloatingKeyboard : KeyAction()            // 浮動鍵盤 ↔ 貼底鍵盤（工具列 ID 33）
    // 編輯動作（iOS 鍵盤 extension 無 API；Android 依 sweetlime 原始定義實作）
    object SelectAll : KeyAction()                         // 全選
    object Copy : KeyAction()                              // 複製
    object Cut : KeyAction()                               // 剪下
    object Undo : KeyAction()                              // 復原（Ctrl+Z）
    object Redo : KeyAction()                              // 重做（Ctrl+Shift+Z）
    object ToggleSimpTrad : KeyAction()                    // 簡繁切換（繁中 ↔ 簡中模式）
}
