package space.gorogoro.simulationgovernor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/*
 * SimulationGovernor
 * サーバーが重い間だけ、対象のワールドのシミュレーション距離を下げる。
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
public class SimulationGovernor extends JavaPlugin implements Listener {

  // Paper の World#setSimulationDistance が受け付ける一番小さい値
  private static final int MIN_DISTANCE = 2;
  // 1 チャンクのブロック数(/simdist の目安の表示用)
  private static final int BLOCKS_PER_CHUNK = 16;

  // 設定(起動時・reload 時に一度だけ読む)
  private List<String> worldNames = new ArrayList<>();
  private int reducedDistance;
  private int emergencyDistance;
  private int checkIntervalSeconds;
  private int windowSeconds;
  private double lowerThresholdTps;
  private double lowerMinOver50Ratio;
  private double emergencyThresholdTps;
  private double emergencyMinOver50Ratio;
  private int startupGraceSeconds;
  private double restoreMarginMspt;
  private double restoreMaxRatio;
  private int restoreWindowSeconds;
  private int minHoldSeconds;
  private int manualHoldSeconds;
  // true のあいだは距離を実際には変えず、「本来なら切り替えた」ことをログに残すだけ
  private boolean dryRun;
  private boolean metricsLog;
  private int metricsRetentionDays;
  // 毎分の指標の記録(metrics-log が false なら null)
  private MetricsLogger metrics = null;

  // 自動の切り替えが有効か(/simgov auto で変わる。reload で config の enabled に戻る)
  private boolean autoEnabled;
  // 管理しているワールド(キーはワールド名。config の worlds の順)
  private final Map<String, ManagedWorld> managed = new LinkedHashMap<>();
  // tick ごとの所要時間の記録
  private TickRecorder recorder = null;
  // 判定の定期タスク
  private BukkitTask checkTask = null;
  // 検証用のビルドにだけあるコマンド(通常のビルドでは null)
  private VerificationHook verificationHook = null;
  // プラグインが有効になった時刻(System.nanoTime())。起動後の猶予に使う
  private long enabledNanos = 0L;

  /**
   * 管理しているワールドの状態。
   */
  private static final class ManagedWorld {
    final String name;
    // ふだんの距離(プラグインが有効になった時点の距離)
    final int normal;
    // 重いときの距離・緊急の距離(設定の値とふだんの距離の小さいほう)
    int reduced;
    int emergency;
    // 最後に距離を切り替えた時刻(System.nanoTime())
    long lastChangeNanos;
    // 最後に距離を上げた時刻(System.nanoTime())。上げた直後は緊急で下げない
    long lastRaiseNanos;
    // 仮の距離(判定に使う距離)。dry-run でないときは実際の距離と同じ
    int virtual;
    // 手動で固定している期限(System.nanoTime())。固定していなければ 0
    long manualUntilNanos = 0L;

    ManagedWorld(String name, int normal) {
      this.name = name;
      this.normal = normal;
    }
  }

  /**
   * JavaPlugin method onEnable.
   */
  @Override
  public void onEnable() {
    try {
      // If there is no setting file, it is created
      if (!getDataFolder().exists()) {
        getDataFolder().mkdir();
      }
      File configFile = new File(getDataFolder(), "config.yml");
      if (!configFile.exists()) {
        saveDefaultConfig();
      }
      // 既存の config.yml に足りない項目があれば、jar 内の既定値を追記する
      if (!getConfig().getKeys(true).containsAll(getConfig().getDefaults().getKeys(true))) {
        getConfig().options().copyDefaults(true);
        saveConfig();
      }

      // 起動直後(プレイヤーのログインやチャンクの読み込み)の重さで切り替えないよう、ここから猶予を数える
      enabledNanos = System.nanoTime();
      loadSettings();
      setupWorlds();

      // 検証用のビルドなら、検証用のコマンドを読み込む
      try {
        Class<?> clazz = Class.forName(VerificationHook.IMPLEMENTATION);
        verificationHook = (VerificationHook) clazz.getDeclaredConstructor().newInstance();
        getLogger().warning("This is a verification build. Do not use it on a production server.");
      } catch (ClassNotFoundException e) {
        verificationHook = null;
      }

      getServer().getPluginManager().registerEvents(this, this);
      startCheckTask();
      startMetrics();
    } catch (Exception e) {
      logStackTrace(e);
    }
  }

  /**
   * JavaPlugin method onDisable.
   */
  @Override
  public void onDisable() {
    try {
      if (checkTask != null) {
        checkTask.cancel();
        checkTask = null;
      }
      if (metrics != null) {
        metrics.stop();
        metrics = null;
      }
      // 変更した距離を、ふだんの距離に戻す(dry-run 中は、手動で変えていた場合だけ戻ることになる)
      for (ManagedWorld mw : managed.values()) {
        restoreNormal(mw, "notify-disable", "DISABLE");
      }
      managed.clear();
    } catch (Exception e) {
      logStackTrace(e);
    }
  }

  /**
   * config.yml を読む。
   */
  private void loadSettings() {
    reloadConfig();
    autoEnabled = getConfig().getBoolean("enabled");
    worldNames = new ArrayList<>(getConfig().getStringList("worlds"));
    emergencyDistance = Math.max(MIN_DISTANCE, getConfig().getInt("emergency-distance"));
    reducedDistance = Math.max(emergencyDistance, getConfig().getInt("reduced-distance"));
    checkIntervalSeconds = Math.max(1, getConfig().getInt("check-interval-seconds"));
    windowSeconds = Math.max(1, getConfig().getInt("window-seconds"));
    lowerThresholdTps = getConfig().getDouble("lower-threshold-tps");
    lowerMinOver50Ratio = getConfig().getDouble("lower-min-over50-ratio");
    emergencyMinOver50Ratio = getConfig().getDouble("emergency-min-over50-ratio");
    startupGraceSeconds = Math.max(0, getConfig().getInt("startup-grace-seconds"));
    emergencyThresholdTps = getConfig().getDouble("emergency-threshold-tps");
    restoreMarginMspt = getConfig().getDouble("restore-margin-mspt");
    restoreMaxRatio = getConfig().getDouble("restore-max-ratio");
    restoreWindowSeconds = Math.max(1, getConfig().getInt("restore-window-seconds"));
    minHoldSeconds = Math.max(0, getConfig().getInt("min-hold-seconds"));
    manualHoldSeconds = Math.max(0, getConfig().getInt("manual-hold-seconds"));
    dryRun = getConfig().getBoolean("dry-run");
    metricsLog = getConfig().getBoolean("metrics-log");
    metricsRetentionDays = Math.max(0, getConfig().getInt("metrics-retention-days"));

    // 長いほうの窓(毎分の記録のため、少なくとも 60 秒)より少し多めに持つ(20 TPS の場合の数)
    int capacity = (Math.max(Math.max(windowSeconds, restoreWindowSeconds), 60) + 20) * 20;
    if (recorder == null || recorder.capacity() != capacity) {
      recorder = new TickRecorder(capacity);
    }
  }

  /**
   * config の worlds から、管理するワールドを決める。
   * 既に管理しているワールドは、覚えているふだんの距離をそのまま使う(今の距離は下げた値かもしれないため)。
   * worlds から外れたワールドは、ふだんの距離に戻してから管理を外す。
   */
  private void setupWorlds() {
    // 外れたワールド
    for (String name : new ArrayList<>(managed.keySet())) {
      if (!worldNames.contains(name)) {
        restoreNormal(managed.remove(name), "notify-reload", "RELOAD");
      }
    }

    long now = System.nanoTime();
    for (String name : worldNames) {
      World world = Bukkit.getWorld(name);
      if (world == null) {
        getLogger().warning("World not found, skipped: " + name);
        continue;
      }
      ManagedWorld mw = managed.get(name);
      if (mw == null) {
        int normal = world.getSimulationDistance();
        if (normal <= emergencyDistance) {
          // 下げる余地がない
          Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
              "SimulationGovernor: world=%s normal=%d is not above emergency-distance=%d, nothing to do",
              name, normal, emergencyDistance));
          continue;
        }
        mw = new ManagedWorld(name, normal);
        // 起動直後でも切り替えられるように、最後の切り替えを min-hold-seconds より前にしておく
        mw.lastChangeNanos = now - minHoldSeconds * 1_000_000_000L;
        mw.lastRaiseNanos = now - windowSeconds * 1_000_000_000L;
        mw.virtual = normal;
        managed.put(name, mw);
      }
      mw.reduced = Math.min(reducedDistance, mw.normal);
      mw.emergency = Math.min(emergencyDistance, mw.normal);
      if (mw.reduced >= mw.normal) {
        Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
            "SimulationGovernor: world=%s normal=%d is not above reduced-distance=%d, only emergency (%d) is used",
            name, mw.normal, reducedDistance, mw.emergency));
      }
      Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
          "SimulationGovernor: world=%s normal=%d reduced=%d emergency=%d",
          name, mw.normal, mw.reduced, mw.emergency));
    }
  }

  private void startCheckTask() {
    if (checkTask != null) {
      checkTask.cancel();
    }
    long period = checkIntervalSeconds * 20L;
    checkTask = getServer().getScheduler().runTaskTimer(this, this::check, period, period);
  }

  /**
   * tick の所要時間を記録する(Paper の ServerTickEndEvent#getTickDuration() を使う)。
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onTickEnd(ServerTickEndEvent event) {
    recorder.record(event.getTickNumber(), event.getTickDuration(), System.nanoTime());
  }

  /**
   * 判定(メインスレッドで check-interval-seconds ごとに実行)。
   */
  private void check() {
    try {
      long now = System.nanoTime();

      // 手動の固定の期限が来たワールドを、自動に戻す
      for (ManagedWorld mw : managed.values()) {
        if (mw.manualUntilNanos != 0L && now >= mw.manualUntilNanos) {
          mw.manualUntilNanos = 0L;
          World world = Bukkit.getWorld(mw.name);
          int distance = world == null ? 0 : world.getSimulationDistance();
          Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
              "SimulationGovernor: world=%s manual hold ended, auto resumed (distance=%d)", mw.name, distance));
          notifyOps(msg("notify-manual-end").replace("{world}", mw.name)
              .replace("{distance}", String.valueOf(distance)));
          if (dryRun) {
            endManualDryRun(mw);
          }
        }
      }

      if (!autoEnabled || managed.isEmpty()) {
        return;
      }
      // 有効になってから startup-grace-seconds の間は判定しない(起動直後・再読み込み直後の一時的な重さで切り替えないため)
      if (now - enabledNanos < startupGraceSeconds * 1_000_000_000L) {
        return;
      }
      TickRecorder.Stats lowerStats = recorder.stats(windowSeconds, restoreMarginMspt, now);
      if (!lowerStats.full()) {
        return;
      }
      TickRecorder.Stats restoreStats = recorder.stats(restoreWindowSeconds, restoreMarginMspt, now);
      boolean heavy = lowerStats.over50Ratio() >= lowerMinOver50Ratio;
      // 緊急は、50ms を超えた tick の割合に別の(高い)下限を設ける。1 回の長い停止では作動させないため
      boolean emergency = heavy && lowerStats.over50Ratio() >= emergencyMinOver50Ratio
          && lowerStats.tps() < emergencyThresholdTps;
      boolean lower = heavy && lowerStats.tps() < lowerThresholdTps;
      boolean restore = restoreStats.full() && restoreStats.overMarginRatio() <= restoreMaxRatio;

      for (ManagedWorld mw : managed.values()) {
        if (mw.manualUntilNanos != 0L) {
          continue;
        }
        World world = Bukkit.getWorld(mw.name);
        if (world == null) {
          continue;
        }
        // dry-run 中は仮の距離で判定を続ける(実際の距離は変えていないため)
        int current = dryRun ? mw.virtual : world.getSimulationDistance();
        boolean real = !dryRun;
        boolean holdPassed = now - mw.lastChangeNanos >= minHoldSeconds * 1_000_000_000L;
        // 距離を上げてから window-seconds の間は、窓に上げる前と上げた瞬間が混ざるので、緊急で下げない
        boolean justRaised = now - mw.lastRaiseNanos < windowSeconds * 1_000_000_000L;

        if (emergency && !justRaised && current > mw.emergency) {
          // 緊急の条件では min-hold-seconds を待たない
          changeDistance(world, mw, current, mw.emergency, now,
              fill(msg("notify-emergency"), lowerStats, restoreStats),
              String.format(Locale.ROOT, "AUTO EMERGENCY tps%ds=%.2f, over50=%.1f%%, avgMspt=%.2f",
                  windowSeconds, lowerStats.tps(), lowerStats.over50Ratio() * 100, lowerStats.avgMspt()), real);
        } else if (lower && current > mw.reduced) {
          if (holdPassed) {
            changeDistance(world, mw, current, mw.reduced, now,
                fill(msg("notify-lower"), lowerStats, restoreStats),
                String.format(Locale.ROOT, "AUTO tps%ds=%.2f, over50=%.1f%%, avgMspt=%.2f",
                    windowSeconds, lowerStats.tps(), lowerStats.over50Ratio() * 100, lowerStats.avgMspt()), real);
          }
        } else if (!lower && restore && holdPassed && current < mw.normal) {
          // 戻すときは 1 段階ずつ
          int next = current < mw.reduced ? mw.reduced : mw.normal;
          changeDistance(world, mw, current, next, now,
              fill(msg("notify-restore"), lowerStats, restoreStats),
              String.format(Locale.ROOT, "AUTO over%.0f_%ds=%.1f%%, avgMspt=%.2f",
                  restoreMarginMspt, restoreWindowSeconds, restoreStats.overMarginRatio() * 100,
                  restoreStats.avgMspt()), real);
        }
      }
    } catch (Exception e) {
      logStackTrace(e);
    }
  }

  /**
   * 距離を変え、ログと OP への通知を出す。
   * real が false(dry-run の自動の切り替え)なら、実際の距離は変えず、仮の距離だけを変える。
   */
  private void changeDistance(World world, ManagedWorld mw, int from, int to, long now, String notice, String detail,
      boolean real) {
    if (real) {
      world.setSimulationDistance(to);
    }
    mw.virtual = to;
    mw.lastChangeNanos = now;
    if (to > from) {
      mw.lastRaiseNanos = now;
    }
    String prefix = real ? "" : "[DRY-RUN] ";
    Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
        "SimulationGovernor: %sworld=%s distance %d -> %d (%s)", prefix, mw.name, from, to, detail));
    if (metrics != null) {
      // 「AUTO EMERGENCY ...」は 2 語、それ以外(AUTO・MANUAL・DISABLE・RELOAD など)は先頭の 1 語を種類とする
      String kind = detail.startsWith("AUTO EMERGENCY") ? "AUTO EMERGENCY"
          : detail.startsWith("MANUAL END") ? "MANUAL END" : detail.split(" ")[0];
      metrics.addEvent(mw.name, prefix + from + "->" + to + " " + kind);
    }
    // 対象外のワールドが負荷の原因になっていないかを後から確かめられるように、全ワールドの数を出す
    // (Paper が持っている数を使い、チャンクやエンティティは走査しない)
    for (World w : Bukkit.getWorlds()) {
      Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
          "SimulationGovernor:   world=%s chunks=%d entities=%d tileEntities=%d",
          w.getName(), w.getChunkCount(), w.getEntityCount(), w.getTileEntityCount()));
    }
    notifyOps((real ? "" : msg("dryrun-prefix")) + notice.replace("{world}", mw.name)
        .replace("{from}", String.valueOf(from))
        .replace("{to}", String.valueOf(to)));
  }

  /**
   * ふだんの距離と違っていれば、ふだんの距離に戻す。
   */
  private void restoreNormal(ManagedWorld mw, String messageKey, String detail) {
    World world = Bukkit.getWorld(mw.name);
    if (world == null) {
      return;
    }
    // 実際の距離を戻す(dry-run 中でも、手動で変えていた距離は実際に戻す)
    int current = world.getSimulationDistance();
    if (current != mw.normal) {
      changeDistance(world, mw, current, mw.normal, System.nanoTime(), msg(messageKey), detail, true);
    }
    mw.virtual = mw.normal;
  }

  /**
   * dry-run 中に手動の固定が終わったとき(期限切れ・/simgov auto on)、実際の距離をふだんの距離に戻す。
   * dry-run 中は自動の判定が実際の距離を変えないため、戻さないと手動で下げた距離が残り続ける。
   * 仮の距離と待ち時間は、手動で合わせた値のまま判定を続ける。
   */
  private void endManualDryRun(ManagedWorld mw) {
    World world = Bukkit.getWorld(mw.name);
    if (world == null) {
      return;
    }
    int current = world.getSimulationDistance();
    if (current == mw.normal) {
      return;
    }
    int virtual = mw.virtual;
    long lastChange = mw.lastChangeNanos;
    long lastRaise = mw.lastRaiseNanos;
    changeDistance(world, mw, current, mw.normal, System.nanoTime(), msg("notify-manual-end-dryrun"),
        "MANUAL END, DRY-RUN", true);
    mw.virtual = virtual;
    mw.lastChangeNanos = lastChange;
    mw.lastRaiseNanos = lastRaise;
  }

  /**
   * /simgov reload で dry-run が切り替わったとき。
   * true → false: 仮の距離は捨て、実際の距離から判定をやり直す(待ち時間は今から数える)。
   * false → true: 自動で下げていた実際の距離をふだんの距離に戻してから、dry-run に入る。
   */
  private void applyDryRunChange(boolean before) {
    if (before == dryRun) {
      return;
    }
    long now = System.nanoTime();
    for (ManagedWorld mw : managed.values()) {
      World world = Bukkit.getWorld(mw.name);
      if (world == null) {
        continue;
      }
      if (dryRun) {
        if (mw.manualUntilNanos == 0L) {
          restoreNormal(mw, "notify-dryrun-on", "DRYRUN-ON");
        }
        mw.virtual = world.getSimulationDistance();
      } else {
        mw.virtual = world.getSimulationDistance();
        mw.lastChangeNanos = now;
      }
    }
    Bukkit.getServer().getLogger().info("SimulationGovernor: dry-run " + (dryRun ? "enabled" : "disabled"));
  }

  private void startMetrics() {
    // /simgov reload で作り直すとき、まだ書いていないこの 1 分の切り替えを引き継ぐ
    Map<String, String> pending = Map.of();
    if (metrics != null) {
      metrics.stop();
      pending = metrics.drainEvents();
      metrics = null;
    }
    if (metricsLog) {
      metrics = new MetricsLogger(this, getDataFolder().toPath().resolve("metrics"), metricsRetentionDays,
          java.time.Clock.systemDefaultZone());
      pending.forEach(metrics::addEvent);
      metrics.start();
    }
  }

  /**
   * 毎分の記録の行(メインスレッドで呼ばれる)。対象のワールドごとに 1 行。
   */
  List<String> metricsRows(String time, Map<String, String> events) {
    List<String> rows = new ArrayList<>();
    long now = System.nanoTime();
    // 直近 60 秒(この 1 分)の tick の所要時間
    double[] d = recorder.durationsInWindow(60, now);
    TickRecorder.Stats minute = recorder.stats(60, 35.0, now);
    TickRecorder.Stats w = recorder.stats(windowSeconds, restoreMarginMspt, now);
    double sum = 0.0;
    double capped = 0.0;
    double max = 0.0;
    int o50 = 0;
    int o40 = 0;
    int o35 = 0;
    for (double v : d) {
      sum += v;
      capped += Math.max(v, 50.0);
      max = Math.max(max, v);
      if (v > 50.0) {
        o50++;
      }
      if (v > 40.0) {
        o40++;
      }
      if (v > 35.0) {
        o35++;
      }
    }
    int n = d.length;
    double[] sorted = d.clone();
    java.util.Arrays.sort(sorted);
    double median = n == 0 ? 0.0 : sorted[(n - 1) / 2];
    double p95 = n == 0 ? 0.0 : sorted[Math.max(0, (int) Math.ceil(n * 0.95) - 1)];
    for (ManagedWorld mw : managed.values()) {
      World world = Bukkit.getWorld(mw.name);
      if (world == null) {
        continue;
      }
      String event = events.getOrDefault(mw.name, "");
      // エンティティの数(@e 相当の getEntities().size())は、数千体で 1 回 2ms 以上かかるので記録しない
      rows.add(String.format(Locale.ROOT,
          "%s,%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%.3f,%.4f,%d,%s",
          time, mw.name, world.getPlayerCount(), world.getSimulationDistance(), dryRun ? mw.virtual
              : world.getSimulationDistance(),
          minute.tps(), n == 0 ? 0.0 : 1000.0 / (capped / n), n == 0 ? 0.0 : sum / n, median, p95, max,
          n == 0 ? 0.0 : (double) o50 / n, n == 0 ? 0.0 : (double) o40 / n, n == 0 ? 0.0 : (double) o35 / n,
          w.tps(), w.over50Ratio(), world.getChunkCount(), event));
    }
    return rows;
  }

  /**
   * オンラインの OP にだけ、グレーの文字で知らせる。
   */
  private void notifyOps(String text) {
    Component message = Component.text(text, NamedTextColor.GRAY);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.isOp()) {
        player.sendMessage(message);
      }
    }
  }

  /**
   * JavaPlugin method onCommand.
   */
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (command.getName().equalsIgnoreCase("simdist")) {
        return commandSimdist(sender);
      }
      if (!command.getName().equalsIgnoreCase("simgov")) {
        return false;
      }
      if (!sender.isOp()) {
        sender.sendMessage(msg("no-permission"));
        return true;
      }
      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      switch (sub) {
        case "status":
          commandStatus(sender);
          return true;
        case "set":
          commandSet(sender, args);
          return true;
        case "auto":
          commandAuto(sender, args);
          return true;
        case "reload": {
          boolean beforeDryRun = dryRun;
          loadSettings();
          setupWorlds();
          applyDryRunChange(beforeDryRun);
          startCheckTask();
          startMetrics();
          sender.sendMessage(msg("reloaded"));
          return true;
        }
        default:
          if (verificationHook != null && verificationHook.onCommand(this, sender, args)) {
            return true;
          }
          sender.sendMessage(msg("usage"));
          return true;
      }
    } catch (Exception e) {
      logStackTrace(e);
    }
    return true;
  }

  private boolean commandSimdist(CommandSender sender) {
    if (sender instanceof Player player) {
      sender.sendMessage(simdistLine(player.getWorld()));
      return true;
    }
    // コンソールからは、対象の全ワールドを表示する
    for (ManagedWorld mw : managed.values()) {
      World world = Bukkit.getWorld(mw.name);
      if (world != null) {
        sender.sendMessage(msg("simdist-console").replace("{world}", mw.name) + simdistLine(world));
      }
    }
    return true;
  }

  /**
   * /simdist の 1 行。対象外のワールドは距離だけを表示する。
   */
  private String simdistLine(World world) {
    int distance = world.getSimulationDistance();
    String key = "simdist-normal";
    ManagedWorld mw = managed.get(world.getName());
    if (mw != null && distance < mw.normal) {
      key = distance <= mw.emergency ? "simdist-emergency" : "simdist-reduced";
    }
    return msg(key).replace("{distance}", String.valueOf(distance))
        .replace("{blocks}", String.valueOf(distance * BLOCKS_PER_CHUNK));
  }

  private void commandStatus(CommandSender sender) {
    long now = System.nanoTime();
    TickRecorder.Stats lowerStats = recorder.stats(windowSeconds, restoreMarginMspt, now);
    TickRecorder.Stats restoreStats = recorder.stats(restoreWindowSeconds, restoreMarginMspt, now);
    sender.sendMessage(msg("status-header").replace("{auto}", autoEnabled ? "ON" : "OFF")
        .replace("{window}", String.valueOf(windowSeconds)));
    if (dryRun) {
      sender.sendMessage(msg("status-dryrun"));
    }
    sender.sendMessage(fill(msg("status-stats"), lowerStats, restoreStats));
    if (!lowerStats.full()) {
      sender.sendMessage(msg("status-stats-not-ready").replace("{window}", String.valueOf(windowSeconds)));
    }
    long graceRemain = (enabledNanos + startupGraceSeconds * 1_000_000_000L - now) / 1_000_000_000L;
    if (graceRemain > 0) {
      sender.sendMessage(msg("status-grace").replace("{grace}", String.valueOf(graceRemain)));
    }
    if (managed.isEmpty()) {
      sender.sendMessage(msg("status-none"));
    }
    for (ManagedWorld mw : managed.values()) {
      World world = Bukkit.getWorld(mw.name);
      String manual = "";
      if (mw.manualUntilNanos != 0L) {
        long remain = Math.max(0L, (mw.manualUntilNanos - now) / 1_000_000_000L);
        manual = msg("status-world-manual").replace("{hold}", String.valueOf(remain));
      }
      sender.sendMessage(msg("status-world").replace("{world}", mw.name)
          .replace("{distance}", world == null ? "-" : String.valueOf(world.getSimulationDistance()))
          .replace("{normal}", String.valueOf(mw.normal))
          .replace("{reduced}", String.valueOf(mw.reduced))
          .replace("{emergency}", String.valueOf(mw.emergency))
          .replace("{manual}", manual)
          + (dryRun ? msg("status-world-virtual").replace("{virtual}", String.valueOf(mw.virtual)) : ""));
    }
  }

  private void commandSet(CommandSender sender, String[] args) {
    if (args.length < 3 || args.length > 4) {
      sender.sendMessage(msg("usage"));
      return;
    }
    ManagedWorld mw = managed.get(args[1]);
    World world = Bukkit.getWorld(args[1]);
    if (mw == null || world == null) {
      sender.sendMessage(msg("unknown-world").replace("{world}", args[1]));
      return;
    }
    int distance;
    try {
      distance = Integer.parseInt(args[2]);
    } catch (NumberFormatException e) {
      distance = -1;
    }
    if (distance < MIN_DISTANCE || distance > mw.normal) {
      sender.sendMessage(msg("invalid-distance").replace("{normal}", String.valueOf(mw.normal)));
      return;
    }
    int hold = manualHoldSeconds;
    if (args.length == 4) {
      try {
        hold = Integer.parseInt(args[3]);
      } catch (NumberFormatException e) {
        hold = -1;
      }
      if (hold < 0) {
        sender.sendMessage(msg("invalid-seconds"));
        return;
      }
    }
    long now = System.nanoTime();
    // 0 秒でも、次の判定で自動に戻るように期限を入れる
    mw.manualUntilNanos = now + Math.max(1L, hold * 1_000_000_000L);
    int from = world.getSimulationDistance();
    String notice = msg("notify-manual").replace("{player}", sender.getName())
        .replace("{hold}", String.valueOf(hold));
    // 手動は、dry-run 中でも実際に変える(OP が明示的に操作したため)。仮の距離も同じ値にそろう
    changeDistance(world, mw, from, distance, now, notice,
        String.format(Locale.ROOT, "MANUAL by %s hold=%ds", sender.getName(), hold), true);
    sender.sendMessage(msg("set-done").replace("{world}", mw.name)
        .replace("{distance}", String.valueOf(distance)).replace("{hold}", String.valueOf(hold)));
  }

  private void commandAuto(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage(msg("usage"));
      return;
    }
    if (args[1].equalsIgnoreCase("on")) {
      autoEnabled = true;
      // auto on は「自動に任せる」という明示の指示なので、手動の固定も解く
      for (ManagedWorld mw : managed.values()) {
        boolean wasManual = mw.manualUntilNanos != 0L;
        mw.manualUntilNanos = 0L;
        if (wasManual && dryRun) {
          endManualDryRun(mw);
        }
      }
      Bukkit.getServer().getLogger().info("SimulationGovernor: auto on by " + sender.getName());
      sender.sendMessage(msg("auto-on"));
    } else if (args[1].equalsIgnoreCase("off")) {
      autoEnabled = false;
      Bukkit.getServer().getLogger().info("SimulationGovernor: auto off by " + sender.getName());
      sender.sendMessage(msg("auto-off"));
    } else {
      sender.sendMessage(msg("usage"));
    }
  }

  /**
   * メッセージの指標の部分を埋める。
   */
  private String fill(String text, TickRecorder.Stats lowerStats, TickRecorder.Stats restoreStats) {
    return text.replace("{tps}", String.format(Locale.ROOT, "%.2f", lowerStats.tps()))
        .replace("{over50}", String.format(Locale.ROOT, "%.1f", lowerStats.over50Ratio() * 100))
        .replace("{avg}", String.format(Locale.ROOT, "%.2f", lowerStats.avgMspt()))
        .replace("{window}", String.valueOf(windowSeconds))
        .replace("{overmargin}", String.format(Locale.ROOT, "%.1f", restoreStats.overMarginRatio() * 100))
        .replace("{margin}", String.format(Locale.ROOT, "%.0f", restoreMarginMspt))
        .replace("{restorewindow}", String.valueOf(restoreWindowSeconds));
  }

  /**
   * config.yml の messages から取る(無ければキーをそのまま返す)。
   */
  private String msg(String key) {
    String text = getConfig().getString("messages." + key);
    return text == null ? key : text;
  }

  /**
   * 検証用のコマンドから使う:直近の指標。
   */
  public TickRecorder.Stats currentStats(int seconds, double marginMs) {
    return recorder.stats(seconds, marginMs, System.nanoTime());
  }

  /**
   * 検証用のコマンドから使う:直近の tick ごとの所要時間(ms)。
   */
  public double[] currentDurations(int seconds) {
    return recorder.durationsInWindow(seconds, System.nanoTime());
  }

  private void logStackTrace(Exception e) {
    getLogger().log(java.util.logging.Level.SEVERE, e.getMessage(), e);
  }
}
