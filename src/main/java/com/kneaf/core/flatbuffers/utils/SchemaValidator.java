package com.kneaf.core.flatbuffers.utils;

/**
 * Interface for validating flatbuffer schemas and data. Implementations should provide
 * schema-specific validation logic.
 */
public interface SchemaValidator<T> {

  /**
   * Validate the input data against the schema.
   *
   * @param input the input data to validate
   * @return validation result
   */
  ValidationResult validate(T input);

  /**
   * Validate the input data against the schema with detailed error reporting.
   *
   * @param input the input data to validate
   * @return detailed validation result with error messages
   */
  DetailedValidationResult validateDetailed(T input);

  /**
   * Get the schema version this validator supports.
   *
   * @return schema version string
   */
  String getSupportedSchemaVersion();

  /**
   * Check if this validator can handle the given schema version.
   *
   * @param schemaVersion the schema version to check
   * @return true if compatible, false otherwise
   */
  boolean isCompatibleWith(String schemaVersion);

  /** Simple validation result. */
  class ValidationResult {
    private final boolean valid;
    private final String errorMessage;

    public ValidationResult(boolean valid, String errorMessage) {
      this.valid = valid;
      this.errorMessage = errorMessage;
    }

    public static ValidationResult valid() {
      return new ValidationResult(true, null);
    }

    public static ValidationResult invalid(String errorMessage) {
      return new ValidationResult(false, errorMessage);
    }

    public boolean isValid() {
      return valid;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    @Override
    public String toString() {
      return valid ? "Valid" : "Invalid: " + errorMessage;
    }
  }

  /** Detailed validation result with multiple error messages. */
  class DetailedValidationResult {
    private final boolean valid;
    private final java.util.List<String> errors;
    private final java.util.List<String> warnings;

    public DetailedValidationResult(
        boolean valid, java.util.List<String> errors, java.util.List<String> warnings) {
      this.valid = valid;
      this.errors = new java.util.ArrayList<>(errors);
      this.warnings = new java.util.ArrayList<>(warnings);
    }

    public static DetailedValidationResult valid() {
      return new DetailedValidationResult(
          true, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public static DetailedValidationResult invalid(java.util.List<String> errors) {
      return new DetailedValidationResult(false, errors, java.util.Collections.emptyList());
    }

    public static DetailedValidationResult invalidWithWarnings(
        java.util.List<String> errors, java.util.List<String> warnings) {
      return new DetailedValidationResult(false, errors, warnings);
    }

    public boolean isValid() {
      return valid;
    }

    public java.util.List<String> getErrors() {
      return new java.util.ArrayList<>(errors);
    }

    public java.util.List<String> getWarnings() {
      return new java.util.ArrayList<>(warnings);
    }

    public boolean hasErrors() {
      return !errors.isEmpty();
    }

    public boolean hasWarnings() {
      return !warnings.isEmpty();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(valid ? "Valid" : "Invalid");
      if (!errors.isEmpty()) {
        sb.append(" (").append(errors.size()).append(" errors)");
      }
      if (!warnings.isEmpty()) {
        sb.append(" (").append(warnings.size()).append(" warnings)");
      }
      return sb.toString();
    }
  }
}
