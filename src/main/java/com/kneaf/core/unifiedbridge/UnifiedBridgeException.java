package com.kneaf.core.unifiedbridge;

import java.util.Objects;

/**
 * Unified exception type for all bridge-related operations, combining BridgeException and RustBridgeException patterns.
 * Follows Rust's error handling patterns with typed error categories.
 */
public class UnifiedBridgeException extends RuntimeException {
    private final ErrorCategory category;
    private final ErrorCode code;
    private final String context;

    /**
     * Create a new UnifiedBridgeException.
     * @param message Error message
     * @param category Error category
     * @param code Specific error code
     */
    public UnifiedBridgeException(String message, ErrorCategory category, ErrorCode code) {
        super(message);
        this.category = Objects.requireNonNull(category);
        this.code = Objects.requireNonNull(code);
        this.context = null;
    }

    /**
     * Create a new UnifiedBridgeException with context.
     * @param message Error message
     * @param category Error category
     * @param code Specific error code
     * @param context Additional context information
     */
    public UnifiedBridgeException(String message, ErrorCategory category, ErrorCode code, String context) {
        super(message);
        this.category = Objects.requireNonNull(category);
        this.code = Objects.requireNonNull(code);
        this.context = context;
    }

    /**
     * Create a new UnifiedBridgeException with cause.
     * @param message Error message
     * @param category Error category
     * @param code Specific error code
     * @param cause Root cause of the error
     */
    public UnifiedBridgeException(String message, ErrorCategory category, ErrorCode code, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category);
        this.code = Objects.requireNonNull(code);
        this.context = null;
    }

    /**
     * Create a new UnifiedBridgeException with cause and context.
     * @param message Error message
     * @param category Error category
     * @param code Specific error code
     * @param cause Root cause of the error
     * @param context Additional context information
     */
    public UnifiedBridgeException(String message, ErrorCategory category, ErrorCode code, Throwable cause, String context) {
        super(message, cause);
        this.category = Objects.requireNonNull(category);
        this.code = Objects.requireNonNull(code);
        this.context = context;
    }

    /**
     * Get the error category.
     * @return Error category
     */
    public ErrorCategory getCategory() {
        return category;
    }

    /**
     * Get the specific error code.
     * @return Error code
     */
    public ErrorCode getCode() {
        return code;
    }

    /**
     * Get additional context information.
     * @return Context information or null if none
     */
    public String getContext() {
        return context;
    }

    /**
     * Check if this exception belongs to a specific category.
     * @param category Category to check
     * @return true if exception belongs to the category, false otherwise
     */
    public boolean isCategory(ErrorCategory category) {
        return this.category == category;
    }

    /**
     * Check if this exception has a specific error code.
     * @param code Code to check
     * @return true if exception has the code, false otherwise
     */
    public boolean isCode(ErrorCode code) {
        return this.code == code;
    }

    /**
     * Create a builder for UnifiedBridgeException.
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating UnifiedBridgeException instances.
     */
    public static class Builder {
        private String message;
        private ErrorCategory category;
        private ErrorCode code;
        private Throwable cause;
        private String context;

        /**
         * Set the error message.
         * @param message Error message
         * @return Builder instance
         */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /**
         * Set the error category.
         * @param category Error category
         * @return Builder instance
         */
        public Builder category(ErrorCategory category) {
            this.category = category;
            return this;
        }

        /**
         * Set the error code.
         * @param code Error code
         * @return Builder instance
         */
        public Builder code(ErrorCode code) {
            this.code = code;
            return this;
        }

        /**
         * Set the root cause.
         * @param cause Root cause
         * @return Builder instance
         */
        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        /**
         * Set additional context.
         * @param context Context information
         * @return Builder instance
         */
        public Builder context(String context) {
            this.context = context;
            return this;
        }

        /**
         * Build the exception.
         * @return UnifiedBridgeException instance
         */
        public UnifiedBridgeException build() {
            Objects.requireNonNull(message, "Message cannot be null");
            Objects.requireNonNull(category, "Category cannot be null");
            Objects.requireNonNull(code, "Code cannot be null");
            
            if (cause != null) {
                if (context != null) {
                    return new UnifiedBridgeException(message, category, code, cause, context);
                } else {
                    return new UnifiedBridgeException(message, category, code, cause);
                }
            } else {
                if (context != null) {
                    return new UnifiedBridgeException(message, category, code, context);
                } else {
                    return new UnifiedBridgeException(message, category, code);
                }
            }
        }
    }

    /**
     * Error categories for bridge exceptions.
     */
    public enum ErrorCategory {
        /** Generic error category */
        GENERIC,
        
        /** Native JNI call errors */
        NATIVE_CALL,
        
        /** Worker management errors */
        WORKER_MANAGEMENT,
        
        /** Task processing errors */
        TASK_PROCESSING,
        
        /** Buffer management errors */
        BUFFER_MANAGEMENT,
        
        /** Batch operation errors */
        BATCH_OPERATION,
        
        /** Result polling errors */
        RESULT_POLLING,
        
        /** Configuration errors */
        CONFIGURATION,
        
        /** Memory management errors */
        MEMORY_MANAGEMENT,
        
        /** Parallel execution errors */
        PARALLEL_EXECUTION,
        
        /** Compatibility layer errors */
        COMPATIBILITY,
        
        /** Shutdown errors */
        SHUTDOWN,
        
        /** Performance monitoring errors */
        PERFORMANCE,
        
        /** Resource management errors */
        RESOURCE_MANAGEMENT
    }

    /**
     * Specific error codes for bridge exceptions.
     */
    public enum ErrorCode {
        /** Generic error */
        GENERIC_ERROR,
        
        /** Native method not found */
        NATIVE_METHOD_NOT_FOUND,
        
        /** JNI call failed */
        JNI_CALL_FAILED,
        
        /** Native library not loaded */
        NATIVE_LIBRARY_NOT_LOADED,
        
        /** Worker creation failed */
        WORKER_CREATION_FAILED,
        
        /** Worker destruction failed */
        WORKER_DESTROY_FAILED,
        
        /** Task push failed */
        TASK_PUSH_FAILED,
        
        /** Task polling failed */
        TASK_POLLING_FAILED,
        
        /** Buffer allocation failed */
        BUFFER_ALLOCATION_FAILED,
        
        /** Buffer access failed */
        BUFFER_ACCESS_FAILED,
        
        /** Buffer free failed */
        BUFFER_FREE_FAILED,
        
        /** Batch processing failed */
        BATCH_PROCESSING_FAILED,
        
        /** Result polling failed */
        RESULT_POLLING_FAILED,
        
        /** Configuration invalid */
        CONFIGURATION_INVALID,
        
        /** Configuration missing */
        CONFIGURATION_MISSING,
        
        /** Memory allocation failed */
        MEMORY_ALLOCATION_FAILED,
        
        /** Memory pressure exceeded */
        MEMORY_PRESSURE_EXCEEDED,
        
        /** Execution failed */
        EXECUTION_FAILED,
        
        /** Operation timed out */
        OPERATION_TIMED_OUT,
        
        /** Invalid parameters */
        INVALID_PARAMETERS,
        
        /** Operation not supported */
        OPERATION_NOT_SUPPORTED,
        
        /** Resource not found */
        RESOURCE_NOT_FOUND,
        
        /** Resource already exists */
        RESOURCE_ALREADY_EXISTS,
        
        /** Resource access denied */
        RESOURCE_ACCESS_DENIED,
        
        /** Shutdown failed */
        SHUTDOWN_FAILED,
        
        /** Compatibility layer error */
        COMPATIBILITY_ERROR,
        
        /** Conversion failed */
        CONVERSION_FAILED,
        
        /** Thread pool error */
        THREAD_POOL_ERROR,
        
        /** Parallel execution error */
        PARALLEL_EXECUTION_ERROR,
        
        /** Performance monitoring error */
        PERFORMANCE_MONITORING_ERROR,
        
        /** Zero-copy operation failed */
        ZERO_COPY_FAILED,
        
        /** Native bridge not available */
        NATIVE_BRIDGE_UNAVAILABLE
    }
}