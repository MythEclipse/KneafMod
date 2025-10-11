package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;

/** Interface for all configuration classes to provide common functionality. */
public interface ConfigurationProvider {

  /**
   * Validate the configuration values.
   *
   * @throws ConfigurationException if configuration is invalid
   */
  void validate() throws ConfigurationException;

  /**
   * Check if this configuration is enabled.
   *
   * @return true if enabled, false otherwise
   */
  boolean isEnabled();

  /**
   * Get a string representation of this configuration.
   *
   * @return string representation
   */
  String toString();
}
