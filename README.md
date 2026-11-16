# SimulationGovernor
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-brightgreen.svg)](https://fill-ui.papermc.io/projects/paper)
[![GitHub release](https://img.shields.io/github/release/gorogoro-space/SimulationGovernor.svg)](https://github.com/gorogoro-space/SimulationGovernor/releases)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/gorogoro-space/SimulationGovernor/issues)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://github.com/gorogoro-space/SimulationGovernor/blob/main/LICENSE)

This plugin lowers the simulation distance automatically while the server is under heavy load.

サーバーが重い間だけ、ワールドのシミュレーション距離(演算距離)を自動で下げ、落ち着いたら元に戻すプラグインです。距離は Paper の `World#setSimulationDistance` で変えるので、再起動は要りません。

# Requirements
- Paper 1.21.11
- Java 21

# Installation method
Download the .jar file from [GitHub Releases](https://github.com/gorogoro-space/SimulationGovernor/releases), and place it in the Paper plugins folder.

.jar ファイルを GitHub の [Releases](https://github.com/gorogoro-space/SimulationGovernor/releases) からダウンロードし、Paper の plugins フォルダに置き、サーバーを再起動してください。

# Features
- **初めて入れたときは dry-run(観察だけ)です。** 距離は実際には変えず、「本来ならこう切り替えた」ことをログと毎分の CSV に残すだけです。数日分の記録を見てしきい値を決めてから、`dry-run: false` にして使ってください(下の「dry-run について」)
- 距離は「ふだん」「重いとき」(既定 3)「緊急」(既定 2)の 3 段階です。「ふだん」は、プラグインが有効になった時点の各ワールドの距離です(設定ではありません)。プラグインが距離をふだんより大きくすることはありません
- tick ごとの所要時間(Paper の `ServerTickEndEvent`)を記録し、次のように判定します
  - **下げる**: 直近 20 秒の実測 TPS が 19.2 を下回り、50ms を超えた tick が 10% 以上なら、重いときの距離にします
  - **緊急**: 直近 20 秒の実測 TPS が 18.0 を下回り、50ms を超えた tick が 50% 以上なら、緊急の距離にします(待ち時間なし。ふだんから直接下げることもあります)
  - **戻す**: 直近 180 秒で、35ms を超えた tick が 1% 以下なら、1 段階ずつ戻します
  - 一度切り替えたら 120 秒は次の切り替えをしません(緊急は除く)。距離を上げてから 20 秒は、緊急でも下げません
  - プラグインが有効になってから 5 分(サーバーの起動・プラグインの再読み込み)は判定しません。起動直後のプレイヤーのログインやチャンクの読み込みで、距離を下げないためです
- 対象は `worlds` に書いたワールドだけです。書いていないワールドには触りません。ふだんの距離が緊急の距離以下のワールドでは何もしません
- 距離を変えたときは、オンラインの OP にグレーの文字で知らせ、サーバーのログにも理由(自動・手動、そのときの TPS など)と全ワールドのチャンク数・エンティティ数を出します
- プラグインが無効になるとき(サーバーの停止・再読み込み)は、変えた距離をふだんの距離に戻します
- 1 分ごとに、対象のワールドごとの指標(人数、実際の距離と仮の距離、実測 TPS、MSPT の平均・中央値・95 パーセンタイル・最大、50/40/35ms を超えた tick の割合、判定に使う 20 秒の値、読み込まれているチャンク数、その 1 分の切り替え)を `plugins/SimulationGovernor/metrics/metrics-YYYY-MM-DD.csv` に残します。ファイルの書き込みはメインスレッドの外で行います。エンティティの数は、数えるのが重い(数千体で 1 回 2ms 以上)ので記録しません。日付はサーバーの時刻で変わり、`metrics-retention-days` 日より古いファイルは、起動時と日付が変わったときに消します

## dry-run について
- 判定はふだんどおり行い、プラグインの中の「仮の距離」を切り替えます。待ち時間・緊急・1 段階ずつ戻すなどは、仮の距離をもとに続けます。ログには `[DRY-RUN]` が付きます。OP への通知にも `[DRY-RUN]` が付きます
- **仮に下げても、実際の負荷は下がりません。** そのため「戻す」の判定は、下げていない距離の負荷で行われ、実際に切り替えた場合より戻りにくくなります。記録を読むときは、「下げる」「緊急」の回数と時刻を主に見てください
- `/simgov status` には、dry-run 中であることと、ワールドごとの実際の距離・仮の距離が出ます。`/simdist` は実際の距離を表示します
- `/simgov set` は、dry-run 中でも実際に距離を変えます(仮の距離も同じ値にそろえます)。dry-run 中は自動では実際の距離を変えないので、手動の固定が終わったとき(期限が切れたとき・`/simgov auto on`)に、実際の距離をふだんの距離に戻します(ログに `MANUAL END, DRY-RUN`)。仮の距離は、手動で合わせた値のまま判定を続けます
- `/simgov reload` で `dry-run` を true から false にすると、仮の距離は捨て、実際の距離から判定をやり直します。false から true にすると、自動で下げていた距離をふだんの距離に戻してから dry-run に入ります

# Commands
- `/simdist` — 自分がいるワールドの、今のシミュレーション距離を表示します。全員が使えます(権限 `simulationgovernor.view`、既定で全員)。コンソールからは、対象の全ワールドを表示します
- `/simgov status` — 自動の有効・無効、各ワールドの距離、直近の実測 TPS・50ms を超えた tick の割合・平均 MSPT を表示します。**OP のみ**
- `/simgov set <ワールド> <距離> [秒]` — 手動で距離を変えます(2〜ふだんの距離)。そのワールドの自動の切り替えは、指定の秒数(省くと `manual-hold-seconds`)止まり、過ぎると自動に戻ります。**OP のみ**
- `/simgov auto <on|off>` — 自動の切り替えを有効・無効にします(`on` にすると手動の固定も解きます)。**OP のみ**
- `/simgov reload` — 設定を読み直します。**OP のみ**

# Config
`plugins/SimulationGovernor/config.yml`
- `enabled` — 自動の切り替えを有効にするか。既定は `true`
- `dry-run` — true のあいだは距離を実際には変えず、ログと CSV に残すだけ。既定は `true`(すでにある config.yml にも、足りない項目として `true` が追記されます)
- `metrics-log` — 1 分ごとの指標を CSV に残すか。既定は `true`
- `metrics-retention-days` — 古い CSV を消すまでの日数(0 なら消さない)。既定は 30
- `worlds` — 対象のワールド名(複数可)。既定は `world`
- `reduced-distance` / `emergency-distance` — 重いとき・緊急の距離。既定は 3 / 2
- `check-interval-seconds` — 判定の間隔(秒)。既定は 5
- `window-seconds` — 下げる判断に使う窓(秒)。既定は 20
- `lower-threshold-tps` / `lower-min-over50-ratio` — 下げる条件(実測 TPS / 50ms を超えた tick の割合)。既定は 19.2 / 0.10
- `emergency-threshold-tps` / `emergency-min-over50-ratio` — 緊急の条件。既定は 18.0 / 0.50
- `startup-grace-seconds` — 有効になってから判定しない秒数。既定は 300
- `restore-margin-mspt` / `restore-max-ratio` / `restore-window-seconds` — 戻す条件(この ms を超えた tick の割合が、窓の間にこの値以下)。既定は 35.0 / 0.01 / 180
- `min-hold-seconds` — 切り替えたあと、次の切り替えをしない秒数(緊急は除く)。既定は 120
- `manual-hold-seconds` — `/simgov set` で秒数を省いたときに、自動を止めておく秒数。既定は 300
- `messages.*` — メッセージ(日本語)。自由に書き換えられます

config.yml が既にある場合、足りない項目は起動時に既定値で追記されます(既にある項目の値は変わりません)。

メモリ: tick ごとの記録は、判定の窓と 1 分の集計のために、長いほうの窓(既定 180 秒)+20 秒分(既定で 4,000 tick 分、約 80KB)を持ちます。CSV は 1 日に約 1,440 行 × 対象のワールドの数です。

# Disclaimer
Do not assume any responsibility by use. Please use it at your own risk.

## ビルド

JDK 21 が必要です。Gradle はラッパー(`gradlew`)が自動でダウンロードするので、別途インストールする必要はありません。

```
gradlew.bat clean build
```

(macOS / Linux では `./gradlew clean build`)

ビルドのときに、毎分の CSV の日付の計算などの単体テスト(JUnit 5)も動きます。

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Gradle を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。
「ビルド → アーティファクトのビルド」はクラスファイルが入らないことがあるので使わないでください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（SimulationGovernor）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`build`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/libs/SimulationGovernor-<version>.jar`(例: `SimulationGovernor-1.0.jar`。バージョンは `gradle.properties` の `version`)

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

## 開発(IntelliJ IDEA で Claude Code を使う)

このリポジトリには、AI コーディングツール [Claude Code](https://docs.claude.com/ja/docs/claude-code/overview) 向けの作業ルールを書いた `CLAUDE.md` があります。IntelliJ IDEA で使う手順は次のとおりです。

1. Claude の有料プラン(Pro / Max)か、Anthropic API のアカウントを用意します。
2. Windows では、先に [Git for Windows](https://git-scm.com/downloads/win) をインストールします。
3. Claude Code 本体をインストールします。Windows では PowerShell で次を実行します。
   ```
   irm https://claude.ai/install.ps1 | iex
   ```
4. IntelliJ IDEA の「設定 → プラグイン → Marketplace」で「Claude Code」を検索してインストールし、IDE を再起動します。検索結果には Anthropic 以外が作った似た名前のプラグインも表示されます。プラグイン名の下に書かれた提供元が「Anthropic」になっているものを選んでください。
5. プロジェクトを開いて、ターミナルで `claude` を実行します(`Ctrl+Esc` でも起動できます)。初回はブラウザでログインします。

起動すると `CLAUDE.md` が自動で読み込まれ、このプロジェクトの設計方針に沿って作業します。

## ライセンス

[LICENSE](LICENSE) を参照してください。

## kubotan へのメモ

リポジトリを新規作成したら、**必ず Watch を設定すること**(忘れない!)。
GitHub の自動 Watch 機能は 2025 年 5 月に廃止されたため、設定しないと他の人が立てた issue や PR の通知が届かない。

1. リポジトリのページ右上の **「Watch」** を押す
2. **「Custom」** を選び、**Issues** と **Pull requests** にチェックを入れる
