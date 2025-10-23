package com.kneaf.core.async;

/**
 * Utility class untuk async operations.
 * Provides helper methods untuk non-blocking logging dan metrics.
 */
public final class AsyncUtils {
    
    private AsyncUtils() {
        throw new AssertionError("No instances");
    }
    
    /**
     * Get async logger untuk class
     */
    public static AsyncLogger getLogger(Class<?> clazz) {
        return AsyncLoggerRegistry.getInstance().getLogger(clazz);
    }
    
    /**
     * Get async logger dengan name
     */
    public static AsyncLogger getLogger(String name) {
        return AsyncLoggerRegistry.getInstance().getLogger(name);
    }
    
    /**
     * Shutdown semua async components
     */
    public static void shutdownAll() {
        AsyncLoggerRegistry.getInstance().shutdownAll();
    }
    
    /**
     * Get logger registry statistics
     */
    public static AsyncLoggerRegistry.RegistryStatistics getLoggerStatistics() {
        return AsyncLoggerRegistry.getInstance().getStatistics();
    }
}
