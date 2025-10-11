package com.kneaf.core.exceptions;

/**
 * Exception thrown for initialization-related errors.
 */
public class InitializationException extends KneafCoreException {
    
    /**
     * Constructs a new InitializationException with the specified detail message.
     *
     * @param message The detail message
     * @param component The component where the exception occurred
     */
    public InitializationException(String message, String component) {
        super(message, "INIT_ERROR", component, false); // Initialization errors are usually not recoverable
    }

    /**
     * Constructs a new InitializationException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     * @param component The component where the exception occurred
     */
    public InitializationException(String message, Throwable cause, String component) {
        super(message, cause, "INIT_ERROR", component, false);
    }
}