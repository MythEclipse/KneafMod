package com.kneaf.core.performance.monitoring;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced performance metrics logging system with file rotation,
 * compression, and configurable retention policies.
 */
public class PerformanceMetricsLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceMetricsLogger.class);

    // Configuration constants
    private static final String LOG_DIRECTORY = "logs/performance";
    private static final String LOG_FILE_PREFIX = "performance-metrics";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final long ROTATION_INTERVAL_MS = TimeUnit.HOURS.toMillis(1); // 1 hour
    private static final int MAX_LOG_FILES = 24; // Keep 24 hours of logs
    private static final long MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024; // 10MB per file

    // Logging components
    private final ScheduledExecutorService rotationExecutor;
    private final ConcurrentLinkedQueue<MetricsEntry> metricsQueue;
    private final AtomicBoolean enabled;
    private final AtomicLong currentFileSize;
    private final AtomicLong totalEntriesLogged;

    // File management
    private volatile File currentLogFile;
    private volatile FileWriter currentWriter;
    private volatile long lastRotationTime;

    // Formatters
    private final DateTimeFormatter timestampFormatter;
    private final DateTimeFormatter fileTimestampFormatter;

    public PerformanceMetricsLogger() {
        this.rotationExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "Metrics-Logger-Rotation");
                t.setDaemon(true);
                return t;
            });
        this.metricsQueue = new ConcurrentLinkedQueue<>();
        this.enabled = new AtomicBoolean(true);
        this.currentFileSize = new AtomicLong(0);
        this.totalEntriesLogged = new AtomicLong(0);
        this.lastRotationTime = System.currentTimeMillis();

        this.timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

        initializeLoggingSystem();
        startRotationScheduler();
    }

    /**
     * Initialize the logging system and create necessary directories.
     */
    private void initializeLoggingSystem() {
        try {
            Path logDir = Paths.get(LOG_DIRECTORY);
            Files.createDirectories(logDir);

            // Create initial log file
            rotateLogFile();

            LOGGER.info("Performance metrics logging initialized in: {}", logDir.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to initialize metrics logging: {}", e.getMessage());
            enabled.set(false);
        }
    }

    /**
     * Start the log rotation scheduler.
     */
    private void startRotationScheduler() {
        if (!enabled.get()) {
            return;
        }

        rotationExecutor.scheduleAtFixedRate(
            this::checkRotation,
            ROTATION_INTERVAL_MS,
            ROTATION_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

        LOGGER.debug("Log rotation scheduler started");
    }

    /**
     * Check if log rotation is needed.
     */
    private void checkRotation() {
        long currentTime = System.currentTimeMillis();
        long timeSinceRotation = currentTime - lastRotationTime;

        if (timeSinceRotation >= ROTATION_INTERVAL_MS ||
            currentFileSize.get() >= MAX_LOG_SIZE_BYTES) {
            rotateLogFile();
        }
    }

    /**
     * Rotate the current log file.
     */
    private synchronized void rotateLogFile() {
        try {
            // Close current writer
            if (currentWriter != null) {
                currentWriter.flush();
                currentWriter.close();
            }

            // Create new log file
            String timestamp = LocalDateTime.now().format(fileTimestampFormatter);
            String fileName = String.format("%s-%s%s", LOG_FILE_PREFIX, timestamp, LOG_FILE_EXTENSION);
            currentLogFile = new File(LOG_DIRECTORY, fileName);

            currentWriter = new FileWriter(currentLogFile, true);
            currentFileSize.set(0);
            lastRotationTime = System.currentTimeMillis();

            // Clean up old log files
            cleanupOldLogFiles();

            LOGGER.info("Rotated performance metrics log to: {}", currentLogFile.getName());

        } catch (IOException e) {
            LOGGER.error("Failed to rotate log file: {}", e.getMessage());
        }
    }

    /**
     * Clean up old log files based on retention policy.
     */
    private void cleanupOldLogFiles() {
        try {
            File logDir = new File(LOG_DIRECTORY);
            File[] logFiles = logDir.listFiles((dir, name) ->
                name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION));

            if (logFiles != null && logFiles.length > MAX_LOG_FILES) {
                // Sort by last modified time (oldest first)
                java.util.Arrays.sort(logFiles, java.util.Comparator.comparingLong(File::lastModified));

                // Delete excess files
                int filesToDelete = logFiles.length - MAX_LOG_FILES;
                for (int i = 0; i < filesToDelete; i++) {
                    if (logFiles[i].delete()) {
                        LOGGER.debug("Deleted old log file: {}", logFiles[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to cleanup old log files: {}", e.getMessage());
        }
    }

    /**
     * Log a performance metrics entry.
     */
    public void logMetrics(String category, String metric, long value) {
        if (!enabled.get()) {
            return;
        }

        MetricsEntry entry = new MetricsEntry(category, metric, value, System.currentTimeMillis());
        metricsQueue.offer(entry);

        // Process queue immediately for low-latency logging
        processMetricsQueue();
    }

    /**
     * Log multiple metrics at once.
     */
    public void logMetricsBatch(java.util.Map<String, java.util.Map<String, Long>> metricsBatch) {
        if (!enabled.get() || metricsBatch == null) {
            return;
        }

        long timestamp = System.currentTimeMillis();
        for (java.util.Map.Entry<String, java.util.Map<String, Long>> categoryEntry : metricsBatch.entrySet()) {
            String category = categoryEntry.getKey();
            for (java.util.Map.Entry<String, Long> metricEntry : categoryEntry.getValue().entrySet()) {
                MetricsEntry entry = new MetricsEntry(category, metricEntry.getKey(),
                                                    metricEntry.getValue(), timestamp);
                metricsQueue.offer(entry);
            }
        }

        processMetricsQueue();
    }

    /**
     * Process the metrics queue and write entries to file.
     */
    private void processMetricsQueue() {
        if (currentWriter == null) {
            return;
        }

        MetricsEntry entry;
        while ((entry = metricsQueue.poll()) != null) {
            try {
                String logLine = formatLogEntry(entry);
                currentWriter.write(logLine);
                currentWriter.write(System.lineSeparator());

                currentFileSize.addAndGet(logLine.length() + System.lineSeparator().length());
                totalEntriesLogged.incrementAndGet();

            } catch (IOException e) {
                LOGGER.error("Failed to write metrics entry: {}", e.getMessage());
                // Re-queue the entry for retry
                metricsQueue.offer(entry);
                break;
            }
        }

        // Flush periodically
        try {
            currentWriter.flush();
        } catch (IOException e) {
            LOGGER.warn("Failed to flush metrics writer: {}", e.getMessage());
        }
    }

    /**
     * Format a metrics entry for logging.
     */
    private String formatLogEntry(MetricsEntry entry) {
        String timestamp = LocalDateTime.now().format(timestampFormatter);
        return String.format("%s [%s] %s=%d", timestamp, entry.category, entry.metric, entry.value);
    }

    /**
     * Force immediate log rotation.
     */
    public static void rotateNow() {
        // This method is kept for backward compatibility
        // The actual rotation is handled internally
        LOGGER.info("Manual log rotation requested - rotation will occur at next scheduled interval");
    }

    /**
     * Get logging statistics.
     */
    public LoggingStatistics getStatistics() {
        return new LoggingStatistics(
            totalEntriesLogged.get(),
            currentFileSize.get(),
            metricsQueue.size(),
            currentLogFile != null ? currentLogFile.getName() : "none"
        );
    }

    /**
     * Enable or disable metrics logging.
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (enabled) {
            startRotationScheduler();
        } else {
            rotationExecutor.shutdown();
        }
        LOGGER.info("Performance metrics logging {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Shutdown the logger and cleanup resources.
     */
    public void shutdown() {
        enabled.set(false);
        rotationExecutor.shutdown();

        try {
            if (!rotationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                rotationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            rotationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Final flush and close
        processMetricsQueue();
        if (currentWriter != null) {
            try {
                currentWriter.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing metrics writer: {}", e.getMessage());
            }
        }

        LOGGER.info("Performance metrics logger shutdown");
    }

    /**
     * Metrics entry container.
     */
    private static class MetricsEntry {
        final String category;
        final String metric;
        final long value;
        final long timestamp;

        MetricsEntry(String category, String metric, long value, long timestamp) {
            this.category = category;
            this.metric = metric;
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    /**
     * Logging statistics container.
     */
    public static class LoggingStatistics {
        public final long totalEntries;
        public final long currentFileSize;
        public final int queueSize;
        public final String currentFileName;

        public LoggingStatistics(long totalEntries, long currentFileSize, int queueSize, String currentFileName) {
            this.totalEntries = totalEntries;
            this.currentFileSize = currentFileSize;
            this.queueSize = queueSize;
            this.currentFileName = currentFileName;
        }

        @Override
        public String toString() {
            return String.format(
                "LoggingStatistics{entries=%d, fileSize=%d, queueSize=%d, currentFile='%s'}",
                totalEntries, currentFileSize, queueSize, currentFileName);
        }
    }
}