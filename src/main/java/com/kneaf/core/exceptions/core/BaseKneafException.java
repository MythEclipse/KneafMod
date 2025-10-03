package com.kneaf.core.exceptions.core;

import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionContext;
import com.kneaf.core.exceptions.utils.ExceptionConstants;

/**
 * Abstract base class for all Kneaf exceptions.
 * Provides common functionality for error codes, severity, context, and suggestions.
 */
public abstract class BaseKneafException extends RuntimeException {
    
    private final String errorCode;
    private final ExceptionSeverity severity;
    private final ExceptionContext context;
    private final String suggestion;
    private final boolean logged;
    
    protected BaseKneafException(Builder<?> builder) {
        super(builder.message, builder.cause);
        this.errorCode = builder.errorCode;
        this.severity = builder.severity;
        this.context = builder.context;
        this.suggestion = builder.suggestion;
        this.logged = builder.logged;
    }
    
    /**
     * Gets the error code for this exception
     */
    public String getErrorCode() {
        return errorCode;
    }
    
    /**
     * Gets the severity level for this exception
     */
    public ExceptionSeverity getSeverity() {
        return severity;
    }
    
    /**
     * Gets the context information for this exception
     */
    public ExceptionContext getContext() {
        return context;
    }
    
    /**
     * Gets the suggestion message for user
     */
    public String getSuggestion() {
        return suggestion;
    }
    
    /**
     * Checks if this exception has been logged
     */
    public boolean isLogged() {
        return logged;
    }
    
    /**
     * Creates a copy of this exception with additional context
     */
    public abstract BaseKneafException withContext(ExceptionContext context);
    
    /**
     * Creates a copy of this exception with additional suggestion
     */
    public abstract BaseKneafException withSuggestion(String suggestion);
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName()).append(": ");
        
        if (errorCode != null) {
            sb.append("[").append(errorCode).append("] ");
        }
        
        sb.append(getMessage());
        
        if (severity != null) {
            sb.append(" [Severity: ").append(severity.getName()).append("]");
        }
        
        if (context != null) {
            sb.append(" ").append(context.toString());
        }
        
        if (suggestion != null) {
            sb.append(" [Suggestion: ").append(suggestion).append("]");
        }
        
        if (getCause() != null) {
            sb.append(" [Cause: ").append(getCause().getMessage()).append("]");
        }
        
        return sb.toString();
    }
    
    /**
     * Builder class for BaseKneafException and its subclasses
     */
    public static abstract class Builder<T extends Builder<T>> {
        protected String message;
        protected Throwable cause;
        protected String errorCode;
        protected ExceptionSeverity severity = ExceptionSeverity.ERROR;
        protected ExceptionContext context;
        protected String suggestion;
        protected boolean logged = false;
        
        protected abstract T self();
        
        public T message(String message) {
            this.message = message;
            return self();
        }
        
        public T cause(Throwable cause) {
            this.cause = cause;
            return self();
        }
        
        public T errorCode(String errorCode) {
            this.errorCode = errorCode;
            return self();
        }
        
        public T severity(ExceptionSeverity severity) {
            this.severity = severity;
            return self();
        }
        
        public T context(ExceptionContext context) {
            this.context = context;
            return self();
        }
        
        public T suggestion(String suggestion) {
            this.suggestion = suggestion;
            return self();
        }
        
        public T logged(boolean logged) {
            this.logged = logged;
            return self();
        }
        
        protected void validate() {
            if (message == null || message.trim().isEmpty()) {
                throw new IllegalArgumentException("Exception message cannot be null or empty");
            }
        }
    }
}