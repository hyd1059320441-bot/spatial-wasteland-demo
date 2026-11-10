"""Prepare accepted Editor models for mobile reuse; preserve geometry and USD metadata."""
import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RUN = Path.home() / ".pico/cli/workflow/runs/run-777163dc-f0e9-458e-9968-71615bf994e7"
SOURCE = ROOT / "assets-src/v4"
OUT = ROOT / "app/src/main/assets/models/v4"
ASSETS = {
    "RepairBayV4": ("asset_RepairBayV4-0697bf7a41f3", 1024),
    "CargoCoverV4": ("asset_CargoCoverV4-187ab8c35030", 512),
    "PowerRelayV4": ("asset_PowerRelayV4-4086fbc41d29", 1024),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    SOURCE.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, (folder, edge) in ASSETS.items():
        original = RUN / "artifacts/assets" / folder / "primary.usdz"
        source = SOURCE / (name + ".usdz")
        shutil.copyfile(original, source)
        work = SOURCE / (name + "-mobile")
        work.mkdir(exist_ok=True)
        with ZipFile(source) as archive:
            names = archive.namelist()
            archive.extractall(work)
        textures = []
        for relative in names:
            if Path(relative).suffix.lower() not in (".png", ".jpg", ".jpeg"):
                continue
            texture = work / relative
            before = sha(texture)
            with Image.open(texture) as image:
                old_size = image.size
                image.thumbnail((edge, edge), Image.Resampling.LANCZOS)
                new_size = image.size
                if texture.suffix.lower() in (".jpg", ".jpeg"):
                    image.convert("RGB").save(texture, quality=92)
                else:
                    image.save(texture, optimize=True)
            textures.append(dict(file=relative, sourceSha256=before, sha256=sha(texture),
                sourceSize=old_size, size=new_size))
        # OpenUSD writes uncompressed entries with the alignment required by USDZ.
        package_code = """
import sys
from pxr import Usd, UsdGeom, Sdf
from pathlib import Path
source, output, *names = sys.argv[1:]
with Sdf.ZipFileWriter.CreateNew(output) as writer:
    for name in names:
        writer.AddFile(str(Path(source) / name), name)
stage = Usd.Stage.Open(output)
assert stage and UsdGeom.GetStageMetersPerUnit(stage) == 1
assert UsdGeom.GetStageUpAxis(stage) == 'Y'
triangles = sum(sum(n - 2 for n in UsdGeom.Mesh(p).GetFaceVertexCountsAttr().Get())
    for p in stage.Traverse() if p.IsA(UsdGeom.Mesh))
print(triangles)
"""
        target = OUT / (name + ".usdz")
        triangles = int(subprocess.check_output(
            [str(ROOT / ".tools/asset-inspector/bin/python"), "-c", package_code,
             str(work), str(target)] + names, text=True))
        manifest.append(dict(name=name, sourceRun="run-eb7e5cc8-7cff-48ef-9512-5d6136f76c3e",
            sourceSha256=sha(source), runtimeSha256=sha(target), triangles=triangles,
            units="meters", upAxis="Y", textures=textures,
            transform="Only texture downsampling; geometry, topology, pivot and USD metadata retained."))
        print(name, target.stat().st_size, "bytes", triangles, "triangles", flush=True)
    (SOURCE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
