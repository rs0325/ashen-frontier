# 自然物の登録と制作契約

Issue #50の入力は`core/src/main/resources/catalog/nature.json`。登録、英日表示、モデル、回収loot、材料・燃料データマップ、画像生成が同じ入力を読む。分類の正本は[設計12](design/12-m2-fixed-map.md)。このカタログはBの自然物を全IDへ展開したもので、Gが所有する旧設備・部品・容器の追加は#56で同じ入力へ統合する。worldの配置資産はここでは変更しない。

## 列と単位

- `id`は`ashen_frontier:`を省略した登録パス。BlockItemはBlockと同名、流動Fluidは`flowing_`を付ける。`kind`はrock/soil/ore/coal/natural/item/water/oil。
- `en`・`ja`は表示名。`material`は材料分類、`domain`は機能を引き継ぐ担当。`behavior`は現在の動作。
- `host`は鉱石の母岩。バニラ岩石名はminecraft、それ以外はカタログ内の岩石を参照する。`hardness`は母岩に揃えたゲームの採掘時間係数で、Paやモース硬度への換算値ではない。B2が道具適性・破壊進行の一般規則を担当する。バニラのpickaxe採掘タグは通常回収のため生成する。
- `recovery`は通常破壊で1個回収するItem。名前空間のない値はashen_frontier。液体行では回収対象のsource Fluidを示し、容器Itemを意味しない。容器の実装はG。
- `mass_kg`は現段階のインベントリ上の1単位を1 kgとする仮の会計単位。ブロック体積から求めた現実の質量ではない。密度が未調査でも架空の密度を逆算して埋めない。実体積との対応や後続の加工歩留まりは、その工程の設計時に決める。
- `physical`は確認済みの物性だけ。今回は石膏2、石英7、燐灰石5のモース硬度を設定（[NPS](https://www.nps.gov/articles/mohs-hardness-scale.htm)）。鉱石全体に含有鉱物の硬度を転用しない。未調査のPa硬度、密度、比熱、融点、熱伝導率は省略する。
- `fuel`は同じJSONの`fuel_profiles`へ参照する品質ID。数値と出典は8プロファイルごとに一度だけ定義し、石炭のBlockItemと原料Itemから共通参照する。datagenはsource/flowingにも同じ値を展開する。発熱量J/kg、揮発分・灰分・硫黄分は質量比0〜1、API度はSI密度と別の指標。未調査項目は省略する。組成コンポーネントへ燃料特性を混ぜない。
- `source`・`physical_source`と燃料プロファイルの`source`は同じJSONの`sources`への参照。出典は物性または地質の根拠であり、架空世界での実際の配置位置を保証するものではない。

## 鉱床の制作ルール

全母岩×全鉱物は作らず、JSONに列挙した30組だけを配置に使う。母岩中の鉱化脈、スカルン化・グライゼン化などの変質部は元の母岩IDへ集約する。砂岩中のルチル・モナザイトは固結した古い漂砂鉱床を表す。この単純化は新しい岩石分類を追加せず、承認された母岩差を表すためのもの。

北西大陸の必須資源に対しては、山脈とその南麓の珪岩の鉄、花崗岩・安山岩の銅、花崗岩の錫、堆積層の石炭・石灰岩・粘土、内陸乾燥地域の粘土質堆積物中の硝酸塩、砂岩・珪岩中の石英を選べる。これらは配置側に渡す候補であり、露頭・鉱床の座標や規模はD/Eが確定する。

硝石原鉱は`raw_nitrate`とし、乾燥湖床の混合硝酸塩を表す。硝酸カリウムの純品や、採掘直後に黒色火薬へ使える物とは扱わない。硫黄は安山岩の噴気変質部候補と黄鉄鉱を登録するだけで、取得経路の選択・焙焼レシピ・地熱地域の配置は行わない。

## 燃料の数値と適用範囲

瀝青炭は[USGS Circular 1143 Table 4](https://pubs.usgs.gov/circ/c1143/html/text.html)の試料212277（Mary Lee炭層）のASTM分析を代表プロファイルに採用する。揮発分32.2%、灰分15.1%、硫黄1.2%、発熱量12,060 Btu/lb。Btuは国際蒸気表Btu（1,055.05585262 J）、lbは0.45359237 kgとして約28.052 MJ/kgへ換算する。異なる試料・分析法の灰分16.0%を混ぜない。炭種全体を表す普遍的な定数ではなく、含水率2.6%の当該試料をゲームの代表として採用した値である。泥炭・褐炭・無煙炭は品質IDのみを確定し、未調査の特性はemptyで扱う。

原油は[OSHA Technical Manual Table IV:2-2](https://www.osha.gov/otm/section-4-safety-hazards/chapter-2)の代表概数から、軽質低硫黄にNigerian Light（API 36、硫黄0.2%）、軽質高硫黄にSaudi Light（34、2%）、重質高硫黄にSaudi Heavy（28、2.1%）を割り当てる。軽質/重質は同表の呼称を採用し、他の分類閾値と混用しない。重質低硫黄は数値未設定。原油の発熱量や揮発分・灰分は調査せず省略する。API度からSI密度への換算条件もまだ確定していない。

## 後続からの利用

`io.github.rs0325.ashenfrontier.core.catalog`を使用する。

- `NatureCatalog.entries()`と`require(String)`で分類・回収先・硬さ・出典を読む。未知IDは制作エラー。配置とルートに独自のID一覧を増やさない。
- `NatureContent.block(String)`・`item(String)`は登録完了後に呼ぶ。`fluids()`のキーは品質ID、値はFluidType/source/flowing/液体Blockの対応。
- `NatureProperties.QUALITY`は`ashen_frontier:quality_profile`。値は共有名前空間付きResourceLocation。土壌・石炭Itemには既定プロファイルを持たせる。Gの容器にも同じIDを保存する。組成・汚染は既存のItemPropertiesを併用する。
- `NatureProperties.fluidStack(String, int)`は品質付きsource FluidStackを作る。引数量はNeoForge標準のmB。Gの保存量kgへは未調査密度で換算せず、容器側でkgと品質を保持する。
- `FuelData.ITEM`と`FuelData.FLUID`は`ashen_frontier:fuel`。石炭のBlockItemと原料Item、原油のsourceとflowingはそれぞれ同じプロファイルを参照する。Gの油層・ドラムはその`profile`と一致させる。未設定値を0や別炭種の値へ自動置換しない。
- `MaterialData.TYPE`は既存どおりItem用。Blockは`block.asItem()`に結び付いた値を読む。液体BlockにBlockItemはなく、材料会計はGの容器で扱う。

水の`surface=true`は清浄水以下の6段階だけ。Dは純水・超純水を自然水面へ配置しない。油は地表水域の候補に含めず、Eが有限な深部油層として扱う。全流体の水源増殖を禁止し、流体間で品質を上書きしない。`NatureLiquidBlock`はバニラバケツでの採取と流体によるブロック置換も拒否し、源や品質を失わせない。Gの専用容器はこのバニラ採取処理を流用しない。海の補給・水域の容量管理はworld/climateの後続責務であり、海水Fluid自体を無限増殖させない。飲用効果、栽培、精製、焙焼、道具製造はまだ持たない。

## 素材と検証

画像原本、組込みGPT imageのプロンプト、16pxへの変換・母岩合成・周期流体模様のスクリプトは`tools/nature-assets/`。縮小後の確認画像も同梱する。生成コマンドは同ディレクトリのREADMEを参照する。

`runData`でモデル・翻訳・loot・データマップを再生成してから`build`を実行する。単体テストがカタログ参照・生成物・禁止タグ・共有燃料プロファイルを照合し、GameTestがItem/Fluid/BlockStateの保存往復、品質維持、有限性、実際のloot解決を検査する。バケツはサーバーのアイテム使用処理から実行し、空バケツで12品質の源が残り、水入りバケツで固体面の隣にある12品質の源・流動状態が変わらないことも検査する。対照としてバニラ水源の回収と空き位置への配置が成功することを確かめる。バケツ試験はcore名前空間の3×4×5の`nature_buckets.nbt`、それ以外は既存の`empty.nbt`を使う。両テンプレートの`size`はInt3件のTAG_List。
