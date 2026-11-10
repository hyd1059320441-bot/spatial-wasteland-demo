"""Preserve verified generated candidates and assemble their real review images."""
import hashlib
import json
from pathlib import Path
import shutil

from PIL import Image, ImageDraw, ImageOps

project = Path(__file__).resolve().parents[1]
child = Path("/Users/bytedance/.pico/cli/workflow/runs/run-65bc8f8a-1b9a-4a88-9d42-ed5adb9d99af")
public = child.parent / "run-a4b6b53c-4a9e-4748-a794-2332dd72de1a"
state = json.loads((child / "native-runtime/assets/produce-assets-driver.json").read_text())
target = project / "artifacts/2026-10-22-v8/generated-candidates"
target.mkdir(parents=True, exist_ok=True)
records = []
for operation in state["operations"].values():
    for artifact in operation["production"]["artifacts"].values():
        name = artifact["metadata"]["name"]
        folder = target / name
        folder.mkdir(exist_ok=True)
        files = []
        previews = []
        for file in artifact["files"]:
            source = next(
                (p for p in (child / file["path"], public / file["path"],
                             project / file["path"]) if p.is_file()),
                None,
            )
            if source is None:
                raise FileNotFoundError(file["path"])
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            assert digest == file["digest"], (name, source, "hash mismatch")
            assert source.stat().st_size == file["bytes"]
            local = folder / source.name
            shutil.copy2(source, local)
            files.append({**file, "sourcePath": str(source), "localPath": str(local)})
            if file["role"] == "preview":
                previews.append(local)
        assert len(previews) == 6
        sheet = Image.new("RGB", (1920, 1692), "#171e27")
        draw = ImageDraw.Draw(sheet)
        for index, preview in enumerate(previews):
            tile = ImageOps.contain(Image.open(preview).convert("RGB"), (960, 540))
            x, y = index % 2 * 960, index // 2 * 564
            sheet.paste(tile, (x + (960 - tile.width) // 2, y + 24 + (540 - tile.height) // 2))
            draw.text((x + 12, y + 5), f"{name} / generated_view_{index + 1}", fill="#ffffff")
        sheet_path = folder / "six-views.png"
        sheet.save(sheet_path)
        record = {
            "status": "generated_candidate_not_workflow_accepted",
            "publicRunId": public.name,
            "childRunId": child.name,
            "artifact": artifact,
            "files": files,
            "contactSheet": str(sheet_path),
        }
        (folder / "provenance.json").write_text(json.dumps(record, ensure_ascii=False, indent=2))
        records.append(record)
(target / "index.json").write_text(json.dumps(records, ensure_ascii=False, indent=2))
print(json.dumps([{"name": r["artifact"]["metadata"]["name"], "sheet": r["contactSheet"]}
                  for r in records], ensure_ascii=False, indent=2))
