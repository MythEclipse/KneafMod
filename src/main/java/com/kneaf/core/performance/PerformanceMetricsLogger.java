package com.kneaf.core.performance;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Simple metrics logger that appends periodic performance summaries to run/logs/kneaf-performance.log
 */
public final class PerformanceMetricsLogger {
    private static final Path LOG_PATH = Paths.get("run", "logs", "kneaf-performance.log");
    private static final Path LOG_ARCHIVE_DIR = Paths.get("run", "logs", "archive");

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

    public static synchronized void logLine(String line) {
        try {
            // Rotate if needed before writing
            rotateLogIfNeeded();
            ensureLogDir();
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(LOG_PATH, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND))) {
                out.println(Instant.now().toString() + " " + line);
            }
        } catch (IOException e) {
            // Fail silently; logging should not bring down server
        }
    }

    public static void logOptimizations(String summary) {
        logLine(summary);
    }

    /**
     * Rotate the performance log if it exceeds the configured maximum size.
     * This is safe to call frequently.
     */
    public static synchronized void rotateLogIfNeeded() {
        try {
            ensureLogDir();
            if (!Files.exists(LOG_PATH)) return;
            long maxBytes = com.kneaf.core.performance.PerformanceConfig.load().getMaxLogBytes();
            long size = Files.size(LOG_PATH);
            if (size > maxBytes && maxBytes > 0) {
                rotateNow();
            }
        } catch (IOException e) {
            // Ignore rotation failures
        }
    }

    /**
     * Force rotate the current performance log to an archive file with timestamp.
     */
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
}
