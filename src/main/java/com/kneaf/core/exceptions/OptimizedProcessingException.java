package com.kneaf.core.exceptions;

/**
 * Legacy compatibility wrapper for OptimizedProcessingException. Extends the new enhanced exception
 * class to maintain backward compatibility.
 *
 * @deprecated Use com.kneaf.core.exceptions.processing.OptimizedProcessingException instead
 */
@Deprecated
public class OptimizedProcessingException extends RuntimeException {

  private final com.kneaf.core.exceptions.processing.OptimizedProcessingException delegate;
  private final ErrorType errorType;
  private final String operation;
  private final Object context;

  public OptimizedProcessingException(ErrorType errorType, String operation, String message) {
    super(message);
    this.delegate =
        com.kneaf.core.exceptions.processing.OptimizedProcessingException.builder()
            .processingErrorType(convertErrorType(errorType))
            .operation(operation)
            .message(message)
            .build();
    this.errorType = errorType;
    this.operation = operation;
    this.context = null;
  }

  public OptimizedProcessingException(
      ErrorType errorType, String operation, String message, Throwable cause) {
    super(message, cause);
    this.delegate =
        com.kneaf.core.exceptions.processing.OptimizedProcessingException.builder()
            .processingErrorType(convertErrorType(errorType))
            .operation(operation)
            .message(message)
            .cause(cause)
            .build();
    this.errorType = errorType;
    this.operation = operation;
    this.context = null;
  }

  public OptimizedProcessingException(
      ErrorType errorType, String operation, String message, Object context) {
    super(message);
    this.delegate =
        com.kneaf.core.exceptions.processing.OptimizedProcessingException.builder()
            .processingErrorType(convertErrorType(errorType))
            .operation(operation)
            .message(message)
            .processingContext(context)
            .build();
    this.errorType = errorType;
    this.operation = operation;
    this.context = context;
  }

  public OptimizedProcessingException(
      ErrorType errorType, String operation, String message, Object context, Throwable cause) {
    super(message, cause);
    this.delegate =
        com.kneaf.core.exceptions.processing.OptimizedProcessingException.builder()
            .processingErrorType(convertErrorType(errorType))
            .operation(operation)
            .message(message)
            .processingContext(context)
            .cause(cause)
            .build();
    this.errorType = errorType;
    this.operation = operation;
    this.context = context;
  }

  /** Gets the error type */
  public ErrorType getErrorType() {
    return errorType != null ? errorType : convertErrorTypeBack(delegate.getProcessingErrorType());
  }

  /** Gets the operation name */
  public String getOperation() {
    return operation != null ? operation : delegate.getOperation();
  }

  /** Gets the context object */
  public Object getContext() {
    return context != null ? context : delegate.getProcessingContext();
  }

  /** Gets the delegate exception for access to new functionality */
  public com.kneaf.core.exceptions.processing.OptimizedProcessingException getDelegate() {
    return delegate;
  }

  /** Factory methods for common exception scenarios */
  public static OptimizedProcessingException batchProcessingError(
      String operation, String message) {
    return new OptimizedProcessingException(ErrorType.BATCH_PROCESSING_ERROR, operation, message);
  }

  public static OptimizedProcessingException batchProcessingError(
      String operation, String message, Throwable cause) {
    return new OptimizedProcessingException(
        ErrorType.BATCH_PROCESSING_ERROR, operation, message, cause);
  }

  public static OptimizedProcessingException memoryAllocationError(
      String operation, String message, Object context) {
    return new OptimizedProcessingException(
        ErrorType.MEMORY_ALLOCATION_ERROR, operation, message, context);
  }

  public static OptimizedProcessingException nativeLibraryError(
      String operation, String message, Throwable cause) {
    return new OptimizedProcessingException(
        ErrorType.NATIVE_LIBRARY_ERROR, operation, message, cause);
  }

  public static OptimizedProcessingException predictiveLoadingError(
      String operation, String message, Object context) {
    return new OptimizedProcessingException(
        ErrorType.PREDICTIVE_LOADING_ERROR, operation, message, context);
  }

  public static OptimizedProcessingException bufferPoolError(String operation, String message) {
    return new OptimizedProcessingException(ErrorType.BUFFER_POOL_ERROR, operation, message);
  }

  public static OptimizedProcessingException workerExecutionError(
      String operation, String message, Throwable cause) {
    return new OptimizedProcessingException(
        ErrorType.WORKER_EXECUTION_ERROR, operation, message, cause);
  }

  public static OptimizedProcessingException timeoutError(
      String operation, String message, long timeoutMs) {
    return new OptimizedProcessingException(
        ErrorType.TIMEOUT_ERROR,
        operation,
        String.format("%s (timeout: %d ms)", message, timeoutMs));
  }

  public static OptimizedProcessingException resourceExhaustionError(
      String operation, String resourceType, Object context) {
    return new OptimizedProcessingException(
        ErrorType.RESOURCE_EXHAUSTION_ERROR,
        operation,
        String.format("Resource exhaustion: %s", resourceType),
        context);
  }

  private static com.kneaf.core.exceptions.processing.OptimizedProcessingException
          .ProcessingErrorType
      convertErrorType(ErrorType errorType) {
    if (errorType == null) return null;

    switch (errorType) {
      case BATCH_PROCESSING_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .BATCH_PROCESSING_ERROR;
      case MEMORY_ALLOCATION_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .MEMORY_ALLOCATION_ERROR;
      case NATIVE_LIBRARY_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .NATIVE_LIBRARY_ERROR;
      case PREDICTIVE_LOADING_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .PREDICTIVE_LOADING_ERROR;
      case BUFFER_POOL_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .BUFFER_POOL_ERROR;
      case WORKER_EXECUTION_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .WORKER_EXECUTION_ERROR;
      case TIMEOUT_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .TIMEOUT_ERROR;
      case RESOURCE_EXHAUSTION_ERROR:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .RESOURCE_EXHAUSTION_ERROR;
      default:
        return com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
            .BATCH_PROCESSING_ERROR;
    }
  }

  private static ErrorType convertErrorTypeBack(
      com.kneaf.core.exceptions.processing.OptimizedProcessingException.ProcessingErrorType
          errorType) {
    if (errorType == null) return null;

    switch (errorType) {
      case BATCH_PROCESSING_ERROR:
        return ErrorType.BATCH_PROCESSING_ERROR;
      case MEMORY_ALLOCATION_ERROR:
        return ErrorType.MEMORY_ALLOCATION_ERROR;
      case NATIVE_LIBRARY_ERROR:
        return ErrorType.NATIVE_LIBRARY_ERROR;
      case PREDICTIVE_LOADING_ERROR:
        return ErrorType.PREDICTIVE_LOADING_ERROR;
      case BUFFER_POOL_ERROR:
        return ErrorType.BUFFER_POOL_ERROR;
      case WORKER_EXECUTION_ERROR:
        return ErrorType.WORKER_EXECUTION_ERROR;
      case TIMEOUT_ERROR:
        return ErrorType.TIMEOUT_ERROR;
      case RESOURCE_EXHAUSTION_ERROR:
        return ErrorType.RESOURCE_EXHAUSTION_ERROR;
      default:
        return ErrorType.BATCH_PROCESSING_ERROR;
    }
  }

  /** Processing error types for backward compatibility */
  public enum ErrorType {
    BATCH_PROCESSING_ERROR("Batch processing failed"),
    MEMORY_ALLOCATION_ERROR("Memory allocation failed"),
    NATIVE_LIBRARY_ERROR("Native library operation failed"),
    PREDICTIVE_LOADING_ERROR("Predictive loading failed"),
    BUFFER_POOL_ERROR("Buffer pool operation failed"),
    WORKER_EXECUTION_ERROR("Worker execution failed"),
    TIMEOUT_ERROR("Operation timed out"),
    RESOURCE_EXHAUSTION_ERROR("Resource exhaustion detected");

    private final String description;

    ErrorType(String description) {
      this.description = description;
    }

    public String getDescription() {
      return description;
    }
  }
}
