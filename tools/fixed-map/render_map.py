"""Javaの実列標本から、固定マップの詳細図と描き込み図を作る制作入口。"""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
PRESETS = {
    "world": ((-16384, -16384, 16384, 16384), 16, "全域"),
    "northwest": ((-16384, -16384, 7168, 1024), 8, "北西大陸と周辺海域"),
}


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--map", choices=["earth_001", "earth_002"], default="earth_001", help="検査する版を明示。通常プリセットは更新しない")
    region = parser.add_mutually_exclusive_group()
    region.add_argument("--preset", choices=["all", *PRESETS], default=None,
                        help="省略時は全域16と北西8を一括生成")
    region.add_argument("--bounds", type=int, nargs=4, metavar=("X0", "Z0", "X1", "Z1"),
                        help="上限を含まないブロック範囲")
    parser.add_argument("--step", type=int, help="標本間隔。各辺はこの値で割り切れること")
    parser.add_argument("--output", type=Path, help="新規または空の出力ディレクトリ")
    parser.add_argument("--font", type=Path, help="日本語TrueType/OpenTypeフォント")
    args = parser.parse_args(argv)
    if args.step is not None and args.step < 1:
        parser.error("--stepは1以上")
    if args.bounds:
        specs = [("custom", tuple(args.bounds), args.step or 16, "指定範囲")]
    else:
        names = PRESETS if args.preset in (None, "all") else [args.preset]
        specs = [(name, PRESETS[name][0], args.step or PRESETS[name][1], PRESETS[name][2]) for name in names]
    jobs = []
    for name, bounds, step, title in specs:
        x0, z0, x1, z1 = bounds
        if not (-16384 <= x0 < x1 <= 16384 and -16384 <= z0 < z1 <= 16384):
            parser.error("範囲は固定マップ内（X/Zとも−16384以上16384以下）で、下限<上限")
        if (x1 - x0) % step or (z1 - z0) % step:
            parser.error("範囲の各辺を--stepで割り切れるように指定する")
        if min((x1 - x0) // step, (z1 - z0) // step) < 2:
            parser.error("陰影の計算には各辺2標本以上が必要")
        jobs.append({"name": f"{name}-{step}", "title": title, "bounds": bounds, "step": step})
    return args, jobs


def japanese_font(specified):
    if specified:
        if not specified.is_file():
            raise ValueError(f"フォントが見つからない: {specified}")
        return specified.resolve()
    candidates = [Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts/YuGothM.ttc",
                  Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")]
    for path in candidates:
        if path.is_file():
            return path
    raise ValueError("日本語フォントを--fontで指定する")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").strip()


def prepare_output(output):
    output = output.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError(f"出力先は新規または空のディレクトリにする: {output}")
    if output.is_relative_to(ROOT):
        relative = output.relative_to(ROOT).as_posix()
        ignored = subprocess.run(["git", "check-ignore", "-q", relative + "/map.png"], cwd=ROOT)
        if ignored.returncode != 0:
            raise ValueError("リポジトリ内の出力先はignoredのディレクトリにする")
    output.mkdir(parents=True, exist_ok=True)
    return output


def sample(request_path):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), ":world:1.21.1:runFixedMapRender", "--init-script",
               str(TOOLS / "map-render.init.gradle"), f"-PfixedMapRenderRequest={request_path}",
               "--no-daemon", "--no-parallel", "--no-configuration-cache", "--console=plain"]
    if os.name == "nt":
        command = ["cmd.exe", "/d", "/c", *command]
    env = os.environ.copy()
    env.pop("ASHEN_TERRAIN_PREVIEW", None)
    env.pop("ASHEN_MOUNTAIN_PREVIEW", None)
    log = request_path.parent / "sampling.log"
    print(f"Java標本化を開始。進捗ログ: {log}", flush=True)
    with log.open("wb") as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"標本化が失敗（exit={result.returncode}）。{log}を確認する")
    marker = request_path.parent / "sampling-complete.json"
    if not marker.exists():
        raise RuntimeError(f"標本化が完了していない。{log}を確認する")
    result = json.loads(marker.read_text(encoding="utf-8"))
    request = json.loads(request_path.read_text(encoding="utf-8"))
    if result["token"] != request["token"]:
        raise RuntimeError("標本化の完了記録が今回の要求と一致しない")
    return result


def main(argv=None):
    args, jobs = arguments(argv)
    if sys.version_info[:2] != (3, 12):
        raise ValueError("制作環境のPython 3.12で実行する（README参照）")
    import numpy
    import PIL
    if numpy.__version__ != "2.2.6" or PIL.__version__ != "11.2.1":
        raise ValueError("requirements.txtのハッシュ固定済み依存を導入する")
    from map_image import render

    font = japanese_font(args.font)
    head = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--untracked-files=normal"))
    output = prepare_output(args.output or TOOLS / "preview/maps" / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + head[:7]))
    manifest = ROOT / f"modules/world/src/main/resources/data/ashen_frontier/fixed_map/{args.map}/manifest.json"
    request = {"token": uuid.uuid4().hex, "head": head, "dirty": dirty,
               "map": args.map, "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
               "layout": json.loads((TOOLS / "input/layout.json").read_text(encoding="utf-8")), "jobs": jobs}
    if args.map == "earth_002":
        request["layout"] = {"facilities": [], "mountains": []}
        request["landform"] = json.loads((manifest.parent / "landform.json").read_text(encoding="utf-8"))
    path = output / "request.json"
    path.write_text(json.dumps(request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = sample(path)
    images = []
    for job in jobs:
        for pale in (False, True):
            images.append(render(output, request, job, font, pale))
            print(images[-1]["file"], flush=True)
    metadata = {"head": head, "dirty": dirty, "map": request["map"],
                "manifest_sha256": request["manifest_sha256"], "sampling": result,
                "method": "FixedTerrain.column: surfaceY, topY, state(surfaceY), state(topY); セル中心",
                "images": images}
    (output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"完了: {output}（標本化 {result['seconds']:.1f}秒）", flush=True)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError) as error:
        print(f"エラー: {error}", file=sys.stderr)
        raise SystemExit(1)
