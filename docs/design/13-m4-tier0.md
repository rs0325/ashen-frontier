# M4: Tier 0（確定）

[08-phase1.md](08-phase1.md)のM4を対象とする。2026-11-24、Q1〜Q8をすべて推奨内容で承認。設計正本はdocs/design/13-m4-tier0.md。M2の保全区の具体配置は未決定であり別途承認を要する。

## 目的

石器、火、有限水の処理、栽培、調理・保存によって安定した生活を成立させる。初期配布・遺物は立ち上げを助け、日常消耗品は自製・増殖で維持する。

## 範囲の調整【確定】

| 含める | 含めない |
|---|---|
| 礫器→両面加工、植物繊維の紐と柄、弓錐着火、土器、手動木工 | 原始時代の全技術、戦闘体系・武器・防具、鉱石の万能採掘 |
| 沈殿・布砂ろ過・煮沸、手動ポンプ・雨水回収・太陽熱/燃料式蒸留 | 有毒水/海水の飲用化、純水/超純水の製造、配管・動力網 |
| 主食・豆・葉菜・菌、繊維・薪の再生産、清浄な堆肥、調理・乾燥保存 | 交配の遺伝規則、汚染作物の移行係数、土壌混合・中和・全面除染、食品工業 |
| チーム研究、端末とJEI/EMIの条件案内 | 高精度分析器、M3の生理/疾病/気象計算の代行 |

土壌の正本は#79承認の「土質＋6汚染種×各5段階」。設計12の旧一覧と#55本文の「土壌5段階」は不一致として関連担当へ返す。M4で旧仕様を復活させない。

## 登録とモジュール【確定】

Mod IDと登録名前空間は区別する。既存IDはashen_frontier:。新規も同名前空間とし、モジュール間で衝突を検査する。自然配置・初期配布する物はcoreに登録し、機能はドメインModに置く。

| 系統 | 主な既存ID・新規ID案 | 所有 |
|---|---|---|
| 採取素材 | 既存pebble/flint/dry_branch/plant_fiber、minecraft:clay_ball/sand/gravel/oak_log | coreの既存自然物・地質、機能primitive |
| 遺物・配布 | 既存stone_hammer/simple_wrench/salvaged_cloth/wood_scrap/glass_scrap/damaged_hand_pump/sealed_water_container/preserved_rations/canned_food/archive_document | core登録、primitive/agricultureが必要機能 |
| 石器・木工 | chopper/biface/stone_flake/tool_handle/hafted_axe/stone_adze/digging_stick | primitive |
| 繊維・火 | cordage/fiber_cloth/fire_drill/tinder/knapping_surface/hearth/drying_rack | primitive |
| 土器 | green_pot/dried_pot/ceramic_pot/ceramic_lid/ceramic_tube | primitive |
| 水 | water_filter/hand_pump/solar_still/fuel_still/rain_collector、既存8水質と品質・kg量成分 | primitive。水質登録と現況契約はcore/#55 |
| 食料植物 | barley_crop/barley_grain、soybean_crop/soybean、leafy_crop/leafy_greens | core登録→agriculture |
| 再生資材 | flax_crop/coppice_willow、既存plant_fiberと新規crop_residue/fuelwood | 自然物core登録、加工物agriculture |
| 菌類 | 既存preserved_fungus、新規oyster_mushroom/mushroom_bed | 自然物core→agriculture、菌床agriculture |
| 食品・改良材 | grain_meal/cooked_staple/cooked_soy/vegetable_soup/dried_vegetables/dried_mushrooms/compost/compost_bin | agriculture |

日英名・現実の対応物・個別の入手製法は末尾の「登録候補の詳細」を参照する。5植物＋菌以外へ増やさない。種は既存preserved_seedにcrop_speciesを付けて区別し、種子アイテムを種別ごとに重複登録しない。

primitiveはcoreへ依存する。agricultureはcore・climateに加えprimitiveへ依存し、primitiveの加工型に食品レシピを供給する。逆向き依存は作らない。これは02の依存表変更を伴う承認済み事項。環境入力の最小契約はcoreに置き、climateが提供する。追加の汎用環境Modは作らない。

バニラの原料は既存の地質・建築表現との一致を理由に使用する。バニラ道具・バケツ・金属製法は復活させない。汎用共通タグや外部ケイパビリティは公開しない。バニラかまど/焚き火/パン等の迂回だけをM4範囲で個別に停止し、建築ブロックを一括削除しない。

## 主な製法【確定】

加工は台石・炉・槽・棚で行う。クラフトグリッドは単純な組立だけ。最初の台石・礫器・紐・火起こし具・播種を研究で自己ロックしない。

| 工程 | 操作 | 条件と結果 |
|---|---|---|
| 打製 | 台石へ材料を入れ、打撃具で加工開始 | 石材1 kg→礫器0.7 kg＋0.1 kg剥片3個、礫器→両面刃0.5 kg＋剥片2個。10/20実秒。工具は保持して摩耗 |
| 紐・柄・布 | 台石で撚り/切削、枠で編む | 繊維1 kg→紐0.1 kg×9と損失0.1 kg。枝1 kg→柄0.8 kg＋損失0.2 kg。10〜30実秒 |
| 柄の取付け | 所持品2×2組立 | 両面刃0.5＋柄0.8＋紐0.1 kg。斧/手斧を作る。一般採鉱用工具にはしない |
| 火起こし | 弓錐で炉を30実秒操作 | 火口・乾燥薪・覆いが必要、確率失敗なし。離れると停止、成功時に火口消費 |
| 土器 | 台石成形→棚乾燥→炉焼成 | 粘土2＋砂0.5＋水0.5 kg。成形10実秒、乾燥1ゲーム日、焼成973〜1123 Kで120実秒。完成土鍋は4 kg容量・非スタック |
| 沈殿/ろ過 | 静置容器→布砂砂利槽 | 濁水4→生水3.6＋保持水0.4 kg。静置半ゲーム日、ろ過30実秒。布10バッチで交換 |
| 煮沸 | 炉上土鍋 | 生水4→清浄水3.8＋蒸発0.2 kg。局所沸点到達後60実秒、高地約2000 m超は180実秒。冷却保管。化学汚染は除けない |
| 手動揚水 | 井戸到達を確認し手動操作 | 10実秒で最大1 kg。現在量から減算、元の水質を保持。浅井戸吸上げのSI m/気圧を検査 |
| 太陽熱蒸留 | 原水投入後の日射積算 | 非揮発性汚染を代表する汚染水のみ、4 kgから上限2 kg、晴天2 kg/m²/ゲーム日目安。日照・冷却面が必要 |
| 燃料蒸留 | 陶製蒸発器と空冷管を炉で加熱 | 同4→2 kg、残水2 kg。燃料入力3 kW、熱利用20%。20℃から約5.85 MJ有効熱、約9740実秒、気乾薪約1.83 kg |
| 雨水回収 | 上空が開いた集水面と容器 | 質量＝面積m²×降水mm×0.7 kg/(m²·mm)。通常raw、汚染降雨はcontaminated。凍結時停止 |
| 栽培 | 掘り棒・種使用・手灌水・収穫 | 環境適合区間のみ成長、乾燥は停止、未熟破壊は種返却。採種モードを表示 |
| 菌床 | 基質加熱→冷却→接種 | 清浄残渣・水・菌種を消費し、3ゲーム日で子実体と継代用菌種。基質は交換 |
| 調理 | 台石で手挽き→炉で煮炊き | 主食/葉菜/菌は標準120実秒、豆は浸漬半ゲーム日＋300実秒。清浄水を消費、汚染/腐敗を無条件に初期化しない |
| 保存 | 下処理後の炉脇棚 | 323〜333 K、湿度適合、1ゲーム日。乾物の腐敗閾値10倍、永久保存なし |
| 肥沃化・薪 | 槽熟成/萌芽更新/棚乾燥 | 堆肥4日、薪乾燥1日、木の再生6日。残渣を堆肥/菌床/燃料で二重計上しない |

質量は材料データ/既存CONTENT_MASSを使う。固体の部分消費基盤を増設せず、新規品の単位質量と個数、明示損失で収支を合わせる。水の部分移送はkg残量を保存し、mBとの換算は密度に基づく。

汚染水蒸留→清浄水は設計03を引き継ぐが、現実の揮発性汚染を除去できない制限を明記する。原水が非揮発性汚染の代表であるという簡略化はQ3で確定する。有毒水/汚染海水は拒否。濃縮残水は既存品質を保つ飲用不可・再投入不可のバッチ状態として保持し、新規第9水質を作らない。持出し・再配置でも残水制限を維持する。安全廃棄操作に抽象化し、廃棄でsourceを作らない。

低温土器の漏水省略、数十秒の石器加工、短い焼成/加熱時間はゲーム簡略化。燃料と水の熱収支は別に保ち、機械/燃焼の実秒と長期のゲーム秒を混ぜない。

## 作物・土壌・栄養【確定】

16面を大麦8・大豆4・葉菜4とし、周期4/4/2ゲーム日、1面収量0.4/0.2/0.25 kg。麦1面0.025 kg以上、大豆は1/8を再播種へ取り分ける。葉菜は1面を採種へ切替。菌・亜麻・薪の区画は別枠。1ブロックは集約栽培区、作期と水量も圧縮する。

標準食の例は麦乾燥換算0.5＋大豆0.2＋葉菜0.25 kg/日。仮受入値は10 MJ、たんぱく質0.06 kg、脂質0.04 kg、水分3 kg/人日とM3簡略ビタミン区分の充足。医学的な基準値ではない。固定栄養データと加工後の値は同じ単位でM3へ渡す。

全微量栄養素（B12等）の長期充足は植物中心の標準食では保証しない。M3第1期のビタミン類は簡略区分、厳密な微量栄養管理は後続提とする。土のN/P/K収支と亜麻精練も集約するゲーム簡略化としてQ2/Q7で承認済み。現実の完全栄養食・閉鎖生態系の再現とは説明しない。

土の質は堆肥で改善できるが汚染は減らさない。清浄段階でも食用の適合保証ではない。M3との適合未確認床では食用栽培を開始させず、安全床への交換/移動を案内する。土段階を食品の数値汚染へ変換しない。汚染作物・移行係数と酸中和等は後続提。

## 研究【確定】

チーム研究、データパックの前提/条件/解放、サーバー実績を使う。初期資料は固定点で再閲覧できる。最初のレシピは未研究で試せる。資料の紛失と知識の消失を分け、チーム移籍規則は変更しない。

| ID（t0/以下） | 前提 | 達成条件→解放 |
|---|---|---|
| stoneworking | なし | 礫観察＋礫器製造→両面加工/柄付き工具 |
| fiberworking | なし | 繊維観察＋紐製造→布/ポンプ木工 |
| fire | なし | 摩擦着火→焼成と加熱調理 |
| pottery | fire | 基本土鍋焼成（fireで製法解放済み）→蓋/陶管/浄水設備 |
| drinking_water | pottery | ろ過と煮沸→配置/貯蔵の手引き |
| cultivation | なし | 初期栽培資料＋播種→堆肥/管理 |
| water_supply | pottery, fiberworking | 水域/井戸候補観察→4水設備 |
| cooking_storage | fire, cultivation | 基本加熱主食製造→乾燥/基質処理/継代 |
| self_support | drinking_water, water_supply, cooking_storage | 自製水と各食料、種/菌種確保→M5への手引き |

24日試験をプレイヤーの研究待ち時間にしない。端末の既存研究descriptionと達成表示を使い、条件IDへ翻訳・具体操作を補う。材料/量、設備、時間系、未達/停止理由、水質と工程可否、再採取案内を表示する。未拡張端末に正確な温度・化学濃度の測定能力を与えない。

JEI/EMIは既存CommonRecipeカテゴリに品質・kg・工具返却/摩耗・副産物・時間系を追加する。未解放条件も確認できる。自動転送を追加しない。両Modなしでも端末/設備だけで進める。

## 水量の所有・加工確定・容器（2026-11-24追加確定）

- 容器/水源から槽へ1操作で移した後の水量は設備が所有する。世界の水源の現在量・採水/移送とその原子性は#55が所有する。
- 加工は開始時に槽の必要量を確認する。予約は行わず、サーバーで全出力を確定する時点で槽からの消費と出力を一度に確定する。
- 取消・出力満杯・保存再開では未消費水を槽へ残す。水源へ戻さず、複製/消失させない。
- 容器は同じIDでCONTENT_MASSと水質を保持し、0 kgは空容器。部分消費では品質を維持する。異品質の混合は規則がないため拒否する。
- #106は設備槽と加工を実装し、#55より先に必要な容器↔槽の移し替えだけは先行実装できる。世界の水源・帯水層・その量保存には触れない。

## 既存実装の再利用と差分【確定】

- CommonRecipeの入力/複数出力・RecipeConditions・RecipeTypeRegistryを使用する。matchesは実行条件を強制せず、assembleは先頭出力だけのため、設備が全条件・全出力・消費/返却をサーバーで一度だけ確定する。
- 品質一致には導入済みNeoForgeのDataComponentIngredientを使う。水kg・入力役割・消費/損失・属性継承は同レシピIDの限定的加工データで補足する。独自レシピ検索器/実行言語を作らない。参照不正はreloadで拒否、加工中レシピ変更は停止して未加工入力を返す。
- 型ごとの時間系表示を最小追加する。既存型の実秒の意味は維持。作物/堆肥のデータは生育責務に置く。
- MaterialData/FuelData、ItemPropertiesの温度・摩耗・汚染・腐敗・丸め、GameTimeを使う。既存の数値汚染・soil_contamination・容器保存形式を変更しない。
- 新しいデータは固定nutrition、必要な可変栄養、crop_species、設備の加工/残水状態。不要な将来遺伝子フィールドを置かない。
- ResearchManager.registerConditionType/report/reportKnowledge、チーム保存、TerminalAttachments/Screenを使用する。配布履歴SavedDataを端末や研究に置かない。
- 機能Modがないcore登録遺物は静的回収物のまま。旧設備のID変更で修復を表さない。

## M2・M3・後続への境界【確定】

| 対象 | 既存の所有と依頼 |
|---|---|
| #55 | 水質/現在量/容器/土壌/帯水層の正本。M4の採水はここを減算。固定サンプラーは初期定義であり補給でない。inventory参照はMapVersion内 |
| #59 | 施設・無道具素材・経路。M4から再採種/粘土/ろ材/再生薪・繊維の必要条件を渡す。無断で配置しない |
| #60 | 約16面・有限貯水・UUID配布。仮5日分の量調整は既存設定データへ。後入り/満杯/同時受領を再実装しない |
| M3 climate | 気温K/圧力Pa/湿度/降水と日射積算/雨水質/換気/涵養。地下水残量は#55と同じ保存所有を用い二重化しない |
| M3 survival | 摂取水分kg・栄養J/kg成分・腐敗・汚染・放射能から症状/感染/体温を判定。バニラ満腹度との二重付与なし |
| M5/M6 | 手挽き→石臼、手動→動力ポンプ、木工→製材、金属部品新製・ガラス製造。M4容器IDを変更せず接続する |

M3が提供する入力の単位・意味・所有者は本書で確定。生理/気象そのものと閾値・病原体の設計はM3で扱う。単調時間と日長設定を区間で扱い、dayTime変更で進捗を巻き戻さない。環境未接続時の常時晴天/無害フォールバックは配布しない。固定入力はテスト専用にする。

## 進行・詰み防止【確定】

- 台石・打撃石・礫器・紐から再開可能。適性工具なしに幹・鉱石・遺物を破壊して迂回しない。
- 初期資料は再閲覧、初期必須品はUUID配布。死亡や移籍で再配布しない。
- 全種/菌喪失の回復は、母株/菌種を残す固定の生きた繁殖保全区を追加する。2日以上の再生と区画水/栄養予算を持ち、母株全破壊の保護範囲を別途承認。座標・外観はM2提案で決める。無から種を配る機構は作らない。
- 水は6 kg/人日を基礎予算、6日無雨で36 kg/人以上の基礎貯蔵。菌/繊維/薪畑の追加用水を上乗せ。太陽熱・燃料蒸留を各プレイヤーの毎日必須にしない。
- 有限地下水は正しく枯れる。承認気候の降水/涵養がある条件で長期収支を閉じ、任意長の永久干ばつ保証はしない。
- 満杯出力は設備内保持、破壊/再設置でも現在量と加工開始時の入力を保存。採水・研究・完成はサーバーで一度だけ。
- 管理者rescueは不具合用。通常の種増殖/水供給の代用にしない。

## #106の共有APIと保存形式（2026-11-24）

### モジュールと所有者

`ProcessingStation`は設備の加工状態であり、Block/BlockEntity/道具の本体ではない。槽・入力・出力・工具・進捗を同じ設備の保存へ含める。設備は実際に移した物だけを`setInput`/`setTool`へ渡す。インベントリ間の移動と外部ケイパビリティは提供しない。

primitiveとagricultureはcoreを参照し、agricultureからprimitiveへのソース依存を追加する。climate未実装の基盤段階ではローダー上のclimate依存はoptionalとし、気象が必要な加工は`EnvironmentSample`未提供なら停止する。機能上の気象依存は維持し、代替の値は持たない。

自然配置する植物・収穫物の実体登録は#111でcoreへ追加する。#106は栄養/種別の成分と加工型を登録し、設備や植物の仮の本体を先取りしない。

### 加工データ

`CommonRecipe`の同じJSON内に任意の`processing`を持つ。既存RecipeManager・serializer・同期を使い、別のレシピ辞書/再読込機構は追加しない。省略時は既存どおり材料各1個。型の時計は`TimeBasis`で指定し、従来の型は実秒のまま。

| フィールド | 意味 |
|---|---|
| inputs | 0始まりのIngredient番号・count・保持するかretained。実スロット順ではない |
| water | quality（水質ID）、mass_kg（確定時に槽から引く正のkg） |
| loss_kg | 工程損失kg。レシピ制作者が入力と全出力の物質収支を確認する |
| tool_wear | catalyst工具の摩耗増分。既存の摩耗刻みへ切り上げ、工具は保持し、Wear上限超過は拒否 |
| inheritance | 原料番号と出力番号。腐敗・数値汚染・放射能・個別栄養を保持。複数原料の混合モデルは導入せず、同一出力への重複指定は拒否 |
| requires_environment | 気象入力の要否。温度/圧力条件またはGAME時計の型も未接続では停止 |

番号の範囲/重複、個数、水質参照/正量を読み込み時に検査する。成分付き入力と触媒の部分一致はNeoForgeの`DataComponentIngredient.of`を使う。

`matches`は材料照合、`assemble`は先頭出力のプレビューであり加工確定ではない。`start`は現在のRecipeManagerから材料・設備・研究・水量を確認し、予約/事前消費しない。`advance`は単調なgameTime差を型の時計へ換算し、設備が区間に供給した実エネルギーJを受ける。環境条件外の区間は進めない。

`finish`は現在のレシピ定義・入力・工具・研究・条件・水量と全出力の空きを再検査し、入力消費・槽消費・工具摩耗・複数出力を一操作で確定する。満杯は状態保持、取消は入力/槽を変更しない。別プレイヤーによる再開始/二重確定は成功しない。変更APIはサーバースレッド専用。

`save`/`load`で設備内の全状態を保存・復元する。`load(tag, registries)`はBlockEntityの`loadAdditional`が受け取る`HolderLookup.Provider`を使い、プレイヤーやLevelを必要としない。復元後最初の`advance`で時計を合わせ、未ロード時間は加工へ加えない。現在のレシピ/研究を再検査し、reloadで定義が削除/変更されたら加工だけ停止して未消費入力/水を残す。完了後保存から再生成しない。未リリースの新規保存形式なので移行は追加しない。

材料照合は空きを除いた材料数を検査し、返す番号は設備の元のスロット番号とする。消費・属性継承もその番号を使う。研究とレシピの両方がロードされた起動時・reload完了時に、`conditions.research`を有効な研究定義と照合する。存在しない研究（前提不正で無効な研究を含む）への参照はレシピID・研究IDをログに示し、そのレシピをRecipeManagerから除外して同期する。

### 槽・容器

`WaterTank.Contents`は容量kg・現在量kg・水質IDを保存する。`fill`は#55等が既に確保した水を受ける入口で、水源を減算しない。先行実装する`fromContainer`/`toContainer`は実移送kgを返す。容器容量は設備/UIが登録仕様から渡し、複数個スタックは事前に1個へ分割する。異品質混合・不正量・容量超過を拒否する。空容器は残していた品質によらず再充填できる。

空槽は品質なし、空容器は同ID・0 kg。部分移送で品質は変えず、`RelicProperties.container`も0 kgを作成できる。世界のsource・帯水層・その保存は追加しない。

水量の分解能は1 mg（0.000001 kg）とする。`WaterMass`を唯一の単位変換境界とし、槽の量・容量と、容器↔槽の加減算・比較・移送はすべてlongのmgで行う。許容差による比較はしない。槽が0 mgになったら品質を消す。既存`CONTENT_MASS`と槽のkgのdouble保存形式は維持し、保存値を読み込む時に最寄りのmgへ丸める（ちょうど半分は切上げ）。表示・保存・既存kg APIの境界でだけkgへ変換する。

レシピの加工水量と水入り出力の量も同じ格子を使い、格子に乗らない制作値は読み込みエラーとする。保存値の丸めと制作データの検査を区別する。doubleのkg保存で1 mgを区別して往復できる範囲として、水量・容量の数値上限を10億kgとする。0 mgへ丸まる移送指定や容量は不正指定とする。毎tick処理は増やさず、変換は操作・読込・保存時、量の演算は定数時間の整数演算で行う。

### 食品・環境

固定栄養は`data/<namespace>/data_maps/item/nutrition.json`で1個当たりのenergy_joules、protein_kg、fat_kg、water_kg、定性的なvitamin_groupsを持つ。個別の`ashen_frontier:nutrition`を固定値より優先し、未定義値を栄養あり/無害と補わない。`crop_species`は既存保存種子/菌類の種別IDで、栽培や遺伝を計算しない。

`FoodIntake.from`は1個分の栄養・腐敗・数値汚染・放射能を読み取るだけで、消費・満腹度・生理更新はしない。実際の摂取は#112/M3がサーバーで一度だけ行う。

`EnvironmentSample`の温度K・圧力Pa・相対湿度0〜1はclimateが提供する。降水kg/m²と日射J/m²はstartTick〜endTick区間の積算、ticksPerDayは区間の日長で、日長変更時は区間を分ける。降水時は水質IDも必要。覆い/換気は局所の状態で、安全/疾病判定ではない。生産コードに固定環境や既定提供者を持たず、固定値はGameTestだけで使う。

### 表示と検証

`CommonRecipe.displayLines`をJEI/EMIで共有し、時計・個数/保持・水質/kg・工具摩耗・損失・容器出力を英日で表示する。種別も既存自然物のツールチップに表示する。精密測定を解放するものではない。

GameTestで容器往復/異品質/0 kg、研究/設備/環境/個数、同時操作、満杯、取消、保存復元、reload変更、全出力、工具保持/摩耗、属性継承、Codec/同期と参照不正を検査する。時計換算は単体テスト、新規モジュール配線はagricultureのGameTest。GUIの視覚確認項目だけをpublish.mdに残し、この作業でクライアントは起動しない。

## 性能【確定】

| 対象 | 方法 |
|---|---|
| 手作業・研究・採水 | 入出力イベントで更新 |
| 稼働炉・槽 | 稼働中だけ20tick間隔の標準scheduled tick、経過秒で積算 |
| 生育・受動設備 | 気候の10〜30実秒更新と操作/保存時に区間評価 |
| 腐敗 | 既存ROTを温度区間ごとに確定、毎tickの全インベントリ走査なし |
| アンロード | 受動生産は停止。現在天候を過去へ適用しない。腐敗は最後の保存条件で長期進行 |
| 同期 | 設備画面/本人の端末に変化分のみ。煙/火花は描画設定で無効化可能 |

4人・食料64面＋補助64面＋稼働設備40件で約53評価/秒の構造見積もり。毎tick168件なら3360呼出/秒となる実装は避ける。追加処理p95 1 ms/tick未満を目標として実測する。計測済みではない。

## 完成条件と検証【確定】

1人と4人で、初回収穫後24ゲーム日を、無降雨6日・弱日照2日の区間込みで運用する。後半12日の水/燃料/食料/種/繊維の在庫が継続減少しない。4人は64面相当へ拡張でき、補助畑と再生資材の水/土地を含む。

- 単体: 質量・熱・水/日射・日長・生育・種/燃料/栄養収支、腐敗と調理保存継承。
- GameTest: 無道具再開、採掘適性、全8水質、同時操作、出力満杯、枯渇、濃縮残水再投入拒否、研究/バニラ迂回、後入り、死亡、保存再起動。
- M3統合: その生活経路から脱水・栄養不足・低体温・感染症が発生しない。換気と適合床を含む。
- 必須buildとgameTest、英日翻訳、datagen、基準版へReset active project、共有契約の重点反証。
- 手動は端末/設備/JEI/EMI表示と操作感だけ。事前に項目を示して許可後に実施。
- M3未接続中は設備/資源循環の単独検証まで。M4全体完了と生存成功は報告しない。

## Issueの分け方【確定】

| 仮ID | 成果 | 先行 |
|---|---|---|
| #106 | 共有契約・モジュール・登録・加工/栄養/種データと表示 | #55/M3境界合意 |
| #107 | 打製・繊維・木工・農具 | #106 |
| #108 | 火・炉・土器 | #107、M3熱/空気契約 |
| #109 | 有限水移送・ろ過・煮沸 | #108、#55確定commit |
| #110 | 4水設備 | #109、M3気象/涵養 |
| #111 | 5植物＋菌・堆肥・再採種と再生資材 | #106/#107/#108、#55/59/60、M3気象/適合 |
| #112 | 調理・保存・摂取接続 | #108/#109/#111、M3栄養/健康 |
| #113 | 研究/配布量・1/4人の生活経路と負荷の統合 | #107、#108、#109、#110、#111、#112、#59/60、M3実装 |

機能ごとの研究定義・翻訳・関連呼出し・テストは各Issueに含める。#113へ機能の未完成を押し付けない。Issueは[M4追跡 #105](https://github.com/rs0325/ashen-frontier/issues/105)の一覧を参照する。

## 承認済み事項（2026-11-24、Q1〜Q8はすべて推奨内容で確定）

| 番号 | 決定 |
|---|---|
| Q1 | 初期16面は1人の起点。4人は食料64面と補助区へ拡張する。作期・収量・灌水は集約栽培のゲーム簡略化として採用する |
| Q2 | 大麦・大豆・葉菜・亜麻・萌芽ヤナギ＋ヒラタケの6系統。全微量栄養素・N/P/K収支・亜麻精練は第1期の簡略モデルとする |
| Q3 | Tier 0の汚染水蒸留は非揮発性汚染の代表として扱う。有毒水・汚染海水は拒否し、濃縮残水は飲用・再投入不可 |
| Q4 | 母株/菌種を残す生きた繁殖保全区で再取得を保証する。再生は2ゲーム日以上、水/栄養予算を持つ。配置・母株保護の具体範囲はM2で別途ユーザー承認が必要であり、未決定 |
| Q5 | 4水設備を実装。遺物ポンプと太陽熱設備は任意の近道。自製代替を確保し、燃料蒸留は熱収支を保つ非常用経路 |
| Q6 | 土壌改良は清浄堆肥・土の交換まで。除染・中和・汚染作物・遺伝交配は後続提。清浄段階だけで食品適合を保証しない |
| Q7 | M3を含む24日生存試験の成功までM4全体を完了にしない。環境/摂取の入力契約は設計13を採用し、配布物に晴天/無害フォールバックを入れない |
| Q8 | 個別初期配布は仮5日分（水15 kg・食品50 MJ/人）と灌水等。M3/M4試験で最終数量を調整し、UUID配布と倍率対象外を維持 |

#55は未実装である。世界の現況・採水APIは先取りしない。2026-11-24追加確定により、#106は設備槽と加工、および必要な容器↔槽の移送だけを先行実装する。保全区の具体配置はこの承認に含まれない。

## 登録候補の詳細【確定】

### 既存品・自然資源・遺物

| ID案（既存は既存と記載） | 日英名 | 分類 | 現実の対応物 | 入手・製作 | 担当モジュール |
|---|---|---|---|---|---|
| pebble / flint（既存） | 礫 / Pebble、火打石 / Flint | 石材 | 打撃石・割れる石核 | loose_pebble / loose_flint、適性工具で露頭採取 | core→原 |
| dry_branch / plant_fiber（既存） | 枯れ枝 / Dry Branch、植物繊維 / Plant Fiber | 原料 | 枝、靭皮・茎繊維 | fallen_branch / dead_grass / fiber_bundle。後に薪・繊維作物 | core→原・農 |
| preserved_seed / preserved_fungus（既存） | 保存種子 / Preserved Seed、保存菌類 / Preserved Fungus | 繁殖材料 | 種子・生存菌株 | #60個別配布、#59採取、再採種。作物識別成分を付ける | core→農 |
| fertile_soil / minecraft:dirt / poor_soil / degraded_soil（既存） | 肥沃土 / Fertile Soil、土 / Dirt、痩せ土 / Poor Soil、劣化土 / Degraded Soil | 土 | 土質、汚染とは独立 | #55と#79の土。肥沃化は後述 | core→農 |
| minecraft:clay_ball / sand / gravel / oak_log（既存バニラ） | 粘土玉 / Clay Ball、砂 / Sand、砂利 / Gravel、オークの原木 / Oak Log | 原料 | 粘土・粒状ろ材・木材 | #55/#59の参照検査で徒歩到達可能性を確認、木は適性石器が必要 | core既存環境→原 |
| stone_hammer / simple_wrench（既存） | 石の打撃具 / Stone Hammer、簡易レンチ / Simple Wrench | 初期工具 | ハンマー・スパナ | #60。打撃具は礫から再製作。レンチは遺物の任意近道、生活必須にしない | core→原 |
| salvaged_cloth / wood_scrap / glass_scrap（既存） | 回収した布 / Salvaged Cloth、木片 / Wood Scrap、ガラス片 / Glass Scrap | 遺物原料 | 使える布、端材、再利用可能なガラス | 市街地。布・木片は自製代替、ガラスは任意の太陽熱設備用 | core→原 |
| damaged_hand_pump（既存） | 損傷した手動ポンプ / Damaged Hand Pump | 遺物設備 | 旧手押しポンプ | #59配置。既存IDの修復状態として機能を付ける | core→原 |
| sealed_water_container / preserved_rations / canned_food / archive_document（既存） | 密封水容器 / Sealed Water Container、保存食 / Preserved Rations、缶詰 / Canned Food、記録資料 / Archive Document | 初期支援 | 水・長期保存食・資料 | #60。再配布を自給経路にしない。水容器の開封後も同じIDで空/充填量を保持 | core→原・農 |
| raw_water / turbid_water / contaminated_water / clean_water（既存） | 生水 / Raw Water、濁水 / Turbid Water、汚染水 / Contaminated Water、清浄水 / Clean Water | 水 | 設計03の水質群 | #55。残り4水質のIDも保持するがTier 0の新規製造対象外 | core→原 |

バニラ原料は既存の地質・木材表現と一致し重複IDを避けるため使う。共通タグ `c:` は使わない。バニラの調理・増殖・水瓶採水を流用して自Modの水質・汚染・研究を飛ばすことは認めない。

### 新しい道具・加工物

| ID案 | 日英名 | 分類 | 現実の対応物 | 入手・製作 | 担当モジュール |
|---|---|---|---|---|---|
| chopper | 礫器 / Chopper | 石器 | 一方向から剥離した石核工具 | 礫または火打石を打撃具で打製 | primitive |
| biface | 両面加工石器 / Biface | 石器 | 両面調整した切削具 | 礫器を追加打製。刃を付けた石斧とは区別 | primitive |
| stone_flake | 石の剥片 / Stone Flake | 刃・副産物 | 剥離片 | 打製時の確定副産物、繊維・木の切削 | primitive |
| cordage / fiber_cloth | 植物繊維の紐 / Plant Cordage、繊維布 / Fiber Cloth | 繊維加工 | 撚紐・粗い織布 | 乾燥繊維を撚る／枠で編む。ろ材の自製代替 | primitive |
| tool_handle | 木の柄 / Wooden Handle | 工具部材 | 加工した枝・柄 | 剥片で枝を削る | primitive |
| hafted_axe / stone_adze | 柄付き石斧 / Hafted Stone Axe、石の手斧 / Stone Adze | 木工工具 | 縦刃斧・横刃手斧 | 両面刃＋柄＋紐。手斧は木製ポンプの中ぐりにも使う抽象化 | primitive |
| digging_stick | 掘り棒 / Digging Stick | 農具 | 尖らせた木棒 | 枝を削る。採鉱用ピッケルにはしない | primitive |
| fire_drill / tinder | 弓錐式火起こし具 / Bow Drill、火口 / Tinder | 着火 | 軸・火きり板・弓・押さえ・乾燥繊維 | 柄・木片・紐・礫を組立／繊維をほぐす | primitive |
| green_pot / dried_pot / ceramic_pot | 生の土器 / Green Pot、乾燥土器 / Dried Pot、土鍋 / Ceramic Pot | 成形・容器 | 粘土の成形品・乾燥品・焼成品 | 成形→乾燥→焼成。完成品は容量4 kg、非スタック | primitive |
| ceramic_lid / ceramic_tube | 陶製の蓋 / Ceramic Lid、陶管 / Ceramic Tube | 陶製部材 | 蓋・凝縮管・弁部材 | 土器と同じ乾燥・焼成工程。一般配管網は追加しない | primitive |
| grain_meal / cooked_staple / cooked_soy / vegetable_soup / dried_vegetables / dried_mushrooms | 穀粉 / Grain Meal、穀物粥 / Grain Porridge、煮大豆 / Cooked Soybeans、野菜汁 / Vegetable Soup、乾燥野菜 / Dried Vegetables、乾燥きのこ / Dried Mushrooms | 食品 | 砕穀・加熱料理・乾物 | 手挽き、清浄水で煮る、下処理後に乾燥 | agriculture（加熱設備は原） |
| compost | 堆肥 / Compost | 土壌改良材 | 熟成した植物堆肥 | 清浄な植物残渣を熟成。汚染除去材にはしない | agriculture |
| crop_residue / fuelwood | 作物残渣 / Crop Residue、薪 / Fuelwood | 再生原料 | 茎葉、乾燥薪 | 作物収穫／萌芽更新した木の収穫と乾燥 | agriculture、燃焼は原 |

柄付き石器に鉱石全種の採掘能力を与えない。M5/M6へ渡す最低限の木工・軟質石材加工に限定する。biface自体は手持ち切削具で、全工程を同じ石器アイテムにまとめない。

### 作物と設備

| ID案 | 日英名 | 分類 | 現実の対応物 | 入手・製作 | 担当モジュール |
|---|---|---|---|---|---|
| barley_crop / barley_grain | 大麦 / Barley、麦粒 / Barley Grain | 主食 | Hordeum vulgare | 作物識別barley付きpreserved_seedを播く、収穫・種取り | 作物/収穫物core→農 |
| soybean_crop / soybean | 大豆 / Soybean | たんぱく・脂質 | Glycine max | 同soybean、加熱して食べる | core→農 |
| leafy_crop / leafy_greens | 葉菜 / Leafy Greens | 野菜 | 小型のアブラナ科葉菜を代表 | 同leafy_greens、収穫・採種モードを分ける | core→農 |
| flax_crop | 亜麻 / Flax | 繊維 | Linum usitatissimum | 同flax、収穫で既存plant_fiberと種 | core→農 |
| coppice_willow | 萌芽更新するヤナギ / Coppice Willow | 薪生産 | Salix属の萌芽更新 | 同willowを繁殖材料として使用するゲーム抽象化。伐り株を残して再生 | core→農 |
| oyster_mushroom / mushroom_bed | ヒラタケ / Oyster Mushroom、菌床 / Mushroom Bed | 菌類・設備 | Pleurotus ostreatus | preserved_fungus＋処理した植物残渣＋清浄水、菌種を分けて継代 | 自然収穫物core→農、菌床はagriculture |
| knapping_surface | 打製作業面 / Knapping Surface | 手作業設備 | 安定した台石 | 礫を地面へ置く。道具不要で設置・回収 | primitive |
| hearth | 石囲い炉 / Stone Hearth | 加熱設備 | 換気された炉床・野焼き | 礫＋粘土、火起こし具で着火、燃料を投入 | primitive |
| drying_rack | 乾燥棚 / Drying Rack | 乾燥設備 | 通風棚・炉脇乾燥 | 枝＋紐。土器乾燥・食品乾燥をレシピ型で区別 | primitive |
| water_filter | 沈殿ろ過槽 / Settling Filter | 浄水設備 | 静置槽＋布・砂・砂利 | 土器＋ろ材。消耗した布を交換 | primitive |
| hand_pump | 木製手動ポンプ / Wooden Hand Pump | 採水設備 | 木製ピストン・逆止弁 | 木材＋紐＋陶製部材。金属・ゴム不要 | primitive |
| solar_still | 太陽熱蒸留器 / Solar Still | 受動設備 | 蓋付き浅盆・凝縮面 | 土器＋陶管＋回収ガラス。任意の遺物利用経路 | primitive |
| fuel_still | 燃料式蒸留器 / Fuel-fired Still | 加熱設備 | 常圧蒸発器＋空冷凝縮管 | 土器＋蓋＋陶管＋炉。ガラス・金属不要 | primitive |
| rain_collector | 雨水回収器 / Rain Collector | 集水設備 | 布製集水面・樋・貯水器 | 繊維布＋枝＋紐＋土器 | primitive |
| compost_bin | 堆肥槽 / Compost Bin | 熟成設備 | 好気性植物堆肥 | 木材＋紐 | agriculture |

種子の種別は既存preserved_seedに `crop_species` を追加して区別し、IDごとの種子を重複登録しない。保存菌類も同じ識別方式を使う。M4で交配・遺伝パラメータは導入しない。自然配置する作物・収穫物・繁殖材料の追加はcoreカタログの承認対象であり、M2側に無断で配置させない。


## 出典

- 石器: [Smithsonian Early Stone Age Tools](https://humanorigins.si.edu/evidence/behavior/stone-tools/early-stone-age-tools)、[Lepre et al. 2011](https://www.nature.com/articles/nature10372)。
- 撚紐: [Hardy et al. 2020](https://pubmed.ncbi.nlm.nih.gov/32273518/)。土器: [Wu et al. 2012](https://pubmed.ncbi.nlm.nih.gov/22745428/)、[野焼き実験研究の公開要約](https://www.sciencedirect.com/science/article/pii/S016913171000308X)。
- 火起こし: [University of Colorado Museum](https://www.colorado.edu/cumuseum/programs/schools-and-groups/colorado-archaeology-classroom/fire-starting)。
- 煮沸/蒸留の限界: [CDC緊急時の処理](https://www.cdc.gov/water-emergency/about/index.html)、[CDC家庭水処理](https://www.cdc.gov/drinking-water/about/about-home-water-treatment-systems.html)。
- 熱物性: [IAPWS SR6](https://iapws.org/technical-guidance/release/LiquidWater)、[IF97](https://iapws.org/technical-guidance/release/IF97-Rev)。
- 太陽熱: [Passive Solar Stills実験研究](https://www.sciepublish.com/article/pii/741)。2 kg上限はゲーム値。
- 作物水: [FAO Crop Water Needs](https://www.fao.org/4/s2022e/s2022e07.htm)。栽培期間/水量の圧縮率を承認対象とする。
- 吸上げ: [FAO Wells–Pumps](https://www.fao.org/4/s1250e/s1250e1e.htm)。薪: [FAO Wood Energy](https://www.fao.org/sustainable-forest-management/toolbox/modules/wood-energy/in-more-depth/en/)。
- 菌: [Cornell Small Farms](https://smallfarms.cornell.edu/resources/methods-of-commercial-mushroom-cultivation-in-the-northeastern-united-states/4-four-methods-of-commercial-cultivation/)。保存: [Colorado State University](https://extension.colostate.edu/resource/drying-vegetables/)。
- 栄養/豆: [FAO大豆](https://www.fao.org/4/t0532e/t0532e02.htm)、[加水加熱の一次研究](https://www.jstage.jst.go.jp/article/nskkk1995/48/6/48_6_416/_article)。
- 実装選定: NeoForge 1.21.1公式[Ingredients](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/ingredients/)、[Blocks](https://docs.neoforged.net/docs/1.21.1/blocks/)。

出典閲覧日2026-11-24。待ち時間・集約収量・腐敗倍率は現実値からの直接導出ではなくゲーム。全文未確認資料は公開抄録/要約の範囲のみ利用。出典の調査限界は本節に記載したとおり。
