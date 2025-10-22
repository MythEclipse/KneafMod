package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proper mode detection system for distinguishing between test and production environments.
 * Prevents test logic from interfering with production runtime.
 */
public final class ModeDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModeDetector.class);
    
    public static final String PRODUCTION_MODE = "PRODUCTION";
    public static final String TEST_MODE = "TEST";
    
    private static final String[] TEST_ENVIRONMENT_INDICATORS = {
        "test", "mock", "unit-test", "integration-test", "dev", "development"
    };
    
    private static final String[] PRODUCTION_ENVIRONMENT_INDICATORS = {
        "prod", "production", "live", "release", "server", "game"
    };
    
    private static String currentMode = determineRuntimeMode();
    private static boolean isTestMode = currentMode.equals(TEST_MODE);
    
    private ModeDetector() {}
    
    /**
     * Determine the runtime mode based on environment indicators
     * @return PRODUCTION_MODE or TEST_MODE
     */
    private static String determineRuntimeMode() {
        LOGGER.info("Starting runtime mode detection...");
        
        // Check system properties first
        String modeFromSystem = System.getProperty("kneaf.core.mode");
        if (modeFromSystem != null) {
            String normalizedMode = modeFromSystem.trim().toLowerCase();
            if (isTestIndicator(normalizedMode)) {
                LOGGER.info("Detected TEST mode from system property: kneaf.core.mode={}", modeFromSystem);
                return TEST_MODE;
            } else if (isProductionIndicator(normalizedMode)) {
                LOGGER.info("Detected PRODUCTION mode from system property: kneaf.core.mode={}", modeFromSystem);
                return PRODUCTION_MODE;
            }
        }
        
        // Check environment variables
        String modeFromEnv = System.getenv("KNEAF_CORE_MODE");
        if (modeFromEnv != null) {
            String normalizedMode = modeFromEnv.trim().toLowerCase();
            if (isTestIndicator(normalizedMode)) {
                LOGGER.info("Detected TEST mode from environment variable: KNEAF_CORE_MODE={}", modeFromEnv);
                return TEST_MODE;
            } else if (isProductionIndicator(normalizedMode)) {
                LOGGER.info("Detected PRODUCTION mode from environment variable: KNEAF_CORE_MODE={}", modeFromEnv);
                return PRODUCTION_MODE;
            }
        }
        
        // Check classpath for test indicators
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            String normalizedClasspath = classpath.toLowerCase();
            if (containsTestIndicator(normalizedClasspath)) {
                LOGGER.info("Detected TEST mode from classpath: contains test indicators");
                return TEST_MODE;
            }
        }
        
        // Check for test classes in context
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader != null) {
                // Check for common test libraries
                if (classLoader.getResource("org/junit/") != null) {
                    LOGGER.info("Detected TEST mode: JUnit classes found in classpath");
                    return TEST_MODE;
                }
                if (classLoader.getResource("org/testng/") != null) {
                    LOGGER.info("Detected TEST mode: TestNG classes found in classpath");
                    return TEST_MODE;
                }
            }
        } catch (SecurityException e) {
            LOGGER.debug("Security restriction prevented classpath inspection for test detection", e);
        }
        
        // Default to PRODUCTION if no clear test indicators found
        LOGGER.info("No test indicators found - defaulting to PRODUCTION mode");
        return PRODUCTION_MODE;
    }
    
    private static boolean isTestIndicator(String input) {
        for (String indicator : TEST_ENVIRONMENT_INDICATORS) {
            if (input.contains(indicator)) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean isProductionIndicator(String input) {
        for (String indicator : PRODUCTION_ENVIRONMENT_INDICATORS) {
            if (input.contains(indicator)) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean containsTestIndicator(String classpath) {
        for (String indicator : TEST_ENVIRONMENT_INDICATORS) {
            if (classpath.contains(indicator)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the current runtime mode
     * @return Current mode (PRODUCTION_MODE or TEST_MODE)
     */
    public static String getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Check if we're currently in test mode
     * @return true if in test mode, false otherwise
     */
    public static boolean isTestMode() {
        return isTestMode;
    }
    
    /**
     * Check if we're currently in production mode
     * @return true if in production mode, false otherwise
     */
    public static boolean isProductionMode() {
        return !isTestMode;
    }
    
    /**
     * Force mode for testing purposes only
     * @param mode Mode to force (PRODUCTION_MODE or TEST_MODE)
     */
    public static void forceModeForTesting(String mode) {
        if (!ModeDetector.TEST_MODE.equals(mode) && !ModeDetector.PRODUCTION_MODE.equals(mode)) {
            throw new IllegalArgumentException("Invalid mode: " + mode);
        }
        
        currentMode = mode;
        isTestMode = ModeDetector.TEST_MODE.equals(mode);
        LOGGER.warn("MODE FORCED FOR TESTING: {}", currentMode);
    }
}