"""Splits the multi-object source sheets in assets/ into individual trimmed sprites.

Each source sheet is a transparent PNG/WebP holding one or more objects laid out in a
row. Columns whose alpha is fully transparent are treated as separators, so every
object becomes its own tightly cropped file in assets/sprites/.

Run from the project root:  python tools/slice_assets.py
"""

import json
import os
import glob

from PIL import Image

SRC = os.path.join("assets")
OUT = os.path.join("assets", "sprites")

# Sheets that are photographic backgrounds / full screens: never sliced.
SKIP = {
    "Vertical_Loading_Screen",
    "Horizontal_Loading_Screen",
    "Olympus_Background_asset",
    "Ancient_Temple_Background_asset",
    "Celestial_Olympus_Arena_Background_asset",
    "Cloud_Island_Background_asset",
    "Golden_Temple_Background_asset",
    "Stormy_Olympus_Sky_Background_asset",
    "Zeus_Sanctuary_Background_asset",
}

ALPHA_THRESHOLD = 12
MIN_RUN = 24  # ignore specks narrower than this

# Sheets whose objects touch or overlap, so automatic gap detection cannot separate
# them. Value is (columns, rows) of an even grid that is cut and then trimmed.
GRID_OVERRIDES = {
    "Marble_Tile_Set_asset": (2, 2),
    "Olympus_Mountain_Silhouette_Set_asset": (2, 2),
    "Ancient_Channels_Set_1_asset": (4, 1),
    "One_Way_Channels_Set_asset": (4, 2),
}


def column_runs(alpha, width, height):
    """Return [(x0, x1)] ranges of columns that contain visible pixels."""
    data = alpha.load()
    occupied = []
    for x in range(width):
        hit = False
        for y in range(height):
            if data[x, y] > ALPHA_THRESHOLD:
                hit = True
                break
        occupied.append(hit)

    runs = []
    start = None
    for x in range(width):
        if occupied[x] and start is None:
            start = x
        elif not occupied[x] and start is not None:
            runs.append((start, x))
            start = None
    if start is not None:
        runs.append((start, width))
    return [r for r in runs if r[1] - r[0] >= MIN_RUN]


def row_runs(alpha, box):
    """Return [(y0, y1)] ranges of rows with visible pixels inside box."""
    x0, x1 = box
    data = alpha.load()
    height = alpha.size[1]
    occupied = []
    for y in range(height):
        hit = False
        for x in range(x0, x1):
            if data[x, y] > ALPHA_THRESHOLD:
                hit = True
                break
        occupied.append(hit)
    runs = []
    start = None
    for y in range(height):
        if occupied[y] and start is None:
            start = y
        elif not occupied[y] and start is not None:
            runs.append((start, y))
            start = None
    if start is not None:
        runs.append((start, height))
    return [r for r in runs if r[1] - r[0] >= MIN_RUN]


# Sprites drawn large on screen keep more pixels; everything else is a small
# board object and would only waste texture memory at source resolution.
HERO = {
    "Zeus_Main_Character_asset",
    "Great_Thunder_Crest_asset",
    "Game_Name",
    "Final_Olympus_Crystal_asset",
    "Central_Zeus_Emblem_asset",
    "Sky_Island_asset",
    "Sacred_Olympus_Altar_asset",
    "Thunder_Crest_Fragment_asset",
    "Golden_Laurel_Wreath_asset",
}
MAX_DIM = 384
HERO_MAX_DIM = 900


def write_pieces(img, name, pieces, manifest, sort=True):
    if sort:
        # Order pieces top-to-bottom then left-to-right so numbering is predictable.
        pieces = sorted(pieces, key=lambda b: (round(b[1] / 120), b[0]))
    base = name.replace("_asset", "")
    out_names = []
    limit = HERO_MAX_DIM if name in HERO else MAX_DIM
    for i, box in enumerate(pieces, start=1):
        piece = img.crop(box)
        if max(piece.size) > limit:
            piece.thumbnail((limit, limit), Image.LANCZOS)
        fname = f"{base}_{i}.webp" if len(pieces) > 1 else f"{base}.webp"
        piece.save(os.path.join(OUT, fname), "WEBP", quality=92, method=5)
        out_names.append(fname)
    manifest[name] = out_names
    print(f"{name}: {len(out_names)} piece(s) -> {out_names}")


def main():
    os.makedirs(OUT, exist_ok=True)
    manifest = {}

    for path in sorted(glob.glob(os.path.join(SRC, "*.webp"))):
        name = os.path.splitext(os.path.basename(path))[0]
        if name in SKIP:
            continue
        img = Image.open(path).convert("RGBA")
        w, h = img.size
        alpha = img.getchannel("A")

        if name in GRID_OVERRIDES:
            gc, gr = GRID_OVERRIDES[name]
            pieces = []
            for r in range(gr):
                for c in range(gc):
                    cell = img.crop(
                        (w * c // gc, h * r // gr, w * (c + 1) // gc, h * (r + 1) // gr)
                    )
                    bbox = cell.getbbox()
                    if bbox and bbox[2] - bbox[0] >= MIN_RUN:
                        pieces.append(
                            (
                                w * c // gc + bbox[0],
                                h * r // gr + bbox[1],
                                w * c // gc + bbox[2],
                                h * r // gr + bbox[3],
                            )
                        )
            write_pieces(img, name, pieces, manifest, sort=False)
            continue

        cols = column_runs(alpha, w, h)
        if not cols:
            continue

        pieces = []
        for cx0, cx1 in cols:
            rows = row_runs(alpha, (cx0, cx1))
            if len(rows) <= 1:
                pieces.append((cx0, rows[0][0], cx1, rows[0][1]) if rows else None)
            else:
                # Stacked layout (e.g. 2x2 grids) -> one piece per row band.
                for ry0, ry1 in rows:
                    band = img.crop((cx0, ry0, cx1, ry1))
                    bbox = band.getbbox()
                    if bbox:
                        pieces.append(
                            (cx0 + bbox[0], ry0 + bbox[1], cx0 + bbox[2], ry0 + bbox[3])
                        )
        pieces = [p for p in pieces if p]
        write_pieces(img, name, pieces, manifest)

    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)


if __name__ == "__main__":
    main()
