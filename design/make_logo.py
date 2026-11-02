"""Turns design/logo_new.png into the app assets: TV banner, launcher icon and the transparent in-app mark."""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design" / "logo_new.png"
RES = ROOT / "app" / "src" / "main" / "res"
BG = np.array([12, 48, 30])  # the flat background of the source picture

source = Image.open(SRC).convert("RGB")
pixels = np.asarray(source).astype(int)

# ---- the banner: the picture as it is, just scaled to the size Android TV asks for
RES.joinpath("drawable-xhdpi").mkdir(parents=True, exist_ok=True)
source.resize((640, 360), Image.LANCZOS).save(RES / "drawable-xhdpi" / "banner.png")


def keyed(box):
    """Crops the box and makes the flat background (and its dot pattern) transparent, edges kept soft."""
    x0, y0, x1, y1 = box
    crop = pixels[y0:y1, x0:x1]
    distance = np.abs(crop - BG).sum(axis=2)
    alpha = np.clip((distance - 50) / 20.0, 0.0, 1.0) * 255
    out = np.dstack([crop, alpha]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


# the whole mark: "Lazy" and the television
keyed((283, 291, 1402, 675)).save(RES / "drawable-xhdpi" / "logo.png")

# ---- the launcher icon: the television alone on the same green, corners rounded like the old one
television = keyed((996, 291, 1402, 675))
icon = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
rounded = Image.new("L", (192, 192), 0)
ImageDraw.Draw(rounded).rounded_rectangle((0, 0, 191, 191), radius=42, fill=255)
icon.paste(Image.new("RGBA", (192, 192), tuple(BG) + (255,)), (0, 0), rounded)
scale = 150 / television.width
tv = television.resize((150, round(television.height * scale)), Image.LANCZOS)
icon.alpha_composite(tv, ((192 - tv.width) // 2, (192 - tv.height) // 2))
icon.save(RES / "mipmap-xxhdpi" / "ic_launcher.png")

print("banner", source.size, "->", (640, 360))
print("logo", television.size, "icon", icon.size)
