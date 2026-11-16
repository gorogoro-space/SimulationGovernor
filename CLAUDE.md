# SimulationGovernor

サーバーが重い間だけ、対象のワールドのシミュレーション距離を自動で下げ、落ち着いたら戻す Paper 用プラグイン(Paper 1.21.11・Java 21)。
リポジトリ: https://github.com/gorogoro-space/SimulationGovernor (GPL-3.0)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。新しく書くコードのコメントは日本語でよい。既存の英語のコメントは、頼まれない限り書き換えない。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` と `CLAUDE.local.md` は Git に入れない(`.git/info/exclude` で除外済み)。
- サーバーや開発 PC の環境情報(プラグインの構成・バージョン、設定ファイルの内容、JDK の場所など)は内部情報なので、Git に入るファイル(README.md・CLAUDE.md・コード・コミットメッセージ・issue・PR)に書かない。`CLAUDE.local.md` にだけ書く。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、次の観点で確認して報告する。
  - 変更概要(何を・なぜ変えているか)
  - 脆弱性(権限チェックの漏れ、入力値の検証など)
  - 安全面(再読み込み時の後始末、他プラグインの妨げなど)
  - 性能(TPS など)の低下(高頻度イベントでの重い処理、メインスレッドでの同期 I/O、定期タスクの追加など。「最重要の設計方針」に沿っているか)

## 環境

- Paper 1.21.11(`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`、`compileOnly`)
- **Java 21 必須。** `build.gradle.kts` の toolchain で JDK 21 を使い、Java 21 の class ファイルを出力する。勝手に変えない
  - paper-api 26.x は「JVM 25 以上専用」と宣言しているため、Java 21 で出力する設定ではビルドできない
- ビルド: Gradle(Kotlin DSL、wrapper 9.7.1)。`gradlew.bat clean build`(Windows)。**JDK 21 で実行する**(JDK の場所は `CLAUDE.local.md`)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/SimulationGovernor-<version>.jar`
  - IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする
  - コンパイルは `-Xlint:all` で行い、警告 0 件を保つ
  - `build` で単体テスト(JUnit 5、`testImplementation`。jar には入らない)も動く。テストは数秒で終わる大きさに保つ
- バージョンは `gradle.properties` の `version` だけで管理する。`plugin.yml` の `version: '${version}'` は `processResources` でビルド時に置き換わる
- パッケージ: `space.gorogoro.simulationgovernor`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- PlugManX(3.2.1)の `/plugman reload|unload|load SimulationGovernor` で動くことを確認済み(無効化でふだんの距離に戻り、有効化でふだんの距離を覚え直す。再読み込みの直後も `startup-grace-seconds` の間は判定しない)
- jar を差し替えるときは先に古い jar を消す(同じ名前のプラグインが 2 つあると、どちらが読まれるかわからない)
- サーバーと開発 PC の環境情報は `CLAUDE.local.md`(Git に入れない)を参照する

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(PlayerInteractEvent、PlayerMoveEvent、AsyncPlayerChatEvent など)を使うときは、安い判定を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O、HTTP 通信をしない(起動時・リロード時に一度だけ行う小さなファイルの読み込みは除く)
- 設定ファイルを追加する場合は、起動時・リロード時に一度だけ解析して保持する
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- プラグインフォルダ以外には何も書き込まない

### 他プラグインとの関係
- 既存の機能を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能仕様

(現在の実装の動作。仕様を変えたらここを更新する)

- **tick の記録**: `ServerTickEndEvent`(Paper)で `getTickNumber()`・`getTickDuration()`・`System.nanoTime()` をリングバッファ(`TickRecorder`。長いほうの窓 +20 秒 × 20 tick 分)に入れる。指標はすべてここから求める
  - 実測 TPS = 窓の中で進んだ tick 数 ÷ 実際の経過時間。50ms 超・`restore-margin-mspt` 超の割合、平均・中央値・p95・最大
  - `getTickDuration()` には tick と tick の間の処理(RCON のコマンドなど)が入らない。その停止は実測 TPS には出るが、50ms 超の割合には出ない
  - `Bukkit.getTPS()` は反応が遅いので使わない
- **起動時(`onEnable`)**: config.yml が無ければ保存し、足りない項目は `copyDefaults` で追記する。`worlds` のワールドごとに、その時点の距離を「ふだん」として覚える(距離は書き換えない)。重いとき・緊急の距離は「設定の値」と「ふだん」の小さいほう。ふだん ≤ `emergency-distance` のワールドは管理しない(ログを出す)。存在しないワールドは警告を出して飛ばす。`worlds` に無いワールドは読みも書きもしない(`/simdist` の表示と、切り替え時のログの数を除く)
- **判定(`check-interval-seconds` ごと、メインスレッド)**: 手動の固定の期限切れを処理 → 自動が OFF なら終わり → 有効になってから `startup-grace-seconds` 秒の間は判定しない → 窓(`window-seconds`)の記録がそろっていなければ判定しない
  - 緊急: 実測 TPS < `emergency-threshold-tps` かつ 50ms 超 ≥ `emergency-min-over50-ratio`(かつ ≥ `lower-min-over50-ratio`)。`min-hold-seconds` を待たない。ただし、そのワールドの距離を上げてから `window-seconds` の間は緊急で下げない
  - 下げる: 実測 TPS < `lower-threshold-tps` かつ 50ms 超 ≥ `lower-min-over50-ratio`。`min-hold-seconds` を守る
  - 戻す: 下げる条件に当たらず、`restore-window-seconds` の窓で `restore-margin-mspt` 超の割合 ≤ `restore-max-ratio`、`min-hold-seconds` を守る。1 段階ずつ(緊急 → 重いとき → ふだん)
  - 手動で固定中のワールドは、緊急も含めて判定しない
- **切り替え**: `World#setSimulationDistance`。値は設定ファイルに保存されない(再起動で server.properties・spigot.yml の値に戻る)ので、データファイルは持たない。サーバーのログに `SimulationGovernor: world=<名前> distance <前> -> <後> (<AUTO|AUTO EMERGENCY|MANUAL by ..|DISABLE|RELOAD> ...)` と、全ワールドのチャンク数・エンティティ数・ブロックエンティティ数(Paper の数。走査しない)を出す。オンラインの OP(`isOp()`)にグレーの文字で知らせる
- **dry-run(`dry-run`、既定 true)**: ワールドごとに仮の距離(`ManagedWorld#virtual`)を持つ。dry-run 中の判定は仮の距離で行い、自動の切り替えでは `setSimulationDistance` を呼ばず、仮の距離だけ変える。ログの `SimulationGovernor:` の直後と OP への通知の先頭に `[DRY-RUN]`(通知の文は `messages.dryrun-prefix`)。dry-run でないときは、切り替えのたびに仮の距離も実際と同じ値にする
  - `/simgov set` は dry-run 中でも実際に変え、仮の距離もそろえる。dry-run 中に手動の固定が終わったら(期限切れ・`/simgov auto on`)、`endManualDryRun` で実際の距離をふだんに戻す(ログ `MANUAL END, DRY-RUN`、通知 `messages.notify-manual-end-dryrun`、CSV の event は `3->4 MANUAL END`)。仮の距離・`lastChangeNanos`・`lastRaiseNanos` は手動で合わせたときの値のまま。dry-run でないときは何もしない(自動の判定が実際の距離を動かすため)。`onDisable`・`worlds` から外れたときは、実際の距離がふだんと違えば戻す(dry-run 中は手動で変えていた場合だけ)
  - `/simgov reload` で true → false: 仮の距離を実際の距離にし、`lastChangeNanos` を今にする。false → true: 手動で固定中でなければ実際の距離をふだんに戻し(`DRYRUN-ON` のログ)、仮の距離を実際の距離にする
  - `/simgov status` は dry-run 中である旨と仮の距離を出す。`/simdist` は実際の距離
- **毎分の記録(`metrics-log`、既定 true)**: `MetricsLogger` が 1 秒ごとに時刻の分が変わったかだけを見て、変わったら `metricsRows()` で対象のワールドごとに 1 行を作る(メインスレッド。リングバッファの直近 60 秒と、判定の窓の値。`getChunkCount()`・`getPlayerCount()`。`@e` 相当の `getEntities().size()` は数千体で 1 回 2ms 以上かかるので入れない)。書き込み(`MetricsFiles.append`)と古いファイルの削除(`MetricsFiles.deleteExpired`、起動時と日付が変わったとき)は `runTaskAsynchronously`。その 1 分の切り替えは `event` 列に「[DRY-RUN] 4->3 AUTO」などの形で入る。`/simgov reload` で作り直すときは、まだ書いていない切り替えを新しい `MetricsLogger` に引き継ぐ
  - 日付の計算・ファイル名・削除の判定は `MetricsFiles`(Bukkit に依存しない。今の時刻は読まず、`LocalDate`・`LocalDateTime` を受け取る)。単体テストは `src/test/java`(JUnit 5)
- **無効化(`onDisable`)**: 管理しているワールドを、ふだんの距離に戻す
- **`/simgov reload`**: 設定を読み直す。`worlds` から外れたワールドはふだんの距離に戻す。残ったワールドは覚えているふだんの距離を使い続ける(今の距離は下げた値かもしれないため)。自動の ON/OFF は config の `enabled` に戻る。起動後の猶予は数え直さない
- **コマンド**: `/simgov status|set|auto|reload` は `isOp()` で判定(権限は使わない。コンソールも可)。`set <world> <2〜ふだん> [秒]` は、そのワールドの自動を秒数(省くと `manual-hold-seconds`)止める。`auto on` は手動の固定も解く。`/simdist` は権限 `simulationgovernor.view`(既定で全員)。対象のワールドなら段階に合わせた文、対象外なら距離だけの文。コンソールからは対象の全ワールド
- **メッセージ**: すべて config.yml の `messages.*`(日本語のみ)。`plugin.yml` のコマンドの説明は英語
- **検証用のビルド**: `gradlew build -Pverification` で `src/verification/java` が加わり、`/simgov stall <秒>`・`/simgov measure <秒> [ms]`・`/simgov mkworld <名前>` が使える(jar 名は `-verification`)。本体は `Class.forName` で有無を見るだけ。リリースには使わない

## 過去にハマった点(他プロジェクトでの経験)

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない
- 乗客などのエンティティを `remove()` すると内部で降車イベント(`EntityDismountEvent`)が出る。これをキャンセルすると、消えたエンティティが乗ったまま残り、毎 tick 降ろそうとして重くなる

## ファイル構成

- `src/main/java/space/gorogoro/simulationgovernor/SimulationGovernor.java` — メインクラス。設定、ワールドの管理、判定、切り替え、コマンド、通知
- `src/main/java/space/gorogoro/simulationgovernor/TickRecorder.java` — tick ごとの所要時間のリングバッファと指標の計算
- `src/main/java/space/gorogoro/simulationgovernor/MetricsLogger.java` — 毎分の記録の定期タスクと、非同期の書き込み・削除の呼び出し
- `src/main/java/space/gorogoro/simulationgovernor/MetricsFiles.java` — CSV のファイル名・日付の計算・書き込み・古いファイルの削除(Bukkit に依存しない)
- `src/test/java/space/gorogoro/simulationgovernor/MetricsFilesTest.java` — `MetricsFiles` の単体テスト(JUnit 5)
- `src/main/java/space/gorogoro/simulationgovernor/VerificationHook.java` — 検証用のコマンドの受け口(実装は検証用のビルドにだけある)
- `src/verification/java/space/gorogoro/simulationgovernor/verification/VerificationCommands.java` — 検証用のサブコマンド(`-Pverification` のときだけビルドに入る)
- `src/main/resources/plugin.yml` — プラグイン定義
- `src/main/resources/config.yml` — 設定とメッセージ
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — Gradle の設定(バージョンは `gradle.properties`)
