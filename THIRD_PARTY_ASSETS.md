# 第三方资产

## Kenney Blaster Kit 2.1（V1/V2 历史资产）

- 作者：Kenney
- 来源：https://kenney.nl/assets/blaster-kit
- 原始下载：https://kenney.nl/media/pages/assets/blaster-kit/261d80a716-1753959510/kenney_blaster-kit_2.1.zip
- 下载日期：2026-10-14
- 许可：Creative Commons Zero 1.0（CC0）；允许个人、教育、商业使用，无强制署名要求。
- 许可全文：https://creativecommons.org/publicdomain/zero/1.0/
- 原包及许可证：`assets-src/kenney/`

| V1/V2 文件 | 原包文件 | SHA-256 |
| --- | --- | --- |
| models/blaster-a.glb | Models/GLB format/blaster-a.glb | d6d6fe0ec5baf21d7717449220799d45b95d2d663ace7b22612b255dc1a8b308 |
| models/crate-medium.glb | Models/GLB format/crate-medium.glb | 187c32592fedaecbbb84152102bbf536f733cbc0c2cfee60828fb18a4803619c |
| models/crate-small.glb | Models/GLB format/crate-small.glb | 00ed47c02a5016c11ce5ea0bd54888d120f3a112c5eab79dc4bd4ab878ceb805 |
| models/crate-wide.glb | Models/GLB format/crate-wide.glb | 0a17a51b274d238c09ade21674b70742bd5f1703f7b0e8974b6d0b2b92a58c8b |
| models/Textures/colormap.png | Models/GLB format/Textures/colormap.png | 4d0867c3c3c8c539106f91fbc0987d0e1b7d2811362d1daed593169c7d3b0fdc |

GLB 文件直接来自原始包，未重新建模。第二版另使用同包 `blaster-d.glb`；其
SHA-256 见下方 V2 manifest。以上文件已从 V3 运行时删除，仅保留在
`assets-src/` 的历史源包与记录中。

## Quaternius Modular Sci-Fi MegaKit Standard（V2 历史资产）

- 作者及官方介绍：https://quaternius.com/packs/modularscifimegakit.html
- 下载来源：Godot Asset Store 的 Quaternius 资源镜像，https://store.godotengine.org/asset/quaternius/modular-sci-fi-megakit/download/143/
- 下载日期：2026-10-14。itch.io 下载未完成后切换此源成功。
- 许可：CC0；下载包内 `License_Standard.txt` 随源文件保留。
- 原包：`assets-src/quaternius/modular-scifi-standard.zip`，SHA-256 `6fae60cf5189e44dff0bd91097f094a765acc6d57d64a85a0cc0dd56e03035e3`。
- 解压源文件：`assets-src/quaternius/environment/Modular SciFi MegaKit[Standard]/`。

V2 的 `models/outpost/` 曾使用该包中的建筑、设备、宝箱与道具；这些文件已从 V3
运行时删除。转换记录仍保存在 V2 manifest。

`tools/prepare_v2_assets.py` 拼接原始 glTF buffer 为 GLB，保留网格、材质引用、骨骼、动画与原始节点。共享 PNG 用 macOS `sips` 将最长边缩至 1024，减少头显纹理占用；源图完整保留。每个模型的源路径、源文件 SHA-256、运行时 SHA-256、每张贴图的转换前后 SHA-256 和转换操作均见 [V2 manifest](assets-src/quaternius/v2-manifest.json)。

## RobotExpressive / Quaternius（V2 来源，V3–V7 历史敌人）

- 原作者：Tomas Laulhe / Quaternius；Three.js 示例版本修改者 Don McCurdy。
- 下载：https://threejs.org/examples/models/gltf/RobotExpressive/RobotExpressive.glb
- 许可声明：https://threejs.org/examples/webgl_animation_skinning_morph.html （示例页面注明 CC0）。
- 下载日期：2026-10-14；页面来源证据：[robot-expressive-source.md](assets-src/quaternius/robot-expressive-source.md)。
- 原文件：`assets-src/quaternius/robot-expressive.glb`；V2 曾使用
  `models/outpost/robot.glb`。二者字节相同，哈希见 V2 manifest。
- 2026-10-15 的战斗修订重新接入其完整骨骼和 14 个动画，替换没有骨骼的
  Meshy 机器人。Walking、Idle、Punch、Death 驱动实际关节，未改写动画 buffer。
- V3–V7 运行时：`app/src/main/assets/models/v3/RiggedRobotComic.glb`，使用锈橙、
  铁灰、墨黑材质；V5 只保留主体骨骼，普通高 1.9 m、侧袭 1.75 m、
  盾卫 2.05 m、精英 2.65 m。
- 可复现转换：`tools/prepare_rigged_robot.py`，只修改 GLB JSON 中的材质参数。
- 源 SHA-256：`047f5e5fb3bb6d378bd1df16ca6137f2a596c99b3a1b5690b4020c05aaf6f319`。
- 运行时 SHA-256：`34a60150157334bb9fc64d913474798c9d21fe866917a3488b374d2a6fdc631a`。

模型坐标和骨骼不重新导出。源 GLB 站姿边界高度约 4.4612；角色按 SDK 实际加载边界统一缩放到上述目标高度，并在父坐标系落地。原角色朝 +Z，由实体旋转面向玩家。

本项目不包含《无主之地》的解包素材、角色、地图、音乐、标识或商标，采用原创玩法及 CC0 资源表现废土科幻风格。

## Meshy 生成资产（V3 运行时）

- 服务：https://www.meshy.ai/
- 生成日期：2026-10-15
- 生成方式：Spatial Editor 6.1.8 原生 MCP，`engine="meshy"`，PBR。
- 美术约束：原创美式漫画废土科幻；粗重墨线、分区明暗、排线阴影、锈橙/
  沙岩/深铁灰高对比。未使用或复制任何现有游戏角色、Logo 或解包素材。
- 运行时目录：`app/src/main/assets/models/v3/`
- 优化：英雄武器、机器人、发电机和宝箱纹理最长边 1024；远景/重复资产和
  小型弹药最长边 512。重包后均通过 `usdchecker`。

| 运行时文件 | SHA-256 |
| --- | --- |
| CargoContainerComicV3.usdz | a9e06416be206c5d48ae951ec7d39c7b9b3695ee3de307ad6177df5b9efd9b7e |
| CartridgeComicV3.usdz | 9be554cc62290fb392f33f2013b47d9b1e1449eda53b37e0378fd2cf01dc4bb6 |
| ChargerRobotComicV3.usdz | 07a7d444a5aed0015275a1446137ce21a4844ec0dc3857410a91664394023f65 |
| EliteRobotComicV3.usdz | 91fcb80df8bfbc6ec39df1d69b53e8a40addbffb5b1d615c21554f4cc8092ba9 |
| EnergyBoltComicV3.usdz | 37f4ce33ff2e90d133f09430269d224cc1f407555dc28e23d2ef0ccb86ff4802 |
| FuelBarrelComicV3.usdz | 430688bc8ba72db817f6952c7bc34d7dbcf188ce44346203064de625bf84ddca |
| GeneratorComicV3.usdz | 694addafe5e920dcfba7c376301f9824cb3fedbdc9dbe6465ed426761e86e43b |
| HeroRifleComicV3.usdz | 1afc9f3a4509ae5abe80e155c857bb7a41779c82fc623fac76a794e08553badc |
| HeroShotgunComicV3.usdz | 6265883686c68a351461a9e3c0282ad2df3754e5e42be9fb77a206a91a373560 |
| LootChestComicV3.usdz | d9efab0f1ff44e7829db21479fc7b4d6be58ad6039dc56adc8d755cf26f23aac |
| MiningCraneComicV3.usdz | 7a26744024eb93d6c4c3d0d42b0465e27138f177b90be153dd1e7926f2716807 |
| MiningTruckComicV3.usdz | 524c81746f349f5d190f7ac82ebd458f37fb04e17b0f74266c43e38286b64410 |
| ReactorPlantComicV3.usdz | ec401728cc2a18759c63aec790bcce4c722d79cdc8870fdea4ed4222843684a1 |
| RepairBayComicV3.usdz | 2786f7e2ca3d1d4abf05012c5855201a520ec4ed2c08eeb138c4b48b0ca4b5f1 |
| SandstoneRockComicV3.usdz | 836214164daefca7fdc0137f1b431c6b5092d355d01a26e5c3005611a3406b99 |
| ShooterRobotComicV3.usdz | 9c4e6b1b5cf6f00352fc6b042ff32680c34b6de9a3a89b4c0a4cf11540caa23b |
| WarehouseComicV3.usdz | 8bbb7c9bba9b3059a8ae7bdeaaaf759dff706a01157db8ebff50fd5aad0bf556 |

Kenney 和 Quaternius 环境包仅保留在 `assets-src/`；当前 V3 只重新接入上述
经过漫画配色处理的 RobotExpressive 骨骼模型。三件 Meshy 机器人保留来源记录，
当前敌人不再加载它们。

## 项目原创天空

`assets-src/v3/wasteland-sky.svg` 为项目内绘制的 2:1 漫画废土全景源文件，
SHA-256 `b86f800af02151982d695d529f9ef1925420d68cff29e36807238baa9bb1bca9`。
运行时 PNG 为 `app/src/main/assets/textures/wasteland-sky.png`，SHA-256
`cf6043679b865e0e96c4024201dabee881f0072a32c9745563bb9be8e92b9cb5`。

## 项目原创反馈与环境资源

- `tools/generate_audio.py` 合成所有 PCM 音效、E 小调工业节奏 BGM、战斗层和四区
  环境循环；普通击杀、爆头和精英击破采用机械瞬态、低频核心坍塌与分层确认和弦，
  另合成空间衰减的核心破裂声。未使用第三方录音或现有游戏音乐。
- `tools/generate_particles.py` 绘制两张 RGBA 粒子纹理（光焰/烟尘），供 SDK 原生
  Billboard 粒子渲染；未使用外部贴图。
- 历史能量棍、现有远景风机和通风扇为项目原创 ECS 几何。V3–V7 敌方步枪
  曾复用上述 Meshy 英雄步枪；V8 删除独立敌枪，使用生成机体内的武器。
- 机器人核心破裂和死亡烟尘复用上述原创 RGBA 纹理，以原生 `ParticleComponent`
  的独立 CORE_BREAK / CORE_SMOKE 参数渲染；没有新增第三方资产。
- V3–V7 历史武器挂点使用 RobotExpressive GLB 的实际 `Bone → Body → Hips → Abdomen →
  Torso → Shoulder.R → UpperArm.R → LowerArm.R → Palm1.R` 父链；
  使用原始 RobotArmature 的旋转和 100 倍缩放合成当前局部关节姿态。

## Editor 生成资产（V4，2026-10-20）

- 通过 Spatial Editor MCP 的受管流程生成原创维修棚、工业货箱及电力中继。
  服务返回的 provider 为 `provider:editor-mcp-model`，任务 ID 为 Meshy。
- 根 Run：`run-eb7e5cc8-7cff-48ef-9512-5d6136f76c3e`。每件资产均实际检查六个
  方向；维修棚第一版存在悬空屋顶和封闭后墙，已拒绝并重新生成。
- 这些为本项目委托生成的原创资产，不是从现有游戏提取的资源；不虚构 CC0
  许可。商业发布需以生成服务账户对应条款为准，当前回执未包含独立授权书。
- 源文件、生成任务与哈希记录：`assets-src/v4/`、`assets-src/v4/manifest.json`、
  `artifacts/2026-10-20-art-direction/mcp/`。受管场景包已完成，根 Run 最终
  revision 126、acceptedRevision 2，空间及打包门均通过，无 warnings。
  交付三件套位于 `editor-asset/src/main/res3d/SpatialPackContent/Build/`；
  游戏加载纹理优化后的单个 USDZ，不加载资产展示场景。
- `tools/prepare_v4_assets.py` 只压缩纹理最长边：维修棚及中继 1024，重复货箱
  512；保留网格、拓扑、米制单位、Y 轴向上和原始坐标。

| 运行时文件 | SHA-256 |
| --- | --- |
| models/v4/RepairBayV4.usdz | b7663787ddecb1c34761cc9f7338dfee9c859009496bd5a5c630d8d5e60bdbaf |
| models/v4/CargoCoverV4.usdz | 5a30d8f417fbc7d1d7136273a6087ec5f8b20119f62cc477a61ebfbc09196e51 |
| models/v4/PowerRelayV4.usdz | 00c69e30519b07504e476cd71fa63fa4a5aa6149808b00efc745c90416ed415f |

`assets-src/v4/outpost-soft-day-v2.hdr` 为本项目程序绘制的暖色天空与冷色填充，
只用于 Editor 资产场景；游戏继续使用原有天空和已存在的方向光 API。

## V5 资产（2026-10-21，V8 替换盾牌、无人机与货箱）

- 五个新模型来自受管 Editor 根 Run `run-d0b0cb70-7d6f-4747-b470-ef6576b616f3`，
  模型生产 Child `run-5e6fc205-4123-4a31-81c3-02c86b85c77e`。
  每件原型的六方向图已检查并通过模型审核。来源为 `provider:editor-mcp-model`，
  回执包含 Meshy 任务 ID；许可沿用上面的生成服务账户条款说明。
- 游戏直接加载独立 USDZ。Editor 展示场景已保存，但根流程仍在 author-scene
  等待候选验收，尚未发布 V5 受管 bundle。原型审核通过不代表场景包验收通过。
- `tools/prepare_v5_assets.py` 用 meshoptimizer 进行保留 UV、法线和切线的索引
  简化，再缩小纹理；原源及原 V3/V4 运行包保存在 `assets-src/v5/`。
  来源、源/运行 SHA-256、三角形数、简化误差、纹理尺寸和单位详见
  [V5 manifest](assets-src/v5/manifest.json)。以下哈希覆盖旧 V3/V4 表中的当前版本。
- 九件资产均检查了原版与新版三方向离线预览，主要轮廓、支架和开放入口保留。
  离线图使用三角形中心 UV 抽样，不是 SDK 材质/光照验收。

| 当前文件 | 三角形 | SHA-256 |
| --- | ---: | --- |
| models/v5/SalvageKioskV5.usdz | 14994 | 4748eb1a3c31202a07a123f94b76802a5ace562ea819f79697c5b4b0f26d2b1c |
| models/v5/PumpStationV5.usdz | 9997 | ebdbf044928515c10ae1bfa9ba5d11d61eda7edde6fb2998f485165267e167dd |
| models/v5/WaterCommsTowerV5.usdz | 11993 | 557d1622970b89a5322eb1f856a9c217c29236ccfefd4ec936302a8dff0710c6 |
| models/v5/SecurityDroneV5.usdz | 7996 | 6ae2bdbd2c5901742d2ad9e3aaf9df4809b4ca5c0a65450bcecf8f53dcddda74 |
| models/v5/GuardShieldV5.usdz | 3994 | f0a96a36560d48117db73805ad058c9c93bd315287e93e80139767adcfede6d9 |
| models/v4/CargoCoverV4.usdz | 7999 | 5aef483ffaad915f3a37a77f104d09be13bdfb9c1888aa0daae90ff596f39bde |
| models/v4/RepairBayV4.usdz | 17990 | 1373db156d6c85ea70167d405342100b263227d5f38f114f100e4ca3040c9cde |
| models/v4/PowerRelayV4.usdz | 9973 | 19d5f9845c07be92494f8553a7b3226c377b0f370d62981b2266bd9096710efc |
| models/v3/SandstoneRockComicV3.usdz | 6000 | 1fe102740edd433df202857a6a4a6b216796000e44ffd7e1f20ef9ec116cba0c |

## V7 原创视觉内容（2026-10-22）

- 能源机座由本项目在受管 Spatial Editor 中使用 21 个基础几何部件制作，
  包含青色钢材、奶油陶瓷与橙色防护柱；没有使用外部模型或贴图。
- 根 Run `run-cf36c1b8-d548-424c-8322-1b59cb684173` 已完成，
  Editor Child `run-7dd43cfa-1d11-45a5-9242-7e8b53732120` 的 acceptedRevision 为 1，
  场景和打包门均通过，warnings 为空。材质逐件隔离预览与七个整体视角均已检查。
- 三环能源装置、分段风旗、灯架、道路合并网格与命中碎片为 Kotlin ECS 原创几何；
  火焰和短烟复用本项目原创粒子纹理。
- 运行时三件套位于 `app/src/main/assets/scene-packages/`。
  `WastelandV7Visuals.bundle` 的 SHA-256 为
  `4b557e3c516a3e68046d596cf8cb7c4fff7565e0f8537cb6c10face4b6b4f80c`；
  scene manifest 为 `5a094a8ae17198730ef59917bf8e4e0f3f83e7d0a55144cddbaaf5155b30bd94`；
  provenance 为 `5b93af445ef1b053595062a9402a1e31fb4e525b7107d5fd1a589d16017ba6e8`。

## V8 Editor 生成与移动端衍生资产（2026-10-22）

- 通过真实 Editor MCP `generate_fresh` 生成四类带机体武器的美漫机器人、三旋翼
  战斗无人机、标准集装箱、维修服务车和加油装置。生成服务账户条款沿用 V4
  来源说明；不将生成素材声称为 CC0，也未提取任何现有游戏素材。
- 景物根 Run `run-ff218ebc-3699-4be9-9071-7838cfa555e1` 与带武器机器人根
  Run `run-38ebc3f6-7f16-4d06-b5f9-169711776190` 已完成验收。
  原始模型、场景包和哈希分别保留在 `artifacts/2026-10-22-v8/` 的生成记录、
  `accepted-scenery` 和 `accepted-armed` 下。最终包没有 warnings。
- `prepare_generated_robot.py` / `prepare_generated_drone.py` / `prepare_generated_prop.py`
  用实际源坐标简化网格、保留材质与贴图、建立机械动画。原始 USDZ 不改写。
  `finalize_generated_rigs.py` 从实际 GLB 骨架、权重与动画计算枪口轨迹，并拆出
  盾牌前面板；拆分前后的源三角形完整保留。盾牌挂架和背板留在机体上。
- 运行时从 `models/v8` 加载移动端衍生文件。四个机器人均通过 glTF 校验，
  错误及警告为零，行走/开火/死亡 CPU 姿态已离线检查。盾牌改为跟随身体
  RForearm 实际变换的刚性面板，避免 SDK 为第二个蒙皮节点导入重复骨架。
- 每件资产的源文件 SHA-256、衍生文件 SHA-256、三角形与单位在
  `rig-staging/*/*-provenance.json` 和 `runtime-assets/*.provenance.json` 中；
  最终 APK 使用的十二个资源文件哈希见
  [运行资源清单](artifacts/2026-10-22-v8/runtime-assets/app-asset-manifest.json)。
- 原来的 1.8 MB 能源机座包保持原字节。新的 295 MB Editor 包是完整创作场景，
  作为验收源档案保留；游戏按正常尺寸加载独立模型，没有将整场景缩成机座。

## V11 原创爆头反馈（2026-10-23）

- `audio/headshot.wav` 是本项目程序合成的机械脆响和双音确认，未使用第三方录音。
  生成脚本 `tools/generate_headshot_audio.py`；单声道、16 bit、22050 Hz、
  0.28 秒、12392 bytes。
  SHA-256 `881faffdb946cefa907e902332364d51de273bb0c2e06a9e17d2c5a0ef39a162`。
- 青金色头部碎光为 Kotlin ECS 原创资源池几何，复用已有共享网格与材质。

## V12 美漫异能枪械与精英配色（2026-10-23）

- 三枪通过真实 Spatial Editor MCP `generate_fresh` 生成；根 Run
  `run-205ea103-9580-4753-bbdd-23716548b5c2`、模型 Child
  `run-6cd4758a-95b9-47de-b173-495f55e36a64`。源模型六方向、材质隔离和
  场景七方向均已检查，根流程完成且没有 warnings。生成服务条款沿用 V4
  说明，不声称生成素材属于 CC0。
- `tools/prepare_generated_prop.py` 制作独立移动端衍生文件，保留米制单位、
  Y 轴向上、UV 和法线，简化至约 1.2–1.5 万三角形，纹理缩到 1024。
  源文件与衍生文件哈希、误差和边界记录在 `assets-src/v12/*/prop-provenance.json`；
  原始 Editor 资源未改写。移动端离线预览的后向渲染为空，不作为背面验收证据。
- 紫金精英由已有 `HeavyGunnerV9.glb` 仅修改基础色贴图产生，原网格、骨架、
  动画和枪口轨迹保留。`tools/prepare_v12_elite_audio.py` 同时生成五个原创
  音效：电弧、轨道、等离子、等离子撞击和拾取；未使用第三方录音。
  精英与音效的完整哈希见 `assets-src/v12/elite-audio-provenance.json`。
- 枪口星芒、轨道束、彩色能量弹及掉落指引为本项目 Kotlin ECS 原创几何。

| 运行时文件 | 三角形 | SHA-256 |
| --- | ---: | --- |
| models/v12/arc_smg.usdz | 12334 | 699579a576d7163d5b4d33e970a32460d8196f82ebac2049c302d0d3585bd3e1 |
| models/v12/rail_sniper.usdz | 13571 | 1f851b2f021030d8118e4f11121a18b04dc8f3edbb0597c2dc39607eb3268339 |
| models/v12/plasma_cannon.usdz | 14795 | 3232788d3bc1ccd22b56ba984946642579bb44bcc0e3356061ae5850047bda27 |
