"""カタログとGPT image原本から遺物素材・市松確認シートを再生成する。"""
import hashlib
import json
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps
from ruins import ruin_tile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ENTRIES = json.loads((ROOT / "core/src/main/resources/catalog/relics.json").read_text(encoding="utf-8"))["entries"]
OUT = ROOT / "core/src/main/resources/assets/ashen_frontier/textures"


def cell(atlas, index, columns, rows):
    x, y = index % columns, index // columns
    w, h = atlas.size
    return atlas.crop((x*w//columns, y*h//rows, (x+1)*w//columns, (y+1)*h//rows))


def item_sprite(tile):
    # 白い物体の内部を抜かず、縮小前に専用のマゼンタ背景とその縁を除去する。
    pixels = tile.load()
    for y in range(tile.height):
        for x in range(tile.width):
            r, g, b, a = pixels[x, y]
            if min(r, b) - g > 80:
                pixels[x, y] = (0, 0, 0, 0)
    bounds = tile.getbbox()
    if bounds is None:
        raise ValueError("アイテム原本のセルが空です")
    tile = ImageOps.contain(tile.crop(bounds), (14, 14), Image.Resampling.BOX)
    tile.putalpha(tile.getchannel("A").point(lambda a: 255 if a >= 128 else 0))
    image = Image.new("RGBA", (16, 16))
    image.alpha_composite(tile, ((16-tile.width)//2, (16-tile.height)//2))
    return image


def casing(entry, panel, top):
    # 原本の筐体の色を引き継ぎ、側面・上面は規則的な継ぎ目・通気孔・ボルトで埋める。
    base = panel.convert("RGB").resize((1, 1), Image.Resampling.BOX).getpixel((0, 0))
    rng = random.Random(entry["id"] + str(top))
    image = Image.new("RGBA", (32, 32))
    for y in range(32):
        for x in range(32):
            delta = rng.choice((-9, -4, 0, 4, 9))
            image.putpixel((x, y), tuple(max(20, min(190, c+delta)) for c in base) + (255,))
    draw = ImageDraw.Draw(image)
    draw.rectangle((1, 1, 30, 30), outline=(35, 36, 34, 255))
    draw.line((3, 28, 28, 28), fill=(110, 103, 88, 255))
    for x in (3, 28):
        for y in (3, 28):
            draw.point((x, y), fill=(163, 155, 132, 255))
    if top:
        draw.rectangle((8, 7, 23, 24), outline=(35, 36, 34, 255))
        draw.line((10, 9, 21, 9), fill=(140, 130, 110, 255))
    else:
        for y in range(10, 24, 3):
            draw.line((8, y, 23, y), fill=(28, 30, 29, 255))
    return image


def checker(size):
    image = Image.new("RGBA", size)
    draw = ImageDraw.Draw(image)
    for y in range(0, size[1], 8):
        for x in range(0, size[0], 8):
            color = (99, 104, 111, 255) if (x//8+y//8) % 2 else (55, 59, 65, 255)
            draw.rectangle((x, y, x+7, y+7), fill=color)
    return image


def main():
    structures = Image.open(HERE / "source-atlas.png").convert("RGBA")
    items = Image.open(HERE / "item-atlas.png").convert("RGBA")
    equipment = Image.open(HERE / "equipment-atlas.png").convert("RGBA")
    sheet = Image.new("RGB", (8*184, math.ceil(len(ENTRIES)/8)*116), "#282828")
    draw = ImageDraw.Draw(sheet)
    equipment_sheet = Image.new("RGB", (8*184, 4*190), "#282828")
    equipment_draw = ImageDraw.Draw(equipment_sheet)
    ruins = Image.open(HERE / "ruins-source-atlas.png").convert("RGBA")
    ruins_sheet = Image.new("RGB", (8*184, 8*140), "#282828")
    ruins_draw = ImageDraw.Draw(ruins_sheet)
    ruin_index = 0
    hashes = {}
    equipment_index = 0
    for i, entry in enumerate(ENTRIES):
        id, kind = entry["id"], entry["kind"]
        if entry.get("shape") is not None:
            image = ruin_tile(entry, ruins)
        elif kind == "item":
            image = item_sprite(cell(items, entry["sprite"], 8, 8))
            digest = hashlib.sha256(image.tobytes()).hexdigest()
            if digest in hashes:
                raise ValueError(f"アイテム素材が重複: {hashes[digest]} / {id}")
            hashes[digest] = id
        elif kind == "equipment":
            image = cell(equipment, entry["sprite"], 8, 4).convert("RGB").resize((32, 32), Image.Resampling.BOX).convert("RGBA")
            side, top = casing(entry, image, False), casing(entry, image, True)
            side.save(OUT / "block" / f"{id}_side.png")
            top.save(OUT / "block" / f"{id}_top.png")
            px, py = equipment_index % 8 * 184, equipment_index // 8 * 190
            for face, offset in ((image, (0, 0)), (side, (68, 0)), (top, (0, 68))):
                equipment_sheet.paste(face.resize((64, 64), Image.Resampling.NEAREST), (px+offset[0], py+offset[1]))
            equipment_draw.text((px, py+136), id[:28], fill="white")
            equipment_draw.text((px, py+150), id[28:], fill="white")
            equipment_draw.text((px, py+164), "front / side / top", fill="#bbbbbb")
            equipment_index += 1
        else:
            tile = cell(structures, entry["sprite"], 8, 8).resize((16, 16), Image.Resampling.BOX)
            image = Image.new("RGBA", (16, 16), (75, 72, 65, 255))
            image.alpha_composite(tile)
            if id.endswith("_window") or id.endswith("_door"):
                frame = ImageDraw.Draw(image)
                frame.rectangle((3, 3, 12, 10 if id.endswith("_door") else 12), fill=(0, 0, 0, 0))
                frame.line((7, 3, 7, 12), fill=(85, 83, 77, 255), width=1)
        if entry.get("shape") is not None:
            rx, ry = ruin_index % 8 * 184, ruin_index // 8 * 140
            ruins_sheet.paste(image.convert("RGB"), (rx, ry))
            enlarged = checker((64,64))
            enlarged.alpha_composite(image.resize((64,64),Image.Resampling.NEAREST))
            ruins_sheet.paste(enlarged.convert("RGB"), (rx+22,ry))
            for ty in range(2):
                for tx in range(2):
                    ruins_sheet.paste(image.convert("RGB"),(rx+94+tx*16,ry+ty*16))
            ruins_draw.text((rx,ry+72),id[:29],fill="white")
            ruins_draw.text((rx,ry+87),id[29:],fill="white")
            ruins_draw.text((rx,ry+105),"16px / 4x / tiling",fill="#bbbbbb")
            ruin_index += 1
        target = OUT / ("item" if kind == "item" else "block") / f"{id}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        image.save(target)
        px, py = i % 8 * 184, i // 8 * 116
        preview = checker((64, 64))
        preview.alpha_composite(image.resize((64, 64), Image.Resampling.NEAREST))
        sheet.paste(preview.convert("RGB"), (px+60, py))
        draw.text((px+2, py+68), id[:29], fill="white")
        draw.text((px+2, py+82), id[29:], fill="white")
    rows = ["# 劣化建材の登録・画像・回収対応表", "",
            "確定（ユーザー承認 2026-11-27、#156）。relics.jsonからgenerate.pyで再生成する。既存IDは変更せず、以下64件を追加する。外観38分類と既存品の再利用は[設計16](design/16-m2-ruins.md)を参照。", "",
            "| ID（ashen_frontier:） | 日本語名 | shape | 原稿セル | 回収先（同名前空間） | 工具 | 画像 |",
            "|---|---|---|---:|---|---|---|"]
    for entry in ENTRIES:
        if entry.get("shape") is None:
            continue
        id = entry["id"]
        tool = "axe" if entry["material"] == "wood" else "pickaxe"
        rows.append(f'| {id} | {entry["ja"]} | {entry["shape"]} | {entry["sprite"]} | {entry["recovery"]} | {tool} | [16px](../core/src/main/resources/assets/ashen_frontier/textures/block/{id}.png) |')
    rows += ["", "全件が硬さ2.0・暫定素材会計1 kg、Blockと同名BlockItem、初回は自身を一回回収。二重ハーフは2個。損傷は静的、専用修理・scrapへの変換なし。"]
    (ROOT / "docs/ruin-materials.md").write_text("\n".join(rows)+"\n", encoding="utf-8")
    sheet.save(HERE / "preview.png")
    equipment_sheet.save(HERE / "equipment-preview.png")
    ruins_sheet.save(HERE / "ruins-preview.png")
    print(f"素材{len(ENTRIES)}件、個別アイテム{len(hashes)}件、設備3面{equipment_index}件を生成")


if __name__ == "__main__":
    main()
