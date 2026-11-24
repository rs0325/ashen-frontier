"""実列標本の段彩・陰影と、詳細版／描き込み版の共通レイアウト。"""

import csv
import struct

import numpy as np
from PIL import Image, ImageDraw, ImageFont

NAMES = {"spawn": "初期拠点", "agriculture": "旧農業地帯", "mining": "鉱業地帯",
         "arid": "乾燥地", "industry": "工業地帯", "coastal_industry": "沿岸工業",
         "cold_north": "寒冷研究拠点"}
HEIGHTS = np.array([63, 95, 130, 180, 250, 350, 440, 520])
LAND = np.array([[173, 188, 144], [151, 178, 124], [176, 182, 134], [189, 175, 135],
                 [167, 153, 133], [138, 139, 137], [186, 187, 180], [230, 233, 229]])
DEPTHS = np.array([0, 8, 24, 60, 100, 140, 183])
SEA = np.array([[183, 218, 226], [138, 191, 211], [95, 162, 193], [61, 126, 169],
                [42, 91, 143], [32, 65, 115], [22, 43, 86]])
SAMPLE = np.dtype([("ground", ">i2"), ("top", ">i2"), ("material", ">u2"),
                   ("cap", ">u2"), ("flags", "u1")])


def read_samples(output, job):
    data = (output / (job["name"] + ".bin")).read_bytes()
    magic, x0, z0, step, width, height = struct.unpack(">6i", data[:24])
    bounds = [x0, z0, x0 + width * step, z0 + height * step]
    if (magic != 0x4d415031 or step != job["step"] or bounds != list(job["bounds"])
            or len(data) != 24 + width * height * SAMPLE.itemsize):
        raise ValueError(f"標本のヘッダ・サイズが要求と一致しない: {job['name']}")
    samples = np.frombuffer(data, offset=24, dtype=SAMPLE).reshape(height, width)
    palette = (output / (job["name"] + "-palette.txt")).read_text(encoding="utf-8").splitlines()
    return samples, palette


def colors(values, levels, palette):
    return np.stack([np.interp(values, levels, palette[:, i]) for i in range(3)], axis=-1)


def shade(height, step):
    dz, dx = np.gradient(height.astype(np.float32), step)
    normal = np.sqrt(dx * dx + dz * dz + 1)
    light = (.5 * dx + .5 * dz + .7071) / normal
    return np.clip(.63 + .44 * light, .43, 1.12)


def edge(mask):
    result = np.zeros(mask.shape, bool)
    result[1:] |= mask[1:] != mask[:-1]
    result[:, 1:] |= mask[:, 1:] != mask[:, :-1]
    return result


def background(samples, palette, step, pale):
    ground, flags = samples["ground"], samples["flags"]
    wet, sea, fresh = (flags & 3) > 0, (flags & 3) == 1, (flags & 3) == 2
    relief = shade(np.where(wet, samples["top"], ground), step)
    if pale:
        rgb = np.repeat((245 - (1 - relief) * 45)[..., None], 3, axis=-1)
        rgb[sea] = np.array([230, 240, 246]) - (1 - shade(ground, step)[sea, None]) * 18
        rgb[fresh] = [142, 186, 213]
    else:
        rgb = colors(ground, HEIGHTS, LAND)
        rock = np.array([any(s in name for s in ["granite", "gneiss", "quartzite", "andesite",
                                                "diorite", "tuff", "cobble", "stone"])
                         for name in palette])[samples["material"]]
        sand = np.array([name.endswith(("sand", "silt", "loess")) for name in palette])[samples["material"]]
        muddy = np.array([any(s in name for s in ["mud", "peat", "clay"])
                          for name in palette])[samples["material"]]
        rgb[rock] = rgb[rock] * .65 + np.array([150, 146, 137]) * .35
        rgb[sand] = rgb[sand] * .55 + np.array([203, 190, 148]) * .45
        rgb[muddy] = rgb[muddy] * .7 + np.array([132, 142, 118]) * .3
        rgb[(flags & 4) > 0] = [236, 240, 239]
        rgb[(flags & 8) > 0] = [184, 213, 225]
        rgb *= relief[..., None]
        ocean = colors(63 - ground.astype(float), DEPTHS, SEA) * (.81 + .19 * shade(ground, step)[..., None])
        rgb[sea] = ocean[sea]
        rgb[fresh] = [63, 133, 176]
        frozen = (flags & 16) > 0
        rgb[frozen] = rgb[frozen] * .68 + np.array([223, 237, 239]) * .32
        yy, xx = np.indices(ground.shape)
        hatch = frozen & ((xx + yy) % 13 == 0)
        rgb[hatch] = rgb[hatch] * .75 + np.array([242, 246, 247]) * .25
    coast, river = edge(sea), edge(fresh) & fresh
    rgb[coast] = rgb[coast] * .62 + np.array([58, 88, 103]) * .38
    rgb[river] = rgb[river] * .7 + np.array([42, 112, 157]) * .3
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))


def dashed(draw, points):
    for a, b in zip(points, points[1:]):
        count = max(1, int(np.hypot(b[0] - a[0], b[1] - a[1]) / 9))
        for i in range(0, count, 2):
            t, u = i / count, min(1, (i + 1) / count)
            draw.line((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                       a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u),
                      fill=(105, 67, 53, 160), width=2)


def clip_segment(a, b, bounds):
    """任意範囲の外へ山脈線を描かないよう、線分を地図矩形で切る。"""
    x0, z0, x1, z1 = bounds
    dx, dz = b[0] - a[0], b[1] - a[1]
    lo, hi = 0., 1.
    for p, q in [(-dx, a[0] - x0), (dx, x1 - a[0]), (-dz, a[1] - z0), (dz, z1 - a[1])]:
        if p == 0:
            if q < 0:
                return None
        elif p < 0:
            lo = max(lo, q / p)
        else:
            hi = min(hi, q / p)
    if lo > hi:
        return None
    return (a[0] + dx * lo, a[1] + dz * lo), (a[0] + dx * hi, a[1] + dz * hi)


def render(output, request, job, font_path, pale):
    samples, palette = read_samples(output, job)
    x0, z0, x1, z1 = job["bounds"]
    step = job["step"]
    height, width = samples.shape
    mapw, left, top = max(1, round(3072 * min(1, width / height))), 155, 180
    maph = round(mapw * height / width)
    side = left + mapw + 70
    canvas = Image.new("RGB", (4100, max(top + maph + 160, 2700)), (250, 249, 245))
    draw = ImageDraw.Draw(canvas)

    def font(size):
        return ImageFont.truetype(str(font_path), size)

    def xy(x, z):
        return left + (x - x0) / (x1 - x0) * mapw, top + (z - z0) / (z1 - z0) * maph

    def inside(x, z):
        return x0 <= x < x1 and z0 <= z < z1

    def label(position, text, size=24, color=(45, 49, 47)):
        f = font(size)
        box = draw.textbbox(position, text, font=f)
        draw.rounded_rectangle((box[0] - 5, box[1] - 2, box[2] + 5, box[3] + 3), radius=3, fill=(253, 253, 248))
        draw.text(position, text, font=f, fill=color)

    draw.text((left, 30), request["map"] + "　" + job["title"] + ("　描き込み用" if pale else ""),
              font=font(46), fill=(32, 44, 48))
    revision = request["head"][:7] + ("（未コミット差分あり）" if request["dirty"] else "")
    draw.text((left, 100), f"基準commit {revision} ｜ 北が上・Xは東、Zは南", font=font(25), fill=(64, 79, 87))
    image = background(samples, palette, step, pale).resize((mapw, maph), Image.Resampling.LANCZOS)
    canvas.paste(image, (left, top))
    grid = Image.new("RGBA", canvas.size)
    gd = ImageDraw.Draw(grid)
    for x in range(-(-x0 // 1000) * 1000, x1 + 1, 1000):
        px, _ = xy(x, z0)
        gd.line((px, top, px, top + maph), fill=(42, 64, 74, 85 if x % 5000 == 0 else 40),
                width=2 if x % 5000 == 0 else 1)
        for y in (top - 36, top + maph + 10):
            draw.text((px, y), str(x), font=font(18), anchor="mt", fill=(44, 58, 64))
    for z in range(-(-z0 // 1000) * 1000, z1 + 1, 1000):
        _, pz = xy(x0, z)
        gd.line((left, pz, left + mapw, pz), fill=(42, 64, 74, 85 if z % 5000 == 0 else 40),
                width=2 if z % 5000 == 0 else 1)
        draw.text((left - 15, pz), str(z), font=font(20), anchor="rm", fill=(44, 58, 64))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), grid)
    draw = ImageDraw.Draw(canvas)
    draw.rectangle((left, top, left + mapw, top + maph), outline=(58, 79, 88), width=2)
    draw.text((side, top), "北　↑", font=font(42), fill=(37, 57, 65))
    for offset, text in [(70, "座標格子：1,000ブロック"), (115, f"地表標本：{step}ブロック／画素"),
                         (155, "Javaの実列から作成（表示拡大）")]:
        draw.text((side, top + offset), text, font=font(24), fill=(49, 67, 74))
    scale_blocks = min(2000, 10 ** int(np.floor(np.log10(x1 - x0))))
    scale = scale_blocks / (x1 - x0) * mapw
    # 狭い任意範囲でも凡例の幅に収める。
    while scale > 600:
        scale_blocks /= 2
        scale /= 2
    line_y = top + 235
    draw.line((side, line_y, side + scale, line_y), fill=(35, 53, 61), width=5)
    for sx in (side, side + scale / 2, side + scale):
        draw.line((sx, line_y - 8, sx, line_y + 8), fill=(35, 53, 61), width=2)
    draw.text((side, line_y + 15), f"0　　　{scale_blocks:g}ブロック", font=font(20), fill=(49, 67, 74))

    facilities = [f for f in request["layout"]["facilities"] if inside(*f["xz"])]
    if not pale:
        for mountain in request["layout"]["mountains"]:
            for a, b in zip(mountain["points"], mountain["points"][1:]):
                segment = clip_segment(a, b, job["bounds"])
                if segment:
                    dashed(draw, [xy(*point) for point in segment])
            for summit in mountain["summits"]:
                if inside(*summit["xz"]):
                    px, pz = xy(*summit["xz"])
                    draw.polygon([(px, pz - 4), (px - 4, pz + 3), (px + 4, pz + 3)], fill=(107, 66, 48))
        labels = [((-11000, -13200), "北の主脈"), ((-1700, -13900), "分岐山脈")]
        if request["map"] == "earth_002":
            labels = [(u["pins"][0]["xz"], str(u["id"])+" "+u["name"]) for u in request["landform"]["units"]
                      if u["id"] in (1,2,21,22,23,24,3,4,61,62,63)]
        for point, text in labels:
            if inside(*point):
                label(xy(*point), text, 26, (100, 58, 39))
        anchors = {row["id"]: row for row in csv.DictReader((output / "anchors.csv").open(encoding="utf-8"))}
        for i, facility in enumerate(facilities, 1):
            px, pz = xy(*facility["xz"])
            draw.ellipse((px - 8, pz - 8, px + 8, pz + 8), fill=(253, 244, 190), outline=(41, 45, 47), width=3)
            offset = (-260, -40) if facility["id"] == "cold_north" else (15, -40) if facility["id"] == "agriculture" else (15, 12)
            tx = max(left + 8, min(left + mapw - 280, px + offset[0]))
            tz = max(top + 8, min(top + maph - 50, pz + offset[1]))
            draw.line((px, pz, tx, tz + 15), fill=(45, 57, 58), width=2)
            label((tx, tz), f"{i} {NAMES[facility['id']]}")
        sy = top + 355
        draw.text((side, sy), "範囲内の施設・拠点", font=font(29), fill=(44, 59, 64))
        sy += 65
        for i, facility in enumerate(facilities, 1):
            row = anchors[facility["id"]]
            draw.text((side, sy), f"{i}　{NAMES[facility['id']]}", font=font(25), fill=(44, 59, 64))
            x, z = facility["xz"]
            draw.text((side + 30, sy + 37), f"X {x:,}　Z {z:,}　地面Y {row['ground_y']}", font=font(21), fill=(88, 98, 99))
            sy += 92
        sy += 35
        for title, levels, palette_colors in [("標高Y（ブロック）", [63, 100, 150, 250, 350, 450, 520], (HEIGHTS, LAND)),
                                              ("水深（海面Y63から）", [0, 20, 60, 100, 150, 183], (DEPTHS, SEA))]:
            draw.text((side, sy), title, font=font(27), fill=(44, 59, 64))
            sy += 58
            for value in levels:
                color = tuple(np.rint(colors(value, *palette_colors)).astype(int))
                draw.rectangle((side, sy, side + 40, sy + 25), fill=color)
                draw.text((side + 58, sy - 5), str(value), font=font(23), fill=(55, 65, 68))
                sy += 36
            sy += 35
        notes = ["川・湖：青", "雪・氷河：白〜淡青", "凍結水面：淡い斜線（色は水深）",
                 "山脈の配置軸：茶の破線", "固定の峰：▲　陰影：北西光"]
        if request["map"] == "earth_002":
            notes = ["D1：基盤と山脈・山地・丘陵", "北西の母岩は中立の石で仮表示", "河川・湖・細地形・雪氷は未制作", "施設・鉱床・路線も未制作", "旧版は既定のまま維持"]
    else:
        sy = top + 380
        notes = ["川・湖：淡い青", "海岸線・陰影のみを薄く表示", "施設名・山脈軸は省略",
                 "範囲・格子は詳細版と同一", "河川は実際の水面の標本", "細い流れは標本間で省略され得る"]
    for text in notes:
        draw.text((side, sy), text, font=font(22), fill=(59, 77, 83))
        sy += 48
    draw.text((left, top + maph + 78), "実列の地形・水面・雪氷・表層材を標本化。構造物はアンカー表示。テクスチャ・実際の光は再現しない。",
              font=font(22), fill=(68, 84, 91))
    path = output / (job["name"] + ("-sketch" if pale else "-detail") + ".png")
    canvas.convert("RGB").save(path)
    return {"file": str(path), "pixels": list(canvas.size), "bounds": job["bounds"],
            "blocks_per_sample": step, "sample_grid": [width, height],
            "ground_y_range": [int(samples["ground"].min()), int(samples["ground"].max())],
            "sea_samples": int(np.sum((samples["flags"] & 3) == 1)),
            "freshwater_samples": int(np.sum((samples["flags"] & 3) == 2)),
            "snow_glacier_samples": int(np.sum((samples["flags"] & 12) > 0)),
            "frozen_water_samples": int(np.sum((samples["flags"] & 16) > 0))}
