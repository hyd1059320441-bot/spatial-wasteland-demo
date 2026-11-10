package com.pico.demo.wasteland.platform

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import com.pico.demo.wasteland.game.GameCommand
import com.pico.demo.wasteland.game.RunOptions
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {
    private val menuKey = MenuKeyGate(ViewConfiguration.getLongPressTimeout().toLong())
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Log.i("Wasteland", "GAME_MENU_KEY source=back")
            gameCommandHandler?.invoke(GameCommand.PAUSE)
        }
    }
    var gameCommandHandler: ((GameCommand) -> Unit)? = null
        set(value) {
            field = value
            backCallback.isEnabled = value != null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        RunOptions.launchStartMs = android.os.SystemClock.elapsedRealtime()
        RunOptions.selfCheck = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("wasteland_self_check", false)
        RunOptions.stress = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("wasteland_combat_stress", false)
        RunOptions.missionStress = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("wasteland_mission_stress", false)
        RunOptions.profileNoInk = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("wasteland_profile_no_ink", false)
        // The distance experiment showed no material GPU/frame benefit; normal play keeps quality.
        RunOptions.profileFullShadows = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !intent.getBooleanExtra("wasteland_profile_shadow_budget", false)
        RunOptions.stressDurationMs = intent.getIntExtra("wasteland_combat_seconds", 120)
            .coerceIn(120, 1200) * 1000L
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = gameCommandHandler
        if (handler != null && event.keyCode in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_Y)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled)
                handler(if (event.keyCode == KeyEvent.KEYCODE_I) GameCommand.SWITCH else GameCommand.INTERACT)
            return true
        }
        if (handler != null && event.keyCode in
            listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ESCAPE)) {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> menuKey.down(event.downTime, event.eventTime, event.repeatCount)
                KeyEvent.ACTION_UP -> menuKey.up(event.downTime, event.eventTime, event.isCanceled)
                else -> null
            }
            if (action != null) {
                val command = if (action == MenuKeyGate.Action.EXIT) GameCommand.EXIT else GameCommand.PAUSE
                Log.i("Wasteland", "GAME_MENU_KEY key=${event.keyCode} command=$command")
                handler(command)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) menuKey.reset()
    }

    override fun onPause() {
        menuKey.reset()
        super.onPause()
    }
}
