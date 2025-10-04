package com.kneaf.core.protocol.core;

import java.time.Instant;
import java.util.Map;

/** Interface for structured protocol logging with traceability. */
public interface ProtocolLogger {

  /**
   * Log protocol operation start.
   *
   * @param operation the operation name
   * @param protocolFormat the protocol format (binary, json, etc.)
   * @param traceId unique trace identifier
   * @param metadata additional metadata
   */
  void logOperationStart(
      String operation, String protocolFormat, String traceId, Map<String, Object> metadata);

  /**
   * Log protocol operation completion.
   *
   * @param operation the operation name
   * @param protocolFormat the protocol format
   * @param traceId unique trace identifier
   * @param durationMs operation duration in milliseconds
   * @param success whether the operation succeeded
   * @param metadata additional metadata
   */
  void logOperationComplete(
      String operation,
      String protocolFormat,
      String traceId,
      long durationMs,
      boolean success,
      Map<String, Object> metadata);

  /**
   * Log protocol validation events.
   *
   * @param validationType the type of validation
   * @param result the validation result
   * @param traceId unique trace identifier
   * @param metadata additional metadata
   */
  void logValidation(
      String validationType,
      ProtocolValidator.ValidationResult result,
      String traceId,
      Map<String, Object> metadata);

  /**
   * Log protocol errors with full context.
   *
   * @param operation the operation that failed
   * @param error the error that occurred
   * @param traceId unique trace identifier
   * @param context additional error context
   */
  void logError(String operation, Exception error, String traceId, Map<String, Object> context);

  /**
   * Log protocol warnings.
   *
   * @param operation the operation context
   * @param warning the warning message
   * @param traceId unique trace identifier
   * @param context additional context
   */
  void logWarning(String operation, String warning, String traceId, Map<String, Object> context);

  /**
   * Log protocol performance metrics.
   *
   * @param operation the operation name
   * @param metrics performance metrics
   * @param traceId unique trace identifier
   */
  void logMetrics(String operation, Map<String, Number> metrics, String traceId);

  /**
   * Generate a unique trace ID for tracking protocol operations.
   *
   * @return unique trace identifier
   */
  String generateTraceId();

  /** Structured log entry for protocol operations. */
  class ProtocolLogEntry {
    private final String traceId;
    private final Instant timestamp;
    private final String operation;
    private final String protocolFormat;
    private final String level;
    private final String message;
    private final Map<String, Object> metadata;

    public ProtocolLogEntry(
        String traceId,
        Instant timestamp,
        String operation,
        String protocolFormat,
        String level,
        String message,
        Map<String, Object> metadata) {
      this.traceId = traceId;
      this.timestamp = timestamp;
      this.operation = operation;
      this.protocolFormat = protocolFormat;
      this.level = level;
      this.message = message;
      this.metadata = metadata;
    }

    // Getters
    public String getTraceId() {
      return traceId;
    }

    public Instant getTimestamp() {
      return timestamp;
    }

    public String getOperation() {
      return operation;
    }

    public String getProtocolFormat() {
      return protocolFormat;
    }

    public String getLevel() {
      return level;
    }

    public String getMessage() {
      return message;
    }

    public Map<String, Object> getMetadata() {
      return metadata;
    }
  }
}
