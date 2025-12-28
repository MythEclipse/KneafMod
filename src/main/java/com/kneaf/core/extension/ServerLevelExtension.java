package com.kneaf.core.extension;

public interface ServerLevelExtension {
    /**
     * Get cached distance to nearest player for an entity.
     * 
     * @return Distance squared or -1 if not cached.
     */
    double kneaf$getCachedDistance(int entityId);
}
