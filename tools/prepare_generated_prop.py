"""Prepare a mobile USDZ derivative without editing immutable Editor sources."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from zipfile import ZipFile

from PIL import Image
from pxr import Sdf, Usd, UsdGeom

ROOT = Path(__file__).resolve().parents[1]


def prepare(source, output, name, triangles, edge):
    output.mkdir(parents=True, exist_ok=True)
    work = output / "mobile-source"
    work.mkdir(exist_ok=True)
    with ZipFile(source) as archive:
        files = archive.namelist()
        archive.extractall(work)
    stage = Usd.Stage.Open(str(work / files[0]))
    assert stage and UsdGeom.GetStageUpAxis(stage) == "Y"
    assert UsdGeom.GetStageMetersPerUnit(stage) == 1
    meshes = []
    for prim in stage.Traverse():
        if not prim.IsA(UsdGeom.Mesh):
            continue
        mesh = UsdGeom.Mesh(prim)
        counts = list(mesh.GetFaceVertexCountsAttr().Get())
        assert all(count == 3 for count in counts)
        points, normals = list(mesh.GetPointsAttr().Get()), list(mesh.GetNormalsAttr().Get())
        uv = UsdGeom.PrimvarsAPI(prim).GetPrimvar("st")
        assert mesh.GetNormalsInterpolation() == "vertex"
        assert uv.GetInterpolation() == "vertex" and not uv.IsIndexed()
        coords = list(uv.Get())
        request = output / "simplify-input.json"
        result_file = output / "simplify-result.json"
        request.write_text(json.dumps({
            "positions": [list(p) for p in points],
            "attributes": [list(n) + list(t) for n, t in zip(normals, coords)],
            "indices": list(mesh.GetFaceVertexIndicesAttr().Get()),
            "triangles": triangles, "targetError": .003,
            "attributeWeights": [.25, .25, .25, 1, 1], "options": ["PreserveFolds"]
        }))
        subprocess.run(["node", str(ROOT / "tools/simplify_mesh.mjs"),
            str(request), str(result_file)], check=True)
        result = json.loads(result_file.read_text())
        indices = result["indices"]
        assert indices and len(indices) % 3 == 0
        used = sorted(set(indices))
        remap = {old: new for new, old in enumerate(used)}
        mesh.GetPointsAttr().Set([points[i] for i in used])
        mesh.GetNormalsAttr().Set([normals[i] for i in used])
        for var in UsdGeom.PrimvarsAPI(prim).GetPrimvars():
            data = var.Get()
            if data and var.GetInterpolation() == "vertex":
                assert not var.IsIndexed() and len(data) == len(points)
                var.Set([data[i] for i in used])
        mesh.GetFaceVertexIndicesAttr().Set([remap[i] for i in indices])
        mesh.GetFaceVertexCountsAttr().Set([3] * (len(indices) // 3))
        mesh.GetExtentAttr().Clear()
        meshes.append({"path": str(prim.GetPath()), "sourceTriangles": len(counts),
            "triangles": len(indices) // 3, "meshoptError": result["error"]})
    stage.GetRootLayer().Save()
    textures = []
    for relative in files:
        file = work / relative
        if file.suffix.lower() not in (".png", ".jpg", ".jpeg"):
            continue
        with Image.open(file) as image:
            original_size = image.size
            image.thumbnail((edge, edge), Image.Resampling.LANCZOS)
            if file.suffix.lower() in (".jpg", ".jpeg"):
                image.convert("RGB").save(file, quality=92)
            else:
                image.save(file, optimize=True)
            textures.append({"path": relative, "sourceSize": original_size, "size": image.size})
    target = output / (name + ".usdz")
    with Sdf.ZipFileWriter.CreateNew(str(target)) as writer:
        for relative in files:
            writer.AddFile(str(work / relative), relative)
    composed = Usd.Stage.Open(str(target))
    bounds = UsdGeom.BBoxCache(Usd.TimeCode.Default(), ["default", "render", "proxy"],
        True).ComputeWorldBound(composed.GetPseudoRoot()).ComputeAlignedRange()
    manifest = {
        "name": name, "source": str(source.resolve()),
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "derivative": str(target.resolve()), "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "bytes": target.stat().st_size, "meshes": meshes, "textures": textures,
        "metersPerUnit": 1, "upAxis": "Y",
        "bounds": {"min": list(bounds.GetMin()), "max": list(bounds.GetMax())},
        "transform": "Preserve authored coordinates, pivot and vertex attributes; simplify indices and downsample textures.",
        "status": "offline_derivative_pending_visual_and_runtime_review"
    }
    (output / "prop-provenance.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--triangles", type=int, default=12000)
    parser.add_argument("--edge", type=int, default=1024)
    args = parser.parse_args()
    prepare(args.source, args.output, args.name, args.triangles, args.edge)
