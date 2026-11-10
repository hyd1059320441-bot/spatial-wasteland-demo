package com.pico.demo.wasteland.platform

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.demo.wasteland.game.GameCommand
import com.pico.demo.wasteland.game.WastelandScene
import com.pico.demo.wasteland.game.WastelandSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Stage disposal on a device without screen-coordinate or compositor assumptions. */
@RunWith(AndroidJUnit4::class)
class ExitFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 75_000
        while (SystemClock.uptimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }

    @Test fun closeDuringLoadingDoesNotWaitForTheEcsCommandQueue() {
        val scenario = ActivityScenario.launch(LaunchActivity::class.java)
        lateinit var activity: LaunchActivity
        scenario.onActivity { activity = it }
        try {
            await("command binding") { activity.gameCommandHandler != null }
            instrumentation.runOnMainSync {
                assertNull("This check must exit before scene initialization", WastelandSystem.runtime)
                activity.gameCommandHandler!!.invoke(GameCommand.EXIT)
            }
            await("loading Stage closed") { activity.isDestroyed && WastelandSystem.runtime == null }
            Log.i("Wasteland", "EXIT_FLOW_LOADING_PASS")
        } finally { scenario.close() }
    }

    @Test fun menuAndExitSurviveRepeatedStageCreationAndDisposeAllSceneResources() {
        repeat(3) { cycle ->
            val scenario = ActivityScenario.launch(LaunchActivity::class.java)
            lateinit var activity: LaunchActivity
            lateinit var scene: WastelandScene
            scenario.onActivity { activity = it }
            try {
                await("scene ready") { activity.gameCommandHandler != null && WastelandSystem.runtime != null }
                instrumentation.runOnMainSync { scene = WastelandSystem.runtime!! }
                if (cycle == 0) {
                    instrumentation.runOnMainSync {
                        val time = SystemClock.uptimeMillis()
                        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0)))
                        assertTrue(activity.dispatchKeyEvent(KeyEvent(time, time + 50,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0)))
                    }
                    SystemClock.sleep(500)
                }
                instrumentation.runOnMainSync {
                    if (cycle == 1) {
                        scene.active = false
                        activity.gameCommandHandler!!.invoke(GameCommand.EXIT)
                        activity.gameCommandHandler?.invoke(GameCommand.EXIT)
                    } else {
                        val time = SystemClock.uptimeMillis()
                        activity.dispatchKeyEvent(KeyEvent(time, time,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0))
                        activity.dispatchKeyEvent(KeyEvent(time, time + 2000,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 1))
                        activity.dispatchKeyEvent(KeyEvent(time, time + 2100,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0))
                    }
                }
                await("Stage and resources disposed, cycle=$cycle") {
                    activity.isDestroyed && WastelandSystem.runtime == null &&
                        !scene.root.valid && !scene.camera.valid
                }
                Log.i("Wasteland", "EXIT_FLOW_CYCLE_PASS cycle=${cycle + 1}")
            } finally { scenario.close() }
        }
    }
}
