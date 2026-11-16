package space.gorogoro.simulationgovernor.verification;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import space.gorogoro.simulationgovernor.SimulationGovernor;
import space.gorogoro.simulationgovernor.TickRecorder;
import space.gorogoro.simulationgovernor.VerificationHook;

/*
 * VerificationCommands
 * 検証用のビルド(gradlew build -Pverification)にだけ入るサブコマンド。本番では使わない。
 *   /simgov stall <秒>            メインスレッドを指定の秒数だけ止める(1 回だけの長い停止の再現)
 *   /simgov measure <秒> [ms]     直近の指標と、全ワールドの数を 1 行で出す(計測のスクリプトが読む)
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
public class VerificationCommands implements VerificationHook {

  @Override
  public boolean onCommand(SimulationGovernor plugin, CommandSender sender, String[] args) {
    if (args.length == 0) {
      return false;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "stall":
        stall(sender, args);
        return true;
      case "measure":
        measure(plugin, sender, args);
        return true;
      case "mkworld":
        mkworld(sender, args);
        return true;
      case "bench":
        bench(plugin, args);
        return true;
      default:
        return false;
    }
  }

  private void stall(CommandSender sender, String[] args) {
    double seconds = args.length >= 2 ? Double.parseDouble(args[1]) : 1.0;
    Bukkit.getServer().getLogger().info(String.format(Locale.ROOT, "SimulationGovernor: VERIFY stall %.2fs", seconds));
    try {
      Thread.sleep((long) (seconds * 1000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    sender.sendMessage("stalled " + seconds + "s");
  }

  // 検証 6.5-7 用:平らなワールドを作る(距離は spigot.yml の world-settings.<名前>.simulation-distance で決まる)
  private void mkworld(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage("usage: /simgov mkworld <name>");
      return;
    }
    World w = new org.bukkit.WorldCreator(args[1]).type(org.bukkit.WorldType.FLAT).createWorld();
    String line = "SimulationGovernor: VERIFY mkworld " + args[1] + " simulationDistance="
        + (w == null ? "-" : String.valueOf(w.getSimulationDistance()));
    Bukkit.getServer().getLogger().info(line);
    sender.sendMessage(line);
  }

  /**
   * 毎分の記録の 1 行を作る処理の部分ごとの時間を、nanoTime で直接測る。/simgov bench <ワールド> [回数]
   * stats: リングバッファの集計(metricsRows と同じ 60 秒・20 秒の stats と、60 秒分のコピー・並べ替え)
   */
  private void bench(SimulationGovernor plugin, String[] args) {
    World world = Bukkit.getWorld(args.length >= 2 ? args[1] : "world");
    int reps = args.length >= 3 ? Integer.parseInt(args[2]) : 20;
    if (world == null) {
      return;
    }
    long tStats = 0;
    long tList = 0;
    long tCount = 0;
    long tChunk = 0;
    long sink = 0;
    for (int i = 0; i < reps; i++) {
      long t0 = System.nanoTime();
      double[] d = plugin.currentDurations(60);
      TickRecorder.Stats a = plugin.currentStats(60, 35.0);
      TickRecorder.Stats b = plugin.currentStats(20, 35.0);
      double[] sorted = d.clone();
      java.util.Arrays.sort(sorted);
      sink += sorted.length + a.ticks() + b.ticks();
      long t1 = System.nanoTime();
      sink += world.getEntities().size();
      long t2 = System.nanoTime();
      sink += world.getEntityCount();
      long t3 = System.nanoTime();
      sink += world.getChunkCount() + world.getPlayerCount();
      long t4 = System.nanoTime();
      tStats += t1 - t0;
      tList += t2 - t1;
      tCount += t3 - t2;
      tChunk += t4 - t3;
    }
    Bukkit.getServer().getLogger().info(String.format(Locale.ROOT,
        "SimulationGovernor: VERIFY bench world=%s reps=%d statsUs=%.1f entitiesListUs=%.1f entityCountUs=%.1f"
            + " chunkPlayerUs=%.1f (sink=%d)",
        world.getName(), reps, tStats / 1000.0 / reps, tList / 1000.0 / reps, tCount / 1000.0 / reps,
        tChunk / 1000.0 / reps, sink));
  }

  private void measure(SimulationGovernor plugin, CommandSender sender, String[] args) {
    int seconds = args.length >= 2 ? Integer.parseInt(args[1]) : 20;
    double margin = args.length >= 3 ? Double.parseDouble(args[2]) : 40.0;
    TickRecorder.Stats s = plugin.currentStats(seconds, margin);
    long chunks = 0;
    long entities = 0;
    long tiles = 0;
    long entityList = 0;
    long loadedChunks = 0;
    StringBuilder distances = new StringBuilder();
    for (World w : Bukkit.getWorlds()) {
      chunks += w.getChunkCount();
      entities += w.getEntityCount();
      tiles += w.getTileEntityCount();
      // 実際のエンティティの数(@e 相当)と、読み込まれているチャンクの数
      entityList += w.getEntities().size();
      loadedChunks += w.getLoadedChunks().length;
      distances.append(w.getName()).append(':').append(w.getSimulationDistance()).append(' ');
    }
    Runtime rt = Runtime.getRuntime();
    long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    // 「1 tick の所要時間と 50ms の大きいほう」の平均から求めた TPS(遅れが取り戻されていなければ実測 TPS と一致する)
    double[] d = plugin.currentDurations(seconds);
    double cappedSum = 0.0;
    int o40 = 0;
    int o35 = 0;
    for (double v : d) {
      cappedSum += Math.max(v, 50.0);
      if (v > 40.0) {
        o40++;
      }
      if (v > 35.0) {
        o35++;
      }
    }
    double cappedTps = d.length == 0 ? 0.0 : 1000.0 / (cappedSum / d.length);
    double r40 = d.length == 0 ? 0.0 : (double) o40 / d.length;
    double r35 = d.length == 0 ? 0.0 : (double) o35 / d.length;
    String line = String.format(Locale.ROOT,
        "SimulationGovernor: VERIFY measure window=%d full=%b ticks=%d tps=%.3f cappedTps=%.3f over50=%.4f over%.0f=%.4f"
            + " r40=%.4f r35=%.4f avg=%.3f median=%.3f p95=%.3f max=%.3f chunks=%d entities=%d tiles=%d"
            + " entityList=%d loadedChunks=%d heapMb=%d distances=%s",
        seconds, s.full(), s.ticks(), s.tps(), cappedTps, s.over50Ratio(), margin, s.overMarginRatio(),
        r40, r35, s.avgMspt(), s.medianMspt(), s.p95Mspt(), s.maxMspt(), chunks, entities, tiles,
        entityList, loadedChunks, heapMb, distances.toString().trim());
    Bukkit.getServer().getLogger().info(line);
    if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
      sender.sendMessage(line);
    }
  }
}
