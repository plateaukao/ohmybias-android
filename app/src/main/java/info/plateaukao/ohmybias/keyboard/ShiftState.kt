package info.plateaukao.ohmybias.keyboard

internal class ShiftState {
    var isShifted = false
        private set
    var isCapsLocked = false
        private set
    private var lastTap: Long? = null

    fun tap(now: Long, doubleTapTimeout: Long) {
        if (isCapsLocked) {
            reset()
        } else if (lastTap?.let { now - it in 0..doubleTapTimeout } == true) {
            lock()
        } else {
            isShifted = !isShifted
            lastTap = now
        }
    }

    fun lock() {
        isCapsLocked = true
        isShifted = true
        interruptTapSequence()
    }

    fun interruptTapSequence() { lastTap = null }

    fun consume() {
        interruptTapSequence()
        if (!isCapsLocked) isShifted = false
    }

    fun reset() {
        isCapsLocked = false
        isShifted = false
        interruptTapSequence()
    }
}
