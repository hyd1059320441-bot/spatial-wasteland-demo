"""Offline geometry/UV previews, compared at identical scale. Not an SDK render."""
import io
import json
from pathlib import Path
from zipfile import ZipFile

import numpy as np
from PIL import Image, ImageDraw
from pxr import Usd, UsdGeom, UsdShade

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts/2026-10-21-expansion/asset-review"
OUTPUT.mkdir(parents=True, exist_ok=True)


def load(path):
    stage = Usd.Stage.Open(str(path))
    cache = UsdGeom.XformCache()
    textures = {}
    triangles, colors = [], []
    for prim in stage.Traverse():
        if not prim.IsA(UsdGeom.Mesh):
            continue
        material = UsdShade.MaterialBindingAPI(prim).ComputeBoundMaterial()[0]
        shader = material.ComputeSurfaceSource()[0]
        source = shader.GetInput("diffuseColor").GetConnectedSource()[0]
        image_name = source.GetPrim().GetAttribute("inputs:file").Get().path.removeprefix("./")
        if image_name not in textures:
            with ZipFile(path) as archive:
                textures[image_name] = np.asarray(Image.open(
                    io.BytesIO(archive.read(image_name))).convert("RGB"))
        texture = textures[image_name]
        mesh = UsdGeom.Mesh(prim)
        points = np.asarray(mesh.GetPointsAttr().Get(), dtype=float)
        indices = np.asarray(mesh.GetFaceVertexIndicesAttr().Get()).reshape(-1, 3)
        world = np.column_stack([points, np.ones(len(points))]) @ np.asarray(
            cache.GetLocalToWorldTransform(prim))
        triangles.append(world[:, :3][indices])
        uv = np.asarray(UsdGeom.PrimvarsAPI(prim).GetPrimvar("st").Get())
        mid = uv[indices].mean(axis=1)
        tx = np.clip(mid[:, 0] * (texture.shape[1] - 1), 0, texture.shape[1] - 1).astype(int)
        ty = np.clip((1 - mid[:, 1]) * (texture.shape[0] - 1), 0, texture.shape[0] - 1).astype(int)
        colors.append(texture[ty, tx])
    return np.concatenate(triangles), np.concatenate(colors)


def render(triangles, colors, angle, limits):
    yaw, pitch = np.radians(angle)
    right = np.array([np.cos(yaw), 0, -np.sin(yaw)])
    depth = np.array([np.sin(yaw), 0, np.cos(yaw)])
    up = np.array([0, np.cos(pitch), 0]) - depth * np.sin(pitch)
    view = depth * np.cos(pitch) + np.array([0, np.sin(pitch), 0])
    projected = np.stack([triangles @ right, triangles @ up, triangles @ view], axis=-1)
    low, high = limits
    scale = min(420 / max(high[0] - low[0], 1e-6), 330 / max(high[1] - low[1], 1e-6))
    xy = projected[:, :, :2] - (low + high) / 2
    xy[:, :, 0] = xy[:, :, 0] * scale + 240
    xy[:, :, 1] = -xy[:, :, 1] * scale + 195
    normal = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    normal /= np.maximum(np.linalg.norm(normal, axis=1)[:, None], 1e-8)
    light = 0.65 + 0.35 * np.abs(normal @ np.array([0.3, 0.8, 0.5]))
    image = Image.new("RGB", (480, 400), "#e5ded0")
    draw = ImageDraw.Draw(image)
    for i in np.argsort(projected[:, :, 2].mean(axis=1)):
        color = tuple(np.clip(colors[i] * light[i], 0, 255).astype(int))
        draw.polygon([tuple(p) for p in xy[i]], fill=color)
    return image


for asset in json.loads((ROOT / "assets-src/v5/manifest.json").read_text()):
    name = asset["name"]
    source = ROOT / "assets-src/v5" / (name + ("-before" if "originalDirectory" in asset else "") + ".usdz")
    runtime = ROOT / "app/src/main/assets/models" / asset.get("originalDirectory", "v5") / (name + ".usdz")
    original, original_colors = load(source)
    reduced, reduced_colors = load(runtime)
    sheet = Image.new("RGB", (1440, 840), "white")
    for col, angle in enumerate([(0, 0), (45, 18), (90, 0)]):
        yaw, pitch = np.radians(angle)
        right = np.array([np.cos(yaw), 0, -np.sin(yaw)])
        up = np.array([0, np.cos(pitch), 0]) - np.array([np.sin(yaw), 0, np.cos(yaw)]) * np.sin(pitch)
        p = np.stack([original @ right, original @ up], axis=-1).reshape(-1, 2)
        bounds = p.min(axis=0), p.max(axis=0)
        for row, (triangles, colors) in enumerate([(original, original_colors), (reduced, reduced_colors)]):
            frame = render(triangles, colors, angle, bounds)
            ImageDraw.Draw(frame).text((14, 10), f"{name} | {'source' if row == 0 else 'mobile'} | {len(triangles)} triangles", fill="black")
            sheet.paste(frame, (col * 480, 30 + row * 400))
    ImageDraw.Draw(sheet).text((16, 10), "Offline geometry + centroid UV preview (not SDK rendering)", fill="black")
    file = OUTPUT / (name + ".png")
    sheet.save(file)
    print(file, flush=True)
