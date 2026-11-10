"""Prepare mobile USDZ copies. Retain original source, UVs, normals, transforms and provenance."""
import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

from PIL import Image
from pxr import Usd, UsdGeom, Sdf

ROOT = Path(__file__).resolve().parents[1]
RUN = Path.home() / ".pico/cli/workflow/runs/run-5e6fc205-4123-4a31-81c3-02c86b85c77e"
SOURCE = ROOT / "assets-src/v5"
OUT = ROOT / "app/src/main/assets/models"
ASSETS = [
    ("SalvageKioskV5", "asset_SalvageKioskV5-54d3a5faeb6f", 15000, 1024),
    ("PumpStationV5", "asset_PumpStationV5-77785d0c8131", 10000, 512),
    ("WaterCommsTowerV5", "asset_WaterCommsTowerV5-f67b1e469ae3", 12000, 1024),
    ("SecurityDroneV5", "asset_SecurityDroneV5-cbaf14aa08c7", 8000, 512),
    ("GuardShieldV5", "asset_GuardShieldV5-a45c5c9b54fa", 4000, 512),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(source, target, budget, edge):
    work = SOURCE / (target.stem + "-mobile")
    work.mkdir(parents=True, exist_ok=True)
    with ZipFile(source) as archive:
        names = archive.namelist()
        archive.extractall(work)
    stage = Usd.Stage.Open(str(work / names[0]))
    assert stage and UsdGeom.GetStageUpAxis(stage) == "Y"
    counts = []
    for prim in stage.Traverse():
        if not prim.IsA(UsdGeom.Mesh):
            continue
        mesh = UsdGeom.Mesh(prim)
        face_counts = list(mesh.GetFaceVertexCountsAttr().Get())
        assert all(n == 3 for n in face_counts), "Only authored triangles are supported"
        points = list(mesh.GetPointsAttr().Get())
        normals = list(mesh.GetNormalsAttr().Get())
        uv = UsdGeom.PrimvarsAPI(prim).GetPrimvar("st")
        assert mesh.GetNormalsInterpolation() == "vertex" and uv.GetInterpolation() == "vertex"
        assert not uv.IsIndexed()
        texcoords = list(uv.Get())
        request = dict(positions=[list(v) for v in points],
            attributes=[list(n) + list(t) for n, t in zip(normals, texcoords)],
            indices=list(mesh.GetFaceVertexIndicesAttr().Get()), triangles=budget)
        input_path = work / "simplify-input.json"
        result_path = work / "simplify-result.json"
        input_path.write_text(json.dumps(request))
        subprocess.run(["node", str(ROOT / "tools/simplify_mesh.mjs"),
            str(input_path), str(result_path)], check=True)
        result = json.loads(result_path.read_text())
        indices = result["indices"]
        assert len(indices) % 3 == 0 and len(indices) > 0
        assert all(0 <= i < len(points) for i in indices)
        # Compact unused vertices with the exact original attributes; no UV or normal recomputation.
        used = sorted(set(indices))
        remap = {old: new for new, old in enumerate(used)}
        mesh.GetPointsAttr().Set([points[i] for i in used])
        mesh.GetNormalsAttr().Set([normals[i] for i in used])
        for var in UsdGeom.PrimvarsAPI(prim).GetPrimvars():
            data = var.Get()
            if data and var.GetInterpolation() == "vertex":
                assert len(data) == len(points) and not var.IsIndexed()
                var.Set([data[i] for i in used])
        mesh.GetFaceVertexIndicesAttr().Set([remap[i] for i in indices])
        mesh.GetFaceVertexCountsAttr().Set([3] * (len(indices) // 3))
        mesh.GetExtentAttr().Clear()
        counts.append(dict(mesh=str(prim.GetPath()), sourceTriangles=len(face_counts),
            triangles=len(indices) // 3, normalizedAppearanceError=result["error"]))
    stage.GetRootLayer().Save()
    textures = []
    for name in names:
        path = work / name
        if path.suffix.lower() in (".png", ".jpg", ".jpeg"):
            with Image.open(path) as image:
                original = image.size
                image.thumbnail((edge, edge), Image.Resampling.LANCZOS)
                size = image.size
                if path.suffix.lower() in (".jpg", ".jpeg"):
                    image.convert("RGB").save(path, quality=92)
                else:
                    image.save(path, optimize=True)
            textures.append(dict(path=name, sourceSize=original, size=size))
    target.parent.mkdir(parents=True, exist_ok=True)
    with Sdf.ZipFileWriter.CreateNew(str(target)) as writer:
        for name in names:
            writer.AddFile(str(work / name), name)
    composed = Usd.Stage.Open(str(target))
    assert composed and UsdGeom.GetStageMetersPerUnit(composed) == UsdGeom.GetStageMetersPerUnit(stage)
    bounds = UsdGeom.BBoxCache(Usd.TimeCode.Default(), [UsdGeom.Tokens.default_]).ComputeWorldBound(
        composed.GetPseudoRoot()).ComputeAlignedRange()
    return dict(sourceSha256=sha(source), runtimeSha256=sha(target), meshes=counts,
        textures=textures, metersPerUnit=UsdGeom.GetStageMetersPerUnit(composed),
        upAxis=UsdGeom.GetStageUpAxis(composed),
        bounds={"min": list(bounds.GetMin()), "max": list(bounds.GetMax())})


def main():
    SOURCE.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, folder, budget, edge in ASSETS:
        source = SOURCE / (name + ".usdz")
        shutil.copyfile(RUN / "artifacts/assets" / folder / "primary.usdz", source)
        result = prepare(source, OUT / "v5" / (name + ".usdz"), budget, edge)
        result.update(name=name, sourceRun="run-d0b0cb70-7d6f-4747-b470-ef6576b616f3",
            transform="Attribute-aware index simplification with original vertex attributes and texture downsampling.")
        manifest.append(result)
        print(name, result["meshes"], flush=True)
    # Preserve a source backup before changing heavily repeated existing environment copies.
    for directory, name, budget, edge in [
        ("v4", "CargoCoverV4", 8000, 512),
        ("v4", "RepairBayV4", 18000, 1024),
        ("v4", "PowerRelayV4", 10000, 512),
        ("v3", "SandstoneRockComicV3", 6000, 512),
    ]:
        target = OUT / directory / (name + ".usdz")
        source = SOURCE / (name + "-before.usdz")
        if not source.exists():
            shutil.copyfile(target, source)
        result = prepare(source, target, budget, edge)
        result.update(name=name, originalDirectory=directory,
            transform="Existing authored asset simplified from retained pre-change source.")
        manifest.append(result)
        print(name, result["meshes"], flush=True)
    (SOURCE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
