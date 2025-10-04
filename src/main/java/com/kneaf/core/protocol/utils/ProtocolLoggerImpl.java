package com.kneaf.core.protocol.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolException;
import com.kneaf.core.protocol.core.ProtocolLogger;
import com.kneaf.core.protocol.core.ProtocolValidator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of ProtocolLogger with structured JSON logging. */
public class ProtocolLoggerImpl implements ProtocolLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger("ProtocolLogger");
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private final String loggerName;
  private final Map<String, ProtocolLogEntry> recentLogs;
  private final int maxRecentLogs;

  /**
   * Create a new protocol logger.
   *
   * @param loggerName the name of this logger
   */
  public ProtocolLoggerImpl(String loggerName) {
    this(loggerName, 1000); // Default to keeping 1000 recent logs
  }

  /**
   * Create a new protocol logger with custom recent log limit.
   *
   * @param loggerName the name of this logger
   * @param maxRecentLogs maximum number of recent logs to keep in memory
   */
  public ProtocolLoggerImpl(String loggerName, int maxRecentLogs) {
    this.loggerName = loggerName;
    this.maxRecentLogs = maxRecentLogs;
    this.recentLogs = new ConcurrentHashMap<>();
  }

  @Override
  public void logOperationStart(
      String operation, String protocolFormat, String traceId, Map<String, Object> metadata) {
    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId,
            Instant.now(),
            operation,
            protocolFormat,
            ProtocolConstants.LOG_LEVEL_INFO,
            "Operation started",
            metadata);

    logEntry(entry);
    storeRecentLog(entry);
  }

  @Override
  public void logOperationComplete(
      String operation,
      String protocolFormat,
      String traceId,
      long durationMs,
      boolean success,
      Map<String, Object> metadata) {
    String level = success ? ProtocolConstants.LOG_LEVEL_INFO : ProtocolConstants.LOG_LEVEL_WARN;
    String message =
        success ? "Operation completed successfully" : "Operation completed with issues";

    Map<String, Object> completeMetadata = new java.util.HashMap<>(metadata);
    completeMetadata.put(ProtocolConstants.META_DURATION, durationMs);
    completeMetadata.put(ProtocolConstants.META_SUCCESS, success);

    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId, Instant.now(), operation, protocolFormat, level, message, completeMetadata);

    logEntry(entry);
    storeRecentLog(entry);
  }

  @Override
  public void logValidation(
      String validationType,
      ProtocolValidator.ValidationResult result,
      String traceId,
      Map<String, Object> metadata) {
    String level =
        result.isValid() ? ProtocolConstants.LOG_LEVEL_INFO : ProtocolConstants.LOG_LEVEL_ERROR;
    String message =
        result.isValid()
            ? "Validation passed: " + validationType
            : "Validation failed: " + validationType;

    Map<String, Object> validationMetadata = new java.util.HashMap<>(metadata);
    validationMetadata.put("validation_type", validationType);
    validationMetadata.put("validation_result", result.isValid());

    if (result.hasErrors()) {
      validationMetadata.put("validation_errors", result.getErrors());
    }
    if (result.hasWarnings()) {
      validationMetadata.put("validation_warnings", result.getWarnings());
    }

    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId, Instant.now(), "validation", null, level, message, validationMetadata);

    logEntry(entry);
    storeRecentLog(entry);
  }

  @Override
  public void logError(
      String operation, Exception error, String traceId, Map<String, Object> context) {
    Map<String, Object> errorMetadata = new java.util.HashMap<>(context);
    errorMetadata.put(ProtocolConstants.META_OPERATION, operation);
    errorMetadata.put("error_type", error.getClass().getSimpleName());
    errorMetadata.put("error_message", error.getMessage());

    if (error instanceof ProtocolException) {
      ProtocolException protocolError = (ProtocolException) error;
      errorMetadata.put(ProtocolConstants.META_ERROR_CODE, protocolError.getErrorCode());
      errorMetadata.put("protocol_format", protocolError.getProtocolFormat());
    }

    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId,
            Instant.now(),
            operation,
            null,
            ProtocolConstants.LOG_LEVEL_ERROR,
            "Operation failed with error",
            errorMetadata);

    logEntry(entry);
    storeRecentLog(entry);

    // Also log to standard error for immediate visibility
    LOGGER.error("Protocol error in operation '{ }': { }", operation, error.getMessage(), error);
  }

  @Override
  public void logWarning(
      String operation, String warning, String traceId, Map<String, Object> context) {
    Map<String, Object> warningMetadata = new java.util.HashMap<>(context);
    warningMetadata.put(ProtocolConstants.META_OPERATION, operation);
    warningMetadata.put("warning_message", warning);

    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId,
            Instant.now(),
            operation,
            null,
            ProtocolConstants.LOG_LEVEL_WARN,
            warning,
            warningMetadata);

    logEntry(entry);
    storeRecentLog(entry);
  }

  @Override
  public void logMetrics(String operation, Map<String, Number> metrics, String traceId) {
    Map<String, Object> metricsMetadata = new java.util.HashMap<>();
    metricsMetadata.put(ProtocolConstants.META_OPERATION, operation);
    metricsMetadata.put("metrics", metrics);

    ProtocolLogEntry entry =
        new ProtocolLogEntry(
            traceId,
            Instant.now(),
            operation,
            null,
            ProtocolConstants.LOG_LEVEL_INFO,
            "Performance metrics",
            metricsMetadata);

    logEntry(entry);
    storeRecentLog(entry);
  }

  @Override
  public String generateTraceId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Log a protocol entry to the structured logger.
   *
   * @param entry the log entry
   */
  private void logEntry(ProtocolLogEntry entry) {
    String jsonLog =
        GSON.toJson(
            Map.of(
                "logger", loggerName,
                "trace_id", entry.getTraceId(),
                "timestamp", entry.getTimestamp().toString(),
                "level", entry.getLevel(),
                "operation", entry.getOperation(),
                "protocol_format", entry.getProtocolFormat(),
                "message", entry.getMessage(),
                "metadata", entry.getMetadata()));

    // Log at appropriate level
    switch (entry.getLevel()) {
      case ProtocolConstants.LOG_LEVEL_DEBUG:
        LOGGER.debug(jsonLog);
        break;
      case ProtocolConstants.LOG_LEVEL_INFO:
        LOGGER.info(jsonLog);
        break;
      case ProtocolConstants.LOG_LEVEL_WARN:
        LOGGER.warn(jsonLog);
        break;
      case ProtocolConstants.LOG_LEVEL_ERROR:
        LOGGER.error(jsonLog);
        break;
      default:
        LOGGER.info(jsonLog);
    }
  }

  /**
   * Store recent log entry for retrieval.
   *
   * @param entry the log entry to store
   */
  private void storeRecentLog(ProtocolLogEntry entry) {
    recentLogs.put(entry.getTraceId(), entry);

    // Maintain size limit by removing oldest entries
    if (recentLogs.size() > maxRecentLogs) {
      String oldestKey = recentLogs.keySet().iterator().next();
      recentLogs.remove(oldestKey);
    }
  }

  /**
   * Get recent log entries.
   *
   * @param limit maximum number of entries to return
   * @return list of recent log entries
   */
  public java.util.List<ProtocolLogEntry> getRecentLogs(int limit) {
    return recentLogs.values().stream()
        .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
        .limit(limit)
        .toList();
  }

  /**
   * Get log entries by trace ID.
   *
   * @param traceId the trace ID
   * @return log entry or null if not found
   */
  public ProtocolLogEntry getLogByTraceId(String traceId) {
    return recentLogs.get(traceId);
  }

  /**
   * Get log entries by operation.
   *
   * @param operation the operation name
   * @param limit maximum number of entries to return
   * @return list of matching log entries
   */
  public java.util.List<ProtocolLogEntry> getLogsByOperation(String operation, int limit) {
    return recentLogs.values().stream()
        .filter(entry -> operation.equals(entry.getOperation()))
        .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
        .limit(limit)
        .toList();
  }

  /**
   * Get error log entries.
   *
   * @param limit maximum number of entries to return
   * @return list of error log entries
   */
  public java.util.List<ProtocolLogEntry> getErrorLogs(int limit) {
    return recentLogs.values().stream()
        .filter(entry -> ProtocolConstants.LOG_LEVEL_ERROR.equals(entry.getLevel()))
        .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
        .limit(limit)
        .toList();
  }

  /**
   * Get logger statistics.
   *
   * @return logger statistics
   */
  public Map<String, Object> getStatistics() {
    Map<String, Object> Stats = new java.util.HashMap<>();
    Stats.put("logger_name", loggerName);
    Stats.put("recent_logs_count", recentLogs.size());
    Stats.put("max_recent_logs", maxRecentLogs);

    // Count log levels
    Map<String, Long> levelCounts =
        recentLogs.values().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    ProtocolLogEntry::getLevel, java.util.stream.Collectors.counting()));
    Stats.put("level_counts", levelCounts);

    return Stats;
  }

  /** Clear all recent logs. */
  public void clearRecentLogs() {
    recentLogs.clear();
  }
}
