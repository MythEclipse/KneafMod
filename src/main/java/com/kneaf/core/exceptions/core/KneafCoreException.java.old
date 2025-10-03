package com.kneaf.core.exceptions;

/**
 * Base exception for all KneafCore operations.
 * Provides a unified exception hierarchy for better error handling and logging.
 */
public class KneafCoreException extends RuntimeException {
    
    /**
     * Error categories for better error classification and handling.
     */
    public enum ErrorCategory {
        DATABASE_OPERATION("Database operation failed"),
        NATIVE_LIBRARY("Native library error"),
        ASYNC_PROCESSING("Async processing error"),
        CONFIGURATION("Configuration error"),
        RESOURCE_MANAGEMENT("Resource management error"),
        PROTOCOL_ERROR("Protocol error"),
        VALIDATION_ERROR("Validation error"),
        SYSTEM_ERROR("System error");
        
        private final String description;
        
        ErrorCategory(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final ErrorCategory category;
    private final String operation;
    private final Object context;
    
    public KneafCoreException(ErrorCategory category, String message) {
        super(message);
        this.category = category;
        this.operation = null;
        this.context = null;
    }
    
    public KneafCoreException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.operation = null;
        this.context = null;
    }
    
    public KneafCoreException(ErrorCategory category, String operation, String message, Throwable cause) {
        super(formatMessage(category, operation, message), cause);
        this.category = category;
        this.operation = operation;
        this.context = null;
    }
    
    public KneafCoreException(ErrorCategory category, String operation, String message, Object context, Throwable cause) {
        super(formatMessage(category, operation, message), cause);
        this.category = category;
        this.operation = operation;
        this.context = context;
    }
    
    public ErrorCategory getCategory() {
        return category;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public Object getContext() {
        return context;
    }
    
    private static String formatMessage(ErrorCategory category, String operation, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(category.name()).append("] ");
        if (operation != null) {
            sb.append("Operation '").append(operation).append("': ");
        }
        sb.append(message);
        return sb.toString();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append(": ");
        sb.append(getMessage());
        if (operation != null) {
            sb.append(" [Operation: ").append(operation).append("]");
        }
        if (context != null) {
            sb.append(" [Context: ").append(context).append("]");
        }
        if (getCause() != null) {
            sb.append(" [Cause: ").append(getCause().getMessage()).append("]");
        }
        return sb.toString();
    }
}