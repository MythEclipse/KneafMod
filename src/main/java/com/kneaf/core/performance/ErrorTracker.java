package com.kneaf.core.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;

/**
 * Error and exception tracker with context preservation.
 * Provides comprehensive error tracking, categorization, and analysis
 * capabilities.
 */
public final class ErrorTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorTracker.class);

    // Error storage with thread safety
    private final ConcurrentHashMap<String, ErrorCategory> errorCategories = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TrackedError> recentErrors = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicLong> errorCounters = new ConcurrentHashMap<>();

    // Error tracking configuration
    private final AtomicInteger maxRecentErrors = new AtomicInteger(1000);
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    private final AtomicBoolean preserveStackTraces = new AtomicBoolean(true);

    // Error statistics
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong uniqueErrors = new AtomicLong(0);
    private final AtomicLong lastErrorTime = new AtomicLong(0);

    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulTracking = new AtomicLong(System.currentTimeMillis());

    // Error analysis
    private final ConcurrentHashMap<String, ErrorPattern> errorPatterns = new ConcurrentHashMap<>();
    private final AtomicLong patternAnalysisInterval = new AtomicLong(300000); // 5 minutes

    public ErrorTracker() {
        LOGGER.info("Initializing ErrorTracker");
        startPatternAnalysis();
    }

    /**
     * Record an error with context
     */
    public void recordError(String component, Throwable error, Map<String, Object> context) {
        if (!isEnabled.get()) {
            return;
        }

        try {
            // Create tracked error
            TrackedError trackedError = new TrackedError(
                    component, error, context != null ? new HashMap<>(context) : new HashMap<>());

            // Add to recent errors queue
            addToRecentErrors(trackedError);

            // Update error counters
            updateErrorCounters(trackedError);

            // Categorize error
            categorizeError(trackedError);

            // Update statistics
            totalErrors.incrementAndGet();
            lastErrorTime.set(System.currentTimeMillis());
            lastSuccessfulTracking.set(System.currentTimeMillis());

            // Log error if severe
            if (isSevereError(error)) {
                LOGGER.error("Severe error tracked in component '{}': {}", component, error.getMessage(), error);
            } else {
                LOGGER.debug("Error tracked in component '{}': {}", component, error.getMessage());
            }

            updateHealthStatus();

        } catch (Exception e) {
            LOGGER.error("Error tracking error from component '{}'", component, e);
            isHealthy.set(false);
        }
    }

    /**
     * Record an error with minimal context
     */
    public void recordError(String component, Throwable error) {
        recordError(component, error, new HashMap<>());
    }

    /**
     * Record an error with message and context
     */
    public void recordError(String component, String errorMessage, Map<String, Object> context) {
        recordError(component, new RuntimeException(errorMessage), context);
    }

    /**
     * Record an error with message only
     */
    public void recordError(String component, String errorMessage) {
        recordError(component, new RuntimeException(errorMessage));
    }

    /**
     * Add error to recent errors queue
     */
    private void addToRecentErrors(TrackedError error) {
        recentErrors.offer(error);

        // Remove old errors if queue is too large
        while (recentErrors.size() > maxRecentErrors.get()) {
            recentErrors.poll();
        }
    }

    /**
     * Update error counters
     */
    private void updateErrorCounters(TrackedError error) {
        String errorKey = error.getErrorKey();
        errorCounters.computeIfAbsent(errorKey, k -> new AtomicLong(0)).incrementAndGet();

        // Update component-specific counters
        String componentKey = "component." + error.getComponent();
        errorCounters.computeIfAbsent(componentKey, k -> new AtomicLong(0)).incrementAndGet();

        // Update error type counters
        String typeKey = "type." + error.getErrorType();
        errorCounters.computeIfAbsent(typeKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Categorize error
     */
    private void categorizeError(TrackedError error) {
        String category = determineErrorCategory(error);
        ErrorCategory errorCategory = errorCategories.computeIfAbsent(category,
                k -> new ErrorCategory(category));
        errorCategory.addError(error);
    }

    /**
     * Determine error category based on error characteristics
     */
    private String determineErrorCategory(TrackedError error) {
        Throwable throwable = error.getError();
        String errorMessage = throwable.getMessage();

        // Check for specific error patterns
        if (throwable instanceof NullPointerException) {
            return "NULL_POINTER";
        } else if (throwable instanceof IllegalArgumentException) {
            return "INVALID_ARGUMENT";
        } else if (throwable instanceof IllegalStateException) {
            return "INVALID_STATE";
        } else if (throwable instanceof InterruptedException) {
            return "INTERRUPTED";
        } else if (throwable instanceof TimeoutException) {
            return "TIMEOUT";
        } else if (errorMessage != null && errorMessage.toLowerCase().contains("memory")) {
            return "MEMORY_ERROR";
        } else if (errorMessage != null && errorMessage.toLowerCase().contains("thread")) {
            return "THREAD_ERROR";
        } else if (errorMessage != null && errorMessage.toLowerCase().contains("network")) {
            return "NETWORK_ERROR";
        } else if (errorMessage != null && errorMessage.toLowerCase().contains("file")) {
            return "FILE_ERROR";
        } else if (errorMessage != null && errorMessage.toLowerCase().contains("database")) {
            return "DATABASE_ERROR";
        } else {
            return "GENERAL_ERROR";
        }
    }

    /**
     * Check if error is severe
     */
    private boolean isSevereError(Throwable error) {
        return error instanceof OutOfMemoryError ||
                error instanceof StackOverflowError ||
                error instanceof VirtualMachineError ||
                (error.getMessage() != null &&
                        (error.getMessage().contains("critical") ||
                                error.getMessage().contains("fatal") ||
                                error.getMessage().contains("severe")));
    }

    /**
     * Get recent errors
     */
    public List<TrackedError> getRecentErrors(int maxCount) {
        List<TrackedError> errors = new ArrayList<>();
        Iterator<TrackedError> iterator = recentErrors.iterator();

        int count = 0;
        while (iterator.hasNext() && count < maxCount) {
            errors.add(iterator.next());
            count++;
        }

        return errors;
    }

    /**
     * Get errors by component
     */
    public List<TrackedError> getErrorsByComponent(String component, int maxCount) {
        List<TrackedError> result = new ArrayList<>();

        for (TrackedError error : recentErrors) {
            if (error.getComponent().equals(component)) {
                result.add(error);
                if (result.size() >= maxCount) {
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Get errors by category
     */
    public List<TrackedError> getErrorsByCategory(String category, int maxCount) {
        ErrorCategory errorCategory = errorCategories.get(category);
        if (errorCategory == null) {
            return new ArrayList<>();
        }

        return errorCategory.getRecentErrors(maxCount);
    }

    /**
     * Get error statistics by component
     */
    public Map<String, Long> getErrorStatisticsByComponent() {
        Map<String, Long> statistics = new HashMap<>();

        for (TrackedError error : recentErrors) {
            String component = error.getComponent();
            statistics.put(component, statistics.getOrDefault(component, 0L) + 1);
        }

        return statistics;
    }

    /**
     * Get error statistics by category
     */
    public Map<String, Long> getErrorStatisticsByCategory() {
        Map<String, Long> statistics = new HashMap<>();

        for (Map.Entry<String, ErrorCategory> entry : errorCategories.entrySet()) {
            statistics.put(entry.getKey(), entry.getValue().getErrorCount());
        }

        return statistics;
    }

    /**
     * Get error rate statistics
     */
    public ErrorRateStatistics getErrorRateStatistics() {
        long now = System.currentTimeMillis();
        long oneMinuteAgo = now - 60000;
        long fiveMinutesAgo = now - 300000;
        long oneHourAgo = now - 3600000;

        long errorsLastMinute = 0;
        long errorsLast5Minutes = 0;
        long errorsLastHour = 0;

        for (TrackedError error : recentErrors) {
            long errorTime = error.getTimestamp().toEpochMilli();

            if (errorTime >= oneMinuteAgo) {
                errorsLastMinute++;
            }
            if (errorTime >= fiveMinutesAgo) {
                errorsLast5Minutes++;
            }
            if (errorTime >= oneHourAgo) {
                errorsLastHour++;
            }
        }

        return new ErrorRateStatistics(
                errorsLastMinute,
                errorsLast5Minutes,
                errorsLastHour,
                totalErrors.get(),
                uniqueErrors.get());
    }

    /**
     * Get error counter value
     */
    public long getErrorCounter(String key) {
        AtomicLong counter = errorCounters.get(key);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Clear recent errors
     */
    public void clearRecentErrors() {
        recentErrors.clear();
        errorCounters.clear();
        totalErrors.set(0);
        uniqueErrors.set(0);
        LOGGER.info("Recent errors cleared");
    }

    /**
     * Clear error categories
     */
    public void clearErrorCategories() {
        errorCategories.clear();
        LOGGER.info("Error categories cleared");
    }

    /**
     * Start pattern analysis
     */
    private void startPatternAnalysis() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::analyzeErrorPatterns,
                patternAnalysisInterval.get(), patternAnalysisInterval.get(), TimeUnit.MILLISECONDS);
    }

    /**
     * Analyze error patterns
     */
    private void analyzeErrorPatterns() {
        if (!isEnabled.get()) {
            return;
        }

        try {
            // Analyze recent errors for patterns
            Map<String, Integer> patternFrequency = new HashMap<>();

            for (TrackedError error : recentErrors) {
                String pattern = extractErrorPattern(error);
                patternFrequency.put(pattern, patternFrequency.getOrDefault(pattern, 0) + 1);
            }

            // Update error patterns
            for (Map.Entry<String, Integer> entry : patternFrequency.entrySet()) {
                ErrorPattern pattern = errorPatterns.computeIfAbsent(entry.getKey(),
                        k -> new ErrorPattern(entry.getKey()));
                pattern.updateFrequency(entry.getValue());
            }

        } catch (Exception e) {
            LOGGER.error("Error during pattern analysis", e);
        }
    }

    /**
     * Extract error pattern from tracked error
     */
    private String extractErrorPattern(TrackedError error) {
        StringBuilder pattern = new StringBuilder();

        pattern.append(error.getComponent()).append("|");
        pattern.append(error.getErrorType()).append("|");

        // Add simplified error message pattern
        String message = error.getError().getMessage();
        if (message != null) {
            // Remove specific values and keep pattern
            String simplified = message.replaceAll("\\d+", "N")
                    .replaceAll("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}", "UUID")
                    .replaceAll("\\b\\d+\\.\\d+\\b", "FLOAT")
                    .replaceAll("\\b\\d+\\b", "INT");
            pattern.append(simplified);
        }

        return pattern.toString();
    }

    /**
     * Get error patterns
     */
    public List<ErrorPattern> getErrorPatterns(int maxPatterns) {
        List<ErrorPattern> patterns = new ArrayList<>(errorPatterns.values());
        patterns.sort((a, b) -> Long.compare(b.getFrequency(), a.getFrequency()));

        if (patterns.size() > maxPatterns) {
            patterns = patterns.subList(0, maxPatterns);
        }

        return patterns;
    }

    /**
     * Enable/disable error tracking
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        LOGGER.info("ErrorTracker {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Set whether to preserve stack traces
     */
    public void setPreserveStackTraces(boolean preserve) {
        this.preserveStackTraces.set(preserve);
        LOGGER.info("Stack trace preservation {}", preserve ? "enabled" : "disabled");
    }

    /**
     * Set maximum number of recent errors to keep
     */
    public void setMaxRecentErrors(int maxErrors) {
        this.maxRecentErrors.set(Math.max(10, maxErrors)); // Minimum 10
        LOGGER.info("Max recent errors set to {}", maxErrors);
    }

    /**
     * Check if error tracker is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() &&
                (System.currentTimeMillis() - lastSuccessfulTracking.get()) < 30000; // 30 second timeout
    }

    /**
     * Shutdown the error tracker
     */
    public void shutdown() {
        LOGGER.info("Shutting down ErrorTracker");
        setEnabled(false);
        clearRecentErrors();
        clearErrorCategories();
    }

    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = totalErrors.get() < 1000 && // Less than 1000 total errors
                (System.currentTimeMillis() - lastSuccessfulTracking.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }

    /**
     * Tracked error information
     */
    public static class TrackedError {
        private final String id;
        private final String component;
        private final Throwable error;
        private final Map<String, Object> context;
        private final Instant timestamp;
        private final String errorKey;
        private final String errorType;

        public TrackedError(String component, Throwable error, Map<String, Object> context) {
            this.id = UUID.randomUUID().toString();
            this.component = component;
            this.error = error;
            this.context = new HashMap<>(context);
            this.timestamp = Instant.now();
            this.errorType = error.getClass().getSimpleName();
            this.errorKey = generateErrorKey();
        }

        private String generateErrorKey() {
            return component + ":" + errorType + ":" +
                    (error.getMessage() != null ? error.getMessage().hashCode() : "null");
        }

        public String getId() {
            return id;
        }

        public String getComponent() {
            return component;
        }

        public Throwable getError() {
            return error;
        }

        public Map<String, Object> getContext() {
            return new HashMap<>(context);
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getErrorKey() {
            return errorKey;
        }

        public String getErrorType() {
            return errorType;
        }

        public String getErrorMessage() {
            return error.getMessage();
        }

        @Override
        public String toString() {
            return String.format("TrackedError{id='%s', component='%s', errorType='%s', timestamp=%s, message='%s'}",
                    id, component, errorType, timestamp, getErrorMessage());
        }
    }

    /**
     * Error category information
     */
    private static class ErrorCategory {
        private final String category;
        private final ConcurrentLinkedQueue<TrackedError> errors = new ConcurrentLinkedQueue<>();
        private final AtomicLong errorCount = new AtomicLong(0);

        public ErrorCategory(String category) {
            this.category = category;
        }

        public void addError(TrackedError error) {
            errors.offer(error);
            errorCount.incrementAndGet();
        }

        public List<TrackedError> getRecentErrors(int maxCount) {
            List<TrackedError> result = new ArrayList<>();
            Iterator<TrackedError> iterator = errors.iterator();

            int count = 0;
            while (iterator.hasNext() && count < maxCount) {
                result.add(iterator.next());
                count++;
            }

            return result;
        }

        public long getErrorCount() {
            return errorCount.get();
        }

        @SuppressWarnings("unused")
        public String getCategory() {
            return category;
        }
    }

    /**
     * Error pattern information
     */
    public static class ErrorPattern {
        private final String pattern;
        private final AtomicLong frequency = new AtomicLong(0);
        private final AtomicLong lastSeen = new AtomicLong(System.currentTimeMillis());

        public ErrorPattern(String pattern) {
            this.pattern = pattern;
        }

        public void updateFrequency(long newFrequency) {
            this.frequency.set(newFrequency);
            this.lastSeen.set(System.currentTimeMillis());
        }

        public String getPattern() {
            return pattern;
        }

        public long getFrequency() {
            return frequency.get();
        }

        public long getLastSeen() {
            return lastSeen.get();
        }

        public long getAgeMs() {
            return System.currentTimeMillis() - lastSeen.get();
        }

        @Override
        public String toString() {
            return String.format("ErrorPattern{pattern='%s', frequency=%d, lastSeen=%d}",
                    pattern, frequency.get(), lastSeen.get());
        }
    }

    /**
     * Error rate statistics
     */
    public static class ErrorRateStatistics {
        private final long errorsLastMinute;
        private final long errorsLast5Minutes;
        private final long errorsLastHour;
        private final long totalErrors;
        private final long uniqueErrors;

        public ErrorRateStatistics(long errorsLastMinute, long errorsLast5Minutes,
                long errorsLastHour, long totalErrors, long uniqueErrors) {
            this.errorsLastMinute = errorsLastMinute;
            this.errorsLast5Minutes = errorsLast5Minutes;
            this.errorsLastHour = errorsLastHour;
            this.totalErrors = totalErrors;
            this.uniqueErrors = uniqueErrors;
        }

        public long getErrorsLastMinute() {
            return errorsLastMinute;
        }

        public long getErrorsLast5Minutes() {
            return errorsLast5Minutes;
        }

        public long getErrorsLastHour() {
            return errorsLastHour;
        }

        public long getTotalErrors() {
            return totalErrors;
        }

        public long getUniqueErrors() {
            return uniqueErrors;
        }

        public double getErrorsPerMinute() {
            return errorsLastMinute;
        }

        public double getErrorsPer5Minutes() {
            return errorsLast5Minutes / 5.0;
        }

        public double getErrorsPerHour() {
            return errorsLastHour / 60.0;
        }
    }
}