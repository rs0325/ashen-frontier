# 固定マップの生成契約

Issue #49で追加したworldモジュールの利用方法。設計の正本は[設計12](design/12-m2-fixed-map.md)とする。Block・Item・Fluidの登録はcoreが所有し、worldへ逆依存させない。

## 座標

`io.github.rs0325.ashenfrontier.world.MapCoordinates`を使う。

- `contains(int x, int z)`：両軸とも`[-16384,16384)`の範囲内かを返す。
- `latitude(double z)`：連続ブロックZを北が正の緯度（度）へ変換する。`-80 × Z / 16384`。北端は80、南端境界は−80。丸め・クランプは行わず、範囲外座標も同じ式で換算する。
- `zFromLatitude(double latitude)`：逆変換。北緯40度はZ=−8192。
- `elevation(double y)`：仮想標高m。`(Y−63)×25`。水平距離の圧縮率を適用しない。

`BiomeSource.getNoiseBiome`の入力はquart座標。`QuartPos.toBlock`で絶対ブロック座標へ変換して配置図を読む。地域0〜7、未割当て陸地の緯度帯3種、高山の合計12種を、固定順の`possibleBiomes()`へ列挙する。地下でも同じ水平位置の地表バイオームを使う。

## 版と資産

overworldの`level.getChunkSource().getGenerator()`が`FixedMapChunkGenerator`なら、`mapVersion()`でそのワールドの`MapVersion`を取得する。別生成器からの変換は行わない。`MapVersion`は不変recordで、`mapVersion()`・`formatVersion()`・`manifestHash()`を公開する。

登録IDはChunkGenerator・BiomeSourceともに`ashen_frontier:fixed_map`。MapCodecは各クラスの`CODEC`。生成器のCodecは`map`に`map_version`・`format_version`・`manifest_hash`、`biome_source`に同じ`map`と固定順の`biomes`を保存する。両方の版の不一致は拒否する。未リリースの仮バイオーム形式で作ったワールドは再生成対象で、移行しない。

`BundledMaps.require(MapVersion)`は、クラスパスの`data/ashen_frontier/fixed_map/<map_version>/manifest.json`と資産を照合する。ハッシュはファイルの生バイト列のSHA-256、小文字hex。manifestの`files`は、同じ版ディレクトリからの相対ファイル名とSHA-256の対応。対応形式は1。版が未同梱、形式・ハッシュの不一致、資産の不足・破損では版名を含む例外で停止する。データパックで同梱資産を上書きしない。

現在同梱する`earth_001`は固定シード520026、幅・奥行32768、高さ384、最低Y=−64の配置図。6層の2048² PNG、`index.json`、`geology_profiles.json`を持つ。`BundledMaps.require`はmanifestから必須6層・索引・地質定義の項目が脱落した場合も拒否する。入力・整数対応表・サンプリング・保護域・再生成方法は[制作ツールの契約](../tools/fixed-map/README.md)に記載する。`catalog_version=profiles_001`は整数対応表の版でありcoreの配置カタログ版ではない。

地域図の0は海、1〜7は承認済み地域、255は未割当て陸地。北西大陸本体は全画素を割り当て、南端は西の乾燥地域と東の工業・沿岸工業地帯を延長する。東・南大陸と離れた島嶼には地域を割り当てない。面積比は参考図の輪郭の実測値（北西34.77%、東21.78%、南32.92%、島嶼10.54%）を正とし、凡例の値は使わない。承認済みマスクからの変化を±1ポイントと三大陸の連結で退行検査する。設計12の面積比の記述は別Issueで更新する。

未リリースの`prototype_001`は置き換えたため、その版で作ったワールドは再生成対象。旧形式への移行・既存ワールドの自動更新は行わない。

## 地質と帯水層

`geology.png`の値は同じ版ディレクトリの`geology_profiles.json`の`profiles`を参照する。地質定義の`map_version`・`format_version`を配置図と一致させ、`profile_version`をmanifest/索引の`geology_profile_version`へ記録する。現在の版は`geology_001`。地層は上から順に、母岩Block ID、厚さ範囲、基盤岩、傾斜、断層を定義する。母岩は#50で登録済みの岩石IDかバニラ石材のみ。Dがこの定義から地層を生成し、変位の計算・座標・単位は制作ツールの契約に従う。鉱床の登録ID・母岩・形状・埋蔵量はE（#54）が確定する。

512四方の64×64セルの帯水層と、容量kg・初期量kg・涵養パラメータはF（#55）が担当する。Fが配置図の`map_version`に紐付く版付き資産として追加し、manifestとプリセットのハッシュを同期する予定。Cでは帯水層の値を生成しない。

## SavedDataとの照合

`MapSavedData.verify(ServerLevel overworld, MapVersion expected)`で、初回は照合用記録を作成し、既存記録は完全一致を要求する。戻り値の`mapVersion()`で保存版を参照できる。保存先はoverworldの`data/ashen_frontier_map.dat`。登録イベントが固定マップのoverworldに対して呼ぶため、後続から毎tick呼ぶ必要はない。

サーバーのLevel生成前に既存ファイルも直接照合する。標準SavedDataの破損時の新規作成経路に入る前に停止し、版を置き換えない。生成スレッドはこのSavedDataや可変の研究・気象状態を読まない。

## 起動と後続範囲

専用サーバーはworldとcoreを導入し、`server.properties`へ`level-type=ashen_frontier:fixed_map`を設定する。プリセットに含めるLevelStemはoverworldのみ。シングルプレイは新規作成画面の初回表示で通常プリセットから固定マップへ誘導し、ユーザーの選択変更は維持する。シードが設定済みの再作成画面は誘導しない。既存セーブの読込みは変換しない。

初期ボーダーは中心0、幅32768。Y=−64は岩盤、地表は16bit配置図の値−64を双線形補間し、細部を版固定の滑らかなノイズで補う。地域境界は両側約96ブロックで標高を平滑化し、地表材の混合比を192ブロック幅で変える。保護域・海岸・水域の高さは平滑化・細部ノイズの対象外。陸地は最低Y=64、水域は水面より1ブロック以上低い河床・海底とし、地形上限Y=287により上部32ブロックを建築余地として残す。土壌図の5段階を地表3層へ置き、0は母岩を露出させる。水は索引の水面まで`water.png`に対応するcore流体のsourceを置く。範囲外は空チャンク。`getBaseHeight`・`getBaseColumn`・実チャンクは同じサンプラーを使う。バニラの構造物・装飾・初期Mob生成は実行しない。

Bはcoreの登録、Cは配置図とmanifest、Dはこの生成器・BiomeSourceの地形と分布を実装する。帯水層はFで追加する。配置図上のspawnはX=−8500、Z=−8192、地表Y=100、床Y=80。初期spawnへの適用とシェルター実体の配置は後続で行う。

## DからE・F・KへのAPI

`FixedMapChunkGenerator.terrain()`は不変の`FixedTerrain`、その`map()`は`FixedMapData`を返す。`FixedMapData.require(MapVersion)`はハッシュを照合し、同じ版のPNGを起動中1回だけデコードする。構築後は配列を変更・公開せず、生成スレッドからSavedDataを読まない。カテゴリは北西角サンプルに対する最近傍（8ブロックの同距離点は東・南側）で、最終画素の範囲内だけ端の値を延長する。

| API | 契約と利用先 |
|---|---|
| `terrain.surfaceY(x,z)` | 固体地表Y。範囲外−65。水面を含めない |
| `terrain.column(x,z)` | 列ごとの境界を一度計算し、`state(y)`で土壌・水込みの生成前地形を返す。`surfaceY()`は固体、`topY()`は水面込み。範囲外は全Yが空気 |
| `column.rockState(y)` | E用。土壌・水・雪氷を重ねる前の母岩、底は岩盤。範囲外・地表より上は空気 |
| `map.region(x,z)` | 地域0〜7、255。範囲外0 |
| `map.geologyProfile(x,z)` | E用の地質ID。範囲外0なので`MapCoordinates.contains`と併用 |
| `map.isProtected(x,z)` | E用。施設・経路の水平保護域を16格子で安全側へ被覆し、最大24ブロック広く保護。範囲外false |
| `map.waterMask(x,z)` | F用。1=陸側海岸、2=河道、4=湖、8=海。範囲外0 |
| `map.waterSurface(x,z)` | F用。水面ブロックY。水域以外・範囲外は`Integer.MIN_VALUE` |
| `map.waterQuality(x,z)` | F用。索引対応表の品質ID、乾燥・範囲外0 |
| `map.soilQuality(x,z)` | F用。混合前の土壌ID、露岩・未割当て・範囲外0 |

地層の厚さは固定シード・定義のsalt・プロファイル/層ID・絶対座標を入力とする256ブロック格子の滑らかなノイズで整数範囲内に決める。地層の下部境界だけに傾斜・断層変位を適用し、上の境界以下・Y=−63以上へ制限する。Eで洞窟・鉱床を追加するときは、この母岩と保護判定を使い、土壌・水より先に適用する。Fの有限採水・海域の補給・帯水層は未実装。生成時の流体更新はここでは登録せず、Fの制御へ接続する。

K用のバイオームIDは`ashen_frontier:`名前空間の`polluted_ocean`、`temperate_wasteland`、`abandoned_farmland`、`mining_highlands`、`arid_wasteland`、`industrial_wasteland`、`industrial_coast`、`cold_research_wastes`、`barren_land`、`cold_barren_land`、`warm_barren_land`、`alpine_wastes`。この順でCodecへ保存する。未割当て陸地は絶対緯度60°以上を寒冷、25°未満を温暖、その間を中立にし、陸地の地表Y=210以上は高山とする。バイオームJSONの標準effectsは共通の既定色のみ。霧・砂塵の濃さや独自描画データはKで追加する。

高山の閾値は`FixedTerrain.surfaceY`と同じノイズ・丸め・水域制約適用後のYで判定する。寒冷研究地域（北）の65°N以上の陸地には8層以上の氷床、鉱業・寒冷研究地域の50〜65°Nの高所（Y=180以上）と60°N以上の谷には氷河を置く。谷は四方96ブロックの平均標高より2ブロック以上低い地点とし、氷床内の谷も氷を厚くする。厚さは緯度・標高・用途別saltの固定ノイズから決め、地表高を変えず表面を`snow_block`、その下3層を`packed_ice`、さらに下を`blue_ice`へ置き換える。寒冷バイオーム（寒冷研究・寒冷な未割当て・高山）では氷河以外の乾いた地表にも雪ブロックを置く。海・河川・湖は凍結せず、範囲外は空のまま。雪氷はランダムtickで融けないブロックを使い、季節・融解・採水処理は追加しない。

地表高・スポーン地点の照合とヘッドレス性能計測は`./gradlew.bat gameTest`で実行する。温帯と雪氷の各1,000チャンク計測は`TERRAIN_BENCHMARK`へ条件とp50/p95を出力する。

自然物の配置ID、母岩と鉱物、土壌・水・燃料の品質、回収先はcoreの`catalog/nature.json`を参照する。登録済みAPIと単位、B2/G/D/Eへの引継ぎは[自然物の登録と制作契約](nature-catalog.md)に従う。配置図の仮カタログ版の更新はCの担当とし、Bからmanifestや配置図資産は変更しない。
