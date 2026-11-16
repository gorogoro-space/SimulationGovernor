package space.gorogoro.simulationgovernor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/*
 * MetricsFiles
 * 毎分の指標の CSV のファイル名・日付の計算・書き込み・古いファイルの削除。
 * Bukkit に依存しない(単体テストのため)。今の時刻はここでは読まず、呼び出す側から渡す。
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
public final class MetricsFiles {

  public static final String PREFIX = "metrics-";
  public static final String SUFFIX = ".csv";
  public static final String HEADER = "time,world,players,actual_distance,virtual_distance,tps,capped_tps,"
      + "avg_mspt,median_mspt,p95_mspt,max_mspt,over50_ratio,over40_ratio,over35_ratio,"
      + "tps20s,over50_20s,loaded_chunks,event";

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private MetricsFiles() {
  }

  /**
   * その日のファイル名(metrics-YYYY-MM-DD.csv)。
   */
  public static String fileName(LocalDate date) {
    return PREFIX + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + SUFFIX;
  }

  /**
   * ファイル名から日付を取り出す。このプラグインのファイル名でなければ null。
   */
  public static LocalDate dateOf(String fileName) {
    if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) {
      return null;
    }
    String date = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
    try {
      return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * CSV の time 列(ISO 8601、秒まで)。
   */
  public static String timestamp(LocalDateTime time) {
    return time.format(TIME);
  }

  /**
   * 消すべきファイルか。retentionDays が 0 以下なら消さない。
   * 今日から retentionDays 日前より古い日付のファイルを消す(今日と、その前の retentionDays 日分は残す)。
   */
  public static boolean isExpired(LocalDate fileDate, LocalDate today, int retentionDays) {
    return retentionDays > 0 && fileDate.isBefore(today.minusDays(retentionDays));
  }

  /**
   * 行を追記する。ファイルが無ければ見出しの行から書く。
   */
  public static synchronized void append(Path dir, LocalDate date, List<String> rows) throws IOException {
    Files.createDirectories(dir);
    Path file = dir.resolve(fileName(date));
    List<String> lines = new ArrayList<>();
    if (!Files.exists(file)) {
      lines.add(HEADER);
    }
    lines.addAll(rows);
    Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * 古いファイルを消し、消したファイル名を返す。このプラグインのファイル名でないものには触らない。
   */
  public static synchronized List<String> deleteExpired(Path dir, LocalDate today, int retentionDays) throws IOException {
    List<String> deleted = new ArrayList<>();
    if (retentionDays <= 0 || !Files.isDirectory(dir)) {
      return deleted;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
      for (Path p : stream) {
        LocalDate d = dateOf(p.getFileName().toString());
        if (d != null && isExpired(d, today, retentionDays)) {
          Files.delete(p);
          deleted.add(p.getFileName().toString());
        }
      }
    }
    return deleted;
  }
}
