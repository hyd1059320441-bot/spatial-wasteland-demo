package com.pico.demo.wasteland.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.demo.wasteland.game.GameCommand
import com.pico.demo.wasteland.game.HudState
import com.pico.demo.wasteland.game.WastelandScene
import com.pico.demo.wasteland.platform.LaunchActivity
import com.pico.demo.wasteland.ui.hud.components.GameHud
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.content.Context
import android.content.ContextWrapper
import android.util.Log

@Composable
fun HomeStage() = Arena()

@Composable
private fun Arena() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(HudState()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var exiting by remember { mutableStateOf(false) }
    var exitFailure by remember { mutableStateOf<String?>(null) }
    val scene = remember { WastelandScene(context) { state = it } }
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.launchActivity() }
    val onCommand: (GameCommand) -> Unit = remember(scene, navigator, scope, activity) {
        { command ->
            if (command == GameCommand.EXIT && !exiting) {
                exiting = true
                exitFailure = null
                scene.active = false
                Log.i("Wasteland", "GAME_EXIT_REQUEST")
                scope.launch {
                    try {
                        navigator.closeStage()
                        activity?.finish()
                        Log.i("Wasteland", "GAME_EXIT_CLOSED")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("Wasteland", "GAME_EXIT_FAILED", error)
                        exiting = false
                        scene.active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        exitFailure = "退出未完成，请再次点击退出游戏"
                    }
                }
            } else if (!exiting) scene.submit(command)
        }
    }
    DisposableEffect(activity, onCommand) {
        activity?.gameCommandHandler = onCommand
        onDispose {
            if (activity?.gameCommandHandler === onCommand) activity.gameCommandHandler = null
        }
    }
    val limbManager = LocalStageUpperLimbRenderModelManager.current
    DisposableEffect(limbManager) {
        val result = limbManager.setUpperLimbRenderMode(UpperLimbRenderMode.Hidden)
        Log.i("Wasteland", "V3_UPPER_LIMB requested=Hidden result=$result")
        onDispose { limbManager.setUpperLimbRenderMode(UpperLimbRenderMode.Default) }
    }
    DisposableEffect(scene, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            scene.active = !exiting && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        scene.active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            scene.close()
        }
    }
    SpatialView(
        attachments = {
            AttachmentPanel("game_hud") {
                val handHaptic = LocalHandControllerHaptic.current
                DisposableEffect(scene, handHaptic) {
                    scene.haptic = { level, duration ->
                        handHaptic.feedback(HandControllerHapticType.custom(level, 130, duration),
                            HandController.Right)
                    }
                    onDispose { scene.haptic = { _, _ -> } }
                }
                PicoTheme {
                    GameHud(state, failure, onCommand, scene::onHudRendered, exiting, exitFailure)
                }
            }
        }
    ) { content, attachments ->
        content.addEntity(scene.root)
        content.addEntity(scene.camera)
        try {
            scene.initialize(attachments.entity("game_hud"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("Wasteland", "SCENE_LOAD_FAILED", error)
            failure = "场景加载失败：${error.message ?: error.javaClass.simpleName}"
        }
    }
}

private fun Context.launchActivity(): LaunchActivity? = when (this) {
    is LaunchActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.launchActivity()
    else -> null
}
