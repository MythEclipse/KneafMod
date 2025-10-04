package com.kneaf.core.exceptions;

/**
 * Legacy compatibility wrapper for AsyncProcessingException. Extends the new enhanced exception
 * class to maintain backward compatibility.
 *
 * @deprecated Use com.kneaf.core.exceptions.processing.AsyncProcessingException instead
 */
@Deprecated
public class AsyncProcessingException extends RuntimeException {

  private final com.kneaf.core.exceptions.processing.AsyncProcessingException delegate;
  private final String taskType;
  private final long timeoutMs;

  public AsyncProcessingException(AsyncErrorType errorType, String message) {
    super(message);
    this.delegate =
        com.kneaf.core.exceptions.processing.AsyncProcessingException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .build();
    this.taskType = null;
    this.timeoutMs = -1;
  }

  public AsyncProcessingException(AsyncErrorType errorType, String message, Throwable cause) {
    super(message, cause);
    this.delegate =
        com.kneaf.core.exceptions.processing.AsyncProcessingException.builder()
            .errorType(convertErrorType(errorType))
            .message(message)
            .cause(cause)
            .build();
    this.taskType = null;
    this.timeoutMs = -1;
  }

  public AsyncProcessingException(
      AsyncErrorType errorType, String taskType, String message, Throwable cause) {
    super(message, cause);
    this.delegate =
        com.kneaf.core.exceptions.processing.AsyncProcessingException.builder()
            .errorType(convertErrorType(errorType))
            .taskType(taskType)
            .message(message)
            .cause(cause)
            .build();
    this.taskType = taskType;
    this.timeoutMs = -1;
  }

  public AsyncProcessingException(
      AsyncErrorType errorType, String taskType, long timeoutMs, String message, Throwable cause) {
    super(message, cause);
    this.delegate =
        com.kneaf.core.exceptions.processing.AsyncProcessingException.builder()
            .errorType(convertErrorType(errorType))
            .taskType(taskType)
            .timeoutMs(timeoutMs)
            .message(message)
            .cause(cause)
            .build();
    this.taskType = taskType;
    this.timeoutMs = timeoutMs;
  }

  /** Gets the error type */
  public AsyncErrorType getErrorType() {
    return convertErrorTypeBack(delegate.getErrorType());
  }

  /** Gets the task type */
  public String getTaskType() {
    return taskType != null ? taskType : delegate.getTaskType();
  }

  /** Gets the timeout in milliseconds */
  public long getTimeoutMs() {
    return timeoutMs != -1 ? timeoutMs : delegate.getTimeoutMs();
  }

  /** Gets the delegate exception for access to new functionality */
  public com.kneaf.core.exceptions.processing.AsyncProcessingException getDelegate() {
    return delegate;
  }

  /** Creates an async processing exception for batch request interruptions. */
  public static AsyncProcessingException batchRequestInterrupted(String taskType, Throwable cause) {
    return new AsyncProcessingException(
        AsyncErrorType.BATCH_REQUEST_INTERRUPTED,
        taskType,
        String.format("Batch request for '%s' was interrupted", taskType),
        cause);
  }

  /** Creates an async processing exception for timeout exceeded. */
  public static AsyncProcessingException timeoutExceeded(
      String taskType, long timeoutMs, Throwable cause) {
    return new AsyncProcessingException(
        AsyncErrorType.TIMEOUT_EXCEEDED,
        taskType,
        timeoutMs,
        String.format("Async operation exceeded timeout of %dms", timeoutMs),
        cause);
  }

  /** Creates an async processing exception for supplyAsync operations. */
  public static AsyncProcessingException supplyAsyncFailed(
      String operation, String key, Throwable cause) {
    return new AsyncProcessingException(
        AsyncErrorType.SUPPLY_ASYNC_FAILED,
        operation,
        String.format("Supply async operation failed for %s: %s", operation, key),
        cause);
  }

  private static com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
      convertErrorType(AsyncErrorType errorType) {
    if (errorType == null) return null;

    switch (errorType) {
      case BATCH_REQUEST_INTERRUPTED:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .BATCH_REQUEST_INTERRUPTED;
      case TIMEOUT_EXCEEDED:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .TIMEOUT_EXCEEDED;
      case EXECUTOR_SHUTDOWN:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .EXECUTOR_SHUTDOWN;
      case TASK_REJECTED:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .TASK_REJECTED;
      case COMPLETION_EXCEPTION:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .COMPLETION_EXCEPTION;
      case SUPPLY_ASYNC_FAILED:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .SUPPLY_ASYNC_FAILED;
      default:
        return com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType
            .SUPPLY_ASYNC_FAILED;
    }
  }

  private static AsyncErrorType convertErrorTypeBack(
      com.kneaf.core.exceptions.processing.AsyncProcessingException.AsyncErrorType errorType) {
    if (errorType == null) return null;

    switch (errorType) {
      case BATCH_REQUEST_INTERRUPTED:
        return AsyncErrorType.BATCH_REQUEST_INTERRUPTED;
      case TIMEOUT_EXCEEDED:
        return AsyncErrorType.TIMEOUT_EXCEEDED;
      case EXECUTOR_SHUTDOWN:
        return AsyncErrorType.EXECUTOR_SHUTDOWN;
      case TASK_REJECTED:
        return AsyncErrorType.TASK_REJECTED;
      case COMPLETION_EXCEPTION:
        return AsyncErrorType.COMPLETION_EXCEPTION;
      case SUPPLY_ASYNC_FAILED:
        return AsyncErrorType.SUPPLY_ASYNC_FAILED;
      default:
        return AsyncErrorType.SUPPLY_ASYNC_FAILED;
    }
  }

  /** Async error types for backward compatibility */
  public enum AsyncErrorType {
    BATCH_REQUEST_INTERRUPTED("Batch request interrupted"),
    TIMEOUT_EXCEEDED("Async operation timeout exceeded"),
    EXECUTOR_SHUTDOWN("Async executor shutdown"),
    TASK_REJECTED("Async task rejected"),
    COMPLETION_EXCEPTION("Async completion exception"),
    SUPPLY_ASYNC_FAILED("Supply async operation failed");

    private final String description;

    AsyncErrorType(String description) {
      this.description = description;
    }

    public String getDescription() {
      return description;
    }
  }
}
