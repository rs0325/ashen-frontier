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
