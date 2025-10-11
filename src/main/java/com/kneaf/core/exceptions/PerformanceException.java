package com.kneaf.core.exceptions;

/**
 * Exception thrown for performance-related errors.
 */
public class PerformanceException extends KneafCoreException {
    
    /**
     * Constructs a new PerformanceException with the specified detail message.
     *
     * @param message The detail message
     * @param component The component where the exception occurred
     */
    public PerformanceException(String message, String component) {
        super(message, "PERFORMANCE_ERROR", component, true);
    }

    /**
     * Constructs a new PerformanceException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     * @param component The component where the exception occurred
     */
    public PerformanceException(String message, Throwable cause, String component) {
        super(message, cause, "PERFORMANCE_ERROR", component, true);
    }
}