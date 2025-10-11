package com.kneaf.core.config.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * Manages configuration loading and parsing for the KneafCore system.
 * Centralizes configuration handling with support for multiple configuration sources.
 */
public class ConfigurationManager {
    private static final ConfigurationManager INSTANCE = new ConfigurationManager();
    private final Map<String, Properties> configSources = new HashMap<>();
    
    private ConfigurationManager() {
        // Private constructor for singleton pattern
    }
    
    /**
     * Get the singleton instance of ConfigurationManager.
     * @return ConfigurationManager instance
     */
    public static ConfigurationManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Load configuration from a properties file.
     * @param configName Name of the configuration
     * @param filePath Path to the properties file
     * @return true if configuration was loaded successfully, false otherwise
     */
    public boolean loadConfiguration(String configName, String filePath) {
        Objects.requireNonNull(configName, "Configuration name cannot be null");
        Objects.requireNonNull(filePath, "File path cannot be null");
        
        try (FileInputStream fis = new FileInputStream(new File(filePath))) {
            Properties properties = new Properties();
            properties.load(fis);
            configSources.put(configName, properties);
            return true;
        } catch (IOException e) {
            System.err.println("Failed to load configuration from " + filePath + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get a configuration value as a string.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return Configuration value or null if not found
     */
    public String getString(String configName, String key) {
        Properties properties = configSources.get(configName);
        return properties != null ? properties.getProperty(key) : null;
    }
    
    /**
     * Get a configuration value as an integer.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return Configuration value or null if not found or not a valid integer
     */
    public Integer getInteger(String configName, String key) {
        String value = getString(configName, key);
        try {
            return value != null ? Integer.parseInt(value) : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid integer value for key " + key + ": " + value);
            return null;
        }
    }
    
    /**
     * Get a configuration value as a boolean.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return Configuration value or null if not found or not a valid boolean
     */
    public Boolean getBoolean(String configName, String key) {
        String value = getString(configName, key);
        try {
            return value != null ? Boolean.parseBoolean(value) : null;
        } catch (Exception e) {
            System.err.println("Invalid boolean value for key " + key + ": " + value);
            return null;
        }
    }
    
    /**
     * Get a configuration value as a long.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return Configuration value or null if not found or not a valid long
     */
    public Long getLong(String configName, String key) {
        String value = getString(configName, key);
        try {
            return value != null ? Long.parseLong(value) : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid long value for key " + key + ": " + value);
            return null;
        }
    }
    
    /**
     * Get a configuration value as a double.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return Configuration value or null if not found or not a valid double
     */
    public Double getDouble(String configName, String key) {
        String value = getString(configName, key);
        try {
            return value != null ? Double.parseDouble(value) : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid double value for key " + key + ": " + value);
            return null;
        }
    }
    
    /**
     * Check if a configuration has a specific key.
     * @param configName Name of the configuration
     * @param key Configuration key
     * @return true if the configuration has the key, false otherwise
     */
    public boolean hasKey(String configName, String key) {
        Properties properties = configSources.get(configName);
        return properties != null && properties.containsKey(key);
    }
    
    /**
     * Get all properties from a configuration.
     * @param configName Name of the configuration
     * @return Map of all properties or null if configuration not found
     */
    public Map<String, String> getAllProperties(String configName) {
        Properties properties = configSources.get(configName);
        if (properties == null) {
            return null;
        }
        
        Map<String, String> result = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            result.put(key, properties.getProperty(key));
        }
        return result;
    }
    
    /**
     * Reload a configuration from its file.
     * @param configName Name of the configuration
     * @param filePath Path to the properties file
     * @return true if configuration was reloaded successfully, false otherwise
     */
    public boolean reloadConfiguration(String configName, String filePath) {
        // Remove the old configuration first
        configSources.remove(configName);
        // Then load the new one
        return loadConfiguration(configName, filePath);
    }
    
    /**
     * Remove a configuration from the manager.
     * @param configName Name of the configuration to remove
     * @return true if configuration was removed, false if not found
     */
    public boolean removeConfiguration(String configName) {
        return configSources.remove(configName) != null;
    }
}