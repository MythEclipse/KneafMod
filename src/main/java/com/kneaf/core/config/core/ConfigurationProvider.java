package com.kneaf.core.config.core;

/**
 * Interface for all configuration classes to provide common functionality.
 */
public interface ConfigurationProvider {
    
    /**
     * Validate the configuration values.
     * @throws IllegalArgumentException if configuration is invalid
     */
    void validate();
    
    /**
     * Check if this configuration is enabled.
     * @return true if enabled, false otherwise
     */
    boolean isEnabled();
    
    /**
     * Get a string representation of this configuration.
     * @return string representation
     */
    String toString();
}