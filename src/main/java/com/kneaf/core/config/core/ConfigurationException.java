package com.kneaf.core.config.core;

/**
 * Exception thrown when configuration-related errors occur.
 */
public class ConfigurationException extends Exception {
    
    /**
     * Constructs a new configuration exception with the specified detail message.
     * @param message the detail message
     */
    public ConfigurationException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new configuration exception with the specified detail message and cause.
     * @param message the detail message
     * @param cause the cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Constructs a new configuration exception with the specified cause.
     * @param cause the cause
     */
    public ConfigurationException(Throwable cause) {
        super(cause);
    }
}