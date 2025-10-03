package com.kneaf.core.exceptions.core;

import com.kneaf.core.exceptions.utils.ExceptionSeverity;

/**
 * Interface for handling exceptions in a standardized way.
 * Implementations can provide different handling strategies based on severity and context.
 */
public interface ExceptionHandler {
    
    /**
     * Handles an exception with the specified severity
     */
    void handleException(Throwable exception, ExceptionSeverity severity);
    
    /**
     * Handles an exception with the specified severity and context
     */
    void handleException(Throwable exception, ExceptionSeverity severity, String context);
    
    /**
     * Handles an exception with the specified severity, context, and operation
     */
    void handleException(Throwable exception, ExceptionSeverity severity, String context, String operation);
    
    /**
     * Determines if the exception should be handled by this handler
     */
    boolean canHandle(Throwable exception);
    
    /**
     * Gets the priority of this handler (lower values = higher priority)
     */
    int getPriority();
    
    /**
     * Gets the name of this handler for identification
     */
    String getHandlerName();
}