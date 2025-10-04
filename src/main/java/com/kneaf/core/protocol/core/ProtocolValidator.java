package com.kneaf.core.protocol.core;

import java.util.List;

/** Interface for protocol data validation with comprehensive validation rules. */
public interface ProtocolValidator<T> {

  /**
   * Validate the input data according to protocol specifications.
   *
   * @param data the data to validate
   * @return validation result containing any errors found
   */
  ValidationResult validate(T data);

  /**
   * Validate data format (binary, JSON, etc.).
   *
   * @param data the data to validate
   * @param format the expected format
   * @return true if format is valid
   */
  boolean validateFormat(T data, String format);

  /**
   * Validate data against protocol version requirements.
   *
   * @param data the data to validate
   * @param protocolVersion the protocol version
   * @return true if version is compatible
   */
  boolean validateVersion(T data, String protocolVersion);

  /**
   * Validate data size limits.
   *
   * @param data the data to validate
   * @return true if size is within limits
   */
  boolean validateSize(T data);

  /**
   * Validate data integrity (checksums, signatures, etc.).
   *
   * @param data the data to validate
   * @return true if integrity is valid
   */
  boolean validateIntegrity(T data);

  /**
   * Get the validation rules supported by this validator.
   *
   * @return list of validation rule names
   */
  List<String> getSupportedRules();

  /** Result of validation containing status and any errors found. */
  class ValidationResult {
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;

    public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
      this.valid = valid;
      this.errors = errors;
      this.warnings = warnings;
    }

    public boolean isValid() {
      return valid;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    public boolean hasErrors() {
      return !errors.isEmpty();
    }

    public boolean hasWarnings() {
      return !warnings.isEmpty();
    }

    public static ValidationResult valid() {
      return new ValidationResult(true, List.of(), List.of());
    }

    public static ValidationResult invalid(List<String> errors) {
      return new ValidationResult(false, errors, List.of());
    }

    public static ValidationResult invalid(List<String> errors, List<String> warnings) {
      return new ValidationResult(false, errors, warnings);
    }
  }
}
