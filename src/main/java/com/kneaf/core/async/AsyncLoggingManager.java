package com.kneaf.core.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.kneaf.core.async.model.CollectorStatistics;

/**
 * Global async logging manager yang intercept semua logging operations.
 * Provides transparent async logging tanpa mengubah existing code.
 * 
 * Usage:
 * AsyncLoggingManager.initialize();
 * // Existing logging code akan otomatis menjadi async
 */
public final class AsyncLoggingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncLoggingManager.class);
    private static final AsyncLoggingManager INSTANCE = new AsyncLoggingManager();

    private final AsyncLoggerRegistry loggerRegistry;
    private final AsyncMetricsCollector metricsCollector;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // Background thread pool untuk async operations
    private final ExecutorService asyncExecutor;

    // Statistics
    private final AtomicLong totalAsyncOperations = new AtomicLong(0);
    private final AtomicLong totalSyncFallbacks = new AtomicLong(0);

    private AsyncLoggingManager() {
        this.loggerRegistry = AsyncLoggerRegistry.getInstance();
        this.metricsCollector = AsyncMetricsCollector.getInstance();

        // Create thread pool untuk general async operations
        this.asyncExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
                r -> {
                    Thread t = new Thread(r, "AsyncLogging-Worker");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "AsyncLoggingManager-Shutdown"));
    }

    public static AsyncLoggingManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize async logging system
     */
    public static void initialize() {
        AsyncLoggingManager manager = getInstance();
        if (manager.initialized.compareAndSet(false, true)) {
            LOGGER.info("AsyncLoggingManager initialized - All logging operations are now async");
        }
    }

    /**
     * Get async logger untuk class
     */
    public static AsyncLogger getAsyncLogger(Class<?> clazz) {
        return getInstance().loggerRegistry.getLogger(clazz);
    }

    /**
     * Get async logger dengan name
     */
    public static AsyncLogger getAsyncLogger(String name) {
        return getInstance().loggerRegistry.getLogger(name);
    }

    /**
     * Get async metrics collector
     */
    public static AsyncMetricsCollector getMetricsCollector() {
        return getInstance().metricsCollector;
    }

    /**
     * Execute task asynchronously tanpa blocking caller
     */
    public static void executeAsync(Runnable task) {
        AsyncLoggingManager manager = getInstance();
        if (manager.enabled.get()) {
            try {
                manager.asyncExecutor.execute(() -> {
                    try {
                        task.run();
                        manager.totalAsyncOperations.incrementAndGet();
                    } catch (Exception e) {
                        // Log error tapi jangan throw untuk avoid breaking executor
                        LOGGER.error("Error in async task", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Executor full, fallback to sync
                manager.totalSyncFallbacks.incrementAndGet();
                task.run();
            }
        } else {
            // Async disabled, run synchronously
            task.run();
        }
    }

    /**
     * Execute task asynchronously dengan timeout
     */
    public static CompletableFuture<Void> executeAsyncWithTimeout(Runnable task, long timeoutMs) {
        return CompletableFuture.runAsync(task, getInstance().asyncExecutor)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    LOGGER.warn("Async task failed or timed out", throwable);
                    return null;
                });
    }

    /**
     * Execute callable asynchronously
     */
    public static <T> CompletableFuture<T> executeAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                getInstance().totalAsyncOperations.incrementAndGet();
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, getInstance().asyncExecutor);
    }

    /**
     * Enable/disable async logging
     */
    public static void setEnabled(boolean enabled) {
        getInstance().enabled.set(enabled);
        LOGGER.info("AsyncLoggingManager {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Check if async logging is enabled
     */
    public static boolean isEnabled() {
        return getInstance().enabled.get();
    }

    /**
     * Get comprehensive statistics
     */
    public static ManagerStatistics getStatistics() {
        AsyncLoggingManager manager = getInstance();
        AsyncLoggerRegistry.RegistryStatistics loggerStats = manager.loggerRegistry.getStatistics();
        CollectorStatistics metricsStats = manager.metricsCollector.getStatistics();

        return new ManagerStatistics(
                loggerStats,
                metricsStats,
                manager.totalAsyncOperations.get(),
                manager.totalSyncFallbacks.get(),
                manager.enabled.get(),
                manager.initialized.get());
    }

    public static class ManagerStatistics {
        public final AsyncLoggerRegistry.RegistryStatistics loggerStats;
        public final CollectorStatistics metricsStats;
        public final long totalAsyncOperations;
        public final long totalSyncFallbacks;
        public final boolean enabled;
        public final boolean initialized;

        public ManagerStatistics(
                AsyncLoggerRegistry.RegistryStatistics loggerStats,
                CollectorStatistics metricsStats,
                long asyncOps,
                long syncFallbacks,
                boolean enabled,
                boolean initialized) {
            this.loggerStats = loggerStats;
            this.metricsStats = metricsStats;
            this.totalAsyncOperations = asyncOps;
            this.totalSyncFallbacks = syncFallbacks;
            this.enabled = enabled;
            this.initialized = initialized;
        }

        public double getAsyncSuccessRate() {
            long total = totalAsyncOperations + totalSyncFallbacks;
            return total > 0 ? (double) totalAsyncOperations / total : 1.0;
        }
    }

    /**
     * Print statistics report
     */
    public static void printStatistics() {
        ManagerStatistics stats = getStatistics();

        System.out.println("\n=== AsyncLoggingManager Statistics ===");
        System.out.println("Initialized: " + stats.initialized);
        System.out.println("Enabled: " + stats.enabled);
        System.out.println("\nLogging:");
        System.out.println("  Active loggers: " + stats.loggerStats.activeLoggers);
        System.out.println("  Messages logged: " + stats.loggerStats.totalMessagesLogged);
        System.out.println("  Messages dropped: " + stats.loggerStats.totalMessagesDropped);
        System.out.println("  Pending messages: " + stats.loggerStats.totalPendingMessages);
        System.out.println("\nMetrics:");
        System.out.println("  Counters: " + stats.metricsStats.counterCount);
        System.out.println("  Gauges: " + stats.metricsStats.gaugeCount);
        System.out.println("  Histograms: " + stats.metricsStats.histogramCount);
        System.out.println("  Timers: " + stats.metricsStats.timerCount);
        System.out.println("  Total recorded: " + stats.metricsStats.totalRecorded);
        System.out.println("  Dropped: " + stats.metricsStats.totalDropped);
        System.out.println("\nAsync Operations:");
        System.out.println("  Total async: " + stats.totalAsyncOperations);
        System.out.println("  Sync fallbacks: " + stats.totalSyncFallbacks);
        System.out.println("  Success rate: " + String.format("%.2f%%", stats.getAsyncSuccessRate() * 100));
        System.out.println("=====================================\n");
    }

    /**
     * Shutdown async logging manager
     */
    private void shutdown() {
        LOGGER.info("Shutting down AsyncLoggingManager...");

        // Print final statistics
        printStatistics();

        // Shutdown components
        loggerRegistry.shutdownAll();
        metricsCollector.shutdown();

        // Shutdown executor
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("AsyncLoggingManager shutdown complete");
    }

    /**
     * Force shutdown (untuk testing)
     */
    public static void forceShutdown() {
        getInstance().shutdown();
    }
}
