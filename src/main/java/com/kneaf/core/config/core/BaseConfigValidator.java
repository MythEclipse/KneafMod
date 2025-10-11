package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;

/**
 * Base implementation of ConfigValidator that provides common validation functionality.
 */
public abstract class BaseConfigValidator implements ConfigValidator {

    /**
     * Validates that a value is not null.
     *
     * @param value the value to check
     * @param name the name of the value
     * @throws ConfigurationException if the value is null
     */
    protected void validateNotNull(Object value, String name) throws ConfigurationException {
        if (value == null) {
            throw new ConfigurationException(name + " must not be null");
        }
    }

    /**
     * Validates that a string value is not null or empty.
     *
     * @param value the string value to check
     * @param name the name of the value
     * @throws ConfigurationException if the value is null or empty
     */
    protected void validateNotEmpty(String value, String name) throws ConfigurationException {
        validateNotNull(value, name);
        if (value.trim().isEmpty()) {
            throw new ConfigurationException(name + " must not be empty");
        }
    }

    /**
     * Validates that a numeric value is within a specified range (inclusive).
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is out of range
     */
    protected void validateRange(int value, int min, int max, String name) throws ConfigurationException {
        if (value < min || value > max) {
            throw new ConfigurationException(name + " must be between " + min + " and " + max);
        }
    }

    /**
     * Validates that a numeric value is within a specified range (inclusive).
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is out of range
     */
    protected void validateRange(long value, long min, long max, String name) throws ConfigurationException {
        if (value < min || value > max) {
            throw new ConfigurationException(name + " must be between " + min + " and " + max);
        }
    }

    /**
     * Validates that a numeric value is within a specified range (inclusive).
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is out of range
     */
    protected void validateRange(double value, double min, double max, String name) throws ConfigurationException {
        if (value < min || value > max) {
            throw new ConfigurationException(name + " must be between " + min + " and " + max);
        }
    }

    /**
     * Validates that a numeric value is greater than or equal to a minimum value.
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is less than the minimum
     */
    protected void validateMin(int value, int min, String name) throws ConfigurationException {
        if (value < min) {
            throw new ConfigurationException(name + " must be at least " + min);
        }
    }

    /**
     * Validates that a numeric value is greater than or equal to a minimum value.
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is less than the minimum
     */
    protected void validateMin(long value, long min, String name) throws ConfigurationException {
        if (value < min) {
            throw new ConfigurationException(name + " must be at least " + min);
        }
    }

    /**
     * Validates that a numeric value is greater than or equal to a minimum value.
     *
     * @param value the value to check
     * @param min the minimum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is less than the minimum
     */
    protected void validateMin(double value, double min, String name) throws ConfigurationException {
        if (value < min) {
            throw new ConfigurationException(name + " must be at least " + min);
        }
    }

    /**
     * Validates that a numeric value is less than or equal to a maximum value.
     *
     * @param value the value to check
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is greater than the maximum
     */
    protected void validateMax(int value, int max, String name) throws ConfigurationException {
        if (value > max) {
            throw new ConfigurationException(name + " must be at most " + max);
        }
    }

    /**
     * Validates that a numeric value is less than or equal to a maximum value.
     *
     * @param value the value to check
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is greater than the maximum
     */
    protected void validateMax(long value, long max, String name) throws ConfigurationException {
        if (value > max) {
            throw new ConfigurationException(name + " must be at most " + max);
        }
    }

    /**
     * Validates that a numeric value is less than or equal to a maximum value.
     *
     * @param value the value to check
     * @param max the maximum allowed value
     * @param name the name of the value
     * @throws ConfigurationException if the value is greater than the maximum
     */
    protected void validateMax(double value, double max, String name) throws ConfigurationException {
        if (value > max) {
            throw new ConfigurationException(name + " must be at most " + max);
        }
    }
}