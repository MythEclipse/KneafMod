package com.kneaf.core.exceptions.processing;

import com.kneaf.core.exceptions.core.BaseKneafException;
import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionContext;
import com.kneaf.core.exceptions.utils.ExceptionConstants;

/**
 * Exception for optimized processing operations.
 * Consolidates various processing-related exceptions into a unified exception hierarchy.
 */
public class OptimizedProcessingException extends BaseKneafException {
    
    public enum ProcessingErrorType {
        BATCH_PROCESSING_ERROR(ExceptionConstants.CODE_PROCESSING_BATCH_FAILED, "Batch processing failed"),
        MEMORY_ALLOCATION_ERROR(ExceptionConstants.CODE_PROCESSING_MEMORY_FAILED, "Memory allocation failed"),
        NATIVE_LIBRARY_ERROR(ExceptionConstants.CODE_PREFIX_NATIVE + "005", "Native library operation failed"),
        PREDICTIVE_LOADING_ERROR("PROC005", "Predictive loading failed"),
        BUFFER_POOL_ERROR("PROC006", "Buffer pool operation failed"),
        WORKER_EXECUTION_ERROR("PROC007", "Worker execution failed"),
        TIMEOUT_ERROR(ExceptionConstants.CODE_PROCESSING_TIMEOUT, "Operation timed out"),
        RESOURCE_EXHAUSTION_ERROR(ExceptionConstants.CODE_PROCESSING_RESOURCE_EXHAUSTION, "Resource exhaustion detected");
        
        private final String errorCode;
        private final String description;
        
        ProcessingErrorType(String errorCode, String description) {
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
    
    private final ProcessingErrorType processingErrorType;
    private final String operation;
    private final Object processingContext;
    
    private OptimizedProcessingException(Builder builder) {
        super(builder);
        this.processingErrorType = builder.processingErrorType;
        this.operation = builder.operation;
        this.processingContext = builder.processingContext;
    }
    
    /**
     * Gets the processing error type
     */
    public ProcessingErrorType getProcessingErrorType() {
        return processingErrorType;
    }
    
    /**
     * Gets the operation name
     */
    public String getOperation() {
        return operation;
    }
    
    /**
     * Gets the processing context
     */
    public Object getProcessingContext() {
        return processingContext;
    }
    
    @Override
    public OptimizedProcessingException withContext(ExceptionContext context) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(context)
            .suggestion(getSuggestion())
            .logged(isLogged())
            .processingErrorType(processingErrorType)
            .operation(operation)
            .processingContext(processingContext)
            .build();
    }
    
    @Override
    public OptimizedProcessingException withSuggestion(String suggestion) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(getContext())
            .suggestion(suggestion)
            .logged(isLogged())
            .processingErrorType(processingErrorType)
            .operation(operation)
            .processingContext(processingContext)
            .build();
    }
    
    /**
     * Creates a builder for OptimizedProcessingException
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Factory methods for common exception scenarios
     */
    public static OptimizedProcessingException batchProcessingError(String operation, String message) {
        return builder()
            .processingErrorType(ProcessingErrorType.BATCH_PROCESSING_ERROR)
            .operation(operation)
            .message(message)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    public static OptimizedProcessingException batchProcessingError(String operation, String message, Throwable cause) {
        return builder()
            .processingErrorType(ProcessingErrorType.BATCH_PROCESSING_ERROR)
            .operation(operation)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    public static OptimizedProcessingException memoryAllocationError(String operation, String message, Object context) {
        return builder()
            .processingErrorType(ProcessingErrorType.MEMORY_ALLOCATION_ERROR)
            .operation(operation)
            .message(message)
            .processingContext(context)
            .severity(ExceptionSeverity.CRITICAL)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    public static OptimizedProcessingException nativeLibraryError(String operation, String message, Throwable cause) {
        return builder()
            .processingErrorType(ProcessingErrorType.NATIVE_LIBRARY_ERROR)
            .operation(operation)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    public static OptimizedProcessingException predictiveLoadingError(String operation, String message, Object context) {
        return builder()
            .processingErrorType(ProcessingErrorType.PREDICTIVE_LOADING_ERROR)
            .operation(operation)
            .message(message)
            .processingContext(context)
            .severity(ExceptionSeverity.WARNING)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    public static OptimizedProcessingException bufferPoolError(String operation, String message) {
        return builder()
            .processingErrorType(ProcessingErrorType.BUFFER_POOL_ERROR)
            .operation(operation)
            .message(message)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    public static OptimizedProcessingException workerExecutionError(String operation, String message, Throwable cause) {
        return builder()
            .processingErrorType(ProcessingErrorType.WORKER_EXECUTION_ERROR)
            .operation(operation)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    public static OptimizedProcessingException timeoutError(String operation, String message, long timeoutMs) {
        return builder()
            .processingErrorType(ProcessingErrorType.TIMEOUT_ERROR)
            .operation(operation)
            .message(String.format("%s (timeout: %d ms)", message, timeoutMs))
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    public static OptimizedProcessingException resourceExhaustionError(String operation, String resourceType, Object context) {
        return builder()
            .processingErrorType(ProcessingErrorType.RESOURCE_EXHAUSTION_ERROR)
            .operation(operation)
            .message(String.format("Resource exhaustion: %s", resourceType))
            .processingContext(context)
            .severity(ExceptionSeverity.CRITICAL)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_RESOURCES)
            .build();
    }
    
    /**
     * Builder class for OptimizedProcessingException
     */
    public static class Builder extends BaseKneafException.Builder<Builder> {
        private ProcessingErrorType processingErrorType;
        private String operation;
        private Object processingContext;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder processingErrorType(ProcessingErrorType processingErrorType) {
            this.processingErrorType = processingErrorType;
            return this;
        }
        
        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }
        
        public Builder processingContext(Object processingContext) {
            this.processingContext = processingContext;
            return this;
        }
        
        @Override
        protected void validate() {
            super.validate();
            if (processingErrorType == null) {
                throw new IllegalArgumentException("Processing error type cannot be null");
            }
        }
        
        public OptimizedProcessingException build() {
            validate();
            
            // Auto-generate error code if not provided
            if (errorCode == null && processingErrorType != null) {
                errorCode = processingErrorType.getErrorCode();
            }
            
            // Auto-set severity if not provided
            if (severity == null) {
                severity = ExceptionSeverity.ERROR;
            }
            
            return new OptimizedProcessingException(this);
        }
    }
}