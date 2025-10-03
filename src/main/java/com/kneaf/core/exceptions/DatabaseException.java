package com.kneaf.core.exceptions;

/**
 * Legacy compatibility wrapper for DatabaseException.
 * Extends the new enhanced exception class to maintain backward compatibility.
 * 
 * @deprecated Use com.kneaf.core.exceptions.storage.DatabaseException instead
 */
@Deprecated
public class DatabaseException extends RuntimeException {
    
    private final com.kneaf.core.exceptions.storage.DatabaseException delegate;
    private final DatabaseErrorType errorType;
    private final String databaseType;
    private final String key;
    
    public DatabaseException(DatabaseErrorType errorType, String message) {
        super(message);
        this.delegate = com.kneaf.core.exceptions.storage.DatabaseException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .build();
        this.errorType = errorType;
        this.databaseType = null;
        this.key = null;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.storage.DatabaseException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .cause(cause)
            .build();
        this.errorType = errorType;
        this.databaseType = null;
        this.key = null;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String operation, String databaseType, String key, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.storage.DatabaseException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .cause(cause)
            .build();
        this.errorType = errorType;
        this.databaseType = databaseType;
        this.key = key;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String databaseType, String message, Throwable cause) {
        super(message, cause);
        this.delegate = com.kneaf.core.exceptions.storage.DatabaseException.builder()
            .errorType(convertErrorType(errorType))
            .databaseType(databaseType)
            .message(message)
            .cause(cause)
            .build();
        this.errorType = errorType;
        this.databaseType = databaseType;
        this.key = null;
    }
    
    /**
     * Gets the database error type
     */
    public DatabaseErrorType getErrorType() {
        return errorType != null ? errorType : convertErrorTypeBack(delegate.getErrorType());
    }
    
    /**
     * Gets the database type
     */
    public String getDatabaseType() {
        return databaseType != null ? databaseType : delegate.getDatabaseType();
    }
    
    /**
     * Gets the database key
     */
    public String getKey() {
        return key != null ? key : delegate.getKey();
    }
    
    /**
     * Gets the delegate exception for access to new functionality
     */
    public com.kneaf.core.exceptions.storage.DatabaseException getDelegate() {
        return delegate;
    }
    
    /**
     * Creates a database exception for async operations.
     */
    public static DatabaseException asyncOperationFailed(String operation, String databaseType, String key, Throwable cause) {
        return new DatabaseException(DatabaseErrorType.ASYNC_OPERATION_FAILED, operation, databaseType, key, 
                                     String.format("Async %s operation failed", operation), cause);
    }
    
    /**
     * Creates a database exception for native library errors.
     */
    public static DatabaseException nativeLibraryError(String databaseType, String message, Throwable cause) {
        return new DatabaseException(DatabaseErrorType.NATIVE_LIBRARY_ERROR, databaseType, message, cause);
    }
    
    private static com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType convertErrorType(DatabaseErrorType errorType) {
        if (errorType == null) return null;
        
        switch (errorType) {
            case CONNECTION_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.CONNECTION_FAILED;
            case OPERATION_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.OPERATION_FAILED;
            case TRANSACTION_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.TRANSACTION_FAILED;
            case VALIDATION_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.VALIDATION_FAILED;
            case BACKUP_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.BACKUP_FAILED;
            case MAINTENANCE_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.MAINTENANCE_FAILED;
            case NATIVE_LIBRARY_ERROR:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.NATIVE_LIBRARY_ERROR;
            case ASYNC_OPERATION_FAILED:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.ASYNC_OPERATION_FAILED;
            default:
                return com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType.OPERATION_FAILED;
        }
    }
    
    private static DatabaseErrorType convertErrorTypeBack(com.kneaf.core.exceptions.storage.DatabaseException.DatabaseErrorType errorType) {
        if (errorType == null) return null;
        
        switch (errorType) {
            case CONNECTION_FAILED:
                return DatabaseErrorType.CONNECTION_FAILED;
            case OPERATION_FAILED:
                return DatabaseErrorType.OPERATION_FAILED;
            case TRANSACTION_FAILED:
                return DatabaseErrorType.TRANSACTION_FAILED;
            case VALIDATION_FAILED:
                return DatabaseErrorType.VALIDATION_FAILED;
            case BACKUP_FAILED:
                return DatabaseErrorType.BACKUP_FAILED;
            case MAINTENANCE_FAILED:
                return DatabaseErrorType.MAINTENANCE_FAILED;
            case NATIVE_LIBRARY_ERROR:
                return DatabaseErrorType.NATIVE_LIBRARY_ERROR;
            case ASYNC_OPERATION_FAILED:
                return DatabaseErrorType.ASYNC_OPERATION_FAILED;
            default:
                return DatabaseErrorType.OPERATION_FAILED;
        }
    }
    
    /**
     * Database error types for backward compatibility
     */
    public enum DatabaseErrorType {
        CONNECTION_FAILED("Database connection failed"),
        OPERATION_FAILED("Database operation failed"),
        TRANSACTION_FAILED("Database transaction failed"),
        VALIDATION_FAILED("Database validation failed"),
        BACKUP_FAILED("Database backup failed"),
        MAINTENANCE_FAILED("Database maintenance failed"),
        NATIVE_LIBRARY_ERROR("Native database library error"),
        ASYNC_OPERATION_FAILED("Async database operation failed");
        
        private final String description;
        
        DatabaseErrorType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
}