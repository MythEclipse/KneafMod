package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Properties;

/**
 * Interface for loading configuration from different sources.
 */
public interface ConfigSource {

    /**
     * Load configuration from the source.
     *
     * @return the loaded properties
     * @throws ConfigurationException if loading fails
     */
    Properties load() throws ConfigurationException;

    /**
     * Get the name of the source.
     *
     * @return the source name
     */
    String getName();
}