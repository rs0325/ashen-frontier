"""GPT image原稿の40面を16pxへ整え、承認された破断面・窓の画素を補正する。"""
from PIL import Image, ImageDraw, ImageEnhance

def ruin_tile(entry, atlas):
    index = entry["sprite"]
    x, y = index % 8, index // 8
    w, h = atlas.size
    # セルの罫線がタイル端に残らないよう、各辺の内側3%だけを切り取る。
    bounds = ((x+.03)*w/8, (y+.03)*h/5, (x+.97)*w/8, (y+.97)*h/5)
    tile = atlas.crop(tuple(round(v) for v in bounds)).convert("RGB").resize((16,16),Image.Resampling.BOX)
    tile = ImageEnhance.Color(tile).enhance(.72).convert("RGBA")
    id = entry["id"]
    draw = ImageDraw.Draw(tile)
    if id.startswith(("intact_reinforced_concrete", "weathered_reinforced_concrete")):
        # 原稿の健全/風化面に混入した鉄筋模様は対応する無筋面で置き直し、冷灰色へ調整する。
        base = 5 if id.startswith("intact") else 6
        tile = atlas.crop((round((base+.03)*w/8),round(.03*h/5),round((base+.97)*w/8),round(.97*h/5))).convert("RGB").resize((16,16),Image.Resampling.BOX).convert("RGBA")
        for py in range(16):
            for px in range(16):
                r,g,b,a=tile.getpixel((px,py));tile.putpixel((px,py),(max(0,r-5),max(0,g-3),b,255))
        draw = ImageDraw.Draw(tile)
    if id.startswith("cracked_") and "glass" not in id:
        draw.line([(3,0),(5,4),(4,8),(8,12),(7,15)],fill=(61,59,53,255))
        draw.line([(4,8),(0,10)],fill=(72,68,60,255))
    if id.startswith("weathered_brickwork"):
        for p in [(2,4),(3,4),(4,5),(11,10),(12,10),(13,11)]:draw.point(p,fill=(169,158,139,255))
    if "timber" in id and ("split" in id or "rotten" in id):
        draw.line([(7,0),(7,5),(8,8),(7,12),(8,15)],fill=(46,39,31,255))
    if id.endswith("_glass_window"):
        # cutoutで多層半透明を使わず、窓枠・反射・ひびを二値アルファで描く。
        tile=Image.new("RGBA",(16,16));draw=ImageDraw.Draw(tile)
        draw.rectangle((0,0,15,15),outline=(81,84,77,255))
        draw.line([(3,11),(10,4)],fill=(171,186,175,255))
        if id.startswith("clouded"):
            for py in range(2,14,3):
                for px in range(2,14,4):draw.point((px,py),fill=(126,137,125,255))
        if id.startswith("cracked"):
            draw.line([(3,2),(8,8),(5,14)],fill=(183,199,187,255))
            draw.line([(8,8),(14,6)],fill=(183,199,187,255))
        if id.startswith("broken"):
            # 実際の欠落は固定boxで空ける。残った縁にだけ破断色を置く。
            draw.line([(2,5),(6,2)],fill=(159,177,165,255))
    return tile
