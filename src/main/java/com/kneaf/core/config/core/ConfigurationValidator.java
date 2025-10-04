package com.kneaf.core.config.core;

/** Interface for configuration validation logic. */
public interface ConfigurationValidator {

  /**
   * Validate the given configuration object.
   *
   * @param configuration the configuration to validate
   * @throws IllegalArgumentException if validation fails
   */
  void validate(Object configuration) throws IllegalArgumentException;

  /**
   * Check if this validator supports the given configuration type.
   *
   * @param configurationType the configuration type to check
   * @return true if this validator can validate the given type, false otherwise
   */
  boolean supports(Class<?> configurationType);
}
