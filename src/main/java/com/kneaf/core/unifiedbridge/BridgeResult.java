package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable result structure for individual bridge operations.
 * Contains comprehensive information about a single operation result.
 */
public final class BridgeResult {
    private final long taskId;
    private final long startTimeNanos;
    private final long endTimeNanos;
    private final byte[] resultData;
    private final Map<String, Object> metadata;
    private final String status;
    private final String errorMessage;

    /**
     * Create a new BridgeResult instance.
     * @param taskId Unique identifier for the task
     * @param startTimeNanos Start time in nanoseconds
     * @param endTimeNanos End time in nanoseconds
     * @param resultData Operation result data
     * @param metadata Additional metadata about the operation
     * @param status Operation status (e.g., "SUCCESS", "FAILURE", "PENDING")
     * @param errorMessage Error message if operation failed
     */
    public BridgeResult(long taskId, long startTimeNanos, long endTimeNanos,
                       byte[] resultData, Map<String, Object> metadata,
                       String status, String errorMessage) {
        this.taskId = taskId;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.resultData = resultData != null ? resultData.clone() : null;
        this.metadata = Map.copyOf(Objects.requireNonNull(metadata));
        this.status = Objects.requireNonNull(status);
        this.errorMessage = errorMessage;
    }

    /**
     * Get unique task identifier.
     * @return Task ID
     */
    public long getTaskId() {
        return taskId;
    }

    /**
     * Get operation start time in nanoseconds.
     * @return Start time in nanoseconds
     */
    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    /**
     * Get operation end time in nanoseconds.
     * @return End time in nanoseconds
     */
    public long getEndTimeNanos() {
        return endTimeNanos;
    }

    /**
     * Get operation duration in nanoseconds.
     * @return Duration in nanoseconds
     */
    public long getDurationNanos() {
        return endTimeNanos - startTimeNanos;
    }

    /**
     * Get operation duration in milliseconds.
     * @return Duration in milliseconds
     */
    public long getDurationMillis() {
        return TimeUnit.NANOSECONDS.toMillis(getDurationNanos());
    }

    /**
     * Get operation result data.
     * @return Result data (clone to prevent modification of internal state)
     */
    public byte[] getResultData() {
        return resultData != null ? resultData.clone() : null;
    }

    /**
     * Get operation metadata.
     * @return Immutable map of metadata
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Get operation status.
     * @return Status string (e.g., "SUCCESS", "FAILURE", "PENDING")
     */
    public String getStatus() {
        return status;
    }

    /**
     * Get error message if operation failed.
     * @return Error message or null if no error
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Check if operation was successful.
     * @return true if operation succeeded, false otherwise
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    /**
     * Check if operation failed.
     * @return true if operation failed, false otherwise
     */
    public boolean isFailure() {
        return "FAILURE".equals(status);
    }

    /**
     * Check if operation is still pending.
     * @return true if operation is pending, false otherwise
     */
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /**
     * Get result as String.
     * @return String representation of result data
     */
    public String getResultString() {
        if (resultData == null) {
            return null;
        }
        return new String(resultData);
    }

    /**
     * Get result as Integer.
     * @return Integer value from result data
     */
    public Integer getResultInteger() {
        if (resultData == null) {
            return null;
        }
        try {
            return Integer.parseInt(new String(resultData).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get result as Long.
     * @return Long value from result data
     */
    public Long getResultLong() {
        if (resultData == null) {
            return null;
        }
        try {
            return Long.parseLong(new String(resultData).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get result as Double.
     * @return Double value from result data
     */
    public Double getResultDouble() {
        if (resultData == null) {
            return null;
        }
        try {
            return Double.parseDouble(new String(resultData).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get result as Boolean.
     * @return Boolean value from result data
     */
    public Boolean getResultBoolean() {
        if (resultData == null) {
            return null;
        }
        String str = new String(resultData).trim().toLowerCase();
        return "true".equals(str) || "1".equals(str);
    }

    /**
     * Get result as Object.
     * @return Object representation of result data
     */
    public Object getResultObject() {
        if (resultData == null) {
            return null;
        }
        return new String(resultData);
    }

    /**
     * Get result as ByteBuffer.
     * @return ByteBuffer representation of result data
     */
    public java.nio.ByteBuffer getResultByteBuffer() {
        if (resultData == null) {
            return null;
        }
        return java.nio.ByteBuffer.wrap(resultData.clone());
    }

    /**
     * Get message from metadata.
     * @return Message or null if not found
     */
    public String getMessage() {
        Object message = metadata.get("message");
        return message != null ? message.toString() : null;
    }

    @Override
    public String toString() {
        return "BridgeResult{" +
                "taskId=" + taskId +
                ", durationMillis=" + getDurationMillis() +
                ", status='" + status + '\'' +
                ", resultSize=" + (resultData != null ? resultData.length : 0) +
                '}';
    }

    /**
     * Builder class for creating BridgeResult instances.
     */
    public static class Builder {
        private long taskId;
        private long startTimeNanos;
        private long endTimeNanos;
        private byte[] resultData;
        private Map<String, Object> metadata = Map.of();
        private String status = "PENDING";
        private String errorMessage = null;

        /**
         * Set task ID.
         * @param taskId Unique task identifier
         * @return Builder instance
         */
        public Builder taskId(long taskId) {
            this.taskId = taskId;
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
         * Set result data.
         * @param resultData Operation result data
         * @return Builder instance
         */
        public Builder resultData(byte[] resultData) {
            this.resultData = resultData != null ? resultData.clone() : null;
            return this;
        }

        /**
         * Set result data from Object.
         * @param resultObject Object to convert to result data
         * @return Builder instance
         */
        public Builder resultObject(Object resultObject) {
            if (resultObject != null) {
                this.resultData = resultObject.toString().getBytes();
            }
            return this;
        }

        /**
         * Set metadata.
         * @param metadata Operation metadata
         * @return Builder instance
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
            return this;
        }

        /**
         * Set message in metadata.
         * @param message Message to store
         * @return Builder instance
         */
        public Builder message(String message) {
            if (message != null) {
                this.metadata = Map.of("message", message);
            }
            return this;
        }

        /**
         * Set operation status.
         * @param status Status string
         * @return Builder instance
         */
        public Builder status(String status) {
            this.status = status;
            return this;
        }

        /**
         * Set operation name.
         * @param operationName Operation name
         * @return Builder instance
         */
        public Builder operationName(String operationName) {
            // Store operation name in metadata
            if (operationName != null) {
                this.metadata = Map.of("operationName", operationName);
            }
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
         * Mark operation as successful.
         * @return Builder instance
         */
        public Builder success() {
            return status("SUCCESS");
        }

        /**
         * Mark operation as successful with boolean parameter.
         * @param success true for success, false for failure
         * @return Builder instance
         */
        public Builder success(boolean success) {
            return status(success ? "SUCCESS" : "FAILURE");
        }

        /**
         * Mark operation as failed with error message.
         * @param errorMessage Error message
         * @return Builder instance
         */
        public Builder failure(String errorMessage) {
            return status("FAILURE").errorMessage(errorMessage);
        }

        /**
         * Build and return a new BridgeResult instance.
         * @return Immutable BridgeResult
         */
        public BridgeResult build() {
            return new BridgeResult(
                    taskId,
                    startTimeNanos,
                    endTimeNanos,
                    resultData,
                    metadata,
                    status,
                    errorMessage
            );
        }
    }
}