package com.kneaf.core.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ValidationUtils class.
 */
class ValidationUtilsTest {

    @Test
    void testNotNullWithValidObject() {
        // Should not throw exception
        ValidationUtils.notNull("test", "Message");
        ValidationUtils.notNull(new Object(), "Message");
    }

    @Test
    void testNotNullWithNullObject() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.notNull(null, "Object cannot be null")
        );
        assertEquals("Object cannot be null", exception.getMessage());
    }

    @Test
    void testNotEmptyWithValidString() {
        // Should not throw exception
        ValidationUtils.notEmptyString("test", "Message");
        ValidationUtils.notEmptyString("  test  ", "Message");
    }

    @Test
    void testNotEmptyWithNullString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.notEmptyString(null, "String cannot be null")
        );
        assertEquals("String cannot be null", exception.getMessage());
    }

    @Test
    void testNotEmptyWithEmptyString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.notEmptyString("", "String cannot be empty")
        );
        assertEquals("String cannot be empty", exception.getMessage());
    }

    @Test
    void testNotEmptyWithWhitespaceString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.notEmptyString("   ", "String cannot be empty")
        );
        assertEquals("String cannot be empty", exception.getMessage());
    }

    @Test
    void testNotEmptyWithMinLengthValid() {
        // Should not throw exception
        ValidationUtils.notEmptyWithMinLength("test", 3, "Message");
        ValidationUtils.notEmptyWithMinLength("longer", 5, "Message");
    }

    @Test
    void testNotEmptyWithMinLengthTooShort() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.notEmptyWithMinLength("ab", 3, "String too short")
        );
        assertEquals("String too short (minimum length: 3)", exception.getMessage());
    }

    @Test
    void testPositiveWithValidNumbers() {
        // Should not throw exception
        ValidationUtils.positive(1, "Message");
        ValidationUtils.positive(100, "Message");
        ValidationUtils.positive(1L, "Message");
        ValidationUtils.positive(100L, "Message");
    }

    @Test
    void testPositiveWithZero() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.positive(0, "Number must be positive")
        );
        assertEquals("Number must be positive", exception.getMessage());
    }

    @Test
    void testPositiveWithNegative() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.positive(-5, "Number must be positive")
        );
        assertEquals("Number must be positive", exception.getMessage());
    }

    @Test
    void testNonNegativeWithValidNumbers() {
        // Should not throw exception
        ValidationUtils.nonNegative(0, "Message");
        ValidationUtils.nonNegative(1, "Message");
        ValidationUtils.nonNegative(100, "Message");
        ValidationUtils.nonNegative(0L, "Message");
        ValidationUtils.nonNegative(100L, "Message");
    }

    @Test
    void testNonNegativeWithNegative() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.nonNegative(-5, "Number must be non-negative")
        );
        assertEquals("Number must be non-negative", exception.getMessage());
    }

    @Test
    void testIsTrueWithTrueCondition() {
        // Should not throw exception
        ValidationUtils.isTrue(true, "Message");
    }

    @Test
    void testIsTrueWithFalseCondition() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.isTrue(false, "Condition must be true")
        );
        assertEquals("Condition must be true", exception.getMessage());
    }

    @Test
    void testIsFalseWithFalseCondition() {
        // Should not throw exception
        ValidationUtils.isFalse(false, "Message");
    }

    @Test
    void testIsFalseWithTrueCondition() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.isFalse(true, "Condition must be false")
        );
        assertEquals("Condition must be false", exception.getMessage());
    }

    @Test
    void testInstanceOfWithValidInstance() {
        // Should not throw exception
        ValidationUtils.instanceOf("test", String.class, "Message");
        ValidationUtils.instanceOf(Integer.valueOf(5), Integer.class, "Message");
    }

    @Test
    void testInstanceOfWithNullObject() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.instanceOf(null, String.class, "Object must be instance")
        );
        assertEquals("Object must be instance", exception.getMessage());
    }

    @Test
    void testInstanceOfWithWrongType() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.instanceOf("test", Integer.class, "Object must be instance")
        );
        assertEquals("Object must be instance", exception.getMessage());
    }

    @Test
    void testMatchesPatternWithValidString() {
        // Should not throw exception
        ValidationUtils.matchesPattern("abc123", "[a-z0-9]+", "Message");
        ValidationUtils.matchesPattern("test@email.com", "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$", "Message");
    }

    @Test
    void testMatchesPatternWithNullString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.matchesPattern(null, "[a-z]+", "String must match pattern")
        );
        assertEquals("String must match pattern", exception.getMessage());
    }

    @Test
    void testMatchesPatternWithInvalidString() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.matchesPattern("ABC", "[a-z]+", "String must match pattern")
        );
        assertEquals("String must match pattern", exception.getMessage());
    }

    @Test
    void testValidFilePathWithValidPath() {
        // Should not throw exception
        ValidationUtils.validFilePath("config/settings.properties", "Message");
        ValidationUtils.validFilePath("data/file.txt", "Message");
    }

    @Test
    void testValidFilePathWithNullPath() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.validFilePath(null, "Path cannot be null")
        );
        assertEquals("Path cannot be null", exception.getMessage());
    }

    @Test
    void testValidFilePathWithInvalidPath() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ValidationUtils.validFilePath("config/../secrets.txt", "Invalid file path")
        );
        assertTrue(exception.getMessage().contains("Invalid file path"));
    }
}