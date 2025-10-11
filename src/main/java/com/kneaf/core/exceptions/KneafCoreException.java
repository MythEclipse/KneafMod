package com.kneaf.core.exceptions;

/**
 * Base exception class for all KneafCore exceptions.
 * Provides consistent exception handling and messaging across the application.
 */
public abstract class KneafCoreException extends RuntimeException {
    
    private final String errorCode;
    private final String component;
    private final boolean recoverable;

    /**
     * Constructs a new KneafCoreException with the specified detail message.
     *
     * @param message The detail message
     * @param errorCode The error code for this exception
     * @param component The component where the exception occurred
     * @param recoverable Whether the exception is recoverable
     */
    public KneafCoreException(String message, String errorCode, String component, boolean recoverable) {
        super(message);
        this.errorCode = errorCode;
        this.component = component;
        this.recoverable = recoverable;
    }

    /**
     * Constructs a new KneafCoreException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     * @param errorCode The error code for this exception
     * @param component The component where the exception occurred
     * @param recoverable Whether the exception is recoverable
     */
    public KneafCoreException(String message, Throwable cause, String errorCode, String component, boolean recoverable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.component = component;
        this.recoverable = recoverable;
    }

    /**
     * Gets the error code for this exception.
     *
     * @return The error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Gets the component where the exception occurred.
     *
     * @return The component name
     */
    public String getComponent() {
        return component;
    }

    /**
     * Checks if the exception is recoverable.
     *
     * @return true if recoverable, false otherwise
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * Gets a formatted string representation of the exception.
     *
     * @return Formatted exception details
     */
    @Override
    public String toString() {
        return String.format("KneafCoreException[code=%s, component=%s, recoverable=%s]: %s", 
                errorCode, component, recoverable, getMessage());
    }
}
