"""Bind the existing eight-asset specification to the current public handoff."""
import argparse
import json
from pathlib import Path
from workflow_read import unwrap

root = Path(__file__).resolve().parents[1]
folder = root / "artifacts/2026-10-22-v8/workflow"
parser = argparse.ArgumentParser()
parser.add_argument("--observation", default="v8-editor-remaining-start.json")
parser.add_argument("--id", default="v8-editor-remaining-spec")
parser.add_argument("--out", default="remaining-spec-request.json")
args = parser.parse_args()
source = json.loads((folder / "scene-spec-request.json").read_text())
live = unwrap(json.loads((folder / args.observation).read_text()))
assert live["current"]["status"] == "waiting"
assert live["next"]["submission"]["kind"] == "scene-spec"
request = {
    "id": args.id,
    "name": live["next"]["tool"],
    "arguments": {**live["next"]["arguments"], "input": source["arguments"]["input"]},
}
# Runtime effects and actual light entities are handled independently by app code.
request["arguments"]["input"].pop("effects", None)
request["arguments"]["input"].pop("lighting", None)
(folder / args.out).write_text(json.dumps(request, ensure_ascii=False, indent=2))
print(json.dumps({"request": str(folder / args.out),
                  "runId": live["runId"], "next": live["next"]["arguments"]}))
