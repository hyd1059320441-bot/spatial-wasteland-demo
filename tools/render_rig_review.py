"""Render exported GLB CPU poses and create two inspectable review pages."""
import argparse
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("poses", type=Path)
args = parser.parse_args()
cases = ["Bind", "Idle", "WalkLeft", "WalkRight", "Fire", "Death"]
for case in cases:
    subprocess.run([str(ROOT / ".tools/render_source_asset_v8"),
        str(args.poses / (case + ".usdz")), str(args.poses / (case + "-views"))], check=True)
for page, selected in enumerate([cases[:3], cases[3:]], 1):
    image = Image.new("RGB", (1800, 1860), "#1b2430")
    draw = ImageDraw.Draw(image)
    for row, case in enumerate(selected):
        for col, view in enumerate(["plusZ", "frontThreeQuarter"]):
            frame = Image.open(args.poses / (case + "-views") / (view + ".png"))
            frame.thumbnail((900, 600), Image.Resampling.LANCZOS)
            image.paste(frame, (col * 900 + (900 - frame.width) // 2, row * 620 + 20))
            draw.text((col * 900 + 12, row * 620 + 4), case + " / " + view, fill="white")
    output = args.poses / f"pose-review-{page}.png"
    image.save(output)
    print(output, flush=True)
