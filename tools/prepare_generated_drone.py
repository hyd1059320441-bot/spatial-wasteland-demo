"""Stage the actual three-rotor generated mesh with rigid rotor animation."""
import argparse
import hashlib
import json
import math
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

from analyze_drone_rotors import connected_regions
from prepare_generated_robot import Glb, ROOT, mesh_source, quaternion, tangents


def prepare(source, output):
    output.mkdir(parents=True, exist_ok=True)
    points, normals, uv, indices, images = mesh_source(source, .2, False)
    # Source-view evidence: two rear motors and one opposite center motor.
    hubs = np.asarray([[-.17, .171, -.125], [-.17, .171, .125], [.176, .16, -.003]])
    original_faces = len(indices) // 3
    input_file, result_file = output / "simplify-input.json", output / "simplify-result.json"
    input_file.write_text(json.dumps(dict(positions=points.tolist(),
        attributes=np.column_stack([normals, uv]).tolist(), indices=indices.tolist(),
        triangles=16000, attributeWeights=[.25, .25, .25, 1, 1],
        targetError=.003, options=["PreserveFolds"])))
    subprocess.run(["node", str(ROOT / "tools/simplify_mesh.mjs"),
        str(input_file), str(result_file)], check=True)
    result = json.loads(result_file.read_text())
    triangles = np.asarray(result["indices"], dtype=np.uint32).reshape(-1, 3)
    part = np.zeros(len(triangles), dtype=int)
    regions = connected_regions(points, triangles, .15)
    chosen = set()
    for rotor in range(3):
        candidates = [(index, region) for index, region in enumerate(regions)
            if region["faceCount"] > 400 and index not in chosen]
        index, region = min(candidates,
            key=lambda item: np.linalg.norm(np.asarray(item[1]["center"])-hubs[rotor]))
        assert np.linalg.norm(np.asarray(region["center"])-hubs[rotor]) < .04
        chosen.add(index)
        # Keep the entire connected blade and hub cap; do not clip blade tips by a radial mask.
        part[region["faceIndices"]] = rotor + 1
    assert all((part == i).sum() > 100 for i in range(4)), "Rotor segmentation unresolved"
    factor = 1.4 / np.ptp(points[:, 0])
    points *= factor
    hubs *= factor

    glb = Glb()
    material = dict(name="AuthoredDroneComicSteel", doubleSided=True,
        pbrMetallicRoughness=dict(baseColorTexture=glb.texture(images["diffuseColor"]),
            metallicFactor=1, roughnessFactor=1))
    if "normal" in images:
        material["normalTexture"] = glb.texture(images["normal"])
    if "roughness" in images and "metallic" in images:
        rough = images["roughness"].convert("L")
        metal = images["metallic"].convert("L").resize(rough.size)
        packed = Image.merge("RGB", (Image.new("L", rough.size, 255), rough, metal))
        material["pbrMetallicRoughness"]["metallicRoughnessTexture"] = glb.texture(packed, mime="image/png")
    glb.doc["materials"] = [material]
    glb.doc["nodes"] = [dict(name="DroneRoot", children=[1, 2, 3, 4])]
    counts = []
    for i in range(4):
        selected = triangles[part == i]
        used, remap = np.unique(selected, return_inverse=True)
        pivot = np.zeros(3) if i == 0 else hubs[i - 1]
        local = points[used] - pivot
        mesh_indices = remap.astype(np.uint32).ravel()
        primitive = dict(attributes={
            "POSITION": glb.accessor(local, "VEC3", target=34962, bounds=True),
            "NORMAL": glb.accessor(normals[used], "VEC3", target=34962),
            "TANGENT": glb.accessor(tangents(local, normals[used], uv[used], mesh_indices), "VEC4", target=34962),
            "TEXCOORD_0": glb.accessor(uv[used], "VEC2", target=34962)},
            indices=glb.accessor(mesh_indices, "SCALAR", 5125, 34963), material=0)
        name = "DroneBody" if i == 0 else f"Rotor{i}"
        glb.doc["meshes"].append(dict(name=name, primitives=[primitive]))
        glb.doc["nodes"].append(dict(name=name, mesh=i, translation=pivot.tolist()))
        counts.append(len(selected))
    glb.doc["scenes"] = [dict(name="CombatDroneV8", nodes=[0])]
    del glb.doc["skins"]
    for clip, duration in (("Idle", .2), ("Walking", .2), ("Fire", .4), ("Death", 1.1)):
        times = np.linspace(0, duration, 17 if duration < .5 else 45)
        input_index = glb.accessor(times, "SCALAR", bounds=True)
        animation = dict(name=clip, channels=[], samplers=[])
        for rotor in range(3):
            sign = -1 if rotor == 1 else 1
            turns = times / .2
            if clip == "Death":
                turns = 5 * (times - times * times / (2 * duration))
            values = [quaternion(1, float(sign * turn * 360)) for turn in turns]
            animation["channels"].append(dict(sampler=len(animation["samplers"]),
                target=dict(node=rotor + 2, path="rotation")))
            animation["samplers"].append(dict(input=input_index,
                output=glb.accessor(values, "VEC4"), interpolation="LINEAR"))
        glb.doc["animations"].append(animation)
    target = output / "CombatDroneV8.glb"
    glb.save(target)
    manifest = dict(name="CombatDroneV8", source=str(source.resolve()),
        sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        derivative=str(target.resolve()), sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
        sourceTriangles=original_faces, triangles=int(len(triangles)), partTriangles=counts,
        metersPerUnit=1, upAxis="Y", width=1.4, sourceRotorCount=3,
        rotorPivots=hubs.tolist(), clips=[a["name"] for a in glb.doc["animations"]],
        status="offline_derivative_pending_rotor_visual_and_runtime_review")
    (output / "rig-provenance.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.output)
