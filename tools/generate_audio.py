"""Generate original short mono PCM effects; no third-party sound recordings."""
import math
import random
import struct
import wave
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/audio"
OUT.mkdir(parents=True, exist_ok=True)
RATE = 22050


def make(name, duration, sample):
    rng = random.Random(626)
    values = []
    for i in range(int(duration * RATE)):
        t = i / RATE
        value = max(-1, min(1, sample(t, rng)))
        values.append(struct.pack("<h", int(value * 22000)))
    with wave.open(str(OUT / f"{name}.wav"), "wb") as f:
        f.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        f.writeframes(b"".join(values))


make("shot", 0.16, lambda t, r: (
    0.7 * r.uniform(-1, 1) + 0.3 * math.sin(2 * math.pi * (180 * t - 350 * t * t))
) * math.exp(-32 * t))
make("hit", 0.12, lambda t, r: (
    0.25 * r.uniform(-1, 1) + 0.5 * math.sin(2 * math.pi * 1250 * t)
) * math.exp(-36 * t))
def kill_cue(t, r, elite=False, head=False):
    # Mechanical snap, low core collapse and a short, layered confirmation chord.
    snap = r.uniform(-1, 1) * math.exp(-85 * t) * 0.5
    collapse = math.sin(2 * math.pi * (105 * t - 38 * t * t)) * math.exp(-13 * t) * 0.3
    chord = 0.0
    for delay, frequency in [(0.035, 659.25), (0.105, 987.77), (0.175, 1318.51)]:
        if t >= delay:
            local = t - delay
            chord += (math.sin(2 * math.pi * frequency * local) +
                      0.28 * math.sin(2 * math.pi * frequency * 2 * local)) * (
                      1 - math.exp(-180 * local)) * math.exp(-12 * local) * 0.18
    if head:
        chord += math.sin(2 * math.pi * 1975.53 * t) * math.exp(-14 * t) * 0.14
    if elite:
        collapse += math.sin(2 * math.pi * 65.41 * t) * math.exp(-5 * t) * 0.25
        chord += sum(math.sin(2 * math.pi * f * t) for f in (329.63, 493.88, 659.25)) * (
                     1 - math.exp(-30 * t)) * math.exp(-5 * t) * 0.08
    return snap + collapse + chord


make("kill", 0.55, kill_cue)
make("kill_head", 0.65, lambda t, r: kill_cue(t, r, head=True))
make("kill_elite", 0.95, lambda t, r: kill_cue(t, r, elite=True))
make("core_break", 0.5, lambda t, r: (
    r.uniform(-1, 1) * math.exp(-25 * t) * 0.55 +
    math.sin(2 * math.pi * (240 * t - 160 * t * t)) * math.exp(-12 * t) * 0.25 +
    math.sin(2 * math.pi * 2183 * t) * math.exp(-18 * t) * 0.15))
make("reload", 0.24, lambda t, r: r.uniform(-1, 1) * (
    math.exp(-90 * t) + (math.exp(-90 * (t - 0.17)) if t >= 0.17 else 0)
))
make("hurt", 0.24, lambda t, r: 0.5 * math.sin(
    2 * math.pi * (120 * t - 90 * t * t)
) * math.exp(-12 * t))
make("shotgun", 0.34, lambda t, r: (
    0.8 * r.uniform(-1, 1) + 0.4 * math.sin(2 * math.pi * (85 * t - 50 * t * t))
) * math.exp(-14 * t))
make("explosion", 0.65, lambda t, r: (
    0.75 * r.uniform(-1, 1) + 0.35 * math.sin(2 * math.pi * 55 * t)
) * math.exp(-7 * t))
make("teleport", 0.24, lambda t, r: 0.45 * math.sin(
    2 * math.pi * (300 * t + 1400 * t * t)
) * math.sin(math.pi * t / 0.24))
make("upgrade", 0.48, lambda t, r: 0.35 * math.sin(
    2 * math.pi * (440 if t < 0.16 else 660 if t < 0.32 else 880) * t
) * math.exp(-3 * t))
make("empty", 0.08, lambda t, r: r.uniform(-1, 1) * math.exp(-95 * t))
make("reload_done", 0.16, lambda t, r: (r.uniform(-1, 1) * 0.6 +
     math.sin(2 * math.pi * 560 * t) * 0.25) * math.exp(-45 * t))
make("warning", 0.24, lambda t, r: math.sin(2 * math.pi * (850 + 300 * t) * t) *
     math.sin(math.pi * t / 0.24) * 0.3)
make("baton", 0.3, lambda t, r: r.uniform(-1, 1) * math.sin(math.pi * t / 0.3) *
     math.exp(-8 * t))
make("metal", 0.19, lambda t, r: (math.sin(2 * math.pi * 1937 * t) +
     0.6 * math.sin(2 * math.pi * 2741 * t)) * math.exp(-32 * t) * 0.35)

# Original seamless 16-second, 120 BPM industrial score in E minor.
def music(t, r, combat=False):
    beat = t * 2
    step = int(beat * 2) % 8
    notes = [82.4069, 82.4069, 123.4708, 110.0, 82.4069, 98.0, 73.4162, 110.0]
    phase = (beat * 2) % 1
    bass = math.sin(2 * math.pi * notes[step] * t) * math.exp(-phase * 5) * 0.23
    pad = sum(math.sin(2 * math.pi * f * t) for f in [164.8125, 196.0, 246.9375]) * 0.04
    kick_phase = beat % 1
    kick = math.sin(2 * math.pi * 48 * t) * math.exp(-kick_phase * 23) * 0.34
    # Integer loop frequencies keep boundaries continuous; deterministic noise is enveloped.
    hat = r.uniform(-1, 1) * math.exp(-phase * 32) * (0.16 if combat else 0.04)
    return (kick + hat + bass * 0.5) if combat else bass + pad

make("bgm", 16, lambda t, r: music(t, r))
make("combat_loop", 16, lambda t, r: music(t, r, True))
for name, frequency in [("ambient_entry", 55), ("ambient_quarry", 37),
                        ("ambient_storage", 73), ("ambient_reactor", 110)]:
    make(name, 8, lambda t, r, f=frequency: (
        math.sin(2 * math.pi * f * t) * 0.16 +
        math.sin(2 * math.pi * (f * 2 + 1) * t) * 0.045 +
        r.uniform(-1, 1) * 0.045) * (0.7 + 0.3 * math.cos(2 * math.pi * t / 8)))
