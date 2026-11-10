"""Preserve reviewed meshes, separate the shield, and bake weighted muzzle tracks.

Tracks evaluate actual glTF rotations using SLERP and actual vertex weights.
They are sampled by the public SDK controller time (seconds), without guessing
SkeletonPoseComponent coordinate conventions. Editor originals are immutable.
"""
import copy
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

from inspect_rig_poses import matrix

ROOT = Path(__file__).resolve().parents[1]
STAGING = ROOT / "artifacts/2026-10-22-v8/rig-staging"
OUTPUT = ROOT / "artifacts/2026-10-22-v8/runtime-assets"

def matrix_quaternion(rotation):
    trace = np.trace(rotation)
    if trace > 0:
        s = np.sqrt(trace + 1) * 2
        return np.array([(rotation[2, 1] - rotation[1, 2]) / s,
            (rotation[0, 2] - rotation[2, 0]) / s,
            (rotation[1, 0] - rotation[0, 1]) / s, s / 4])
    i = int(np.argmax(np.diag(rotation)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = np.sqrt(1 + rotation[i, i] - rotation[j, j] - rotation[k, k]) * 2
    q = np.zeros(4)
    q[i] = s / 4
    q[j] = (rotation[j, i] + rotation[i, j]) / s
    q[k] = (rotation[k, i] + rotation[i, k]) / s
    q[3] = (rotation[k, j] - rotation[j, k]) / s
    return q


class Asset:
    def __init__(self, path):
        data = path.read_bytes()
        size = struct.unpack_from("<I", data, 12)[0]
        self.doc = json.loads(data[20:20 + size])
        self.binary = bytearray(data[28 + size:])

    def read(self, index):
        a = self.doc["accessors"][index]
        v = self.doc["bufferViews"][a["bufferView"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[a["type"]]
        dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2"}[a["componentType"]]
        return np.frombuffer(self.binary, dtype=dtype, count=a["count"] * width,
            offset=v.get("byteOffset", 0) + a.get("byteOffset", 0)).reshape(-1, width).copy()

    def indices(self, values):
        self.binary.extend(b"\0" * (-len(self.binary) % 4))
        view = dict(buffer=0, byteOffset=len(self.binary), byteLength=values.size * 4, target=34963)
        self.binary.extend(values.astype("<u4").tobytes())
        self.doc["bufferViews"].append(view)
        self.doc["accessors"].append(dict(bufferView=len(self.doc["bufferViews"]) - 1,
            componentType=5125, count=values.size, type="SCALAR"))
        return len(self.doc["accessors"]) - 1

    def subset(self, index, selected):
        source = self.doc["accessors"][index]
        values = self.read(index)[selected]
        self.binary.extend(b"\0" * (-len(self.binary) % 4))
        self.doc["bufferViews"].append(dict(buffer=0, byteOffset=len(self.binary),
            byteLength=values.nbytes, target=34962))
        self.binary.extend(values.tobytes())
        accessor = dict(bufferView=len(self.doc["bufferViews"]) - 1,
            componentType=source["componentType"], count=len(values), type=source["type"])
        if "min" in source:
            accessor.update(min=values.min(0).tolist(), max=values.max(0).tolist())
        self.doc["accessors"].append(accessor)
        return len(self.doc["accessors"]) - 1

    def save(self, path):
        self.doc["buffers"][0]["byteLength"] = len(self.binary)
        text = json.dumps(self.doc, separators=(",", ":")).encode()
        text += b" " * (-len(text) % 4)
        self.binary.extend(b"\0" * (-len(self.binary) % 4))
        data = struct.pack("<III", 0x46546C67, 2, 28 + len(text) + len(self.binary))
        data += struct.pack("<II", len(text), 0x4E4F534A) + text
        data += struct.pack("<II", len(self.binary), 0x004E4942) + self.binary
        path.write_bytes(data)

    def matrices(self, animation, time):
        rotations = {i: np.asarray(n.get("rotation", [0, 0, 0, 1]), float)
            for i, n in enumerate(self.doc["nodes"])}
        translations = {i: np.asarray(n.get("translation", [0, 0, 0]), float)
            for i, n in enumerate(self.doc["nodes"])}
        for channel in animation["channels"]:
            sampler = animation["samplers"][channel["sampler"]]
            times, values = self.read(sampler["input"]).ravel(), self.read(sampler["output"])
            k = min(max(np.searchsorted(times, time) - 1, 0), len(times) - 2)
            ratio = np.clip((time - times[k]) / (times[k + 1] - times[k]), 0, 1)
            a, b = values[k].astype(float), values[k + 1].astype(float)
            node, kind = channel["target"]["node"], channel["target"]["path"]
            if kind == "translation":
                translations[node] = a * (1 - ratio) + b * ratio
            elif kind == "rotation":
                dot = float(np.dot(a, b))
                if dot < 0:
                    b, dot = -b, -dot
                theta = np.arccos(np.clip(dot, -1, 1))
                q = (a * (1-ratio) + b * ratio) if theta < 1e-6 else (
                    a * np.sin((1-ratio)*theta) + b * np.sin(ratio*theta)) / np.sin(theta)
                rotations[node] = q / np.linalg.norm(q)
        parents = {child: i for i, n in enumerate(self.doc["nodes"]) for child in n.get("children", [])}
        world = {}

        def transform(i):
            if i not in world:
                local = matrix(rotations[i], translations[i])
                local[:3, :3] *= self.doc["nodes"][i].get("scale", [1, 1, 1])
                world[i] = transform(parents[i]) @ local if i in parents else local
            return world[i]

        skin = self.doc["skins"][0]
        inverse = self.read(skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
        return np.asarray([transform(i) @ inverse[j] for j, i in enumerate(skin["joints"])])


def finish(name, direction, region):
    source = STAGING / name / (name + ".glb")
    asset = Asset(source)
    prim = asset.doc["meshes"][0]["primitives"][0]
    attr = prim["attributes"]
    points = asset.read(attr["POSITION"])
    joints, weights = asset.read(attr["JOINTS_0"]), asset.read(attr["WEIGHTS_0"])
    faces = asset.read(prim["indices"]).ravel().reshape(-1, 3)
    shield_vertices = None
    direction = np.asarray(direction, float)
    direction /= np.linalg.norm(direction)
    candidates = np.flatnonzero(region(points))
    projected = points[candidates] @ direction
    # Average the terminal rim, not one arbitrary extreme vertex.
    rim = candidates[projected > projected.max() - .014]
    assert len(rim) >= 8
    socket = points[rim].mean(0)
    shield = None
    if name == "ShieldRobotV9":
        center = points[faces].mean(1)
        normal = np.array([-.6841184, .12629801, .71835285])
        tangent = center @ np.array([.71835285, 0, .6841184])
        mask = (center[:, 1] > .94) & (center[:, 1] < 1.61) & (
            center[:, 0] < .10) & (center @ normal > .285) & (tangent > -.37)
        panel_faces, body_faces = faces[mask], faces[~mask]
        assert len(panel_faces) > 800 and len(body_faces) > 10000
        vertices = np.unique(panel_faces)
        shield_vertices = vertices
        shield = dict(min=points[vertices].min(0).tolist(), max=points[vertices].max(0).tolist(),
            triangles=len(panel_faces), planeNormal=normal.tolist())
        body = copy.deepcopy(prim)
        body["indices"] = asset.indices(body_faces.ravel())
        panel = copy.deepcopy(prim)
        panel_vertices, remap = np.unique(panel_faces, return_inverse=True)
        panel["indices"] = asset.indices(remap.astype(np.uint32))
        # An armor panel is rigid. Import it without skin to avoid the SDK
        # creating another complete skeleton for the second rendering node.
        panel["attributes"].pop("JOINTS_0")
        panel["attributes"].pop("WEIGHTS_0")
        for attribute, index in list(panel["attributes"].items()):
            panel["attributes"][attribute] = asset.subset(index, panel_vertices)
        asset.doc["meshes"][0]["primitives"] = [body]
        asset.doc["meshes"].append(dict(name="BreakableShieldPanel", primitives=[panel]))
        surface = next(i for i, n in enumerate(asset.doc["nodes"]) if n.get("mesh") == 0)
        panel_node = copy.deepcopy(asset.doc["nodes"][surface])
        panel_node.update(name="BreakableShieldPanel", mesh=1)
        panel_node.pop("skin")
        asset.doc["nodes"].append(panel_node)
        asset.doc["scenes"][0]["nodes"].append(len(asset.doc["nodes"]) - 1)
        assert len(panel_faces) + len(body_faces) == len(faces)
    clips = {}
    for animation in asset.doc["animations"]:
        duration = max(float(asset.read(s["input"])[-1, 0]) for s in animation["samplers"])
        times = np.linspace(0, duration, int(np.ceil(duration * 120)) + 1)
        samples = []
        for time in times:
            matrices = asset.matrices(animation, time)
            blended = (matrices[joints[rim]] * weights[rim, :, None, None]).sum(1)
            pos = np.einsum("nij,nj->ni", blended,
                np.column_stack([points[rim], np.ones(len(rim))]))[:, :3].mean(0)
            axis = np.einsum("nij,j->ni", blended[:, :3, :3], direction).mean(0)
            axis /= np.linalg.norm(axis)
            row = np.concatenate([pos, axis])
            if shield_vertices is not None:
                # RForearm is the measured shield-carrying arm of this source.
                panel_transform = matrices[5]
                panel_points = (np.column_stack([points[shield_vertices],
                    np.ones(len(shield_vertices))]) @ panel_transform.T)[:, :3]
                lo, hi = panel_points.min(0), panel_points.max(0)
                row = np.concatenate([row, (lo + hi) / 2, hi - lo,
                    panel_transform[:3, 3], matrix_quaternion(panel_transform[:3, :3])])
            samples.append(row.round(7).tolist())
        clips[animation["name"]] = dict(duration=duration, samples=samples,
            loop=animation["name"] in ("Idle", "Walking"))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / (name + ".glb")
    asset.save(target)
    tracks = dict(schemaVersion=1, name=name, units="meters", sampleTime="seconds",
        bindMuzzle=socket.tolist(), bindDirection=direction.tolist(), rimVertices=rim.tolist(),
        shield=shield, clips=clips)
    if shield is not None:
        tracks["shieldRigidJoint"] = "RForearm"
    (OUTPUT / (name + ".sockets.json")).write_text(json.dumps(tracks, separators=(",", ":")))
    provenance = json.loads((STAGING / name / "rig-provenance.json").read_text())
    provenance.update(reviewedRigSha256=provenance["sha256"], derivative=str(target),
        sha256=hashlib.sha256(target.read_bytes()).hexdigest(), muzzle=socket.tolist(),
        muzzleDirection=direction.tolist(), shield=shield,
        status="offline_verified_mesh_and_animation_tracks_pending_sdk_runtime")
    (OUTPUT / (name + ".provenance.json")).write_text(json.dumps(provenance, indent=2))
    print(name, "muzzle", socket, "direction", direction, "rim", len(rim), "shield", shield, flush=True)


if __name__ == "__main__":
    finish("AssaultRobotV8", [0, 0, 1], lambda p: (p[:, 0] < -.23) & (p[:, 1] > 1.13) & (p[:, 1] < 1.25))
    finish("SniperRobotV8", [1, 0, .16], lambda p: (p[:, 0] > .5) & (p[:, 1] > 1.32) & (p[:, 1] < 1.44))
    finish("HeavyGunnerV9", [1, 0, 1], lambda p: (p[:, 0] > .34) & (p[:, 1] > 1.07) & (p[:, 1] < 1.47) & (p[:, 2] > .3))
    finish("ShieldRobotV9", [1, -.10, 1], lambda p: (p[:, 0] > .24) & (p[:, 1] > 1.45) & (p[:, 1] < 1.64) & (p[:, 2] > .3))
