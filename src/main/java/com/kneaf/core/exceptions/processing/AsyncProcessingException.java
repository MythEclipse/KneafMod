package com.kneaf.core.exceptions.processing;

import com.kneaf.core.exceptions.core.BaseKneafException;
import com.kneaf.core.exceptions.core.KneafCoreException;
import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionContext;
import com.kneaf.core.exceptions.utils.ExceptionConstants;

/**
 * Exception thrown for async processing operation failures.
 * Replaces BatchRequestInterruptedException and handles async processing specific errors.
 */
public class AsyncProcessingException extends BaseKneafException {
    
    public enum AsyncErrorType {
        BATCH_REQUEST_INTERRUPTED(ExceptionConstants.CODE_ASYNC_INTERRUPTED, "Batch request interrupted"),
        TIMEOUT_EXCEEDED(ExceptionConstants.CODE_ASYNC_TIMEOUT, "Async operation timeout exceeded"),
        EXECUTOR_SHUTDOWN("ASYNC004", "Async executor shutdown"),
        TASK_REJECTED("ASYNC005", "Async task rejected"),
        COMPLETION_EXCEPTION("ASYNC006", "Async completion exception"),
        SUPPLY_ASYNC_FAILED("ASYNC007", "Supply async operation failed");
        
        private final String errorCode;
        private final String description;
        
        AsyncErrorType(String errorCode, String description) {
            this.errorCode = errorCode;
            this.description = description;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final AsyncErrorType errorType;
    private final String taskType;
    private final long timeoutMs;
    
    private AsyncProcessingException(Builder builder) {
        super(builder);
        this.errorType = builder.errorType;
        this.taskType = builder.taskType;
        this.timeoutMs = builder.timeoutMs;
    }
    
    /**
     * Gets the async error type
     */
    public AsyncErrorType getErrorType() {
        return errorType;
    }
    
    /**
     * Gets the task type
     */
    public String getTaskType() {
        return taskType;
    }
    
    /**
     * Gets the timeout in milliseconds
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    @Override
    public AsyncProcessingException withContext(ExceptionContext context) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(context)
            .suggestion(getSuggestion())
            .logged(isLogged())
            .errorType(errorType)
            .taskType(taskType)
            .timeoutMs(timeoutMs)
            .build();
    }
    
    @Override
    public AsyncProcessingException withSuggestion(String suggestion) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(getContext())
            .suggestion(suggestion)
            .logged(isLogged())
            .errorType(errorType)
            .taskType(taskType)
            .timeoutMs(timeoutMs)
            .build();
    }
    
    /**
     * Creates a builder for AsyncProcessingException
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates an async processing exception for batch request interruptions.
     */
    public static AsyncProcessingException batchRequestInterrupted(String taskType, Throwable cause) {
        return builder()
            .errorType(AsyncErrorType.BATCH_REQUEST_INTERRUPTED)
            .taskType(taskType)
            .message(String.format("Batch request for '%s' was interrupted", taskType))
            .cause(cause)
            .severity(ExceptionSeverity.WARNING)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    /**
     * Creates an async processing exception for timeout exceeded.
     */
    public static AsyncProcessingException timeoutExceeded(String taskType, long timeoutMs, Throwable cause) {
        return builder()
            .errorType(AsyncErrorType.TIMEOUT_EXCEEDED)
            .taskType(taskType)
            .timeoutMs(timeoutMs)
            .message(String.format("Async operation exceeded timeout of %dms", timeoutMs))
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    /**
     * Creates an async processing exception for supplyAsync operations.
     */
    public static AsyncProcessingException supplyAsyncFailed(String operation, String key, Throwable cause) {
        return builder()
            .errorType(AsyncErrorType.SUPPLY_ASYNC_FAILED)
            .taskType(operation)
            .message(String.format("Supply async operation failed for %s: %s", operation, key))
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    /**
     * Builder class for AsyncProcessingException
     */
    public static class Builder extends BaseKneafException.Builder<Builder> {
        private AsyncErrorType errorType;
        private String taskType;
        private long timeoutMs = -1;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder errorType(AsyncErrorType errorType) {
            this.errorType = errorType;
            return this;
        }
        
        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }
        
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        
        @Override
        protected void validate() {
            super.validate();
            if (errorType == null) {
                throw new IllegalArgumentException("Error type cannot be null");
            }
        }
        
        public AsyncProcessingException build() {
            validate();
            
            // Auto-generate error code if not provided
            if (errorCode == null && errorType != null) {
                errorCode = errorType.getErrorCode();
            }
            
            // Auto-set severity if not provided
            if (severity == null) {
                severity = ExceptionSeverity.ERROR;
            }
            
            return new AsyncProcessingException(this);
        }
    }
}