package com.kneaf.core.utils;

import java.util.Collection;
import java.util.Map;

/**
 * Utility class for common validation operations.
 * Provides reusable validation methods to eliminate DRY violations.
 */
public final class ValidationUtils {

    private ValidationUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates that an object is not null.
     *
     * @param obj The object to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if object is null
     */
    public static void notNull(Object obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a string is not null or empty.
     *
     * @param str The string to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if string is null or empty
     */
    public static void notEmptyString(String str, String message) {
        if (str == null || str.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a string is not null, empty, and has minimum length.
     *
     * @param str The string to validate
     * @param minLength The minimum required length
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if string is null, empty, or too short
     */
    public static void notEmptyWithMinLength(String str, int minLength, String message) {
        notEmptyString(str, message);
        if (str.length() < minLength) {
            throw new IllegalArgumentException(message + " (minimum length: " + minLength + ")");
        }
    }

    /**
     * Validates that a collection is not null or empty.
     *
     * @param collection The collection to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if collection is null or empty
     */
    public static void notEmptyCollection(Collection<?> collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a map is not null or empty.
     *
     * @param map The map to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if map is null or empty
     */
    public static void notEmpty(Map<?, ?> map, String message) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that an array is not null or empty.
     *
     * @param array The array to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if array is null or empty
     */
    public static void notEmpty(Object[] array, String message) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a number is positive (greater than zero).
     *
     * @param number The number to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if number is not positive
     */
    public static void positive(int number, String message) {
        if (number <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a number is positive (greater than zero).
     *
     * @param number The number to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if number is not positive
     */
    public static void positive(long number, String message) {
        if (number <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a number is non-negative (zero or positive).
     *
     * @param number The number to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if number is negative
     */
    public static void nonNegative(int number, String message) {
        if (number < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a number is non-negative (zero or positive).
     *
     * @param number The number to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if number is negative
     */
    public static void nonNegative(long number, String message) {
        if (number < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a condition is true.
     *
     * @param condition The condition to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if condition is false
     */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a condition is false.
     *
     * @param condition The condition to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if condition is true
     */
    public static void isFalse(boolean condition, String message) {
        if (condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that an object is an instance of the specified class.
     *
     * @param obj The object to validate
     * @param clazz The class to check against
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if object is not an instance of the class
     */
    public static void instanceOf(Object obj, Class<?> clazz, String message) {
        if (obj == null || !clazz.isInstance(obj)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a string matches a regex pattern.
     *
     * @param str The string to validate
     * @param pattern The regex pattern to match
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if string doesn't match the pattern
     */
    public static void matchesPattern(String str, String pattern, String message) {
        if (str == null || !str.matches(pattern)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that a file path is valid (not null, not empty, and has valid format).
     *
     * @param filePath The file path to validate
     * @param message The error message to use if validation fails
     * @throws IllegalArgumentException if file path is invalid
     */
    public static void validFilePath(String filePath, String message) {
        notEmptyString(filePath, message);
        
        // Basic file path validation - adjust as needed for your platform
        if (filePath.contains("..") || filePath.contains("//") || filePath.contains("\\\\")) {
            throw new IllegalArgumentException(message + " - Invalid file path: " + filePath);
        }
    }
}