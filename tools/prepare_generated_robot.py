"""Stage a skinned mobile derivative of a verified generated USDZ.

The original Editor files remain immutable. This writes only to the explicit
output folder, with source hashes and anatomical rig data for visual review.
The derivative is not an accepted Editor scene package or SDK runtime proof.
"""
import argparse
import hashlib
import io
import json
import math
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

import numpy as np
from PIL import Image
from pxr import Usd, UsdGeom, UsdShade

ROOT = Path(__file__).resolve().parents[1]


class Glb:
    def __init__(self):
        self.binary = bytearray()
        self.doc = dict(asset={"version": "2.0", "generator": "Wasteland generated mesh rig"},
            bufferViews=[], accessors=[], nodes=[], meshes=[], skins=[], animations=[],
            images=[], textures=[], samplers=[{"magFilter": 9729, "minFilter": 9987,
                "wrapS": 10497, "wrapT": 10497}], materials=[], scenes=[], scene=0)

    def view(self, data, target=None):
        self.binary.extend(b"\0" * (-len(self.binary) % 4))
        item = dict(buffer=0, byteOffset=len(self.binary), byteLength=len(data))
        if target:
            item["target"] = target
        self.binary.extend(data)
        self.doc["bufferViews"].append(item)
        return len(self.doc["bufferViews"]) - 1

    def accessor(self, data, kind, component=5126, target=None, bounds=False):
        dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2"}[component]
        values = np.asarray(data, dtype=dtype)
        item = dict(bufferView=self.view(values.tobytes(), target),
            componentType=component, count=len(values), type=kind)
        if bounds:
            item.update(min=np.atleast_1d(values.min(axis=0)).tolist(),
                max=np.atleast_1d(values.max(axis=0)).tolist())
        self.doc["accessors"].append(item)
        return len(self.doc["accessors"]) - 1

    def texture(self, image, mode="RGB", mime="image/jpeg"):
        image = image.convert(mode)
        image.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
        data = io.BytesIO()
        image.save(data, format="JPEG" if mime == "image/jpeg" else "PNG", quality=92)
        self.doc["images"].append({"bufferView": self.view(data.getvalue()), "mimeType": mime})
        self.doc["textures"].append({"source": len(self.doc["images"]) - 1, "sampler": 0})
        return {"index": len(self.doc["textures"]) - 1}

    def save(self, path):
        self.doc["buffers"] = [{"byteLength": len(self.binary)}]
        text = json.dumps(self.doc, separators=(",", ":")).encode()
        text += b" " * (-len(text) % 4)
        self.binary.extend(b"\0" * (-len(self.binary) % 4))
        data = struct.pack("<III", 0x46546C67, 2, 28 + len(text) + len(self.binary))
        data += struct.pack("<II", len(text), 0x4E4F534A) + text
        data += struct.pack("<II", len(self.binary), 0x004E4942) + self.binary
        path.write_bytes(data)


def quaternion(axis, degrees):
    angle = math.radians(degrees) / 2
    q = [0., 0., 0., math.cos(angle)]
    q[axis] = math.sin(angle)
    return q


def rotation_matrix(q):
    x, y, z, w = q
    return np.asarray([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def tangents(points, normals, uv, indices):
    triangles = indices.reshape(-1, 3)
    edge1 = points[triangles[:, 1]] - points[triangles[:, 0]]
    edge2 = points[triangles[:, 2]] - points[triangles[:, 0]]
    duv1 = uv[triangles[:, 1]] - uv[triangles[:, 0]]
    duv2 = uv[triangles[:, 2]] - uv[triangles[:, 0]]
    determinant = duv1[:, 0]*duv2[:, 1] - duv1[:, 1]*duv2[:, 0]
    factor = np.divide(1, determinant, out=np.zeros_like(determinant),
        where=np.abs(determinant) > 1e-9)
    tangent = (edge1*duv2[:, 1, None] - edge2*duv1[:, 1, None])*factor[:, None]
    bitangent = (edge2*duv1[:, 0, None] - edge1*duv2[:, 0, None])*factor[:, None]
    total, total_b = np.zeros_like(points), np.zeros_like(points)
    for corner in range(3):
        np.add.at(total, triangles[:, corner], tangent)
        np.add.at(total_b, triangles[:, corner], bitangent)
    total -= normals*np.sum(normals*total, axis=1, keepdims=True)
    length = np.linalg.norm(total, axis=1)
    missing = length < 1e-9
    axis = np.tile([1., 0., 0.], (missing.sum(), 1))
    axis[np.abs(normals[missing, 0]) > .8] = [0., 1., 0.]
    total[missing] = np.cross(normals[missing], axis)
    total /= np.maximum(np.linalg.norm(total, axis=1, keepdims=True), 1e-12)
    handedness = np.where(np.sum(np.cross(normals, total)*total_b, axis=1) < 0, -1, 1)
    return np.column_stack([total, handedness])


def mesh_source(path, height, reverse):
    stage = Usd.Stage.Open(str(path))
    assert UsdGeom.GetStageUpAxis(stage) == "Y"
    prims = [p for p in stage.Traverse() if p.IsA(UsdGeom.Mesh)]
    assert len(prims) == 1, "This rig processor requires one authored mesh"
    prim = prims[0]
    mesh = UsdGeom.Mesh(prim)
    points = np.asarray(mesh.GetPointsAttr().Get(), dtype=np.float64)
    normals = np.asarray(mesh.GetNormalsAttr().Get(), dtype=np.float64)
    assert mesh.GetNormalsInterpolation() == "vertex"
    uv = UsdGeom.PrimvarsAPI(prim).GetPrimvar("st")
    assert uv.GetInterpolation() == "vertex" and not uv.IsIndexed()
    texcoords = np.asarray(uv.Get(), dtype=np.float64)
    assert len(points) == len(normals) == len(texcoords)
    assert all(n == 3 for n in mesh.GetFaceVertexCountsAttr().Get())
    indices = np.asarray(mesh.GetFaceVertexIndicesAttr().Get(), dtype=np.uint32)
    transform = np.asarray(UsdGeom.XformCache().GetLocalToWorldTransform(prim))
    points = (np.column_stack([points, np.ones(len(points))]) @ transform)[:, :3]
    normals = normals @ np.linalg.inv(transform[:3, :3]).T
    normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-12)
    lo, hi = points.min(axis=0), points.max(axis=0)
    scale = height / (hi[1] - lo[1])
    points -= [(hi[0] + lo[0]) / 2, lo[1], (hi[2] + lo[2]) / 2]
    points *= scale
    if reverse:
        points[:, [0, 2]] *= -1
        normals[:, [0, 2]] *= -1
    # glTF and USD image coordinates use opposite vertical texture direction.
    texcoords[:, 1] = 1 - texcoords[:, 1]
    material = UsdShade.MaterialBindingAPI(prim).ComputeBoundMaterial()[0]
    shader = material.ComputeSurfaceSource()[0]
    images = {}
    with ZipFile(path) as archive:
        for role in ("diffuseColor", "normal", "roughness", "metallic"):
            source = shader.GetInput(role).GetConnectedSource()
            if source:
                filename = source[0].GetPrim().GetAttribute("inputs:file").Get().path
                images[role] = Image.open(io.BytesIO(archive.read(filename.removeprefix("./")))).copy()
    return points, normals, texcoords, indices, images


def anatomical_rig(height, name):
    # Fractions derived from independent source views, not copied from the old robot.
    slender = "Assault" in name or "Sniper" in name
    hip_y = .51 if slender else .49
    knee_y = .285 if slender else .31
    hip_x = .095 if slender else .11
    shoulder_x = .16 if slender else .20
    elbow_x = .185 if slender else .245
    hand_x = .20 if slender else .26
    bones = [("Root", -1, [0, 0, 0]), ("Pelvis", 0, [0, hip_y, 0]),
        ("Torso", 1, [0, .63, 0]), ("Head", 2, [0, .88, 0])]
    for side, sign in (("R", -1), ("L", 1)):
        shoulder = len(bones)
        bones.extend([(side + "UpperArm", 2, [sign * shoulder_x, .79, 0]),
            (side + "Forearm", shoulder, [sign * elbow_x, .66, .025]),
            (side + "Hand", shoulder + 1, [sign * hand_x, .54, .04])])
        thigh = len(bones)
        bones.extend([(side + "Thigh", 1, [sign * hip_x, hip_y, 0]),
            (side + "Shin", thigh, [sign * (hip_x + .02), knee_y, 0]),
            (side + "Foot", thigh + 1, [sign * (hip_x + .045), .08, .01])])
    names = [b[0] for b in bones]
    parents = [b[1] for b in bones]
    pivots = np.asarray([b[2] for b in bones]) * height
    return names, parents, pivots


def skin_weights(points, height, names, parents, pivots):
    joints = np.zeros((len(points), 4), dtype=np.uint16)
    weights = np.zeros((len(points), 4), dtype=np.float32)
    def smooth(lo, hi, value):
        t = np.clip((value - lo) / (hi - lo), 0, 1)
        return t*t*(3-2*t)
    field = np.zeros((len(points), len(names)), dtype=np.float64)
    p = points / height
    x, y = p[:, 0], p[:, 1]
    hip = pivots[names.index("Pelvis"), 1] / height
    knee = pivots[names.index("RShin"), 1] / height
    torso = smooth(.50, .63, y)
    head = smooth(.855, .91, y) * (1 - smooth(.125, .185, np.abs(x)))
    leg = 1 - smooth(hip-.055, hip+.045, y)
    arm_threshold = .145 + smooth(.73, .81, y) * .025
    arm = smooth(arm_threshold-.022, arm_threshold+.025, np.abs(x))
    arm *= smooth(.44, .52, y) * (1-smooth(.86, .93, y))
    arm *= 1-leg
    body = np.clip(1-leg-arm, 0, 1)
    field[:, names.index("Head")] = body * head
    field[:, names.index("Torso")] = body * (1-head) * torso
    field[:, names.index("Pelvis")] = body * (1-head) * (1-torso)
    right = 1-smooth(-.018, .018, x)
    for side, side_weight in (("R", right), ("L", 1-right)):
        shin = 1-smooth(knee-.05, knee+.05, y)
        foot = 1-smooth(.06, .115, y)
        field[:, names.index(side+"Thigh")] = leg*side_weight*(1-shin)
        field[:, names.index(side+"Shin")] = leg*side_weight*shin*(1-foot)
        field[:, names.index(side+"Foot")] = leg*side_weight*shin*foot
        elbow = pivots[names.index(side+"Forearm"), 1] / height
        forearm = 1-smooth(elbow-.04, elbow+.045, y)
        field[:, names.index(side+"UpperArm")] = arm*side_weight*(1-forearm)
        # Gun, wrist and hand share the forearm transform; no independent gun owner.
        field[:, names.index(side+"Forearm")] = arm*side_weight*forearm
    for i, row in enumerate(field):
        selected = np.argsort(row)[-4:][::-1]
        joints[i] = selected
        weights[i] = row[selected] / row[selected].sum()
    joints[weights == 0] = 0
    assert np.allclose(weights.sum(axis=1), 1)
    return joints, weights


def add_animations(glb, names, parents, pivots, height, points, joints, weights,
        weapon_joint="RForearm"):
    doc = glb.doc
    for clip, duration, frames in (("Idle", 2., 61), ("Walking", .9, 28),
            ("Punch", .6, 19), ("Fire", .42, 14), ("Death", 1.1, 34)):
        times = np.linspace(0, duration, frames)
        time_index = glb.accessor(times, "SCALAR", bounds=True)
        animation = {"name": clip, "samplers": [], "channels": []}
        angles = {name: np.zeros(frames) for name in names}
        axes = {name: 0 for name in names}
        phase = times / duration * math.tau
        if clip == "Idle":
            angles["Torso"] = np.sin(phase) * 1.2
            axes["Torso"] = 1
            angles["Head"] = np.sin(phase) * 4
            axes["Head"] = 1
        elif clip == "Walking":
            for side, offset in (("R", 0), ("L", math.pi)):
                step = np.sin(phase + offset)
                angles[side + "Thigh"] = step * 18
                angles[side + "Shin"] = -np.maximum(0, -step) * 22
                angles[side + "Foot"] = -angles[side + "Thigh"] - angles[side + "Shin"]
                angles[side + "UpperArm"] = -step * 5
            angles["Torso"] = np.sin(phase) * 3
            axes["Torso"] = 1
        elif clip == "Punch":
            strike = np.sin(np.pi * times / duration)
            angles["RUpperArm"] = -strike * 48
            angles["RForearm"] = -strike * 18
            angles["Torso"] = strike * 8
            axes["Torso"] = 1
        elif clip == "Fire":
            recoil = np.sin(np.pi * times / duration)
            angles[weapon_joint] = recoil * 7
            angles["Torso"] = recoil * 2
        else:
            fall = np.minimum(1, times / .8)
            angles["Root"] = fall * -82
            angles["RThigh"] = fall * 20
            angles["LThigh"] = fall * -15
            angles["RUpperArm"] = fall * -28
            axes["Root"] = 0
        samples = {name: np.asarray([quaternion(axes[name], value) for value in angles[name]])
            for name in names}
        translations = []
        for frame in range(frames):
            worlds = []
            for j, name in enumerate(names):
                local = np.eye(4)
                local[:3, :3] = rotation_matrix(samples[name][frame])
                local[:3, 3] = pivots[j] - (pivots[parents[j]] if parents[j] >= 0 else 0)
                worlds.append(worlds[parents[j]] @ local if parents[j] >= 0 else local)
            inverse = np.repeat(np.eye(4)[None, ...], len(names), axis=0)
            inverse[:, :3, 3] = -pivots
            skin = np.asarray(worlds) @ inverse
            blend = (skin[joints] * weights[:, :, None, None]).sum(axis=1)
            posed = np.einsum("nij,nj->ni", blend,
                np.column_stack([points, np.ones(len(points))]))
            translations.append([0, -float(posed[:, 1].min()), 0])
        for j, name in enumerate(names):
            values = samples[name]
            output = glb.accessor(values, "VEC4")
            animation["channels"].append({"sampler": len(animation["samplers"]),
                "target": {"node": j + 1, "path": "rotation"}})
            animation["samplers"].append({"input": time_index, "output": output,
                "interpolation": "LINEAR"})
        animation["channels"].append({"sampler": len(animation["samplers"]),
            "target": {"node": 1, "path": "translation"}})
        animation["samplers"].append({"input": time_index,
            "output": glb.accessor(translations, "VEC3"), "interpolation": "LINEAR"})
        doc["animations"].append(animation)


def prepare(source, output, height, reverse, triangles, name, weapon_joint="RForearm"):
    output.mkdir(parents=True, exist_ok=True)
    points, normals, uv, indices, images = mesh_source(source, height, reverse)
    source_faces = len(indices) // 3
    input_path, result_path = output / "simplify-input.json", output / "simplify-result.json"
    input_path.write_text(json.dumps(dict(positions=points.tolist(),
        attributes=np.column_stack([normals, uv]).tolist(), indices=indices.tolist(),
        triangles=triangles, attributeWeights=[.25, .25, .25, 1, 1],
        targetError=.003, options=["PreserveFolds"])))
    subprocess.run(["node", str(ROOT / "tools/simplify_mesh.mjs"),
        str(input_path), str(result_path)], check=True)
    result = json.loads(result_path.read_text())
    indices = np.asarray(result["indices"], dtype=np.uint32)
    used, inverse = np.unique(indices, return_inverse=True)
    points, normals, uv = points[used], normals[used], uv[used]
    indices = inverse.astype(np.uint32)
    glb = Glb()
    names, parents, pivots = anatomical_rig(height, name)
    joints, weights = skin_weights(points, height, names, parents, pivots)
    glb.doc["nodes"].append({"name": "Rig", "children": [1]})
    for i, bone in enumerate(names):
        children = [j + 1 for j, parent in enumerate(parents) if parent == i]
        translation = pivots[i] - (pivots[parents[i]] if parents[i] >= 0 else 0)
        node = {"name": bone, "translation": translation.tolist(),
            "rotation": [0, 0, 0, 1], "scale": [1, 1, 1]}
        if children:
            node["children"] = children
        glb.doc["nodes"].append(node)
    glb.doc["nodes"].append({"name": name + "Surface", "mesh": 0, "skin": 0})
    inverse_bind = np.repeat(np.eye(4)[None, ...], len(names), axis=0)
    inverse_bind[:, :3, 3] = -pivots
    glb.doc["skins"] = [{"name": "MechanicalRig", "skeleton": 1,
        "joints": list(range(1, len(names) + 1)),
        "inverseBindMatrices": glb.accessor(inverse_bind.transpose(0, 2, 1).reshape(-1, 16), "MAT4")}]
    material = {"name": "AuthoredComicSteel", "doubleSided": True,
        "pbrMetallicRoughness": {"baseColorTexture": glb.texture(images["diffuseColor"]),
            "metallicFactor": 1, "roughnessFactor": 1}}
    if "normal" in images:
        material["normalTexture"] = glb.texture(images["normal"])
    if "roughness" in images and "metallic" in images:
        rough = images["roughness"].convert("L")
        metal = images["metallic"].convert("L").resize(rough.size)
        packed = Image.merge("RGB", (Image.new("L", rough.size, 255), rough, metal))
        material["pbrMetallicRoughness"]["metallicRoughnessTexture"] = glb.texture(
            packed, mime="image/png")
    glb.doc["materials"] = [material]
    glb.doc["meshes"] = [{"name": name, "primitives": [{
        "attributes": {"POSITION": glb.accessor(points, "VEC3", target=34962, bounds=True),
            "NORMAL": glb.accessor(normals, "VEC3", target=34962),
            "TANGENT": glb.accessor(tangents(points, normals, uv, indices), "VEC4", target=34962),
            "TEXCOORD_0": glb.accessor(uv, "VEC2", target=34962),
            "JOINTS_0": glb.accessor(joints, "VEC4", 5123, 34962),
            "WEIGHTS_0": glb.accessor(weights, "VEC4", target=34962)},
        "indices": glb.accessor(indices, "SCALAR", 5125, 34963), "material": 0}]}]
    glb.doc["scenes"] = [{"name": name, "nodes": [0, len(names)+1]}]
    assert weapon_joint in names
    add_animations(glb, names, parents, pivots, height, points, joints, weights, weapon_joint)
    target = output / (name + ".glb")
    glb.save(target)
    manifest = dict(name=name, source=str(source.resolve()),
        sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        derivative=str(target.resolve()), sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
        sourceTriangles=source_faces, triangles=len(indices) // 3,
        meshoptError=result["error"], metersPerUnit=1, upAxis="Y", targetHeight=height,
        forward="source coordinates rotated 180 degrees around Y" if reverse
            else "source coordinates preserved; semantic forward requires reviewed evidence",
        fireJoint=weapon_joint,
        joints=[dict(name=n, parent=parents[i], pivot=pivots[i].tolist()) for i, n in enumerate(names)],
        clips=[a["name"] for a in glb.doc["animations"]],
        status="offline_derivative_pending_visual_and_runtime_review")
    (output / "rig-provenance.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--height", type=float, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--reverse", action="store_true")
    parser.add_argument("--triangles", type=int, default=14000)
    parser.add_argument("--weapon-joint",
        choices=["RForearm", "LForearm", "RUpperArm", "LUpperArm", "Torso"], default="RForearm")
    args = parser.parse_args()
    assert args.height > 0
    prepare(args.source, args.output, args.height, args.reverse, args.triangles, args.name,
        args.weapon_joint)
