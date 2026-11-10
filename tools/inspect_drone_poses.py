"""Render-ready poses evaluated from the staged drone GLB's actual buffers."""
import argparse
import json
import struct
from pathlib import Path

import numpy as np
from pxr import Sdf, Usd, UsdGeom, UsdShade, Vt

from inspect_rig_poses import matrix


def inspect(path, output):
    data = path.read_bytes()
    size = struct.unpack_from("<I", data, 12)[0]
    doc, binary = json.loads(data[20:20+size]), data[28+size:]
    output.mkdir(parents=True, exist_ok=True)

    def read(index):
        accessor = doc["accessors"][index]
        view = doc["bufferViews"][accessor["bufferView"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[accessor["type"]]
        dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2"}[accessor["componentType"]]
        return np.frombuffer(binary, dtype=dtype, count=accessor["count"]*width,
            offset=view.get("byteOffset", 0)+accessor.get("byteOffset", 0)).reshape(-1, width)

    image = doc["images"][0]
    view = doc["bufferViews"][image["bufferView"]]
    texture_path = output / "texture.jpg"
    texture_path.write_bytes(binary[view.get("byteOffset", 0):view.get("byteOffset", 0)+view["byteLength"]])
    results = []
    for name, clip, time in (("Bind", None, 0), ("Rotor45", "Idle", .025),
            ("Rotor90", "Idle", .05), ("Death", "Death", .9)):
        rotations = {i: np.asarray(node.get("rotation", [0, 0, 0, 1]), dtype=float)
            for i, node in enumerate(doc["nodes"])}
        if clip:
            animation = next(a for a in doc["animations"] if a["name"] == clip)
            for channel in animation["channels"]:
                sampler = animation["samplers"][channel["sampler"]]
                times, values = read(sampler["input"]).ravel(), read(sampler["output"])
                k = min(max(np.searchsorted(times, time)-1, 0), len(times)-2)
                ratio = (time-times[k])/(times[k+1]-times[k])
                a, b = values[k], values[k+1].copy()
                if np.dot(a, b) < 0:
                    b *= -1
                q = a*(1-ratio)+b*ratio
                rotations[channel["target"]["node"]] = q/np.linalg.norm(q)
        points_all, normals_all, uv_all, faces_all = [], [], [], []
        offset = 0
        for i, node in enumerate(doc["nodes"]):
            if "mesh" not in node:
                continue
            primitive = doc["meshes"][node["mesh"]]["primitives"][0]
            attrs = primitive["attributes"]
            points, normals = read(attrs["POSITION"]), read(attrs["NORMAL"])
            transform = matrix(rotations[i], np.asarray(node.get("translation", [0, 0, 0])))
            points = (np.column_stack([points, np.ones(len(points))]) @ transform.T)[:, :3]
            normals = normals @ transform[:3, :3].T
            uv = read(attrs["TEXCOORD_0"]).copy()
            uv[:, 1] = 1-uv[:, 1]
            faces = read(primitive["indices"]).ravel().reshape(-1, 3)+offset
            points_all.append(points)
            normals_all.append(normals)
            uv_all.append(uv)
            faces_all.append(faces)
            offset += len(points)
        points = np.concatenate(points_all).astype(np.float32)
        normals, uv = np.concatenate(normals_all).astype(np.float32), np.concatenate(uv_all)
        faces = np.concatenate(faces_all)
        assert np.isfinite(points).all()
        results.append(dict(pose=name, clip=clip, time=time,
            min=points.min(axis=0).tolist(), max=points.max(axis=0).tolist(),
            triangles=len(faces), rotorCount=3))
        layer = output / (name+".usda")
        stage = Usd.Stage.CreateNew(str(layer))
        UsdGeom.SetStageUpAxis(stage, "Y")
        UsdGeom.SetStageMetersPerUnit(stage, 1)
        root = UsdGeom.Xform.Define(stage, "/Root")
        stage.SetDefaultPrim(root.GetPrim())
        mesh = UsdGeom.Mesh.Define(stage, "/Root/mesh")
        mesh.CreatePointsAttr(Vt.Vec3fArray.FromNumpy(points))
        mesh.CreateNormalsAttr(Vt.Vec3fArray.FromNumpy(normals))
        mesh.SetNormalsInterpolation("vertex")
        mesh.CreateFaceVertexCountsAttr([3]*len(faces))
        mesh.CreateFaceVertexIndicesAttr(faces.ravel().tolist())
        mesh.CreateSubdivisionSchemeAttr("none")
        mesh.CreateDoubleSidedAttr(True)
        UsdGeom.PrimvarsAPI(mesh).CreatePrimvar("st", Sdf.ValueTypeNames.TexCoord2fArray,
            "vertex").Set(Vt.Vec2fArray.FromNumpy(uv.astype(np.float32)))
        material = UsdShade.Material.Define(stage, "/Root/Material")
        surface = UsdShade.Shader.Define(stage, "/Root/Material/Surface")
        surface.CreateIdAttr("UsdPreviewSurface")
        surface.CreateInput("roughness", Sdf.ValueTypeNames.Float).Set(.75)
        material.CreateSurfaceOutput().ConnectToSource(surface.ConnectableAPI(), "surface")
        reader = UsdShade.Shader.Define(stage, "/Root/Material/UV")
        reader.CreateIdAttr("UsdPrimvarReader_float2")
        reader.CreateInput("varname", Sdf.ValueTypeNames.Token).Set("st")
        reader.CreateOutput("result", Sdf.ValueTypeNames.Float2)
        texture = UsdShade.Shader.Define(stage, "/Root/Material/Texture")
        texture.CreateIdAttr("UsdUVTexture")
        texture.CreateInput("file", Sdf.ValueTypeNames.Asset).Set(Sdf.AssetPath("./texture.jpg"))
        texture.CreateInput("st", Sdf.ValueTypeNames.Float2).ConnectToSource(reader.ConnectableAPI(), "result")
        texture.CreateOutput("rgb", Sdf.ValueTypeNames.Float3)
        surface.CreateInput("diffuseColor", Sdf.ValueTypeNames.Color3f).ConnectToSource(texture.ConnectableAPI(), "rgb")
        UsdShade.MaterialBindingAPI.Apply(mesh.GetPrim()).Bind(material)
        stage.GetRootLayer().Save()
        with Sdf.ZipFileWriter.CreateNew(str(output/(name+".usdz"))) as writer:
            writer.AddFile(str(layer), layer.name)
            writer.AddFile(str(texture_path), texture_path.name)
    (output/"pose-checks.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("asset", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inspect(args.asset, args.output)
