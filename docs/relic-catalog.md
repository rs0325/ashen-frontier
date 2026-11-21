# 遺物の登録と後続契約

Issue #56の正本入力は`core/src/main/resources/catalog/relics.json`。`nature.json`のID・列は変更せず、同じ`NatureCatalog.Entry`形式で分けている。登録・datagen・素材生成が同じ入力を読む。構成物50ブロック、旧設備32ブロック、単独Item50（容器3・資料1・簡易工具2を含む）、BlockItem82、計132 Item。

分類は`structure`・`equipment`・`item`。既存列の`behavior`で装飾、非稼働設備、保管・分解材、生活物資、食料、容器、資料、簡易工具を区別する。日用品の着用・医療・飲用効果はM3/M4、食料2品は通常の食事動作のみ。Tier 0〜10の代表群を登録し、Tier 11〜12の完成遺物は含めない。配置先の地域は決定しない。

## 既存機能の利用

登録はDeferredRegister、形状・接続・扉の上下処理とモデル生成はMinecraft/NeoForge標準、破壊適性は`MiningRules`、品質は`NatureProperties.QUALITY`、知識は`ResearchManager.reportKnowledge`を使う。新規依存なし。外装だけは中空の衝突形状とモデルが必要なので専用Blockを使う。外部ケイパビリティ・ネットワーク・設備tickerは持たない。

全Itemの`MaterialData`はBと同じ暫定会計単位1 kg。内容物のkg量と容器自身の会計質量は別であり、実体積から求めた質量ではない。未調査の硬度・密度・比熱等は設定しない。新しい現実の物性値の採用はなく、追加のドメイン知識・出典はない。

## 回収と配置

- `RelicCatalog.entries()` / `require(String)`で分類・回収先・担当ドメイン・用途を読む。未知IDは制作エラー。
- `RelicContent.block(String)` / `item(String)`は登録後に呼ぶ。共有名前空間は`ashen_frontier`。
- 全32設備は`damaged_*`のまま、core所有の`RelicEquipmentEntity`（type: `ashen_frontier:relic_equipment`）を保持する。状態は水平向きのみ。後続は別IDへ置換せず機能を足す。
- 設備は`needs_simple_tool`ブロックタグ、`stone_hammer`と`simple_wrench`は`simple_tools`Itemタグ。一般の回収は適正工具でブロックを破壊する1回だけ。追加の右クリック回収やBE削除時のドロップを作らない。lootにも工具条件を設け、爆発等の工具なし回収を防ぐ。
- 構成物はpickaxe適性。扉は下半分から1個、二重ハーフは2個を回収する。
- 柵・扉・階段・ハーフはカタログからMinecraft標準のBlock/Itemタグ（`fences`・`doors`・`stairs`・`slabs`）も生成する。4種類の柵は非木製の同族として相互接続する。「壁」は全面の壁材Blockなので低いWallBlock用の`walls`には入れず、窓はIronBarsBlockの型による接続を使う。
- `recovery`は一般回収時に1個得るItem ID。loot倍率・初回開封・内部インベントリはG2の責務。保管容器は現時点では非稼働の外観であり、収納GUIやランダムlootを持たない。

## 容器と資料

- `RelicProperties.container(String, ResourceLocation, double)`は配置・loot・初期配布向け。水/油はBの品質IDを検査し、試薬は名前空間付き試薬IDを指定する。内容量は正の有限値kg。
- 品質は`ashen_frontier:quality_profile`、量は`ashen_frontier:content_mass_kg`、試薬は`ashen_frontier:reagent`、資料は`ashen_frontier:document`。品質・量・試薬・資料が違うItemStackは同じスタックとして扱わない。
- 通常の容器Itemは暫定配布単位1 kgで、水は`clean_water`、油は`light_low_sulfur_crude_oil`、試薬は物性未確定の`archived_reagent`。配置側は上記factoryで必要な品質・量を明示する。試薬名は成分や安全性の確定を意味しない。
- `RelicProperties.document(ResourceLocation)`で資料を作る。通常の資料Itemは`ashen_frontier:field_manual`。使用するとサーバーから所属チームの知識へIDを記録し、消費しない。資料を捨ててもチーム知識を失わない。初期資料の具体的内容と配布保証はJで設定する。

### 採取の判断

採水・採油・開封操作はF/M4へ引き継ぐ。M2の自然水域には容量kg・有限水源消費・元の海域マスク限定補給の責務があり、汎用の容器Itemだけでは正しく完結しない。BのmB量をkgへ換算する密度条件も未確定。今回は任意の換算値やクライアント側の採取を追加せず、密封済みの品質・量を保存する契約までを実装する。Fで採取を完成させる場合も、指定sourceの消費・海域補給・容器更新をサーバーで一体処理する。M4の汲上げ・浄水と混同しない。バニラのバケツ採取禁止を維持する。

## G2・H・I・Jへの引継ぎ

G2はカタログの`behavior`と上記容器・資料factoryを使って有限lootを組む。生活消耗品倍率は資料・初期配布保証と分離して設計する。Hは構成物の形状ID、Iは設備本体と`recovery`・`domain`を参照してテンプレートを作り、BE付き設備と生きたEntityを混同しない。Jは簡易工具2品と品質・kg量・資料IDを明示した初期物資を配る。新しいID一覧を別に増やさず、カタログから照合する。

## 素材

組込みGPT imageの原本・プロンプト・Pillow変換・市松背景の確認シートは`tools/relic-assets/`。Itemは50品それぞれ異なる図柄で、縮小前に原本のマゼンタ背景を除去する。構成物の材質は形状間で共有する。基本16px、設備は全面を筐体で埋めた32pxの前面・側面・上面を持つ。設備の各面は`equipment-preview.png`でも確認できる。外部素材の転載なし。登録・形状モデル・翻訳・loot・材料データは`:core:1.21.1:runData`で生成し、`.cache/`を除いてGit管理する。
