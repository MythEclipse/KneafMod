package com.kneaf.core.exceptions;

/**
 * Generic exception for optimized processing operations.
 * Consolidates various processing-related exceptions into a unified exception hierarchy.
 */
public class OptimizedProcessingException extends KneafCoreException {
    
    public enum ErrorType {
        BATCH_PROCESSING_ERROR("Batch processing failed"),
        MEMORY_ALLOCATION_ERROR("Memory allocation failed"),
        NATIVE_LIBRARY_ERROR("Native library operation failed"),
        PREDICTIVE_LOADING_ERROR("Predictive loading failed"),
        BUFFER_POOL_ERROR("Buffer pool operation failed"),
        WORKER_EXECUTION_ERROR("Worker execution failed"),
        TIMEOUT_ERROR("Operation timed out"),
        RESOURCE_EXHAUSTION_ERROR("Resource exhaustion detected");
        
        private final String description;
        
        ErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final ErrorType errorType;
    private final String operation;
    private final Object context;
    
    public OptimizedProcessingException(ErrorType errorType, String operation, String message) {
        super(getErrorCategory(errorType), operation, message, null);
        this.errorType = errorType;
        this.operation = operation;
        this.context = null;
    }
    
    public OptimizedProcessingException(ErrorType errorType, String operation, String message, Throwable cause) {
        super(getErrorCategory(errorType), operation, message, null, cause);
        this.errorType = errorType;
        this.operation = operation;
        this.context = null;
    }
    
    public OptimizedProcessingException(ErrorType errorType, String operation, String message, Object context) {
        super(getErrorCategory(errorType), operation, message, context, null);
        this.errorType = errorType;
        this.operation = operation;
        this.context = context;
    }
    
    public OptimizedProcessingException(ErrorType errorType, String operation, String message, Object context, Throwable cause) {
        super(getErrorCategory(errorType), operation, message, context, cause);
        this.errorType = errorType;
        this.operation = operation;
        this.context = context;
    }
    
    /**
     * Map ErrorType to KneafCoreException.ErrorCategory
     */
    private static KneafCoreException.ErrorCategory getErrorCategory(ErrorType errorType) {
        switch (errorType) {
            case BATCH_PROCESSING_ERROR:
            case PREDICTIVE_LOADING_ERROR:
                return KneafCoreException.ErrorCategory.ASYNC_PROCESSING;
            case MEMORY_ALLOCATION_ERROR:
            case BUFFER_POOL_ERROR:
            case RESOURCE_EXHAUSTION_ERROR:
                return KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT;
            case NATIVE_LIBRARY_ERROR:
                return KneafCoreException.ErrorCategory.NATIVE_LIBRARY;
            case WORKER_EXECUTION_ERROR:
                return KneafCoreException.ErrorCategory.SYSTEM_ERROR;
            case TIMEOUT_ERROR:
                return KneafCoreException.ErrorCategory.ASYNC_PROCESSING;
            default:
                return KneafCoreException.ErrorCategory.SYSTEM_ERROR;
        }
    }
    
    public ErrorType getErrorType() {
        return errorType;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public Object getContext() {
        return context;
    }
    
    /**
     * Factory methods for common exception scenarios
     */
    public static OptimizedProcessingException batchProcessingError(String operation, String message) {
        return new OptimizedProcessingException(ErrorType.BATCH_PROCESSING_ERROR, operation, message);
    }
    
    public static OptimizedProcessingException batchProcessingError(String operation, String message, Throwable cause) {
        return new OptimizedProcessingException(ErrorType.BATCH_PROCESSING_ERROR, operation, message, cause);
    }
    
    public static OptimizedProcessingException memoryAllocationError(String operation, String message, Object context) {
        return new OptimizedProcessingException(ErrorType.MEMORY_ALLOCATION_ERROR, operation, message, context);
    }
    
    public static OptimizedProcessingException nativeLibraryError(String operation, String message, Throwable cause) {
        return new OptimizedProcessingException(ErrorType.NATIVE_LIBRARY_ERROR, operation, message, cause);
    }
    
    public static OptimizedProcessingException predictiveLoadingError(String operation, String message, Object context) {
        return new OptimizedProcessingException(ErrorType.PREDICTIVE_LOADING_ERROR, operation, message, context);
    }
    
    public static OptimizedProcessingException bufferPoolError(String operation, String message) {
        return new OptimizedProcessingException(ErrorType.BUFFER_POOL_ERROR, operation, message);
    }
    
    public static OptimizedProcessingException workerExecutionError(String operation, String message, Throwable cause) {
        return new OptimizedProcessingException(ErrorType.WORKER_EXECUTION_ERROR, operation, message, cause);
    }
    
    public static OptimizedProcessingException timeoutError(String operation, String message, long timeoutMs) {
        return new OptimizedProcessingException(ErrorType.TIMEOUT_ERROR, operation, 
            String.format("%s (timeout: %d ms)", message, timeoutMs));
    }
    
    public static OptimizedProcessingException resourceExhaustionError(String operation, String resourceType, Object context) {
        return new OptimizedProcessingException(ErrorType.RESOURCE_EXHAUSTION_ERROR, operation, 
            String.format("Resource exhaustion: %s", resourceType), context);
    }
}