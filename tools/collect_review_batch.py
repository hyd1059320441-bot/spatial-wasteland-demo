"""Verify/copy a live public review batch and make lossless preview contact sheets."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps
from workflow_read import unwrap

parser = argparse.ArgumentParser()
parser.add_argument("observation", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
live = unwrap(json.loads(args.observation.read_text()))
text = live["next"]["submission"]["instructions"]
batch, _ = json.JSONDecoder().raw_decode(text.split("Review batch:\n", 1)[1])
args.output.mkdir(parents=True, exist_ok=True)
for item in batch:
    target = args.output / item["metadata"]["name"]
    target.mkdir(parents=True, exist_ok=True)
    first = Path(item["evidence"][0]["imagePath"])
    source = first.parent
    for file in source.iterdir():
        if file.suffix in (".json", ".usdz", ".png"):
            shutil.copy2(file, target / file.name)
    sheet = Image.new("RGB", (1920, 1692), "#171e27")
    draw = ImageDraw.Draw(sheet)
    for index, evidence in enumerate(item["evidence"]):
        path = Path(evidence["imagePath"])
        assert hashlib.sha256(path.read_bytes()).hexdigest() == evidence["file"]["digest"]
        assert path.stat().st_size == evidence["file"]["bytes"]
        image = ImageOps.contain(Image.open(path).convert("RGB"), (960, 540))
        x, y = index % 2 * 960, index // 2 * 564
        sheet.paste(image, (x + (960 - image.width) // 2, y + 24))
        draw.text((x + 12, y + 5), item["metadata"]["name"] + " / " +
            evidence["viewId"], fill="white")
    sheet.save(target / "six-views.png")
    (target / "review-context.json").write_text(json.dumps({
        "publicRunId": live["runId"], "review": item,
        "status": "candidate_review_pending"}, indent=2))
    print(target / "six-views.png")
(args.output / "review-batch.json").write_text(json.dumps(batch, indent=2))
