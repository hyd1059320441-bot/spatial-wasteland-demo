"""Texture-only elite derivative and original synthesized arsenal audio."""
import colorsys
import hashlib
import io
import json
import math
import random
import struct
import wave
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "app/src/main/assets/models/v8/HeavyGunnerV9.glb"
data = source.read_bytes()
json_len = struct.unpack_from("<I", data, 12)[0]
document = json.loads(data[20:20 + json_len])
binary_offset = 20 + json_len
bin_len = struct.unpack_from("<I", data, binary_offset)[0]
binary = bytearray(data[binary_offset + 8:binary_offset + 8 + bin_len])
indices = {m["pbrMetallicRoughness"]["baseColorTexture"]["index"]
           for m in document["materials"] if "baseColorTexture" in m.get("pbrMetallicRoughness", {})}
images = {document["textures"][i]["source"] for i in indices}
for index in images:
    item = document["images"][index]
    view = document["bufferViews"][item["bufferView"]]
    offset = view.get("byteOffset", 0)
    image = Image.open(io.BytesIO(binary[offset:offset + view["byteLength"]])).convert("RGBA")
    pixels = []
    for r, g, b, a in image.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if s > 0.18 and v > 0.15:
            # Preserve dark ink/shading. Warm armor becomes purple; cool trim becomes gold.
            hue = 0.77 if h < 0.18 or h > 0.9 else 0.13
            rgb = colorsys.hsv_to_rgb(hue, max(s, 0.72), v)
            pixels.append(tuple(round(c * 255) for c in rgb) + (a,))
        else:
            pixels.append((r, g, b, a))
    image.putdata(pixels)
    stream = io.BytesIO(); image.save(stream, format="PNG", optimize=True)
    while len(binary) % 4:
        binary.append(0)
    image_data = stream.getvalue()
    document["bufferViews"].append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(image_data)})
    item["bufferView"] = len(document["bufferViews"]) - 1; item["mimeType"] = "image/png"
    binary.extend(image_data)
document["buffers"][0]["byteLength"] = len(binary)
encoded = json.dumps(document, separators=(",", ":")).encode()
encoded += b" " * ((-len(encoded)) % 4)
binary.extend(b"\x00" * ((-len(binary)) % 4))
output = ROOT / "app/src/main/assets/models/v12/ElitePurpleGold.glb"
output.parent.mkdir(parents=True, exist_ok=True)
payload = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(encoded) + 8 + len(binary))
payload += struct.pack("<II", len(encoded), 0x4E4F534A) + encoded
payload += struct.pack("<II", len(binary), 0x004E4942) + binary
output.write_bytes(payload)
manifest = {"source": str(source), "sourceSha256": hashlib.sha256(data).hexdigest(),
            "derivative": str(output), "sha256": hashlib.sha256(payload).hexdigest(),
            "change": "Base-color texture hues only; original vertices, joints, clips, sockets and accessor bytes preserved."}

rate = 22050
sounds = {"arc": (0.13, 780), "rail": (0.5, 210), "plasma": (0.42, 130),
          "plasma_impact": (0.55, 70), "pickup": (0.36, 660)}
for name, (duration, frequency) in sounds.items():
    rng = random.Random(name)
    samples = []
    for i in range(int(rate * duration)):
        t = i / rate
        if name == "pickup":
            f = frequency * (1 + int(t / 0.09) * 0.25)
            value = math.sin(2 * math.pi * f * t) * math.exp(-4 * t) * 0.6
        else:
            chirp = frequency * t + (800 if name == "arc" else -frequency * 0.4) * t * t
            tone = math.sin(2 * math.pi * chirp) * (0.5 + 0.15 * math.sin(2 * math.pi * 40 * t))
            noise = rng.uniform(-1, 1) * (0.16 if name == "arc" else 0.35)
            value = (tone + noise) * (1 - math.exp(-400 * t)) * math.exp(-8 * t)
        samples.append(struct.pack("<h", int(max(-0.95, min(0.95, value)) * 26000)))
    target = ROOT / "app/src/main/assets/audio" / (name + ".wav")
    with wave.open(str(target), "wb") as out:
        out.setparams((1, 2, rate, 0, "NONE", "not compressed")); out.writeframes(b"".join(samples))
    manifest.setdefault("originalAudio", []).append({"name": name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
path = ROOT / "assets-src/v12/elite-audio-provenance.json"
path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2))
