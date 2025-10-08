package com.kneaf.core.chunkstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for memory pressure configuration functionality.
 * This test verifies the centralized configuration system works correctly.
 */
public class MemoryPressureConfigTest {

    @BeforeEach
    void setUp() {
        // Ensure native library is loaded
        try {
            // Try to access native methods to check if library is available
            com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureConfig();
        } catch (UnsatisfiedLinkError e) {
            // Skip tests if native library is not available
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Native library not available: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test that memory pressure configuration can be retrieved")
    void testMemoryPressureConfigRetrieval() {
        // Get configuration from Rust via JNI
        String rustConfigJson = com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureConfig();
        assertNotNull(rustConfigJson, "Rust configuration should not be null");
        assertFalse(rustConfigJson.isEmpty(), "Rust configuration should not be empty");
        
        // Verify it's valid JSON format
        assertTrue(rustConfigJson.contains("normalThreshold"), "Configuration should contain normalThreshold");
        assertTrue(rustConfigJson.contains("moderateThreshold"), "Configuration should contain moderateThreshold");
        assertTrue(rustConfigJson.contains("highThreshold"), "Configuration should contain highThreshold");
        assertTrue(rustConfigJson.contains("criticalThreshold"), "Configuration should contain criticalThreshold");
    }

    @Test
    @DisplayName("Test memory pressure level mapping")
    void testMemoryPressureLevelMapping() {
        // Test various usage ratios and verify consistent mapping
        double[] testRatios = {0.50, 0.69, 0.70, 0.80, 0.84, 0.85, 0.90, 0.94, 0.95, 0.97, 0.98, 0.99};
        int[] expectedLevels = {0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3}; // 0=Normal, 1=Moderate, 2=High, 3=Critical
        
        for (int i = 0; i < testRatios.length; i++) {
            int actualLevel = com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureLevel(testRatios[i]);
            assertEquals(expectedLevels[i], actualLevel, 
                String.format("Usage ratio %.2f should map to level %d, got %d", 
                    testRatios[i], expectedLevels[i], actualLevel));
        }
    }

    @Test
    @DisplayName("Test configuration validation")
    void testConfigurationValidation() {
        // Test valid configuration
        String validConfig = "{\"normalThreshold\":0.60,\"moderateThreshold\":0.75,\"highThreshold\":0.90,\"criticalThreshold\":0.95}";
        assertTrue(com.kneaf.core.performance.NativeBridge.nativeValidateMemoryPressureConfig(validConfig),
            "Valid configuration should pass validation");
        
        // Test invalid configurations
        String[] invalidConfigs = {
            // Normal >= Moderate
            "{\"normalThreshold\":0.80,\"moderateThreshold\":0.75,\"highThreshold\":0.90,\"criticalThreshold\":0.95}",
            // Moderate >= High
            "{\"normalThreshold\":0.60,\"moderateThreshold\":0.90,\"highThreshold\":0.85,\"criticalThreshold\":0.95}",
            // High >= Critical
            "{\"normalThreshold\":0.60,\"moderateThreshold\":0.75,\"highThreshold\":0.95,\"criticalThreshold\":0.90}"
        };
        
        for (String invalidConfig : invalidConfigs) {
            assertFalse(com.kneaf.core.performance.NativeBridge.nativeValidateMemoryPressureConfig(invalidConfig),
                "Invalid configuration should fail validation: " + invalidConfig);
        }
    }

    @Test
    @DisplayName("Test configuration update and retrieval")
    void testConfigurationUpdate() {
        // Create a new configuration
        String newConfig = "{\"normalThreshold\":0.65,\"moderateThreshold\":0.80,\"highThreshold\":0.92,\"criticalThreshold\":0.97}";
        
        // Validate the new configuration
        assertTrue(com.kneaf.core.performance.NativeBridge.nativeValidateMemoryPressureConfig(newConfig),
            "New configuration should be valid");
        
        // Update the configuration
        int result = com.kneaf.core.performance.NativeBridge.nativeUpdateMemoryPressureConfig(newConfig);
        assertEquals(0, result, "Configuration update should succeed");
        
        // Retrieve the updated configuration
        String updatedConfig = com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureConfig();
        assertNotNull(updatedConfig, "Updated configuration should not be null");
        
        // Verify the updated configuration contains the new values
        assertTrue(updatedConfig.contains("0.65"), "Updated config should contain new normal threshold");
        assertTrue(updatedConfig.contains("0.80"), "Updated config should contain new moderate threshold");
        assertTrue(updatedConfig.contains("0.92"), "Updated config should contain new high threshold");
        assertTrue(updatedConfig.contains("0.97"), "Updated config should contain new critical threshold");
        
        // Restore original configuration
        String originalConfig = "{\"normalThreshold\":0.70,\"moderateThreshold\":0.85,\"highThreshold\":0.95,\"criticalThreshold\":0.98}";
        com.kneaf.core.performance.NativeBridge.nativeUpdateMemoryPressureConfig(originalConfig);
    }

    @Test
    @DisplayName("Test backward compatibility with previous hardcoded values")
    void testBackwardCompatibility() {
        // Get current configuration
        String configJson = com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureConfig();
        
        // Verify that the current configuration is reasonable
        assertTrue(configJson.contains("0.70"), "Configuration should contain normal threshold 0.70");
        assertTrue(configJson.contains("0.85"), "Configuration should contain moderate threshold 0.85");
        assertTrue(configJson.contains("0.95"), "Configuration should contain high threshold 0.95");
        assertTrue(configJson.contains("0.98"), "Configuration should contain critical threshold 0.98");
    }

    @Test
    @DisplayName("Test error handling for invalid inputs")
    void testErrorHandling() {
        // Test null configuration
        assertEquals(1, com.kneaf.core.performance.NativeBridge.nativeUpdateMemoryPressureConfig(null),
            "Null configuration should return error code 1");
        
        // Test malformed JSON
        assertEquals(4, com.kneaf.core.performance.NativeBridge.nativeUpdateMemoryPressureConfig("invalid json"),
            "Malformed JSON should return error code 4");
        
        // Test invalid configuration values
        String invalidConfig = "{\"normalThreshold\":1.5,\"moderateThreshold\":0.75,\"highThreshold\":0.90,\"criticalThreshold\":0.95}";
        assertEquals(3, com.kneaf.core.performance.NativeBridge.nativeUpdateMemoryPressureConfig(invalidConfig),
            "Invalid configuration values should return error code 3");
        
        // Test null validation input
        assertFalse(com.kneaf.core.performance.NativeBridge.nativeValidateMemoryPressureConfig(null),
            "Null validation input should return invalid (false)");
    }
}