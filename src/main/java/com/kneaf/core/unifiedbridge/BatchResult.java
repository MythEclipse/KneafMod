package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable result structure for batch operations.
 * Contains comprehensive statistics about batch processing results.
 */
public final class BatchResult {
    private final long batchId;
    private final long startTimeNanos;
    private final long endTimeNanos;
    private final int totalTasks;
    private final int successfulTasks;
    private final int failedTasks;
    private final long totalBytesProcessed;
    private final Map<String, Object> detailedStats;
    private final String status;
    private final String errorMessage;

    /**
     * Create a new BatchResult instance.
     * @param batchId Unique identifier for the batch
     * @param startTimeNanos Start time in nanoseconds
     * @param endTimeNanos End time in nanoseconds
     * @param totalTasks Total tasks in batch
     * @param successfulTasks Successful tasks
     * @param failedTasks Failed tasks
     * @param totalBytesProcessed Total bytes processed
     * @param detailedStats Detailed statistics map
     * @param status Batch status (e.g., "COMPLETED", "PARTIAL", "FAILED")
     * @param errorMessage Error message if status is not COMPLETED
     */
    public BatchResult(long batchId, long startTimeNanos, long endTimeNanos,
                      int totalTasks, int successfulTasks, int failedTasks,
                      long totalBytesProcessed, Map<String, Object> detailedStats,
                      String status, String errorMessage) {
        this.batchId = batchId;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.totalTasks = totalTasks;
        this.successfulTasks = successfulTasks;
        this.failedTasks = failedTasks;
        this.totalBytesProcessed = totalBytesProcessed;
        this.detailedStats = Map.copyOf(Objects.requireNonNull(detailedStats));
        this.status = Objects.requireNonNull(status);
        this.errorMessage = errorMessage;
    }

    /**
     * Get unique batch identifier.
     * @return Batch ID
     */
    public long getBatchId() {
        return batchId;
    }

    /**
     * Get batch start time in nanoseconds.
     * @return Start time in nanoseconds
     */
    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    /**
     * Get batch end time in nanoseconds.
     * @return End time in nanoseconds
     */
    public long getEndTimeNanos() {
        return endTimeNanos;
    }

    /**
     * Get total duration in nanoseconds.
     * @return Duration in nanoseconds
     */
    public long getDurationNanos() {
        return endTimeNanos - startTimeNanos;
    }

    /**
     * Get total duration in milliseconds.
     * @return Duration in milliseconds
     */
    public long getDurationMillis() {
        return TimeUnit.NANOSECONDS.toMillis(getDurationNanos());
    }

    /**
     * Get total tasks in batch.
     * @return Total tasks
     */
    public int getTotalTasks() {
        return totalTasks;
    }

    /**
     * Get successful tasks count.
     * @return Successful tasks
     */
    public int getSuccessfulTasks() {
        return successfulTasks;
    }

    /**
     * Get failed tasks count.
     * @return Failed tasks
     */
    public int getFailedTasks() {
        return failedTasks;
    }

    /**
     * Get total bytes processed.
     * @return Total bytes
     */
    public long getTotalBytesProcessed() {
        return totalBytesProcessed;
    }

    /**
     * Get detailed statistics map.
     * @return Immutable map of detailed statistics
     */
    public Map<String, Object> getDetailedStats() {
        return detailedStats;
    }

    /**
     * Get batch status.
     * @return Status string (e.g., "COMPLETED", "PARTIAL", "FAILED")
     */
    public String getStatus() {
        return status;
    }

    /**
     * Get error message if batch failed or had partial success.
     * @return Error message or null if no error
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Check if batch completed successfully.
     * @return true if all tasks succeeded, false otherwise
     */
    public boolean isSuccessful() {
        return "COMPLETED".equals(status) && failedTasks == 0;
    }

    /**
     * Check if batch had partial success (some tasks failed).
     * @return true if some tasks succeeded but some failed
     */
    public boolean isPartialSuccess() {
        return "PARTIAL".equals(status) && successfulTasks > 0 && failedTasks > 0;
    }

    /**
     * Check if batch failed completely.
     * @return true if all tasks failed or batch failed to process
     */
    public boolean isFailed() {
        return "FAILED".equals(status) || (totalTasks > 0 && successfulTasks == 0);
    }

    @Override
    public String toString() {
        return "BatchResult{" +
                "batchId=" + batchId +
                ", durationMillis=" + getDurationMillis() +
                ", totalTasks=" + totalTasks +
                ", successfulTasks=" + successfulTasks +
                ", failedTasks=" + failedTasks +
                ", status='" + status + '\'' +
                '}';
    }

    /**
     * Builder class for creating BatchResult instances.
     */
    public static class Builder {
        private long batchId;
        private long startTimeNanos;
        private long endTimeNanos;
        private int totalTasks = 0;
        private int successfulTasks = 0;
        private int failedTasks = 0;
        private long totalBytesProcessed = 0;
        private Map<String, Object> detailedStats = Map.of();
        private String status = "UNKNOWN";
        private String errorMessage = null;

        /**
         * Set batch ID.
         * @param batchId Unique batch identifier
         * @return Builder instance
         */
        public Builder batchId(long batchId) {
            this.batchId = batchId;
            return this;
        }

        /**
         * Set start time in nanoseconds.
         * @param startTimeNanos Start time
         * @return Builder instance
         */
        public Builder startTimeNanos(long startTimeNanos) {
            this.startTimeNanos = startTimeNanos;
            return this;
        }

        /**
         * Set end time in nanoseconds.
         * @param endTimeNanos End time
         * @return Builder instance
         */
        public Builder endTimeNanos(long endTimeNanos) {
            this.endTimeNanos = endTimeNanos;
            return this;
        }

        /**
         * Set total operations.
         * @param totalOperations Total operations
         * @return Builder instance
         */
        public Builder totalOperations(int totalOperations) {
            this.totalTasks = totalOperations;
            return this;
        }

        /**
         * Set total tasks.
         * @param totalTasks Total tasks
         * @return Builder instance
         */
        public Builder totalTasks(int totalTasks) {
            this.totalTasks = totalTasks;
            return this;
        }

        /**
         * Set successful tasks.
         * @param successfulTasks Successful tasks
         * @return Builder instance
         */
        public Builder successfulTasks(int successfulTasks) {
            this.successfulTasks = successfulTasks;
            return this;
        }

        /**
         * Set failed tasks.
         * @param failedTasks Failed tasks
         * @return Builder instance
         */
        public Builder failedTasks(int failedTasks) {
            this.failedTasks = failedTasks;
            return this;
        }

        /**
         * Set start time in milliseconds.
         * @param startTime Start time in milliseconds
         * @return Builder instance
         */
        public Builder startTime(long startTime) {
            this.startTimeNanos = startTime * 1_000_000; // Convert millis to nanos
            return this;
        }

        /**
         * Set end time in milliseconds.
         * @param endTime End time in milliseconds
         * @return Builder instance
         */
        public Builder endTime(long endTime) {
            this.endTimeNanos = endTime * 1_000_000; // Convert millis to nanos
            return this;
        }

        /**
         * Set total bytes processed.
         * @param totalBytesProcessed Total bytes
         * @return Builder instance
         */
        public Builder totalBytesProcessed(long totalBytesProcessed) {
            this.totalBytesProcessed = totalBytesProcessed;
            return this;
        }

        /**
         * Set detailed statistics.
         * @param detailedStats Statistics map
         * @return Builder instance
         */
        public Builder detailedStats(Map<String, Object> detailedStats) {
            this.detailedStats = Map.copyOf(detailedStats);
            return this;
        }

        /**
         * Set batch status.
         * @param status Status string
         * @return Builder instance
         */
        public Builder status(String status) {
            this.status = status;
            return this;
        }

        /**
         * Set batch success status.
         * @param success true for success, false for failure
         * @return Builder instance
         */
        public Builder success(boolean success) {
            this.status = success ? "COMPLETED" : "FAILED";
            return this;
        }

        /**
         * Set batch name.
         * @param batchName Batch name
         * @return Builder instance
         */
        public Builder batchName(String batchName) {
            // Store batch name in detailed stats
            if (batchName != null) {
                this.detailedStats = Map.of("batchName", batchName);
            }
            return this;
        }

        /**
         * Add operation result.
         * @param operationResult Operation result to add
         * @return Builder instance
         */
        public Builder addOperationResult(BridgeResult operationResult) {
            // Increment appropriate counters based on operation result
            if (operationResult != null) {
                if (operationResult.isSuccess()) {
                    successfulTasks++;
                } else {
                    failedTasks++;
                }
                totalTasks++;
            }
            return this;
        }

        /**
         * Set successful operations count.
         * @param successfulOperations Number of successful operations
         * @return Builder instance
         */
        public Builder successfulOperations(int successfulOperations) {
            this.successfulTasks = successfulOperations;
            return this;
        }

        /**
         * Set error message.
         * @param errorMessage Error message
         * @return Builder instance
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * Build and return a new BatchResult instance.
         * @return Immutable BatchResult
         */
        public BatchResult build() {
            return new BatchResult(
                    batchId,
                    startTimeNanos,
                    endTimeNanos,
                    totalTasks,
                    successfulTasks,
                    failedTasks,
                    totalBytesProcessed,
                    detailedStats,
                    status,
                    errorMessage
            );
        }
    }
}