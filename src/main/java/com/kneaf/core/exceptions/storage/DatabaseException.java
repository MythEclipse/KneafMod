package com.kneaf.core.exceptions.storage;

import com.kneaf.core.exceptions.core.BaseKneafException;
import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionContext;
import com.kneaf.core.exceptions.utils.ExceptionConstants;

/**
 * Exception thrown for database operation failures.
 * Replaces the duplicate DatabaseOperationException and RustDatabaseException classes.
 */
public class DatabaseException extends BaseKneafException {
    
    public enum DatabaseErrorType {
        CONNECTION_FAILED(ExceptionConstants.CODE_DB_CONNECTION_FAILED, "Database connection failed"),
        OPERATION_FAILED("DB001", "Database operation failed"),
        TRANSACTION_FAILED(ExceptionConstants.CODE_DB_TRANSACTION_FAILED, "Database transaction failed"),
        VALIDATION_FAILED(ExceptionConstants.CODE_DB_VALIDATION_FAILED, "Database validation failed"),
        BACKUP_FAILED(ExceptionConstants.CODE_DB_BACKUP_FAILED, "Database backup failed"),
        MAINTENANCE_FAILED("DB005", "Database maintenance failed"),
        NATIVE_LIBRARY_ERROR(ExceptionConstants.CODE_PREFIX_NATIVE + "006", "Native database library error"),
        ASYNC_OPERATION_FAILED("DB006", "Async database operation failed");
        
        private final String errorCode;
        private final String description;
        
        DatabaseErrorType(String errorCode, String description) {
            this.errorCode = errorCode;
            this.description = description;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final DatabaseErrorType errorType;
    private final String databaseType;
    private final String key;
    
    private DatabaseException(Builder builder) {
        super(builder);
        this.errorType = builder.errorType;
        this.databaseType = builder.databaseType;
        this.key = builder.key;
    }
    
    /**
     * Gets the database error type
     */
    public DatabaseErrorType getErrorType() {
        return errorType;
    }
    
    /**
     * Gets the database type
     */
    public String getDatabaseType() {
        return databaseType;
    }
    
    /**
     * Gets the database key
     */
    public String getKey() {
        return key;
    }
    
    @Override
    public DatabaseException withContext(ExceptionContext context) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(context)
            .suggestion(getSuggestion())
            .logged(isLogged())
            .errorType(errorType)
            .databaseType(databaseType)
            .key(key)
            .build();
    }
    
    @Override
    public DatabaseException withSuggestion(String suggestion) {
        return new Builder()
            .message(getMessage())
            .cause(getCause())
            .errorCode(getErrorCode())
            .severity(getSeverity())
            .context(getContext())
            .suggestion(suggestion)
            .logged(isLogged())
            .errorType(errorType)
            .databaseType(databaseType)
            .key(key)
            .build();
    }
    
    /**
     * Creates a builder for DatabaseException
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a database exception for async operations.
     */
    public static DatabaseException asyncOperationFailed(String operation, String databaseType, String key, Throwable cause) {
        return builder()
            .errorType(DatabaseErrorType.ASYNC_OPERATION_FAILED)
            .databaseType(databaseType)
            .key(key)
            .message(String.format("Async %s operation failed", operation))
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    /**
     * Creates a database exception for native library errors.
     */
    public static DatabaseException nativeLibraryError(String databaseType, String message, Throwable cause) {
        return builder()
            .errorType(DatabaseErrorType.NATIVE_LIBRARY_ERROR)
            .databaseType(databaseType)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    /**
     * Creates a database exception for connection failures.
     */
    public static DatabaseException connectionFailed(String databaseType, String message, Throwable cause) {
        return builder()
            .errorType(DatabaseErrorType.CONNECTION_FAILED)
            .databaseType(databaseType)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_DEPENDENCIES)
            .build();
    }
    
    /**
     * Creates a database exception for transaction failures.
     */
    public static DatabaseException transactionFailed(String databaseType, String message, Throwable cause) {
        return builder()
            .errorType(DatabaseErrorType.TRANSACTION_FAILED)
            .databaseType(databaseType)
            .message(message)
            .cause(cause)
            .severity(ExceptionSeverity.ERROR)
            .suggestion(ExceptionConstants.SUGGESTION_CHECK_LOGS)
            .build();
    }
    
    /**
     * Creates a database exception for validation failures.
     */
    public static DatabaseException validationFailed(String databaseType, String key, String message) {
        return builder()
            .errorType(DatabaseErrorType.VALIDATION_FAILED)
            .databaseType(databaseType)
            .key(key)
            .message(message)
            .severity(ExceptionSeverity.WARNING)
            .suggestion(ExceptionConstants.SUGGESTION_VERIFY_CONFIG)
            .build();
    }
    
    /**
     * Builder class for DatabaseException
     */
    public static class Builder extends BaseKneafException.Builder<Builder> {
        private DatabaseErrorType errorType;
        private String databaseType;
        private String key;
        
        @Override
        protected Builder self() {
            return this;
        }
        
        public Builder errorType(DatabaseErrorType errorType) {
            this.errorType = errorType;
            return this;
        }
        
        public Builder databaseType(String databaseType) {
            this.databaseType = databaseType;
            return this;
        }
        
        public Builder key(String key) {
            this.key = key;
            return this;
        }
        
        @Override
        protected void validate() {
            super.validate();
            if (errorType == null) {
                throw new IllegalArgumentException("Error type cannot be null");
            }
        }
        
        public DatabaseException build() {
            validate();
            
            // Auto-generate error code if not provided
            if (errorCode == null && errorType != null) {
                errorCode = errorType.getErrorCode();
            }
            
            // Auto-set severity if not provided
            if (severity == null) {
                severity = ExceptionSeverity.ERROR;
            }
            
            return new DatabaseException(this);
        }
    }
}