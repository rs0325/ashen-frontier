"""承認済みの北西大陸へ鉱床と地下経路の固定データを出力する。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "modules/world/src/main/resources/data/ashen_frontier/fixed_map/earth_001"


def document():
    deposits = []

    def deposit(id, block, host, x, z, depth, radii, shape="ellipsoid", required=False, lens=False, vanilla=None):
        deposits.append(dict(id=id, block=block, host=host, x=x, z=z, depth=depth,
                             radii=radii, shape=shape, required=required, lens=lens, vanilla=vanilla))

    deposit("mining_iron", "quartzite_hematite_ore", "quartzite", -10250, -10700, 62, [18, 5, 12], required=True)
    deposit("mining_copper", "granite_chalcopyrite_ore", "minecraft:granite", -9750, -10764, 9, [16, 5, 10], required=True)
    deposit("mining_coal", "bituminous_coal", "shale", -10500, -10500, 12, [22, 3, 14], "layer", True, True)
    deposit("mining_tin", "granite_cassiterite_ore", "minecraft:granite", -10000, -11000, 10, [16, 4, 6], "vein", True)
    deposit("mining_limestone", "limestone", "limestone", -10300, -10450, 10, [18, 5, 12], "layer", True, True)
    deposit("agriculture_clay", "clay_sediment", "clay_sediment", -5300, -8500, 4, [18, 2, 14], "layer", True, True)
    deposit("arid_nitrate", "clay_sediment_nitrate_ore", "clay_sediment", -12600, -5400, 4, [20, 2, 14], "layer", True, True)
    deposit("coastal_silica", "sandstone_quartz_ore", "minecraft:sandstone", -4200, -3800, 10, [18, 4, 12], "layer", True)
    deposit("sulfur_fumarole", "andesite_sulfur_ore", "minecraft:andesite", -10800, -10900, 7, [5, 2, 5], lens=True)
    deposit("sulfur_pyrite", "granite_pyrite_ore", "minecraft:granite", -9600, -11100, 12, [5, 3, 5])
    # 地域の役割が確定した北西大陸だけに、カタログの対応を選んで置く。
    candidates = [
        ("magnetite", "limestone_magnetite_ore", "limestone", 32),
        ("lead", "limestone_galena_ore", "limestone", 32),
        ("zinc", "limestone_sphalerite_ore", "limestone", 35),
        ("nickel", "peridotite_pentlandite_ore", "peridotite", 70),
        ("aluminium", "limestone_bauxite_ore", "limestone", 25),
        ("titanium", "sandstone_rutile_ore", "minecraft:sandstone", 16),
        ("chromium", "peridotite_chromite_ore", "peridotite", 72),
        ("manganese", "limestone_pyrolusite_ore", "limestone", 30),
        ("molybdenum", "granite_molybdenite_ore", "minecraft:granite", 12),
        ("tungsten", "limestone_scheelite_ore", "limestone", 35),
        ("uranium", "granite_uraninite_ore", "minecraft:granite", 18),
        ("phosphate", "limestone_apatite_ore", "limestone", 28),
        ("graphite", "gneiss_graphite_ore", "gneiss", 45),
        ("lithium", "granite_spodumene_ore", "minecraft:granite", 15),
        ("cobalt", "quartzite_cobaltite_ore", "quartzite", 65),
        ("rare_earth", "sandstone_monazite_ore", "minecraft:sandstone", 15),
    ]
    for i, (id, block, host, depth) in enumerate(candidates):
        deposit(id, block, host, -11400 + i % 4 * 180, -11300 - i // 4 * 180,
                depth, [8, 3, 6], "vein", lens=True)
    for i, quality in enumerate(["peat", "lignite", "anthracite"]):
        deposit(quality, quality, "shale", -10900 - i * 160, -10100, 12, [12, 2, 8], "layer", lens=True)
    for i, quality in enumerate(["light_low_sulfur", "light_high_sulfur", "heavy_low_sulfur", "heavy_high_sulfur"]):
        deposit("oil_" + quality, quality + "_crude_oil", "minecraft:sandstone", -6400 + i * 160,
                -5500, 110, [7, 2, 6], "layer", lens=True)
    # バニラ鉱石はこの小さな局所枠だけ。通常のFeatureは呼び出さない。
    for i, ore in enumerate(["iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald"]):
        deposit("vanilla_" + ore, "minecraft:" + ore + "_ore", "minecraft:granite", -9300 + i * 100,
                -11400, 20, [2, 2, 2], vanilla=True)

    spaces = [
        dict(id="city_metro", kind="metro", facility="spawn", x=-8340, z=-8000, end_x=-8040, end_z=-8000, depth=28, width=3, height=5),
        dict(id="mining_gallery", kind="mine", facility="mining", x=-10160, z=-10650, end_x=-10400, end_z=-10650, depth=34, width=2, height=3),
        dict(id="industry_utility", kind="utility", facility="industry", x=-6160, z=-5000, end_x=-6460, end_z=-5000, depth=16, width=1, height=3),
        dict(id="fixed_cave", kind="cave", facility="mining", x=-10800, z=-11600, end_x=-11100, end_z=-11600, depth=60, width=4, height=6),
    ]
    return dict(map_version="earth_001", format_version=1, fixed_seed=520026,
                salt="underground_001", deposits=deposits, spaces=spaces)


def write():
    data = document()
    encoded = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode()
    (OUT / "underground.json").write_bytes(encoded)
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    manifest["files"]["underground.json"] = hashlib.sha256(encoded).hexdigest()
    manifest["tool"]["files"]["tools/fixed-map/write_underground.py"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    manifest["tool"]["files"]["tools/fixed-map/generate.py"] = hashlib.sha256(Path(__file__).with_name("generate.py").read_bytes()).hexdigest()
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    (OUT / "manifest.json").write_bytes(encoded)
    preset = OUT.parents[1] / "worldgen/world_preset/fixed_map.json"
    content = json.loads(preset.read_text(encoding="utf-8"))

    def update(value):
        if isinstance(value, dict):
            if "manifest_hash" in value:
                value["manifest_hash"] = hashlib.sha256(encoded).hexdigest()
            for child in value.values():
                update(child)
        elif isinstance(value, list):
            for child in value:
                update(child)
    update(content)
    preset.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    write()
