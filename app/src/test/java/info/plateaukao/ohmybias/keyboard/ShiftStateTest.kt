package info.plateaukao.ohmybias.keyboard

import org.junit.Assert.*
import org.junit.Test

class ShiftStateTest {
    @Test fun shiftGesturesAndTyping() {
        val state = ShiftState()
        state.tap(1000, 300)
        assertTrue(state.isShifted)
        assertFalse(state.isCapsLocked)
        state.consume()
        assertFalse(state.isShifted)
        state.tap(1100, 300)
        assertFalse(state.isCapsLocked) // Typing interrupts a double tap.
        state.tap(1200, 300)
        assertTrue(state.isCapsLocked)
        repeat(3) { state.consume(); assertTrue(state.isShifted) }
        state.tap(1300, 300)
        assertFalse(state.isCapsLocked)
        assertFalse(state.isShifted)

        state.tap(2000, 300)
        state.tap(2400, 300)
        assertFalse(state.isCapsLocked) // Slow taps just toggle Shift.
        assertFalse(state.isShifted)
        state.tap(3000, 300)
        state.interruptTapSequence()
        state.tap(3100, 300)
        assertFalse(state.isCapsLocked) // Other keys interrupt double tap too.

        state.lock() // Long press works without a preceding tap.
        state.consume()
        assertTrue(state.isCapsLocked)
        assertTrue(state.isShifted)
        state.reset() // Changing language clears Shift and Caps Lock.
        assertFalse(state.isShifted)
        assertFalse(state.isCapsLocked)
        state.tap(3200, 300)
        assertFalse(state.isCapsLocked)
    }
}
