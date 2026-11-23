"""既存の先頭フレームの配色を保ち、水面・流水の循環アニメーションを再生成する。"""
import json
import math
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / "core/src/main/resources/assets/ashen_frontier/textures/block"
WATERS = ("ultrapure_water", "pure_water", "clean_water", "raw_water", "turbid_water",
          "contaminated_water", "toxic_water", "polluted_seawater")


def animate():
    for water in WATERS:
        for kind, size in (("still", 16), ("flow", 32)):
            path = TEXTURES / f"{water}_{kind}.png"
            with Image.open(path) as image:
                source = image.convert("RGBA").crop((0, 0, size, size))
            strip = Image.new("RGBA", (size, size * 32))
            for frame in range(32):
                phase = frame * math.tau / 32
                for y in range(size):
                    for x in range(size):
                        dx = round(2 * (math.sin(phase + y * math.tau / size)
                                        - math.sin(y * math.tau / size)))
                        dy = frame if kind == "flow" else round(2 * math.sin(phase))
                        strip.putpixel((x, frame * size + y), source.getpixel(((x + dx) % size, (y + dy) % size)))
            strip.save(path)
            path.with_suffix(".png.mcmeta").write_text(json.dumps({"animation": {
                "frametime": 2, "interpolate": True, "frames": list(range(32))}}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    animate()
