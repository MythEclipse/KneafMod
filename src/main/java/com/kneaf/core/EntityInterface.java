package com.kneaf.core;

/**
 * Common interface for all entity types that can be processed by the service.
 * This allows both real Minecraft entities and mock entities to be used interchangeably.
 */
public interface EntityInterface {
    /**
     * Get unique entity ID
     */
    long getId();
    
    /**
     * Get X coordinate
     */
    double getX();
    
    /**
     * Get Y coordinate
     */
    double getY();
    
    /**
     * Get Z coordinate
     */
    double getZ();
}