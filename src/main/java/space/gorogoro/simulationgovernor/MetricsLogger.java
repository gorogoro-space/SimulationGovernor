package space.gorogoro.simulationgovernor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/*
 * MetricsLogger
 * 時刻の 1 分ごとに、対象のワールドごとの指標を CSV(plugins/SimulationGovernor/metrics/)に残す。
 * 値の集計はメインスレッドで行い、ファイルの書き込みと古いファイルの削除は非同期で行う。
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
final class MetricsLogger {

  private final SimulationGovernor plugin;
  private final Path dir;
  private final int retentionDays;
  private final Clock clock;
  // 最後に記録した分(エポックからの分)
  private long lastMinute;
  // 最後に記録した日付(日付が変わったら古いファイルを消す)
  private LocalDate lastDate;
  // この 1 分の間の切り替え(ワールド名 → 「4->3 AUTO」など)
  private final Map<String, StringBuilder> events = new LinkedHashMap<>();
  private BukkitTask task = null;

  MetricsLogger(SimulationGovernor plugin, Path dir, int retentionDays, Clock clock) {
    this.plugin = plugin;
    this.dir = dir;
    this.retentionDays = retentionDays;
    this.clock = clock;
  }

  void start() {
    lastMinute = clock.millis() / 60_000L;
    lastDate = LocalDate.now(clock);
    LocalDate today = lastDate;
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteExpired(today));
    // 1 秒ごとに、時刻の分が変わったかだけを見る(変わったときだけ集計する)
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
  }

  void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  /**
   * 切り替えを、この 1 分の event 列に足す。
   */
  void addEvent(String world, String text) {
    StringBuilder sb = events.computeIfAbsent(world, k -> new StringBuilder());
    if (sb.length() > 0) {
      sb.append(" / ");
    }
    sb.append(text);
  }

  /**
   * まだ書いていない切り替えを取り出して空にする(作り直すときの引き継ぎ用)。
   */
  Map<String, String> drainEvents() {
    Map<String, String> out = new LinkedHashMap<>();
    events.forEach((k, v) -> out.put(k, v.toString()));
    events.clear();
    return out;
  }

  private void tick() {
    long minute = clock.millis() / 60_000L;
    if (minute == lastMinute) {
      return;
    }
    lastMinute = minute;
    LocalDateTime now = LocalDateTime.now(clock);
    Map<String, String> ev = drainEvents();
    List<String> rows = plugin.metricsRows(MetricsFiles.timestamp(now), ev);
    LocalDate date = now.toLocalDate();
    boolean dateChanged = !date.equals(lastDate);
    lastDate = date;
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        if (!rows.isEmpty()) {
          MetricsFiles.append(dir, date, rows);
        }
      } catch (IOException e) {
        plugin.getLogger().log(Level.WARNING, "Failed to write metrics: " + e.getMessage());
      }
      if (dateChanged) {
        deleteExpired(date);
      }
    });
  }

  private void deleteExpired(LocalDate today) {
    try {
      for (String name : MetricsFiles.deleteExpired(dir, today, retentionDays)) {
        plugin.getLogger().info("Deleted old metrics file: " + name);
      }
    } catch (IOException e) {
      plugin.getLogger().log(Level.WARNING, "Failed to delete old metrics: " + e.getMessage());
    }
  }
}
