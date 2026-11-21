# 固定マップの生成契約

Issue #49で追加したworldモジュールの利用方法。設計の正本は[設計12](design/12-m2-fixed-map.md)とする。Block・Item・Fluidの登録はcoreが所有し、worldへ逆依存させない。

## 座標

`io.github.rs0325.ashenfrontier.world.MapCoordinates`を使う。

- `contains(int x, int z)`：両軸とも`[-16384,16384)`の範囲内かを返す。
- `latitude(double z)`：連続ブロックZを北が正の緯度（度）へ変換する。`-80 × Z / 16384`。北端は80、南端境界は−80。丸め・クランプは行わず、範囲外座標も同じ式で換算する。
- `zFromLatitude(double latitude)`：逆変換。北緯40度はZ=−8192。
- `elevation(double y)`：仮想標高m。`(Y−63)×25`。水平距離の圧縮率を適用しない。

`BiomeSource.getNoiseBiome`の入力はquart座標。後続Dでブロック座標の配置図を読むときは、標準の`QuartPos.toBlock`で変換する。現段階は全域で仮の`minecraft:the_void`を返し、`possibleBiomes()`にも同じ1種を列挙する。

## 版と資産

overworldの`level.getChunkSource().getGenerator()`が`FixedMapChunkGenerator`なら、`mapVersion()`でそのワールドの`MapVersion`を取得する。別生成器からの変換は行わない。`MapVersion`は不変recordで、`mapVersion()`・`formatVersion()`・`manifestHash()`を公開する。

登録IDはChunkGenerator・BiomeSourceともに`ashen_frontier:fixed_map`。MapCodecは各クラスの`CODEC`。生成器のCodecは`map`に`map_version`・`format_version`・`manifest_hash`、`biome_source`にバイオーム設定を保存する。

`BundledMaps.require(MapVersion)`は、クラスパスの`data/ashen_frontier/fixed_map/<map_version>/manifest.json`と資産を照合する。ハッシュはファイルの生バイト列のSHA-256、小文字hex。manifestの`files`は、同じ版ディレクトリからの相対ファイル名とSHA-256の対応。対応形式は1。版が未同梱、形式・ハッシュの不一致、資産の不足・破損では版名を含む例外で停止する。データパックで同梱資産を上書きしない。

現在同梱する`prototype_001`は仮データで、固定シード0、幅・奥行32768、高さ384、最低Y=−64、仮カタログ版を持つ。配置図ファイルはまだない。Cはmanifestの`files`へ実資産を追加し、manifest変更後にプリセットの`manifest_hash`を更新する。この試作版を使ったワールドは再生成対象。旧形式への移行・既存ワールドの自動更新は行わない。

## SavedDataとの照合

`MapSavedData.verify(ServerLevel overworld, MapVersion expected)`で、初回は照合用記録を作成し、既存記録は完全一致を要求する。戻り値の`mapVersion()`で保存版を参照できる。保存先はoverworldの`data/ashen_frontier_map.dat`。登録イベントが固定マップのoverworldに対して呼ぶため、後続から毎tick呼ぶ必要はない。

サーバーのLevel生成前に既存ファイルも直接照合する。標準SavedDataの破損時の新規作成経路に入る前に停止し、版を置き換えない。生成スレッドはこのSavedDataや可変の研究・気象状態を読まない。

## 起動と後続範囲

専用サーバーはworldとcoreを導入し、`server.properties`へ`level-type=ashen_frontier:fixed_map`を設定する。プリセットに含めるLevelStemはoverworldのみ。シングルプレイは新規作成画面の初回表示で通常プリセットから固定マップへ誘導し、ユーザーの選択変更は維持する。シードが設定済みの再作成画面は誘導しない。既存セーブの読込みは変換しない。

初期ボーダーは中心0、幅32768。仮地形はY=−64に岩盤、Y=−63〜63に石。範囲外は空チャンク。`getBaseHeight`・`getBaseColumn`・実チャンクは同じサンプラーを使う。バニラの構造物・装飾・初期Mob生成は実行しない。

Bはcoreの登録、Cは配置図とmanifest、Dはこの生成器・BiomeSourceの地形と分布を実装する。地域・水域・地質・帯水層などの未実装APIを先に追加しない。初期シェルター座標や本来の地形の完成を、この仮地形で代替しない。

自然物の配置ID、母岩と鉱物、土壌・水・燃料の品質、回収先はcoreの`catalog/nature.json`を参照する。登録済みAPIと単位、B2/G/D/Eへの引継ぎは[自然物の登録と制作契約](nature-catalog.md)に従う。配置図の仮カタログ版の更新はCの担当とし、Bからmanifestや配置図資産は変更しない。
