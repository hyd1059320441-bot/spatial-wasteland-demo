package com.pico.demo.wasteland.ui.hud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.demo.wasteland.game.GameCommand
import com.pico.demo.wasteland.game.HudState
import com.pico.spatial.ui.design.*

/** A single Stage attachment: compact combat instruments, expanded only on user request. */
@Composable
fun GameHud(state: HudState, failure: String?, onCommand: (GameCommand) -> Unit,
    onRendered: (HudState, Int, Int) -> Unit, exiting: Boolean = false, exitFailure: String? = null) {
    var renderedWidth by remember { mutableIntStateOf(0) }
    var renderedHeight by remember { mutableIntStateOf(0) }
    SideEffect { if (renderedWidth > 0 && renderedHeight > 0) onRendered(state, renderedWidth, renderedHeight) }
    val foreground = PicoTheme.colorScheme.labelPrimaryLight
    // One bounded HUD surface owns its dark fill; all internal structural groups stay transparent.
    Column(
        Modifier.width(if (state.rewardMenuOpen) 930.dp else 840.dp)
            .onSizeChanged { renderedWidth = it.width; renderedHeight = it.height }
            .clip(RoundedCornerShape(20.dp))
            .background(PicoTheme.colorScheme.fillPrimary).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (exitFailure != null) Text(exitFailure, fontSize = 22.sp, color = foreground)
        when {
            exiting -> Text("正在退出游戏…", fontSize = 26.sp, color = foreground)
            failure != null -> {
                Text(failure, fontSize = 24.sp, color = foreground)
                ExitButton(onCommand)
            }
            !state.ready -> {
                Text(state.status, fontSize = 26.sp, color = foreground)
                ExitButton(onCommand)
            }
            state.paused -> PauseControls(state, onCommand)
            state.over -> {
                Text("行动失败", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = foreground)
                Text("击破 ${state.kills} 台 · 获得 ${state.score} 分", fontSize = 22.sp, color = foreground)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Button(onClick = { onCommand(GameCommand.RESTART) }) { Text("重新挑战") }
                    ExitButton(onCommand)
                }
            }
            state.rewardMenuOpen -> RewardChoices(state, onCommand)
            state.complete -> {
                Text("挑战完成", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = foreground)
                Text("击破 100 / 100 · 获得 ${state.score} 分", fontSize = 24.sp, color = foreground)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Button(onClick = { onCommand(GameCommand.RESTART) }) { Text("再挑战一次") }
                    ExitButton(onCommand)
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.complete) "矿区已夺回" else "矿区前哨",
                        fontSize = 23.sp, fontWeight = FontWeight.Bold, color = foreground)
                    Text("击破 ${state.kills} / 100 · ${state.score} 分", fontSize = 20.sp, color = foreground)
                    Button(onClick = { onCommand(GameCommand.PAUSE) }) { Text("菜单 · X / B") }
                }
                Text(state.objective, fontSize = 23.sp, color = foreground, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("生命  ${state.health} / ${state.maxHealth}", fontSize = 22.sp, color = foreground)
                        LinearProgressIndicator(progress = { state.health.toFloat() / state.maxHealth },
                            modifier = Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.weapon, fontSize = 20.sp, color = foreground)
                        Text("${state.ammo} / ${state.magazine}", fontSize = 38.sp,
                            fontWeight = FontWeight.Bold, color = foreground)
                        if (state.reloading) LinearProgressIndicator(progress = { state.reloadProgress },
                            modifier = Modifier.fillMaxWidth())
                        else Text(if (state.ammo == 0) "A 换弹" else "I / Grip 切换武器",
                            fontSize = 18.sp, color = foreground)
                    }
                    Text("敌人\n${state.enemies}", fontSize = 24.sp, color = foreground)
                }
                if (state.killFeedback.isNotEmpty())
                    Text(state.killFeedback, fontSize = 22.sp, color = foreground)
                if (state.pickupHint.isNotEmpty())
                    Text(state.pickupHint, fontSize = 23.sp, color = foreground)
                if (state.rewardAvailable) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("击杀进度升级可领取", fontSize = 22.sp, color = foreground)
                        Button(onClick = { onCommand(GameCommand.OPEN_REWARDS) }) {
                            Text(if (state.pickupHint.isNotEmpty()) "选择升级" else "选择升级 · Y")
                        }
                    }
                } else Text(state.status, fontSize = 19.sp, color = foreground, maxLines = 2)
                if (state.talents.isNotEmpty())
                    Text("本局强化  " + state.talents.joinToString(" · ") { it.label },
                        fontSize = 18.sp, color = foreground, maxLines = 2)
                if (state.arsenalHint.isNotEmpty())
                    Text("已持有  ${state.arsenalHint}", fontSize = 18.sp, color = foreground, maxLines = 2)
            }
        }
    }
}

@Composable
private fun RewardChoices(state: HudState, onCommand: (GameCommand) -> Unit) {
    val foreground = PicoTheme.colorScheme.labelPrimaryLight
    Text("战区升级", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = foreground)
    Text("选择一项，立即强化本次行动。返回后仍可自由射击。",
        fontSize = 21.sp, color = foreground)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        state.rewardOffers.forEachIndexed { index, talent ->
            Option(
                selected = state.rewardCursor == index,
                onSelectChange = {
                    onCommand(listOf(GameCommand.SELECT_REWARD_0,
                        GameCommand.SELECT_REWARD_1, GameCommand.SELECT_REWARD_2)[index])
                },
                modifier = Modifier.weight(1f).heightIn(min = 190.dp),
                // The component owns both state colors; its text slots inherit the paired foreground.
                colors = OptionDefaults.optionColors(
                    checkedContainerColor = PicoTheme.colorScheme.fillSecondary,
                    checkedContentColor = PicoTheme.colorScheme.labelPrimary,
                    unCheckedContainerColor = PicoTheme.colorScheme.fillLight,
                    unCheckedContentColor = foreground,
                ),
                enabled = state.ready,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("升级 ${index + 1}", fontSize = 18.sp)
                    Text(talent.label, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(talent.description, fontSize = 21.sp, maxLines = 4)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onCommand(GameCommand.CLOSE_REWARDS) }) { Text("返回战场 · B") }
        Button(onClick = { onCommand(GameCommand.PAUSE) }) { Text("菜单 · X") }
        Button(onClick = { onCommand(GameCommand.CONFIRM_REWARD) },
            enabled = state.rewardOffers.isNotEmpty()) {
            Text("装配 ${state.rewardOffers.getOrNull(state.rewardCursor)?.label.orEmpty()} · Y")
        }
    }
}

@Composable
private fun PauseControls(state: HudState, onCommand: (GameCommand) -> Unit) {
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    val foreground = PicoTheme.colorScheme.labelPrimaryLight
    Text(if (confirmRestart) "重新开始本次行动？" else if (state.over) "行动失败" else "已暂停",
        fontSize = 32.sp, fontWeight = FontWeight.Bold, color = foreground)
    Text(if (confirmRestart) "本局进度和升级将清空。" else
        "X / B 打开或关闭菜单 · 退出游戏返回系统",
        fontSize = 22.sp, color = foreground)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Button(onClick = {
            if (confirmRestart) confirmRestart = false else onCommand(GameCommand.PAUSE)
        }) { Text(if (confirmRestart) "返回" else if (state.over) "返回结果" else "继续行动 · X / B") }
        Button(onClick = {
            if (confirmRestart) onCommand(GameCommand.RESTART) else confirmRestart = true
        }) { Text(if (confirmRestart) "确认重新开始" else "重新开始") }
        ExitButton(onCommand)
    }
}

@Composable
private fun ExitButton(onCommand: (GameCommand) -> Unit) {
    Button(onClick = { onCommand(GameCommand.EXIT) }) { Text("退出游戏") }
}
