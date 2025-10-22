package com.kneaf.core;

/**
 * Simple Entity interface to replace Minecraft's Entity class for testing purposes.
 * This provides basic entity functionality without Minecraft dependencies.
 */
public interface Entity {
    int getId();
    String getType();
    double getX();
    double getY();
    double getZ();
    
    // Mock methods that would normally be in Minecraft Entity
    default Object level() {
        return null; // Mock implementation - return null for test
    }
    
    default boolean isClientSide() {
        return false; // Mock implementation - not client side
    }
    
    default boolean hasCustomName() {
        return false; // Mock implementation
    }
    
    // Mock movement methods
    default Vec3 getDeltaMovement() {
        return new Vec3(0, 0, 0);
    }
    
    default void setDeltaMovement(double x, double y, double z) {
        // Mock implementation - do nothing
    }
    
    // Mock Player check
    default boolean isPlayer() {
        return false; // Mock implementation
    }
}

/**
 * Simple Vec3 class for movement vectors
 */
class Vec3 {
    public final double x;
    public final double y;
    public final double z;
    
    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}