package com.kneaf.core.chunkstorage.common;

/**
 * Interface for components that provide storage statistics.
 * Implemented by cache, database adapters, swap manager, and main storage manager.
 */
public interface StorageStatisticsProvider {
    
    /**
     * Get storage statistics for monitoring and debugging.
     * 
     * @return Statistics object containing performance metrics
     */
    Object getStats();
}