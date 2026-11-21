"""PyPIのリリース情報から、固定版の配布物ハッシュを記録する（更新時のみ実行）。"""

import json
from pathlib import Path
from urllib.request import urlopen


def main():
    lines = ["# Python 3.12 / 制作時のみ。更新時はlock_dependencies.pyを明示実行する。"]
    for name, version in [("numpy", "2.2.6"), ("Pillow", "11.2.1")]:
        with urlopen(f"https://pypi.org/pypi/{name}/{version}/json") as response:
            release = json.load(response)
        hashes = sorted({item["digests"]["sha256"] for item in release["urls"]})
        lines.append(f"{name}=={version} \\")
        lines.extend(f"    --hash=sha256:{value}" + (" \\" if i < len(hashes) - 1 else "")
                     for i, value in enumerate(hashes))
    Path(__file__).with_name("requirements.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
