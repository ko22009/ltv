"""Phone icon from the picture: the letters and the arc are cut out and put on a dark plate with a stronger glow, with air at the edges."""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

SRC = Path(sys.argv[1])
RES = Path(sys.argv[2])
OUT = Path(sys.argv[3])

im = Image.open(SRC).convert("RGB")
a = np.asarray(im).astype(float)
sq = a[128:692, 101:644]  # the square design
# the letters and the arc (relative to the design)
block = sq[150:440, 60:505]
LAYER = 432
# the picture was drawn for a 80% crop of the layer (the old icons); a launcher shows 66.7% of an adaptive icon, so everything is
# scaled around the centre by this factor and both kinds of icon frame the picture the same way
K = 0.667 / 0.80

# ---- the plate: a dark vertical gradient and a teal glow rising from the bottom edge
yy, xx = np.mgrid[0:LAYER, 0:LAYER].astype(float)
top = np.array([12, 15, 19.])
bottom = np.array([8, 10, 13.])
plate = top + (bottom - top) * (yy / LAYER)[..., None]
# the glow of the phone icon: a rounder, stronger ellipse that stands high enough to show inside the round mask
yy, xx = np.mgrid[0:LAYER, 0:LAYER].astype(float)
dx = (xx - LAYER / 2) / (190.0 * K)
dy = (yy - (LAYER * (0.5 + 0.34 * K))) / (115.0 * K)
fall = np.clip(1 - np.sqrt(dx * dx + dy * dy), 0, 1) ** 1.3
glow = fall[..., None] * np.array([0, 235, 195.]) * 0.95
plate = 255 - (255 - plate) * (255 - glow) / 255  # screen blend

# ---- the letters and the arc
width = round(LAYER * 0.47 * K)
scale = width / block.shape[1]
height = round(block.shape[0] * scale)
small = np.asarray(Image.fromarray(block.astype(np.uint8)).resize((width, height), Image.LANCZOS)).astype(float)
x0 = (LAYER - width) // 2
y0 = round(LAYER * (0.5 - 0.01 * K) - height * 0.37)  # the middle of the letters (not of the letters with the arc) stands at 49% of the height
layer = plate.copy()
region = layer[y0 : y0 + height, x0 : x0 + width]
# only the bright parts (the white letters, the teal arc) are taken from the cut-out: the soft dark edge of the arc leaves no box
alpha = np.clip((small.max(axis=2) - 70) / 90.0, 0, 1)[..., None]
layer[y0 : y0 + height, x0 : x0 + width] = region * (1 - alpha) + small * alpha
layer_img = Image.fromarray(layer.round().astype(np.uint8), "RGB")
layer_img.save(OUT / "adaptive_preview.png")

for density, size in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
    layer_img.resize((size, size), Image.LANCZOS).save(RES / f"mipmap-{density}" / "ic_launcher_background.png")


def rounded_mask(size, radius):
    big = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=radius * 4, fill=255)
    return big.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    big = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


# the old (non-adaptive) icons: the middle of the same picture, so the letters keep the same air
crop = 0.667  # the part of the layer a launcher shows of an adaptive icon (72 of 108 dp): the old icons must frame the picture the same way
c0 = round(LAYER * (1 - crop) / 2)
c1 = LAYER - c0
legacy = layer_img.crop((c0, c0, c1, c1))
for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    base = legacy.resize((size, size), Image.LANCZOS).convert("RGBA")
    rounded = base.copy()
    rounded.putalpha(rounded_mask(size, round(size * 0.22)))
    rounded.save(RES / f"mipmap-{density}" / "ic_launcher.png")
    circle = base.copy()
    circle.putalpha(circle_mask(size))
    circle.save(RES / f"mipmap-{density}" / "ic_launcher_round.png")
print("done")
