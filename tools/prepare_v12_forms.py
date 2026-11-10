"""Use the current public handoff arguments, never reconstructed control IDs."""
import json
import sys
from pathlib import Path
from workflow_read import unwrap

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/2026-10-23-v12/workflow"


def request(observation, name, payload):
    response = unwrap(json.loads((OUT / observation).read_text()))
    next_step = response["next"]
    assert response["current"]["status"] == "waiting"
    result = {"id": name, "name": next_step["tool"],
              "arguments": {**next_step["arguments"], "input": payload}}
    target = OUT / (name + "-request.json")
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(target)


if sys.argv[1] == "review":
    batch = json.loads((OUT / "asset-review-batch.json").read_text())
    summaries = {
        "arc_smg": "All six views inspected. Complete connected receiver, pistol grip, stock, cyan coil barrel and foregrip; vivid purple/cyan paint with dark edges. No extra object or broken silhouette.",
        "rail_sniper": "All six views inspected. Complete long rail rifle with connected stock, grip, magazine and scope. Purple/cobalt surfaces and gold accents, slender distinct silhouette. No broken geometry or extra object.",
        "plasma_cannon": "All six views inspected. Complete connected bulbous magenta cannon with lime muzzle, energy chamber, grip and rear stock. Distinct exaggerated silhouette; no detached part or extra scene object.",
    }
    request("v12-editor-wait2.json", "v12-assets-reviewed", {"reviews": [
        {"requestId": item["request"]["requestId"], "artifactDigest": item["artifactDigest"],
         "verdict": "pass", "evidenceIds": [e["evidenceId"] for e in item["evidence"]],
         "fulfilledCriteria": item["request"]["acceptanceIds"],
         "summary": summaries[item["request"]["name"]]} for item in batch
    ]})
elif sys.argv[1] == "candidate":
    workspace = unwrap(json.loads((OUT / "v12-workspace-final.json").read_text()))
    captures = []
    for name in ["arc", "rail", "plasma"]:
        response = json.loads((OUT / ("v12-capture-" + name + "2.json")).read_text())
        text = "\n".join(item["text"] for item in response["content"] if item["type"] == "text")
        proof = json.loads(text.rsplit("\n", 1)[-1])
        assert proof["receipt"]["status"] == "succeeded"
        captures.append(proof["capture"])
    request("v12-editor-candidate.json", "v12-editor-candidate2", {
        "candidate": {
            "sceneFingerprint": workspace["sceneFingerprint"],
            "inventory": {
                "scenePath": workspace["scene"]["scenePath"],
                "assetBindings": [{"assetId": "asset:" + name, "entityPaths": ["/Root/" + name]}
                                  for name in ["arc_smg", "rail_sniper", "plasma_cannon"]],
                "enhancementIds": [],
            },
            "operationIds": [op["id"] for op in workspace["operations"] if op["status"] == "succeeded"],
            "evidence": captures,
        },
        "interactionContract": {"schemaVersion": "spatialcraft.editor-interaction-contract/v1",
                                "owner": "application-code", "bindings": [],
                                "digest": "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"},
    })
elif sys.argv[1] == "codegen":
    result = {"id": "v12-code-start", "name": "start_3d_generation_workflow",
              "arguments": {"input": {
                  "schemaVersion": "spatialcraft.spatial-3d-generation-input/v1",
                  "mode": "revision",
                  "lineage": {"parentRunId": "run-2fe3d970-c75c-4b69-a269-568202414a99"},
                  "workspace": {"root": str(ROOT), "projectPath": str(ROOT),
                                "outputPath": "app/src/main/java/com/pico/demo/wasteland/game/WastelandScene.kt"},
                  "backendPreference": "codegen",
                  "goal": (ROOT / "docs/product/V12-elite-arsenal.md").read_text(),
                  "acceptanceCriteria": [
                      {"id": "scene_generated", "required": True,
                       "description": "Continuous solid-world enemy bullets, purple/gold elites, persistent safe Y drops, owned I/Grip arsenal with three genuine generated gun models and distinct real ballistics/FX/audio, spaced small reinforcements. Preserve 100 total, 8 maximum, stable robot animation and V9 layout."},
                      {"id": "project_builds", "required": True,
                       "description": "Controlled fixed-wrapper JDK21 build using existing SDK6.2.6."}],
                  "delivery": {"formats": ["spatial-sdk-kotlin-scene"], "previews": "none",
                               "packageName": "com.pico.demo.wasteland"}
              }}}
    target = OUT / "v12-code-start-request.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(target)
elif sys.argv[1] == "build":
    main = "app/src/main/java/com/pico/demo/wasteland/"
    test = "app/src/test/java/com/pico/demo/wasteland/game/"
    files = [main + "game/" + name + ".kt" for name in [
        "GameRules", "WeaponView", "LootView", "ArsenalFx", "SceneKit", "WastelandScene",
        "GeneratedRobotAsset", "GameAudio", "AnimatedActor"]]
    files += [main + "platform/LaunchActivity.kt", main + "ui/hud/components/GameHud.kt"]
    files += [test + name + ".kt" for name in ["GameRulesTest", "ExperienceTest",
        "CombatPolishTest", "ExpansionTest", "RunTalentsTest", "CombatRewardsTest",
        "CombatRhythmTest", "ArsenalTest"]]
    request("v12-code-spec.json", "v12-code-authored", {
        "schemaVersion": "spatialcraft.spatial-codegen-change-set/v1",
        "sceneEntry": main + "game/WastelandScene.kt", "changedFiles": files,
        "notes": ["Existing FullSpace root, SDK6.2.6, independent robot skeletons and source layout retained.",
                  "Three Editor-generated weapons integrated from isolated mobile derivatives; new elite changes only base-color textures.",
                  "Projectile logic uses authoritative positions and radius/world/body precedence; Debug checks cover native solid cover and thin-wall boundary.",
                  "Original new gun audio generated locally; actual tests/build/run results are separate evidence."]})
elif sys.argv[1] == "spec":
    request("v12-code-start.json", "v12-code-spec", {
        "schemaVersion": "spatialcraft.scene-generation-spec/v2",
        "sceneId": "wasteland_v12_elite_arsenal",
        "name": "精英战利品与美漫异能枪械",
        "objective": "敌弹遇掩体即停、来向可辨，紫金精英必掉武器，靠近Y拾取、I或Grip切换，刷怪改为有间隔的小批增援。",
        "coordinateSystem": {"handedness": "right", "upAxis": "Y", "unitsPerMeter": 1},
        "subjects": [
            {"id": "elite", "kind": "character", "authoringStrategy": "group",
             "description": "保留完整动画重装机器人，紫金装甲与独立精英血条；固定登场机会，击破掉落各类新枪。"},
            {"id": "arsenal", "kind": "prop", "authoringStrategy": "group",
             "description": "使用本轮已独立生成并验收的青紫电弧枪、紫金轨道枪、洋红荧绿等离子炮，保留默认步枪霰弹；握持比例、弹道、弹匣、射速、伤害、声音和特效各自独立。"},
            {"id": "loot", "kind": "prop", "authoringStrategy": "group",
             "description": "精英死亡点可见武器、短柱光与拾取指引，近距离同楼层无掩体Y拾取；库存只切已持有武器，重启清空新枪，死亡重试保留。"},
            {"id": "projectiles", "kind": "prop", "authoringStrategy": "group",
             "description": "敌方可见红橙弹体与红拖尾，真实炮口预警；弹体逻辑位置、半径连续扫掠、世界先于身体且受击前复核遮挡；玩家能量弹与弧线爆弹同样遵守墙体遮挡。"},
            {"id": "retained_arena", "kind": "environment", "authoringStrategy": "group",
             "description": "保留成人1.65米眼位、原始工业尺寸、V9松散布局、稳定独立动画，八活敌上限与100累计挑战；开场三敌，间隔5至7秒增援两三敌，清空后喘息。"}],
        "composition": {"description": "现有FullSpace矿区内的局部战斗系统扩展，不改写既有环境资产。",
                        "relationships": ["世界碰撞优先于所有伤害，范围爆炸不能穿墙。",
                                          "按Y拾取优先于升级入口，同一装备不重复加入。",
                                          "三种新枪实际改变射击速度与轨迹，短促美漫画面反馈避开近眼。",
                                          "旧枪保持默认拥有，精英按序掉落三种新枪；间隔增援不清空存活怪。"]}})
