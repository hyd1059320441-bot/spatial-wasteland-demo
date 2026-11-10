"""Offline connected-region evidence for complete rigid rotor selection."""
import argparse
import json
from pathlib import Path

import numpy as np

from prepare_generated_robot import mesh_source

def connected_regions(points, triangles, floor):
    _, weld = np.unique(np.round(points, 5), axis=0, return_inverse=True)
    faces = np.flatnonzero(np.min(points[triangles][:, :, 1], axis=1) > floor)
    parents = {int(face): int(face) for face in faces}
    edges = {}

    def root(index):
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    for face in faces:
        for a, b in ((0, 1), (1, 2), (2, 0)):
            key = tuple(sorted((int(weld[triangles[face, a]]), int(weld[triangles[face, b]]))))
            if key in edges:
                parents[root(int(face))] = root(edges[key])
            else:
                edges[key] = int(face)
    groups = {}
    for face in faces:
        groups.setdefault(root(int(face)), []).append(int(face))
    regions = []
    for group in sorted(groups.values(), key=len, reverse=True)[:20]:
        region = points[triangles[group]].reshape(-1, 3)
        regions.append(dict(faceCount=len(group), faceIndices=group,
            center=region.mean(axis=0).tolist(), min=region.min(axis=0).tolist(),
            max=region.max(axis=0).tolist()))
    return regions


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("simplified", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    points, _, _, _, _ = mesh_source(args.source, .2, False)
    triangles = np.asarray(json.loads(args.simplified.read_text())["indices"]).reshape(-1, 3)
    result = [dict(floor=floor, regions=connected_regions(points, triangles, floor))
        for floor in (.12, .13, .14, .15)]
    args.output.write_text(json.dumps(result, indent=2))
    for level in result:
        print("floor", level["floor"])
        for region in level["regions"][:8]:
            print(region["faceCount"], np.round(region["center"], 3),
                np.round(region["min"], 3), np.round(region["max"], 3))
