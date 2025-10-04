package com.kneaf.core.protocol.core;

import com.kneaf.core.protocol.utils.ProtocolContext;
import com.kneaf.core.protocol.utils.ProtocolLoggerImpl;
import com.kneaf.core.protocol.utils.ProtocolValidatorImpl;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Enhanced protocol processor with comprehensive support for multiple protocol formats, improved
 * error handling, structured logging, and protocol versioning.
 */
public class EnhancedProtocolProcessor implements ProtocolHandler<Object, Object> {

  private final ProtocolLogger logger;
  private final String protocolVersion;
  private final long defaultTimeoutMs;

  /**
   * Create a new enhanced protocol processor.
   *
   * @param logger the protocol logger
   * @param protocolVersion the supported protocol version
   * @param defaultTimeoutMs default timeout in milliseconds
   */
  public EnhancedProtocolProcessor(
      ProtocolLogger logger, String protocolVersion, long defaultTimeoutMs) {
    this.logger = logger;
    this.protocolVersion = protocolVersion;
    this.defaultTimeoutMs = defaultTimeoutMs;
  }

  /**
   * Create a default enhanced protocol processor.
   *
   * @return default processor
   */
  public static EnhancedProtocolProcessor createDefault() {
    return new EnhancedProtocolProcessor(
        new ProtocolLoggerImpl("EnhancedProtocolProcessor"),
        ProtocolConstants.CURRENT_VERSION,
        ProtocolConstants.DEFAULT_TIMEOUT_MS);
  }

  @Override
  public Object processBinary(Object input) throws Exception {
    String traceId = logger.generateTraceId();
    ProtocolContext context =
        ProtocolContext.builder()
            .operation("process_binary")
            .protocolFormat(ProtocolConstants.FORMAT_BINARY)
            .protocolVersion(protocolVersion)
            .logger(logger)
            .validator(ProtocolValidatorImpl.createBinaryValidator())
            .addMetadata("input_type", input.getClass().getSimpleName())
            .build();

    return processWithContext(
        input,
        context,
        inputData -> {
          try {
            return executeBinaryProcessing(inputData);
          } catch (Exception e) {
            throw new RuntimeException("Binary processing failed", e);
          }
        });
  }

  @Override
  public Object processJson(Object input) throws Exception {
    String traceId = logger.generateTraceId();
    ProtocolContext context =
        ProtocolContext.builder()
            .operation("process_json")
            .protocolFormat(ProtocolConstants.FORMAT_JSON)
            .protocolVersion(protocolVersion)
            .logger(logger)
            .validator(ProtocolValidatorImpl.createJsonValidator())
            .addMetadata("input_type", input.getClass().getSimpleName())
            .build();

    return processWithContext(
        input,
        context,
        inputData -> {
          try {
            return executeJsonProcessing(inputData);
          } catch (Exception e) {
            throw new RuntimeException("JSON processing failed", e);
          }
        });
  }

  @Override
  public Object processWithFallback(Object input) throws Exception {
    String traceId = logger.generateTraceId();
    String detectedFormat = ProtocolUtils.detectFormat(null, input);

    ProtocolContext context =
        ProtocolContext.builder()
            .operation("process_with_fallback")
            .protocolFormat(detectedFormat)
            .protocolVersion(protocolVersion)
            .logger(logger)
            .addMetadata("detected_format", detectedFormat)
            .addMetadata("input_type", input.getClass().getSimpleName())
            .build();

    logger.logOperationStart(
        "process_with_fallback",
        detectedFormat,
        traceId,
        Map.of("input_type", input.getClass().getSimpleName()));

    try {
      Object result;

      switch (detectedFormat) {
        case ProtocolConstants.FORMAT_BINARY:
          result = processBinary(input);
          break;
        case ProtocolConstants.FORMAT_JSON:
          result = processJson(input);
          break;
        default:
          // Try JSON as fallback
          logger.logWarning(
              "process_with_fallback",
              "Unknown format '" + detectedFormat + "', falling back to JSON",
              traceId,
              Map.of("detected_format", detectedFormat));
          result = processJson(input);
          break;
      }

      logger.logOperationComplete(
          "process_with_fallback",
          detectedFormat,
          traceId,
          context.getElapsedTime(),
          true,
          Map.of("result_type", result.getClass().getSimpleName()));

      return result;

    } catch (Exception e) {
      logger.logError(
          "process_with_fallback", e, traceId, Map.of("detected_format", detectedFormat));
      throw e;
    }
  }

  @Override
  public CompletableFuture<Object> processAsync(Object input) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return processWithFallback(input);
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        });
  }

  @Override
  public String getProtocolVersion() {
    return protocolVersion;
  }

  @Override
  public boolean supportsFormat(String format) {
    return ProtocolConstants.FORMAT_BINARY.equals(format)
        || ProtocolConstants.FORMAT_JSON.equals(format);
  }

  /**
   * Process data with the given context and processing function.
   *
   * @param input the input data
   * @param context the protocol context
   * @param processor the processing function
   * @return processing result
   * @throws Exception if processing fails
   */
  private Object processWithContext(
      Object input, ProtocolContext context, java.util.function.Function<Object, Object> processor)
      throws Exception {
    String traceId = context.getTraceId();
    String operation = context.getOperation();
    String format = context.getProtocolFormat();

    logger.logOperationStart(operation, format, traceId, context.getMetadata());

    try {
      // Validate input
      if (context.getValidator() != null) {
        @SuppressWarnings("unchecked")
        ProtocolValidator<Object> typedValidator =
            (ProtocolValidator<Object>) context.getValidator();
        ProtocolValidator.ValidationResult validationResult = typedValidator.validate(input);
        logger.logValidation("input_validation", validationResult, traceId, context.getMetadata());

        if (!validationResult.isValid()) {
          throw ProtocolException.validationFailed(
              "Input validation failed: " + String.join(", ", validationResult.getErrors()),
              format,
              traceId);
        }
      }

      // Execute processing with timeout
      Object result = executeWithTimeout(() -> processor.apply(input), defaultTimeoutMs);

      logger.logOperationComplete(
          operation,
          format,
          traceId,
          context.getElapsedTime(),
          true,
          Map.of("result_type", result != null ? result.getClass().getSimpleName() : "null"));

      return result;

    } catch (TimeoutException e) {
      ProtocolException timeoutError =
          ProtocolException.timeout(operation, defaultTimeoutMs, traceId);
      logger.logError(operation, timeoutError, traceId, context.getMetadata());
      throw timeoutError;
    } catch (Exception e) {
      if (e instanceof ProtocolException) {
        logger.logError(operation, e, traceId, context.getMetadata());
        throw e;
      } else {
        ProtocolException wrappedError =
            ProtocolException.serializationFailed(
                "Processing failed: " + e.getMessage(), e, format, traceId);
        logger.logError(operation, wrappedError, traceId, context.getMetadata());
        throw wrappedError;
      }
    }
  }

  /**
   * Execute binary processing logic.
   *
   * @param input the binary input
   * @return processing result
   * @throws Exception if processing fails
   */
  private Object executeBinaryProcessing(Object input) throws Exception {
    // Convert input to ByteBuffer if needed
    ByteBuffer buffer;
    if (input instanceof byte[]) {
      buffer = ByteBuffer.wrap((byte[]) input);
    } else if (input instanceof ByteBuffer) {
      buffer = (ByteBuffer) input;
    } else if (input instanceof String) {
      // Assume base64 encoded string
      byte[] decoded = java.util.Base64.getDecoder().decode((String) input);
      buffer = ByteBuffer.wrap(decoded);
    } else {
      throw new IllegalArgumentException(
          "Unsupported binary input type: " + input.getClass().getSimpleName());
    }

    // Here you would implement actual binary processing logic
    // For now, return the buffer as-is for demonstration
    return buffer;
  }

  /**
   * Execute JSON processing logic.
   *
   * @param input the JSON input
   * @return processing result
   * @throws Exception if processing fails
   */
  private Object executeJsonProcessing(Object input) throws Exception {
    String jsonString;
    if (input instanceof String) {
      jsonString = (String) input;
    } else {
      // Convert object to JSON
      jsonString = ProtocolUtils.toJson(input);
    }

    // Validate JSON format
    if (!ProtocolUtils.isValidJson(jsonString)) {
      throw ProtocolException.validationFailed(
          "Invalid JSON format", ProtocolConstants.FORMAT_JSON, logger.generateTraceId());
    }

    // Here you would implement actual JSON processing logic
    // For now, return the parsed JSON as a Map for demonstration
    return ProtocolUtils.parseJson(jsonString);
  }

  /**
   * Execute a function with timeout.
   *
   * @param function the function to execute
   * @param timeoutMs timeout in milliseconds
   * @return function result
   * @throws TimeoutException if timeout occurs
   * @throws Exception if function fails
   */
  private Object executeWithTimeout(java.util.concurrent.Callable<Object> function, long timeoutMs)
      throws TimeoutException, Exception {
    CompletableFuture<Object> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return function.call();
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });

    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw new TimeoutException("Operation timed out after " + timeoutMs + " ms");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof CompletionException) {
        cause = cause.getCause();
      }
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new Exception(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Exception("Operation was interrupted", e);
    }
  }

  /** Interface for binary serialization (legacy compatibility). */
  public interface BinarySerializer<T> {
    ByteBuffer serialize(T input) throws Exception;
  }

  /** Interface for binary native calling (legacy compatibility). */
  public interface BinaryNativeCaller<R> {
    R callNative(ByteBuffer input) throws Exception;
  }

  /** Interface for binary deserialization (legacy compatibility). */
  public interface BinaryDeserializer<T> {
    T deserialize(byte[] resultBytes) throws Exception;
  }

  /** Interface for JSON input preparation (legacy compatibility). */
  public interface JsonInputPreparer<T> {
    Map<String, Object> prepareInput(T input);
  }

  /** Interface for JSON native calling (legacy compatibility). */
  public interface JsonNativeCaller<R> {
    R callNative(String jsonInput) throws Exception;
  }

  /** Interface for JSON result parsing (legacy compatibility). */
  public interface JsonResultParser<T> {
    T parseResult(String jsonResult) throws Exception;
  }

  /** Result wrapper for protocol operations (legacy compatibility). */
  public static class ProtocolResult<T> {
    private final T data;
    private final Exception error;
    private final boolean success;

    public ProtocolResult(T data, Exception error, boolean success) {
      this.data = data;
      this.error = error;
      this.success = success;
    }

    public T getDataOrThrow() throws Exception {
      if (!success && error != null) {
        throw error;
      }
      return data;
    }

    public T getData() {
      return data;
    }

    public Exception getError() {
      return error;
    }

    public boolean isSuccess() {
      return success;
    }
  }

  /**
   * Process data with unified binary/JSON fallback mechanism (legacy compatibility).
   *
   * @param input the input data
   * @param operationName the operation name
   * @param binarySerializer binary serializer
   * @param binaryCaller binary native caller
   * @param binaryDeserializer binary deserializer
   * @param jsonInputPreparer JSON input preparer
   * @param jsonCaller JSON native caller
   * @param jsonResultParser JSON result parser
   * @param fallbackResult fallback result
   * @return protocol result
   */
  public <T, R> ProtocolResult<R> processWithFallback(
      T input,
      String operationName,
      BinarySerializer<T> binarySerializer,
      BinaryNativeCaller<byte[]> binaryCaller,
      BinaryDeserializer<R> binaryDeserializer,
      JsonInputPreparer<T> jsonInputPreparer,
      JsonNativeCaller<String> jsonCaller,
      JsonResultParser<R> jsonResultParser,
      R fallbackResult) {

    String traceId = logger.generateTraceId();

    try {
      // Try binary protocol first
      ByteBuffer binaryInput = binarySerializer.serialize(input);
      byte[] binaryResult = binaryCaller.callNative(binaryInput);

      if (binaryResult != null && binaryResult.length > 0) {
        R result = binaryDeserializer.deserialize(binaryResult);
        logger.logOperationComplete(
            operationName,
            ProtocolConstants.FORMAT_BINARY,
            traceId,
            0,
            true,
            Map.of("result_type", result.getClass().getSimpleName()));
        return new ProtocolResult<>(result, null, true);
      }
    } catch (Exception e) {
      logger.logWarning(
          operationName,
          "Binary processing failed, trying JSON fallback",
          traceId,
          Map.of("error", e.getMessage()));
    }

    try {
      // Try JSON protocol as fallback
      Map<String, Object> jsonInput = jsonInputPreparer.prepareInput(input);
      String jsonInputStr = ProtocolUtils.toJson(jsonInput);
      String jsonResult = jsonCaller.callNative(jsonInputStr);

      if (jsonResult != null && !jsonResult.isEmpty()) {
        R result = jsonResultParser.parseResult(jsonResult);
        logger.logOperationComplete(
            operationName,
            ProtocolConstants.FORMAT_JSON,
            traceId,
            0,
            true,
            Map.of("result_type", result.getClass().getSimpleName()));
        return new ProtocolResult<>(result, null, true);
      }
    } catch (Exception e) {
      logger.logWarning(
          operationName,
          "JSON processing also failed, returning fallback",
          traceId,
          Map.of("error", e.getMessage()));
    }

    // Return fallback result
    logger.logOperationComplete(
        operationName, "fallback", traceId, 0, true, Map.of("fallback_used", true));
    return new ProtocolResult<>(fallbackResult, null, true);
  }
}
