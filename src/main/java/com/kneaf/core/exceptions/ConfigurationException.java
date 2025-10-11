package com.kneaf.core.exceptions;

/**
 * Exception thrown for configuration-related errors.
 */
public class ConfigurationException extends KneafCoreException {
    
    /**
     * Constructs a new ConfigurationException with the specified detail message.
     *
     * @param message The detail message
     * @param component The component where the exception occurred
     */
    public ConfigurationException(String message, String component) {
        super(message, "CONFIG_ERROR", component, true);
    }

    /**
     * Constructs a new ConfigurationException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     * @param component The component where the exception occurred
     */
    public ConfigurationException(String message, Throwable cause, String component) {
        super(message, cause, "CONFIG_ERROR", component, true);
    }
}