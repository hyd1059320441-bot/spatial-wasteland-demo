"""Package selected CC0 glTFs as GLBs with shared textures and provenance.

No mesh, skin or animation re-export: buffers are only concatenated and image
paths are made explicit. Shared runtime textures are resized to 1024px using
macOS sips; originals and both hashes remain in assets-src/ and the manifest.
"""
import hashlib
import json
import shutil
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets-src/quaternius/environment/Modular SciFi MegaKit[Standard]/glTF"
OUT = ROOT / "app/src/main/assets/models/outpost"
SELECTED = {
    "floor": "Platforms/Platform_Metal.gltf",
    "floor_detail": "Platforms/Platform_3Plates.gltf",
    "wall": "Walls/WallAstra_Straight.gltf",
    "wall_broken": "Walls/WallAstra_Straight_Broken.gltf",
    "wall_window": "Walls/WallAstra_Straight_Window.gltf",
    "roof": "Platforms/Platform_DarkPlates.gltf",
    "door_frame": "Platforms/Door_Frame_SquareTall.gltf",
    "door": "Platforms/Door_Metal.gltf",
    "column": "Columns/Column_MetalSupport.gltf",
    "pipes": "Columns/Column_Pipes.gltf",
    "vent": "Props/Prop_Vent_Big.gltf",
    "computer": "Props/Prop_Computer.gltf",
    "terminal": "Props/Prop_AccessPoint.gltf",
    "chest": "Props/Prop_Chest.gltf",
    "barrel": "Props/Prop_Barrel_Large.gltf",
    "crate": "Props/Prop_Crate3.gltf",
    "crate_tall": "Props/Prop_Crate4.gltf",
    "light": "Props/Prop_Light_Wide.gltf",
    "cable": "Props/Prop_Cable_3.gltf",
    "rail": "Props/Prop_Rail_4.gltf",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def package(source, destination):
    original = source.read_bytes()
    doc = json.loads(original)
    binary = bytearray()
    starts = []
    for buffer in doc["buffers"]:
        starts.append(len(binary))
        data = (source.parent / buffer["uri"]).read_bytes()
        assert len(data) >= buffer["byteLength"]
        binary.extend(data[:buffer["byteLength"]])
        binary.extend(b"\0" * (-len(binary) % 4))
    for view in doc.get("bufferViews", []):
        view["byteOffset"] = view.get("byteOffset", 0) + starts[view.get("buffer", 0)]
        view["buffer"] = 0
    doc["buffers"] = [{"byteLength": len(binary)}]
    images = []
    for image in doc.get("images", []):
        uri = image.get("uri")
        if not uri or uri.startswith("data:"):
            continue
        texture_source = source.parent / uri
        if not texture_source.exists():
            texture_source = SOURCE.parent / "Textures" / Path(uri).name
        data = texture_source.read_bytes()
        filename = f"{Path(uri).stem}-{digest(data)[:12]}-1024{Path(uri).suffix}"
        target = OUT / "textures" / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(data)
            subprocess.run(["sips", "-Z", "1024", str(target)], check=True,
                stdout=subprocess.DEVNULL, timeout=15)
        image["uri"] = f"textures/{filename}"
        images.append({"file": image["uri"], "sha256": digest(target.read_bytes()),
            "sourceSha256": digest(data), "transform": "sips max dimension 1024"})
    encoded = json.dumps(doc, separators=(",", ":")).encode()
    encoded += b" " * (-len(encoded) % 4)
    glb = struct.pack("<III", 0x46546C67, 2, 28 + len(encoded) + len(binary))
    glb += struct.pack("<II", len(encoded), 0x4E4F534A) + encoded
    glb += struct.pack("<II", len(binary), 0x004E4942) + binary
    destination.write_bytes(glb)
    return {
        "source": str(source.relative_to(ROOT)), "sourceSha256": digest(original),
        "file": str(destination.relative_to(ROOT / "app/src/main/assets")),
        "sha256": digest(glb), "images": images,
        "animations": [a.get("name", "") for a in doc.get("animations", [])],
        "skins": len(doc.get("skins", [])), "units": "meters", "upAxis": "Y",
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, relative in SELECTED.items():
        item = package(SOURCE / relative, OUT / f"{name}.glb")
        manifest.append(item)
        print(f"Packed {name}: {len(item['images'])} texture references", flush=True)
    robot = ROOT / "assets-src/quaternius/robot-expressive.glb"
    shutil.copyfile(robot, OUT / "robot.glb")
    data = robot.read_bytes()
    json_length = struct.unpack_from("<I", data, 12)[0]
    doc = json.loads(data[20:20 + json_length])
    manifest.append({
        "file": "models/outpost/robot.glb", "source": str(robot.relative_to(ROOT)),
        "sha256": digest(data), "sourceSha256": digest(data),
        "animations": [a["name"] for a in doc["animations"]], "skins": len(doc["skins"]),
        "units": "meters", "upAxis": "Y", "license": "CC0",
    })
    with ZipFile(ROOT / "assets-src/kenney/blaster-kit-2.1.zip") as z:
        name = "Models/GLB format/blaster-d.glb"
        data = z.read(name)
        (ROOT / "app/src/main/assets/models/blaster-d.glb").write_bytes(data)
        manifest.append({
            "file": "models/blaster-d.glb", "source": name, "sha256": digest(data),
            "license": "CC0", "units": "meters", "upAxis": "Y",
        })
    (ROOT / "assets-src/quaternius/v2-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    referenced = {image["file"] for item in manifest for image in item.get("images", [])}
    for file in (OUT / "textures").glob("*.png"):
        if str(file.relative_to(OUT)) not in referenced:
            file.unlink()
    print(f"Prepared {len(manifest)} models, shared textures bounded to 1024px", flush=True)


if __name__ == "__main__":
    main()
