package space.gorogoro.simulationgovernor;

import java.util.Arrays;

/*
 * TickRecorder
 * tick ごとの所要時間を持つリングバッファ。判断に使う指標は、すべてこの記録から求める。
 * 記録も集計もメインスレッドだけで行う(同期は不要)。
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
public class TickRecorder {

  // tick 番号(ServerTickEndEvent#getTickNumber()。Bukkit.getCurrentTick() と同じ数)
  private final int[] tickNumbers;
  // tick が終わった時刻(System.nanoTime())
  private final long[] endNanos;
  // tick の所要時間(ms。ServerTickEndEvent#getTickDuration())
  private final double[] durations;
  // 次に書き込む位置
  private int head = 0;
  // 記録している件数
  private int size = 0;

  /**
   * @param capacity 持っておく tick の数
   */
  public TickRecorder(int capacity) {
    tickNumbers = new int[capacity];
    endNanos = new long[capacity];
    durations = new double[capacity];
  }

  public int capacity() {
    return durations.length;
  }

  /**
   * 1 tick 分を記録する。
   */
  public void record(int tickNumber, double durationMs, long nowNanos) {
    tickNumbers[head] = tickNumber;
    endNanos[head] = nowNanos;
    durations[head] = durationMs;
    head = (head + 1) % durations.length;
    if (size < durations.length) {
      size++;
    }
  }

  /**
   * 直近 windowSeconds 秒の、tick ごとの所要時間(ms)を古い順に返す(検証用のコマンドが使う)。
   */
  public double[] durationsInWindow(int windowSeconds, long nowNanos) {
    long windowStart = nowNanos - windowSeconds * 1_000_000_000L;
    int newest = (head - 1 + durations.length) % durations.length;
    int count = 0;
    while (count < size && endNanos[(newest - count + durations.length) % durations.length] >= windowStart) {
      count++;
    }
    double[] out = new double[count];
    for (int i = 0; i < count; i++) {
      out[count - 1 - i] = durations[(newest - i + durations.length) % durations.length];
    }
    return out;
  }

  /**
   * 直近 windowSeconds 秒の指標を求める。
   * 窓の秒数分の記録がまだ無いときは full が false になる(判断に使わない)。
   */
  public Stats stats(int windowSeconds, double marginMs, long nowNanos) {
    if (size == 0) {
      return Stats.EMPTY;
    }
    long windowStart = nowNanos - windowSeconds * 1_000_000_000L;
    int newest = (head - 1 + durations.length) % durations.length;

    // 窓の中で一番古い記録を探す(新しいほうから遡る)
    int count = 0;
    int oldest = newest;
    double sum = 0.0;
    double max = 0.0;
    int over50 = 0;
    int overMargin = 0;
    double[] values = new double[size];
    for (int i = 0; i < size; i++) {
      int idx = (newest - i + durations.length) % durations.length;
      if (endNanos[idx] < windowStart) {
        break;
      }
      double d = durations[idx];
      values[count] = d;
      count++;
      oldest = idx;
      sum += d;
      if (d > max) {
        max = d;
      }
      if (d > 50.0) {
        over50++;
      }
      if (d > marginMs) {
        overMargin++;
      }
    }
    // バッファの一番古い記録が窓の始まりより新しければ、窓の秒数分の記録がそろっていない
    int bufferOldest = size < durations.length ? 0 : head;
    boolean full = endNanos[bufferOldest] < windowStart;
    if (count == 0) {
      return new Stats(full, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    // 実測 TPS = 窓の間に進んだ tick 数 ÷ 実際の経過時間
    // 窓の中で一番古い tick の終わりから、一番新しい tick の終わりまでで測る
    double tps = 0.0;
    long elapsed = endNanos[newest] - endNanos[oldest];
    int ticks = tickNumbers[newest] - tickNumbers[oldest];
    if (elapsed > 0 && ticks > 0) {
      tps = ticks / (elapsed / 1_000_000_000.0);
    }

    double[] sorted = Arrays.copyOf(values, count);
    Arrays.sort(sorted);
    double median = sorted[(count - 1) / 2];
    double p95 = sorted[(int) Math.ceil(count * 0.95) - 1];

    return new Stats(full, count, tps, (double) over50 / count, (double) overMargin / count,
        sum / count, median, p95, max);
  }

  /**
   * 窓の指標。割合は 0.0〜1.0。
   * @param full        窓の秒数分の記録がそろっているか
   * @param ticks       窓の中の tick 数
   * @param tps         実測 TPS
   * @param over50Ratio 50ms を超えた tick の割合
   * @param overMarginRatio 指定した ms を超えた tick の割合
   * @param avgMspt     平均 MSPT
   * @param medianMspt  MSPT の中央値
   * @param p95Mspt     MSPT の 95 パーセンタイル
   * @param maxMspt     最大 MSPT
   */
  public record Stats(boolean full, int ticks, double tps, double over50Ratio, double overMarginRatio,
      double avgMspt, double medianMspt, double p95Mspt, double maxMspt) {
    static final Stats EMPTY = new Stats(false, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
  }
}
