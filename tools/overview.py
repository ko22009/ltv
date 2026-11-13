"""One contact sheet of key screens, so the screens are checked once at the end of a task instead of
by a series of single screenshots.

Usage: python -I -X utf8 tools/overview.py <device> [steps-file]
Each line of the steps file: `name | token token ...`; tokens: key:<keycode>, tap:<x>,<y>, wait:<seconds>,
seed:<settings-key>=<json-value> (debug builds only). Every step starts from a fresh app start.
Result: build/overview/<device>.png (build/ is not in git).
"""
import subprocess
import sys
import time
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ADB = r"C:\Android\sdk\platform-tools\adb.exe"
PKG = "ru.lazytv"


def adb(device, *args, binary=False):
    out = subprocess.run([ADB, "-s", device, *args], capture_output=True, check=True,
                         env={**__import__("os").environ, "MSYS_NO_PATHCONV": "1"}).stdout
    return out if binary else out.decode("utf-8", "replace")


def run_step(device, tokens):
    adb(device, "shell", f"am force-stop {PKG}")
    for t in tokens:
        if t.startswith("seed:"):
            key, _, value = t[5:].partition("=")
            adb(device, "shell",
                f"am broadcast -a ru.lazytv.SEED -n {PKG}/.DebugSeed --es key {key} --es value '{value}'")
    adb(device, "shell", f"am start -n {PKG}/.MainActivity")
    time.sleep(5)
    for t in tokens:
        if t.startswith("key:"):
            adb(device, "shell", f"input keyevent {t[4:]}")
            time.sleep(0.6)
        elif t.startswith("tap:"):
            x, y = t[4:].split(",")
            adb(device, "shell", f"input tap {x} {y}")
            time.sleep(1)
        elif t.startswith("wait:"):
            time.sleep(float(t[5:]))
    time.sleep(2)
    return Image.open(BytesIO(adb(device, "exec-out", "screencap", "-p", binary=True))).convert("RGB")


def main():
    device = sys.argv[1]
    steps_file = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "tools" / "overview-steps.txt"
    steps = []
    for line in steps_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            name, _, rest = line.partition("|")
            steps.append((name.strip(), rest.split()))
    shots = [(name, run_step(device, tokens)) for name, tokens in steps]
    cols = 3
    w = 640
    scaled = [(n, im.resize((w, int(im.height * w / im.width)))) for n, im in shots]
    rows = [scaled[i:i + cols] for i in range(0, len(scaled), cols)]
    heights = [max(im.height for _, im in r) + 24 for r in rows]
    sheet = Image.new("RGB", (cols * w, sum(heights)), (30, 30, 30))
    d = ImageDraw.Draw(sheet)
    y = 0
    for r, h in zip(rows, heights):
        for i, (n, im) in enumerate(r):
            d.text((i * w + 6, y + 5), n, fill=(255, 255, 255))
            sheet.paste(im, (i * w, y + 24))
        y += h
    out = ROOT / "build" / "overview"
    out.mkdir(parents=True, exist_ok=True)
    path = out / (device.replace(":", "_") + ".png")
    sheet.save(path)
    print(path)


main()
