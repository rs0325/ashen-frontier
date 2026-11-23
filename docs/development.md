# 開発環境設計

## 方針

| 項目 | 決定 |
|---|---|
| リポジトリ | モノレポ。`main`はリリース、`develop`は開発版 |
| Mod分割 | 共通基盤`core`と機能Modに分け、それぞれ独立jarで配布する。機能Modは`core`へ必須依存する |
| 対象ローダー | NeoForgeのみ。ローダー抽象化層は作らない |
| 複数バージョン | Stonecutterで1つのソースから各Minecraftバージョンを生成する。初期対象は1.21.1 |
| バージョン番号 | 全Mod同一番号でまとめてリリースする（`<mod_version>+mc<Minecraftバージョン>`） |

## 採用ツール

- **ModDevGradle**（`net.neoforged:moddev-gradle`）: NeoForge公式の開発プラグイン。
- **Stonecutter 0.10**: 条件コメントでバージョン差分を書くプリプロセッサ。ブランチ機能で複数Modを同じバージョン集合に揃える。Gradle自体をJava 25以上で動かす必要がある。
- **Gradle 9.8.1**: `gradle/gradle-daemon-jvm.properties`で実行用JDK 25を、Javaツールチェーンでコンパイル用JDK 21を、foojayで自動取得する。
- **JUnit 5**: Minecraftクラスに依存しない計算や共通ロジックをJUnit Platformで検証する。全モジュールの`test`が`build`に含まれる。
- **GameTest**: NeoForgeの`gameTestServer`を使い、`gameTest`タスクで各モジュールのGameTestを実行する。

ModDevGradleとStonecutterを組み合わせた公式例はないため、共通設定は`build-logic`の規約プラグインで自作した。

## 構成

```
settings.gradle.kts           Stonecutterへのモジュール・バージョン登録
stonecutter.gradle.kts        作業中バージョン（active）
gradle.properties             Mod共通の値（mod_version、group、作者、ライセンス）
gradle/versions/<mc>.properties  Minecraftバージョンごとの依存バージョン
build-logic/                  規約プラグイン ashenfrontier.mod
core/                         ashen_frontier_core
modules/<name>/               ashen_frontier_<name>
```

Gradleのプロジェクトは`:<モジュール>:<Minecraftバージョン>`（例: `:core:1.21.1`）になる。ソースは各モジュール直下の`src/`に置き、`versions/<mc>/`はビルド出力と実行ディレクトリだけを持つ。

規約プラグインは版プロジェクト名をMinecraftバージョン、親プロジェクト名をモジュール名として扱い、次を設定する。

- Mod IDは`ashen_frontier_<モジュール>`、groupは`<mod_group>.<モジュール>`
- NeoForge・Parchment・Javaのバージョンを`gradle/versions/<mc>.properties`から読み込む
- `client`・`server`の実行構成
- `core`以外は同じバージョンの`core`へ依存させ、実行構成へ`core`も読み込む
- `META-INF/neoforge.mods.toml`の`${...}`を展開する

## 作業手順

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat gameTest
```

```powershell
.\gradlew.bat :world:1.21.1:runClient
```

テスト用の研究・レシピデータを有効にするには、`-PrecipeTestFixture=true`を付けてクライアントまたはサーバーを起動する。専用サーバーの例:

```powershell
.\gradlew.bat -PrecipeTestFixture=true :core:1.21.1:runServer
```

`runServer`はサーバー画面を開く。`/reload`などの管理者コマンドは、この画面の入力欄で実行するか、入力欄で`op <名前>`を実行してプレイヤーに権限を与えてからゲーム内で実行する。開発クライアントのプレイヤー名は通常`Dev`。

### 確認地点の自動撮影

```powershell
.\gradlew.bat :world:1.21.1:runScreenshots '-Pplan=tools/screenshots/plans/river-headwaters.json'
```

計画のパスはリポジトリルート基準（絶対パスも可）。ファイル名は英数字・`_`・`-`と`.json`で構成する。
サンプルは#84の谷頭の見下ろしと段差の近景を撮影する。撮影元ブランチの地形を使用するため、上流の変更を確認するときはその変更を含むブランチで実行する。

実装は`tools/screenshots/src/main/`の開発専用ソースセットに置き、worldの撮影タスクだけで専用Modとして読み込む。既存の補助ソースセット構成を再利用し、Minecraft・NeoForge・Gson以外の依存は追加していない。通常の`runClient`・`gameTestServer`からは除外し、配布jarにはクラス・Mod情報・Mixinを含めない。計画の指定がなければ撮影処理も登録しない。`build`で撮影コードのコンパイルと計画読込みの単体テストも検証する。

撮影クライアントのウィンドウは、モニターの枚数や配置によらず**生成時から非表示・非フォーカス**とし、タスクバーにも表示しない。撮影専用の`config/fml.toml`でNeoForgeの起動画面を無効化し、開発専用Mixinで本体の生成直前に`GLFW_VISIBLE=false`・`GLFW_FOCUSED=false`・`GLFW_FOCUS_ON_SHOW=false`を指定する。マウスも取り込まない。実行中に表示またはフォーカス取得を検出した場合は、非表示にして失敗結果を記録し終了する。表示する方式への自動切替は行わない。

非表示でも最小化はせず、MinecraftのRenderTargetへの通常描画を続け、`Screenshot.grab`でPNGを保存する。フォーカス喪失時の一時停止を無効化し、指定サイズの描画先ができるまで待つ。Gradleは通常のJavaExecで子Javaを起動し、新しいコンソールを作成しない。外部からバックグラウンド起動する際も、Windowsでは`CREATE_NO_WINDOW`などでコンソールを表示しないこと。

出力はignoredの`build/screenshots/<計画ファイル名（拡張子なし）>/`。通常の開発用`run/`・`options.txt`・ワールドへは書き込まない。

- `client/`: 撮影専用の設定、ログ、新規ワールド。設定は実行前に撮影用へ初期化し、ワールドは毎回`capture-<UUID>`という別フォルダーで新規作成する。既存ワールドを削除・再利用しない。
- `runs/<UUID>/screenshots/<地点名>.png`: 今回の撮影画像。過去の画像を成功と誤認しないよう、毎回別の出力先を使う。
- `runs/<UUID>/result.json`: 計画パス、開始時刻、全体の成否・エラー・経過秒数、各地点の計画値・成否・エラー・経過秒数・実座標と視点・画像の相対パス。

JSONはUTF-8で記述し、以下の全フィールドを指定する。書式全体は[サンプル計画](../tools/screenshots/plans/river-headwaters.json)を参照。

| フィールド | 指定 |
|---|---|
| `world` | `name`は表示名、`preset`は`ashen_frontier:fixed_map`固定、`seed`は整数 |
| `gameMode` | `spectator` / `creative` / `survival` / `adventure`。撮影時は重力と移動速度を止める |
| `environment` | `time`は0〜23999、`weather`は`clear` / `rain` / `thunder`。昼夜・天候サイクルとMob自然出現を停止し、難易度はピースフル |
| `view` | `width`は320〜7680、`height`は240〜4320、`fov`は30〜110、`hud`・`debug`は真偽値、`renderDistance`は2〜32チャンク。F3表示にはHUDも必要 |
| `timeoutSeconds` | 地点ごとの移動・チャンク生成・描画待ち・PNG保存の合計上限（1〜3600秒） |
| `startupTimeoutSeconds` | ワールド参加までの上限（1〜3600秒） |
| `points` | 1件以上。各地点は`name`、`x`・`y`・`z`、`yaw`・`pitch`、`waitSeconds`を指定 |

地点名は英数字で始まる1〜64文字の英数字・`_`・`-`とし、重複とWindowsの予約名は禁止。X/Zは固定マップ内（-16384以上16384未満）、Yは-64〜1024。座標は足元で、`yaw`は-180〜180度（0が南、90が西）、`pitch`は-90〜90度（正が下向き）。`waitSeconds`は0以上かつ地点の上限時間未満。

移動は統合サーバーで実行する。クライアントが指定座標・視点へ到達し、描画距離内のチャンク受信と照明処理、描画キュー、可視セクションのコンパイルが完了したことを毎フレーム確認する。完了状態が`waitSeconds`（最低1秒）続いた後、HUD・F3を含むフレーム全体を保存する。待ち状態へ戻れば安定時間も数え直す。上限時間に達した地点は画像を成功扱いせず、失敗を記録して次へ進む。PNG保存のコールバックを受けてから次の地点へ移動し、全地点の結果を書いてクライアントを終了する。Gradleの終了コードだけで成功と判断せず、`result.json`の`success`と画像も確認する。

撮影後のワールド・ログ・画像は調査用に保持する。不要になった場合は撮影出力だけを削除する。高い描画距離や大きな画面サイズはメモリ・GPU負荷と待ち時間を増やす。

### 機能Modの追加

機能Modの責務・分割・依存は[設計02](design/02-architecture.md)で確定している。`modules/world`は[M2](design/12-m2-fixed-map.md)の固定マップを担当する。

1. `settings.gradle.kts`の`featureModules`へ名前を追加する。
2. `modules/<name>/build.gradle.kts`で`id("ashenfrontier.mod")`を適用する。
3. `modules/<name>/src/main/resources/META-INF/neoforge.mods.toml`を`modules/world`に倣って作成する。

### Minecraftバージョンの追加

1. `gradle/versions/<mc>.properties`を作成する。
2. `settings.gradle.kts`の`minecraftVersions`へ追加する。
3. バージョン差分は`//? if >=1.21.2 {`などの条件コメントで書く。作業バージョンの切替はStonecutterのタスク`Set active project to <mc>`で行い、コミット前に`Reset active project`で基準バージョンへ戻す。

## 未決定事項

- Mavenへの公開

## GameTest構造物

構造物テンプレートは、対象Modの`src/main/resources/data/<mod_id>/structure/`に置き、GameTestクラス名（小文字）を先頭に付けたテンプレート名で配置する。クラス名の接頭辞を付けない場合は`@PrefixGameTestTemplate(false)`を付け、テンプレート名と名前空間を`@GameTest`で指定する。
