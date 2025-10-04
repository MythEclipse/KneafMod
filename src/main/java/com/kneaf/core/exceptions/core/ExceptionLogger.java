package com.kneaf.core.exceptions.core;

import com.kneaf.core.exceptions.utils.ExceptionSeverity;

/**
 * Interface for logging exceptions with structured information. Provides standardized logging
 * across the application.
 */
public interface ExceptionLogger {

  /** Logs an exception with the specified severity */
  void logException(Throwable exception, ExceptionSeverity severity);

  /** Logs an exception with the specified severity and message */
  void logException(Throwable exception, ExceptionSeverity severity, String message);

  /** Logs an exception with the specified severity, message, and context */
  void logException(
      Throwable exception, ExceptionSeverity severity, String message, Object context);

  /** Logs an exception with structured data */
  void logException(
      Throwable exception,
      ExceptionSeverity severity,
      String message,
      String operation,
      String component,
      Object context);

  /** Determines if the logger is enabled for the specified severity */
  boolean isEnabled(ExceptionSeverity severity);

  /** Gets the logger name for identification */
  String getLoggerName();
}
