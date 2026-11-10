"""Prepare managed capture requests or a submission from genuine archived receipts."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw
from workflow_read import unwrap

BASE = Path(__file__).resolve().parents[1] / "artifacts/2026-10-22-v8/workflow-reopen"
FP = "17a6014b65a618b026fb3b5b3f72d27f54380cbc6bf4e1d239a06d4d4a64dbde"
VIEWS = [
    ("plusX", "view_from_+X", 90, 0), ("minusX", "view_from_-X", -90, 0),
    ("plusY", "view_from_+Y", 0, -90), ("minusY", "view_from_-Y", 0, 90),
    ("plusZ", "view_from_+Z", 0, 0), ("minusZ", "view_from_-Z", 180, 0),
    ("threeQuarter", "front_three_quarter", 30, -22),
]
parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["requests", "submission"])
args = parser.parse_args()
if args.mode == "requests":
    for name, label, yaw, pitch in VIEWS:
        request = {
            "id": f"v8-armed-final-{name}", "name": "execute_editor_operation",
            "arguments": {
                "operation_id": f"v8-armed-final-{name}", "expected_scene_fingerprint": FP,
                "tool": "capture_3d_scene", "arguments": {
                    "frame": "none", "camera_mode": "look_at",
                    "camera_target": [2.0069503784, 1.3906536102, -0.8096532822],
                    "camera_target_radius": 10.22, "camera_distance": 28,
                    "camera_yaw_deg": yaw, "camera_pitch_deg": pitch,
                    "container_fit_mode": "none", "capture_mode": "offscreen", "delivery": "auto",
                },
            },
        }
        p = BASE / f"armed-final-{name}-request.json"
        p.write_text(json.dumps(request, indent=2))
        print(json.dumps({"file": str(p)}))
else:
    views = []
    sheet = Image.new("RGB", (1800, 1620), "#1b2430")
    draw = ImageDraw.Draw(sheet)
    for i, (name, label, _, _) in enumerate(VIEWS):
        envelope = json.loads((BASE / f"v8-armed-final-{name}.json").read_text())
        result = json.loads(envelope["content"][0]["text"])
        capture = json.loads(envelope["content"][1]["text"])["capture"]
        assert capture["sceneFingerprint"] == FP
        p = Path(capture["imagePath"])
        assert hashlib.sha256(p.read_bytes()).hexdigest() == capture["imageHash"]
        views.append({k: capture[k] for k in ["capturedAt", "imagePath", "imageHash"]} |
                     {"viewLabel": label, "camera": result["camera"]})
        frame = Image.open(p).convert("RGB")
        frame.thumbnail((900, 380))
        x, y = (i % 2) * 900, (i // 2) * 405
        sheet.paste(frame, (x + (900-frame.width)//2, y+25))
        draw.text((x+12, y+6), label, fill="white")
    sheet.save(BASE / "armed-final-seven.png")
    status = unwrap(json.loads((BASE / "v8-armed-candidate-diagnostic.json").read_text()))
    request = {"id": "v8-armed-capture-submit", "name": status["next"]["tool"],
               "arguments": status["next"]["arguments"] | {"input": {"views": views}}}
    (BASE / "armed-capture-submit-request.json").write_text(json.dumps(request, indent=2))
    print(BASE / "armed-final-seven.png")
