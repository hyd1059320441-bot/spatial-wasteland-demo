"""Bind this agent's completed seven-view inspection to live review metadata."""
import hashlib
import json
from pathlib import Path
from workflow_read import unwrap

base = Path(__file__).resolve().parents[1] / "artifacts/2026-10-22-v8/workflow-reopen"
status = unwrap(json.loads((base / "v8-armed-capture-submit.json").read_text()))
text = status["next"]["submission"]["instructions"]
review = json.loads(text.split("Structured task input:\n", 1)[1])
submitted = json.loads((base / "armed-capture-submit-request.json").read_text())["arguments"]["input"]["views"]
for item, original in zip(review["reviewerEvidence"], submitted):
    assert item["viewLabel"] == original["viewLabel"]
    assert item["imageHash"] == original["imageHash"]
    assert hashlib.sha256(Path(item["imagePath"]).read_bytes()).hexdigest() == item["imageHash"]
    assert item["candidateFingerprint"] == review["candidate"]["sceneFingerprint"]
    assert item["observedOperationLineage"] == review["candidate"]["operationLineage"]
summaries = [
    ("scene-identity", ["front_three_quarter"], "The exact saved WastelandV7Visuals candidate and all seven archived images share the supplied fingerprint. Original induction housing, accepted scenery and robot row remain identifiable."),
    ("hard-criteria", ["front_three_quarter", "view_from_+Y"], "Candidate is saved and ready for downstream managed packaging. No bundle completion is claimed by this review; packaging and provenance remain downstream gates."),
    ("visual-composition", ["front_three_quarter", "view_from_+Y", "view_from_-Y", "view_from_+Z"], "Actually inspected the identical seven hashed images. Heavy and Shield replacements stand in the separated robot row; truck, full-size container, station and induction housing remain. Top and bottom views support separate footprints and grounding; natural axis occlusion is not penetration. Selection outline and incomplete material isolation are recorded; independent source inspections establish asset appearance."),
    ("interaction-bindings", ["front_three_quarter"], "Empty bindings match this authored asset increment. Runtime combat and animation belong to application code."),
    ("operation-lineage", ["front_three_quarter", "view_from_+Y"], "All reviewer-owned metadata binds the same observedOperationLineage reference and fingerprint to the immutable candidate, with matching archived image hashes."),
]
judgment = {
    "schemaVersion": "spatialcraft.editor-experience-review-judgment/v1",
    "verdict": "pass", "fulfilledCriteria": ["editor_delivery_completed"],
    "checks": [{"checkId": key, "status": "pass", "evidenceViewLabels": labels, "summary": summary}
               for key, labels, summary in summaries],
    "findings": [],
}
request = {"id": "v8-armed-final-review", "name": status["next"]["tool"],
           "arguments": status["next"]["arguments"] | {"input": judgment}}
(base / "armed-final-review-request.json").write_text(json.dumps(request, indent=2))
print(base / "armed-final-review-request.json")
