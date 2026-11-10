"""Evaluate the actual staged GLB skin/clip buffers and export inspectable poses."""
import argparse
import json
import struct
from pathlib import Path

import numpy as np
from pxr import Gf, Sdf, Usd, UsdGeom, UsdShade, Vt


def matrix(q, t):
    x, y, z, w = q / np.linalg.norm(q)
    value = np.eye(4)
    value[:3, :3] = [[1 - 2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1 - 2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1 - 2*(x*x+y*y)]]
    value[:3, 3] = t
    return value


def main(path, output, include_unskinned=False):
    data = path.read_bytes()
    size = struct.unpack_from("<I", data, 12)[0]
    doc = json.loads(data[20:20+size])
    binary = data[28+size:]

    def read(index):
        a = doc["accessors"][index]
        view = doc["bufferViews"][a["bufferView"]]
        count = a["count"]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[a["type"]]
        dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2"}[a["componentType"]]
        offset = view.get("byteOffset", 0) + a.get("byteOffset", 0)
        value = np.frombuffer(binary, dtype=dtype, count=count*width, offset=offset).reshape(count, width)
        return value

    output.mkdir(parents=True, exist_ok=True)
    for i, image in enumerate(doc["images"]):
        view = doc["bufferViews"][image["bufferView"]]
        (output / f"texture{i}.{'jpg' if image['mimeType']=='image/jpeg' else 'png'}").write_bytes(
            binary[view.get("byteOffset", 0):view.get("byteOffset", 0)+view["byteLength"]])
    primitive = doc["meshes"][0]["primitives"][0]
    attr = primitive["attributes"]
    points, normals = read(attr["POSITION"]), read(attr["NORMAL"])
    uv = read(attr["TEXCOORD_0"]).copy()
    uv[:, 1] = 1 - uv[:, 1]
    joints, weights = read(attr["JOINTS_0"]), read(attr["WEIGHTS_0"])
    indices = read(primitive["indices"]).reshape(-1, 3)
    skin = doc["skins"][0]
    inverse = read(skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
    names = {n["name"]: i for i, n in enumerate(doc["nodes"])}
    parents = {}
    for i, node in enumerate(doc["nodes"]):
        for child in node.get("children", []):
            parents[child] = i
    results = []
    cases = [("Bind", None, 0), ("Idle", "Idle", .5), ("WalkLeft", "Walking", .225),
        ("WalkRight", "Walking", .675), ("Fire", "Fire", .21), ("Death", "Death", .9)]
    for name, clip, time in cases:
        rotations = {i: np.asarray(n.get("rotation", [0, 0, 0, 1]), dtype=float)
            for i, n in enumerate(doc["nodes"])}
        translations = {i: np.asarray(n.get("translation", [0, 0, 0]), dtype=float)
            for i, n in enumerate(doc["nodes"])}
        if clip:
            animation = next(a for a in doc["animations"] if a["name"] == clip)
            for channel in animation["channels"]:
                sampler = animation["samplers"][channel["sampler"]]
                times = read(sampler["input"]).ravel()
                values = read(sampler["output"])
                k = min(max(np.searchsorted(times, time) - 1, 0), len(times)-2)
                ratio = (time-times[k]) / max(times[k+1]-times[k], 1e-9)
                q0, q1 = values[k].copy(), values[k+1].copy()
                if channel["target"]["path"] == "translation":
                    translations[channel["target"]["node"]] = q0*(1-ratio) + q1*ratio
                    continue
                if np.dot(q0, q1) < 0:
                    q1 *= -1
                q = q0 * (1-ratio) + q1 * ratio
                rotations[channel["target"]["node"]] = q / np.linalg.norm(q)
        world = {}

        def joint_world(index):
            if index not in world:
                node = doc["nodes"][index]
                local = matrix(rotations[index], translations[index])
                world[index] = joint_world(parents[index]) @ local if index in parents else local
            return world[index]

        matrices = np.asarray([joint_world(index) @ inverse[j]
            for j, index in enumerate(skin["joints"])])
        blended = (matrices[joints] * weights[:, :, None, None]).sum(axis=1)
        deformed = np.einsum("nij,nj->ni", blended,
            np.column_stack([points, np.ones(len(points))]))[:, :3]
        normal = np.einsum("nij,nj->ni", blended[:, :3, :3], normals)
        normal /= np.maximum(np.linalg.norm(normal, axis=1, keepdims=True), 1e-12)
        assert np.all(np.isfinite(deformed))
        if name == "Bind":
            assert np.max(np.abs(deformed-points)) < 2e-6, "Inverse-bind mismatch"
        original_edges = np.linalg.norm(points[indices[:, 1]]-points[indices[:, 0]], axis=1)
        posed_edges = np.linalg.norm(deformed[indices[:, 1]]-deformed[indices[:, 0]], axis=1)
        ratios = posed_edges / np.maximum(original_edges, 1e-7)
        results.append(dict(pose=name, clip=clip, time=time, min=deformed.min(axis=0).tolist(),
            max=deformed.max(axis=0).tolist(), edgeStretch95=float(np.quantile(ratios, .95)),
            edgeStretch99=float(np.quantile(ratios, .99)), maxStretch=float(ratios.max())))
        layer = output / (name + ".usda")
        stage = Usd.Stage.CreateNew(str(layer))
        UsdGeom.SetStageUpAxis(stage, "Y")
        UsdGeom.SetStageMetersPerUnit(stage, 1)
        root = UsdGeom.Xform.Define(stage, "/Root")
        stage.SetDefaultPrim(root.GetPrim())
        mesh = UsdGeom.Mesh.Define(stage, "/Root/mesh")
        mesh.CreatePointsAttr(Vt.Vec3fArray.FromNumpy(deformed.astype(np.float32)))
        mesh.CreateNormalsAttr(Vt.Vec3fArray.FromNumpy(normal.astype(np.float32)))
        mesh.SetNormalsInterpolation("vertex")
        mesh.CreateFaceVertexCountsAttr([3] * len(indices))
        mesh.CreateFaceVertexIndicesAttr(indices.ravel().tolist())
        mesh.CreateSubdivisionSchemeAttr("none")
        mesh.CreateDoubleSidedAttr(True)
        UsdGeom.PrimvarsAPI(mesh).CreatePrimvar("st", Sdf.ValueTypeNames.TexCoord2fArray,
            "vertex").Set(Vt.Vec2fArray.FromNumpy(uv.astype(np.float32)))
        material = UsdShade.Material.Define(stage, "/Root/Material")
        shader = UsdShade.Shader.Define(stage, "/Root/Material/Surface")
        shader.CreateIdAttr("UsdPreviewSurface")
        shader.CreateInput("roughness", Sdf.ValueTypeNames.Float).Set(.75)
        shader.CreateInput("metallic", Sdf.ValueTypeNames.Float).Set(.15)
        material.CreateSurfaceOutput().ConnectToSource(shader.ConnectableAPI(), "surface")
        reader = UsdShade.Shader.Define(stage, "/Root/Material/UV")
        reader.CreateIdAttr("UsdPrimvarReader_float2")
        reader.CreateInput("varname", Sdf.ValueTypeNames.Token).Set("st")
        reader.CreateOutput("result", Sdf.ValueTypeNames.Float2)
        texture = UsdShade.Shader.Define(stage, "/Root/Material/Texture")
        texture.CreateIdAttr("UsdUVTexture")
        texture.CreateInput("file", Sdf.ValueTypeNames.Asset).Set(Sdf.AssetPath("./texture0.jpg"))
        texture.CreateInput("st", Sdf.ValueTypeNames.Float2).ConnectToSource(reader.ConnectableAPI(), "result")
        texture.CreateOutput("rgb", Sdf.ValueTypeNames.Float3)
        shader.CreateInput("diffuseColor", Sdf.ValueTypeNames.Color3f).ConnectToSource(
            texture.ConnectableAPI(), "rgb")
        UsdShade.MaterialBindingAPI.Apply(mesh.GetPrim()).Bind(material)
        if include_unskinned:
            for node in doc["nodes"]:
                if node.get("name") != "BreakableShieldPanel" or "skin" in node:
                    continue
                panel_primitive = doc["meshes"][node["mesh"]]["primitives"][0]
                panel_attrs = panel_primitive["attributes"]
                panel_faces = read(panel_primitive["indices"]).ravel().reshape(-1, 3)
                transform = matrices[5] if clip else np.eye(4)
                panel_source = read(panel_attrs["POSITION"])
                panel_points = (np.column_stack([panel_source,
                    np.ones(len(panel_source))]) @ transform.T)[:, :3]
                panel_normals = read(panel_attrs["NORMAL"]) @ transform[:3, :3].T
                panel_uv = read(panel_attrs["TEXCOORD_0"]).copy()
                panel_uv[:, 1] = 1 - panel_uv[:, 1]
                panel = UsdGeom.Mesh.Define(stage, "/Root/BreakableShieldPanel")
                panel.CreatePointsAttr(Vt.Vec3fArray.FromNumpy(panel_points.astype(np.float32)))
                panel.CreateNormalsAttr(Vt.Vec3fArray.FromNumpy(panel_normals.astype(np.float32)))
                panel.SetNormalsInterpolation("vertex")
                panel.CreateFaceVertexCountsAttr([3] * len(panel_faces))
                panel.CreateFaceVertexIndicesAttr(panel_faces.ravel().tolist())
                panel.CreateSubdivisionSchemeAttr("none")
                panel.CreateDoubleSidedAttr(True)
                UsdGeom.PrimvarsAPI(panel).CreatePrimvar("st", Sdf.ValueTypeNames.TexCoord2fArray,
                    "vertex").Set(Vt.Vec2fArray.FromNumpy(panel_uv.astype(np.float32)))
                UsdShade.MaterialBindingAPI.Apply(panel.GetPrim()).Bind(material)
        stage.GetRootLayer().Save()
        package = output / (name + ".usdz")
        with Sdf.ZipFileWriter.CreateNew(str(package)) as writer:
            writer.AddFile(str(layer), layer.name)
            writer.AddFile(str(output / "texture0.jpg"), "texture0.jpg")
        print(package, flush=True)
    (output / "pose-checks.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("asset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--include-unskinned", action="store_true")
    args = parser.parse_args()
    main(args.asset, args.output, args.include_unskinned)
