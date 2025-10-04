package com.kneaf.core.performance.monitoring;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Simple metrics logger that appends periodic performance summaries to
 * run/logs/kneaf-performance.log
 */
public final class PerformanceMetricsLogger {
  private static final Path LOG_PATH = Paths.get("run", "logs", "kneaf-performance.log");
  private static final Path LOG_ARCHIVE_DIR = Paths.get("run", "logs", "archive");
  // Cache the config so we don't reload properties file on every rotation check
  private static final PerformanceConfig CONFIG = PerformanceConfig.load();
  // Single-threaded executor to perform file IO off the main thread to avoid blocking ticks
  private static final ExecutorService LOG_EXECUTOR =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KneafMetricsLogger");
        t.setDaemon(true);
        return t;
      });

  private PerformanceMetricsLogger() {}

  private static synchronized void ensureLogDir() throws IOException {
    Path dir = LOG_PATH.getParent();
    if (dir != null && !Files.exists(dir)) {
      Files.createDirectories(dir);
    }
    if (!Files.exists(LOG_PATH)) {
      Files.createFile(LOG_PATH);
    }
  }

  /**
   * Non-blocking log write: schedule write to a dedicated single-threaded executor so that
   * server tick threads are not blocked on file IO or rotations.
   */
  public static void logLine(String line) {
    LOG_EXECUTOR.submit(
        () -> {
          try {
            // Rotate if needed before writing
            rotateLogIfNeeded();
            ensureLogDir();
            try (PrintWriter out =
                new PrintWriter(
                    Files.newBufferedWriter(
                        LOG_PATH,
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND))) {
              out.println(Instant.now().toString() + " " + line);
            }
          } catch (IOException e) {
            // Fail silently; logging should not bring down server
          }
        });
  }

  public static void logOptimizations(String summary) {
    logLine(summary);
  }

  /**
   * Rotate the performance log if it exceeds the configured maximum size. This is safe to call
   * frequently.
   */
  public static synchronized void rotateLogIfNeeded() {
    try {
      ensureLogDir();
      if (!Files.exists(LOG_PATH)) return;
      long maxBytes = CONFIG.getMaxLogBytes();
      long size = Files.size(LOG_PATH);
      if (size > maxBytes && maxBytes > 0) {
        rotateNow();
      }
    } catch (IOException e) {
      // Ignore rotation failures
    }
  }

  /** Force rotate the current performance log to an archive file with timestamp. */
  public static synchronized void rotateNow() {
    try {
      ensureLogDir();
      if (!Files.exists(LOG_PATH)) return;
      if (!Files.exists(LOG_ARCHIVE_DIR)) Files.createDirectories(LOG_ARCHIVE_DIR);
      String name = "kneaf-performance-" + Instant.now().toString().replace(':', '-') + ".log";
      Path target = LOG_ARCHIVE_DIR.resolve(name);
      Files.move(LOG_PATH, target);
      // Create new empty log file
      Files.createFile(LOG_PATH);
    } catch (IOException e) {
      // Ignore rotation failures
    }
  }

  static {
    // Ensure executor is shut down cleanly on JVM exit to flush pending logs
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    LOG_EXECUTOR.shutdown();
                    LOG_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                }));
  }
}
