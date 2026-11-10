"""Original compact mechanical head-hit confirmation; do not rewrite existing audio."""
import hashlib
import math
import random
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
target = ROOT / "app/src/main/assets/audio/headshot.wav"
rate = 22050
rng = random.Random(1108)
samples = []
for i in range(int(0.28 * rate)):
    t = i / rate
    snap = rng.uniform(-1, 1) * math.exp(-110 * t) * 0.5
    tone = 0.0
    for delay, frequency in ((0.015, 1480), (0.07, 2093)):
        local = t - delay
        if local >= 0:
            tone += (math.sin(2 * math.pi * frequency * local) +
                     0.2 * math.sin(2 * math.pi * frequency * 2 * local)) * (
                     1 - math.exp(-180 * local)) * math.exp(-25 * local) * 0.28
    samples.append(struct.pack("<h", int(max(-0.95, min(0.95, snap + tone)) * 26000)))
with wave.open(str(target), "wb") as output:
    output.setparams((1, 2, rate, 0, "NONE", "not compressed"))
    output.writeframes(b"".join(samples))
print(target, target.stat().st_size, hashlib.sha256(target.read_bytes()).hexdigest())
