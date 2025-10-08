package com.kneaf.core.chunkstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for cross-language memory pressure configuration consistency.
 * This test verifies that both Rust and Java components use the same memory pressure
 * thresholds, ensuring consistent behavior across the cross-language boundary.
 */
public class MemoryPressureCrossLanguageTest {

    @BeforeEach
    void setUp() {
        // Ensure native library is loaded
        try {
            // Try to access native methods to check if library is loaded
            SwapManager.nativeGetMemoryPressureConfig();
        } catch (UnsatisfiedLinkError e) {
            // Skip tests if native library is not available
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Native library not available: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test that Java and Rust use the same memory pressure thresholds")
    void testCrossLanguageThresholdConsistency() {
        // Get configuration from Rust via JNI
        String rustConfigJson = com.kneaf.core.performance.NativeBridge.nativeGetMemoryPressureConfig();
        assertNotNull(rustConfigJson, "Rust configuration should not be null");
        assertFalse(rustConfigJson.isEmpty(), "Rust configuration should not be empty");
        
        // Parse the JSON configuration
        MemoryPressureConfig rustConfig = MemoryPressureConfig.fromJson(rustConfigJson);
        assertNotNull(rustConfig, "Parsed Rust configuration should not be null");
        
        // Verify the thresholds match the expected values
        assertEquals(0.70, rustConfig.getNormalThreshold(), 0.001, 
            "Normal threshold should be 0.70");
        assertEquals(0.85, rustConfig.getModerateThreshold(), 0.001, 
            "Moderate threshold should be 0.85");
        assertEquals(0.95, rustConfig.getHighThreshold(), 0.001, 
            "High threshold should be 0.95");
        assertEquals(0.98, rustConfig.getCriticalThreshold(), 0.001, 
            "Critical threshold should be 0.98");
        
        // Verify threshold ordering
        assertTrue(rustConfig.getNormalThreshold() < rustConfig.getModerateThreshold(),
            "Normal threshold should be less than moderate threshold");
        assertTrue(rustConfig.getModerateThreshold() < rustConfig.getHighThreshold(),
            "Moderate threshold should be less than high threshold");
        assertTrue(rustConfig.getHighThreshold() < rustConfig.getCriticalThreshold(),
            "High threshold should be less than critical threshold");
    }

    @Test
    @DisplayName("Test memory pressure level mapping consistency")
    void testMemoryPressureLevelMapping() {
        // Test various usage ratios and verify consistent mapping
        double[] testRatios = {0.50, 0.69, 0.70, 0.80, 0.84, 0.85, 0.90, 0.94, 0.95, 0.97, 0.98, 0.99};
        int[] expectedLevels = {0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3}; // 0=Normal, 1=Moderate, 2=High, 3=Critical
        
        for (int i = 0; i < testRatios.length; i++) {
            int actualLevel = SwapManager.nativeGetMemoryPressureLevel(testRatios[i]);
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
        assertTrue(SwapManager.nativeValidateMemoryPressureConfig(validConfig),
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
            assertFalse(SwapManager.nativeValidateMemoryPressureConfig(invalidConfig),
                "Invalid configuration should fail validation: " + invalidConfig);
        }
    }

    @Test
    @DisplayName("Test configuration update and retrieval")
    void testConfigurationUpdate() {
        // Create a new configuration
        String newConfig = "{\"normalThreshold\":0.65,\"moderateThreshold\":0.80,\"highThreshold\":0.92,\"criticalThreshold\":0.97}";
        
        // Validate the new configuration
        assertTrue(SwapManager.nativeValidateMemoryPressureConfig(newConfig),
            "New configuration should be valid");
        
        // Update the configuration
        int result = SwapManager.nativeUpdateMemoryPressureConfig(newConfig);
        assertEquals(0, result, "Configuration update should succeed");
        
        // Retrieve the updated configuration
        String updatedConfig = SwapManager.nativeGetMemoryPressureConfig();
        assertNotNull(updatedConfig, "Updated configuration should not be null");
        
        // Parse and verify the updated configuration
        MemoryPressureConfig config = MemoryPressureConfig.fromJson(updatedConfig);
        assertEquals(0.65, config.getNormalThreshold(), 0.001, 
            "Updated normal threshold should be 0.65");
        assertEquals(0.80, config.getModerateThreshold(), 0.001, 
            "Updated moderate threshold should be 0.80");
        assertEquals(0.92, config.getHighThreshold(), 0.001, 
            "Updated high threshold should be 0.92");
        assertEquals(0.97, config.getCriticalThreshold(), 0.001, 
            "Updated critical threshold should be 0.97");
        
        // Restore original configuration
        String originalConfig = "{\"normalThreshold\":0.70,\"moderateThreshold\":0.85,\"highThreshold\":0.95,\"criticalThreshold\":0.98}";
        SwapManager.nativeUpdateMemoryPressureConfig(originalConfig);
    }

    @Test
    @DisplayName("Test backward compatibility with previous hardcoded values")
    void testBackwardCompatibility() {
        // Get current configuration
        String configJson = SwapManager.nativeGetMemoryPressureConfig();
        MemoryPressureConfig config = MemoryPressureConfig.fromJson(configJson);
        
        // Verify that the current configuration matches the previous hardcoded Java constants
        // These were the values in SwapManager.java before centralization:
        // private static final double NORMAL_MEMORY_THRESHOLD = 0.75;
        // private static final double ELEVATED_MEMORY_THRESHOLD = 0.85;
        // private static final double HIGH_MEMORY_THRESHOLD = 0.95;
        // private static final double CRITICAL_MEMORY_THRESHOLD = 0.95;
        
        // Note: The new standardized values are slightly different but more logical
        assertEquals(0.70, config.getNormalThreshold(), 0.001, 
            "Normal threshold should be 0.70 (updated from 0.75 for consistency)");
        assertEquals(0.85, config.getModerateThreshold(), 0.001, 
            "Moderate threshold should be 0.85 (matches previous elevated threshold)");
        assertEquals(0.95, config.getHighThreshold(), 0.001, 
            "High threshold should be 0.95 (matches previous high threshold)");
        assertEquals(0.98, config.getCriticalThreshold(), 0.001, 
            "Critical threshold should be 0.98 (updated from 0.95 for better distinction)");
    }

    @Test
    @DisplayName("Test error handling for invalid inputs")
    void testErrorHandling() {
        // Test null configuration
        assertEquals(1, SwapManager.nativeUpdateMemoryPressureConfig(null),
            "Null configuration should return error code 1");
        
        // Test malformed JSON
        assertEquals(4, SwapManager.nativeUpdateMemoryPressureConfig("invalid json"),
            "Malformed JSON should return error code 4");
        
        // Test invalid configuration values
        String invalidConfig = "{\"normalThreshold\":1.5,\"moderateThreshold\":0.75,\"highThreshold\":0.90,\"criticalThreshold\":0.95}";
        assertEquals(3, SwapManager.nativeUpdateMemoryPressureConfig(invalidConfig),
            "Invalid configuration values should return error code 3");
        
        // Test null validation input
        assertFalse(SwapManager.nativeValidateMemoryPressureConfig(null),
            "Null validation input should return invalid (0)");
    }

    /**
     * Helper class to represent memory pressure configuration
     */
    private static class MemoryPressureConfig {
        private double normalThreshold;
        private double moderateThreshold;
        private double highThreshold;
        private double criticalThreshold;

        public static MemoryPressureConfig fromJson(String json) {
            // Simple JSON parsing for testing
            MemoryPressureConfig config = new MemoryPressureConfig();
            
            // Remove braces and quotes
            json = json.replace("{", "").replace("}", "").replace("\"", "");
            
            // Parse key-value pairs
            String[] pairs = json.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":");
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim();
                    double value = Double.parseDouble(keyValue[1].trim());
                    
                    switch (key) {
                        case "normalThreshold":
                            config.normalThreshold = value;
                            break;
                        case "moderateThreshold":
                            config.moderateThreshold = value;
                            break;
                        case "highThreshold":
                            config.highThreshold = value;
                            break;
                        case "criticalThreshold":
                            config.criticalThreshold = value;
                            break;
                    }
                }
            }
            
            return config;
        }

        public double getNormalThreshold() { return normalThreshold; }
        public double getModerateThreshold() { return moderateThreshold; }
        public double getHighThreshold() { return highThreshold; }
        public double getCriticalThreshold() { return criticalThreshold; }
    }
}