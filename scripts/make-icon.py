"""Draws the FourSight icon (the Retro Sunset brand): a burnt-orange sun on dark brown, cut by four
cream stripes, one per platform. Pure shapes, no fonts. Writes the PNG the window uses and the ICO
the Windows installer uses.

    python scripts/make-icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

BROWN = (43, 27, 18, 255)  # #2B1B12
ORANGE = (224, 112, 47, 255)  # #E0702F
CREAM = (243, 227, 195, 255)  # #F3E3C3
SIZE = 1024
root = Path(__file__).resolve().parent.parent


def draw(size: int = SIZE) -> Image.Image:
    s = size / SIZE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(224 * s), fill=BROWN)

    # The sun: a disc whose lower part sits behind the horizon line.
    cx, cy, r = 512 * s, 560 * s, 330 * s
    sun = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sun)
    sd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ORANGE)
    # Four stripes cut through the lower half, thicker toward the horizon (the retro look).
    stripes = [(590, 22), (660, 30), (730, 38), (800, 46)]
    for y, h in stripes:
        sd.rectangle([0, (y - h / 2) * s, size, (y + h / 2) * s], fill=(0, 0, 0, 0))
    # Clip at the horizon.
    sd.rectangle([0, 840 * s, size, size], fill=(0, 0, 0, 0))
    img.alpha_composite(sun)

    # Horizon line in cream.
    d.rounded_rectangle([150 * s, 850 * s, 874 * s, 878 * s], radius=int(14 * s), fill=CREAM)
    return img


def main() -> None:
    big = draw()
    out_png = root / "desktopApp" / "src" / "main" / "resources" / "foursight-icon.png"
    out_png.parent.mkdir(parents=True, exist_ok=True)
    big.resize((512, 512), Image.LANCZOS).save(out_png)
    out_ico = root / "desktopApp" / "icons" / "foursight.ico"
    out_ico.parent.mkdir(parents=True, exist_ok=True)
    big.save(out_ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("wrote", out_png, "and", out_ico)


if __name__ == "__main__":
    main()
