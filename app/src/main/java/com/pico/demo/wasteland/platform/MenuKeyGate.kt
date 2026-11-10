package com.pico.demo.wasteland.platform

/** One action per key press, including Android repeats and cancelled/focus-lost presses. */
class MenuKeyGate(private val longPressMs: Long) {
    enum class Action { MENU, EXIT }
    private var startedAt: Long? = null
    private var handled = false

    fun down(downTime: Long, eventTime: Long, repeatCount: Int): Action? {
        if (repeatCount == 0) {
            startedAt = downTime
            handled = false
        }
        if (startedAt != downTime || handled) return null
        if (eventTime - downTime < longPressMs) return null
        handled = true
        return Action.EXIT
    }

    fun up(downTime: Long, eventTime: Long, cancelled: Boolean): Action? {
        if (startedAt != downTime) return null
        val action = if (!handled && !cancelled && eventTime >= downTime)
            if (eventTime - downTime >= longPressMs) Action.EXIT else Action.MENU
        else null
        reset()
        return action
    }

    fun reset() { startedAt = null; handled = false }
}
