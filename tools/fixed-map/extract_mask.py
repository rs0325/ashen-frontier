"""参考図の36,000四方の作図範囲を抽出する。出力PNGは手で修正できる制作入力。"""

import json
import hashlib
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
INPUT = Path(__file__).with_name("input")


def components(mask):
    """4近傍の連結成分。色分類後の小領域処理だけに使用する。"""
    seen = np.zeros(mask.shape, bool)
    h, w = mask.shape
    for y, x in zip(*np.nonzero(mask)):
        if seen[y, x]:
            continue
        queue = deque([(int(y), int(x))])
        seen[y, x] = True
        pixels = []
        while queue:
            a, b = queue.popleft()
            pixels.append((a, b))
            for c, d in ((a-1, b), (a+1, b), (a, b-1), (a, b+1)):
                if 0 <= c < h and 0 <= d < w and mask[c, d] and not seen[c, d]:
                    seen[c, d] = True
                    queue.append((c, d))
        yield np.array(pixels)


def main():
    INPUT.mkdir(exist_ok=True)
    source = ROOT / "docs/design/images/map-reference-v1.webp"
    # 目盛文字は図の内側にある。海岸の張り出しと外周の海を含む作図範囲を使う。
    crop = (256, 27, 1212, 930)
    rgb = np.array(Image.open(source).convert("RGB").crop(crop), dtype=np.float32)
    r, g, b = rgb.transpose(2, 0, 1)
    # 濃紺の海を基準にする。明るい氷床や赤い陸地を海に分類しない。
    sea = (b-r > 20) & (b-g > 6) & (r < 80)
    land = ~sea & (np.maximum.reduce([r,g,b]) > 25)
    image = Image.fromarray(land.astype("uint8") * 255)
    draw = ImageDraw.Draw(image)
    boxes = [(716, 412, 846, 458), (881, 701, 1160, 793),
                (936, 106, 1026, 132), (1073, 171, 1207, 190),
                (790, 228, 891, 256), (432, 513, 519, 540),
                (600, 569, 706, 598), (650, 497, 754, 530),
                (961, 550, 1066, 581), (1076, 640, 1160, 670),
                (881, 813, 1120, 846), (256,54,279,81),
                (256,195,279,225), (256,337,279,367),
                (256,474,281,499), (256,610,279,638),
                (256,750,279,780), (256,897,280,926),
                (1171,35,1216,106), (1207,550,1212,930),
                (1209,27,1212,550), (256,927,500,930), (650,927,1212,930),
                (1197,750,1212,930), (256,27,1212,29)]
    for box in boxes:
        draw.rectangle(tuple(v - crop[i % 2] for i, v in enumerate(box)), fill=0)
    arrow = [(640,354),(655,363),(680,392),(698,425),(711,458),(728,480),(760,501),(788,524),(813,555),(843,579),(870,598),(888,626),(905,666),(918,699),(927,718)]
    draw.line([(x-crop[0],y-crop[1]) for x,y in arrow], fill=0, width=18)
    draw.line([(0,487-crop[1]),(910-crop[0],487-crop[1])], fill=0, width=5)
    image = image.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))
    image = image.filter(ImageFilter.MedianFilter(3))
    corrections = json.loads((INPUT/"corrections.json").read_text(encoding="utf-8"))
    draw = ImageDraw.Draw(image)
    for correction in corrections["land_polygons"]:
        draw.polygon([(x-crop[0],y-crop[1]) for x,y in correction["points"]],fill=255)
    land = np.array(image) > 0
    for points in components(~land):
        if np.all(points > 0) and np.all(points[:, 0] < land.shape[0]-1) and np.all(points[:, 1] < land.shape[1]-1):
            land[points[:, 0], points[:, 1]] = True
    groups = sorted(components(land), key=len, reverse=True)
    labels = np.zeros(land.shape, "uint8")
    # 三大陸は面積順でなく位置で識別する。
    for points in groups:
        if len(points) < 12:
            continue
        cy, cx = points.mean(axis=0)
        label = (1 if cx < 570 and cy < 440 else 2 if cx >= 570 and cy < 540 else 3) if len(points) > 15000 else 4
        labels[points[:, 0], points[:, 1]] = label
    Image.fromarray(labels).resize((2048, 2048), Image.Resampling.NEAREST).save(INPUT / "land.png")
    metadata = {"source": source.relative_to(ROOT).as_posix(), "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "crop_xyxy": crop,
                "source_extent_blocks": 36000, "extent_blocks": 32768,
                "scale": 32768/36000, "north": "-Z", "latitude_bounds": [80, -80],
                "labels": {"0": "海 / Ocean", "1": "北西大陸 / Northwest", "2": "東大陸 / East", "3": "南大陸 / South", "4": "島嶼 / Islands"},
                "cleanup": {"closing_pixels": 3, "median_pixels": 3, "fill_enclosed_holes": True, "remove_components_below_pixels": 12},
                "manual_exclusions_source_xyxy": boxes,
                "manual_arrow_source_xy": arrow}
    region_reference = ROOT / "docs/design/images/m2-northwest-v1.png"
    metadata["region_reference"] = region_reference.relative_to(ROOT).as_posix()
    metadata["region_reference_sha256"] = hashlib.sha256(region_reference.read_bytes()).hexdigest()
    colors = np.array([[5, 30, 45], [110, 170, 100], [200, 160, 90], [160, 170, 90], [160, 190, 210]], "uint8")
    preview = Path(__file__).with_name("preview")
    preview.mkdir(exist_ok=True)
    Image.fromarray(colors[labels]).save(preview / "mask.png")
    reference = np.array(Image.open(source).convert("RGB").crop(crop))
    overlay = (reference.astype(float)*.65 + colors[labels]*.35).astype("uint8")
    edge = np.array(Image.fromarray((labels>0).astype("uint8")*255).filter(ImageFilter.FIND_EDGES))>0
    overlay[edge] = [255,30,180]
    Image.fromarray(overlay).save(preview / "reference-overlay.png")
    comparison = Image.new("RGB",(reference.shape[1]*3,reference.shape[0]))
    for i,data in enumerate([reference,colors[labels],overlay]):
        comparison.paste(Image.fromarray(data),(i*reference.shape[1],0))
    comparison.save(preview / "reference-comparison.png")
    counts = np.bincount(labels.ravel(), minlength=5)[1:]
    metadata["source_land_pixels"] = counts.tolist()
    metadata["manual_land_corrections"] = "tools/fixed-map/input/corrections.json"
    (INPUT / "extraction.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print("land ratios:", counts / counts.sum())


if __name__ == "__main__":
    main()
