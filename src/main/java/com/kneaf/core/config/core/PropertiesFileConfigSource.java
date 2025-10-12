package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * ConfigSource implementation for loading configuration from properties files.
 */
public class PropertiesFileConfigSource implements ConfigSource {
    private final String filePath;

    /**
     * Create a new PropertiesFileConfigSource.
     *
     * @param filePath the path to the properties file
     */
    public PropertiesFileConfigSource(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public Properties load() throws ConfigurationException {
        Properties properties = new Properties();
        Path path = Paths.get(filePath);

        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
                return properties;
            } catch (IOException e) {
                throw new ConfigurationException("Failed to load properties from file: " + filePath, e);
            }
        } else {
            // Treat missing properties file as non-fatal: return empty properties and log a warning.
            // Many environments (tests, CI) may not provide an optional config file and should
            // continue with defaults.
            System.err.println("Properties file not found: " + filePath + " - using defaults");
            return properties;
        }
    }

    @Override
    public String getName() {
        return "PropertiesFileConfigSource[" + filePath + "]";
    }
}