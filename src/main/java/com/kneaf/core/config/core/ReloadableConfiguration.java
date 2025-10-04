package com.kneaf.core.config.core;

/** Interface for configuration classes that support reloading. */
public interface ReloadableConfiguration {

  /**
   * Reload the configuration from the source.
   *
   * @throws ConfigurationException if reload fails
   */
  void reload() throws ConfigurationException;

  /**
   * Check if this configuration is reloadable.
   *
   * @return true if reload is supported, false otherwise
   */
  boolean isReloadable();

  /**
   * Get the last reload timestamp.
   *
   * @return timestamp of last reload in milliseconds, or -1 if never reloaded
   */
  long getLastReloadTime();
}
