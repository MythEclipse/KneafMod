package com.kneaf.core.data.core;

/** Exception thrown when data validation fails. */
public class DataValidationException extends RuntimeException {

  private final String field;
  private final Object value;

  public DataValidationException(String field, Object value, String message) {
    super(
        String.format(
            "Validation failed for field '%s' with value '%s': %s", field, value, message));
    this.field = field;
    this.value = value;
  }

  public DataValidationException(String field, Object value, String message, Throwable cause) {
    super(
        String.format(
            "Validation failed for field '%s' with value '%s': %s", field, value, message),
        cause);
    this.field = field;
    this.value = value;
  }

  public String getField() {
    return field;
  }

  public Object getValue() {
    return value;
  }
}
