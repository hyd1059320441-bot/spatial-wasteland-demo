"""Prepare managed revision requests from the facade's current public handoff."""
import json
import sys
from pathlib import Path
from workflow_read import unwrap

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/2026-10-23-v12/workflow"
tag, mode = sys.argv[1:3]
name = tag + "-" + mode
if mode == "start":
    request = json.loads((OUT / "v12-code-start-request.json").read_text())
    request["id"] = name
    request["arguments"]["input"]["lineage"]["parentRunId"] = sys.argv[3]
    request["arguments"]["input"]["goal"] += (
        "\nFocused verification revision: prevent all weapon fire when the full muzzle "
        "volume is inside cover before spending ammunition; native checks of actual "
        "new gun hits, solid-world blocking, plasma blast occlusion, collectible Y "
        "and owned I switching; bounded five-gun combat and retained exit lifecycle."
    )
elif mode in ("spec", "build"):
    previous = tag + ("-start" if mode == "spec" else "-spec") + ".json"
    observation = unwrap(json.loads((OUT / previous).read_text()))
    assert observation["current"]["status"] == "waiting"
    original = "v12-code-spec-request.json" if mode == "spec" else "v12-code-authored-request.json"
    payload = json.loads((OUT / original).read_text())["arguments"]["input"]
    if mode == "spec":
        payload["sceneId"] += "_" + tag.replace("-", "_")
        payload["objective"] += "；本次补齐枪口完整体积预判与五枪原生碰撞回归。"
    else:
        payload["changedFiles"] = [
            "app/src/main/java/com/pico/demo/wasteland/game/WastelandScene.kt"
        ]
        payload["changedFiles"] += sys.argv[3:]
        payload["notes"] = [
            "Focused muzzle clearance correction and native arsenal regression only.",
            "SDK, world layout, Editor-authored assets and stable actor rigs retained.",
        ]
    request = {"id": name, "name": observation["next"]["tool"],
               "arguments": {**observation["next"]["arguments"], "input": payload}}
elif mode == "wait":
    observation = unwrap(json.loads((OUT / sys.argv[3]).read_text()))
    request = {"id": name, "name": observation["next"]["tool"],
               "arguments": observation["next"]["arguments"]}
else:
    raise ValueError(mode)
target = OUT / (name + "-request.json")
target.write_text(json.dumps(request, ensure_ascii=False, indent=2))
print(target)
