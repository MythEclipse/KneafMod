package com.kneaf.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Proper mode detection system for distinguishing between test and production
 * environments.
 * Prevents test logic from interfering with production runtime.
 * Uses explicit configuration priority system to avoid false positives.
 */
public final class ModeDetector {

    public static final String PRODUCTION_MODE = "PRODUCTION";
    public static final String TEST_MODE = "TEST";

    private static final String[] TEST_ENVIRONMENT_INDICATORS = {
            "test", "mock", "unit-test", "integration-test", "test-classes"
    };

    private static final String[] PRODUCTION_ENVIRONMENT_INDICATORS = {
            "prod", "production", "live", "release", "server", "game", "client"
    };

    // Explicit configuration properties that take highest priority
    private static final String FORCE_PRODUCTION_PROPERTY = "kneaf.core.forceProduction";
    private static final String FORCE_TEST_PROPERTY = "kneaf.core.forceTest";

    private static String currentMode;
    private static boolean isTestMode;

    // Initialize mode detection in a thread-safe manner
    static {
        try {
            currentMode = determineRuntimeMode();
            isTestMode = TEST_MODE.equals(currentMode);
        } catch (Throwable t) {
            // ALWAYS fallback to PRODUCTION mode on any error during initialization
            // This is a critical safety feature - never allow test mode by accident
            currentMode = PRODUCTION_MODE;
            isTestMode = false;
        }
    }

    // Configuration cache for performance
    private static final ConcurrentHashMap<String, Boolean> detectionCache = new ConcurrentHashMap<>();

    private ModeDetector() {
    }

    /**
     * Determine the runtime mode based on environment indicators
     * 
     * @return PRODUCTION_MODE or TEST_MODE
     */
    private static String determineRuntimeMode() {
        // Use cache for performance with simplified key
        String cacheKey = "runtimeMode:" + System.currentTimeMillis(); // Use full timestamp for uniqueness
        if (detectionCache.containsKey(cacheKey)) {
            return detectionCache.get(cacheKey) ? TEST_MODE : PRODUCTION_MODE;
        }

        // PRIORITY 1: Explicit force properties (highest priority)
        String forceProduction = System.getProperty(FORCE_PRODUCTION_PROPERTY);
        String forceTest = System.getProperty(FORCE_TEST_PROPERTY);

        if (forceProduction != null && Boolean.parseBoolean(forceProduction)) {
            detectionCache.put(cacheKey, false);
            return PRODUCTION_MODE;
        }

        if (forceTest != null && Boolean.parseBoolean(forceTest)) {
            detectionCache.put(cacheKey, true);
            return TEST_MODE;
        }

        // PRIORITY 2: Explicit mode properties
        String modeFromSystem = System.getProperty("kneaf.core.mode");
        if (modeFromSystem != null) {
            String normalizedMode = modeFromSystem.trim().toLowerCase();
            if (isTestIndicator(normalizedMode)) {
                detectionCache.put(cacheKey, true);
                return TEST_MODE;
            } else if (isProductionIndicator(normalizedMode)) {
                detectionCache.put(cacheKey, false);
                return PRODUCTION_MODE;
            }
        }

        // PRIORITY 3: Environment variables
        String modeFromEnv = System.getenv("KNEAF_CORE_MODE");
        if (modeFromEnv != null) {
            String normalizedMode = modeFromEnv.trim().toLowerCase();
            if (isTestIndicator(normalizedMode)) {
                detectionCache.put(cacheKey, true);
                return TEST_MODE;
            } else if (isProductionIndicator(normalizedMode)) {
                detectionCache.put(cacheKey, false);
                return PRODUCTION_MODE;
            }
        }

        // PRIORITY 4: Classpath analysis (more conservative)
        String classpath = System.getProperty("java.class.path");
        if (classpath != null) {
            String normalizedClasspath = classpath.toLowerCase();

            // Only consider strong test indicators, not weak ones like "dev"
            boolean hasStrongTestIndicators = normalizedClasspath.contains("test-classes") ||
                    normalizedClasspath.contains("junit") ||
                    normalizedClasspath.contains("testng") ||
                    normalizedClasspath.matches(".*[\\\\/]test[\\\\/].*");

            if (hasStrongTestIndicators) {
                detectionCache.put(cacheKey, true);
                return TEST_MODE;
            }
        }

        // PRIORITY 5: Test library detection (conservative approach)
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader != null) {
                // Only consider explicit test frameworks, not just any test-related classes
                if (classLoader.getResource("org/junit/") != null) {
                    detectionCache.put(cacheKey, true);
                    return TEST_MODE;
                }
                if (classLoader.getResource("org/testng/") != null) {
                    detectionCache.put(cacheKey, true);
                    return TEST_MODE;
                }
            }
        } catch (SecurityException e) {
        }

        // ALWAYS default to PRODUCTION mode when in doubt
        // This is the SAFEST default for production environments - NEVER change this!
        // Production should always be the default unless explicitly configured
        // otherwise
        detectionCache.put(cacheKey, false);
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

    /**
     * Get the current runtime mode
     * 
     * @return Current mode (PRODUCTION_MODE or TEST_MODE)
     */
    public static String getCurrentMode() {
        return currentMode;
    }

    /**
     * Check if we're currently in test mode
     * 
     * @return true if in test mode, false otherwise
     */
    public static boolean isTestMode() {
        return isTestMode;
    }

    /**
     * Check if we're currently in production mode
     * 
     * @return true if in production mode, false otherwise
     */
    public static boolean isProductionMode() {
        return !isTestMode;
    }

    /**
     * Force mode for testing purposes only
     * 
     * @param mode Mode to force (PRODUCTION_MODE or TEST_MODE)
     */
    public static void forceModeForTesting(String mode) {
        if (!ModeDetector.TEST_MODE.equals(mode) && !ModeDetector.PRODUCTION_MODE.equals(mode)) {
            throw new IllegalArgumentException("Invalid mode: " + mode);
        }

        currentMode = mode;
        isTestMode = ModeDetector.TEST_MODE.equals(mode);
    }
}