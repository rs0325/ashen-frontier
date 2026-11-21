"""自然物カタログから16px素材と確認シートを再生成する。"""
import hashlib
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CATALOG = json.loads((ROOT / "core/src/main/resources/catalog/nature.json").read_text(encoding="utf-8"))
ENTRIES = {entry["id"]: entry for entry in CATALOG["entries"]}
OUT = ROOT / "core/src/main/resources/assets/ashen_frontier/textures"
ATLAS = Image.open(HERE / "source-atlas.png").convert("RGBA")


def sprite(index):
    x, y = index % 8, index // 8
    width, height = ATLAS.size
    tile = ATLAS.crop((x * width // 8, y * height // 8, (x+1) * width // 8, (y+1) * height // 8))
    tile = tile.resize((16, 16), Image.Resampling.BOX)
    tile.putalpha(tile.getchannel("A").point(lambda a: 255 if a >= 160 else 0))
    return tile


def noise(id, color, layered=False, size=16):
    rng = random.Random(int.from_bytes(hashlib.sha256(id.encode()).digest()[:8], "big"))
    base = tuple(bytes.fromhex(color))
    image = Image.new("RGBA", (size, size))
    for y in range(size):
        band = (y % 5 - 2) * 5 if layered else 0
        for x in range(size):
            delta = rng.choice([-15, -8, -3, 0, 3, 8, 15]) + band
            image.putpixel((x, y), tuple(max(0, min(255, c + delta)) for c in base) + (255,))
    return image


def ore(entry):
    host = entry["host"]
    if host in ENTRIES:
        base = noise(host, ENTRIES[host]["color"], host in ("shale", "slate", "gneiss", "quartzite"))
    else:
        # バニラ画像は複製せず、同系色の地を独自に描く。
        base = noise(host, {"granite": "a78070", "andesite": "898780", "sandstone": "c6b383"}[host])
    grains = sprite(44)
    rgb = bytes.fromhex(entry["color"])
    for y in range(16):
        for x in range(16):
            r, g, b, alpha = grains.getpixel((x, y))
            if alpha:
                light = (r + g + b) / (3 * 160)
                grains.putpixel((x, y), tuple(min(255, round(c * light)) for c in rgb) + (alpha,))
    return Image.alpha_composite(base, grains)


def fluid(entry, flowing):
    size = 32 if flowing else 16
    image = noise(entry["id"] + str(flowing), entry["color"], size=size)
    for y in range(size):
        for x in range(size):
            r, g, b, _ = image.getpixel((x, y))
            wave = round(6 * math.sin(2 * math.pi * (y / size + x / size)))
            image.putpixel((x, y), tuple(max(0, min(255, c+wave)) for c in (r, g, b))
                           + (235 if entry["kind"] == "water" else 255,))
    return image


def main():
    previews = []
    for entry in ENTRIES.values():
        id, kind = entry["id"], entry["kind"]
        if kind in ("water", "oil"):
            for suffix in ("still", "flow"):
                image = fluid(entry, suffix == "flow")
                target = OUT / "block" / f"{id}_{suffix}.png"
                target.parent.mkdir(parents=True, exist_ok=True)
                image.save(target)
                previews.append((id + "_" + suffix, image.resize((16, 16), Image.Resampling.BOX)))
            continue
        if kind == "ore":
            image = ore(entry)
        elif "sprite" in entry:
            image = sprite(entry["sprite"])
        else:
            image = noise(id, entry["color"], kind == "rock" and id in ("shale", "slate", "gneiss", "quartzite"))
        target = OUT / ("item" if kind == "item" else "block") / f"{id}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        image.save(target)
        previews.append((id, image))
    sheet = Image.new("RGB", (8 * 160, math.ceil(len(previews)/8) * 100), "#272727")
    draw = ImageDraw.Draw(sheet)
    for i, (id, image) in enumerate(previews):
        x, y = i % 8 * 160, i // 8 * 100
        expanded = image.resize((64, 64), Image.Resampling.NEAREST)
        sheet.paste(expanded, (x+48, y), expanded)
        draw.text((x+2, y+66), id[:25], fill="white")
        if len(id) > 25:
            draw.text((x+2, y+80), id[25:], fill="white")
    sheet.save(HERE / "preview.png")
    print(f"{len(previews)}枚を生成")


if __name__ == "__main__":
    main()
