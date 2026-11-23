---
name: fixed-map-render
description: 固定マップの実地形から全体図を生成する。ユーザーから全体図を求められたとき、バイオーム・地形配置の案を作るとき、大規模な地形変更を確認・比較するときに使う。
---

# 固定マップの全体図

Javaの`FixedTerrain.column`を標本化して、標高・陰影・水深・水域・雪氷・施設アンカー・座標格子を描く。配置図のPNGだけで代用しない。

## 実行

対象のcommitとworktreeを確認し、そのリポジトリルートで実行する。適用されるコマンド記録の手順で、次のコマンド全体を記録する。

既存の制作環境はPython 3.12、NumPy 2.2.6、Pillow 11.2.1。[制作ツールのREADME](../../../tools/fixed-map/README.md)の手順で仮想環境とハッシュ固定依存を用意する。

```powershell
# 全域16ブロック/画素・北西大陸8ブロック/画素をまとめて生成
& .\tools\fixed-map\.venv\Scripts\python.exe -X utf8 tools/fixed-map/render_map.py

# 片方だけ、または任意範囲（X0 Z0 X1 Z1、上限を含まない）
& .\tools\fixed-map\.venv\Scripts\python.exe -X utf8 tools/fixed-map/render_map.py --preset northwest --step 8
& .\tools\fixed-map\.venv\Scripts\python.exe -X utf8 tools/fixed-map/render_map.py --bounds -6000 -15000 -2000 -11000 --step 8
```

必要なら`--output <新規または空のディレクトリ>`を指定する。省略時はignoredの`tools/fixed-map/preview/maps/<日時>-<commit>/`。Linuxは`.venv/bin/python`を使い、日本語フォントが自動検出されない場合は`--font <ttf/otf/ttc>`を指定する。

専用のヘッドレスGameTestサーバーを自動起動し、終了後に描画する。開発クライアントは起動しない。同じworktreeで他のGradle実行・コード編集と重ねない。全域と北西で約1,060万列、標本化は数分、初回のコンパイル・描画を含め数分〜十数分を見込む。メモリはJavaと描画で数GiBの余裕を持つ。間隔を半分にすると標本数は約4倍になる。

## 出力と使い方

- `*-detail.png`: 詳細版。施設名・山脈の配置軸・固定峰・座標・凡例付き。
- `*-sketch.png`: 同じ範囲・格子の淡い描き込み版。
- `metadata.json`・`request.json`: 基準commit、未コミット差分の有無、範囲・間隔、標本化時間など。
- `sampling.log`: Java標本化の進捗と失敗時の原因。完了記録がない実行を成功と扱わない。

出力を目で見て、海岸・河川・山脈・施設ラベルと格子が対象データに合うか確認してから提示する。施設は配置アンカーで、実際の建物の描画ではない。図中の山脈軸も実測の稜線そのものではない。

地形配置の案を作るときは、詳細版で現状を確認し、淡い版に案を重ねる。AGENTS.mdで指定されたGPT imageの地図と併用し、現状の実測図と提案を区別する。変更前後の比較では、各worktreeで同じ範囲・間隔を指定し、基準commitと未コミット差分の表示を添える。承認を得るための図の生成だけでは、地形データの変更・公開は許可されない。

## 限界

標本間隔より細い流れ・微地形は省略され得る。画像を拡大しても標本の解像度は増えない。テクスチャ・ゲーム内の光・霧・空・建物・植生は再現しない。実際の見た目は#85の自動撮影で確認する。描画物と中間標本は公開対象へ含めない。
