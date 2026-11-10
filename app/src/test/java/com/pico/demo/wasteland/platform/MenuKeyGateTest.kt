package com.pico.demo.wasteland.platform

import com.pico.demo.wasteland.game.GameRules
import org.junit.Assert.*
import org.junit.Test

class MenuKeyGateTest {
    @Test fun shortPressOpensOneMenuOnlyOnRelease() {
        val key = MenuKeyGate(500)
        assertNull(key.down(1000, 1000, 0))
        assertNull(key.down(1000, 1200, 1))
        assertEquals(MenuKeyGate.Action.MENU, key.up(1000, 1499, false))
        assertNull(key.up(1000, 1499, false))
    }

    @Test fun longPressExitsOnceWithoutTogglingPauseOnRelease() {
        val key = MenuKeyGate(500)
        key.down(1000, 1000, 0)
        assertEquals(MenuKeyGate.Action.EXIT, key.down(1000, 1500, 1))
        assertNull(key.down(1000, 1800, 2))
        assertNull(key.up(1000, 1900, false))
        assertNull(key.down(1000, 2000, 3))
        key.down(3000, 3000, 0)
        assertEquals(MenuKeyGate.Action.MENU, key.up(3000, 3100, false))
    }

    @Test fun longPressWorksEvenWithoutAndroidRepeatEvents() {
        val key = MenuKeyGate(500)
        key.down(1000, 1000, 0)
        assertEquals(MenuKeyGate.Action.EXIT, key.up(1000, 1500, false))
        assertNull(key.up(1000, 1501, false))
    }

    @Test fun cancelledAndFocusLostPressesCannotActOnResume() {
        val key = MenuKeyGate(500)
        key.down(1000, 1000, 0)
        assertNull(key.up(1000, 1100, true))
        key.down(2000, 2000, 0)
        key.reset()
        assertNull(key.down(2000, 2600, 1))
        assertNull(key.up(2000, 2700, false))
        assertNull(key.up(3000, 3100, false))
        key.down(4000, 4000, 0)
        assertEquals(MenuKeyGate.Action.MENU, key.up(4000, 4100, false))
    }

    @Test fun staleReleaseDoesNotCancelANewerPress() {
        val key = MenuKeyGate(500)
        key.down(1000, 1000, 0)
        key.down(2000, 2000, 0)
        assertNull(key.up(1000, 2100, false))
        assertEquals(MenuKeyGate.Action.MENU, key.up(2000, 2200, false))
    }

    @Test fun deathStillAllowsMenuAndResumeCannotReviveOrFire() {
        val game = GameRules()
        game.damagePlayer(1000)
        assertTrue(game.over)
        game.togglePause()
        assertTrue(game.paused)
        assertFalse(game.shoot())
        game.togglePause()
        assertFalse(game.paused)
        assertTrue(game.over)
        assertFalse(game.shoot())
        game.restart()
        assertFalse(game.over)
        assertFalse(game.paused)
        assertEquals(100, game.health)
    }
}
