"""Generates full-bleed launcher icons (legacy + adaptive) from assets/Icon.png.

The artwork is a full square, so we scale it edge-to-edge with no padding: the
adaptive foreground fills the entire 108dp canvas, guaranteeing no empty margins
after the launcher mask is applied.
"""

import os
from PIL import Image, ImageDraw

SRC = "assets/Icon.png"
RES = "app/src/main/res"

# density -> (legacy launcher px @48dp, adaptive layer px @108dp)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def cover_resize(img, size):
    """Scale + center-crop so the image fully covers a square of `size`."""
    w, h = img.size
    scale = size / min(w, h)
    nw, nh = max(size, round(w * scale)), max(size, round(h * scale))
    img = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - size) // 2
    top = (nh - size) // 2
    return img.crop((left, top, left + size, top + size))


def rounded(img, radius_ratio=0.5):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main():
    base = Image.open(SRC).convert("RGBA")
    for dens, (legacy, adaptive) in DENSITIES.items():
        mip = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(mip, exist_ok=True)

        # Legacy square icon (edge-to-edge).
        sq = cover_resize(base, legacy)
        sq.save(os.path.join(mip, "ic_launcher.png"))
        # Legacy round icon.
        rounded(cover_resize(base, legacy), 0.5).save(
            os.path.join(mip, "ic_launcher_round.png")
        )

        # Adaptive layers fill the whole 108dp canvas -> no empty edges.
        fg = cover_resize(base, adaptive)
        fg.save(os.path.join(mip, "ic_launcher_foreground.png"))
        fg.save(os.path.join(mip, "ic_launcher_background.png"))
        print(f"{dens}: legacy {legacy}px, adaptive {adaptive}px")

    # Adaptive descriptors.
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8") as fh:
        fh.write(xml)
    with open(os.path.join(anydpi, "ic_launcher_round.xml"), "w", encoding="utf-8") as fh:
        fh.write(xml)
    print("adaptive descriptors written")


if __name__ == "__main__":
    main()
