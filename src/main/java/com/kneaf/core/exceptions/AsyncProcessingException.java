package com.kneaf.core.exceptions;

/**
 * Exception thrown for async processing operation failures.
 * Replaces BatchRequestInterruptedException and handles async processing specific errors.
 */
public class AsyncProcessingException extends KneafCoreException {
    
    public enum AsyncErrorType {
        BATCH_REQUEST_INTERRUPTED("Batch request interrupted"),
        TIMEOUT_EXCEEDED("Async operation timeout exceeded"),
        EXECUTOR_SHUTDOWN("Async executor shutdown"),
        TASK_REJECTED("Async task rejected"),
        COMPLETION_EXCEPTION("Async completion exception"),
        SUPPLY_ASYNC_FAILED("Supply async operation failed");
        
        private final String description;
        
        AsyncErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final AsyncErrorType errorType;
    private final String taskType;
    private final long timeoutMs;
    
    public AsyncProcessingException(AsyncErrorType errorType, String message) {
        super(ErrorCategory.ASYNC_PROCESSING, message);
        this.errorType = errorType;
        this.taskType = null;
        this.timeoutMs = -1;
    }
    
    public AsyncProcessingException(AsyncErrorType errorType, String message, Throwable cause) {
        super(ErrorCategory.ASYNC_PROCESSING, message, cause);
        this.errorType = errorType;
        this.taskType = null;
        this.timeoutMs = -1;
    }
    
    public AsyncProcessingException(AsyncErrorType errorType, String taskType, String message, Throwable cause) {
        super(ErrorCategory.ASYNC_PROCESSING, taskType, message, 
              String.format("Task: %s", taskType), cause);
        this.errorType = errorType;
        this.taskType = taskType;
        this.timeoutMs = -1;
    }
    
    public AsyncProcessingException(AsyncErrorType errorType, String taskType, long timeoutMs, String message, Throwable cause) {
        super(ErrorCategory.ASYNC_PROCESSING, taskType, message, 
              String.format("Task: %s, Timeout: %dms", taskType, timeoutMs), cause);
        this.errorType = errorType;
        this.taskType = taskType;
        this.timeoutMs = timeoutMs;
    }
    
    public AsyncErrorType getErrorType() {
        return errorType;
    }
    
    public String getTaskType() {
        return taskType;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    /**
     * Creates an async processing exception for batch request interruptions.
     */
    public static AsyncProcessingException batchRequestInterrupted(String taskType, Throwable cause) {
        return new AsyncProcessingException(AsyncErrorType.BATCH_REQUEST_INTERRUPTED, taskType, 
                                           String.format("Batch request for '%s' was interrupted", taskType), cause);
    }
    
    /**
     * Creates an async processing exception for timeout exceeded.
     */
    public static AsyncProcessingException timeoutExceeded(String taskType, long timeoutMs, Throwable cause) {
        return new AsyncProcessingException(AsyncErrorType.TIMEOUT_EXCEEDED, taskType, timeoutMs,
                                           String.format("Async operation exceeded timeout of %dms", timeoutMs), cause);
    }
    
    /**
     * Creates an async processing exception for supplyAsync operations.
     */
    public static AsyncProcessingException supplyAsyncFailed(String operation, String key, Throwable cause) {
        return new AsyncProcessingException(AsyncErrorType.SUPPLY_ASYNC_FAILED, operation,
                                           String.format("Supply async operation failed for %s: %s", operation, key), cause);
    }
}