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
}
