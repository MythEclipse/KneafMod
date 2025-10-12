package com.kneaf.core.exceptions.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility methods for exception handling, formatting, and logging. */
public final class ExceptionUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionUtils.class);

  // Prevent instantiation
  private ExceptionUtils() {
    throw new AssertionError("Cannot instantiate utility class");
  }

  /** Formats exception stack trace as a string */
  public static String getStackTraceAsString(Throwable throwable) {
    if (throwable == null) {
      return "";
    }

    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    throwable.printStackTrace(pw);
    return sw.toString();
  }

  /** Gets the root cause of an exception */
  public static Throwable getRootCause(Throwable throwable) {
    if (throwable == null) {
      return null;
    }

    Throwable cause = throwable.getCause();
    while (cause != null && cause != throwable) {
      throwable = cause;
      cause = throwable.getCause();
    }
    return throwable;
  }

  /** Checks if the exception or any of its causes is of the specified type */
  public static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
    if (throwable == null || causeType == null) {
      return false;
    }

    Throwable current = throwable;
    while (current != null) {
      if (causeType.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /** Creates a formatted error message with context */
  public static String formatErrorMessage(String message, ExceptionContext context) {
    if (message == null) {
      message = "Unknown error";
    }

    StringBuilder sb = new StringBuilder(message);

    if (context != null) {
      if (context.getOperation() != null) {
        sb.append(" [Operation: ").append(context.getOperation()).append("]");
      }
      if (context.getComponent() != null) {
        sb.append(" [Component: ").append(context.getComponent()).append("]");
      }
      if (context.getMethod() != null) {
        sb.append(" [Method: ").append(context.getMethod()).append("]");
      }
      if (context.getLineNumber() > 0) {
        sb.append(" [Line: ").append(context.getLineNumber()).append("]");
      }
      if (!context.getContextData().isEmpty()) {
        sb.append(" [Context: ").append(context.getContextData()).append("]");
      }
    }

    return sb.toString();
  }

  /** Logs exception with appropriate severity level */
  public static void logException(
      Logger logger, ExceptionSeverity severity, String message, Throwable throwable) {
    String formattedMessage = formatErrorMessage(message, null);

    switch (severity) {
      case CRITICAL:
        logger.error("CRITICAL: {}", formattedMessage, throwable);
        break;
      case ERROR:
        logger.error("ERROR: {}", formattedMessage, throwable);
        break;
      case WARNING:
        logger.warn("WARNING: {}", formattedMessage, throwable);
        break;
      case INFO:
        logger.info("INFO: {}", formattedMessage, throwable);
        break;
      case DEBUG:
        logger.debug("DEBUG: {}", formattedMessage, throwable);
        break;
      default:
        logger.error("UNKNOWN SEVERITY: {}", formattedMessage, throwable);
    }
  }

  /** Logs exception with context information */
  public static void logExceptionWithContext(
      Logger logger,
      ExceptionSeverity severity,
      String message,
      Throwable throwable,
      ExceptionContext context) {
    String formattedMessage = formatErrorMessage(message, context);
    logException(logger, severity, formattedMessage, throwable);
  }

  /** Creates a simple context map from key-value pairs */
  public static Map<String, Object> createContext(Object... keyValuePairs) {
    if (keyValuePairs == null || keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("Key-value pairs must be provided in pairs");
    }

    Map<String, Object> context = new HashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      String key = String.valueOf(keyValuePairs[i]);
      Object value = keyValuePairs[i + 1];
      context.put(key, value);
    }
    return context;
  }

  /** Safely executes a runnable, catching and logging any exceptions */
  public static void safeExecute(Runnable runnable, String operationName) {
    safeExecute(runnable, operationName, ExceptionSeverity.ERROR);
  }

  /** Safely executes a runnable, catching and logging any exceptions with specified severity */
  public static void safeExecute(
      Runnable runnable, String operationName, ExceptionSeverity severity) {
    try {
      runnable.run();
    } catch (Exception e) {
      logException(
          LOGGER,
          severity,
          String.format("Safe execution failed for operation: %s", operationName),
          e);
    }
  }

  /** Creates a user-friendly error message from technical error */
  public static String createUserFriendlyMessage(String technicalMessage, String suggestion) {
    if (technicalMessage == null) {
      technicalMessage = "An unexpected error occurred";
    }

    if (suggestion == null) {
      return technicalMessage;
    }

    return String.format("%s. Suggestion: %s", technicalMessage, suggestion);
  }

  /** Determines appropriate severity based on exception type */
  public static ExceptionSeverity determineSeverity(Throwable throwable) {
    if (throwable == null) {
      return ExceptionSeverity.INFO;
    }

    // Check for critical exceptions
    String className = throwable.getClass().getSimpleName();
    if (className.contains("OutOfMemoryError")
        || className.contains("StackOverflowError")
        || className.contains("VirtualMachineError")) {
      return ExceptionSeverity.CRITICAL;
    }

    // Check for error exceptions
    if (className.contains("Exception") && !className.contains("RuntimeException")) {
      return ExceptionSeverity.ERROR;
    }

    // Default to warning for runtime exceptions
    return ExceptionSeverity.WARNING;
  }
}
