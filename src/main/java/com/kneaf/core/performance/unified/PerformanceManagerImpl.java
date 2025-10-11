package com.kneaf.core.performance.unified;

/**
 * Singleton implementation of PerformanceManager for unified performance monitoring.
 */
public class PerformanceManagerImpl {
    private static PerformanceManagerImpl instance;
    private final PerformanceManager performanceManager;

    /**
     * Private constructor to prevent instantiation.
     */
    private PerformanceManagerImpl() {
        this.performanceManager = PerformanceManager.getInstance();
    }

    /**
     * Get the singleton instance of PerformanceManagerImpl.
     * @return singleton instance
     */
    public static synchronized PerformanceManagerImpl getInstance() {
        if (instance == null) {
            instance = new PerformanceManagerImpl();
        }
        return instance;
    }

    /**
     * Get the underlying PerformanceManager.
     * @return performance manager
     */
    public PerformanceManager getPerformanceManager() {
        return performanceManager;
    }

    /**
     * Check if performance monitoring is enabled.
     * @return true if enabled
     */
    public boolean isEnabled() {
        return performanceManager.isEnabled();
    }

    /**
     * Set performance monitoring enabled state.
     * @param enabled enabled state
     */
    public void setEnabled(boolean enabled) {
        if (enabled) {
            performanceManager.enable();
        } else {
            performanceManager.disable();
        }
    }

    /**
     * Get current TPS.
     * @return current TPS
     */
    public double getCurrentTPS() {
        return performanceManager.getCurrentTPS();
    }

    /**
     * Get average TPS.
     * @return average TPS
     */
    public double getAverageTPS() {
        return performanceManager.getAverageTPS();
    }

    /**
     * Get used memory percentage.
     * @return used memory percentage
     */
    public double getUsedMemoryPercentage() {
        return performanceManager.getUsedMemoryPercentage();
    }
}