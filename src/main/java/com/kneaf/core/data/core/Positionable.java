package com.kneaf.core.data.core;

/**
 * Interface for entities that have position coordinates.
 */
public interface Positionable {
    
    /**
     * Gets the X coordinate of this entity.
     * @return the X coordinate
     */
    double getX();
    
    /**
     * Gets the Y coordinate of this entity.
     * @return the Y coordinate
     */
    double getY();
    
    /**
     * Gets the Z coordinate of this entity.
     * @return the Z coordinate
     */
    double getZ();
}