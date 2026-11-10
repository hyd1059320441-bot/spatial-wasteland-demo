"""Keep licensed CC0 skeleton/animation buffers verbatim; apply the wasteland comic palette."""
import hashlib
import json
from pathlib import Path
import struct

root = Path(__file__).resolve().parents[1]
source = root / "assets-src/quaternius/robot-expressive.glb"
blob = source.read_bytes()
length = struct.unpack_from("<I", blob, 12)[0]
document = json.loads(blob[20:20 + length])
palette = {
    "Main": [0.75, 0.24, 0.025, 1],
    "Grey": [0.18, 0.21, 0.25, 1],
    "Black": [0.012, 0.009, 0.007, 1],
}
for material in document["materials"]:
    pbr = material.setdefault("pbrMetallicRoughness", {})
    pbr.update(baseColorFactor=palette[material["name"]], roughnessFactor=0.95, metallicFactor=0.15)
text = json.dumps(document, separators=(",", ":")).encode()
text += b" " * (-len(text) % 4)
tail = blob[20 + length:]
output = struct.pack("<4sII", b"glTF", 2, 20 + len(text) + len(tail))
output += struct.pack("<I4s", len(text), b"JSON") + text + tail
target = root / "app/src/main/assets/models/v3/RiggedRobotComic.glb"
target.write_bytes(output)
print(json.dumps({
    "source_sha256": hashlib.sha256(blob).hexdigest(),
    "runtime_sha256": hashlib.sha256(output).hexdigest(),
    "skins": len(document["skins"]),
    "animations": [a["name"] for a in document["animations"]],
    "buffer_bytes_unchanged": blob[20 + length:] == output[20 + len(text):],
}, indent=2))
