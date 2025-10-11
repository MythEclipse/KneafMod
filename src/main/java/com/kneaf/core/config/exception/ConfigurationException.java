package com.kneaf.core.config.exception;

/**
 * Exception thrown when configuration validation fails or configuration errors occur.
 */
public class ConfigurationException extends Exception {

    /**
     * Constructs a new ConfigurationException with the specified detail message.
     *
     * @param message the detail message
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new ConfigurationException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause (which is saved for later retrieval by the {@link #getCause()} method)
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}