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

初期ボーダーは中心0、幅32768。仮地形はY=−64に岩盤、Y=−63〜63に石。範囲外は空チャンク。`getBaseHeight`・`getBaseColumn`・実チャンクは同じサンプラーを使う。バニラの構造物・装飾・初期Mob生成は実行しない。

Bはcoreの登録、Cは配置図とmanifest、Dはこの生成器・BiomeSourceの地形と分布を実装する。地域・水域・地質・帯水層などの未実装Java APIを先に追加しない。配置図上のspawnはX=−8500、Z=−8192、地表Y=100、床Y=80。ゲーム内への適用とシェルター実体の配置は後続で行い、この仮地形で完成を代替しない。

自然物の配置ID、母岩と鉱物、土壌・水・燃料の品質、回収先はcoreの`catalog/nature.json`を参照する。登録済みAPIと単位、B2/G/D/Eへの引継ぎは[自然物の登録と制作契約](nature-catalog.md)に従う。配置図の仮カタログ版の更新はCの担当とし、Bからmanifestや配置図資産は変更しない。
