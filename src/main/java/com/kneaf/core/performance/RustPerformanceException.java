package com.kneaf.core.performance;

public class RustPerformanceException extends RuntimeException {
  public RustPerformanceException(String message) {
    super(message);
  }

  public RustPerformanceException(String message, Throwable cause) {
    super(message, cause);
  }
}
