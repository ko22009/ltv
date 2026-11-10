"""TV banner from the picture: letters and arc cut out and put on a smooth dark plate with a glow (no texture of the source picture)."""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SRC = Path(sys.argv[1])
RES = Path(sys.argv[2])
OUT = Path(sys.argv[3])

a = np.asarray(Image.open(SRC).convert("RGB")).astype(float)
block = a[235:610, 930:1620]  # letters and arc of the wide design
W, H = 640, 360

yy, xx = np.mgrid[0:H, 0:W].astype(float)
top = np.array([12, 15, 19.])
bottom = np.array([8, 10, 13.])
plate = top + (bottom - top) * (yy / H)[..., None]
# one glow for both pictures, set in shares of the picture so the square and the wide one get the same shape:
# the source sits in the middle of the lower edge, a little beyond it; wide and soft, the brightest at the lower border,
# fading smoothly upwards and to the sides. It is part of the plate, so the letters and the arc are drawn over it untouched.
def glow_layer(width, height):
    yy, xx = np.mgrid[0:height, 0:width].astype(float)
    dx = (xx - width * 0.5) / (width * 0.60)
    dy = (yy - height * 1.04) / (height * 0.42)
    fall = np.exp(-3.0 * (dx * dx + dy * dy))
    return fall[..., None] * np.array([0, 235, 195.]) * 0.92
glow = glow_layer(W, H)
plate = 255 - (255 - plate) * (255 - glow) / 255

width = round(W * 0.60)
scale = width / block.shape[1]
height = round(block.shape[0] * scale)
small = np.asarray(Image.fromarray(block.astype(np.uint8)).resize((width, height), Image.LANCZOS)).astype(float)
x0 = (W - width) // 2
y0 = round(H * 0.49 - height * 0.40)  # the middle of the letters (not of the letters with the arc) stands at 49% of the height
alpha = np.clip((small.max(axis=2) - 70) / 90.0, 0, 1)[..., None]
out = plate.copy()
region = out[y0 : y0 + height, x0 : x0 + width]
out[y0 : y0 + height, x0 : x0 + width] = region * (1 - alpha) + small * alpha
img = Image.fromarray(out.round().astype(np.uint8), "RGB")
img.save(RES / "drawable-xhdpi" / "banner.png")
img.save(OUT / "banner_preview.png")
print("done", x0, y0, width, height)
