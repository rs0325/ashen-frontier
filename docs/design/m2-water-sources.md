# 有限水源の保存・移送基盤

状態: **確定**。承認日2026-11-25。#55分割Q1〜Q6はすべて推奨案のとおり承認。実装#122、実配置#123、追跡#55。

## 1. 分割と完了の扱い

**#55は追跡、#122が先行基盤、#123が後行配置。** #122がmergeされても#55は閉じない。

| 分割 | 目的・範囲 | 完成条件 | 依存と検証 |
|---|---|---|---|
| #122（仮称） | coreの残量台帳、版付きキー、全8水質、保存、原子的な採水/容器移送、M3涵養入力の契約と重複防止、迂回封鎖。world/primitiveには最小接続だけ | 全8水質で減算と受取が一致。0は再ロード/再登録で復活しない。無限は真正の海だけ。通常保存後も量・品質・涵養窓が一致。未接続は拒否 | #53/#97/#106/#108の既存基盤。D2/D4/D5の配置完了は不要。単体・GameTestと実チャンク/ディスク再読込、重点反証 |
| #123（仮称） | 承認された水域・帯水層の初期量/水質と位置、土壌分布、worldの読出し/表示・生成接続。自然氷との量の接続 | 同じ水を湖/合流/チャンクで二重に数えない。固定サンプラーから復活しない。枯渇状態と表示・採取が一致。土質/汚染・栽培床と経路が成立 | #122、D2/#99、D4/#101、D5/#102、必要な#59/#60の承認位置。実列分布・逆順/並列生成・保存再起動・到達性・性能 |
| #55追跡 | A/BおよびM2との接続を追跡 | A/Bの完成条件を確認し、#96/#62へ結果を接続して初めて終了 | M3の涵養計算やM4の全生活循環まで自分の実装範囲へ取り込まない |

D2は現在のInventory(id,key,qualityId,finite)・列所有の契約を作り続ける。Aはそれをcore向け値へ写す受け口を持ち、Bが実列から初期容量を作る。Aで湖や井戸の座標、地質、容量の本番値、土壌分布を決めない。Aのfixtureを配布物の供給源にしない。

#109のコード依存は#122＋#108の確定merge commit。AのAPIを使って浄水機能をfixtureで検証できる。通常世界での自然源到達性はB、M3の気圧/沸点と生活収支はM3/#113まで残す。#105の24日生活循環、#96の新版公開条件を早期完了にしない。

## 2. データ契約

### キーと定義

coreのWaterSourceKeyは、map_version・format_version・manifest_hash・kind・local_keyの値。kindはsurface/aquifer。dimensionはAPIへ渡すServerLevelとそのSavedDataで分離し、他dimensionの残量を共用しない。

- surface例: earth_002 / format 1 / 当該manifest SHA-256 / surface / nw05。整数2だけを保存キーにせず、湖L10・各reachを別残量にしない。
- aquifer例: 同じ版 / aquifer / 承認後の安定した帯水層キー。実名・境界・本番値はBの配置案で決める。天候セルIDをそのまま新しい貯水量の単位にしない。
- earth_001のid=2/3はearth_002と同一視しない。旧版用アダプターもBで別定義とし、新版へ読み替えない。
- manifest不一致・未知版・未知キーは採水拒否/初期化停止。別版の台帳を空扱いして満水で作り直さない。

WaterSourceDefinitionはkey・quality・Optional<Stock(capacityMg, initialMg)>。stockなしは無限の海だけ。有限なら0≦initial≦capacity≦Long.MAX_VALUE、容量は正。無限なら容量/残量を持たず、oceanの承認定義と位置検証を必要とする。品質IDはultrapure_water/pure_water/clean_water/raw_water/turbid_water/contaminated_water/toxic_water/polluted_seawaterの既存値のみ。番号の大小で混合・昇格しない。枯渇しても水源の定義品質は残す（空の槽のqualityがemptyになることとは別）。

Bが不変の定義カタログを渡す。D2のgeometryからの総量集計はB/制作側で一度だけ行い、チャンク生成順で容量を足し込まない。新しい水量資産が必要ならBのmanifestへ加え、AからD2のhydrology必須スキーマへ先行追加しない。coreからworldのMapVersion/FixedMapDataへ逆依存しない。

### 量と換算（Q2）

表示・業務上はkg、台帳の演算・保存は整数mg。大きな水域の長期残量をdoubleへ変換して計算しない。

- 容器/槽は既存WaterMassと10億kg上限・kg double形式を維持する。
- 水域台帳はlong mgを直接保存する。上限は約9.22兆kg/水源。WaterMass.toKgへ上限超の水域全量を通さず、表示が必要な場合はBigDecimalの十進kg表現を使う。
- 移送1回は容器/槽側の上限内。requestedKgを既存fromKgで1回だけ正規化し、以降は整数比較・加減算。受入kgを既存fillから受ける場合も既存変換でmgへ戻し、同じ値を水源から引く。
- 定義データの換算もWaterMassの同じmg規則へ集約する。大容量の定義は整数mgまたは正確な十進値から作り、既存の小口MAX_KGを黙って広げない。checked演算で溢れは制作エラー、切捨て/飽和/自動分割はしない。
- 確定した初期換算は1 block³＝1 m³、全8水質の密度を1000 kg/m³へ固定。仮想標高の1B≒25mは水の体積換算へ使わない。これはゲーム上の縮尺と密度近似であり、塩分/温度による実密度差を再現したものではない。
- 表面の実水列を一意に所有させた体積だけを集計し、落水/flowingの表示は別の供給量にしない。気泡・地下の岩石体積を全量水としない。
- FluidStackのmBを採水の必須経路にしない。今回の公開移送はkg/mg＋品質。mB＝kg、バニラバケツ1個＝自然水源1m³等の未承認換算を入れない。

### 保存・初期化

NeoForge標準SavedDataをcoreで利用する。保存名案ashen_frontier_water_sources、water_schema_version=1。MapSavedDataの版照合をworld側で済ませ、同じ版の参照とdefinition_hashを水台帳ヘッダーへ置く。worldへもう一つの残量保存を作らない。

有限レコードはkind/local_key/quality/capacity_mg/remaining_mg/recharge_through_tickと直近の涵養結果を持つ。longで保持し、0のレコードも削除しない。無限の表現に負数やDouble.POSITIVE_INFINITYを使わない。未知形式・不一致・負量・容量超過・定義済みレコード欠損は停止/明示エラーとし、満水へフォールバックしない。

初回は互換な新規ワールドの明示的カタログ初期化で全定義を一括登録し、initializedを保存する。未ロードチャンク・任意のread・採水失敗・reloadでは初期化しない。既存記録へ同じ定義を再接続しても残量は上書きしない。Aでは新規の試験worldだけにfixtureカタログを初期化し、通常worldへの生産用カタログ導入はB。未リリース試作の無断移行/再生成はしない。

例（全体地形の容量でなく、Aの大容量fixtureの1件のみ）:
```text
water_schema_version: 1
map: { map_version: earth_002, format_version: 1, manifest_hash: <当該SHA-256> }
definition_hash: <定義カタログSHA-256>
initialized: true
sources:
  - kind: surface
    local_key: nw05
    quality: ashen_frontier:raw_water
    capacity_mg: 2000000000000000L
    remaining_mg: 1999999996000000L
    recharge_through_tick: 12000L
```
2十億kgのfixtureから4kgを採った例。実ファイルは定義カタログの全レコードを検証し、例の数値をD2へ配置しない。

## 3. 公開APIとモジュール

coreに水源台帳と移送を置く。worldが版・初期定義・位置解決、primitiveが容器/設備操作と機械条件、climateが降水/涵養の計算を担う。新モジュール、一般流体網、予約/エスクロー、任意コールバック付きトランザクション基盤、外部ケイパビリティは作らない。

core→worldの逆依存を避ける最小のWaterSourceAccessProviderを置く。worldが実装してレベルに接続する。Aの固定提供者は試験だけ。位置解決は現在のロード済みチャンクと承認定義から、キー・品質・source/flowing・自然海の適格性・利用可否を返す。クライアントのinventory IDや「海です」という申告を信用せず、移送直前にサーバーが引き直す。

| API名案 | 前提・引数 | 戻り値と副作用・失敗 |
|---|---|---|
| initializeNewWorld(level, mapRef, definitions, provider) | worldの新規作成処理だけ。版照合済み・サーバースレッド | 初期量と涵養epochを一度作る。既存台帳は拒否。ファイルの有無で呼出しを選ばない |
| bindDefinitions(level, mapRef, definitions, provider) | 保存済み台帳への再接続 | 量・涵養epochを作らず、台帳ファイル/レコード欠損・版/hash/定義不一致は例外で停止 |
| inspect(level, intakePos, kind) | ロード済み位置。core側でproviderを呼ぶ | 読取りsnapshot/状態。キー未接続/対象なしを区別。読み取りで初期化・補給・チャンク生成しない。精密自然残量はサーバー/試験用 |
| sourceToContainer(player, intakePos, hand, requestedKg) | 手の容器1個・登録補給容量・権限/到達距離・現在sourceの再検証 | TransferResult(status,movedMg,quality)。source減算と同ID容器の量/品質更新を一括。枯渇・満杯・異品質・未接続は0で両方不変 |
| sourceToTank(player, intakePos, kind, hand, tankOwner, requestedKg) | ロード済み同一levelの設備BEと所属槽。機械の入口/揚程/仕事量の条件はprimitiveが先に検証 | 同上。実受入量だけ減算し、台帳と設備をdirty化。裸のキーだけで遠隔源を減算するAPIは公開しない |
| containerToTank(player, hand, tankOwner, requestedKg) | 承認容器1個、所属槽、操作権限 | 既存WaterTank.fromContainerを再利用。世界水源には触れず、実移送kgをmgへ正規化して返す。槽→容器は既存toContainerを再利用 |
| applyRecharge(level, key, quality, fromTick, toTick, offeredMg) | M3のサーバー計算からのみ。有限源・同品質・承認された入力予算・連続した区間 | 適用/既適用/不整合と受入・越流水量。容量を超えた分は明示的overflow。残量と区間確定を同じSavedDataで変更 |

TransferResultは成功/一部成功の実移送量を持つ。代表statusはNO_BINDING、NO_SOURCE、DEPLETED、FULL、QUALITY_MISMATCH、INVALID_CONTAINER、UNLOADED、ACCESS_DENIED。業務上の拒否は変更0。非正・非有限の要求、mgへ丸めると0、ローカル上限超過、別thread等の呼出し違反は更新前の例外。予期しないプログラム障害を「失敗したので何度でも再試行可能」として握り潰さない。

設備の所属確認/dirty通知はWaterTankにないため、coreの最小内部WaterTankOwner（水槽の取得だけ）を設備BEが実装し、サービスが現在のBE同一性とlevelを確認してsetChangedする。可変の任意宛先・外部Mod接続へ一般化しない。既存Player依存のWaterTankをこのIssueで自動化向けに作り替えない。M3の台帳入力はServerLevelで行えてプレイヤー不要。

補給可能容器の容量は既存方式のItemデータマップで最小定義を持つ案。primitiveが土鍋4kgを登録し、coreはprimitiveのクラスへ依存しない。密封水容器の初期CONTENT_MASS=1.0kgは最大容量ではない。Q3の決定により密封水容器は既存内容の払出しのみとする。油/試薬容器、氷Item、瓶、Potionを水容器にしない。単一容器へ分ける操作は既存所持品操作を使い、スタック数2以上は拒否して案内する。

## 4. 原子性の手順と保存保証（Q4）

1. メインサーバースレッドで、入力位置・現在のFluidState・版・品質・残量・手のItemStackまたは槽所属BE・権限・空き量を再検証する。条件イベントが必要ならこの段階まで。
2. request/available/freeの整数mgの最小値を計画する。0なら変更しない。overflowや保存範囲を含む既知の失敗条件を先に検査する。M3の同時補給も同じthreadで直列化する。
3. 容器はコピーで成分を作り、所有スロットを再確認して反映する。槽は既存fillを使い、実受入量をmgへ戻して確保済みの水源残高から同じ量を引く。fillの前検査と上限により、書込み後に既知の業務例外を発生させない。2つの更新の間でイベント・ネットワーク・外部コールバックを呼ばない。
4. 台帳と相手をdirty化し、一括確定後に表示/同期する。設備が受け取った後の加工の取消・満杯・再開で、水源へ返さない。

Q4で確定した保証は、ゲーム内操作の原子性と正常な保存・停止・再起動まで。SavedData、チャンクBE、プレイヤー所持品は別保存ファイルであり、setDirtyは停電に対する同時コミットではない。強制終了/電源断/一部ファイルだけのバックアップ復元まで総量を保証するとは書かない。そこまで必要なら、両所有者へ安定した取引識別と回復手順を加える別設計が必要で、Aへ隠れて汎用WALを持ち込まない。

## 5. 涵養契約と枯渇後の再開

Aは受け口・量の適用・重複防止だけを作る。降水/浸透/流出/蒸発/遅延・水位・河川との交換の計算と本番の呼出しはM3で実装する。M3なしで時間を進めても残量は増えない。

- EnvironmentSampleの降水kg/m²は帯水層への涵養kgそのものではない。M3が承認面積・流出/蒸発・浸透・遅延を計算し、水源キーごとに配分した整数mgを渡す。同じ降水を河川と帯水層へ全量ずつ二重加算しない。
- fromTick/toTickは単調なgameTimeの区間。水源に保存したrecharge_through_tickから連続させる。雨量計算のGAME換算・日長区分はM3が持つ。
- 直近の区間・offered/accepted/overflowをレシートとして保存する。同じ区間・同じ入力の再送は元の結果＋committedNow=false、直近より古い区間は不整合として拒否し、再加算しない。同じ区間の別入力やgapは拒否。0mgの区間も明示確定できる。
- 同品質のみ受入れ。雨水をきれいな水として注いだだけで有毒な帯水層を浄化したことにしない。浸透後の品質/混合の承認モデルがなければ受入れ拒否し、M3で別途設計する。
- capacityまでの実受入分だけ加える。越流分は返値へ出し、M3が流出へ計上する。満杯で受けなかった区間を後で使い回して無から再補給しない。

枯渇0を保存し、採水はDEPLETED。再開は、承認された降水/涵養予算が実際に適用され、入口と量が利用可能になったときだけ。待っただけ・新チャンク探索・再設置・再起動では回復しない。プレイヤーには枯渇/未接続/満杯/異品質と再開条件を案内し、未測定の帯水層の精密な総量を端末へ常時表示する機能は追加しない。

生活側は設計13の6kg/人日・6日無雨で36kg/人以上を基礎貯蔵にし、栽培等を別途上乗せする。4人なら基礎144kg。これは水源の初期容量や、密封水容器1個の容量を決める値ではない。長期に採水が補給を超えれば枯れる。任意長の永久干ばつを保証せず、M3接続後の#113で承認気候の長期収支を検証する。

設計03/12の旧「M3が残量」の表現は、確定済みの#55/設計13に従い「残量の保存・適用は#55、変化量計算はM3」へ本実装で注記する。#55の「海だけから補給」は無限補給の例外を意味し、予算のあるM3涵養を禁ずる意味に拡張しない。

## 6. 現実の根拠と簡略化

比産出率Syは、飽和した土や岩から重力で取り出せる水の割合で、空隙率全体と区別する。実際の放出は瞬時とは限らない。したがって岩の体積をそのまま水容量にしない。[USGS・Specific Yield](https://water.usgs.gov/ogw/gwrp/methods/wtf/estimating_sy.html)

比産出率の定義からの概算（本設計での推論）として、不圧帯水層の制作値を考える際の近似はM≈ρ×A×Sy×Δh（kg）。Aはm²、Δhは利用可能な水位低下m、Syは無次元。これは容量の根拠を整理する概算で、Aの流動ソルバーではない。被圧帯水層の弾性貯留へ同じSy式を機械的に使わず、B/M3で地質・取水可能域を別途決める。Syや面積・水位の本番数値は今回選ばない。

降水・涵養と揚水量は井戸の水位に影響し、渇水で利用しにくくなる。貯水があることと、任意の速度で揚水できることは違う。[USGS・Drought and Groundwater Levels](https://www.usgs.gov/water-science-school/science/drought-and-groundwater-levels)

過剰な揚水は貯留の減少だけでなく、河川・湖や水質にも影響する。本設計は区域ごとの可採水量へ集約し、井戸の局所ドローダウン・河川との捕捉流・地盤沈下をAで数値計算しない。[USGS・Groundwater Decline](https://www.usgs.gov/water-science-school/science/groundwater-decline-and-depletion)

Aのmin演算は保存量の限界だけを守り、現実の井戸の生産能力を証明しない。#110の手動作業/設備流量と、Bの取水可能域、将来M3の水位条件が別の限界になる。Aで無根拠な「全井戸共通の毎日補給量/揚水率」を配布しない。

水の密度は約1g/mLで、温度・溶存成分によって変わる。本設計の1000kg/m³を全水質に使うことと、1block³=1m³の初期換算はゲーム近似である。[USGS・Water Density](https://www.usgs.gov/water-science-school/science/water-density)

水面の全sourceブロックを独立した貯水槽とせず、D2で確定済みのinventoryを一つの貯水量として扱う。局所採水による湖全体/流域内の即時共有もゲーム上の集約。水位の変化はB/M3の表示接続で扱う（Q5）。

技術選定は標準SavedData＋既存データコンポーネント＋既存WaterTankを採る。チャンクAttachmentへ流域残量を分散させず、設備BEは自身の槽だけを保存する。既存機能で足りるため外部ライブラリを増やさない。[NeoForge 1.21.1 SavedData](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)、[Data Attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/)

閲覧日2026-11-25。USGS Circular1186/1376等の一部本文は403となったため、本設計の根拠は上記で実読できた公式ページの範囲。定量的な地下水モデルや安全揚水量を確認済みとはしない。

## 7. バニラ迂回の封鎖と自然ブロック

既存のNatureFluidType.canConvertToSource(false)、NatureLiquidBlock.pickupBlock→EMPTY、流体置換拒否を再利用する。recipe_disabledでバケツ製法が止まっているだけでは採水権限の代わりにならない。

| 経路 | Aで守る規則・検証 | B/後続との境界 |
|---|---|---|
| バニラ空バケツ/ディスペンサー採取 | 全8水質で通常BucketPickup経路を拒否。元の量/品質を変えず、バニラ水へ変換しない | 専用容器の採水だけが台帳移送を呼ぶ |
| ガラス瓶 | プレイヤーとディスペンサーの瓶経路を試験し、自然水からvanilla水Potionを作らない。標準イベント/既存ブロック経路で足りない箇所だけ狭く封鎖 | Potion/水瓶をM4の承認容器にしない。#109も型/品質/量を検査 |
| waterlogging・持ち運び・再設置 | booleanのWATERLOGGEDを8品質の所有物としない。Nature水の水没化を許可せず、既存の水没ブロックからの水はsource解決/補給へ流入させない | 固体ブロック移動だけで水源の登録/初期化をしない |
| バニラ水入りバケツ・大釜・鍾乳石等 | vanilla水/無品質水を台帳・土鍋・槽へ取り込まない。独自流体の上書き/瓶への品質変換を試験。生存経路で到達する変換穴があればAで閉じる | 全流体を扱う汎用大釜や配管を新設しない |
| source増殖・隣接更新・flowing/落水 | 独自source変換禁止を継続し、flowingを採水源にしない。samplerの再照会や流れ表示から残量を増やさない | D2の水面/落水は初期形状。Aに補給tickを入れない |
| 海水の持出し | 真正の海の指定source位置だけ無限。容器/槽/持出し先は有限量・品質8のまま | 内陸の海水や海へ接した淡水をoceanへ昇格しない |
| 自然氷/雪・スポンジ・ブロック置換 | Aで氷→vanilla水等の新しい変換を入れない。氷Itemは水容器として受けない。表示の凍結/復元・吸収等が台帳を再初期化しないことを試験 | 自然氷の採取・液相との量配分と実地表示はB、融解/水位の時間変化はM3。水を放出する新製法は別設計まで不可 |

標準イベントがすべてのディスペンサー/内部流体経路を捕捉するとは仮定しない。実装時に対象版の既存BucketPickup/瓶/水没ブロックの経路を確認し、必要な最小介入を選ぶ。制限は自Modの水と承認済みプレイ範囲へ限定し、外部ケイパビリティやc:タグは公開しない。

Aでは水を液体ブロックとして自由に注ぎ出す機能を追加しない。容器→槽を使う。表示ブロックの数と残量を同一視せず、枯渇0なら見た目が水でも採水は必ず拒否・案内する。Bはロード済み位置の表示と状態を接続し、M3は将来の水位変化を扱う。Aだけで自然水の見た目・流域の物理まで完成したとはしない（Q5）。

## 8. 性能と検証計画

毎tick・全水域・全ブロックを走査しない。操作時のキー照会と辞書残高更新は概ねO(1)。worldの位置索引はD2の既存近傍索引を使う。未ロードchunkの強制生成は行わない。移送ごとの処理は少数の成分更新とdirty化だけで、全台帳を即時ディスク書込みしない。SavedDataの保存は源数Nに対しO(N)、0のレコードを残す。

仮見積りは1万源なら常駐10MiB級（1源約1KiBとしての概算）、非圧縮NBTは数MiB級。実測値ではなく、実装時にN・JVM・heap・保存時間とともに測る。表面の現行3流域を細かいブロック数へ展開しない。Bの帯水層件数が確定したら再評価する。同期は操作先の状態に限定し、全台帳を全プレイヤーへ毎tick配らない。

Aの検証:
- 単体: mgの加減算/比較/上限/overflow、0→補給、異品質、定義不整合、重複/逆順/gap涵養、capacity越流、複数セル予算の二重受領防止。
- 反例: 144kgを0.025kgで5760回、0.1kgで1440回使い切る。台帳はちょうど0。大容量2十億kgから4kgを引いても精度を失わず、ローカルWaterMassの上限を台帳へ誤適用しない。
- GameTest: 全8水質、0/端数/満杯、異品質、容器スタック拒否、手持ち/設備の同時2人採水、一部受入、キャンセル/破壊/入替え/アンロードで複製・消失しない。
- 保存: 元水源・プレイヤー容器・BE槽の総mgを正常保存→サーバー再起動で比較。実チャンクsave/unload/disk load、0源の再接続、初期定義再読込み、未生成チャンク生成でも残量が増えない。
- 版: 同じ数値ID/ローカルキーでも別map/manifest/dimensionは混ざらない。キー欠損/不正schemaは満水復元しない。
- 涵養: M3のfixture入力だけで再開、同区間の再送と保存再起動で二重加算しない。無雨6日・弱日照2日はM3/#113の生活統合検証へ残す。
- 迂回: 上表の全経路、8品質、真正海/内陸海水/河口接続、source/flowing、凍結表示、隣接更新を実エンジン経路で試す。ヘルパーだけの直接呼出しで網羅した扱いにしない。
- build gameTest --no-daemonと重点反証。UIの日英案内の見た目だけ確認項目に残し、開発クライアントは別途許可まで起動しない。
- world試験の保存停止等が再発すればダンプ保全し、そのrunを成功扱いせず、Aへ地形修正を混在させない。

Bの検証は新旧版・全流域/湖/合流/河口・帯水層・凍結域の一意性、土質＋6汚染、順序/seed独立、採水前後の実列・保存、必須経路と貯蔵予算、p95/heapを測る。水量/地形側の完了とM3の長期涵養モデルの完了を混同しない。

## 承認結果（確定・2026-11-25）

- Q1: #55を追跡として残し、先行基盤Aと後行配置Bを新設する。
- Q2: 台帳はlongのmg。初期体積換算は1 block³=1 m³、全水質1000 kg/m³のゲーム近似。容器・槽のkg double保存形式とWaterMassの上限は維持する。
- Q3: 補給容器は土鍋4kgのみ。密封水容器は既存内容の払出しのみで、初期内容量1kgを容量と解釈しない。
- Q4: 原子性は同一サーバー操作と正常保存・再起動まで。停電・強制終了・部分バックアップ復元の複数ファイル同時永続化は保証しない。
- Q5: 区域台帳の残量が正。水面・水位・自然氷の本番接続はB/M3。0mgは常に採水拒否。
- Q6: #109のコード依存はAと#108の確定merge。本番配置はB、気候と生活循環はM3/#113待ち。M2/M4全体を早期完了にしない。

## 実装APIとM3への引継ぎ（#122）

実装の入口はcoreの `water/WaterSources.java`、`WaterTransfers.java`、`WaterSourceAccessProvider.java`、`WaterTankOwner.java`。パッケージは `io.github.rs0325.ashenfrontier.core.water`。加工・槽の実装は既存の `recipe/ProcessingStation.java`・`WaterTank.java`・`WaterMass.java`を使う。

- `WaterSourceKey(MapRef map, Kind kind, String localKey)` のMapRefはmapVersion・formatVersion・manifestHash。worldは既存MapVersionを値で写す。KindはSURFACE/AQUIFER。earth_002のsurfaceキーはocean/nw05/nw06/nw16を引き継ぎ、L10を別台帳にしない。
- `WaterSourceDefinition(key, quality, Optional<Stock>)`。StockはcapacityMg・initialMgのlong。stockなしはSURFACE/ocean/汚染海水だけ。位置での真正海判定も別に必要。
- `WaterSources.bindDefinitions(level, map, definitions, provider)` は保存済み台帳への再接続のみ。ファイル全体が欠けても新規扱いせず停止する。新規worldの作成処理だけが同じ引数のinitializeNewWorldを呼び、初期量・gameTimeの涵養epochを一度作る。world側は新規作成/既存接続の文脈で選び、ファイルの存在有無を初期化の根拠にしない。definitionsは全件。providerは読み取り専用で、ロード済み入口からキーとdesignatedOceanSourceを返す。surfaceの現在の独自Fluid/source/品質はサービスでも検査。aquiferの地質・取水域はproviderが検査する。
- `WaterTransfers.inspect(level, intake, kind)` はSnapshot(status, Optional<definition>, OptionalLong remainingMg)。無限海のremainingはempty。キー照会だけでチャンクを生成しない。
- `sourceToContainer(player, intake, hand, requestedKg)` は登録補給容器1個へ移す。`sourceToTank(player, intake, kind, hand, BlockEntity owner, requestedKg)` はWaterTankOwnerの槽へ移す。呼出し元は設備の仕事量/入口条件を先に検査する。携帯採水は入口への到達範囲（標準blockInteractionRange+1）を検査する。設備への移送は操作する設備への到達・同一level・ロード済みBEの現在の同一性と入口の保護権限を検査する。設備と入口の接続・揚程/仕事量は呼出し元設備の事前条件であり、プレイヤーが地下入口へ直接届くことを要求しない。
- `containerToTank(player, hand, owner, requestedKg)` と `tankToContainer(...)` は既存WaterTankを使う。密封容器は払出しのみ。最大容量は `ashen_frontier:water_container` Itemデータマップ（kg double、1mg格子）でprimitiveが土鍋4kgを登録する。
- 移送結果はResult(status, movedMg, Optional<quality>)。SUCCESS/PARTIALだけが正の実移送量。通常拒否は0で両所有者不変。API要求の非正/非有限/上限外・サーバースレッド違反・不正定義/保存は例外。呼出し元が結果確定後にUI/同期する。
- 補給操作は土鍋を持って独自水のsourceへ空中右クリック（通常1kg、Shift4kg）。供給者未接続ではNO_BINDING。供給者も初期量も通常worldへ仮実装しない。台石の容器↔槽はこの所属検査付き入口を使う。

### 涵養入力の単位・符号・所有者・頻度

`WaterSources.rechargeThroughTick(level, key)` が次のfromTickとなる保存済みgameTimeを返す。プレイヤーは不要。無限海・未登録/未接続は拒否する。

`WaterSources.applyRecharge(level, key, quality, fromTick, toTick, offeredMg)` は**M3のサーバー計算からだけ**呼ぶ。offeredMgは**非負long、1単位=1mg=1e-6kg**。減水の負値を渡さない。fromTickは保存cursorと一致、toTickはfromTickより後で現在gameTime以下。0mgも確定可能。

M3が降水・蒸発・浸透・流出・遅延を計算し、**水源キーごとに集約した連続窓を1回**渡す。固定の毎tick呼出しは要求しない。M3の低頻度更新や必要時の遅延評価で送る。同じ水源/同じ窓へ天候セルごとに別予算を送らず、河川と帯水層へ二重配分しない。Aは入力の適用・保存・重複防止の所有者であり、涵養量を計算しない。

返値RechargeResultはfromTick/toTick/offeredMg/acceptedMg/overflowMg/committedNow。直近の同一窓・同量の再送は元の結果＋falseであり、overflowを後から再投入してはならない。同一区間の別量、直近より古い区間、重複/逆順/gap、異品質は例外で無変更。越流は容量に入らなかった正のmgとしてM3が計上する。

**将来の減水への所見:** 現在の受け口は非負の補給専用で、蒸発・負の残量変化・水位は表せない。符号やレシートの意味をそのまま拡張すると既存呼出しと二重計上防止を壊す。版付きキー・mg台帳・所有者は再利用でき、補給APIを残した別の減水入口または1窓の水収支契約を後から設計できる。ただし補給/減水間の窓の確定と原子性はその時に承認する。Aが「API変更不要で減水まで対応」と保証するものではない。

### 実保存と失敗時の境界

SavedData名はashen_frontier_water_sources。schema=1、initialized=true、map、definition_hash、sourcesの全件を保存する。各sources要素はdefinition（key/quality/optional stock）と、有限源だけbalance（remaining_mg/recharge_through_tick/optional last_recharge）。直近レシートはfrom_tick/to_tick/offered_mg/accepted_mg/overflow_mg。本文の平坦なNBT例は概念例であり、実際の階層はこの形式。

定義hashはkind/localKey順に並べた全WaterSourceDefinitionのCodec JSONのUTF-8 SHA-256。順序だけの変更では再初期化しない。初期量を含む定義変更は不一致で拒否する。全レコード・0残高を維持し、欠損や未知schemaを満水の新規源にしない。標準DimensionDataStorageが読み込み例外をnullへ変換するため、既存datがある状態の初期化を明示拒否する。移行は含めない。

SavedDataの標準保存は非同期。正常停止がIOを完了する既存の仕組みを使い、移送ごとにflushしない。試験でディスクを読み直す前はNeoForge標準IOUtilities.waitUntilIOWorkerCompleteを使う。停電/強制終了時の複数ファイル一括永続化は保証外。

本番の配置は#123。初期量・海域マスク・帯水層への到達/揚程・自然氷の搬出との量配分はそこで接続する。採水で表示の水面ブロックを減らさず、0mgなら見た目によらず拒否する。

### UIの確認項目

開発クライアントは起動せず、公開後の手動確認項目として、土鍋の右クリック/Shift、オフハンド、未接続・枯渇・満杯・異品質・容器スタック拒否の日英表示を残す。M3/本番worldが未接続の状態で自然補給や天候の仮値を表示しない。


## #122反証による修正（2026-11-25）

- 台帳の新規初期化と再接続を分離する。再接続時のdat全体欠損を拒否する。新規作成の判定はworld側が所有し、ファイル欠損から自動初期化へ切り替えない。正常保存・再起動までという原子性の保証は変えない。
- 携帯採水はクライアントの予測でも独自水sourceへの操作を消費し、1クリックで次の手へ進ませない。水量の変更はServerPlayerの経路だけ。
- 手を受ける移送APIは、権限のmayUseItemAtまでその手を渡す。sourceToTankもkindの次にhandを明示する。未リリースのAPI変更で、現在の呼出し元を同時に更新する。保存形式は変更しない。
- 槽→非空の異品質容器はQUALITY_MISMATCH、同品質で容量不足ならFULL。Result.message()は既存の日英キーを選び、採水と台石の容器操作が一括確定後に表示する。
