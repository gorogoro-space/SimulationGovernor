package space.gorogoro.simulationgovernor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * MetricsFiles の単体テスト(日付の計算・ファイル名・日付が変わったときの書き分け・古いファイルの削除)
 */
class MetricsFilesTest {

  private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

  @Test
  void fileNameUsesLocalDate() {
    assertEquals("metrics-2026-11-16.csv", MetricsFiles.fileName(LocalDate.of(2026, 11, 16)));
  }

  @Test
  void dateChangesAtLocalMidnight() {
    // サーバーの時刻(ここでは JST)の 0 時で日付が変わる。UTC ではまだ前の日
    Clock before = Clock.fixed(Instant.parse("2026-11-16T14:59:59Z"), JST);
    Clock after = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), JST);
    assertEquals("metrics-2026-11-16.csv", MetricsFiles.fileName(LocalDate.now(before)));
    assertEquals("metrics-2026-11-17.csv", MetricsFiles.fileName(LocalDate.now(after)));
  }

  @Test
  void timestampHasSeconds() {
    assertEquals("2026-11-16T20:31:00", MetricsFiles.timestamp(LocalDateTime.of(2026, 11, 16, 20, 31, 0)));
    assertEquals("2026-11-16T20:31:05", MetricsFiles.timestamp(LocalDateTime.of(2026, 11, 16, 20, 31, 5, 123_000_000)));
  }

  @Test
  void dateOfParsesOnlyOwnFiles() {
    assertEquals(LocalDate.of(2026, 1, 2), MetricsFiles.dateOf("metrics-2026-01-02.csv"));
    assertNull(MetricsFiles.dateOf("metrics-2026-13-40.csv"));
    assertNull(MetricsFiles.dateOf("other-2026-01-02.csv"));
    assertNull(MetricsFiles.dateOf("metrics-2026-01-02.txt"));
  }

  @Test
  void expiryKeepsTodayAndRetentionDays() {
    LocalDate today = LocalDate.of(2026, 11, 16);
    assertFalse(MetricsFiles.isExpired(today, today, 30));
    assertFalse(MetricsFiles.isExpired(today.minusDays(30), today, 30));
    assertTrue(MetricsFiles.isExpired(today.minusDays(31), today, 30));
    // 0 なら消さない
    assertFalse(MetricsFiles.isExpired(today.minusDays(1000), today, 0));
  }

  @Test
  void appendWritesHeaderOnceAndSplitsByDate(@TempDir Path dir) throws IOException {
    LocalDate d1 = LocalDate.of(2026, 11, 16);
    LocalDate d2 = d1.plusDays(1);
    MetricsFiles.append(dir, d1, List.of("a"));
    MetricsFiles.append(dir, d1, List.of("b"));
    MetricsFiles.append(dir, d2, List.of("c"));
    List<String> l1 = Files.readAllLines(dir.resolve("metrics-2026-11-16.csv"), StandardCharsets.UTF_8);
    List<String> l2 = Files.readAllLines(dir.resolve("metrics-2026-11-17.csv"), StandardCharsets.UTF_8);
    assertEquals(List.of(MetricsFiles.HEADER, "a", "b"), l1);
    assertEquals(List.of(MetricsFiles.HEADER, "c"), l2);
  }

  @Test
  void deleteExpiredRemovesOnlyOldOwnFiles(@TempDir Path dir) throws IOException {
    LocalDate today = LocalDate.of(2026, 11, 16);
    Files.writeString(dir.resolve(MetricsFiles.fileName(today.minusDays(31))), "x");
    Files.writeString(dir.resolve(MetricsFiles.fileName(today.minusDays(30))), "x");
    Files.writeString(dir.resolve(MetricsFiles.fileName(today)), "x");
    Files.writeString(dir.resolve("metrics-notadate.csv"), "x");
    Files.writeString(dir.resolve("other.csv"), "x");
    List<String> deleted = MetricsFiles.deleteExpired(dir, today, 30);
    assertEquals(List.of("metrics-2026-10-16.csv"), deleted);
    assertTrue(Files.exists(dir.resolve("metrics-2026-10-17.csv")));
    assertTrue(Files.exists(dir.resolve("metrics-2026-11-16.csv")));
    assertTrue(Files.exists(dir.resolve("metrics-notadate.csv")));
    assertTrue(Files.exists(dir.resolve("other.csv")));
  }
}
