package com.kneaf.core.exceptions.utils;

/**
 * Constants for common exception messages and error codes. Provides standardized error messages
 * across the application.
 */
public final class ExceptionConstants {

  // Prevent instantiation
  private ExceptionConstants() {
    throw new AssertionError("Cannot instantiate utility class");
  }

  // Common error messages
  public static final String MSG_OPERATION_FAILED = "Operation failed";
  public static final String MSG_INVALID_ARGUMENT = "Invalid argument provided";
  public static final String MSG_NULL_POINTER = "Null pointer encountered";
  public static final String MSG_ILLEGAL_STATE = "Illegal state detected";
  public static final String MSG_RESOURCE_NOT_FOUND = "Resource not found";
  public static final String MSG_PERMISSION_DENIED = "Permission denied";
  public static final String MSG_TIMEOUT_EXCEEDED = "Operation timed out";
  public static final String MSG_CONCURRENT_ACCESS = "Concurrent access violation";
  public static final String MSG_CORRUPTED_DATA = "Corrupted data detected";
  public static final String MSG_INITIALIZATION_FAILED = "Initialization failed";
  public static final String MSG_CLEANUP_FAILED = "Cleanup operation failed";

  // Database-specific messages
  public static final String MSG_DB_CONNECTION_FAILED = "Database connection failed";
  public static final String MSG_DB_TRANSACTION_FAILED = "Database transaction failed";
  public static final String MSG_DB_VALIDATION_FAILED = "Database validation failed";
  public static final String MSG_DB_BACKUP_FAILED = "Database backup failed";
  public static final String MSG_DB_OPERATION_FAILED = "Database operation failed";

  // Native library messages
  public static final String MSG_NATIVE_LIBRARY_NOT_AVAILABLE = "Native library is not available";
  public static final String MSG_NATIVE_LIBRARY_LOAD_FAILED = "Failed to load native library";
  public static final String MSG_NATIVE_CALL_FAILED = "Native method call failed";
  public static final String MSG_BINARY_PROTOCOL_ERROR = "Binary protocol error";
  public static final String MSG_JNI_ERROR = "JNI error";
  public static final String MSG_MEMORY_ALLOCATION_FAILED = "Native memory allocation failed";

  // Async processing messages
  public static final String MSG_BATCH_REQUEST_INTERRUPTED = "Batch request interrupted";
  public static final String MSG_ASYNC_TIMEOUT_EXCEEDED = "Async operation timeout exceeded";
  public static final String MSG_EXECUTOR_SHUTDOWN = "Async executor shutdown";
  public static final String MSG_TASK_REJECTED = "Async task rejected";
  public static final String MSG_COMPLETION_EXCEPTION = "Async completion exception";
  public static final String MSG_SUPPLY_ASYNC_FAILED = "Supply async operation failed";

  // Processing optimization messages
  public static final String MSG_BATCH_PROCESSING_ERROR = "Batch processing failed";
  public static final String MSG_MEMORY_ALLOCATION_ERROR = "Memory allocation failed";
  public static final String MSG_PREDICTIVE_LOADING_ERROR = "Predictive loading failed";
  public static final String MSG_BUFFER_POOL_ERROR = "Buffer pool operation failed";
  public static final String MSG_WORKER_EXECUTION_ERROR = "Worker execution failed";
  public static final String MSG_RESOURCE_EXHAUSTION_ERROR = "Resource exhaustion detected";

  // Common error codes (prefix-based)
  public static final String CODE_PREFIX_DB = "DB";
  public static final String CODE_PREFIX_NATIVE = "NAT";
  public static final String CODE_PREFIX_ASYNC = "ASYNC";
  public static final String CODE_PREFIX_PROCESSING = "PROC";
  public static final String CODE_PREFIX_CONFIG = "CFG";
  public static final String CODE_PREFIX_VALIDATION = "VAL";
  public static final String CODE_PREFIX_SYSTEM = "SYS";

  // Specific error codes
  public static final String CODE_DB_CONNECTION_FAILED = "DB001";
  public static final String CODE_DB_TRANSACTION_FAILED = "DB002";
  public static final String CODE_DB_VALIDATION_FAILED = "DB003";
  public static final String CODE_DB_BACKUP_FAILED = "DB004";

  public static final String CODE_NATIVE_LIBRARY_NOT_AVAILABLE = "NAT001";
  public static final String CODE_NATIVE_LIBRARY_LOAD_FAILED = "NAT002";
  public static final String CODE_NATIVE_CALL_FAILED = "NAT003";
  public static final String CODE_BINARY_PROTOCOL_ERROR = "NAT004";

  public static final String CODE_ASYNC_TIMEOUT = "ASYNC001";
  public static final String CODE_ASYNC_INTERRUPTED = "ASYNC002";
  public static final String CODE_ASYNC_REJECTED = "ASYNC003";

  public static final String CODE_PROCESSING_BATCH_FAILED = "PROC001";
  public static final String CODE_PROCESSING_MEMORY_FAILED = "PROC002";
  public static final String CODE_PROCESSING_TIMEOUT = "PROC003";
  public static final String CODE_PROCESSING_RESOURCE_EXHAUSTION = "PROC004";

  // Suggestion messages
  public static final String SUGGESTION_CHECK_LOGS =
      "Check application logs for detailed error information";
  public static final String SUGGESTION_VERIFY_CONFIG =
      "Verify configuration settings and parameters";
  public static final String SUGGESTION_CHECK_RESOURCES =
      "Check system resources (memory, disk space, network)";
  public static final String SUGGESTION_RESTART_SERVICE =
      "Try restarting the service or application";
  public static final String SUGGESTION_CONTACT_SUPPORT =
      "Contact system administrator or support team";
  public static final String SUGGESTION_CHECK_DEPENDENCIES =
      "Check external dependencies and services";
  public static final String SUGGESTION_UPDATE_SOFTWARE =
      "Consider updating to the latest software version";
}
