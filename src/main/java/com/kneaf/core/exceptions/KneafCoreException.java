package com.kneaf.core.exceptions;

import com.kneaf.core.exceptions.utils.ExceptionContext;

/**
 * Legacy compatibility wrapper for KneafCoreException.
 * Extends the new enhanced exception class to maintain backward compatibility.
 * 
 * @deprecated Use com.kneaf.core.exceptions.core.KneafCoreException instead
 */
@Deprecated
public class KneafCoreException extends RuntimeException {
    
    private final com.kneaf.core.exceptions.core.KneafCoreException delegate;
    
    public KneafCoreException(ErrorCategory category, String message) {
        super(message);
        this.delegate = com.kneaf.core.exceptions.core.KneafCoreException.builder()
            .category(convertCategory(category))
            .message(message)
            .build();
    }
    
    public KneafCoreException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.core.KneafCoreException.builder()
            .category(convertCategory(category))
            .message(message)
            .cause(cause)
            .build();
    }
    
    public KneafCoreException(ErrorCategory category, String operation, String message, Throwable cause) {
        super(formatMessage(category, operation, message), cause);
        this.delegate = com.kneaf.core.exceptions.core.KneafCoreException.builder()
            .category(convertCategory(category))
            .operation(operation)
            .message(message)
            .cause(cause)
            .build();
    }
    
    public KneafCoreException(ErrorCategory category, String operation, String message, Object context, Throwable cause) {
        super(formatMessage(category, operation, message), cause);
        this.delegate = com.kneaf.core.exceptions.core.KneafCoreException.builder()
            .category(convertCategory(category))
            .operation(operation)
            .message(message)
            .context(context != null ? ExceptionContext.basic(operation, category.name()) : null)
            .cause(cause)
            .build();
    }
    
    /**
     * Gets the error category
     */
    public ErrorCategory getCategory() {
        return convertCategoryBack(delegate.getCategory());
    }
    
    /**
     * Gets the operation name
     */
    public String getOperation() {
        return delegate.getOperation();
    }
    
    /**
     * Gets the context object
     */
    public Object getContext() {
        ExceptionContext context = delegate.getContext();
        return context != null ? context.getContextData() : null;
    }
    
    /**
     * Gets the delegate exception for access to new functionality
     */
    public com.kneaf.core.exceptions.core.KneafCoreException getDelegate() {
        return delegate;
    }
    
    private static com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory convertCategory(ErrorCategory category) {
        if (category == null) return null;
        
        switch (category) {
            case DATABASE_OPERATION:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.DATABASE_OPERATION;
            case NATIVE_LIBRARY:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.NATIVE_LIBRARY;
            case ASYNC_PROCESSING:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.ASYNC_PROCESSING;
            case CONFIGURATION:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.CONFIGURATION;
            case RESOURCE_MANAGEMENT:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT;
            case PROTOCOL_ERROR:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.PROTOCOL_ERROR;
            case VALIDATION_ERROR:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.VALIDATION_ERROR;
            case SYSTEM_ERROR:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.SYSTEM_ERROR;
            default:
                return com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory.SYSTEM_ERROR;
        }
    }
    
    private static ErrorCategory convertCategoryBack(com.kneaf.core.exceptions.core.KneafCoreException.ErrorCategory category) {
        if (category == null) return null;
        
        switch (category) {
            case DATABASE_OPERATION:
                return ErrorCategory.DATABASE_OPERATION;
            case NATIVE_LIBRARY:
                return ErrorCategory.NATIVE_LIBRARY;
            case ASYNC_PROCESSING:
                return ErrorCategory.ASYNC_PROCESSING;
            case CONFIGURATION:
                return ErrorCategory.CONFIGURATION;
            case RESOURCE_MANAGEMENT:
                return ErrorCategory.RESOURCE_MANAGEMENT;
            case PROTOCOL_ERROR:
                return ErrorCategory.PROTOCOL_ERROR;
            case VALIDATION_ERROR:
                return ErrorCategory.VALIDATION_ERROR;
            case SYSTEM_ERROR:
                return ErrorCategory.SYSTEM_ERROR;
            default:
                return ErrorCategory.SYSTEM_ERROR;
        }
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
}