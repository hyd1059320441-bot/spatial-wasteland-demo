package com.pico.demo.wasteland.platform

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.demo.wasteland.game.GameCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Activity dispatch coverage; physical headset/controller delivery is still a manual check. */
@RunWith(AndroidJUnit4::class)
class ArsenalKeyTest {
    @Test fun iAndYDispatchOnceOnReleaseAndRejectCancelledReleases() {
        val scenario = ActivityScenario.launch(LaunchActivity::class.java)
        lateinit var activity: LaunchActivity
        scenario.onActivity { activity = it }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val deadline = SystemClock.uptimeMillis() + 75_000
            var bound = false
            while (!bound && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { bound = activity.gameCommandHandler != null }
                if (!bound) SystemClock.sleep(100)
            }
            assertTrue("Game command binding did not appear", bound)
            instrumentation.runOnMainSync {
                val original = activity.gameCommandHandler
                val commands = mutableListOf<GameCommand>()
                activity.gameCommandHandler = { commands.add(it) }
                try {
                    val time = SystemClock.uptimeMillis()
                    for (key in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_Y)) {
                        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0)))
                        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time + 40, KeyEvent.ACTION_DOWN, key, 2)))
                        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time + 80, KeyEvent.ACTION_UP, key, 0)))
                    }
                    assertEquals(listOf(GameCommand.SWITCH, GameCommand.INTERACT), commands)
                    for (key in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_Y)) {
                        activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0))
                        activity.dispatchKeyEvent(KeyEvent(time, time + 80, KeyEvent.ACTION_UP,
                            key, 0, 0, -1, 0, KeyEvent.FLAG_CANCELED))
                    }
                    assertEquals(2, commands.size)
                    Log.i("Wasteland", "V12_KEY_DISPATCH_PASS I=SWITCH Y=INTERACT repeatDown=ignored cancelledUp=ignored")
                } finally { activity.gameCommandHandler = original }
            }
        } finally { scenario.close() }
    }
}
