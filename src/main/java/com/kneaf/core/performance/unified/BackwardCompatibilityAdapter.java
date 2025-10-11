package com.kneaf.core.performance.unified;

/**
 * Adapter for backward compatibility with existing performance APIs.
 * Provides a bridge between the new unified system and old interfaces.
 */
public final class BackwardCompatibilityAdapter {
    private static final BackwardCompatibilityAdapter INSTANCE = new BackwardCompatibilityAdapter();
    private final PerformanceManagerImpl unifiedManager;

    /**
     * Private constructor to prevent instantiation.
     */
    private BackwardCompatibilityAdapter() {
        this.unifiedManager = PerformanceManagerImpl.getInstance();
    }

    /**
     * Get the singleton instance of BackwardCompatibilityAdapter.
     *
     * @return singleton instance
     */
    public static BackwardCompatibilityAdapter getInstance() {
        return INSTANCE;
    }

    /**
     * Adapter method for old PerformanceManager.isEnabled().
     *
     * @return true if performance monitoring is enabled
     */
    public static boolean isEnabled() {
        return INSTANCE.unifiedManager.isEnabled();
    }

    /**
     * Adapter method for old PerformanceManager.setEnabled().
     *
     * @param enabled enabled state
     */
    public static void setEnabled(boolean enabled) {
        INSTANCE.unifiedManager.setEnabled(enabled);
    }

    /**
     * Adapter method for old PerformanceManager.getCurrentTPS().
     *
     * @return current TPS
     */
    public static double getCurrentTPS() {
        return INSTANCE.unifiedManager.getCurrentTPS();
    }

    /**
     * Adapter method for old PerformanceManager.getAverageTPS().
     *
     * @return average TPS
     */
    public static double getAverageTPS() {
        return INSTANCE.unifiedManager.getAverageTPS();
    }

    /**
     * Adapter method for old RustPerformance.getCpuStats().
     *
     * @return CPU stats as string
     */
    public static String getCpuStats() {
        // In a real implementation, this would delegate to the unified system
        return "N/A"; // Replace with actual implementation
    }

    /**
     * Adapter method for old RustPerformance.getMemoryStats().
     *
     * @return memory stats as string
     */
    public static String getMemoryStats() {
        // In a real implementation, this would delegate to the unified system
        return "N/A"; // Replace with actual implementation
    }

    /**
     * Adapter method for old PerformanceMetricsLogger.rotateNow().
     */
    public static void rotateLog() {
        INSTANCE.unifiedManager.getPerformanceManager().rotateLog();
    }

    /**
     * Adapter method for old RustPerformance.getTotalEntitiesProcessed().
     *
     * @return total entities processed
     */
    public static long getTotalEntitiesProcessed() {
        // In a real implementation, this would get from unified metrics
        return 0; // Replace with actual implementation
    }

    /**
     * Adapter method for old RustPerformance.getTotalMobsProcessed().
     *
     * @return total mobs processed
     */
    public static long getTotalMobsProcessed() {
        // In a real implementation, this would get from unified metrics
        return 0; // Replace with actual implementation
    }

    /**
     * Adapter method for old RustPerformance.getTotalBlocksProcessed().
     *
     * @return total blocks processed
     */
    public static long getTotalBlocksProcessed() {
        // In a real implementation, this would get from unified metrics
        return 0; // Replace with actual implementation
    }

    /**
     * Adapter method for old RustPerformance.getTotalMerged().
     *
     * @return total merged items
     */
    public static long getTotalMerged() {
        // In a real implementation, this would get from unified metrics
        return 0; // Replace with actual implementation
    }

    /**
     * Adapter method for old RustPerformance.getTotalDespawned().
     *
     * @return total despawned entities
     */
    public static long getTotalDespawned() {
        // In a real implementation, this would get from unified metrics
        return 0; // Replace with actual implementation
    }

    /**
     * Get the underlying unified performance manager.
     *
     * @return unified performance manager
     */
    public PerformanceManagerImpl getUnifiedManager() {
        return unifiedManager;
    }
}