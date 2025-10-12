package com.kneaf.core.config.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for common configuration operations. */
public final class ConfigurationUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationUtils.class);

  private ConfigurationUtils() {} // Prevent instantiation

  /**
   * Load properties from a file path.
   *
   * @param configPath the path to the configuration file
   * @return Properties object, empty if file doesn't exist or error occurs
   */
  public static Properties loadProperties(String configPath) {
    Properties props = new Properties();
    Path path = Paths.get(configPath);

    if (Files.exists(path)) {
      try (InputStream in = Files.newInputStream(path)) {
        props.load(in);
        LOGGER.info("Loaded configuration from: {}", configPath);
      } catch (IOException e) {
        LOGGER.warn(
            "Failed to read configuration from {}, using defaults: {}",
            configPath,
            e.getMessage());
      }
    } else {
      LOGGER.info("Configuration file not found at {}, using defaults", configPath);
    }

    return props;
  }

  /** Get boolean property with default value. */
  public static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
    String value = props.getProperty(key);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  /** Get integer property with default value. */
  public static int getIntProperty(Properties props, String key, int defaultValue) {
    String value = props.getProperty(key);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOGGER.warn("Invalid integer value for key '{}': {}", key, value);
      return defaultValue;
    }
  }

  /** Get long property with default value. */
  public static long getLongProperty(Properties props, String key, long defaultValue) {
    String value = props.getProperty(key);
    if (value == null) return defaultValue;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      LOGGER.warn("Invalid long value for key '{}': {}", key, value);
      return defaultValue;
    }
  }

  /** Get double property with default value. */
  public static double getDoubleProperty(Properties props, String key, double defaultValue) {
    String value = props.getProperty(key);
    if (value == null) return defaultValue;
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      LOGGER.warn("Invalid double value for key '{}': {}", key, value);
      return defaultValue;
    }
  }

  /** Get string property with default value. */
  public static String getProperty(Properties props, String key, String defaultValue) {
    return props.getProperty(key, defaultValue);
  }

  /** Get string array property with default value. */
  public static String[] getStringArrayProperty(
      Properties props, String key, String[] defaultValue) {
    String value = props.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue != null ? defaultValue.clone() : new String[0];
    }
    return value.split("\\s*,\\s*");
  }

  /**
   * Validate that a value is positive.
   *
   * @param value the value to validate
   * @param name the name of the parameter for error messages
   * @throws IllegalArgumentException if value is not positive
   */
  public static void validatePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Validate that a value is positive.
   *
   * @param value the value to validate
   * @param name the name of the parameter for error messages
   * @throws IllegalArgumentException if value is not positive
   */
  public static void validatePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Validate that a value is positive.
   *
   * @param value the value to validate
   * @param name the name of the parameter for error messages
   * @throws IllegalArgumentException if value is not positive
   */
  public static void validatePositive(double value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Validate that a string is not null or empty.
   *
   * @param value the string to validate
   * @param name the name of the parameter for error messages
   * @throws IllegalArgumentException if string is null or empty
   */
  public static void validateNotEmpty(String value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " cannot be null or empty");
    }
  }

  /**
   * Validate that a value is within a range (inclusive).
   *
   * @param value the value to validate
   * @param min the minimum allowed value
   * @param max the maximum allowed value
   * @param name the name of the parameter for error messages
   * @throws IllegalArgumentException if value is outside the range
   */
  public static void validateRange(double value, double min, double max, String name) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          name + " must be between " + min + " and " + max + ", got: " + value);
    }
  }

  /**
   * Validate that min value is less than or equal to max value.
   *
   * @param min the minimum value
   * @param max the maximum value
   * @param minName the name of the minimum parameter
   * @param maxName the name of the maximum parameter
   * @throws IllegalArgumentException if min is greater than max
   */
  public static void validateMinMax(int min, int max, String minName, String maxName) {
    if (min > max) {
      throw new IllegalArgumentException(
          minName + " (" + min + ") cannot be greater than " + maxName + " (" + max + ")");
    }
  }

  /**
   * Validate that min value is less than or equal to max value.
   *
   * @param min the minimum value
   * @param max the maximum value
   * @param minName the name of the minimum parameter
   * @param maxName the name of the maximum parameter
   * @throws IllegalArgumentException if min is greater than max
   */
  public static void validateMinMax(double min, double max, String minName, String maxName) {
    if (min > max) {
      throw new IllegalArgumentException(
          minName + " (" + min + ") cannot be greater than " + maxName + " (" + max + ")");
    }
  }
}
