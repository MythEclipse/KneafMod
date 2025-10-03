package com.kneaf.core.exceptions;

/**
 * Exception thrown for database operation failures.
 * Replaces the duplicate DatabaseOperationException and RustDatabaseException classes.
 */
public class DatabaseException extends KneafCoreException {
    
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
    
    private final DatabaseErrorType errorType;
    private final String databaseType;
    private final String key;
    
    public DatabaseException(DatabaseErrorType errorType, String message) {
        super(ErrorCategory.DATABASE_OPERATION, message);
        this.errorType = errorType;
        this.databaseType = null;
        this.key = null;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String message, Throwable cause) {
        super(ErrorCategory.DATABASE_OPERATION, message, cause);
        this.errorType = errorType;
        this.databaseType = null;
        this.key = null;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String operation, String databaseType, String key, String message, Throwable cause) {
        super(ErrorCategory.DATABASE_OPERATION, operation, message, 
              String.format("Database: %s, Key: %s", databaseType, key), cause);
        this.errorType = errorType;
        this.databaseType = databaseType;
        this.key = key;
    }
    
    public DatabaseException(DatabaseErrorType errorType, String databaseType, String message, Throwable cause) {
        super(ErrorCategory.DATABASE_OPERATION, message, 
              String.format("Database: %s", databaseType), cause);
        this.errorType = errorType;
        this.databaseType = databaseType;
        this.key = null;
    }
    
    public DatabaseErrorType getErrorType() {
        return errorType;
    }
    
    public String getDatabaseType() {
        return databaseType;
    }
    
    public String getKey() {
        return key;
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
}