"""固定マップ用の装飾・自然スポーンなしバイオームと翻訳を出力する。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "modules/world/src/main/resources"
DUST_PARTICLES = {
    "arid_wasteland": "white_ash",
    "industrial_wasteland": "ash",
    "barren_land": "ash",
    "warm_barren_land": "ash",
}

BIOMES = [
    ("polluted_ocean", "Polluted Ocean", "汚染された海", 0.5, 0.5, 0.78, 0x78827F),
    ("temperate_wasteland", "Temperate Wasteland", "温帯の荒廃地", 0.6, 0.5, 0.58, 0xA9A99A),
    ("abandoned_farmland", "Abandoned Farmland", "旧農業地帯", 0.7, 0.6, 0.46, 0xA9B49B),
    ("mining_highlands", "Mining Highlands", "鉱業高地", 0.2, 0.4, 0.28, 0x93A4A5),
    ("arid_wasteland", "Arid Wasteland", "内陸の乾燥荒廃地", 1.8, 0.0, 0.62, 0xC5A77A),
    ("industrial_wasteland", "Industrial Wasteland", "工業荒廃地", 0.8, 0.3, 0.82, 0x77736C),
    ("industrial_coast", "Industrial Coast", "沿岸工業地帯", 0.8, 0.5, 0.76, 0x7F8986),
    ("cold_research_wastes", "Cold Research Wastes", "北の寒冷研究地域", -0.5, 0.4, 0.68, 0xA8B8C4),
    ("barren_land", "Barren Land", "未割当ての荒廃地", 0.5, 0.3, 0.52, 0xA4A092),
    ("cold_barren_land", "Cold Barren Land", "寒冷な未割当て荒廃地", -0.5, 0.3, 0.58, 0xA4B0BA),
    ("warm_barren_land", "Warm Barren Land", "温暖な未割当て荒廃地", 1.2, 0.2, 0.48, 0xBBA27B),
    ("alpine_wastes", "Alpine Wastes", "高山の荒廃地", -0.7, 0.3, 0.18, 0xB8CAD2),
]


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    for name, _, _, temperature, downfall, density, fog_color in BIOMES:
        write(RESOURCES / f"data/ashen_frontier/worldgen/biome/{name}.json", {
            "has_precipitation": downfall > 0, "temperature": temperature, "downfall": downfall,
            "effects": {"fog_color": fog_color, "water_color": 4159204,
                        "water_fog_color": 329011, "sky_color": 7907327},
            "spawners": {}, "spawn_costs": {}, "carvers": {}, "features": [],
        })
        write(RESOURCES / f"data/ashen_frontier/biome_atmosphere/{name}.json",
              {"density": density, "dust_particle": DUST_PARTICLES.get(name, "none")})
    for language, column in (("en_us", 1), ("ja_jp", 2)):
        path = RESOURCES / f"assets/ashen_frontier/lang/{language}.json"
        translations = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        translations.update({f"biome.ashen_frontier.{row[0]}": row[column] for row in BIOMES})
        write(path, translations)
    path = RESOURCES / "data/ashen_frontier/worldgen/world_preset/fixed_map.json"
    preset = json.loads(path.read_text(encoding="utf-8"))
    generator = preset["dimensions"]["minecraft:overworld"]["generator"]
    generator["biome_source"] = {
        "biomes": [f"ashen_frontier:{row[0]}" for row in BIOMES], "map": generator["map"].copy(),
    }
    write(path, preset)


if __name__ == "__main__":
    main()
