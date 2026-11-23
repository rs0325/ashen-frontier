# 構造物の変換と配置

Issue #58の制作専用ツール。Java 21と既存のNeoForge/Minecraftクラスパス、core登録、Gsonを使う。圧縮・NBT・テンプレートは標準`NbtIo`・`StructureTemplate`、regionは標準`RegionFile`を再利用する。WorldEditや外部NBTエンジンは追加しない。

`world`の`structures`ソースセットで変換器、`structureGameTest`で動作検証を分離する。ModDevGradleの[独立ソースセット](https://docs.neoforged.net/toolchain/docs/plugins/mdg/#isolated-source-sets)を使い、配布jarにはどちらも含めない。`tools/structures/fixtures/converted`も開発起動にだけ読み込む。`main`に検証用コード・fixtureは追加しない。

## 実行

リポジトリルートで、各コマンドを作業環境の`run_logged.py`に通して実行する。fixture生成と変換を同じGradle呼出しで並列に実行しない。

```powershell
.\gradlew.bat :world:1.21.1:runStructureFixtures --no-daemon
$config = (Resolve-Path tools/structures/fixtures/convert.json).Path
.\gradlew.bat :world:1.21.1:runStructureConvert "-PstructureConfig=$config" --no-daemon
.\gradlew.bat build --no-daemon
.\gradlew.bat gameTest --no-daemon
```

変換例は[fixtures/convert.json](fixtures/convert.json)。パスは設定JSONの親から解決し、絶対パスも使える。出力先は未作成のディレクトリを指定する。上記の変換例は初回用であり、同梱済み`converted`の再生成時には旧出力を保全して別の未作成パスへ出力し、比較後に入れ替える。失敗した変換は完成出力を公開しない。

`format`は`nbt`、`sponge3`、`mca`。すべてMinecraft 1.21.1、DataVersion=3955に限定する。Spongeはv3のみで、v2以前・未知版は拒否する。旧schematicは対応するWorldEdit環境で読み込み、Minecraft 1.21.1環境へ移した上で、Sponge v3形式の`.schem`として保存し直す。旧版をこのツールで自動移行しない。仕様本文・WorldEditコードは転載しない。[Sponge公式v3仕様](https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-3.md)を参照してpalette・varint・Offsetを実装した。仕様文書の明示ライセンスは確認できないため同梱しない。

MCAでは`input`に対象dimensionの`region`ディレクトリ、`dimension`に`minecraft:overworld`等、`range`に絶対座標の`[minX,minY,minZ,maxX,maxY,maxZ]`（両端を含む）を指定する。作業用ワールドのコピーを渡し、起動中のワールドは渡さない。ツールが必要なMCA/MCCをさらに一時コピーし、そのコピーだけを`RegionFile`で開く。コピー前後と取込み完了時に原本のSHA-256を照合する。欠けたregion・チャンク・外部MCC、未完成チャンク、異版はエラーとし、原本にファイルを作らない。元ワールドのサーバー起動やDataFixerは使わない。

## 設定と安全化

- `id`は施設ID、`mapVersion`は施設索引の版。`origin`は正規化後の施設全体の最小角を置く座標。回転・鏡映の中心もこの共通原点で、断片ごとに中心を変えない。`rotation`はMinecraftの`NONE` / `CLOCKWISE_90` / `CLOCKWISE_180` / `COUNTERCLOCKWISE_90`、`mirror`は`NONE` / `LEFT_RIGHT` / `FRONT_BACK`。
- SpongeのOffsetとMCAの絶対最小座標を`sourceOffset`へ記録し、ブロックを0始まりへ正規化する。配置時は`origin`を使う。Spongeのブロック/BE位置はOffset適用前のローカル座標として読む。
- `mapping`は入力Block ID→出力Block ID/状態。Block IDだけなら同名の状態プロパティを保持し、対応しない状態はエラー。状態全体を変更する場合は`[facing=north,...]`のように出力状態を明示する。
- `allowed`は最終的に残せるBlock IDの承認リスト。変更しないバニラも明記する。coreの`data/ashen_frontier/tags/item/removed.json`に載る禁止品は承認リストでも許可できない。未知ID・未知プロパティ・未承認IDを空気に置き換えない。coreのIDは[遺物カタログ](../../docs/relic-catalog.md)を参照する。
- 生きたEntityをすべて除去する。スポナー、コマンドブロック、structure block、jigsawは`structure_void`へ除去し、座標とIDをレポートへ残す。通常の空気は掘削、`structure_void`は既存ブロックの保持として区別する。
- 入力BEの持ち物、LootTable、コンポーネント、コマンド等は保持しない。チェスト・ベッド・看板・coreの静的設備BEだけを、新しい型IDから構築する。他の稼働BEは拒否する。看板は日英を含む表示文字列を保持し、装飾・クリック動作等を除いたプレーンテキストへ変換する。
- 扉の上下、ベッドの頭足、二重チェストの左右・向き・相互の接続先を照合する。欠損したペアや、二重チェストの相方がsingleになっている状態は変換エラー。隣接するsingle同士は独立した容器として許可する。
- `protectedPaths`は共通ローカル座標の境界箱（6整数・両端含む）。必須階段・入口・資料・シェルター・栽培室をここで保護する。施設外形の地形保護は配置図の保護域とIで照合する。
- `loot`は`pos`（共通ローカル座標）と承認済み`table`（ResourceLocation）。実際の容器位置と`ashen_frontier:buildings/<region>/<facility>`形式のテーブルだけを許可し、チェスト内容は生成しない。一般lootの例は`ashen_frontier:buildings/temperate/fixture`。必須の保証は`ashen_frontier:buildings/guaranteed/fixture`のように`buildings/guaranteed/`へ分け、倍率の対象外にする。地域に適したテーブルをIで割り当て、G2の倍率は一般lootの開封時に適用する。自作fixtureも同じ契約を使い、テスト専用の例外は設けない。
- `decay`は`input`、`output`（いずれもBlock ID）と`probability`（0〜1）。標準`RuleProcessor`の固定座標乱数を使い、入力・出力とも単独の静的ブロックに限定する。扉・背の高い植物など上下半分を持つブロックと、ベッド・チェストを含むBE付きブロックは両側とも拒否する。この制約は変換器とworldのmanifest読込みに共通。崩落は欠損・瓦礫への静的置換とし、保護経路を除外する。

## 出力とworldへの接続

出力ルートはリソースパックと同じ`data/`構造。

| 出力 | 内容 |
|---|---|
| `data/<施設のnamespace>/structure/<施設path>/<x>_<y>_<z>.nbt` | 最大48³の標準template。`size`はTAG_List。Entityなし |
| `data/ashen_frontier/structures/<mapVersion>/index.json` | 施設manifestの配列。施設ID・原点・向き・入力Offset・権利・断片と境界箱・保護経路・loot・劣化・チャンク索引 |
| `conversion-report.json` | 入力形式/dimension/範囲、原本MCA/MCC等のハッシュ、出力ハッシュ、禁止ブロック除去と権利記録 |

各断片を`StructureTemplate`で保存し、`NbtIo`で再読込みしたブロック・状態・BE・寸法、および標準templateの往復結果を照合する。manifestも再読込みして索引を再計算する。重複座標・断片の重なり・施設間の重なり・壊れた索引は拒否する。

worldは`FixedMapChunkGenerator.applyBiomeDecoration`（FEATURES段階）から配置する。索引は生成器ごとに1回読み込み、チャンクに交差する断片だけを取り出す。施設索引がない版には施設を置かない。Iで承認した資産だけを`modules/world/src/main/resources/`へ移す。複数施設を収録するときはmanifest配列を1つにまとめ、NBTは各ID別のまま保持する。配置原点・向きを変更するときは変換を再実行し、`chunks`を手で編集しない。

境界外はルールプロセッサの前に除外し、`placeInWorld`のBoundingBoxも当該チャンクに限定する。形状とwaterloggingの隣接探索を抑え、流体・重力ブロックだけを当該チャンクの遅延tickへ積む。`WorldGenRegion`ではProtoChunkのtickリストに入り、周辺生成後のロード時に実行される。標準保存済み生成状態がFEATURES以後なら再配置しないため、プレイヤーの改変を再訪で戻さない。

loot専用の`ashen_frontier:facility_loot`プロセッサが遅延LootTable/seedを設定する。標準templateが容器seedを上書きするため、配置直後にワールドシードと絶対座標由来のseedを再設定する。G2へは`buildings/`配下のIDで接続し、`buildings/guaranteed/`の必須保証枠は倍率から除外する。劣化の乱数にはワールドシードを混ぜない。

## 権利記録

取込み前に`rights`へ`author`、`source`、`license`、`redistribution=true`、`modification=true`、`attribution`を明記する。フラグは権利確認結果の記録であり、許諾を自動的に付与するものではない。不明な素材は取り込まない。必要なライセンス本文・表示も素材に添える。

入力版`inputVersion`と`inputSha256`は変換器が記録する。MCAの場合、ファイル名→SHA-256の表をレポートへ保存し、その表のハッシュもmanifestに持つ。fixtureの作者はAshen Frontier contributors、出典は`FixtureWriter.java`、ライセンスは[MIT](fixtures/LICENSE.txt)。外部の構造物・コード・サンプルは使っていない。実際の施設素材の選定・許諾確認はI（#59）。

## 検証

```powershell
.\gradlew.bat :world:1.21.1:test --no-daemon
.\gradlew.bat build --no-daemon
.\gradlew.bat gameTest --no-daemon
```

自作65×50×5 fixtureの3形式、負座標・Offset、48境界を跨ぐ扉/ベッド/二重チェスト、看板、空気/structure_void、v2・未知版・不正varint・未登録状態・欠損チャンク・外部MCC・原本ハッシュ保持・権利拒否・未承認ID・不完全ペア・索引破損・再読込みを単体テストで確認する。GameTestは`ashen_frontier_world`名前空間で、全回転/鏡映、チャンク順、保存済み生成状態による再訪、保護経路、core設備BE、遅延lootとそのCodecを検証する。境界外のBlockPosを受け取ったWorldGenLevel呼出しは検証中に失敗させる。実クライアントの表示を変更する機能はない。
