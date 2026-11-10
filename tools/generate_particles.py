"""Original RGBA sprites for native Spatial SDK particles; no external artwork."""
from pathlib import Path
from PIL import Image
import math

out = Path(__file__).resolve().parents[1] / "app/src/main/assets/textures"
for name in ("particle-flare", "particle-smoke"):
    image = Image.new("RGBA", (128, 128))
    pixels = image.load()
    for y in range(128):
        for x in range(128):
            nx, ny = (x - 63.5) / 63.5, (y - 63.5) / 63.5
            radius = math.hypot(nx, ny)
            if name.endswith("flare"):
                alpha = max(0, 1 - radius) ** 2
                rays = math.exp(-min(abs(nx), abs(ny)) * 24) * max(0, 1 - radius)
                alpha = min(1, alpha + rays * 0.65)
            else:
                turbulence = 0.8 + 0.2 * math.sin(nx * 18 + math.cos(ny * 11))
                alpha = max(0, 1 - radius) ** 1.5 * turbulence
            pixels[x, y] = (255, 255, 255, int(alpha * 255))
    image.save(out / f"{name}.png")
