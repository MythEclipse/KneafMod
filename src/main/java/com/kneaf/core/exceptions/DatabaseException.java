package com.kneaf.core.exceptions;

/**
 * Exception thrown for database-related errors.
 */
public class DatabaseException extends KneafCoreException {
    
    /**
     * Constructs a new DatabaseException with the specified detail message.
     *
     * @param message The detail message
     * @param component The component where the exception occurred
     */
    public DatabaseException(String message, String component) {
        super(message, "DATABASE_ERROR", component, true);
    }

    /**
     * Constructs a new DatabaseException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     * @param component The component where the exception occurred
     */
    public DatabaseException(String message, Throwable cause, String component) {
        super(message, cause, "DATABASE_ERROR", component, true);
    }
}
