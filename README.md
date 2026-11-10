# 废土矿区 V12

最新版源码备份：2026-11-10。V12 增加紫金精英、持久武器掉落和电弧 / 轨道 / 等离子三款枪械；
累计 100 个敌人、场上最多 8 个。当前 APK 的 Android 版本号仍为 `3.0 (3)`。
安装包、校验值及交付说明见 [V12 Release](https://github.com/hyd1059320441-bot/spatial-wasteland-demo/releases/tag/v12)。
最新玩法与验证范围见 [V12 交付说明](artifacts/2026-10-23-v12/delivery.md)；
恢复构建与源码清单见 [备份说明](github-backup/README.md)。

PICO Spatial SDK **6.2.6 正式版**、Kotlin ECS、Full Space Stage 和 SpatialUI
实现的第一人称 VR 射击游戏。包名为 `com.pico.demo.wasteland`，APK 版本
`3.0 (3)`。

V3 使用 Meshy 生成 USDZ 构建约 78×100 米的四区矿场。武器、机器人、
沙岩、维修棚、矿车、起重机、仓库、反应堆、发电机、集装箱、油桶、宝箱、弹壳与
能量弹采用同一套原创美式漫画废土语言，并由运行时黑色背面描边统一轮廓。旧 V1/V2
环境 GLB 已退出 APK；机器人使用重新配色的 CC0 骨骼模型；来源、处理方式和最终哈希见
[THIRD_PARTY_ASSETS.md](THIRD_PARTY_ASSETS.md)。

## 操作

戴好头显并唤醒手柄。系统手柄和手臂模型隐藏，枪械跟随右手柄。
HUD 使用原生 SpatialUI 生命条、弹药数字、可点击升级卡和暂停操作。
系统默认白色射线尚未解决；当前 SDK 未找到可调用的 HideRay 接口。

| 输入 | 行为 |
| --- | --- |
| 右扳机 | 步枪按住连射；霰弹枪单发并产生 8 条散布射线 |
| 右摇杆 | 前推始终沿实时头部水平朝向移动；转头后前方立即随之改变 |
| 左摇杆左右 | 每次转向 30°，回中后可再次转向 |
| 左摇杆向前保持并松开 | 使用左手抛物线瞬移；门禁、掩体、坡道和高度均参与判定 |
| 右 A | 换弹，清场后仍是换弹；死亡后重新挑战；奖励菜单内返回战场 |
| 右 B | 暂停 / 继续；奖励菜单内返回战场 |
| 左 X | 打开 / 关闭暂停菜单；奖励菜单内也能打开暂停菜单 |
| HUD 菜单 → 退出游戏 | 关闭游戏 Stage，返回系统；加载、失败和死亡页面也提供退出按钮 |
| 右 Grip / 键盘 I | 在已拥有的五类枪械之间循环；主动打开奖励菜单后切换天赋 |
| 左 Y / 键盘 Y | 2 米内、同楼层且无遮挡时优先拾取武器；其次处理升级与任务交互 |

系统向应用转发 Android MENU / START / ESC 时，短按打开菜单，长按退出。
系统 HOME 键由系统处理；游戏菜单可直接使用 X / B 或 HUD 按钮。

枪口、金属命中、尘土和电击使用 SDK `ParticleComponent`。退壳与敌方慢速攻击使用
生成的弹壳和能量弹模型。射击油桶会造成范围伤害并可连锁引爆。
玩家曳光为 6 毫米宽、最多 22 厘米长的橙色短光迹，到达终点立即回收。
命中显示四斜线标记（装甲橙色、爆头青色、击杀红色）、机器人闪白和受击硬直；
换弹结束有独立上膛音。敌人持枪或能量棍，预警从武器端闪烁并伴随提示音，
枪弹从实际骨骼挂点的枪口发出，普通命中可打断蓄力。
最多两台敌人同时预警，攻击间隔至少 0.22 秒；预警开始锁定瞄准点，
近战为固定方向的 80° 挥击范围，可侧移躲开。受击只亮来袭方向对应的细边，
残弹和换弹时间在弹药栏直接显示。扳机、Grip 和 A/B/Y 在失焦、失联后需要先松开。
枪击、爆头、精英和油桶击杀统一产生一次死亡反馈：小块金属碎片与短促电光，
朝敌人背后和下方散落，0.42 秒结束，近战距离关闭额外粒子；无死亡光球、冲击环或烟雾，
配有普通/爆头/精英确认音和随距离衰减的机械破裂声。HUD 显示本次得分，
3 秒内连续击杀显示连杀数，第三次起追加封顶 100 分的奖励；
霰弹不会反复给尸体记分或播放命中音。入口、采矿场和最终增援清场补充最多
15 点生命，基础生命上限为 100。

入口、采矿场和反应堆守卫清场后各出现一次随机三选一天赋，已获得的天赋不再出现。
HUD 显示明确的升级入口。奖励待领取时照常射击、换弹和切枪。
按 Y 主动打开三张升级卡，Grip 或指向点击选择，Y 或确认按钮领取；B 可关闭菜单。
菜单打开期间冻结战斗，关闭后扳机需先松开。领取后按 Y 推进任务或靠近目标交互。
六种天赋为：高压弹药（枪弹伤害 +20%）、弱点解析（爆头 4 倍）、快装弹匣
（换弹耗时 -25%）、复合护甲（受伤 -3，最低 1）、回收维修（击杀回血 2）和
备用电池（上限 +25、立即回血 25）。天赋对两把武器共享，重开清空本局成长。

环境包含远景风机、通风扇、烟柱、风沙和爆炸烟尘。原创工业风 BGM 与战斗节奏
叠加，四区环境循环渐变。BGM、环境和非枪声音量增强，持续枪声不再压低 BGM，
击杀时短暂降低背景音突出确认提示；远处敌人音效按距离和头部朝向衰减。

## 任务

1. 清理入口车辆维修区的 14 台机器人，选择天赋，按 Y 推进。
2. 推进中央采矿场并清理第二波 16 台机器人，选择第二个天赋。
3. 进入仓储侧路，靠近发电机按 Y 恢复供电。
4. 穿过闸门，击败 1 台反应堆精英和 12 台护卫；清完护卫后选择第三个天赋并领取升级。
5. 获取电击核心、清理最终 16 台增援并登上 2 米平台开启宝箱。

整轮共 59 台。重开和连续移动使用本帧 ECS 坐标，避免读取尚未提交的原生矩阵
把玩家拉回旧位置；重开清空瞬移、曳光和击杀效果。
通关后仍可探索、射击和切枪。重新开始由暂停菜单明确操作并确认，清场后 A 不会误重开。

所有存活敌人每帧共享玩家当前坐标，失去视线或受到硬直时也更新目标。
房屋、仓库、起重机、反应堆和掩体周围初始化时按实际胶囊碰撞建立 0.5 米导航网格，
后台 A* 寻找入口并绕开墙面与掩体；拥堵触发分离、侧移和重新规划。
网格烘焙和运行时使用同一胶囊尺寸；出生点会避开真实网格中的障碍。
发电机位于仓库后墙外。HUD 显示目标距离，并用真实遮挡与平台高度判断是否能按 Y。
攻击仍需要真实视线和弹道遮挡检查，不能隔墙伤害。静态网格在重开后保留。
门关闭时玩家、瞬移和敌人都不能越过反应堆边界。

## 构建与运行

需要 JDK 17+、Android SDK 35 和支持 Spatial Runtime 的设备。

```bash
cd /Users/bytedance/Documents/ai/spatial-wasteland-demo
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --replace -d PB331XPGL6040019G
pico-cli app launch com.pico.demo.wasteland -d PB331XPGL6040019G
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。USDZ 必须在
`androidResources.noCompress` 中保持未压缩。

## 验证

单测共 70 项，覆盖射击、任务、追踪、攻击中断、遮挡、绕行、窄门、
隐藏移动目标、击杀事件、爆炸连杀、尸体清理、输入失联、重定位坐标边界、
随机奖励、天赋效果、领奖期间换弹、取消菜单恢复，以及头显转向与虚拟转向组合。
菜单键检查覆盖短按、长按、重复、取消、失焦与死亡后打开菜单。
`ExitFlowTest` 真机测试检查加载中退出、重复进入/退出与场景资源释放。
Debug APK 的真机自检命令：

```bash
adb -s PB331XPGL6040019G shell am start -S \
  -n com.pico.demo.wasteland/.platform.LaunchActivity --ez wasteland_self_check true
adb -s PB331XPGL6040019G logcat -s Wasteland:I AndroidRuntime:E
```

合并运行自检、40 次重开和 120 秒进屋追踪/交火，并保存对应进程的日志：

```bash
python3 tools/run_polish_device_check.py --serial PB331XPGL6040019G \
  --output artifacts/device-check.log
```

可用 `--check self` 或 `--check combat` 单独运行；`--trace <文件路径>`
可在交火期间使用 pico-cli 采集 30 秒 Perfetto。测试结束会恢复正常规则。
`--combat-seconds 600` 可运行 10 分钟的交火/切枪/重开/暂停组合检查，
每分钟保存同一进程的 PSS，日志读取只消费新增内容。该长时检查需要显式启用。
循环音乐独立于短音效池；暂停清空临时粒子，过期粒子关闭实体。
描边壳和无光照模型禁用重复阴影，主体 PBR 模型保留阴影。
`--check mission` 连续用真实胶囊移动经过仓储侧路、闸门、发电机、坡道、核心和宝箱，
并验证三次天赋选择；伤害使用脚本推进，不能据此称已覆盖真实手柄战斗。

加载完成标记为 `SCENE_READY version=3`，自检通过标记为
`V3_SELFCHECK_PASS sdk=6.2.6 version=3`。真实手柄、画面、震动、舒适度和长期帧率
仍需佩戴头显观察；自动自检不替代这些项目。完整证据见
[本轮反馈与验证记录](artifacts/2026-10-15-combat-repair/feedback-verification.md)。
进屋追踪与击杀打磨的证据见
[打磨验证记录](artifacts/2026-10-15-combat-repair/polish-verification.md)。
曳光、重开、敌人扩充和音量改动见
[本轮验证记录](artifacts/2026-10-15-combat-repair/tracer-restart-verification.md)。
10 月 16 日的特效减量、人数、资源生命周期及性能取证见
[持续打磨记录](artifacts/2026-10-16-polish/work-log.md)；
[性能报告](artifacts/2026-10-16-polish/session-output-20261016-104144/report.html)
记录了阴影工作量减半及仍待解决的 GPU 帧率问题。

主要文件：

- `game/V3Layout.kt`：四区几何、门禁、坡道、滑动碰撞和 A*。
- `game/NavigationGrid.kt` / `NavigationWorker.kt`：不可变墙面采样和后台寻路。
- `game/GameRules.kt`：战斗、任务、敌人导航和区域检查点。
- `game/RunTalents.kt`：随机三选一与局内天赋数值。
- `game/SceneKit.kt` / `OutpostEnvironment.kt`：USDZ 缓存、归一化、描边和场景。
- `game/AnimatedActor.kt`：骨骼动画、持械挂点、攻击预警和命中体。
- `game/ParticleFx.kt`：可复用原生粒子池。
- `game/KillVfx.kt` / `DefeatGeometry.kt`：视线安全的金属碎片与方向/范围约束。
- `game/SceneCoordinates.kt`：本帧 ECS 坐标与玩家重定位。
- `game/WeaponView.kt` / `WastelandScene.kt`：武器、手柄、瞬移、射击和真机自检。
- `content/HomeStage.kt`：原生 PicoTheme SpatialUI HUD。
- `ui/hud/components/GameHud.kt`：战斗仪表、三选一升级与暂停/重开交互。
