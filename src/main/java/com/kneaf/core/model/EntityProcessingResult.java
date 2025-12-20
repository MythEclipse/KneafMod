package com.kneaf.core.model;

/**
 * Entity processing result
 */
public class EntityProcessingResult {
    public final boolean success;
    public final String message;
    public final EntityPhysicsData processedData;

    public EntityProcessingResult(boolean success, String message, EntityPhysicsData processedData) {
        this.success = success;
        this.message = message;
        this.processedData = processedData;
    }
}
